import com.cf.client.poloniex.PoloniexExchangeService
import com.cf.data.model.poloniex.PoloniexChartData
import javafx.application.Application
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.geometry.Orientation
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.chart.*
import javafx.stage.Stage
import javafx.util.StringConverter
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.concurrent.thread
import java.time.format.DateTimeFormatter
import javafx.scene.chart.XYChart
import javafx.scene.control.Button
import javafx.scene.layout.BorderPane
import javafx.scene.layout.FlowPane
import javafx.scene.paint.Paint
import javafx.scene.shape.Circle

class App : Application() {
    enum class SeriesType { PRICE, BIG_BUY, SMALL_BUY, BIG_SELL, SMALL_SELL, MA_A, MA_B, MA_DIFF, MACD, SIGNAL, MACD_REF }

    private lateinit var chart: LineChart<Number,Number>
    private lateinit var priceSeries: XYChart.Series<Double, Double>
    private lateinit var sma50Series: XYChart.Series<Double, Double>
    private lateinit var ema30Series: XYChart.Series<Double, Double>
    private lateinit var ema20Series: XYChart.Series<Double, Double>
    private lateinit var macdSeries: XYChart.Series<Double, Double>
    private lateinit var signalSeries: XYChart.Series<Double, Double>
    private lateinit var macdRef: XYChart.Series<Double, Double>

    private var balance = Balance(0.0, 0.0)

    @Suppress("UNCHECKED_CAST")
    private fun createContent(): Parent {
        val data = FXCollections.observableArrayList(
                XYChart.Series<Double, Double>("price", FXCollections.observableArrayList())
                        .also { priceSeries = it },
                XYChart.Series<Double, Double>("SMA50", FXCollections.observableArrayList())
                        .also { sma50Series = it },
                XYChart.Series<Double, Double>("EMA30", FXCollections.observableArrayList())
                        .also { ema30Series = it},
                XYChart.Series<Double, Double>("EMA20", FXCollections.observableArrayList())
                        .also { ema20Series = it},
                XYChart.Series<Double, Double>("EMA20 - EMA30", FXCollections.observableArrayList())
                        .also { macdSeries = it},
                XYChart.Series<Double, Double>("SIGNAL", FXCollections.observableArrayList())
                        .also { signalSeries = it},
                XYChart.Series<Double, Double>("MACD-REF", FXCollections.observableArrayList())
                        .also { macdRef = it})
        val xAxis = NumberAxis("date", 0.0, 8.0, 1.0).apply {
            upperBound = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond().toDouble()
            lowerBound = upperBound - (3600*24*2)
            tickUnit = (upperBound - lowerBound) / 20
            tickLabelFormatter = object : StringConverter<Number>() {
                override fun toString(t: Number) = Instant
                        .ofEpochSecond(t.toLong())
                        .atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

                override fun fromString(string: String): Number = TODO()
            }
        }
        val yAxis = NumberAxis("price", 0.0, 8.0, 1.0).apply {
            isForceZeroInRange = false
            isAutoRanging = true
        }
        chart = LineChart<Number,Number>(xAxis, yAxis, data as ObservableList<XYChart.Series<Number, Number>>)
        val buttonsPane = FlowPane(Orientation.HORIZONTAL)
        buttonsPane.children.add(Button("-24h").apply {
            setOnAction {
                xAxis.lowerBound -= (3600*24)
                xAxis.upperBound -= (3600*24)
                adjustPriceRange()
            }
        })
        buttonsPane.children.add(Button("+24h").apply {
            setOnAction {
                xAxis.lowerBound += (3600*24)
                xAxis.upperBound += (3600*24)
                adjustPriceRange()
            }
        })
        buttonsPane.children.add(Button("-1h").apply {
            setOnAction {
                xAxis.lowerBound -= (3600*1)
                xAxis.upperBound -= (3600*1)
                adjustPriceRange()
            }
        })
        buttonsPane.children.add(Button("+1h").apply {
            setOnAction {
                xAxis.lowerBound += (3600*1)
                xAxis.upperBound += (3600*1)
                adjustPriceRange()
            }
        })
        val rootPane = BorderPane()
        rootPane.center = chart
        rootPane.bottom = buttonsPane
        return rootPane
    }

    // TODO push a version using simple averages, then try to implement a MACD strategy using all MA indicators to see whats going on.
    private fun adjustPriceRange() {
        // based on items between x-low, x-high, set y-low and y-high
        val xAxis = chart.xAxis as NumberAxis
        //val yAxis = chart.yAxis as NumberAxis
        val dataBetweenBounds = priceSeries.data
                .filter { it.xValue.toInt() > xAxis.lowerBound && it.xValue.toInt() < xAxis.upperBound }
        if (dataBetweenBounds.isEmpty()) return
        //yAxis.upperBound = dataBetweenBounds.map { it.yValue.toDouble() }.max()!!
        //yAxis.lowerBound = dataBetweenBounds.map { it.yValue.toDouble() }.min()!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun addPoint(timeEpoch: Number,
                         price: Double,
                         seriesType: SeriesType) {
        Platform.runLater {
            val series = when (seriesType) {
                SeriesType.PRICE, SeriesType.BIG_BUY, SeriesType.SMALL_BUY,
                SeriesType.BIG_SELL, SeriesType.SMALL_SELL -> priceSeries
                SeriesType.MA_A -> sma50Series
                SeriesType.MA_B -> ema30Series
                SeriesType.MA_DIFF -> ema20Series
                SeriesType.MACD -> macdSeries
                SeriesType.SIGNAL -> signalSeries
                SeriesType.MACD_REF -> macdRef
            }
            val data = XYChart.Data<Number, Number>(timeEpoch, price)
            when (seriesType) {
                SeriesType.BIG_BUY -> data.node = Circle(5.0, Paint.valueOf("#673AB7"))
                SeriesType.SMALL_BUY -> data.node = Circle(5.0, Paint.valueOf("#B39DDB"))
                SeriesType.BIG_SELL -> data.node = Circle(5.0, Paint.valueOf("#4CAF50"))
                SeriesType.SMALL_SELL -> data.node = Circle(5.0, Paint.valueOf("#A5D6A7"))
                else -> data.node = Circle(0.0)
            }
            series.data.add(data as XYChart.Data<Double, Double>)
            adjustPriceRange()
        }
    }

