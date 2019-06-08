package com.lleps.tradexchange.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.lleps.tradexchange.*
import com.lleps.tradexchange.strategy.ChartWriterImpl
import com.lleps.tradexchange.strategy.Strategy
import com.lleps.tradexchange.util.*
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import org.ta4j.core.Bar
import org.ta4j.core.BaseTimeSeries
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.concurrent.thread


/** Server main class. Makes backtesting, handles http requests, etc. */
@RestController
class RESTServer {
    private val mapper = ObjectMapper()

    // Server state
    private data class InstancesWrapper(var list: List<String> = emptyList())
    private val loadedInstances = mutableMapOf<String, Unit>()
    private val instances: InstancesWrapper
    private val instanceState = mutableMapOf<String, InstanceState>()
    private val instanceChartData = mutableMapOf<String, InstanceChartData>()
    private val defaultInput = mutableMapOf(
        "pair" to "USDT_ETH",
        "period" to "300",
        "initialMoney" to "1000.0",
        "initialCoins" to "0.0",
        "warmupTicks" to "20",
        "cooldownTicks" to "20",
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

    // This is sent compressed - big charts may take like 20MBs.
    @GetMapping("/instanceChartData/{instance}")
    fun getInstanceChartData(@PathVariable instance: String): String {
        loadInstanceIfNecessary(instance)
        val fullString = mapper.writeValueAsString(instanceChartData.getValue(instance).copy())
        return Base64.getEncoder().encodeToString(GZIPCompression.compress(fullString))
    }

    @PostMapping("/updateInput/{instance}")
    fun updateInput(@PathVariable instance: String, @RequestBody input: Map<String, String>) {
        loadInstanceIfNecessary(instance)
        onInputChanged(instance, input)
    }

    @PutMapping("/createInstance/{instanceQuery}")
    fun createInstance(@PathVariable instanceQuery: String): String {
        // parse query
        val parts = instanceQuery.split(":")
        if (parts.size < 2 || parts.size > 3) error("query should be <type>:<name>:<copyFrom?>")
        val instanceTypeStr = parts[0]
        val instanceCopyFrom = if (parts.size == 3) parts[2] else null
        val instanceType = InstanceType.valueOf(instanceTypeStr.toUpperCase())
        val instanceName = "[${instanceType.toString().toLowerCase()}]${parts[1]}"

        // try to get the input if copied from somewhere
        val inputData = if (instanceCopyFrom != null) instanceState.getValue(instanceCopyFrom).input else defaultInput
        if (instanceState.containsKey(instanceName)) error("instance with name '$instanceName' already exists.")
        val action1 = when (instanceType) {
            InstanceType.BACKTEST -> "Run"
            InstanceType.LIVE -> "Start"
            InstanceType.TRAIN -> "Reset"
        }
        val action2 = if (instanceType == InstanceType.TRAIN) "Save" else ""

        // create the instance
        instanceState[instanceName] = InstanceState(
            type = instanceType,
            input = inputData,
            action1 = action1,
            action2 = action2
        )
        instanceChartData[instanceName] = InstanceChartData()
        instances.list = instances.list + instanceName
        loadedInstances[instanceName] = Unit
        saveInstance(instanceName)
        saveInstanceList()
        return instanceName
    }

    @DeleteMapping("/deleteInstance/{instance}")
    fun deleteInstance(@PathVariable instance: String) {
        loadInstanceIfNecessary(instance)
        instanceState.remove(instance)
        instanceChartData.remove(instance)
        instances.list = instances.list - instance
        loadedInstances.remove(instance)
        deleteInstanceFiles(instance)
        saveInstanceList()
    }

    @GetMapping("/getInstanceVersion/{instance}")
    fun getInstanceVersion(@PathVariable instance: String): String {
        loadInstanceIfNecessary(instance)
        val state = instanceState.getValue(instance)
        return "${state.stateVersion}:${state.chartVersion}"
    }

    private fun onInputChanged(instance: String, input: Map<String, String>) {
        LOGGER.info("Input: $input")
        val state = instanceState.getValue(instance)
        state.input = input
        state.stateVersion++
        val trades = mutableListOf<TradeEntry>()
        startStrategyRunThread(instance,
            input = input,
            onTrade = { buy, sell, amount, code ->
                trades.add(TradeEntry(code, buy, sell, amount))
                // intermediate updates while the strategy is running
                val tradeSum = trades.sumByDouble { (it.sell-it.buy)*it.amount }
                state.statusText = "%d trades sum \$%.2f".format(trades.size, tradeSum)
                state.statusPositiveness = if (tradeSum > 0.0) 1 else -1
                state.stateVersion++
            },
            onFinish = {
                state.trades = trades
                state.stateVersion++
            })
    }

    private fun startStrategyRunThread(
        instance: String,
        input: Map<String, String>,
        onTrade: (buy: Double, sell: Double, amount: Double, code: Int) -> Unit,
        onFinish: () -> Unit
    ) {
        val strategyOutput = object : Strategy.OutputWriter {
            override fun write(string: String) {
                LOGGER.info("$instance: $string")
                val state = instanceState.getValue(instance)
                state.output += "$string\n"
                state.stateVersion++
            }
        }
        thread(start = true, isDaemon = true) {
            try {
                runBacktest(instance, input, strategyOutput, onTrade, onFinish)
            } catch (e: Exception) {
                LOGGER.info("$instance: error: $e", e)
                strategyOutput.write("error")
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                strategyOutput.write(sw.toString())
                instanceState[instance]?.let { state ->
                    state.statusPositiveness = -1
                    state.statusText = "Error. check output"
                    state.stateVersion++
                }
                onFinish()
                saveInstance(instance)
            }
        }
    }

    private fun runBacktest(
        instance: String,
        input: Map<String, String>,
        strategyOutput: Strategy.OutputWriter,
        onTrade: (buy: Double, sell: Double, amount: Double, code: Int) -> Unit,
        onFinish: () -> Unit
    ) {
        instanceState[instance]?.output = ""

        // read input
        val period = input.getValue("period").toInt()
        val warmupTicks = input.getValue("warmupTicks").toInt()
        val cooldownTicks = input.getValue("cooldownTicks").toInt()
        val plotChart = input["plotChart"]?.toInt() ?: error("plotIndicators")
        val initialMoney = input.getValue("initialMoney").toDouble()
        val initialCoins = input.getValue("initialCoins").toDouble()

        // Get market ticks
        val ticks = fetchTicks(input, strategyOutput)

        // Set up
        strategyOutput.write("Starting... (period: ${period/60} min, ${ticks.size} ticks)")
        val chartOperations = mutableListOf<Operation>()
        val candles = mutableListOf<Candle>()
        val chartWriter = ChartWriterImpl(plotChart)

        // Execute strategy
        val exchange = PoloniexBacktestExchange(initialMoney, initialCoins)
        val timeSeries = BaseTimeSeries(ticks)
        val strategy = Strategy(
            output = strategyOutput,
            series = timeSeries,
            period = period.toLong(),
            backtest = true,
            exchange = exchange,
            input = input
        )
        val sellOnlyTick = timeSeries.endIndex - cooldownTicks
        for (i in warmupTicks..timeSeries.endIndex) {
            val tick = timeSeries.getBar(i)
            val epoch = tick.beginTime.toEpochSecond()
            if (i >= sellOnlyTick) strategy.sellOnly = true
            val operations = strategy.onTick(i)
            strategy.onDrawChart(chartWriter, epoch, i)
            candles.add(Candle(
                epoch,
                tick.openPrice.doubleValue(),
                tick.closePrice.doubleValue(),
                tick.maxPrice.doubleValue(),
                tick.minPrice.doubleValue()))
            chartOperations.addAll(operations.map { op ->
                val type = if (op.type == Strategy.OperationType.BUY)
                    OperationType.BUY
                else
                    OperationType.SELL
                Operation(epoch, type, tick.closePrice.doubleValue(), op.description)
            })
            for (op in operations) {
                if (op.type == Strategy.OperationType.SELL) {
                    onTrade(op.buyPrice, tick.closePrice.doubleValue(), op.amount, op.code)
                }
            }
        }

        // Print resume
        val firstPrice = ClosePriceIndicator(timeSeries)[warmupTicks]
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

        val state = instanceState.getValue(instance)

        onFinish()

        // Update view
        val chartData = instanceChartData.getOrPut(instance) { InstanceChartData() }
        chartData.candles = if (plotChart >= 1) candles else emptyList()
        chartData.operations = if (plotChart >= 1) chartOperations else emptyList()
        chartData.priceIndicators = if (plotChart >= 1) chartWriter.priceIndicators else emptyMap()
        chartData.extraIndicators = if (plotChart >= 1) chartWriter.extraIndicators else emptyMap()
        state.chartVersion++

        // Update status text
        val bhDifference = latestPrice - firstPrice
        val bhCoinPercent = bhDifference * 100.0 / firstPrice
        val tradeDifference = exchange.moneyBalance - initialMoney
        val tradePercent = tradeDifference * 100.0 / initialMoney
        val tradeSum = state.trades.sumByDouble { (it.sell-it.buy)*it.amount }
        val tradesString = "%d trades sum \$%.2f".format(state.trades.size, tradeSum)
        val tradingVsHoldingString = "(%.1f%s vs %.1f%s)".format(tradePercent, "%", bhCoinPercent, "%")
        state.statusText = "$tradesString $tradingVsHoldingString"
        state.statusPositiveness = if (tradePercent > 0) 1 else -1
        state.stateVersion++

        saveInstance(instance)
    }

    private val fetchTicksRequiredInput = mapOf(
        "pair" to "USDT_ETH",
        "period" to "300",
        "bt.source" to "poloniex",
        "bt.csv.file" to "../Bitfinex-historical-data/ETHUSD/Candles_1m/2019/merged.csv",
        "bt.csv.startDate" to "2019-01-01",
        "bt.csv.endDate" to "2019-01-04",
        "bt.poloniex.dayRange" to "7-0"
    )

    /** Parse ticks from the given input. May throw anything. */
    private fun fetchTicks(input: Map<String, String>, out: Strategy.OutputWriter): List<Bar> {
        val pair = input["pair"] ?: error("pair")
        val period = input["period"]?.toLong() ?: error("period")
        val btSource = input.getValue("bt.source")
        val btCsvFile = input.getValue("bt.csv.file")
        val btCsvDateStart = input.getValue("bt.csv.startDate")
        val btCsvDateEnd = input.getValue("bt.csv.endDate")
        val btPolDays0 = input.getValue("bt.poloniex.dayRange").split("-")[0].toInt()
        val btPolDays1 = input.getValue("bt.poloniex.dayRange").split("-")[1].toInt()

        return when (btSource) {
            "csv" -> {
                out.write("Parse ticks from $btCsvFile at period ${period.toInt()}")
                val ticks = parseCandlesFromCSV(
                    file = btCsvFile,
                    periodSeconds = period.toInt(),
                    startDate = LocalDate.parse(btCsvDateStart, DateTimeFormatter.ISO_DATE).atStartOfDay(),
                    endDate = LocalDate.parse(btCsvDateEnd, DateTimeFormatter.ISO_DATE).atStartOfDay())
                ticks
            }
            "poloniex" -> {
                out.write("Using data from poloniex server...")
                val limit = (Instant.now().toEpochMilli() / 1000) - (btPolDays1 * 24 * 3600)
                val ticks = getTicksFromPoloniex(pair, period.toInt(), btPolDays0)
                    .filter { it.endTime.toEpochSecond() < limit }
                ticks
            }
            else -> error("invalid bt.source. Valid: 'csv' or 'poloniex'")
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

    companion object {
        private val LOGGER = LoggerFactory.getLogger(RESTServer::class.java)

        @JvmStatic
        fun main(args: Array<String>) {
            val candles = parseCandlesFromCSV(
                file = "../Bitfinex-historical-data/BTCUSD/Candles_1m/2018/merged.csv",
                periodSeconds = 60/*,
                startDate = LocalDateTime.of(2018, 5, 1, 0, 0),
                endDate = LocalDateTime.of(2018, 10, 2, 0, 0)*/
            )
            println("candles size: ${candles.size}")
            //println(candles.map { it.endTime })
        }
    }
}