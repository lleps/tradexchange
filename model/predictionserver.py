import asyncio
import sys
import numpy
import tensorflow as tf
import json
import websockets

# global state, for both buy and sell models
sess_buy, sess_sell = None, None
in_tensor_buy, in_tensor_sell = None, None
out_tensor_buy, out_tensor_sell = None, None


## problema: al leer el grafo, no tiene operaciones.
## se puede isolar.
## capaz que no tiene el formato correcto.. o no se.
## 1. verificar que se exporte el .pb, chequear que no este vacio y que sea ese.
## 2. isolar el cargado, para descartar problema del grafo.
## 3. una vez arreglado, unir al codigo principal


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
            return "sess_buy not initialized"

        return str(do_prediction(content, sess_buy, in_tensor_buy, out_tensor_buy))

    elif msg == "sell_predict":
        if sess_sell is None:
            return "sess_sell not initialized"

        return str(do_prediction(content, sess_sell, in_tensor_sell, out_tensor_sell))


async def handle_request(socket, _):
    while True:
        msg = await socket.recv()
        if msg == "bye":
            break

        msg_type, content = msg.split(':', 1)
        await socket.send(process(msg_type, content))


if __name__ == '__main__':
    # print(process("buy_load", "/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.pb"))
    # print(process("sell_load", "/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.pb"))
    # print(process("buy_predict", "0.9,0.2|0.4,0.2|0.01,0.19"))
    # print(process("sell_predict", "0.9,0.2|0.4,0.2|0.01,0.19"))
    # #print(result)

    if len(sys.argv) != 3:
        print(sys.argv[0], "<host> <port>")
        exit(1)

    start_server = websockets.serve(handle_request, sys.argv[1], int(sys.argv[2]))
    asyncio.get_event_loop().run_until_complete(start_server)

    print("listen at", sys.argv[1] + ":" + sys.argv[2])
    sys.stdout.flush()

    asyncio.get_event_loop().run_forever()
