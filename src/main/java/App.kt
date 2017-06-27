import com.google.gson.Gson
import com.google.gson.GsonBuilder
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.concurrent.thread

class App : Application() {
    private lateinit var chart: TradeChart
    private lateinit var exchange: Exchange
    private lateinit var state: TradePersistentState

    private enum class TradeType { BUY, SELL }
    private data class TradeEvent(val id: Int,
                                  val type: TradeType,
                                  val coins: Double,
                                  val price: Double,
                                  val epoch: Long)

    private data class TradePersistentState(val events: List<TradeEvent>, val unSoldCoins: List<TradeEvent>)

    // TODO Testear PoloniexLiveExchange
    // TODO definir estrategia, multi coin, hacer un balance check, etc
    // TODO days para el modo live:<days> que te muestre historial
    // TODO tradexchange-poloniex-auth.json con apiKey y apiSecret.
    private var lastDiffFromMacd = 0.0

    private fun handle(period: Long, price: Double, history: List<Double>, epoch: Long) {
        val max24h = history.takeLast(period.forHours(48)).max()!!
        val min24h = history.takeLast(period.forHours(48)).min()!!
        val divergence24h = (max24h - min24h)
        val macd = history.macd()
        val lastSellPrice = if (state.events.lastOrNull()?.type == TradeType.SELL) state.events.last().price else 0.0
        val lastBuyPrice = if (state.events.lastOrNull()?.type == TradeType.BUY) state.events.last().price else Double.MAX_VALUE
        val consecutiveBuys = state.events.takeLastWhile { it.type == TradeType.BUY }.size
        val consecutiveSells = state.events.takeLastWhile { it.type == TradeType.SELL }.size
        val farAboveLastSell = price > lastSellPrice+divergence24h*0.2
        val farBelowLastBuy = price < lastBuyPrice-divergence24h*0.2
        val diffFromLastMacd = history.macd().histogram - history.dropLast(1).macd().histogram
        val lastHistogram = history.dropLast(1).macd().histogram
        val histogram = history.macd().histogram
        if (
                //price > history.ema(period.forHours(24)) &&
                /*&& lastHistogram > 0
                && histogram > 0
                && macd.macd > 0
                && diffFromLastMacd > lastDiffFromMacd*2*/
                exchange.coinBalance >= 1.0
                && history.rsi(14) >= 70.0
                && farAboveLastSell
                //&& state.unSoldCoins.any { it.price < price-divergence24h*0.2 }
                ) {
            val nextEventId = (state.events.map { it.id }.max() ?: 0) + 1
            val sellEvent = TradeEvent(nextEventId, TradeType.SELL, 1.0, price, epoch)
            state = state.copy(events = state.events + sellEvent)
            exchange.sell(sellEvent.coins, sellEvent.price)
            chart.addPoint("Sell", epoch, price, "Sell event: $sellEvent\n${exchange.prettyBalance()}")
        } else if (
                //price < history.ema(period.forHours(24)) &&
                /*&& lastHistogram < 0
                && histogram < 0
                && macd.macd < 0
                && diffFromLastMacd < lastDiffFromMacd*2*/
                exchange.moneyBalance >= price
                && history.rsi(14) <= 30.0
                && farBelowLastBuy
                ) {
            val nextEventId = (state.events.map { it.id }.max() ?: 0) + 1
            val buyEvent = TradeEvent(nextEventId, TradeType.BUY, 1.0, price, epoch)
            state = state.copy(events = state.events + buyEvent)
            exchange.buy(buyEvent.coins, buyEvent.price)
            chart.addPoint("Buy", epoch, price, "Buy event: $buyEvent\n${exchange.prettyBalance()}")
        } else {
            chart.addPoint("Price", epoch, price)
        }
        lastDiffFromMacd = diffFromLastMacd
        chart.addPointExtra("MACD", "macd", epoch, history.macd().macd)
        chart.addPointExtra("MACD", "signal", epoch, history.macd().signal)
        chart.addPointExtra("MACD", "histogram", epoch, history.macd().histogram)
        chart.addPointExtra("RSI(14)", "rsi", epoch, history.rsi(14))
        chart.addPointExtra("RSI(14)", "rsi-70", epoch, 70.0)
        chart.addPointExtra("RSI(14)", "rsi-30", epoch, 30.0)
        chart.addPointExtra("BALANCE", "money", epoch, exchange.moneyBalance)
    }

    override fun start(stage: Stage) {
        val pair: String
        val backtestMode: Boolean
        var backTestDays = 0L
        var stateFileName: String? = null // only in live mode

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
                        warmUpPeriods = period.forHours(48),
                        initialMoney = 2000.0,
                        initialCoins = 5.0)
                state = TradePersistentState(emptyList(), emptyList())
            } else {
                Platform.runLater { stage.title = "Tradexchange live trading for $pair" }
                println("Starting live trading for $pair...")
                exchange = PoloniexLiveExchange(
                        pair = pair,
                        period = period,
                        warmUpPeriods = period.forHours(48)
                )
                stateFileName = "tradexchange-state-$pair.json"
                println("Trying to recover algorithm state from $stateFileName ...")
                state = try {
                    Gson().fromJson(Files.readAllBytes(Paths.get(stateFileName)).toString(Charset.defaultCharset()), TradePersistentState::class.java)
                } catch (e: Exception) {
                    println("Failed to load ($e). Creating an empty state ...")
                    TradePersistentState(emptyList(), emptyList())
                }
            }

            // TODO recover state events to chart if not in backtest


            var firstPrice: Double? = null
            var priceHistory = exchange.warmUpHistory

            if (!backtestMode && state.events.isNotEmpty()) {
                println("Drawing in chart past events ...")
            }

            while (true) {
                val ticker = exchange.fetchTicker() ?: break
                if (firstPrice == null) firstPrice = ticker.price

                handle(period, ticker.price, priceHistory, ticker.epoch)
                priceHistory += ticker.price

                if (!backtestMode) {
                    println("Saving algorithm state to file ${stateFileName!!} ... ")
                    val gson = GsonBuilder().setPrettyPrinting().create()
                    Files.write(Paths.get(stateFileName!!), gson.toJson(state).toByteArray(Charset.defaultCharset()))
                }
            }

            if (!backtestMode) {
                println("Balance resume only aviable in backtest mode. Exiting...")
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
            val moneyWonTrading = exchange.moneyBalance - 5000.0
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
