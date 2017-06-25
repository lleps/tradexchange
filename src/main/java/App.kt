import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.concurrent.thread

class App : Application() {
    private lateinit var chart: TradeChart
    private lateinit var exchange: Exchange

    private enum class TradeType { BUY, SELL }
    private data class TradeEvent(val id: Int, val type: TradeType, val coins: Double, val price: Double, val epoch: Long)
    private class HoldingCoin(val id: Int, val price: Double) {
        override fun toString() = "[#$id, \$$price]"
    }
    private data class TradePersistentState(val events: List<TradeEvent>,
                                            val unSoldCoins: List<HoldingCoin>)
    // TODO implementar poloniex live exchange
    // TODO comprar solo si el balance alcanza
    // TODO eliminar variables redundantes (que se pueden calcular con funciones) para unificar el estado persistente.
    private var lastSellPrice = 0.0
    private var lastBuyPrice = Double.MAX_VALUE
    private var lastDiffFromMacd = 0.0
    private var coinId = 0
    private var consecutiveBuys = 0
    private var consecutiveSells = 0
    private var unSoldCoins = listOf<HoldingCoin>()

    private fun handle(period: Long, price: Double, history: List<Double>, epoch: Long) {
        val max24h = history.takeLast(period.forHours(24)).max()!!
        val min24h = history.takeLast(period.forHours(24)).min()!!
        val divergence24h = (max24h - min24h)
        val macd = history.macd()
        val farAboveLastSell = price > lastSellPrice+divergence24h*0.2
        val farBelowLastBuy = price < lastBuyPrice-divergence24h*0.2
        val diffFromLastMacd = history.macd().histogram - history.dropLast(1).macd().histogram
        val lastHistogram = history.dropLast(1).macd().histogram
        val histogram = history.macd().histogram
        if (price > history.ema(period.forHours(12))
                && lastHistogram > 0
                && histogram > 0
                && macd.macd > 0
                && diffFromLastMacd > lastDiffFromMacd*2
                && farAboveLastSell
                && unSoldCoins.any { it.price < price-divergence24h*0.2 }) {
            consecutiveBuys = 0
            consecutiveSells += 1
            val soldCoins = unSoldCoins.filter { it.price < price-divergence24h*0.2 }.take(consecutiveSells)
            lastSellPrice = price
            lastBuyPrice = Double.MAX_VALUE
            exchange.sell(soldCoins.size.toDouble(), price)
            chart.addPoint("Sell", epoch, price, "price $price\nsold $soldCoins\n${exchange.prettyBalance()}")
            unSoldCoins -= soldCoins
        } else if (price < history.ema(period.forHours(12))
                && lastHistogram < 0
                && histogram < 0
                && macd.macd < 0
                && diffFromLastMacd < lastDiffFromMacd*2
                && farBelowLastBuy) {
            lastBuyPrice = price
            lastSellPrice = 0.0
            consecutiveSells = 0
            consecutiveBuys += 1
            var coinsToBuy = emptyList<HoldingCoin>()
            for (i in 1..consecutiveBuys) {
                coinsToBuy += HoldingCoin(coinId++, price)
            }
            unSoldCoins += coinsToBuy
            exchange.buy(coinsToBuy.size.toDouble(), price)
            chart.addPoint("Buy", epoch, price, "price $price\nbuy ${coinsToBuy}\n${exchange.prettyBalance()}")
        } else {
            chart.addPoint("Price", epoch, price)
        }
        lastDiffFromMacd = diffFromLastMacd
        chart.addPoint("SMA(12h)", epoch, history.ema(period.forHours(12)))
        chart.addPointExtra("MACD", "macd", epoch, history.macd().macd)
        chart.addPointExtra("MACD", "signal", epoch, history.macd().signal)
        chart.addPointExtra("MACD", "histogram", epoch, history.macd().histogram)
        chart.addPointExtra("BALANCE", "money", epoch, exchange.moneyBalance)
        chart.addPointExtra("BALANCE", "coins", epoch, exchange.coinBalance)
    }

    override fun start(stage: Stage) {
        var pair = ""
        var backtestMode = false
        var backTestDays = 0L
        try {
            pair = args[0]
            if (args[1].startsWith("backtest")) {
                backtestMode = true
                backTestDays = args[1].split(":")[1].toLong()
            } else if (args[1] == "live") {
                backtestMode = false
            } else throw Exception()
        } catch (e: Exception) {
            println("Params: <pair> <mode>")
            println("Mode 1: backtest:[days] Mode 2: live")
            System.exit(0)
            return
        }

        chart = TradeChart()
        stage.scene = Scene(chart.node)
        stage.icons.add(Image("money-icon.png"))
        stage.show()

        thread(start = true, isDaemon = true) {
            val period = 1800L
            if (backtestMode) {
                Platform.runLater { stage.title = "Tradexchange $backTestDays-day backtest for $pair" }
                println("Starting backtesting $backTestDays-day for $pair...")
                exchange = PoloniexBacktestExchange(
                        pair = pair,
                        period = period,
                        fromEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(backTestDays).toEpochSecond(),
                        warmUpPeriods = period.forHours(48))
            } else {
                Platform.runLater { stage.title = "Tradexchange live trading for $pair" }
                println("Starting live trading for $pair...")
                TODO("live trading")
            }

            var firstPrice: Double? = null
            var priceHistory = exchange.warmUpHistory
            while (true) {
                val ticker = exchange.fetchTicker() ?: break
                if (firstPrice == null) firstPrice = ticker.price
                handle(period, ticker.price, priceHistory, ticker.epoch)
                priceHistory += ticker.price
            }

            if (!backtestMode) {
                println("Exiting...")
                System.exit(0)
                return@thread
            }

            if (exchange.coinBalance < 0.0) {
                println("Fixing exchange balance: Buying ${-exchange.coinBalance} coins.")
                exchange.buy(-exchange.coinBalance, priceHistory.last())
            } else if (exchange.coinBalance > 0.0) {
                println("Fixing exchange balance: Selling ${exchange.coinBalance} coins.")
                exchange.sell(exchange.coinBalance, priceHistory.last())
            }

            val moneyWonHolding = priceHistory.last() - firstPrice!!
            val moneyWonTrading = exchange.moneyBalance
            val tradingOverHolding = moneyWonTrading - moneyWonHolding
            val percentTradingOverHolding = (moneyWonTrading / moneyWonHolding) * 100
            println("Money won holding: $moneyWonHolding | " +
                    "Money won trading: $moneyWonTrading | " +
                    "Trading over holding: $tradingOverHolding ($percentTradingOverHolding%)")
        }
    }

    companion object {
        private lateinit var args: Array<String>

        @JvmStatic
        fun main(args: Array<String>) {
            this.args = args
            launch(App::class.java)
        }
    }
}
