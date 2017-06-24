import com.cf.client.poloniex.PoloniexExchangeService
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.concurrent.thread

class App : Application() {
    private lateinit var chart: TradeChart
    private val pair = "USDT_BTC"
    private val backTestDays = 20L
    private var balance = Balance(0.0, 0.0)
    private var lastSellPrice = 0.0
    private var lastBuyPrice = Double.MAX_VALUE
    private var periodId = 0
    private var lastBuyPeriodId = 0
    private var lastSellPeriodId = 0
    private var lastDiffFromMacd = 0.0
    private var coinId = 0
    private var consecutiveBuys = 0
    private var consecutiveSells = 0

    private class HoldingCoin(val id: Int, val price: Double) {
        override fun toString() = "$id (\$$price)"
    }

    private var boughtCoins = listOf<HoldingCoin>()

    private fun handle(period: Long, price: Double, history: List<Double>, epoch: Long) {
        val max24h = history.takeLast(period.forHours(24)).max()!!
        val min24h = history.takeLast(period.forHours(24)).min()!!
        val divergence24h = (max24h - min24h)
        val lastMacd = history.dropLast(1).macd()
        val macd = history.macd()
        val macdSellSignal = lastMacd.histogram < 0 && macd.histogram > 0
        val macdBuySignal = lastMacd.histogram > 0 && macd.histogram < 0
        val rsi = history.rsi(10)
        val lastRsi = history.dropLast(1).rsi(14)
        val rsiReturnedBelow70 = lastRsi > 70.0 && rsi < 70.0
        val rsiReturnedOver30 = lastRsi < 30.0 && rsi > 30.0
        val last3MacdMacd = listOf(history.dropLast(2).macd().macd, history.dropLast(1).macd().macd, history.macd().macd)
        val farAboveLastSell = price > lastSellPrice+divergence24h*0.2
        val farBelowLastBuy = price < lastBuyPrice-divergence24h*0.2
        val divergedFarAboveEma =  price > history.ema(10)+divergence24h*0.1
        val divergedFarBelowEma =  price < history.ema(10)-divergence24h*0.1
        val diffFromLastMacd = history.macd().histogram - history.dropLast(1).macd().histogram
        val lastLastHistogram = history.dropLast(2).macd().histogram
        val lastHistogram = history.dropLast(1).macd().histogram
        val histogram = history.macd().histogram
        val lastHistograms = listOf(lastLastHistogram, lastHistogram, histogram)
        val histogramLocalMax = lastHistograms.isLocalMaximum()
        val histogramLocalMix = lastHistograms.isLocalMinimum()
        if (price > history.ema(period.forHours(12))
                && lastHistogram > 0
                && histogram > 0
                && macd.macd > 0
                && diffFromLastMacd > lastDiffFromMacd*2
                && farAboveLastSell
                && boughtCoins.any { it.price < price-divergence24h*0.2 }) {
            val rsiMultiplier = if (rsi >= 70.0) 2 else 1
            consecutiveBuys = 0
            consecutiveSells += 1
            val soldCoins = boughtCoins.filter { it.price < price-divergence24h*0.2 }.take(consecutiveSells)
            lastSellPrice = price
            lastBuyPrice = 9999999.9
            balance = balance.sell(soldCoins.size.toDouble(), price)
            lastSellPeriodId = periodId
            chart.addPoint("Sell", epoch, price, "price $price\nsold ${soldCoins}\n$balance")
            boughtCoins -= soldCoins
        } else if (price < history.ema(period.forHours(12)) && lastHistogram < 0 && histogram < 0 && macd.macd < 0 && diffFromLastMacd < lastDiffFromMacd*2 && farBelowLastBuy /*history.isLocalMinimum() && farBelowLastBuy && divergedFarBelowEma*/) {
            // buy track
            lastBuyPrice = price
            lastSellPrice = 0.0
            lastBuyPeriodId = periodId

            // add to list
            val rsiMultiplier = if (rsi <= 30.0) 2 else 1
            consecutiveSells = 0
            consecutiveBuys += 1
            var coinsToBuy = emptyList<HoldingCoin>()
            for (i in 1..consecutiveBuys) {
                coinsToBuy += HoldingCoin(coinId++, price)
            }
            boughtCoins += coinsToBuy

            // update balancce
            balance = balance.buy(coinsToBuy.size.toDouble(), price)
            chart.addPoint("Buy", epoch, price, "price $price\nbuy ${coinsToBuy}\n$balance")
        } else {
            chart.addPoint("Price", epoch, price)
        }
        lastDiffFromMacd = diffFromLastMacd
        chart.addPoint("SMA(12h)", epoch, history.ema(period.forHours(12)))
        chart.addPointExtra("MACD", "macd", epoch, history.macd().macd)
        chart.addPointExtra("MACD", "signal", epoch, history.macd().signal)
        chart.addPointExtra("MACD", "histogram", epoch, history.macd().histogram)
        chart.addPointExtra("RSI", "rsi", epoch, rsi)
        chart.addPointExtra("RSI", "rsi 70", epoch, 70.0)
        chart.addPointExtra("RSI", "rsi 30", epoch, 30.0)
        chart.addPointExtra("BALANCE", "USD", epoch, balance.usd)
        periodId++
    }


    override fun start(stage: Stage) {
        chart = TradeChart()
        stage.scene = Scene(chart.node)
        stage.icons.add(Image("money-icon.png"))
        stage.title = "Tradexchange $backTestDays-day backtest for ${pair.replace("_", ":").toUpperCase()}"
        stage.show()

        thread(start = true, isDaemon = true) {
            val apiKey = "GW202H58-HQULS9AJ-SZDSHJZ1-FXRYESKK"
            val apiSecret = "7fe0d64f187fd333a9754085fa7a1e57c6a98345908f7c84dcbeed1465aa55a7adb7b36a276e95557a4598887673cbdbfbc8bacc0f9968f970bbe96fccb0745b"
            val poloniex = PoloniexExchangeService(apiKey, apiSecret)
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
                println("Buying ${-balance.coins} coins.")
                balance = balance.buy(-balance.coins, testableData.last().price)
            } else {
                println("Selling ${balance.coins} coins.")
                balance = balance.sell(balance.coins, testableData.last().price)
            }
            val holdingResult = Balance(usd = testableData.last().price - firstPrice, coins = 0.0)
            val tradingResult = balance
            val earnsOverHolding = tradingResult.usd-holdingResult.usd
            val percentOverHolding = (tradingResult.usd/holdingResult.usd)*100
            println("Trading: $tradingResult | Holding: ${holdingResult} | Earns over holding: $earnsOverHolding (+$percentOverHolding%)")
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
