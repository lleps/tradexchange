import asyncio
from timeit import default_timer as timer

import numpy
import tensorflow as tf
import websockets
import sys
from tensorflow.python.platform import gfile

# global state
arr = None
sess = None
in_tensor = None
out_tensor = None
in_dict = None


# used to convert from keras to tensorflow model
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


def process(msg, content):
    # load:/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.h5
    # predict:0.9,0.2|0.4,0.2|0.01,0.19
    global arr
    global sess
    global in_tensor
    global out_tensor

    if msg == "load":
        if sess is not None:
            sess.close()

        # load keras model
        tf.keras.backend.set_learning_phase(0)
        keras_model = tf.keras.models.load_model(content)
        in_tensor_name = keras_model.input.name
        out_tensor_name = keras_model.output.name

        # save as tensorflow model
        frozen_graph = freeze_session(tf.keras.backend.get_session(),
                                      output_names=[out.op.name for out in keras_model.outputs])
        tf.train.write_graph(frozen_graph, "log", "tmp_tf_model.pb", as_text=False)

        # load it
        sess = tf.Session()
        f = gfile.FastGFile("./log/tmp_tf_model.pb", 'rb')
        graph_def = tf.GraphDef()
        graph_def.ParseFromString(f.read())
        f.close()
        sess.graph.as_default()
        tf.import_graph_def(graph_def)

        # get tensors
        in_tensor = sess.graph.get_tensor_by_name('import/' + in_tensor_name)
        out_tensor = sess.graph.get_tensor_by_name('import/' + out_tensor_name)
        return "ok"

    elif msg == "predict":
        if sess is None:
            return "session not initialized"

        # content format: f00,f01,f02|f10,f11,f12.
        # convert content to a numpy array shape (timesteps, features)
        lines = content.split('|')

        if arr is None:
            timesteps = len(lines)
            features_per_line = len(lines[0].split(','))
            arr = numpy.zeros((1, timesteps, features_per_line), dtype=float)

        for i in range(len(lines)):
            features = lines[i].split(',')
            for j in range(len(features)):
                arr[0, i, j] = float(features[j])

        result = sess.run(out_tensor, {in_tensor: arr})
        return str(result[0][0])


async def handle_request(socket, _):
    while True:
        # receive and parse msg
        msg = await socket.recv()
        if msg == "bye":
            break

        msgtype, content = msg.split(':', 1)
        await socket.send(process(msgtype, content))


if __name__ == '__main__':
    #process("load", "/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.h5")
    #result = process("predict", "0.9,0.2|0.4,0.2|0.01,0.19")
    #print(result)
    if len(sys.argv) != 3:
        print(sys.argv[0], "<host> <port>")
        exit(1)

    start_server = websockets.serve(handle_request, sys.argv[1], int(sys.argv[2]))
    asyncio.get_event_loop().run_until_complete(start_server)

    print("listen at", sys.argv[1]+":"+sys.argv[2])
    sys.stdout.flush()

    asyncio.get_event_loop().run_forever()
