import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import org.ta4j.core.BaseTimeSeries
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
        stage.scene.stylesheets.add("style.css")
        stage.icons.add(Image("money-icon.png"))
        stage.show()

        thread(start = true, isDaemon = true) {
            val period = 1800L
            when (mode) {
                Mode.BACKTEST -> {
                    // Set up
                    Platform.runLater { stage.title = "Tradexchange $days-day backtest for $pair" }
                    println("Starting backtesting $days-day for $pair...")

                    val initialMoney = 3500.0
                    val initialCoins = 0.0

                    exchange = PoloniexBacktestExchange(
                            pair = pair,
                            period = period,
                            fromEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(days.toLong()).toEpochSecond(),
                            warmUpPeriods = period.forHours(48),
                            initialMoney = initialMoney,
                            initialCoins = initialCoins)

                    val allTicks = mutableListOf(*exchange.warmUpHistory.toTypedArray())

                    while (true) {
                        val tick = exchange.fetchTick() ?: break
                        allTicks += tick
                    }

                    Platform.runLater { chart.fix() }

                    // Test
                    val timeSeries = BaseTimeSeries(allTicks)
                    val strategy = Strategy(timeSeries, chart, exchange)

                    for (i in exchange.warmUpHistory.size..timeSeries.endIndex) {
                        strategy.onTick(i)
                    }

                    // Resume
                    println("[Done] Final balance: ${exchange.coinBalance} coins | \$${exchange.moneyBalance} money.")

                    val firstPrice = ClosePriceIndicator(timeSeries)[exchange.warmUpHistory.size]
                    val latestPrice = ClosePriceIndicator(timeSeries)[timeSeries.endIndex]

                    println("[Done] Start price: \$$firstPrice | Last price: \$$latestPrice")

                    println("[Done] Holding: 1.0c and $0 = \$$latestPrice " +
                            "(won \$${latestPrice - firstPrice})")

                    val finalValue = exchange.moneyBalance + (exchange.coinBalance * latestPrice)

                    println("[Done] Trading: " +
                            "${exchange.coinBalance}c and ${exchange.moneyBalance} = \$${latestPrice * exchange.coinBalance} " +
                            "(won ${finalValue - firstPrice})")
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