    override fun start(stage: Stage) {
        stage.scene = Scene(createContent())
        stage.show()

        thread(start = true, isDaemon = true) {
            val apiKey = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK"
            val apiSecret = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"
            val poloniex = PoloniexExchangeService(apiKey, apiSecret)
            val pair = "USDT_ETH"
            val backtestDays = 8L
            val startEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(backtestDays + 1).toEpochSecond()
            val period = 1800L
            val fullData = poloniex.returnChartData(pair, period, startEpoch)
            val warmUpPeriods = (3600*24) / period.toInt()
            val testableData = fullData.subList(warmUpPeriods, fullData.size)

            testableData.forEachIndexed { index, data ->
                handle(period, data, fullData.subList(0, warmUpPeriods+index), data.date.toLong())
            }
            println("balance: $balance")
        }
    }

    private var oldHistogram = 0.0

    private fun handle(period: Long,
                       actualData: PoloniexChartData,
                       history: List<PoloniexChartData>, epoch: Long) {
        val price = actualData.price
        val maA = history.ema(period.periodsForHours(3).toInt())
        val maB = history.ema(period.periodsForHours(15).toInt())
        val historyReversed = history.reversed().map { it.price }
        val offFromA = actualData.price - maA
        val offFromB = actualData.price - maB
        val low5 = 5.0 * (history.maxPriceHours(period, 24) - history.minPriceHours(period, 24)) / 100.0
        val low20 = 20.0 * (history.maxPriceHours(period, 24) - history.minPriceHours(period, 24)) / 100.0

        val a = (historyReversed[14] + historyReversed[13] + historyReversed[12] + historyReversed[11] + historyReversed[10]) / 5.0 // [2] // 5,4 8,7,6
        val b = (historyReversed[9] + historyReversed[8] + historyReversed[7] + historyReversed[6] + historyReversed[5]) / 5.0 // [1] // 3,2 5,4,3
        val c = (historyReversed[4] + historyReversed[3] + historyReversed[2] + historyReversed[1] + historyReversed[0]) / 5.0 // [0] // 1,0 2,1,0

        val bigAmount = 4.0
        val smallAmount = 1.0

        addPoint(epoch, history.sma(50), SeriesType.MA_A)
        addPoint(epoch, history.ema(30), SeriesType.MA_B)
        addPoint(epoch, history.ema(20), SeriesType.MA_DIFF)

        val macd = history.ema(20) - history.ema(30)
        val signalPeriod = 10
        val macdLine = mutableListOf<Double>()
        (signalPeriod-1 downTo 0).forEach {
            val historyAtTheTime = history.dropLast(it)
            macdLine += historyAtTheTime.ema(20) - historyAtTheTime.ema(30)
        }
        val signal = macdLine.emaDouble(signalPeriod)
        val newHistogram = macd - signal

        if (oldHistogram < 0.0 && newHistogram > 0.0) {
            addPoint(epoch, price, SeriesType.BIG_SELL)
            balance = balance.sell(bigAmount, price)
        } else if (oldHistogram > 0.0 && newHistogram < 0.0) {
            addPoint(epoch, price, SeriesType.BIG_BUY)
            balance = balance.buy(bigAmount, price)
        }

        /*else if (    offFromA > low5 &&
                historyReversed[1] > historyReversed[0] &&
                historyReversed[1] > historyReversed[2]) {
            addPoint(epoch, price, SeriesType.SMALL_SELL)
            balance = balance.sell(smallAmount, price)
        } else if (
                offFromA < -low5 &&
                historyReversed[1] < historyReversed[0] &&
                historyReversed[1] < historyReversed[2]) {
            addPoint(epoch, price, SeriesType.SMALL_BUY)
            balance = balance.buy(smallAmount, price)
        }*/ else {
            addPoint(epoch, price, SeriesType.PRICE)
        }

        oldHistogram = newHistogram

        //addPoint(epoch, 250.0, SeriesType.MACD)
        //addPoint(epoch, 250.0 + histogram, SeriesType.SIGNAL)
    }

    private data class Balance(val usd: Double, val coins: Double) {
        fun sell(coins: Double, price: Double) = Balance(this.usd + (price*coins), this.coins - coins)
        fun buy(coins: Double, price: Double) = Balance(this.usd - (price * coins), this.coins + coins)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            launch(App::class.java)
        }
    }
}
