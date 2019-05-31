package com.lleps.tradexchange.server

import com.google.gson.Gson
import com.lleps.tradexchange.RESTInterface
import com.lleps.tradexchange.client.FullChart
import com.lleps.tradexchange.client.MainView
import com.lleps.tradexchange.util.get
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import org.ta4j.core.BaseTimeSeries
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.concurrent.thread

/** Server main class. Makes backtesting, handles http requests, etc. */
@RestController
class RESTServer {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(RESTServer::class.java)
    }

    private enum class Mode { BACKTEST, LIVE }

    // Server state
    private var instanceState = emptyMap<String, RESTInterface.InstanceState>()
    private val instanceChartData = mutableMapOf<String, RESTInterface.InstanceChartData>()

    init {
        // fill some default instances
        // should instead load from some db.
        var input = mapOf(
            "pair" to "USDT_ETH",
            "period" to "300",
            "days" to "7-0",
            "initialMoney" to "1000.0",
            "initialCoins" to "0.0",
            "plotChart" to "3"
        )
        Strategy.requiredInput.forEach { key, value -> input = input + (key to value.toString()) }
        instanceState = mapOf(
            "default" to RESTInterface.InstanceState(input),
            "default-1" to RESTInterface.InstanceState(input)
        )

        /* // TODO: Fix logger
        Logger.getRootLogger().addAppender(object : AppenderSkeleton() {
            override fun append(event: LoggingEvent) { log.append(event.message.toString() + "\n") }
            override fun close() {}
            override fun requiresLayout() = false
        })*/
    }
    
    @RequestMapping("/instances")
    fun getInstances(): List<String> = instanceState.keys.toList()

    @RequestMapping("/instanceState/{instance}")
    fun getInstanceState(@PathVariable instance: String): RESTInterface.InstanceState {
        return instanceState[instance]?.copy() ?: RESTInterface.InstanceState()
    }

    @RequestMapping("/instanceChartData/{instance}")
    fun getInstanceChartData(@PathVariable instance: String): RESTInterface.InstanceChartData {
        LOGGER.info(Gson().toJson(instanceChartData[instance]?.copy()!!))
        return instanceChartData[instance]?.copy() ?: RESTInterface.InstanceChartData()
    }

    @PostMapping("/updateInput/{instance}")
    fun updateInput(@PathVariable instance: String, @RequestBody input: Map<String, String>) {
        onInputChanged(instance, input)
    }

    private fun onInputChanged(instance: String, input: Map<String, String>) {
        LOGGER.info("Input: $input")
        val state = instanceState.getValue(instance)
        state.input = input
        val trades = mutableListOf<MainView.TradeEntry>()
        startStrategyRunThread(instance,
            input = input,
            mode = Mode.BACKTEST,
            onTrade = { buy, sell, amount -> trades.add(MainView.TradeEntry(1, buy, sell, amount)) },
            onFinish = { state.trades = trades })
    }

    private fun startStrategyRunThread(
        instance: String,
        input: Map<String, String>,
        mode: Mode,
        onTrade: (buy: Double, sell: Double, amount: Double) -> Unit,
        onFinish: () -> Unit
    ) {
        thread(start = true, isDaemon = true) {
            try {
                runStrategy(instance, mode, input, onTrade, onFinish)
            } catch (e: Exception) {
                LOGGER.info("error: $e", e)
                onFinish()
            }
        }
    }

    private fun runStrategy(
        instance: String,
        mode: Mode,
        input: Map<String, String>,
        onTrade: (buy: Double, sell: Double, amount: Double) -> Unit,
        onFinish: () -> Unit
    ) {
        val pair = input["pair"] ?: error("pair")
        val days = input["days"]?.split("-")?.get(0)?.toInt() ?: error("days")
        val daysLimit = input["days"]?.split("-")?.get(1)?.toInt() ?: error("days")
        val period = input["period"]?.toLong() ?: error("period")
        val plotChart = input["plotChart"]?.toInt() ?: error("plotIndicators")
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
                val exchange = PoloniexBacktestExchange(
                    pair = pair,
                    period = period,
                    fromEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(days.toLong()).toEpochSecond(),
                    warmUpTicks = 100,
                    initialMoney = initialMoney,
                    initialCoins = initialCoins)
                val allTicks = mutableListOf(*exchange.warmUpHistory.toTypedArray())
                val tickLimitEpoch = LocalDateTime.now().minusDays(daysLimit.toLong()).toEpochSecond(ZoneOffset.UTC)
                while (true) {
                    // ignore daysLimit.
                    val tick = exchange.fetchTick() ?: break
                    if (tick.beginTime.toEpochSecond() >= tickLimitEpoch) break
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
                val chartData = instanceChartData.getOrPut(instance) { RESTInterface.InstanceChartData() }
                chartData.candles = if (plotChart >= 1) candles else emptyList()
                chartData.operations = if (plotChart >= 1) chartOperations else emptyList()
                chartData.priceIndicators = if (plotChart >= 1) priceIndicators else emptyMap()
                chartData.extraIndicators = if (plotChart >= 1) extraIndicators else emptyMap()
                onFinish()
            }
            Mode.LIVE -> TODO("Implement live mode")
        }
    }
}