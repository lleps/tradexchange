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
import javafx.util.StringConverter
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.concurrent.thread
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter





class App : Application() {
    private lateinit var chart: ScatterChart<Number,Number>

    private val backtestDays = 14L

    @Suppress("UNCHECKED_CAST")
    private fun createContent(): Parent {
        val data = FXCollections.observableArrayList(
                XYChart.Series<Double, Double>("price", FXCollections.observableArrayList()),
                XYChart.Series<Double, Double>("moving average", FXCollections.observableArrayList()),
                XYChart.Series<Double, Double>("buy", FXCollections.observableArrayList()),
                XYChart.Series<Double, Double>("sell", FXCollections.observableArrayList()),
                XYChart.Series<Double, Double>("max", FXCollections.observableArrayList()))
        val xAxis = NumberAxis("time (sec)", 0.0, 8.0, 1.0).apply {
            lowerBound = ZonedDateTime.now(ZoneOffset.UTC).minusDays(backtestDays).toEpochSecond().toDouble()
            upperBound = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond().toDouble()
            tickUnit = (upperBound - lowerBound) / 20
        }
        val yAxis = NumberAxis("price (usd)", 0.0, 8.0, 1.0).apply {
            lowerBound = 150.0
            upperBound = 380.0
        }
        chart = ScatterChart<Number,Number>(xAxis, yAxis, data as ObservableList<XYChart.Series<Number, Number>>)
        xAxis.tickLabelFormatter = object : StringConverter<Number>() {
            val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

            override fun toString(t: Number): String {
                val dateReadable = Instant.ofEpochSecond(t.toLong()).atZone(ZoneOffset.UTC).format(formatter)
                return dateReadable
            }

            override fun fromString(string: String): Number = TODO()
        }

        chart.setOnScroll {
            println("${it.isAltDown} ${it.isControlDown} ${it.isShiftDown}")
            if (it.isAltDown) {
                if (it.deltaY < -0.0) {
                    xAxis.upperBound -= 3600*3
                } else if (it.deltaY > 0) {
                    xAxis.upperBound += 3600*3
                }
            } else {
                if (it.deltaY < -0.0) {
                    xAxis.lowerBound -= 3600*3
                } else if (it.deltaY > 0) {
                    xAxis.lowerBound += 3600*3
                }
            }
        }
        return chart
    }

    enum class PointType { PRICE, MA, SELL, BUY, MAX }

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
            val pair =  "USDT_ETH"
            val startEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(backtestDays + 1).toEpochSecond()
            val period = 900L
            val fullData = poloniex.returnChartData(pair, period, startEpoch)
            val movingAveragePeriod = 10*2
            val usingCoins = 50.0
            var coinBalance = usingCoins
            var usdBalance = 0.0
            var soldCoins = 0
            val warmUpPeriods = (3600*24) / period.toInt()

            fullData.subList(warmUpPeriods, fullData.size).forEachIndexed { index, candle ->
                val absoluteIndex = index + warmUpPeriods
                val movingAverageCandles = fullData.filterIndexed { i, _ -> i >= (absoluteIndex - movingAveragePeriod) && i <= absoluteIndex }
                val movingAverage = movingAverageCandles
                        .sumByDouble { it.weightedAverage.toDouble() } / movingAverageCandles.size

                val candleTimeEpoch = candle.date.toLong()
                val candleAverage = candle.weightedAverage.toDouble()

                if (index % 10 == 0) addPointToChart(candleTimeEpoch, candleAverage, PointType.PRICE)
                //if (index % 8 == 0) addPointToChart(candleTimeEpoch, candleAverage, PointType.MAX)

                //addPointToChart(candleTimeEpoch, movingAverage, PointType.MA)

                val priceOffFromMA = candleAverage - movingAverage
                if (Math.abs(priceOffFromMA) > 2.5) {
                    val buy = priceOffFromMA < 0
                    val dataReversed = movingAverageCandles.reversed().map { it.weightedAverage.toDouble() }
                    if (!buy &&
                            dataReversed[1] > dataReversed[0] &&
                            dataReversed[1] > dataReversed[2]) { // Local maximum - SELL
                        if (soldCoins < 5) {
                            soldCoins++
                            coinBalance -= 2
                            usdBalance += candleAverage*2
                            addPointToChart(candleTimeEpoch, candleAverage, PointType.SELL, priceOffFromMA.toString(),
                                    usdBalance, coinBalance)
                            println("selling a coin at $candleAverage")
                        }
                    } else if (buy &&
                            dataReversed[1] < dataReversed[0] &&
                            dataReversed[1] < dataReversed[2]) { // Local minimum - BUY
                        if (soldCoins > 0) {
                            val totalPrice = (soldCoins*2) * candleAverage
                            println("buying $soldCoins coins at a total of $totalPrice")
                            usdBalance -= totalPrice
                            coinBalance += (soldCoins*2)
                            soldCoins = 0
                            addPointToChart(candleTimeEpoch, candleAverage, PointType.BUY, priceOffFromMA.toString(),
                                    usdBalance, coinBalance)
                        }
                        /*addPointToChart(candleTimeEpoch, candleAverage,
                            if (priceOffFromMA > 0) PointType.SELL else PointType.BUY,
                            priceOffFromMA.toString(),
                            usdBalance, coinBalance)*/
                    }
                }
                if (fullData.last() == candle) {
                    if (soldCoins > 0) {
                        val totalPrice = soldCoins * candleAverage
                        println("buying $soldCoins coins at a total of $totalPrice")
                        usdBalance -= totalPrice
                        coinBalance += soldCoins
                        soldCoins = 0
                        addPointToChart(candleTimeEpoch, candleAverage, PointType.BUY, priceOffFromMA.toString(),
                                usdBalance, coinBalance)
                    }
                }
            }
            println("usdBalance: $usdBalance | coinBalance: $coinBalance")
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            launch(App::class.java)
        }
    }
}
