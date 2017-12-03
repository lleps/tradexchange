
import indicator.MaximumIndicator
import indicator.MinimumIndicator
import indicator.NormalizationIndicator
import indicator.OBVOscillatorIndicator
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import org.ta4j.core.BaseTimeSeries
import org.ta4j.core.Decimal
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
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

    private val indicatorCache = mutableMapOf<String, Indicator<Decimal>>()

    private fun indicator(key: String, builder: () -> Indicator<Decimal>)// = builder()
           = indicatorCache.getOrPut(key) { builder() }

    private val maxTime = 24*2 // Limit time for a trade
    private var timePassed = 0

    private var wantToSell = true

    private var currentAmount = 1.0

    private fun handle(period: Long, history: TimeSeries, end: Int) {
        val epoch = history.getTick(end).endTime.toEpochSecond()

        // Indicators
        val closePrice = indicator("close") { ClosePriceIndicator(history) }
        val maxIndicator = indicator("max") { MaximumIndicator(closePrice, 48*2) }
        val minIndicator = indicator("min") { MinimumIndicator(closePrice, 48*2) }
        val obvOscillatorIndicator = indicator("obv-o") { NormalizationIndicator(OBVOscillatorIndicator(history, 3*2)) }
        val macdIndicator = indicator("macd") { NormalizationIndicator(MACDIndicator(closePrice, 12, 26)) }
        val longSMA = indicator("long-sma") { EMAIndicator(closePrice, 48*2) }
        val shortSMA = indicator("short-sma") { EMAIndicator(closePrice, 48/2) }

        // Util
        val divergence = maxIndicator[end] - minIndicator[end]
        val lastSellPrice = if (state.events.lastOrNull()?.type == TradeType.SELL) state.events.last().price else 0.0
        val lastBuyPrice = if (state.events.lastOrNull()?.type == TradeType.BUY) state.events.last().price else Double.MAX_VALUE
        val price = closePrice[end]
        val farAboveLastSell = price > lastSellPrice+divergence*0.2
        val farBelowLastBuy = price < lastBuyPrice-divergence*0.2
        val percent = avg(obvOscillatorIndicator[end] to 1, macdIndicator[end] to 2)

        // Cuando este bajo, comprar. Y esperar a que suba. pero seria manteniendo una moneda?

        timePassed++

        var action = false

        // Waiting to sell
        if (wantToSell) {
            val passed = (timePassed / maxTime).toDouble()
            val chanceOfSell = avg(passed to 1, percent to 6)
            if (chanceOfSell > .8) {
                action = true
                chart.addPoint("Sell", epoch, price)
                timePassed = 0
                exchange.sell(exchange.coinBalance, price)
                wantToSell = false
            }
        }

        // Waiting to buy
        else if (!wantToSell) {
            val passed = (timePassed / maxTime).toDouble()
            val chanceOfBuy = avg(passed to 1, (1.0 - percent) to 6)
            if (chanceOfBuy > .8) {
                action = true
                chart.addPoint("Buy", epoch, price)
                timePassed = 0
                exchange.buy(exchange.moneyBalance / price, price)
                wantToSell = true
            }
        }

        if (!action) {
            chart.addPoint("Price", epoch, price)
        }

        /*if (exchange.coinBalance >= 1.0 && percent > 0.8 && farAboveLastSell) {
            val nextEventId = (state.events.map { it.id }.max() ?: 0) + 1
            val sellEvent = TradeEvent(nextEventId, TradeType.SELL, 1.0, price, epoch)

            state = state.copy(events = state.events + sellEvent)
            exchange.sell(sellEvent.coins, sellEvent.price)

            val coinToSell = state.events
                    .filter { it.type == TradeType.BUY }
                    .firstOrNull { price > it.price + divergence*0.2 }

            if (coinToSell != null) { // existe alguna moneda
                chart.addPoint("Sell", epoch, price, "Sell event: $sellEvent\n${exchange.prettyBalance()}")
                state = state.copy(events = state.events - coinToSell)
            }
        } else if (exchange.moneyBalance >= price && percent < 0.2 && farBelowLastBuy && exchange.coinBalance <= 5.0) {
            val nextEventId = (state.events.map { it.id }.max() ?: 0) + 1
            val buyEvent = TradeEvent(nextEventId, TradeType.BUY, 1.0, price, epoch)
            state = state.copy(events = state.events + buyEvent)
            exchange.buy(buyEvent.coins, buyEvent.price)
            chart.addPoint("Buy", epoch, price, "Buy event: $buyEvent\n${exchange.prettyBalance()}")
        } else {
            chart.addPoint("Price", epoch, price)
        }*/
        //chart.addPoint("long-SMA", epoch, longSMA[end])
        //chart.addPoint("short-SMA", epoch, shortSMA[end])
        chart.addPointExtra("MACD", "macd", epoch, macdIndicator[end])
        chart.addPointExtra("OBV", "obv", epoch, obvOscillatorIndicator[end])
        chart.addPointExtra("BOTH", "both", epoch, percent)
        //chart.addPointExtra("BALANCE", "money", epoch, exchange.moneyBalance)
    }

    fun avg(vararg entries: Pair<Double, Int>) = entries.sumByDouble { it.first * it.second } / entries.sumBy { it.second }

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
                    val initialMoney = 0.0
                    val initialCoins = 1.0
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
                    val timeSeries = BaseTimeSeries(allTicks)
                    for (i in exchange.warmUpHistory.size..timeSeries.endIndex) handle(period, timeSeries, i)

                    // Resume
                    println("[Done] Final balance: ${exchange.coinBalance} coins | \$${exchange.moneyBalance} money.")
                    val firstPrice = ClosePriceIndicator(timeSeries)[exchange.warmUpHistory.size]
                    val latestPrice = ClosePriceIndicator(timeSeries)[timeSeries.endIndex]
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