import numpy
import tensorflow as tf
import sys
from sklearn import preprocessing

if len(sys.argv) < 3:
    print("Required input: <csv file> <output model>")
    exit(1)

input_csv = sys.argv[1]
output_model = sys.argv[2]

dataset = numpy.loadtxt(input_csv, delimiter=",")
feature_count = dataset[0].size - 1

X = dataset[:, 1:feature_count]
Y = dataset[:, feature_count]
X = preprocessing.normalize(X)

#tbCallback = tf.keras.callbacks.TensorBoard(log_dir='./Graph', histogram_freq=0,
#          write_graph=True, write_images=True)

model = tf.keras.models.Sequential()
model.add(tf.keras.layers.Dense(12, input_dim=feature_count-1, activation='relu'))
model.add(tf.keras.layers.Dense(32, activation='relu'))
model.add(tf.keras.layers.Dense(1, activation='sigmoid'))

model.compile(loss='binary_crossentropy', optimizer='adam', metrics=['accuracy'])
model.fit(X, Y, epochs=300, batch_size=32)
model.save(output_model)

scores = model.evaluate(X, Y)
print("\n%s: %.2f%%" % (model.metrics_names[1], scores[1]*100))

#data_to_predict = numpy.array([[75.25,77.160293068121987,0.950569942021128532,0.710063145041034306]])
#result = model.predict(data_to_predict)
#print("result:")
#print(result)