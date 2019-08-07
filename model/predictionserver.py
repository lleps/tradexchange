import asyncio
import sys
import numpy
import tensorflow as tf
import json
import websockets
from pandas import DataFrame
from pandas import concat
from timeit import default_timer as timer

# global state, for both buy and sell models
sess_buy, sess_sell = None, None
in_tensor_buy, in_tensor_sell = None, None
out_tensor_buy, out_tensor_sell = None, None
train_sess, train_model, train_X, train_y = None, None, None, None


def freeze_session(session, keep_var_names=None, output_names=None, clear_devices=True):
    from tf_graph_util import convert_variables_to_constants
    graph = session.graph
    with graph.as_default():
        freeze_var_names = list(set(v.op.name for v in tf.global_variables()).difference(keep_var_names or []))
        output_names = output_names or []
        output_names += [v.op.name for v in tf.global_variables()]
        # Graph -> GraphDef ProtoBuf
        input_graph_def = graph.as_graph_def()
        if clear_devices:
            for node in input_graph_def.node:
                node.device = ""
        frozen_graph = convert_variables_to_constants(session, input_graph_def,
                                                      output_names, freeze_var_names)
        return frozen_graph


def join_past_rows_features(data, past_rows=1, future_rows=1, dropnan=True):
    """
    For each row, join the features since past_rows up to future_rows, so
    it may be reshaped for a recurrent NN input.
    """

    n_vars = 1 if type(data) is list else data.shape[1]
    df = DataFrame(data)
    cols, names = list(), list()
    # input sequence (t-n, ... t-1)
    for i in range(past_rows, 0, -1):
        cols.append(df.shift(i))
        names += [('var%d(t-%d)' % (j + 1, i)) for j in range(n_vars)]
    # forecast sequence (t, t+1, ... t+n)
    for i in range(0, future_rows):
        cols.append(df.shift(-i))
        if i == 0:
            names += [('var%d(t)' % (j + 1)) for j in range(n_vars)]
        else:
            names += [('var%d(t+%d)' % (j + 1, i)) for j in range(n_vars)]

    # put it all together
    agg = concat(cols, axis=1)
    agg.columns = names
    # drop rows with NaN values
    if dropnan:
        agg.dropna(inplace=True)
    return agg


def load_model(path):
    """Load tensorflow .pb model from path, return it as a tf (session, in_tensor, out_tensor) ready to predict."""

    graph = tf.Graph()
    sess = tf.Session(graph=graph)
    with graph.as_default():
        # load model
        from tensorflow.python.platform import gfile
        with gfile.FastGFile(path, 'rb') as f:
            graph_def = tf.GraphDef()
            graph_def.ParseFromString(f.read())
            tf.import_graph_def(graph_def)

        # load .json meta file, which contains the tensor names for input/output
        with open(path + "_meta.json") as f:
            meta = json.load(f)

        asd = [n.name for n in sess.graph.as_graph_def().node]
        print(asd)

        in_tensor = graph.get_tensor_by_name("import/" + meta["input_name"])
        out_tensor = graph.get_tensor_by_name("import/" + meta["output_name"])
        return sess, in_tensor, out_tensor


def do_prediction(query, session, tensor_in, tensor_out):
    """Returns a prediction (float) on the given session, parsing the input received from 'predict:' query."""

    lines = query.split('|')
    timesteps = len(lines)
    features_per_line = len(lines[0].split(','))
    array = numpy.zeros((1, timesteps, features_per_line), dtype=float)

    for i in range(timesteps):
        features = lines[i].split(',')
        for j in range(features_per_line):
            array[0, i, j] = float(features[j])

    res = session.run(tensor_out, {tensor_in: array})
    return res[0][0]


