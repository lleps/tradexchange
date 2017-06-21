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
    private val hbox = VBox(35.0)
    private val mainChart: LineChart<Number, Number>
    private val mainChartSeries = mutableMapOf<String, XYChart.Series<Number, Number>>()
    private val extraCharts = mutableMapOf<String, LineChart<Number, Number>>()
    private val extraChartsSeries = mutableMapOf<String, MutableMap<String, XYChart.Series<Number, Number>>>()

    init {
        val xAxis = NumberAxis("date", 0.0, 8.0, 1.0).apply {
            upperBound = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond().toDouble()
            lowerBound = upperBound - (3600*24*2)
            tickUnit = (3600*24*7).toDouble()
            tickLabelFormatter = object : StringConverter<Number>() {
                override fun toString(t: Number) = Instant
                        .ofEpochSecond(t.toLong())
                        .atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

                override fun fromString(string: String): Number = TODO()
            }
        }

        val yAxis = NumberAxis("price", 0.0, 8.0, 1.0)
        yAxis.tickUnit = 50.0
        mainChart = LineChart<Number, Number>(xAxis, yAxis, FXCollections.observableArrayList<XYChart.Series<Number, Number>>()).apply {
            setOnScroll {
                xAxis.lowerBound -= 3600*2 * if (it.deltaY < 0.0) 1.0 else -1.0
                adjustYRangeByXBounds(this)
            }
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
            isLegendVisible = false
        }
        hbox.children.add(mainChart)
        hbox.alignment = Pos.TOP_CENTER
        node.center = hbox
    }

    private fun adjustYRangeByXBounds(chart: LineChart<Number, Number>) {
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

    fun addPoint(type: String, epoch: Long, value: Double, description: String? = null) {
        Platform.runLater {
            val seriesName = if (type == "BUY" || type == "SELL" || type == "BIGBUY" || type == "BIGSELL") "PRICE" else type
            val series = mainChartSeries.getOrPut(seriesName) {
                val result = XYChart.Series<Number, Number>(seriesName, FXCollections.observableArrayList())
                mainChart.data.add(result)
                result
            }

            val data = XYChart.Data<Number, Number>(epoch, value)
            when (type) {
                "BUY" -> data.node = Circle(4.0, Paint.valueOf("#673AB7"))
                "SELL" -> data.node = Circle(4.0, Paint.valueOf("#4CAF50"))
                "BIGBUY" -> data.node = Circle(6.0, Paint.valueOf("#673AB7"))
                "BIGSELL" -> data.node = Circle(6.0, Paint.valueOf("#4CAF50"))
                else -> data.node = Circle(0.0)
            }
            if (description != null && data.node != null) {
                Tooltip.install(data.node, Tooltip(description).apply { hackTooltipStartTiming(this) })
            }
            series.data.add(data)
            adjustYRangeByXBounds(mainChart)
        }
    }

    private var lastAddedXAxis: NumberAxis? = null

    fun addPointExtra(chartId: String, type: String, epoch: Long, value: Double, description: String? = null) {
        Platform.runLater {
            // Get or create chart
            val chart = extraCharts.getOrPut(chartId) {
                val xAxis = NumberAxis("date", 0.0, 8.0, 1.0).apply {
                    upperBoundProperty().bind((mainChart.xAxis as NumberAxis).upperBoundProperty())
                    lowerBoundProperty().bind((mainChart.xAxis as NumberAxis).lowerBoundProperty())
                    tickUnit = (3600*24*7).toDouble()
                    tickLabelFormatter = object : StringConverter<Number>() {
                        override fun toString(t: Number) = Instant
                                .ofEpochSecond(t.toLong())
                                .atZone(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

                        override fun fromString(string: String): Number = TODO()
                    }
                }
                val yAxis = NumberAxis("value", 0.0, 8.0, 1.0)
                yAxis.tickUnit = 50.0
                val result = LineChart<Number, Number>(xAxis, yAxis, FXCollections.observableArrayList<XYChart.Series<Number, Number>>())
                result.animated = false
                result.prefHeightProperty().bind(Bindings.divide(mainChart.heightProperty(), 3.0))
                result.maxHeightProperty().bind(Bindings.divide(mainChart.heightProperty(), 3.0))
                result.minHeightProperty().bind(Bindings.divide(mainChart.heightProperty(), 3.0))
                result.prefWidthProperty().bind(mainChart.widthProperty())

                extraChartsSeries[chartId] = mutableMapOf()
                hbox.children.add(result)

                // Hide main x axis stuff
                mainChart.xAxis.isTickLabelsVisible = false
                mainChart.xAxis.isTickMarkVisible = false
                mainChart.xAxis.isVisible = false
                mainChart.xAxis.opacity = 0.0

                // Hide last chart X axis
                lastAddedXAxis?.let {
                    it.opacity = 0.0
                    it.isVisible = false
                    it.isTickMarkVisible = false
                    it.isTickLabelsVisible = false
                }

                lastAddedXAxis = xAxis
                // Update ranges when change
                (mainChart.xAxis as NumberAxis).lowerBoundProperty().addListener { _, _, _ -> adjustYRangeByXBounds(result) }
                (mainChart.xAxis as NumberAxis).upperBoundProperty().addListener { _, _, _ -> adjustYRangeByXBounds(result) }
                result
            }

            // Get or create series
            val series = extraChartsSeries[chartId]!!.getOrPut(type) {
                val result = XYChart.Series<Number, Number>(type, FXCollections.observableArrayList())
                chart.data.add(result)
                result
            }

            // add point
            val dataPoint = XYChart.Data<Number, Number>(epoch, value)
            if (description == null) {
                dataPoint.node = Circle(0.0)
            } else {
                dataPoint.node = Circle(3.0, Paint.valueOf("#673AB7"))
                Tooltip.install(dataPoint.node, Tooltip(description).apply { hackTooltipStartTiming(this) })
            }
            series.data.add(dataPoint)
            adjustYRangeByXBounds(chart)
        }
    }
}