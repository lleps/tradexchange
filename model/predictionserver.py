import numpy
import tensorflow as tf
import pandas as pd
import sys
import asyncio
import websockets
from pandas import read_csv
from pandas import DataFrame
from pandas import concat
from timeit import default_timer as timer


# the point of this file is to allow to make predictions
# using any keras model.

model = None
arr = None

def process(msg, content):
    global model
    global arr
    if msg == "load":
        model = tf.keras.models.load_model(content)
        return "ok"
    elif msg == "predict":
        if model is None:
            return "model not loaded"

        # content format: f00,f01,f02|f10,f11,f12.
        # convert content to a numpy array shape (timesteps, features)
        lines = content.split('|')

        if arr is None:
            timesteps = len(lines)
            features_per_line = len(lines[0].split(','))
            arr = numpy.zeros((1, timesteps, features_per_line), dtype=float)

        for x in range(1):
            i = 0
            for l in lines:
                features = l.split(',')
                j = 0
                for f in features:
                    arr[x, i, j] = float(f)
                    j += 1
                i += 1

        return str(model.predict(arr)[0])


#load:/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.h5
#predict:0.9,0.2|0.4,0.2|0.01,0.19
if __name__ == '__main__':
    while 1:
        try:
            fulldata = sys.stdin.readline().rstrip()
            if fulldata == "" or fulldata == "bye":
                print("gg wp")
                break

            msg, content = fulldata.split(':', 1)
            start = timer()
            result = process(msg, content)
            result = process(msg, content)
            result = process(msg, content)
            took = timer() - start
            print("response:", result, "(in ", took, "seconds)")
        except KeyboardInterrupt:
            break
