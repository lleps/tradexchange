package com.lleps.tradexchange.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.lleps.tradexchange.*
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
    private var instanceState = emptyMap<String, InstanceState>()
    private val instanceChartData = mutableMapOf<String, InstanceChartData>()
    private var defaultInput = mapOf(
        "pair" to "USDT_ETH",
        "period" to "300",
        "days" to "7-0",
        "initialMoney" to "1000.0",
        "initialCoins" to "0.0",
        "plotChart" to "3"
    )

    init {
        // fill some default instances
        // should instead load from some db.
        Strategy.requiredInput.forEach { key, value -> defaultInput = defaultInput + (key to value.toString()) }
        instanceState = mapOf(
            "default" to InstanceState(defaultInput),
            "default-1" to InstanceState(defaultInput)
        )

        // TODO: server errors and warnings should be logged to ALL instances!
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(e: ILoggingEvent) {
                // to which? hmm. nah, its bad dud.
                // maybe use the interface.
                // but, since its devoriented, i want
                // to see raw output. not fancy stuff...
                // ok. get instance programatically.
                val message = e.message
                println("FROM APPENDER: $message")
            }

        }
        val rootAppLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        rootAppLogger.addAppender(appender)
    }
    
    @GetMapping("/instances")
    fun getInstances(): List<String> = instanceState.keys.toList()

    @GetMapping("/instanceState/{instance}")
    fun getInstanceState(@PathVariable instance: String): InstanceState {
        return instanceState[instance]?.copy() ?: InstanceState()
    }

    @GetMapping("/instanceChartData/{instance}")
    fun getInstanceChartData(@PathVariable instance: String): InstanceChartData {
        return instanceChartData[instance]?.copy() ?: InstanceChartData()
    }

    @PostMapping("/updateInput/{instance}")
    fun updateInput(@PathVariable instance: String, @RequestBody input: Map<String, String>) {
        onInputChanged(instance, input)
    }

    @PutMapping("/createInstance/{instance}")
    fun createInstance(@PathVariable instance: String) {
        if (instanceState.containsKey(instance)) error("instance with name '$instance' already exists.")
        instanceState = instanceState + (instance to InstanceState(defaultInput))
    }

    @DeleteMapping("/deleteInstance/{instance}")
    fun deleteInstance(@PathVariable instance: String) {
        if (!instanceState.containsKey(instance)) error("instance with name '$instance' does not exists.")
        instanceState = instanceState - instance
    }

    private fun onInputChanged(instance: String, input: Map<String, String>) {
        LOGGER.info("$instance: Input: $input")
        val state = instanceState.getValue(instance)
        state.input = input
        val trades = mutableListOf<TradeEntry>()
        startStrategyRunThread(instance,
            input = input,
            mode = Mode.BACKTEST,
            onTrade = { buy, sell, amount -> trades.add(TradeEntry(1, buy, sell, amount)) },
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
                LOGGER.info("$instance: error: $e", e)
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
        val state = instanceState.getValue(instance)
        val strategyOutput = object : Strategy.OutputWriter {
            override fun write(string: String) {
                LOGGER.info("$instance: $string")
                state.output += "$string\n"
            }
        }
        when (mode) {
            Mode.BACKTEST -> {
                // Set up
                strategyOutput.write("$instance: Starting backtesting $days-day for $pair... (period: ${period/60} min)")
                val initialMoney = 1000.0
                val initialCoins = 0.0
                val chartOperations = mutableListOf<Operation>()
                val candles = mutableListOf<Candle>()
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
                    output = strategyOutput,
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
                    candles.add(Candle(
                        epoch,
                        tick.openPrice.toDouble(),
                        tick.closePrice.toDouble(),
                        tick.maxPrice.toDouble(),
                        tick.minPrice.toDouble()))
                    chartOperations.addAll(operations.map { op ->
                        val type = if (op.type == Strategy.OperationType.BUY)
                            OperationType.BUY
                        else
                            OperationType.SELL
                        Operation(epoch, type, tick.closePrice.toDouble(), op.description)
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
                strategyOutput.write("  ______________________________________________________ ")
                strategyOutput.write("                   RESULTS                               ")
                strategyOutput.write(" Initial balance        %.03f'c $%.03f"
                    .format(initialCoins, initialMoney))
                strategyOutput.write(" Final balance          %.03f'c $%.03f (net %.03f'c \$%.03f)"
                    .format(exchange.coinBalance, exchange.moneyBalance,
                        exchange.coinBalance - initialCoins,
                        exchange.moneyBalance - initialMoney))
                strategyOutput.write(" Coin start/end value   $%.03f / $%.03f (net $%.03f)"
                    .format(firstPrice, latestPrice, latestPrice - firstPrice))
                strategyOutput.write(" Trades: ${strategy.tradeCount}")
                strategyOutput.write("  ______________________________________________________ ")

                // Update view
                val chartData = instanceChartData.getOrPut(instance) { InstanceChartData() }
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