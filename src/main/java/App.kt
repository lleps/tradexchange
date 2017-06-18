import com.cf.client.poloniex.PoloniexExchangeService
import javafx.application.Application
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableList
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
import javafx.scene.control.Tooltip
import javafx.scene.paint.Paint
import javafx.scene.shape.Circle

class App : Application() {
    private lateinit var chart: LineChart<Number,Number>

    // TODO cleanup for commented code, unused variables and commented blocks
    // TODO modularise as much as possible, in order to keep trade logic clean.
    // TODO fix tick units for charts
    @Suppress("UNCHECKED_CAST")
    private fun createContent(): Parent {
        val data = FXCollections.observableArrayList<XYChart.Series<Double, Double>>()
        val xAxis = NumberAxis("date", 0.0, 8.0, 1.0).apply {
            upperBound = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond().toDouble()
            lowerBound = upperBound - (3600*24*2)
            tickLabelFormatter = object : StringConverter<Number>() {
                override fun toString(t: Number) = Instant
                        .ofEpochSecond(t.toLong())
                        .atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

                override fun fromString(string: String): Number = TODO()
            }
        }
        val yAxis = NumberAxis("price", 0.0, 8.0, 1.0).apply { tickUnit = 25.0 }
        chart = LineChart<Number,Number>(xAxis, yAxis, data as ObservableList<XYChart.Series<Number, Number>>)
        chart.setOnScroll {
            xAxis.lowerBound -= 3600*2 * if (it.deltaY < 0.0) 1.0 else -1.0
            adjustPriceRangeByXBounds()
        }
        var lastX = 0.0
        chart.setOnMouseMoved { lastX = it.x }
        chart.setOnMouseDragged {
            val delta = it.x - lastX
            xAxis.lowerBound += 900 * -delta
            xAxis.upperBound += 900 * -delta
            lastX = it.x
            adjustPriceRangeByXBounds()
        }
        chart.animated = false
        return chart
    }

    private fun adjustPriceRangeByXBounds() {
        val xAxis = chart.xAxis as NumberAxis
        val pricesBetweenRange = chart.data
                .flatMap { it.data }
                .asSequence()
                .filter { it.xValue.toInt() >= xAxis.lowerBound && it.xValue.toInt() <= xAxis.upperBound }
                .map { it.yValue.toDouble() }

        val yAxis = chart.yAxis as NumberAxis
        yAxis.upperBound = pricesBetweenRange.max() ?: 0.0
        yAxis.lowerBound = pricesBetweenRange.min() ?: 0.0
        xAxis.tickUnit = (xAxis.upperBound - xAxis.lowerBound) / 20
    }

    private val series = mutableMapOf<String, XYChart.Series<Double, Double>>()

    @Suppress("UNCHECKED_CAST")
    private fun addPoint(timeEpoch: Number,
                         price: Double,
                         type: String,
                         extra: String? = null) {
        Platform.runLater {
            val seriesName = if (type == "BUY" || type == "SELL" || type == "BIGBUY" || type == "BIGSELL") "PRICE" else type
            val series = series.getOrPut(seriesName) {
                val result = XYChart.Series<Double, Double>(seriesName, FXCollections.observableArrayList())
                chart.data.add(result as XYChart.Series<Number, Number>)
                result
            }

            val data = XYChart.Data<Number, Number>(timeEpoch, price)
            when (type) {
                "BUY" -> data.node = Circle(6.0, Paint.valueOf("#673AB7"))
                "SELL" -> data.node = Circle(6.0, Paint.valueOf("#4CAF50"))
                "BIGBUY" -> data.node = Circle(5.0, Paint.valueOf("#E040FB"))
                "BIGSELL" -> data.node = Circle(5.0, Paint.valueOf("#795548"))
                else -> data.node = Circle(0.0)
            }
            if (extra != null && data.node != null) {
                Tooltip.install(data.node, Tooltip(extra).apply { hackTooltipStartTiming(this) })
            }
            series.data.add(data as XYChart.Data<Double, Double>)
            adjustPriceRangeByXBounds()
        }
    }

    private var balance = Balance(10.000, 10.0)

    override fun start(stage: Stage) {
        stage.scene = Scene(createContent())
        stage.show()

        thread(start = true, isDaemon = true) {
            val apiKey = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK"
            val apiSecret = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"
            val poloniex = PoloniexExchangeService(apiKey, apiSecret)
            val pair = "USDT_BTC"
            val backTestDays = 14L
            val startEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(backTestDays + 1).toEpochSecond()
            val period = 1800L
            val fullData = poloniex.returnChartData(pair, period, startEpoch)
            val warmUpPeriods = (3600*24) / period.toInt()
            val testableData = fullData.subList(warmUpPeriods, fullData.size)

            val firstPrice = testableData[0].price
            testableData.forEachIndexed { index, data ->
                handle(period, data.price, fullData.subList(0, warmUpPeriods+index).map { it.price }, data.date.toLong())
            }
            if (balance.coins < 0.0) {
                println("recovering balance: ${balance.coins}")
                balance = balance.buy(-balance.coins, testableData.last().price)
            }
            println("trading: $balance | holding: ${Balance(usd = testableData.last().price - firstPrice, coins = 0.0)}")
        }
    }

