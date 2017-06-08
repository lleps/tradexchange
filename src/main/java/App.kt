import com.cf.client.poloniex.PoloniexExchangeService
import javafx.application.Application
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.ScatterChart
import javafx.scene.chart.XYChart
import javafx.scene.control.Tooltip
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.*
import kotlin.concurrent.thread
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.util.Duration
import java.lang.reflect.AccessibleObject.setAccessible




class App : Application() {
    private lateinit var chart: ScatterChart<Number,Number>

    private val backtestDays = 2L

    @Suppress("UNCHECKED_CAST")
    private fun createContent(): Parent {
        val data = FXCollections.observableArrayList(
                XYChart.Series<Double, Double>("price", FXCollections.observableArrayList()),
                XYChart.Series<Double, Double>("buy", FXCollections.observableArrayList()),
                XYChart.Series<Double, Double>("sell", FXCollections.observableArrayList()))
        val xAxis = NumberAxis("time (sec)", 0.0, 8.0, 1.0).apply {
            lowerBound = ZonedDateTime.now(ZoneOffset.UTC).minusDays(backtestDays).toEpochSecond().toDouble()
            upperBound = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond().toDouble()
            //upperBound = 3600.0/2
            tickUnit = (upperBound - lowerBound) / 20
            //isAutoRanging = true
        }
        val yAxis = NumberAxis("price (usd)", 0.0, 8.0, 1.0).apply {
            lowerBound = 19.0
            upperBound = 15.0
            //isAutoRanging = true
        }
        chart = ScatterChart<Number,Number>(xAxis, yAxis, data as ObservableList<XYChart.Series<Number, Number>>)
        //chart.setOnScroll { e -> xAxis.upperBound += e.deltaY }
        return chart
    }

    enum class PointType { PRICE, BUY, SELL }

    private fun addPointToChart(timeEpoch: Number, price: Double, pointType: PointType) {
        Platform.runLater {
            val d = XYChart.Data<Number, Number>(timeEpoch, price)
            chart.data[pointType.ordinal].data.add(d)
            Tooltip.install(d.node, Tooltip("time: $timeEpoch - price: $price").apply { hackTooltipStartTiming(this) })

        }
    }

    fun hackTooltipStartTiming(tooltip: Tooltip) {
        try {
            val fieldBehavior = tooltip.javaClass.getDeclaredField("BEHAVIOR")
            fieldBehavior.isAccessible = true
            val objBehavior = fieldBehavior.get(tooltip)

            val fieldTimer = objBehavior.javaClass.getDeclaredField("activationTimer")
            fieldTimer.isAccessible = true
            val objTimer = fieldTimer.get(objBehavior) as Timeline

            objTimer.keyFrames.clear()
            objTimer.keyFrames.add(KeyFrame(Duration(15.0)))
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    override fun start(stage: Stage) {
        stage.scene = Scene(createContent())
        stage.show()

        /*
        Hacer programa que haga compras y ventas y las dibuje en un grafico
        con un dataset de 24 horas atras hasta ahora. El algoritmo operaría
        con información del grafico únicamente.

        main() {
            val period = ...
            val dataLast24hPeriod1800: List<Data> = ...
            for (d in dataLast24h) {
                // logic for buying, selling, etc.
                // depending on action, draw a point
            }
        }
         */
        thread(start = true, isDaemon = true) {
            val apiKey = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK"
            val apiSecret = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"
            val poloniex = PoloniexExchangeService(apiKey, apiSecret)
            val pair =  "USDT_ETC"
            val startEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(backtestDays + 1).toEpochSecond()
            val period = 1800L
            val fullData = poloniex.returnChartData(pair, period, startEpoch)
            var candleIndex = 1
            for (candle in fullData.subList(1, fullData.size)) {
                val dataLast24h = fullData.filterIndexed { index, data -> index < candleIndex}.reversed().take(48)
                val max24h = dataLast24h.maxBy { it.high.toDouble() }!!.high.toDouble()
                val min24h = dataLast24h.minBy { it.low.toDouble() }!!.low.toDouble()
                val average24h = (max24h + min24h) / 2.0
                val close = candle.close.toDouble()
                val open = candle.open.toDouble()
                val average = (close + open) / 2.0
                val candleTimeEpoch = candle.date.toLong()
                addPointToChart(candleTimeEpoch, average, PointType.PRICE)
                val percentSince24h = ((average - average24h) / average24h) * 100.0
                println("percent: $percentSince24h")
                if (candleIndex % 4 == 0) {
                    // if (percentLast24h > 5)
                    // else if (percentLast24h < -5)

                    if (average > average24h+0.1) {
                        addPointToChart(candleTimeEpoch, average, PointType.SELL)
                    } else if (average < average24h-0.1) {
                        addPointToChart(candleTimeEpoch, average, PointType.BUY)
                    }
                }
                candleIndex++
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            launch(App::class.java)
        }
    }
}
