package com.lleps.tradexchange;

import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.modelimport.keras.exceptions.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.exceptions.UnsupportedKerasConfigurationException;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.io.ClassPathResource;

import java.io.IOException;
import java.util.Arrays;

public class ImportKerasModel {
    private static MultiLayerNetwork model;
    private static boolean inited = false;

    public static void main(String[] args) throws IOException, InvalidKerasConfigurationException, UnsupportedKerasConfigurationException {
        if (inited) return;
        String path = "/media/lleps/Compartido/Dev/pythontest/[train]stochastic-test.h5";
        model = KerasModelImport.importKerasSequentialModelAndWeights(path);
        System.out.println("prediction: " + predict(0.0, 13.53535, 15.160293068121987,0.21950569942021128532,0.11710063145041034306));
        inited = true;
    }

    public static double predict(double price, double rsik, double rsid, double macd, double obv) {
        double[][] data = new double[][] {
                {
                        rsik/100.0, rsid/100.0, macd, obv
                }
        };
        double prediction = model.output(Nd4j.create(data)).getDouble(0);
        return prediction;
    }
}