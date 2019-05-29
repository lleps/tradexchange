package com.lleps.tradexchange

import com.lleps.tradexchange.view.FullChart
import com.lleps.tradexchange.view.MainView
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import org.apache.log4j.AppenderSkeleton
import org.apache.log4j.Logger
import org.apache.log4j.spi.LoggingEvent
import org.slf4j.LoggerFactory
import org.ta4j.core.BaseTimeSeries
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.concurrent.thread

class App : Application() {
    private lateinit var view: MainView
    private lateinit var exchange: Exchange

    private enum class Mode { BACKTEST, LIVE }

    private fun onExecute(input: Map<String, String>) {
        view.toggleExecute(false)
        LOGGER.info("Input: $input")
        val trades = mutableListOf<MainView.TradeEntry>()
        runStrategy(input,
            mode = Mode.BACKTEST,
            onTrade = { buy, sell, amount ->
                // TODO: why trades are recorded here but chart data is recorded at
                //  the same time the strategy is executed?
                trades.add(MainView.TradeEntry(1, buy, sell, amount))
            },
            onFinish = {
                view.toggleExecute(true)
                view.setTrades(trades)
            })
    }

    override fun start(stage: Stage) {
        view = MainView()
        view.onExecute { onExecute(it) }
        stage.scene = Scene(view.initJavaFxContent())
        stage.icons.add(Image("money-icon.png"))
        stage.title = "Tradexchange"
        stage.show()

        // Set default strategy input
        val defaultInput = mutableMapOf(
            "pair" to "USDT_ETH",
            "period" to "300",
            "days" to "7",
            "initialMoney" to "1000.0",
            "initialCoins" to "0.0",
            "plotChart" to "3"
        )
        Strategy.requiredInput.forEach { key, value -> defaultInput[key] = value.toString() }
        view.setInput(defaultInput)

        // Attach logger to the view
        Logger.getRootLogger().addAppender(object : AppenderSkeleton() {
            override fun append(event: LoggingEvent) {
                view.log(event.message.toString())
            }
            override fun close() {}
            override fun requiresLayout() = false
        })
    }

    private fun runStrategy(
        input: Map<String, String>,
        mode: Mode,
        onTrade: (buy: Double, sell: Double, amount: Double) -> Unit,
        onFinish: () -> Unit
    ) {
        val pair = input["pair"] ?: error("pair")
        val days = input["days"]?.toInt() ?: error("days")
        val period = input["period"]?.toLong() ?: error("period")
        val plotChart = input["plotChart"]?.toInt() ?: error("plotIndicators")
        thread(start = true, isDaemon = true) {
            try {
                when (mode) {
                    Mode.BACKTEST -> {
                        // Set up
                        LOGGER.info("Starting backtesting $days-day for $pair... (period: ${period/60} min)")
                        val initialMoney = 1000.0
                        val initialCoins = 0.0
                        val chartOperations = mutableListOf<FullChart.Operation>()
                        val candles = mutableListOf<FullChart.Candle>()
                        val priceIndicators = mutableMapOf<String, MutableMap<Long, Double>>()
                        val extraIndicators = mutableMapOf<String, MutableMap<String, MutableMap<Long, Double>>>()
                        val chartWriter = object : Strategy.ChartWriter {
                            override fun priceIndicator(name: String, epoch: Long, value: Double) {
                                if (plotChart >= 2) {
                                    val data = priceIndicators.getOrPut(name) { mutableMapOf() }
                                    data[epoch] = value
                                }
                            }

                            override fun extraIndicator(chart: String, name: String, epoch: Long, value: Double) {
                                if (plotChart >= 3) {
                                    val chartData = extraIndicators.getOrPut(chart) { mutableMapOf() }
                                    val data = chartData.getOrPut(name) { mutableMapOf() }
                                    data[epoch] = value
                                }
                            }
                        }

                        // Fetch data
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

                        // Execute strategy
                        val timeSeries = BaseTimeSeries(allTicks)
                        val strategy = Strategy(
                            series = timeSeries,
                            period = period,
                            backtest = true,
                            epochStopBuy = ZonedDateTime.now(ZoneOffset.UTC).minusHours(8).toEpochSecond(),
                            exchange = exchange,
                            input = input
                        )
                        for (i in exchange.warmUpHistory.size..timeSeries.endIndex) {
                            val tick = timeSeries.getTick(i)
                            val epoch = tick.beginTime.toEpochSecond()
                            val operations = strategy.onTick(i)
                            strategy.onDrawChart(chartWriter, epoch, i)
                            candles.add(FullChart.Candle(
                                epoch,
                                tick.openPrice.toDouble(),
                                tick.closePrice.toDouble(),
                                tick.maxPrice.toDouble(),
                                tick.minPrice.toDouble()))
                            chartOperations.addAll(operations.map { op ->
                                val type = if (op.type == Strategy.OperationType.BUY)
                                    FullChart.OperationType.BUY
                                else
                                    FullChart.OperationType.SELL
                                FullChart.Operation(epoch, type, tick.closePrice.toDouble(), op.description)
                            })
                            for (op in operations) {
                                if (op.type == Strategy.OperationType.SELL) {
                                    onTrade(op.buyPrice, tick.closePrice.toDouble(), op.amount)
                                }
                            }
                        }

                        // Print resume
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
                        LOGGER.info("Trades: ${strategy.tradeCount}")
                        LOGGER.info(" ______________________________________________________ ")

                        // Update view
                        view.setChart(
                            candles = if (plotChart >= 1) candles else emptyList(),
                            operations = if (plotChart >= 1) chartOperations else emptyList(),
                            priceIndicators = if (plotChart >= 1) priceIndicators else emptyMap(),
                            extraIndicators = if (plotChart >= 1) extraIndicators else emptyMap()
                        )
                        LOGGER.info("Done!")
                        onFinish()
                    }
                    Mode.LIVE -> TODO("Implement live mode")
                }
            } catch (e: Exception) {
                LOGGER.info("error: $e", e)
                onFinish()
            }
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(App::class.java)

        @JvmStatic
        fun main(args: Array<String>) {
            launch(App::class.java)
        }
    }
}