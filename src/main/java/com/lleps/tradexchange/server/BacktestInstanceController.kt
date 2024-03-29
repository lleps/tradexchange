package com.lleps.tradexchange.server

import com.lleps.tradexchange.*
import com.lleps.tradexchange.strategy.ChartWriterImpl
import com.lleps.tradexchange.strategy.Strategy
import com.lleps.tradexchange.util.get
import org.ta4j.core.BaseTimeSeries
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import java.util.concurrent.atomic.AtomicBoolean

class BacktestInstanceController(
    val instance: String,
    val state: InstanceState,
    val chartData: InstanceChartData,
    val operationChartsWrapper: RESTServer.InstanceOperationCharts,
    val out: Strategy.OutputWriter
) : InstanceController {

    private var running = AtomicBoolean(false)

    override fun getRequiredInput(): Map<String, String> {
        return mapOf(
            "pair" to "USDT_ETH",
            "period" to "300",
            "warmupTicks" to "300",
            "cooldownTicks" to "300",
            "plotChart" to "3",
            "initialMoney" to "1000",
            "initialCoins" to "0") +
            fetchTicksRequiredInput() +
            Strategy.REQUIRED_INPUT
    }

    override fun onLoaded() {
        state.action1 = "Run"
        state.action2 = ""
    }

    override fun onCreated() {
    }

    override fun onDeleted() {
    }

    override fun onToggleCandle(candleEpoch: Long, toggle: Int) {
    }

    override fun onExecute(input: Map<String, String>, button: Int) {
        if (running.get()) {
            running.set(false)
            state.output = ""
            state.action1 = "Run"
            return
        }

        running.set(true)
        state.output = ""
        state.action1 = "Stop"
        state.statusText = "Starting..."
        state.trades = emptyList()

        // read input
        val pair = input.getValue("pair")
        val period = input.getValue("period").toInt()
        val warmupTicks = input.getValue("warmupTicks").toInt()
        val cooldownTicks = input.getValue("cooldownTicks").toInt()
        val plotChart = input["plotChart"]?.toInt() ?: error("plotIndicators")
        val initialMoney = input.getValue("initialMoney").toDouble()
        val initialCoins = input.getValue("initialCoins").toDouble()

        // Get market ticks
        val ticks = fetchTicks(pair, period.toLong(), input, out)

        // Set up
        out.write("Starting... (period: ${period/60} min, ${ticks.size} ticks)")
        val chartOperations = mutableListOf<Operation>()
        val candles = mutableListOf<Candle>()
        val chartWriter = ChartWriterImpl(plotChart)

        // Operation charts
        val opCharts = mutableMapOf<Int, InstanceChartData>()

        // Execute strategy
        val trades = mutableListOf<TradeEntry>()
        val exchange = PoloniexBacktestExchange(initialMoney, initialCoins)
        val timeSeries = BaseTimeSeries(ticks)
        val strategy = Strategy(
            output = out,
            series = timeSeries,
            period = period.toLong(),
            exchange = exchange,
            input = input
        )
        strategy.init()
        val startMillis = System.currentTimeMillis()
        var etaLock = 2L
        val etaP = 0.4
        var etaProgress = 0.0

        val sellOnlyTick = timeSeries.endIndex - cooldownTicks
        for (i in warmupTicks..timeSeries.endIndex) {
            if (!running.get()) {
                state.statusText = "Interrupted"
                state.statusPositiveness = -1
                state.action1 = "Run"
                return
            }
            val tick = timeSeries.getBar(i)
            val epoch = tick.endTime.toEpochSecond()
            exchange.marketPrice = tick.closePrice.doubleValue()
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
                Operation(epoch, type, tick.closePrice.doubleValue(), op.description, op.code)
            })
            for (op in operations) {
                if (op.type == Strategy.OperationType.SELL) {
                    val chartData = InstanceChartData(
                        candles = op.chart.candles,
                        operations = emptyList(),
                        priceIndicators = op.chart.priceIndicators,
                        extraIndicators = op.chart.extraIndicators
                    )
                    opCharts[op.code] = chartData
                    trades.add(TradeEntry(op.code, op.buyPrice, tick.closePrice.doubleValue(), op.amount))
                }
            }

            val secondsSinceStarted = (System.currentTimeMillis() - startMillis) / 1000
            if (secondsSinceStarted >= etaLock) {
                etaLock = secondsSinceStarted + 1
                val currentPercent = (i / timeSeries.endIndex.toDouble()) * 100
                val etaSeconds = (100 * secondsSinceStarted / currentPercent) - secondsSinceStarted
                etaProgress = (etaP) * etaProgress + (1.0-etaP) * etaSeconds
                state.statusText = "%d%s (eta %ds)".format(currentPercent.toInt(), "%", etaProgress.toInt())
                state.statusPositiveness = 1
                state.stateVersion++
            }

        }
        // Print resume
        val firstPrice = ClosePriceIndicator(timeSeries)[warmupTicks]
        val latestPrice = ClosePriceIndicator(timeSeries)[timeSeries.endIndex]
        out.write("  ______________________________________________________ ")
        out.write("                   RESULTS                               ")
        out.write(" Initial balance        %.03f'c $%.03f"
            .format(initialCoins, initialMoney))
        out.write(" Final balance          %.03f'c $%.03f (net %.03f'c \$%.03f)"
            .format(exchange.coinBalance, exchange.moneyBalance,
                exchange.coinBalance - initialCoins,
                exchange.moneyBalance - initialMoney))
        out.write(" Coin start/end value   $%.03f / $%.03f (net $%.03f)"
            .format(firstPrice, latestPrice, latestPrice - firstPrice))
        out.write(" Trades: ${strategy.tradeCount}")
        val buyTriggers = getOperationListTriggers(chartOperations, OperationType.BUY)
            .joinToString(separator = ", ") { "${it.first} (${it.second}%)" }
        val sellTriggers = getOperationListTriggers(chartOperations, OperationType.SELL)
            .joinToString(separator = ", ") { "${it.first} (${it.second}%)" }
        out.write(" Buy triggers: $buyTriggers")
        out.write(" Sell triggers: $sellTriggers")
        out.write("  ______________________________________________________ ")

        // Update view
        chartData.candles = if (plotChart >= 1) candles else emptyList()
        chartData.operations = if (plotChart >= 1) chartOperations else emptyList()
        chartData.priceIndicators = if (plotChart >= 1) chartWriter.priceIndicators else emptyMap()
        chartData.extraIndicators = if (plotChart >= 1) chartWriter.extraIndicators else emptyMap()
        operationChartsWrapper.map = opCharts
        state.chartVersion++

        // Update trades
        state.trades = trades.toList()
        state.stateVersion++

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

        running.set(false)
        state.action1 = "Run"
    }

    /** Returns a list of pairs (trigger, percent). To get the most common triggers. */
    private fun getOperationListTriggers(ops: List<Operation>, type: OperationType): List<Pair<String, Int>> {
        val triggerCount = mutableMapOf<String, Int>()
        var registeredTriggers = 0
        for (op in ops) {
            if (op.type == type) {
                val fullDescription = op.description ?: continue
                val descriptionTriggerString = fullDescription.split("________")[1].replace("\n", "")
                val trigger = descriptionTriggerString.split(":")[0]
                triggerCount[trigger] = (triggerCount[trigger]?:0) + 1
                registeredTriggers++
            }
        }
        // now should convert each entry to percentage
        val result = mutableListOf<Pair<String, Int>>()
        for ((trigger, count) in triggerCount) {
            result += trigger to (count.toDouble() / registeredTriggers.toDouble() * 100.0).toInt()
        }
        return result.sortedByDescending { it.second }
    }
}