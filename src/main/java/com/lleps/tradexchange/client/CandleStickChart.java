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
package com.lleps.tradexchange.client;

import java.util.Iterator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import com.lleps.tradexchange.Candle;
import javafx.animation.FadeTransition;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.chart.Axis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.util.Duration;

/**
 * A candlestick chart is a style of bar-chart used primarily to describe
 * price movements of a security, derivative, or currency over time.
 *
 * The Data Y value is used for the opening price and then the
 * close, high and low values are stored in the Data's
 * extra value property using a CandleStickExtraValues object.
 */
public class CandleStickChart extends XYChart<Number, Number> {

    public enum PriceType { CANDLES, LINE }

    private BiConsumer<Candle, MouseButton> onSelectCandle = (Candle c, MouseButton b) -> {};

    private PriceType priceType = PriceType.LINE;

    public void setPriceType(PriceType priceType) {
        this.priceType = priceType;
    }

    public PriceType getPriceType() {
        return priceType;
    }

    public void setOnSelectCandle(BiConsumer<Candle, MouseButton> onSelectCandle) {
        this.onSelectCandle = onSelectCandle;
    }

    /**
     * Construct a new CandleStickChart with the given axis.
     */
    public CandleStickChart(Axis<Number> xAxis, Axis<Number> yAxis) {
        super(xAxis, yAxis);
        //final String candleStickChartCss =
        //        getClass().getResource("CandleStickChart.css").toExternalForm();
        //getStylesheets().add(candleStickChartCss);
        getStylesheets().add("CandleStickChart.css");
        setAnimated(false);
        xAxis.setAnimated(false);
        yAxis.setAnimated(false);
    }

    /**
     * Construct a new CandleStickChart with the given axis and data.
     *             reflected in the chart.
     */
    public CandleStickChart(Axis<Number> xAxis, Axis<Number> yAxis,
                            ObservableList<Series<Number, Number>> data) {
        this(xAxis, yAxis);
        setData(data);
    }

    /** Called to update and layout the content for the plot */
    @Override protected void layoutPlotChildren() {
        if (priceType == PriceType.CANDLES) {
            if (!getData().isEmpty()) {
                Series<Number, Number> candleSeries = getData().get(0);
                candleSeries.getNode().setVisible(false);
            }
        }

        // we have nothing to layout if no data is present
        if (getData() == null) {
            return;
        }
        // update candle positions
        for (int index = 0; index < getData().size(); index++) {
            Series<Number, Number> series = getData().get(index);
            Iterator<XYChart.Data<Number, Number>> iter =
                    getDisplayedDataIterator(series);
            Path seriesPath = null;
            if (series.getNode() instanceof Path) {
                seriesPath = (Path) series.getNode();
                seriesPath.getElements().clear();
            }
            while (iter.hasNext()) {
                Axis<Number> yAxis = getYAxis();
                XYChart.Data<Number, Number> item = iter.next();
                Number X = getCurrentDisplayedXValue(item);
                Number Y = getCurrentDisplayedYValue(item);
                double x = getXAxis().getDisplayPosition(X);
                double y = getYAxis().getDisplayPosition(Y);
                Node itemNode = item.getNode();
                Candle extra =
                        (Candle)item.getExtraValue();

                if (itemNode instanceof CandleNode && extra != null) {
                    double close = yAxis.getDisplayPosition(extra.getClose());
                    double high = yAxis.getDisplayPosition(extra.getHigh());
                    double low = yAxis.getDisplayPosition(extra.getLow());
                    // calculate candle width
                    NumberAxis xAxisNum = (NumberAxis)getXAxis();
                    double amplitude = xAxisNum.getUpperBound() - xAxisNum.getLowerBound();
                    long period = 1L;
                    if (!getData().isEmpty() && !getData().get(0).getData().isEmpty()) {
                        period = (long)getData().get(0).getData().get(1).getXValue() -
                                (long)getData().get(0).getData().get(0).getXValue();
                    }
                    double candleCount = amplitude / period;
                    double candleWidth = 500.0 * (1.0 / candleCount);//7.0;
                    /*if (getXAxis() instanceof NumberAxis) {
                        // use 90% width between ticks
                        NumberAxis xa = (NumberAxis) getXAxis();
                        double unit = xa.getDisplayPosition(xa.getTickUnit());
                        candleWidth = unit * 0.90;
                    }*/
                    // update candle
                    CandleNode candle = (CandleNode)itemNode;
                    candle.update(close - y, high - y, low - y, candleWidth);
                    candle.updateTooltip(item.getYValue().doubleValue(),
                            extra.getClose(), extra.getHigh(),
                            extra.getLow());
                }
                if (itemNode != null) {
                    // position the candle (or any other node)
                    itemNode.setLayoutX(x);
                    itemNode.setLayoutY(y);
                }
                if (seriesPath != null) {
                    double ave = yAxis.getDisplayPosition(extra != null ? extra.getClose() : item.getYValue());
                    if (seriesPath.getElements().isEmpty()) {
                        seriesPath.getElements().add(new MoveTo(x, ave));
                    } else {
                        seriesPath.getElements().add(new LineTo(x, ave));
                    }
                }
            }
        }
    }

    @Override protected void dataItemChanged(Data<Number, Number> item) {
    }

