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
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.concurrent.thread

class App : Application() {
    private lateinit var chart: ScatterChart<Number,Number>

    private val backtestDays = 30L

    @Suppress("UNCHECKED_CAST")
    private fun createContent(): Parent {
        val data = FXCollections.observableArrayList(
                XYChart.Series<Double, Double>("price", FXCollections.observableArrayList()),
                XYChart.Series<Double, Double>("buy", FXCollections.observableArrayList()),
                XYChart.Series<Double, Double>("sell", FXCollections.observableArrayList()))
        val xAxis = NumberAxis("time (sec)", 0.0, 8.0, 1.0).apply {
            lowerBound = ZonedDateTime.now(ZoneOffset.UTC).minusDays(backtestDays).toEpochSecond().toDouble()
            upperBound = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond().toDouble()
            tickUnit = (upperBound - lowerBound) / 20
        }
        val yAxis = NumberAxis("price (usd)", 0.0, 8.0, 1.0).apply {
            lowerBound = 5.0
            upperBound = 24.0
        }
        chart = ScatterChart<Number,Number>(xAxis, yAxis, data as ObservableList<XYChart.Series<Number, Number>>)
        chart.setOnScroll {
            if (it.deltaY < -0.4) {
                xAxis.upperBound -= 3600*24
            } else if (it.deltaY > 0) {
                xAxis.upperBound += 3600*24
            }
            println("${it.deltaY}")
            ////xAxis.upperBound += it.deltaX
        }
        return chart
    }

    enum class PointType { PRICE, BUY, SELL }

    private fun addPointToChart(timeEpoch: Number,
                                price: Double,
                                pointType: PointType,
                                extra: String = "none",
                                balance: Double = 0.0,
                                coins: Double = 0.0) {
        Platform.runLater {
            val d = XYChart.Data<Number, Number>(timeEpoch, price)
            val dateReadable = Instant.ofEpochSecond(timeEpoch.toLong()).atZone(ZoneOffset.UTC).toString()
            val tooltip = Tooltip("$dateReadable\nprice: $price USDT\nextra: $extra\n" +
                    "balance: $balance\n" +
                    "coins: $coins")
            chart.data[pointType.ordinal].data.add(d)
            hackTooltipStartTiming(tooltip)
            Tooltip.install(d.node, tooltip)
        }
    }

    override fun start(stage: Stage) {
        stage.scene = Scene(createContent())
        stage.show()

        thread(start = true, isDaemon = true) {
            val apiKey = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK"
            val apiSecret = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"
            val poloniex = PoloniexExchangeService(apiKey, apiSecret)
            val pair =  "USDT_ETC"
            val startEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(backtestDays + 1).toEpochSecond()
            val period = 1800L
            val fullData = poloniex.returnChartData(pair, period, startEpoch)
            val criteriaList = listOf(PreviousAverageCriteria())
            var coins = 100.0
            var balance = 1000.0

            fullData.subList(1, fullData.size).forEachIndexed { candleIndex, candle ->
                val candleTimeEpoch = candle.date.toLong()
                val average = (candle.close.toDouble() + candle.open.toDouble()) / 2.0
                val history = fullData.filterIndexed { index, _ -> index < (candleIndex+1) }
                val decision = criteriaList.sumByDouble { it.evaluate(period, candle, history) }

                addPointToChart(candleTimeEpoch, average, PointType.PRICE)
                if (decision > 0.1) {
                    val amount = Math.abs(decision)
                    coins -= amount
                    balance += amount * average
                    addPointToChart(candleTimeEpoch, average, PointType.SELL, decision.toString(), balance, coins)
                } else if (decision < -0.1) {
                    val amount = Math.abs(decision)
                    coins += amount
                    balance -= amount * average
                    addPointToChart(candleTimeEpoch, average, PointType.BUY, decision.toString(), balance, coins)
                }
            }
            println("coins: $coins | balance: $balance")
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            launch(App::class.java)
        }
    }
}
