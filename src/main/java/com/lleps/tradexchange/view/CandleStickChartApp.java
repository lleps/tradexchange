/*
 * Copyright (c) 2008, 2016, Oracle and/or its affiliates.
 * All rights reserved. Use is subject to license terms.
 *
 * This file is available and licensed under the following license:
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  - Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the distribution.
 *  - Neither the name of Oracle Corporation nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.lleps.tradexchange.view;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.shape.Polygon;
import javafx.stage.Stage;

/**
 * A custom candlestick chart. This sample shows how to extend XYChart base class
 * to create your own two axis chart type.
 */
public class CandleStickChartApp extends Application {

    // DAY, OPEN, CLOSE, HIGH, LOW, AVERAGE
    private static double[][] data = new double[][]{
            {1,  25, 20, 32, 16, 20},
            {2,  26, 30, 33, 22, 25},
            {3,  30, 38, 40, 20, 32},
            {4,  24, 30, 34, 22, 30},
            {5,  26, 36, 40, 24, 32},
            {6,  28, 38, 45, 25, 34},
            {7,  36, 30, 44, 28, 39},
            {8,  30, 18, 36, 16, 31},
            {9,  40, 50, 52, 36, 41},
            {10, 30, 34, 38, 28, 36},
            {11, 24, 12, 30, 8,  32.4},
            {12, 28, 40, 46, 25, 31.6},
            {13, 28, 18, 36, 14, 32.6},
            {14, 38, 30, 40, 26, 30.6},
            {15, 28, 33, 40, 28, 30.6},
            {16, 25, 10, 32, 6,  30.1},
            {17, 26, 30, 42, 18, 27.3},
            {18, 20, 18, 30, 10, 21.9},
            {19, 20, 10, 30, 5,  21.9},
            {20, 26, 16, 32, 10, 17.9},
            {21, 38, 40, 44, 32, 18.9},
            {22, 26, 40, 41, 12, 18.9},
            {23, 30, 18, 34, 10, 18.9},
            {24, 12, 23, 26, 12, 18.2},
            {25, 30, 40, 45, 16, 18.9},
            {26, 25, 35, 38, 20, 21.4},
            {27, 24, 12, 30, 8,  19.6},
            {28, 23, 44, 46, 15, 22.2},
            {29, 30, 18, 38, 14, 23},
            {30, 28, 16, 36, 12, 21.2},
            // copy
            {31,  25, 20, 32, 16, 20},
            {32,  26, 30, 33, 22, 25},
            {33,  30, 38, 40, 20, 32},
            {34,  24, 30, 34, 22, 30},
            {35,  26, 36, 40, 24, 32},
            {36,  28, 38, 45, 25, 34},
            {37,  36, 30, 44, 28, 39},
            {38,  30, 18, 36, 16, 31},
            {39,  40, 50, 52, 36, 41},
            {40, 30, 34, 38, 28, 36},
            {41, 24, 12, 30, 8,  32.4},
            {42, 28, 40, 46, 25, 31.6},
            {43, 28, 18, 36, 14, 32.6},
            {44, 38, 30, 40, 26, 30.6},
            {45, 28, 33, 40, 28, 30.6},
            {46, 25, 10, 32, 6,  30.1},
            {47, 26, 30, 42, 18, 27.3},
            {48, 20, 18, 30, 10, 21.9},
            {49, 20, 10, 30, 5,  21.9},
            {50, 26, 16, 32, 10, 17.9},
            {51, 38, 40, 44, 32, 18.9},
            {52, 26, 40, 41, 12, 18.9},
            {53, 30, 18, 34, 10, 18.9},
            {54, 12, 23, 26, 12, 18.2},
            {55, 30, 40, 45, 16, 18.9},
            {56, 25, 35, 38, 20, 21.4},
            {57, 24, 12, 30, 8,  19.6},
            {58, 23, 44, 46, 15, 22.2},
            {59, 30, 18, 38, 14, 23},
            {60, 28, 16, 36, 12, 21.2},
            // end copy
            {61, 26, 14, 30, 10, 20}
    };

    private CandleStickChart chart;
    private NumberAxis xAxis;
    private NumberAxis yAxis;

    public Parent createContent() {
        int multiplier = 1;
        xAxis = new NumberAxis(0,data.length * multiplier,1);
        xAxis.setMinorTickCount(0);
        yAxis = new NumberAxis();
        chart = new CandleStickChart(xAxis,yAxis);
        // setup chart
        xAxis.setLabel("Day");
        yAxis.setLabel("Price");
        // add starting data
        XYChart.Series<Number,Number> series = new XYChart.Series<Number,Number>();
        XYChart.Series<Number,Number> series2 = new XYChart.Series<Number,Number>();
        XYChart.Series<Number,Number> series3 = new XYChart.Series<Number,Number>();
        long start = System.currentTimeMillis();
        for (int i=0; i< data.length * multiplier; i++) {
            double[] day = data[i % data.length];
            final CandleStickExtraValues extras =
                    new CandleStickExtraValues(day[2],day[3],day[4],day[5]);
            XYChart.Data<Number,Number> d = new XYChart.Data<Number,Number>(i+1,day[1] + Math.random() * 5f);
            d.setNode(new Polygon( 5.0,0.0,  10.0,10.0, 0.0,10.0 ));
            series.getData().add(new XYChart.Data<Number,Number>(i+1,day[1], extras));
            series2.getData().add(d);
            //series3.getData().add(new XYChart.Data<Number,Number>(i+1,day[1] + Math.random() * 5f));
        }

        ObservableList<XYChart.Series<Number,Number>> data = chart.getData();
        if (data == null) {
            data = FXCollections.observableArrayList(series, series2, series3);
            chart.setData(data);
        } else {
            chart.getData().addAll(series, series2, series3);
        }
        //chart.getData().add(series2);
        long timeSpent = System.currentTimeMillis() - start;
        System.out.println("Generate " + (CandleStickChartApp.data.length * multiplier) + " points in " + timeSpent + " ms.");
        return chart;
    }

    @Override public void start(Stage primaryStage) throws Exception {
        primaryStage.setScene(new Scene(createContent()));
        primaryStage.show();
    }

    /**
     * Java main for when running without JavaFX launcher
     */
    public static void main(String[] args) { launch(args); }
}