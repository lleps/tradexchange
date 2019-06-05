package com.lleps.tradexchange.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.lleps.tradexchange.*
import com.lleps.tradexchange.util.GZIPCompression
import com.lleps.tradexchange.util.get
import com.lleps.tradexchange.util.loadFrom
import com.lleps.tradexchange.util.saveTo
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import org.ta4j.core.Bar
import org.ta4j.core.BaseBar
import org.ta4j.core.BaseTimeSeries
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.num.DoubleNum
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.*
import kotlin.concurrent.thread
import java.io.PrintWriter
import java.io.StringWriter
import java.time.format.DateTimeFormatter
import java.util.*


/** Server main class. Makes backtesting, handles http requests, etc. */
@RestController
class RESTServer {
    private enum class Mode { BACKTEST, LIVE }
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
        "plotChart" to "3",
        "bt.source" to "poloniex",
        "bt.csv.file" to "../Bitfinex-historical-data/ETHUSD/Candles_1m/2019/merged.csv",
        "bt.csv.startDate" to "2019-01-01",
        "bt.csv.endDate" to "2019-01-04",
        "bt.poloniex.dayRange" to "7-0"
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

    @PutMapping("/createInstance/{instanceCode}")
    fun createInstance(@PathVariable instanceCode: String) {
        val parts = instanceCode.split(":")
        val instance = if (parts.size == 2) parts[0] else instanceCode
        val inputData = if (parts.size == 2) instanceState.getValue(parts[1]).input else defaultInput
        if (instanceState.containsKey(instance)) error("instance with name '$instance' already exists.")
        instanceState[instance] = InstanceState(inputData)
        instanceChartData[instance] = InstanceChartData()
        instances.list = instances.list + instance
        loadedInstances[instance] = Unit
        saveInstance(instance)
        saveInstanceList()
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
            mode = Mode.BACKTEST,
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
        mode: Mode,
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
                runStrategy(instance, mode, input, strategyOutput, onTrade, onFinish)
            } catch (e: Exception) {
                LOGGER.info("$instance: error: $e", e)
                strategyOutput.write("error running strategy: $e")
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

    private fun runStrategy(
        instance: String,
        mode: Mode,
        input: Map<String, String>,
        strategyOutput: Strategy.OutputWriter,
        onTrade: (buy: Double, sell: Double, amount: Double, code: Int) -> Unit,
        onFinish: () -> Unit
    ) {
        instanceState[instance]?.output = ""

        // main data
        val pair = input["pair"] ?: error("pair")
        val period = input["period"]?.toLong() ?: error("period")
        val warmupTicks = input.getValue("warmupTicks").toInt()
        val cooldownTicks = input.getValue("cooldownTicks").toInt()
        val plotChart = input["plotChart"]?.toInt() ?: error("plotIndicators")
        val initialMoney = input.getValue("initialMoney").toDouble()
        val initialCoins = input.getValue("initialCoins").toDouble()

        // bt data
        val btSource = input.getValue("bt.source")
        val btCsvFile = input.getValue("bt.csv.file")
        val btCsvDateStart = input.getValue("bt.csv.startDate")
        val btCsvDateEnd = input.getValue("bt.csv.endDate")
        val btPolDays0 = input.getValue("bt.poloniex.dayRange").split("-")[0].toInt()
        val btPolDays1 = input.getValue("bt.poloniex.dayRange").split("-")[1].toInt()
        when (mode) {
            Mode.BACKTEST -> {
                // Set up
                strategyOutput.write("Starting backtesting $btPolDays0-day for $pair... (period: ${period/60} min)")
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

                // Build backtest ticks
                val exchange = PoloniexBacktestExchange(
                    pair = pair,
                    period = period,
                    fromEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(btPolDays0.toLong()).toEpochSecond(),
                    warmUpTicks = warmupTicks,
                    initialMoney = initialMoney,
                    initialCoins = initialCoins)
                val allTicks = mutableListOf<Bar>()
                if (btSource == "csv") {
                    strategyOutput.write("Parse ticks from $btCsvFile at period ${period.toInt()}")
                    try {
                        val ticks = parseCandlesFromCSV(
                            file = btCsvFile,
                            periodSeconds = period.toInt(),
                            startDate = LocalDate.parse(btCsvDateStart, DateTimeFormatter.ISO_DATE).atStartOfDay(),
                            endDate = LocalDate.parse(btCsvDateEnd, DateTimeFormatter.ISO_DATE).atStartOfDay())
                        allTicks.addAll(ticks)
                        strategyOutput.write("Parsed ${allTicks.size} ticks from CSV.")
                    } catch (e: Throwable) {
                        strategyOutput.write("exception reading csv: $e")
                        return
                    }
                } else if (btSource == "poloniex") {
                    strategyOutput.write("Using data from poloniex server...")
                    allTicks.addAll(exchange.warmUpHistory.toTypedArray())
                    val tickLimitEpoch = LocalDateTime.now().minusDays(btPolDays1.toLong()).toEpochSecond(ZoneOffset.UTC)
                    while (true) {
                        // ignore daysLimit.
                        val tick = exchange.fetchTick() ?: break
                        if (tick.beginTime.toEpochSecond() >= tickLimitEpoch) break
                        allTicks += tick
                    }
                } else error("invalid bt.source. Valid: 'csv' or 'poloniex'")

                // Execute strategy
                val timeSeries = BaseTimeSeries(allTicks)
                val strategy = Strategy(
                    output = strategyOutput,
                    series = timeSeries,
                    period = period,
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
                chartData.priceIndicators = if (plotChart >= 1) priceIndicators else emptyMap()
                chartData.extraIndicators = if (plotChart >= 1) extraIndicators else emptyMap()
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

        private fun parseCandlesFromCSV(
            file: String,
            periodSeconds: Int,
            startDate: LocalDateTime? = null,
            endDate: LocalDateTime? = null
        ): List<Bar> {
            val startEpochMilli = (startDate?.toEpochSecond(ZoneOffset.UTC) ?: 0) * 1000
            val endEpochMilli = (endDate?.toEpochSecond(ZoneOffset.UTC) ?: 0) * 1000
            val result = ArrayList<Bar>(50000)
            val duration = Duration.ofSeconds(periodSeconds.toLong())
            var firstLine = true
            for (line in Files.lines(Paths.get(file))) {
                if (firstLine) { firstLine = false; continue }

                // parse tick, check for time bounds
                val parts = line.split(",")
                val epoch = parts[0].toLong()
                if (epoch < startEpochMilli) continue
                else if (endEpochMilli in 1..(epoch - 1)) break

                val tick = BaseBar(
                    duration,
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(epoch), ZoneOffset.UTC),
                    DoubleNum.valueOf(parts[1].toDouble()), // open
                    DoubleNum.valueOf(parts[3].toDouble()), // high
                    DoubleNum.valueOf(parts[4].toDouble()), // low
                    DoubleNum.valueOf(parts[2].toDouble()), // close
                    DoubleNum.valueOf(parts[5].toDouble()), // volume
                    DoubleNum.valueOf(0)
                )
                result.add(tick)
            }
            return result
        }
    }
}