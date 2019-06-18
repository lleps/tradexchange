import numpy
import tensorflow as tf
import pandas as pd
import sys
from pandas import read_csv
from pandas import DataFrame
from pandas import concat
from sklearn import preprocessing


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


if len(sys.argv) < 3:
    print("Required input: <csv file> <output model>")
    exit(1)

pd.set_option('display.max_columns', None)
pd.set_option('display.max_rows', None)
input_csv = sys.argv[1]
output_model = sys.argv[2]

dataset = numpy.loadtxt(input_csv, delimiter=",")
feature_count = dataset[0].size - 1
num_timesteps = 7

X = dataset[:, 1:-1] # ignore price
y = dataset[:, -1:]
X_joined = join_rows_by_time(X, past_rows=num_timesteps - 1).values  # from t(timesteps-1) to t(0)
X_joined = X_joined.reshape((X_joined.shape[0], num_timesteps, int(X_joined.shape[1] / num_timesteps)))

# back to X and Y
X = X_joined
y = y[num_timesteps - 1:] # remove first num_timesteps entries to sync size with X_joined
print("len(X):", X.shape[0], "len(y):", len(y))
print("Shape of X:", X.shape)

model = tf.keras.models.Sequential()
model.add(tf.keras.layers.LSTM(50, input_shape=(X.shape[1], X.shape[2])))
model.add(tf.keras.layers.Dense(32, activation='relu'))
model.add(tf.keras.layers.Dense(1, activation='sigmoid'))

model.compile(loss='binary_crossentropy', optimizer='adam', metrics=['accuracy'])
model.fit(X, y, epochs=10, batch_size=32, verbose=2)
model.save(output_model)

scores = model.evaluate(X, y)
print("\n%s: %.2f%%" % (model.metrics_names[1], scores[1]*100))