    @Override protected void dataItemAdded(Series<Number, Number> series,
                                           int itemIndex,
                                           Data<Number, Number> item) {
        int seriesIndex = getData().indexOf(series);
        if (seriesIndex != 0) { // should create candle only on 0
            Node n = item.getNode();
            if (n != null) {
                getPlotChildren().add(n);
            }
            return;
        }
        Node candle = createCandle(seriesIndex, item, itemIndex);
        if (shouldAnimate()) {
            candle.setOpacity(0);
            getPlotChildren().add(candle);
            // fade in new candle
            final FadeTransition ft =
                    new FadeTransition(Duration.millis(500), candle);
            ft.setToValue(1);
            ft.play();
        } else {
            getPlotChildren().add(candle);
        }
        // always draw average line on top
        if (series.getNode() != null) {
            series.getNode().toFront();
        }
    }

    @Override protected void dataItemRemoved(Data<Number, Number> item,
                                             Series<Number, Number> series) {
        final Node candle = item.getNode();
        if (shouldAnimate()) {
            // fade out old candle
            final FadeTransition ft =
                    new FadeTransition(Duration.millis(500), candle);
            ft.setToValue(0);
            ft.setOnFinished((ActionEvent actionEvent) -> {
                getPlotChildren().remove(candle);
            });
            ft.play();
        } else {
            getPlotChildren().remove(candle);
        }
    }

    @Override protected void seriesAdded(Series<Number, Number> series,
                                         int seriesIndex) {
        // handle any data already in series
        for (int j = 0; j < series.getData().size(); j++) {
            XYChart.Data item = series.getData().get(j);
            if (seriesIndex != 0) { // not the candles
                Node n = item.getNode();
                if (n != null) {
                    getPlotChildren().add(n);
                }
                continue;
            }
            Node candle = createCandle(seriesIndex, item, j);
            if (shouldAnimate()) {
                candle.setOpacity(0);
                getPlotChildren().add(candle);
                // fade in new candle
                final FadeTransition ft =
                        new FadeTransition(Duration.millis(500), candle);
                ft.setToValue(1);
                ft.play();
            } else {
                getPlotChildren().add(candle);
            }
        }
        // create series path
        Path seriesPath = new Path();
        if (seriesIndex == 0) {
            seriesPath.getStyleClass().setAll("candlestick-average-line",
                    "candlestick-series-" + seriesIndex);
        } else {
            seriesPath.getStyleClass().setAll("candlestick-series-" + seriesIndex);
        }
        series.setNode(seriesPath);
        getPlotChildren().add(seriesPath);
    }

    @Override protected void seriesRemoved(Series<Number, Number> series) {
        // remove all candle nodes
        for (XYChart.Data<Number, Number> d : series.getData()) {
            final Node candle = d.getNode();
            if (shouldAnimate()) {
                // fade out old candle
                final FadeTransition ft =
                        new FadeTransition(Duration.millis(500), candle);
                ft.setToValue(0);
                ft.setOnFinished((ActionEvent actionEvent) -> {
                    getPlotChildren().remove(candle);
                });
                ft.play();
            } else {
                getPlotChildren().remove(candle);
            }
        }
    }

    /**
     * Create a new Candle node to represent a single data item
     */
    private Node createCandle(int seriesIndex, final XYChart.Data item,
                              int itemIndex) {
        Node candle = item.getNode();
        // check if candle has already been created
        if (candle instanceof CandleNode) {
            ((CandleNode)candle).setSeriesAndDataStyleClasses("series" + seriesIndex,
                    "data" + itemIndex);
        } else {
            candle = new CandleNode("series" + seriesIndex, "data" + itemIndex);
            item.setNode(candle);
            candle.setOnMouseClicked(e -> onSelectCandle.accept((Candle)item.getExtraValue(), e.getButton()));
            if (priceType == PriceType.LINE) candle.setVisible(false);
        }
        return candle;
    }

    /**
     * This is called when the range has been invalidated and we need to
     * update it. If the axis are auto ranging then we compile a list of
     * all data that the given axis has to plot and call invalidateRange()
     * on the axis passing it that data.
     */
    /*@Override
    protected void updateAxisRange() {
        // For candle stick chart we need to override this method as we need
        // to let the axis know that they need to be able to cover the area
        // occupied by the high to low range not just its center data value.
        final Axis<Number> xa = getXAxis();
        final Axis<Number> ya = getYAxis();
        List<Number> xData = null;
        List<Number> yData = null;
        if (xa.isAutoRanging()) {
            xData = new ArrayList<Number>();
        }
        if (ya.isAutoRanging()) {
            yData = new ArrayList<Number>();
        }
        if (xData != null || yData != null) {
            for (XYChart.Series<Number, Number> series : getData()) {
                for (XYChart.Data<Number, Number> data : series.getData()) {
                    if (xData != null) {
                        xData.add(data.getXValue());
                    }
                    if (yData != null) {
                        CandleStickExtraValues extras =
                                (CandleStickExtraValues)data.getExtraValue();
                        if (extras != null) {
                            yData.add(extras.getHigh());
                            yData.add(extras.getLow());
                        } else {
                            yData.add(data.getYValue());
                        }
                    }
                }
            }
            if (xData != null) {
                xa.invalidateRange(xData);
            }
            if (yData != null) {
                ya.invalidateRange(yData);
            }
        }
    }*/
}