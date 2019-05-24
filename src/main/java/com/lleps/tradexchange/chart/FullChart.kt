package com.lleps.tradexchange.chart

import javafx.collections.FXCollections
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Paint
import javafx.scene.shape.Polygon

class FullChart : BorderPane() {
    companion object {
        private val BUY_COLOR = Paint.valueOf("#43A047")
        private val SELL_COLOR = Paint.valueOf("#F44336")
    }

    class Candle(val timestamp: Long, val open: Double, val close: Double, val high: Double, val low: Double)
    enum class OperationType { BUY, SELL }
    class Operation(val timestamp: Long, val type: OperationType, val price: Double, val description: String? = null)

    private val nodeHBox = VBox(0.0)
    private val priceChart: CandleStickChart
    init {
        val xAxis = NumberAxis(0.0, 1.0, 1.0)
        xAxis.minorTickCount = 0
        val yAxis = NumberAxis()
        priceChart = CandleStickChart(xAxis, yAxis)
        center = nodeHBox
    }

    var priceData = emptyList<Candle>()
    var operations = emptyList<Operation>()
    var priceIndicators = emptyMap<String, Map<Long, Double>>()
    var extraIndicators = emptyMap<String, Map<String, Map<Long, Double>>>()

    fun fill() {
        val allSeries = FXCollections.observableArrayList<XYChart.Series<Number, Number>>()
        val candleSeries = XYChart.Series<Number, Number>()
        for (candle in priceData) {
            // should only add if inside bounds.
            candleSeries.data.add(
                XYChart.Data(
                    candle.timestamp,
                    candle.open,
                    CandleStickExtraValues(
                        candle.close,
                        candle.high,
                        candle.low,
                        (candle.high + candle.low) / 2.0
                    )
                )
            )
        }
        allSeries.add(candleSeries)

        // this series should be hidden via css (-fx-hidden: true;)
        val operationSeries = XYChart.Series<Number, Number>()
        for (operation in operations) {
            val node = if (operation.type == OperationType.BUY) {
                Polygon( 5.0,0.0,  10.0,10.0, 0.0,10.0 )
                    .apply { fill = BUY_COLOR }
            } else {
                Polygon( 5.0,10.0,  10.0,0.0, 0.0,0.0 )
                    .apply { fill = SELL_COLOR }
            }
            candleSeries.data.add(
                XYChart.Data<Number, Number>(operation.timestamp, operation.price)
                    .also { it.node = node }
            )
        }
        allSeries.add(operationSeries)

        for ((indicatorName, indicatorPoints) in priceIndicators) {
            val series = XYChart.Series<Number, Number>()
            series.name = indicatorName
            series.data.addAll(indicatorPoints.map { XYChart.Data<Number, Number>(it.key, it.value) })
            allSeries.add(series)
        }

        priceChart.data = allSeries
    }
}