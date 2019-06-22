package com.lleps.tradexchange.client

import com.lleps.tradexchange.Candle
import com.lleps.tradexchange.Operation
import com.lleps.tradexchange.OperationType
import com.lleps.tradexchange.util.hackTooltipStartTiming
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.geometry.Side
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.Button
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.AnchorPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Paint
import javafx.scene.shape.Polygon
import javafx.stage.Stage
import javafx.util.StringConverter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class FullChart(val useCandles: Boolean = true) : BorderPane() {
    companion object {
        private val chartPlotExecutor = Executors.newCachedThreadPool()
        private val BUY_COLOR = Paint.valueOf("#0000e4")
        private val SELL_COLOR = Paint.valueOf("#1B5E20")
        private val NEGATIVE_SELL_COLOR = Paint.valueOf("#B71C1C")
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        private val TICK_LABEL_FORMATTER = object : StringConverter<Number>() {
            override fun toString(t: Number) = Instant
                .ofEpochSecond(t.toLong())
                .atZone(ZoneOffset.UTC)
                .format(DATE_FORMATTER)

            override fun fromString(string: String): Number = TODO()
        }
    }

    private val nodeHBox = VBox(-10.0)
    private val extraChartsHBox = VBox(-20.0)
    private lateinit var priceChart: CandleStickChart
    private lateinit var chartNavToolbar: HBox
    private lateinit var operationSeries: XYChart.Series<Number, Number>
    private val extraCharts = mutableListOf<LineChart<Number, Number>>()
    private var minTimestamp = 0L
    private var maxTimestamp = 0L

    private var onSelectCandleCallback: (Candle, MouseButton) -> Unit = { _, _ -> }
    private var onSelectOperationCallback: (Operation) -> Unit = { }

    fun onSelectCandle(onSelect: (Candle, MouseButton) -> Unit) {
        onSelectCandleCallback = onSelect
    }

    fun onSelectOperation(onSelect: (Operation) -> Unit) {
        onSelectOperationCallback = onSelect
    }

    init {
        center = nodeHBox
        createChartNavToolbar()
        createPriceChart()
    }

    private fun adjustYRangeByXBounds(chart: XYChart<Number, Number>) {
        if (chart.data == null) return
        val xAxis = chart.xAxis as NumberAxis
        val pricesBetweenRange = chart.data
            .flatMap { it.data }
            .asSequence()
            .filter { it.xValue.toInt() >= xAxis.lowerBound && it.xValue.toInt() <= xAxis.upperBound }
            .map { it.yValue.toDouble() }

        val yAxis = chart.yAxis as NumberAxis
        val max = pricesBetweenRange.max() ?: 0.0
        val min = pricesBetweenRange.min() ?: 0.0
        setBoundsWithSpacing(yAxis, max, min)
    }

    private fun setBoundsWithSpacing(axis: NumberAxis, upper: Double, lower: Double) {
        val amplitude = upper-lower
        axis.upperBound = upper + amplitude*0.15
        axis.lowerBound = lower - amplitude*0.15
    }

    private fun createChartNavToolbar() {
        chartNavToolbar = HBox(10.0)
        val timeFrames = mapOf(
            "1H" to 3600,
            "6H" to 3600*6,
            "12H" to 3600*12,
            "1D" to 3600*24,
            "3D" to 3600*24*3,
            "7D" to 3600*24*7,
            "14D" to 3600*24*14,
            "30D" to 3600*24*30
        )
        for ((timeFrameStr, timeFrameSeconds) in timeFrames) {
            chartNavToolbar.children.add(Button(timeFrameStr).apply {
                style = "-fx-background-color: transparent;"
                setOnAction { plotChart(maxTimestamp - timeFrameSeconds, maxTimestamp) }
            })
        }
        chartNavToolbar.children.add(Button("ALL").apply {
            style = "-fx-background-color: transparent;"
            setOnAction { plotChart(minTimestamp, maxTimestamp) }
        })
        chartNavToolbar.children.add(Button("<").apply {
            style = "-fx-background-color: transparent;"
            setOnAction {
                val xAxis = priceChart.xAxis as NumberAxis
                val currFrame = xAxis.upperBound - xAxis.lowerBound
                plotChart((xAxis.lowerBound - currFrame).toLong(), (xAxis.upperBound - currFrame).toLong())
            }
        })
        chartNavToolbar.children.add(Button(">").apply {
            style = "-fx-background-color: transparent;"
            setOnAction {
                val xAxis = priceChart.xAxis as NumberAxis
                val currFrame = xAxis.upperBound - xAxis.lowerBound
                plotChart((xAxis.lowerBound + currFrame).toLong(), (xAxis.upperBound + currFrame).toLong())
            }
        })
        chartNavToolbar.alignment = Pos.CENTER
        chartNavToolbar.style = "-fx-background-color: transparent;"
    }

    private fun createPriceChart() {
        val xAxis = NumberAxis(0.0, 5.0, 500.0)
        xAxis.minorTickCount = 0
        val yAxis = NumberAxis(0.0, 5.0, 5.0)
        yAxis.label = "price"
        yAxis.side = Side.RIGHT

        priceChart = CandleStickChart(xAxis, yAxis)
        priceChart.animated = false
        priceChart.priceType = if (useCandles) CandleStickChart.PriceType.CANDLES else CandleStickChart.PriceType.LINE
        xAxis.tickLabelFormatter = TICK_LABEL_FORMATTER
        xAxis.tickUnitProperty().bind(Bindings.divide(Bindings.subtract(xAxis.upperBoundProperty(), xAxis.lowerBoundProperty()), 20.0))
        priceChart.apply {
            setOnScroll {
                val difference = (xAxis.upperBound - xAxis.lowerBound) / 20
                xAxis.lowerBound -= difference * if (it.deltaY < 0.0) 1.0 else -1.0
                adjustYRangeByXBounds(this)
            }
            var lastX = 0.0
            setOnMouseMoved { lastX = it.x }
            setOnMouseDragged {
                val delta = it.x - lastX
                val difference = (xAxis.upperBound - xAxis.lowerBound) / 100
                xAxis.lowerBound += difference * -delta
                xAxis.upperBound += difference * -delta
                lastX = it.x
                adjustYRangeByXBounds(this)
            }
            setOnSelectCandle { c, b -> onSelectCandleCallback(c, b) }
        }
        val anchor = AnchorPane()
        anchor.children.addAll(priceChart, chartNavToolbar)
        AnchorPane.setTopAnchor(chartNavToolbar, 18.0)
        AnchorPane.setLeftAnchor(chartNavToolbar, 50.0)
        AnchorPane.setRightAnchor(chartNavToolbar, 50.0)
        AnchorPane.setTopAnchor(priceChart, 1.0)
        AnchorPane.setRightAnchor(priceChart, 1.0)
        AnchorPane.setLeftAnchor(priceChart, 1.0)
        AnchorPane.setBottomAnchor(priceChart, 1.0)
        if (nodeHBox.children.isNotEmpty()) {
            nodeHBox.children[0] = anchor
        } else {
            nodeHBox.children.add(anchor)
        }
        if (nodeHBox.children.size == 1) {
            nodeHBox.children.add(extraChartsHBox)
        }
    }

    var priceData = emptyList<Candle>()
    var operations = emptyList<Operation>()
    var priceIndicators = emptyMap<String, Map<Long, Double>>()
    var extraIndicators = emptyMap<String, Map<String, Map<Long, Double>>>()

    private fun createOperationNode(operation: Operation): Node {
        val offsetY = 15.0/2.0
        val node = if (operation.type == OperationType.BUY) {
            Polygon( 5.0,-offsetY,  10.0,15.0-offsetY, 0.0,15.0-offsetY)
                .apply {
                    fill = BUY_COLOR
                    setOnMouseClicked { onSelectOperationCallback(operation) }
                }
        } else {
            Polygon( 5.0,+offsetY,  10.0,-15.0+offsetY, 0.0,-15.0+offsetY)
                .apply {
                    fill = sellColor(operation)
                    setOnMouseClicked { onSelectOperationCallback(operation) }
                }
        }
        if (operation.description != null) {
            Tooltip.install(node, Tooltip(operation.description))
        }
        return node
    }

    // provide a method to update operations since this is needed for candle clicks.
    // You can't rebuild the chart, lose the interval, etc at each click.
    fun updateOperations() {
        Platform.runLater {
            operationSeries.data.clear()
            for (operation in operations) { // operations are not parsed with RR in mind
                if (operation.timestamp < minTimestamp || operation.timestamp > maxTimestamp) continue
                val node = createOperationNode(operation)
                operationSeries.data.add(
                    XYChart.Data<Number, Number>(operation.timestamp, operation.price)
                        .also { it.node = node }
                )
            }
        }
    }

    private fun sellColor(op: Operation): Paint {
        return if (op.description?.contains("-") == true) NEGATIVE_SELL_COLOR else SELL_COLOR
    }

    private fun plotChart(minTimestamp: Long, maxTimestamp: Long, maxTicks: Int = 800) {
        chartPlotExecutor.execute {
            var minValue = Double.MAX_VALUE
            var maxValue = 0.0
            val allSeries = FXCollections.observableArrayList<XYChart.Series<Number, Number>>()
            val candleSeries = XYChart.Series<Number, Number>()

            // Calculate chart resolution
            val tickCount = priceData.count { it.timestamp in (minTimestamp)..(maxTimestamp) }
            if (tickCount == 0) return@execute

            var tickRR = 1
            while ((tickCount / tickRR) > maxTicks) {
                tickRR++
            }

            // Build candles list
            var candleIndex = 0
            for (candle in priceData) {
                if (candle.timestamp < minTimestamp) continue
                else if (candle.timestamp > maxTimestamp) break
                if (candleIndex++ % tickRR != 0) continue

                candleSeries.data.add(
                    XYChart.Data(
                        candle.timestamp,
                        candle.open,
                        candle
                    )
                )
                minValue = minOf(candle.low, minValue)
                maxValue = maxOf(candle.high, maxValue)
            }
            allSeries.add(candleSeries)

            // Build operations list
            val operationSeries = XYChart.Series<Number, Number>()
            for (operation in operations) { // operations are not parsed with RR in mind
                if (operation.timestamp < minTimestamp || operation.timestamp > maxTimestamp) continue
                val node = createOperationNode(operation)
                operationSeries.data.add(
                    XYChart.Data<Number, Number>(operation.timestamp, operation.price)
                        .also { it.node = node }
                )
            }
            this.operationSeries = operationSeries
            allSeries.add(operationSeries)

            // Build price indicators list
            for ((indicatorName, indicatorPoints) in priceIndicators) {
                val series = XYChart.Series<Number, Number>()
                series.name = indicatorName
                var indicatorIndex = 0
                for ((epoch, dataValue) in indicatorPoints) {
                    if (epoch < minTimestamp) continue
                    else if (epoch > maxTimestamp) break
                    if (indicatorIndex++ % tickRR != 0) continue
                    series.data.add(XYChart.Data<Number, Number>(epoch, dataValue))
                    minValue = minOf(dataValue, minValue)
                    maxValue = maxOf(dataValue, maxValue)
                }
                allSeries.add(series)
            }

            // Plot main chart
            Platform.runLater {
                createPriceChart()
                if (minValue == Double.MAX_VALUE) minValue = 0.0
                val xa = (priceChart.xAxis as NumberAxis)
                val ya = (priceChart.yAxis as NumberAxis)
                ya.tickUnit = (maxValue - minValue) / 10.0
                xa.lowerBound = minTimestamp.toDouble()
                xa.upperBound = maxTimestamp.toDouble()
                setBoundsWithSpacing(ya, maxValue, minValue)
                priceChart.data = allSeries
                hackTooltipStartTiming()
            }

            // Build extra chart list
            val newExtraCharts = mutableListOf<LineChart<Number, Number>>()
            for ((indicatorName, indicatorData) in extraIndicators) {
                // create extra chart
                val xAxis = NumberAxis(minTimestamp.toDouble(), maxTimestamp.toDouble(), (maxTimestamp - minTimestamp) / 20.0)
                xAxis.minorTickCount = 0
                xAxis.isTickLabelsVisible = false
                xAxis.isTickMarkVisible = false
                Platform.runLater {
                    xAxis.lowerBoundProperty().bind((priceChart.xAxis as NumberAxis).lowerBoundProperty())
                    xAxis.upperBoundProperty().bind((priceChart.xAxis as NumberAxis).upperBoundProperty())
                }

                val yAxis = NumberAxis(0.0, 1.0, 5.0)
                yAxis.side = Side.RIGHT
                yAxis.label = indicatorName

                val chart = LineChart(xAxis, yAxis)
                chart.createSymbols = false
                chart.isLegendVisible = false
                chart.animated = false

                val chartData = FXCollections.observableArrayList<XYChart.Series<Number, Number>>()
                var minExtraVal = Double.MAX_VALUE
                var maxExtraVal = 0.0
                for ((seriesName, seriesData) in indicatorData) {
                    val series = XYChart.Series<Number, Number>()
                    series.name = seriesName
                    var indicatorIndex = 0
                    for ((epoch, value) in seriesData) {
                        if (epoch < minTimestamp) continue
                        else if (epoch > maxTimestamp) break
                        if (indicatorIndex++ % tickRR != 0) continue

                        minExtraVal = minOf(value, minExtraVal)
                        maxExtraVal = maxOf(value, maxExtraVal)
                        series.data.add(XYChart.Data<Number, Number>(epoch, value))
                    }
                    chartData.add(series)
                }
                setBoundsWithSpacing(yAxis, maxExtraVal, minExtraVal)
                yAxis.tickUnit = (maxExtraVal - minExtraVal) / 2
                chart.data = chartData
                newExtraCharts.add(chart)
            }

            // Plot extra charts
            Platform.runLater {
                if (newExtraCharts.isNotEmpty()) {// maybe priceChart.height?
                    val heightPerChart = (maxOf(priceChart.height, nodeHBox.height / 2.0, extraChartsHBox.height) / newExtraCharts.size)
                    for (c in newExtraCharts) {
                        c.minHeight = heightPerChart
                        c.maxHeight = heightPerChart
                        c.prefHeight = heightPerChart
                    }
                }
                extraChartsHBox.children.removeAll(extraCharts)
                extraCharts.clear()
                extraCharts.addAll(newExtraCharts)
                extraChartsHBox.children.addAll(newExtraCharts)
            }
        }
    }

    fun fill() {
        minTimestamp = priceData.firstOrNull()?.timestamp ?: 0L
        maxTimestamp = priceData.lastOrNull()?.timestamp ?: 1L
        plotChart(minTimestamp, maxTimestamp)
    }

    // To test this view quickly
    class TestApp : Application() {
        override fun start(primaryStage: Stage) {
            val chart = FullChart()
            /*chart.priceData = listOf(
                Candle(1, 23.0, 25.5, 26.6, 22.0),
                Candle(2, 24.0, 23.5, 28.6, 24.0),
                Candle(3, 25.0, 27.5, 30.6, 23.0),
                Candle(4, 26.0, 23.5, 32.6, 22.0)
            )
            chart.operations = listOf(
                Operation(1, OperationType.BUY, 25.0),
                Operation(3, OperationType.SELL, 30.0)
            )
            chart.priceIndicators = mapOf(
                "ema" to mapOf(
                    1L to 24.0,
                    2L to 23.0,
                    3L to 22.0,
                    4L to 22.5
                ),
                "low" to mapOf(
                    1L to 29.0,
                    2L to 29.0,
                    3L to 29.0,
                    4L to 29.5
                ),
                "high" to mapOf(
                    1L to 14.0,
                    2L to 14.0,
                    3L to 14.0,
                    4L to 14.5
                )
            )
            chart.extraIndicators = mapOf(
                "rsi" to mapOf(
                    "num" to mapOf(
                        1L to 50.0,
                        2L to 65.0,
                        3L to 70.0,
                        4L to 79.0
                    ),
                    "top" to mapOf(
                        1L to 70.0,
                        2L to 70.0,
                        3L to 70.0,
                        4L to 70.0
                    ),
                    "bottom" to mapOf(
                        1L to 30.0,
                        2L to 30.0,
                        3L to 30.0,
                        4L to 30.0
                    )
                ),
                "macd?" to mapOf(
                    "num" to mapOf(
                        1L to 50.0,
                        2L to 65.0,
                        3L to 70.0,
                        4L to 79.0
                    ),
                    "top" to mapOf(
                        1L to 70.0,
                        2L to 70.0,
                        3L to 70.0,
                        4L to 70.0
                    ),
                    "bottom" to mapOf(
                        1L to 30.0,
                        2L to 30.0,
                        3L to 30.0,
                        4L to 30.0
                    )
                )
            )*/

            println("fill...")
            chart.fill()
            println("done")
            primaryStage.scene = Scene(chart)
            primaryStage.show()
        }

        companion object {
            @JvmStatic
            fun main(args: Array<String>) {
                launch(TestApp::class.java)
            }
        }
    }
}