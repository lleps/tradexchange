package com.lleps.tradexchange

import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import org.slf4j.LoggerFactory
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

    private data class TradeEvent(
        val id: Int,
        val type: TradeType,
        val coins: Double,
        val price: Double,
        val epoch: Long
    )

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
            LOGGER.error("Parameters: <pair> <mode> <days>")
            System.exit(0)
            return
        }

        chart = TradeChart()
        stage.scene = Scene(chart.node)
        stage.scene.stylesheets.add("style.css")
        stage.icons.add(Image("money-icon.png"))
        stage.show()

        thread(start = true, isDaemon = true) {
            val period = 300L
            when (mode) {
                Mode.BACKTEST -> {
                    // Set up
                    Platform.runLater { stage.title = "Tradexchange $days-day backtest for $pair \uD83D\uDD34" }
                    LOGGER.info("Starting backtesting $days-day for $pair... (period: ${period/60} min)")

                    val initialMoney = 1000.0
                    val initialCoins = 0.0

                    exchange = PoloniexBacktestExchange(
                        pair = pair,
                        period = period,
                        fromEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(days.toLong()).toEpochSecond(),
                        warmUpTicks = 100,
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
                    val strategy = Strategy(
                        series = timeSeries,
                        chart = chart,
                        period = period,
                        backtest = true,
                        epochStopBuy = ZonedDateTime.now(ZoneOffset.UTC).minusHours(8).toEpochSecond(),
                        exchange = exchange
                    )

                    for (i in exchange.warmUpHistory.size..timeSeries.endIndex) {
                        strategy.onTick(i)
                    }

                    // Resume
                    val firstPrice = ClosePriceIndicator(timeSeries)[exchange.warmUpHistory.size]
                    val latestPrice = ClosePriceIndicator(timeSeries)[timeSeries.endIndex]

                    LOGGER.info(" ______________________________________________________ ")
                    LOGGER.info("                  RESULTS                               ")
                    LOGGER.info("Initial balance        %.03f'c $%.03f"
                            .format(initialCoins, initialMoney))
                    LOGGER.info("Final balance          %.03f'c $%.03f (net %.03f'c \$%.03f)"
                            .format(exchange.coinBalance, exchange.moneyBalance,
                                    exchange.coinBalance - initialCoins,
                                    exchange.moneyBalance - initialMoney))
                    LOGGER.info("Coin start/end value   $%.03f / $%.03f (net $%.03f)"
                            .format(firstPrice, latestPrice, latestPrice - firstPrice))
                    LOGGER.info(" ______________________________________________________ ")
                }
                Mode.LIVE -> TODO("Implement live mode")
            }
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(App::class.java)

        private lateinit var args: Array<String>

        @JvmStatic
        fun main(args: Array<String>) {
            Companion.args = args
            launch(App::class.java)
        }
    }
}