    private var lastSellPrice = 0.0
    private var lastBuyPrice = Double.MAX_VALUE

    private fun handle(period: Long, price: Double, history: List<Double>, epoch: Long) {
        val max24h = history.takeLast(period.forHours(24)).max()!!
        val min24h = history.takeLast(period.forHours(24)).min()!!

        val beforeMacdLocalMax = history.dropLast(1).macd().histogram > history.macd().histogram
                        && history.dropLast(1).macd().histogram > history.dropLast(2).macd().histogram
        val beforeMacdLocalMin = history.dropLast(1).macd().histogram < history.macd().histogram
                && history.dropLast(1).macd().histogram < history.dropLast(2).macd().histogram

        val macdSellSignal = history.dropLast(1).macd().histogram < 0 && history.macd().histogram > 0
        val macdBuySignal = history.dropLast(1).macd().histogram > 0 && history.macd().histogram < 0
        val macdCrossedOverCenterLine = history.dropLast(1).macd(20, 30).macd < 0 && history.macd(20, 30).macd > 0
        val macdCrossedBelowCenterLine = history.dropLast(1).macd(20, 30).macd > 0 && history.macd(20, 30).macd < 0

        val macd = history.macd().macd
        val macdPrev = history.dropLast(1).macd().macd
        val macdPrevPrev = history.dropLast(2).macd().macd
        val macdMacdLocalMax = macdPrevPrev > 0 && macdPrev > 0 && macd > 0 && listOf(macdPrevPrev, macdPrev, macd).isLocalMaximum()
        val macdMacdLocalMin = macdPrevPrev < 0 && macdPrev < 0 && macd < 0 && listOf(macdPrevPrev, macdPrev, macd).isLocalMinimum()
        /*if (price > history.ema(10)
                && (macdSellSignal || (beforeMacdLocalMax && price-lastSellPrice > (max24h-min24h)/10.0)
                && (price - history.ema(10) > (lastSellPrice - lastSellEma)*1.5)) && balance.coins > 0.0) {
            addPoint(epoch, price, "SELL", "MACD sellSignal: $macdSellSignal | offFromLastSell: ${price-lastSellPrice}/${(max24h-min24h)/10.0}")
            balance = balance.sell(1.0, price)
            lastSellPrice = price
            lastSellEma = history.ema(10)
        }
        else if (balance.usd > price
                && price < history.ema(10)
                && (macdBuySignal || (beforeMacdLocalMin && price-lastBuyPrice < (max24h-min24h)/10.0)
                && (price - history.ema(10) < (lastBuyPrice - lastBuyEma)*1.5))) {
            addPoint(epoch, price, "BUY", "MACD sellSignal: $macdSellSignal | offFromLastBuy: ${price-lastBuyPrice}/${(max24h-min24h)/10.0}")
            balance = balance.buy(1.0, price)
            lastBuyPrice = price
            lastBuyEma = history.ema(10)
        }

        if (price - history.ema(10) > ((max24h-min24h)/10.0) && history.rsi(14) > 70.0 && price-lastSellPrice > (max24h-min24h)/10.0
                && balance.coins > 0.0) {
            addPoint(epoch, price, "BIGSELL")
            balance = balance.sell(1.0, price)
            lastSellPrice = price
            lastBuyPrice = 999999.0
        } else if (
        balance.usd > price && price - history.ema(10) < -((max24h-min24h)/10.0) && history.rsi(14) < 30.0 && price-lastBuyPrice < (max24h-min24h)/10.0) {
            addPoint(epoch, price, "BIGBUY")
            balance = balance.buy(1.0, price)
            lastBuyPrice = price
            lastSellPrice = 0.0
        }*/
        if (macdMacdLocalMax && price > history.sma(20) && price-lastSellPrice > (max24h-min24h)/5.0) {
            addPoint(epoch, price, "SELL", "previous: ${history.dropLast(1).macd().macd}\nnew: ${history.macd().macd}")
            lastSellPrice = price
            lastBuyPrice = 9999999.9
        } else if (macdMacdLocalMin && price < history.sma(20) && price-lastBuyPrice < (max24h-min24h)/5.0) {
            addPoint(epoch, price, "BUY", "previous: ${history.dropLast(1).macd().macd}\nnew: ${history.macd().macd}")
            lastBuyPrice = price
            lastSellPrice = 0.0
        }
        else {
            addPoint(epoch, price, "PRICE")
        }
    }

    private data class Balance(val usd: Double, val coins: Double) {
        fun sell(coins: Double, price: Double) = Balance(this.usd + (price * coins), this.coins - coins)
        fun buy(coins: Double, price: Double) = Balance(this.usd - (price * coins), this.coins + coins)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            launch(App::class.java)
        }
    }
}