def process(msg, content):
    global sess_buy, sess_sell
    global in_tensor_buy, in_tensor_sell
    global out_tensor_buy, out_tensor_sell
    global train_sess, train_model, train_X, train_y

    # train
    if msg == "train_init": # to prepare the data and build the model architecture, based on the given timesteps
        tf.keras.backend.clear_session()

        params = content.split(",", 2)
        csv_path = params[0]
        timesteps = int(params[1])

        # load csv into x and y
        print("loading txt...")
        dataset = numpy.loadtxt(csv_path, delimiter=",")
        x = dataset[:, 1:-1] # remove price column (the first) and output column (the last)
        feature_count = x[0].size
        y = dataset[:, -1:]

        # on each row, include the features from the previous (timestep - 1) rows.
        # so, will end having feature_count*timesteps features per row.
        x = join_past_rows_features(x, past_rows=timesteps - 1).values  # from t(timesteps-1) to t(0)
        x = x.reshape((x.shape[0], timesteps, feature_count))
        y = y[timesteps - 1:] # remove first _timesteps_ entries, to keep sample count in sync with x
        print("data preprocessing done. shape:", x.shape)

        # build the model
        train_sess = tf.Session()
        model = tf.keras.models.Sequential()
        model.add(tf.keras.layers.GRU(32, input_shape=(timesteps, feature_count)))
        model.add(tf.keras.layers.Dropout(0.2))
        model.add(tf.keras.layers.Dense(32, activation='relu'))
        model.add(tf.keras.layers.Dense(1, activation='sigmoid'))
        model.compile(loss='binary_crossentropy', optimizer='adam', metrics=['accuracy'])
        print("model compiled.")

        # save to global state
        train_model = model
        train_X = x
        train_y = y
        return "ok"

    elif msg == "train_fit":
        if train_model is None:
            return "error: model not initialized"

        params = content.split(",", 2)
        epochs = int(params[0])
        batch_size = int(params[1])
        start = timer()
        train_model.fit(train_X, train_y, epochs=epochs, batch_size=batch_size, verbose=2)
        scores = train_model.evaluate(train_X, train_y)
        time = timer() - start
        return "ok: loss: %f, acc: %f, time: %f)" % (scores[0], scores[1], time)

    elif msg == "train_save": # :path
        if train_model is None:
            return "error: model not initialized"

        pb_path = content
        tensor_in_name = train_model.input.name
        tensor_out_name = train_model.output.name
        with open(pb_path + "_meta.json", 'w') as f:
            json.dump({'input_name': tensor_in_name, 'output_name': tensor_out_name}, f)

        frozen_graph = freeze_session(tf.keras.backend.get_session(),
                                      output_names=[out.op.name for out in train_model.outputs])
        tf.train.write_graph(frozen_graph, ".", pb_path, as_text=False)
        return "ok"

    # load
    if msg == "buy_load":
        if sess_buy is not None:
            sess_buy.close()

        sess_buy, in_tensor_buy, out_tensor_buy = load_model(content)
        return "ok"

    elif msg == "sell_load":
        if sess_sell is not None:
            sess_sell.close()

        sess_sell, in_tensor_sell, out_tensor_sell = load_model(content)
        return "ok"

    # predict
    elif msg == "buy_predict":
        if sess_buy is None:
            return "error: sess_buy not initialized"

        return str(do_prediction(content, sess_buy, in_tensor_buy, out_tensor_buy))

    elif msg == "sell_predict":
        if sess_sell is None:
            return "error: sess_sell not initialized"

        return str(do_prediction(content, sess_sell, in_tensor_sell, out_tensor_sell))


async def handle_request(socket, _):
    while True:
        msg = await socket.recv()
        if msg == "bye":
            break

        msg_type, content = msg.split(':', 1)
        await socket.send(process(msg_type, content))


if __name__ == '__main__':
    ## test loading and predicting
    # print(process("buy_load", "/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.pb"))
    # print(process("sell_load", "/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.pb"))
    # print(process("buy_predict", "0.9,0.2|0.4,0.2|0.01,0.19"))
    # print(process("sell_predict", "0.9,0.2|0.4,0.2|0.01,0.19"))

    ## test training and saving
    # print(process("train_init", "/media/lleps/Compartido/Dev/tradexchange/data/trainings/[train]besttrainever.csv,4"))
    # print(process("train_fit", "10,32"))
    # print(process("train_save", "/home/lleps/some.pb"))
    # print(process("train_init", "/media/lleps/Compartido/Dev/tradexchange/data/trainings/[train]besttrainever.csv,8"))
    # print(process("train_fit", "10,32"))
    # print(process("train_save", "some2.pb"))

    if len(sys.argv) != 3:
        print(sys.argv[0], "<host> <port>")
        exit(1)

    start_server = websockets.serve(handle_request, sys.argv[1], int(sys.argv[2]))
    asyncio.get_event_loop().run_until_complete(start_server)

    print("listen at", sys.argv[1] + ":" + sys.argv[2])
    sys.stdout.flush()

    asyncio.get_event_loop().run_forever()
