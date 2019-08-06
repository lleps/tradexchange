import numpy
import tensorflow as tf
import pandas as pd
import sys
import json
from pandas import DataFrame
from pandas import concat


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


# For each row, join the features since past_rows up to future_rows, so
# it may be reshaped for a recurrent NN input.
def join_rows_by_time(data, past_rows=1, future_rows=1, dropnan=True):
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


if len(sys.argv) < 6:
    print("Required input: <epochs> <batch size> <timesteps> <csv file> <output model>")
    exit(1)

pd.set_option('display.max_columns', None)
pd.set_option('display.max_rows', None)
epochs = int(sys.argv[1])
batch_size = int(sys.argv[2])
timesteps = int(sys.argv[3])
input_csv = sys.argv[4]
output_model = sys.argv[5]

print("loading txt...")
dataset = numpy.loadtxt(input_csv, delimiter=",")
feature_count = dataset[0].size - 1
num_timesteps = timesteps

print("joining by time...")
X = dataset[:, 1:-1]  # ignore price
y = dataset[:, -1:]
X_joined = join_rows_by_time(X, past_rows=num_timesteps - 1).values  # from t(timesteps-1) to t(0)
X_joined = X_joined.reshape((X_joined.shape[0], num_timesteps, int(X_joined.shape[1] / num_timesteps)))

# back to X and Y
X = X_joined
y = y[num_timesteps - 1:]  # remove first num_timesteps entries to sync size with X_joined
print("len(X):", X.shape[0], "len(y):", len(y))
print("Shape of X:", X.shape)

# config
rnn_type = 'gru'  # or gru

# build layers
print("building layers...")
model = tf.keras.models.Sequential()
if rnn_type == 'lstm':
    model.add(tf.keras.layers.LSTM(32, input_shape=(X.shape[1], X.shape[2])))
elif rnn_type == 'gru':
    model.add(tf.keras.layers.GRU(32, input_shape=(X.shape[1], X.shape[2])))
else:
    print("invalid rnn_type:", rnn_type)
    exit(1)
model.add(tf.keras.layers.Dropout(0.2))
model.add(tf.keras.layers.Dense(32, activation='relu'))
model.add(tf.keras.layers.Dense(1, activation='sigmoid'))

# compile model
print("compiling and training...")
model.compile(loss='binary_crossentropy', optimizer='adam', metrics=['accuracy'])
model.fit(X, y, epochs=epochs, batch_size=batch_size, verbose=2)

scores = model.evaluate(X, y)
print("done!")
print("\n%s: %.2f%%" % (model.metrics_names[0], scores[0] * 100))
print("%s: %.2f%%" % (model.metrics_names[1], scores[1] * 100))

# save model meta
tensor_in_name = model.input.name
tensor_out_name = model.output.name
print("save meta file...")
with open(output_model + "_meta.json", 'w') as f:
    json.dump({'input_name': tensor_in_name, 'output_name': tensor_out_name}, f)

# save model pb
print("save pb file...")
frozen_graph = freeze_session(tf.keras.backend.get_session(),
                              output_names=[out.op.name for out in model.outputs])
tf.train.write_graph(frozen_graph, ".", output_model, as_text=False)
print("all ok!")
