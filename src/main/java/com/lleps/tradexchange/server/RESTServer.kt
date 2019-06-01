package com.lleps.tradexchange.server

import com.lleps.tradexchange.*
import com.lleps.tradexchange.util.get
import com.lleps.tradexchange.util.loadFrom
import com.lleps.tradexchange.util.saveTo
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import org.ta4j.core.BaseTimeSeries
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
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
    private data class InstancesWrapper(var list: List<String> = emptyList())
    private val loadedInstances = mutableMapOf<String, Unit>()
    private val instances: InstancesWrapper
    private val instanceState = mutableMapOf<String, InstanceState>()
    private val instanceChartData = mutableMapOf<String, InstanceChartData>()
    private val defaultInput = mutableMapOf(
        "pair" to "USDT_ETH",
        "period" to "300",
        "days" to "7-0",
        "initialMoney" to "1000.0",
        "initialCoins" to "0.0",
        "plotChart" to "3"
    )

    init {
        File("data").mkdir()
        File("data/instances").mkdir()
        Strategy.requiredInput.forEach { key, value -> defaultInput[key] = value.toString() }
        instances = loadInstanceList()
    }

    @GetMapping("/instances")
    fun getInstances(): List<String> = instances.list.toList()

    @GetMapping("/instanceState/{instance}")
    fun getInstanceState(@PathVariable instance: String): InstanceState {
        loadInstanceIfNecessary(instance)
        return instanceState.getValue(instance).copy()
    }

    @GetMapping("/instanceChartData/{instance}")
    fun getInstanceChartData(@PathVariable instance: String): InstanceChartData {
        loadInstanceIfNecessary(instance)
        return instanceChartData.getValue(instance).copy()
    }

    @PostMapping("/updateInput/{instance}")
    fun updateInput(@PathVariable instance: String, @RequestBody input: Map<String, String>) {
        loadInstanceIfNecessary(instance)
        onInputChanged(instance, input)
    }

    @PutMapping("/createInstance/{instance}")
    fun createInstance(@PathVariable instance: String) {
        if (instanceState.containsKey(instance)) error("instance with name '$instance' already exists.")
        instanceState[instance] = InstanceState(defaultInput)
        instanceChartData[instance] = InstanceChartData()
        instances.list = instances.list + instance
        loadedInstances[instance] = Unit
        saveInstance(instance)
        saveInstanceList()
    }

    @DeleteMapping("/deleteInstance/{instance}")
    fun deleteInstance(@PathVariable instance: String) {
        if (!instanceState.containsKey(instance)) error("instance with name '$instance' does not exists.")
        instanceState.remove(instance)
        instanceChartData.remove(instance)
        instances.list = instances.list - instance
        loadedInstances.remove(instance)
        deleteInstanceFiles(instance)
        saveInstanceList()
    }

    private fun onInputChanged(instance: String, input: Map<String, String>) {
        LOGGER.info("Input: $input")
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
                saveInstance(instance)
            }
            Mode.LIVE -> TODO("Implement live mode")
        }
    }


    // Persistence
    private fun loadInstanceList(): InstancesWrapper {
        return loadFrom<InstancesWrapper>("data/instances/list.json") ?: InstancesWrapper()
    }

    private fun saveInstanceList() {
        instances.saveTo("data/instances/list.json")
    }

    private fun loadInstanceIfNecessary(instance: String) {
        if (!loadedInstances.containsKey(instance)) {
            val state = loadFrom<InstanceState>("data/instances/state-$instance.json") ?: error("instance: $instance")
            val chartData = loadFrom<InstanceChartData>("data/instances/chartData-$instance.json") ?: error("instance: $instance")
            instanceState[instance] = state
            instanceChartData[instance] = chartData
            loadedInstances[instance] = Unit
        }
    }

    private fun saveInstance(instance: String) {
        val state = instanceState.getValue(instance)
        val chartData = instanceChartData.getValue(instance)
        state.saveTo("data/instances/state-$instance.json")
        chartData.saveTo("data/instances/chartData-$instance.json")
    }

    private fun deleteInstanceFiles(instance: String) {
        Files.delete(Paths.get("data/instances/state-$instance.json"))
        Files.delete(Paths.get("data/instances/chartData-$instance.json"))
    }
}