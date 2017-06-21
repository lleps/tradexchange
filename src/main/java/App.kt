import com.cf.client.poloniex.PoloniexExchangeService
import javafx.application.Application
import javafx.scene.Scene
import javafx.stage.Stage
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.concurrent.thread

class App : Application() {
    private lateinit var chart: TradeChart

    private var balance = Balance(10.000, 10.0)
    private var lastSellPrice = 0.0
    private var lastBuyPrice = Double.MAX_VALUE
    private var periodId = 0
    private var lastBuyPeriodId = 0
    private var lastSellPeriodId = 0

    private fun handle(period: Long, price: Double, history: List<Double>, epoch: Long) {
        val macd = history.macd()
        val rsi = history.rsi(14)
        var macdSellWeight = 0.0
        if (macd.histogram >= 0.0) {
            macdSellWeight += 20.0
        }
        if (macd.macd > 0) {

        }
        val last3MacdMacd = listOf(history.dropLast(2).macd().macd, history.dropLast(1).macd().macd, history.macd().macd)
        if (last3MacdMacd.isLocalMaximum()) {
            chart.addPoint("SELL", epoch, price, "previous: ${history.dropLast(1).macd().macd}\nnew: ${history.macd().macd}")
            lastSellPrice = price
            lastBuyPrice = 9999999.9
            balance = balance.sell(1.0, price)
            lastSellPeriodId = periodId
        } else if (last3MacdMacd.isLocalMinimum()) {
            chart.addPoint("BUY", epoch, price, "previous: ${history.dropLast(1).macd().macd}\nnew: ${history.macd().macd}")
            lastBuyPrice = price
            lastSellPrice = 0.0
            lastBuyPeriodId = periodId
            balance = balance.buy(1.0, price)
        } else {
            chart.addPoint("PRICE", epoch, price)
        }
        chart.addPoint("EMA(15)", epoch, history.ema(15))

        chart.addPointExtra("MACD", "macd", epoch, history.macd().macd)
        chart.addPointExtra("MACD", "signal", epoch, history.macd().signal)
        chart.addPointExtra("MACD", "histogram", epoch, history.macd().histogram)
        chart.addPointExtra("RSI", "rsi", epoch, history.rsi(14))
        chart.addPointExtra("RSI", "rsi-70", epoch, 70.0)
        chart.addPointExtra("RSI", "rsi-30", epoch, 30.0)
        periodId++
    }


    override fun start(stage: Stage) {
        chart = TradeChart()
        stage.scene = Scene(chart.node)
        stage.show()

        thread(start = true, isDaemon = true) {
            val apiKey = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK"
            val apiSecret = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"
            val poloniex = PoloniexExchangeService(apiKey, apiSecret)
            val pair = "USDT_ETH"
            val backTestDays = 14L
            val startEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(backTestDays + 1).toEpochSecond()
            val period = 1800L
            val fullData = poloniex.returnChartData(pair, period, startEpoch)
            val warmUpPeriods = (3600*24) / period.toInt()
            val testableData = fullData.subList(warmUpPeriods, fullData.size)

            println("Testing...")
            val firstPrice = testableData[0].price
            testableData.forEachIndexed { index, data ->
                handle(period, data.price, fullData.subList(0, warmUpPeriods+index).map { it.price }, data.date.toLong())
            }

            if (balance.coins < 0.0) {
                println("Buying ${balance.coins} coins.")
                balance = balance.buy(-balance.coins, testableData.last().price)
            }
            println("Result: Trading: $balance | Holding: ${Balance(usd = testableData.last().price - firstPrice, coins = 0.0)}")
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
