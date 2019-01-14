package com.lleps.tradexchange

import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Paint
import javafx.scene.shape.Circle
import javafx.util.StringConverter
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class TradeChart {
    val node = BorderPane()
    private val hbox = VBox(10.0)
    private val mainChart: LineChart<Number, Number>
    private val mainChartSeries = mutableMapOf<String, XYChart.Series<Number, Number>>()
    private val extraCharts = mutableMapOf<String, LineChart<Number, Number>>()
    private val extraChartsSeries = mutableMapOf<String, MutableMap<String, XYChart.Series<Number, Number>>>()

    init {
        val xAxis = NumberAxis("Date", 0.0, 8.0, 1.0).apply {
            upperBound = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond().toDouble()
            lowerBound = upperBound - (3600*24*2)
            tickLabelFormatter = object : StringConverter<Number>() {
                override fun toString(t: Number) = Instant
                        .ofEpochSecond(t.toLong())
                        .atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

                override fun fromString(string: String): Number = TODO()
            }
            tickUnitProperty().bind(Bindings.divide(Bindings.subtract(upperBoundProperty(), lowerBoundProperty()), 20.0))
        }

        val yAxis = NumberAxis("Price", 0.0, 8.0, 1.0).apply {
            tickUnitProperty().bind(Bindings.divide(Bindings.subtract(upperBoundProperty(), lowerBoundProperty()), 10.0))
        }

        mainChart = LineChart<Number, Number>(xAxis, yAxis, FXCollections.observableArrayList<XYChart.Series<Number, Number>>()).apply {
            //isCache = true
            //isCacheShape = true
            setOnScroll {
                xAxis.lowerBound -= 3600*6 * if (it.deltaY < 0.0) 1.0 else -1.0
                adjustYRangeByXBounds(this)
            }
            stylesheets.add("style.css")
            var lastX = 0.0
            setOnMouseMoved { lastX = it.x }
            setOnMouseDragged {
                val delta = it.x - lastX
                xAxis.lowerBound += 900 * -delta
                xAxis.upperBound += 900 * -delta
                lastX = it.x
                adjustYRangeByXBounds(this)
            }
            animated = false
            //prefHeightProperty().bind(Bindings.divide(node.heightProperty(), 2.0))
        }
        hbox.children.add(mainChart)
        hbox.alignment = Pos.TOP_CENTER
        node.center = hbox
    }

    private var lastAdjust = HashMap<LineChart<Number, Number>, Long>()

    private fun adjustYRangeByXBounds(chart: LineChart<Number, Number>) {
        if (System.currentTimeMillis() - lastAdjust.getOrPut(chart) { 0L } < 1000) return
        lastAdjust[chart] = System.currentTimeMillis()

        val xAxis = chart.xAxis as NumberAxis
        val pricesBetweenRange = chart.data
                .flatMap { it.data }
                .asSequence()
                .filter { it.xValue.toInt() >= xAxis.lowerBound && it.xValue.toInt() <= xAxis.upperBound }
                .map { it.yValue.toDouble() }

        val yAxis = chart.yAxis as NumberAxis
        yAxis.upperBound = pricesBetweenRange.max() ?: 0.0
        yAxis.lowerBound = pricesBetweenRange.min() ?: 0.0
    }

    // TODO improve chart navigation performance
    //  Threaded?
    //  Data streaming. Separate series in blocks of X points. When fixing, should stream the main chart.


    private class SeriesList()

    private val mainChartDataRaw = mutableMapOf<String, SeriesList>()

    fun addPoint(type: String, epoch: Long, value: Double, description: String? = null) {
        Platform.runLater {
            val seriesName = if (type == "Buy" || type.contains("Sell")) "Price" else type
            val series = mainChartSeries.getOrPut(seriesName) {
                val result = XYChart.Series<Number, Number>(seriesName, FXCollections.observableArrayList())
                mainChart.data.add(result)
                result
            }

            val data = XYChart.Data<Number, Number>(epoch, value)
            when (type) {
                "Buy" -> data.node = Circle(5.0, Paint.valueOf("#673AB7"))
                "Sell" -> data.node = Circle(5.0, Paint.valueOf("#4CAF50"))
                "BadSell" -> data.node = Circle(5.0, Paint.valueOf("#F44336"))
                "GoodSell" -> data.node = Circle(5.0, Paint.valueOf("#43A047"))
                else -> data.node = Circle(0.0).apply { isVisible = false }
            }
            if (description != null && data.node != null) {
                Tooltip.install(data.node, Tooltip(description).apply { hackTooltipStartTiming(this) })
            }
            series.data.add(data)
            //adjustYRangeByXBounds(mainChart)
        }
    }

    private var lastExtraXAxis: NumberAxis? = null

    fun addPointExtra(chartId: String, type: String, epoch: Long, value: Double, description: String? = null) {
        Platform.runLater {
            val chart = extraCharts.getOrPut(chartId) {
                val xAxis = NumberAxis("Date", 0.0, 8.0, 1.0).apply {
                    upperBoundProperty().bind((mainChart.xAxis as NumberAxis).upperBoundProperty())
                    lowerBoundProperty().bind((mainChart.xAxis as NumberAxis).lowerBoundProperty())
                    tickUnitProperty().bind((mainChart.xAxis as NumberAxis).tickUnitProperty())
                    tickLabelFormatterProperty().bind((mainChart.xAxis as NumberAxis).tickLabelFormatterProperty())
                    animatedProperty().bind(mainChart.xAxis.animatedProperty())
                }
                val yAxis = NumberAxis(chartId, 0.0, 8.0, 1.0).apply {
                    tickUnitProperty().bind(Bindings.divide(Bindings.subtract(upperBoundProperty(), lowerBoundProperty()), 3.0))
                }
                val result = LineChart<Number, Number>(xAxis, yAxis, FXCollections.observableArrayList<XYChart.Series<Number, Number>>()).apply {
                    //isCache = true
                    //isCacheShape = true
                    prefHeightProperty().bind(Bindings.divide(mainChart.prefHeightProperty(), 5.0))
                }

                extraChartsSeries[chartId] = mutableMapOf()
                hbox.children.add(result)

                val bottomXAxis = lastExtraXAxis ?: mainChart.xAxis
                bottomXAxis.apply {
                    //isTickLabelsVisible = false
                    //isTickMarkVisible = false
                    //isVisible = false
                    //opacity = 0.0
                }
                lastExtraXAxis = xAxis

                (mainChart.xAxis as NumberAxis).lowerBoundProperty().addListener { _, _, _ -> adjustYRangeByXBounds(result) }
                (mainChart.xAxis as NumberAxis).upperBoundProperty().addListener { _, _, _ -> adjustYRangeByXBounds(result) }

                result
            }

            val series = extraChartsSeries[chartId]!!.getOrPut(type) {
                val result = XYChart.Series<Number, Number>(type, FXCollections.observableArrayList())
                chart.data.add(result)
                result
            }

            val dataPoint = XYChart.Data<Number, Number>(epoch, value)
            if (description == null) {
                dataPoint.node = Circle(0.0).apply { isVisible = false }
            } else {
                dataPoint.node = Circle(3.0, Paint.valueOf("#673AB7"))
                Tooltip.install(dataPoint.node, Tooltip(description).apply { hackTooltipStartTiming(this) })
            }
            series.data.add(dataPoint)
            //adjustYRangeByXBounds(chart)
        }
    }

    fun fix() {
        adjustYRangeByXBounds(mainChart)
        extraCharts.values.forEach { adjustYRangeByXBounds(it) }
    }
}