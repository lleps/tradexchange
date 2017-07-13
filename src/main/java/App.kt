import eu.verdelhan.ta4j.TimeSeries
import eu.verdelhan.ta4j.indicators.simple.ClosePriceIndicator
import eu.verdelhan.ta4j.indicators.trackers.MACDIndicator
import eu.verdelhan.ta4j.indicators.trackers.RSIIndicator
import indicator.MaximumIndicator
import indicator.MinimumIndicator
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
    private lateinit var state: TradePersistentState

    private enum class TradeType { BUY, SELL }
    private data class TradeEvent(val id: Int,
                                  val type: TradeType,
                                  val coins: Double,
                                  val price: Double,
                                  val epoch: Long)

    private data class TradePersistentState(val events: List<TradeEvent>, val unSoldCoins: List<TradeEvent>)

    private fun handle(period: Long, history: TimeSeries, end: Int) {
        val closePrice = ClosePriceIndicator(history)
        val rsiIndicator = RSIIndicator(closePrice, 14)
        val price = closePrice.getValue(end).toDouble()
        val maxIndicator = MaximumIndicator(closePrice, 48*2)
        val minIndicator = MinimumIndicator(closePrice, 48*2)
        val epoch = history.getTick(end).endTime.toEpochSecond()
        val macdIndicator = MACDIndicator(closePrice, 12, 26)
        val divergence = (maxIndicator.getValue(end).toDouble() - minIndicator.getValue(end).toDouble())
        val lastSellPrice = if (state.events.lastOrNull()?.type == TradeType.SELL) state.events.last().price else 0.0
        val lastBuyPrice = if (state.events.lastOrNull()?.type == TradeType.BUY) state.events.last().price else Double.MAX_VALUE
        val farAboveLastSell = price > lastSellPrice+divergence*0.2
        val farBelowLastBuy = price < lastBuyPrice-divergence*0.2
        if (
                exchange.coinBalance >= 1.0
                && rsiIndicator.getValue(end).toDouble() >= 70.0
                && macdIndicator.getValue(end).toDouble() > 0
                && farAboveLastSell
                ) {
            val nextEventId = (state.events.map { it.id }.max() ?: 0) + 1
            val sellEvent = TradeEvent(nextEventId, TradeType.SELL, 1.0, price, epoch)
            state = state.copy(events = state.events + sellEvent)
            exchange.sell(sellEvent.coins, sellEvent.price)
            chart.addPoint("Sell", epoch, price, "Sell event: $sellEvent\n${exchange.prettyBalance()}")
        } else if (exchange.moneyBalance >= price
                && rsiIndicator.getValue(end).toDouble() <= 30.0
                && macdIndicator.getValue(end).toDouble() < 0
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
        chart.addPointExtra("MACD", "macd", epoch, macdIndicator.getValue(end).toDouble())
        chart.addPointExtra("RSI(14)", "rsi", epoch, rsiIndicator.getValue(end).toDouble())
        chart.addPointExtra("RSI(14)", "rsi-70", epoch, 70.0)
        chart.addPointExtra("RSI(14)", "rsi-30", epoch, 30.0)
        chart.addPointExtra("BALANCE", "money", epoch, exchange.moneyBalance)
    }

    private enum class Mode { BACKTEST, LIVE }
    override fun start(stage: Stage) {
        val pair: String
        val mode: Mode
        val days: Int

        try {
            pair = args[0]
            mode = Mode.valueOf(args[1].toUpperCase())
            days = args[2].toInt()
        } catch (e: Exception) {
            println("Parameters: <pair> <mode> <days>")
            System.exit(0)
            return
        }

        chart = TradeChart()
        stage.scene = Scene(chart.node)
        stage.icons.add(Image("money-icon.png"))
        stage.show()

        thread(start = true, isDaemon = true) {
            val period = 1800L
            when (mode) {
                Mode.BACKTEST -> {
                    // Set up
                    Platform.runLater { stage.title = "Tradexchange $days-day backtest for $pair" }
                    println("Starting backtesting $days-day for $pair...")
                    val initialMoney = 2000.0
                    val initialCoins = 5.0
                    exchange = PoloniexBacktestExchange(
                            pair = pair,
                            period = period,
                            fromEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(days.toLong()).toEpochSecond(),
                            warmUpPeriods = period.forHours(48),
                            initialMoney = initialMoney,
                            initialCoins = initialCoins)
                    state = TradePersistentState(emptyList(), emptyList())
                    val allTicks = mutableListOf(*exchange.warmUpHistory.toTypedArray())
                    while (true) {
                        val tick = exchange.fetchTick() ?: break
                        allTicks += tick
                    }

                    // Test
                    val timeSeries = TimeSeries(allTicks)
                    for (i in exchange.warmUpHistory.size..timeSeries.end) handle(period, timeSeries, i)

                    // Resume
                    println("[Done] Final balance: ${exchange.coinBalance} coins | \$${exchange.moneyBalance} money.")
                    val firstPrice = ClosePriceIndicator(timeSeries).getValue(exchange.warmUpHistory.size).toDouble()
                    val latestPrice = ClosePriceIndicator(timeSeries).getValue(timeSeries.end).toDouble()
                    if (exchange.coinBalance > initialCoins) {
                        val coinsToSell = exchange.coinBalance - initialCoins
                        println("[Done] Selling $coinsToSell coins at latest price \$$latestPrice to get " +
                                "total money (hold vs trade)")
                        exchange.sell(coinsToSell, latestPrice)
                    } else if (exchange.coinBalance < initialCoins) {
                        val coinsToBuy = initialCoins - exchange.coinBalance
                        println("[Done] Buying $coinsToBuy coins at latest price \$$latestPrice to get " +
                                "total coins (hold vs trade)")
                        exchange.buy(coinsToBuy, latestPrice)
                    }
                    println("[Done] Holding: \$${latestPrice - firstPrice} money.")
                    println("[Done] Trading: \$${exchange.moneyBalance - initialMoney} money.")
                    println("[Done] Fixed balance: ${exchange.coinBalance} coins | \$${exchange.moneyBalance} money.")
                }
                Mode.LIVE -> TODO("Implement live mode")
            }
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
