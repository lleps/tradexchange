package com.lleps.tradexchange.server

import com.lleps.tradexchange.*
import com.lleps.tradexchange.strategy.ChartWriterImpl
import com.lleps.tradexchange.strategy.Strategy
import org.ta4j.core.Bar
import org.ta4j.core.BaseTimeSeries
import org.ta4j.core.TimeSeries
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths

class TrainInstanceController(
    val instance: String,
    val state: InstanceState,
    val chartData: InstanceChartData,
    val out: Strategy.OutputWriter
) : InstanceController {

    override fun getRequiredInput(): Map<String, String> {
        return mapOf(
            "pair" to "USDT_ETH",
            "period" to "300",
            "autobuyPeriod" to "100",
            "autobuyBatch" to "10",
            "autobuyOffset" to "1",
            "warmupTicks" to "300") +
            fetchTicksRequiredInput() +
            Strategy.REQUIRED_INPUT
    }

    override fun onLoaded() {
        state.action1 = "Reset & Update"
        state.action2 = "Build model"
    }

    override fun onCreated() {
    }

    override fun onDeleted() {
    }

    override fun onExecute(input: Map<String, String>, button: Int) {
        if (button == 1) resetTrain(input)
        else exportAndBuildModel(input)
    }

    private fun exportAndBuildModelType(type: OperationType, csvPath: String, modelPath: String) {
        out.write("$type: Exporting to $csvPath...")
        val sb = StringBuilder()
        val opsByTimestamp = hashMapOf<Long, Operation>()
        for (op in chartData.operations) opsByTimestamp[op.timestamp] = op
        // Convert indicator points to list to access them by index.
        val extraIndicatorsList = mutableListOf<List<Double>>()
        for (chartDataEntry in chartData.extraIndicators.entries) {
            for (seriesDataEntry in chartDataEntry.value.entries) {
                // ensure indicator series size match the candles
                check(seriesDataEntry.value.size == chartData.candles.size) {
                    "size of indicator series (${seriesDataEntry.value.size} != candles size (${chartData.candles.size})"
                }
                val list = mutableListOf<Double>()
                extraIndicatorsList.add(list)
                for (pointDataEntry in seriesDataEntry.value.entries) {
                    list.add(pointDataEntry.value)
                }
            }
        }
        repeat(chartData.candles.size) { i ->
            val features = mutableListOf<Double>()
            val tickTimestamp = chartData.candles[i].timestamp
            features.add(chartData.candles[i].close)
            for (indicator in extraIndicatorsList) {
                features.add(indicator[i])
            }
            val op = opsByTimestamp[tickTimestamp]
            val action = if (op != null && op.type == type) 1 else 0
            sb.append(features.joinToString(separator = ","))
            sb.append(",$action\n")
        }
        Files.write(Paths.get(csvPath), sb.toString().toByteArray(Charset.defaultCharset()))
        val cmd = listOf("model/venv/bin/python", "model/buildmodel.py", csvPath, modelPath)
        out.write("$type: Invoke '$cmd'...")
        val exit = runCommand(cmd, onStdOut = { outStr -> out.write(outStr)})
        out.write("$type: Exit code: $exit.")
        if (exit != 0) out.write("$type: Something went wrong. Check the output.")
    }

    private fun exportAndBuildModel(input: Map<String, String>) {
        File("data/trainings").mkdir()
        File("data/models").mkdir()
        val buysCsv = "data/trainings/$instance-open.csv"
        val sellsCsv = "data/trainings/$instance-close.csv"
        val buysModel = "data/models/$instance-open.h5"
        val sellsModel = "data/models/$instance-close.h5"
        exportAndBuildModelType(OperationType.BUY, buysCsv, buysModel)
        exportAndBuildModelType(OperationType.SELL, sellsCsv, sellsModel)
    }

    private fun resetTrain(input: Map<String, String>) {
        state.output = ""

        val pair = input.getValue("pair")
        val period = input.getValue("period").toInt()
        val warmupTicks = input.getValue("warmupTicks").toInt()
        val autobuyPeriod = input.getValue("autobuyPeriod").toInt()
        val autobuyBatch = input.getValue("autobuyBatch").toInt()
        val autobuyOffset = input.getValue("autobuyOffset").toInt()
        val ticks = fetchTicks(pair, period.toLong(), input, out)

        // Set up
        out.write("Chart data loaded (period: ${period/60} min, ${ticks.size} ticks)")
        val candles = mutableListOf<Candle>()
        val chartWriter = ChartWriterImpl()
        val timeSeries = BaseTimeSeries(ticks)

        // Add autobuy points.
        // Added before plotting, because it's necessary to attach
        // trades to the bars so the BuyPressureIndicator can read it.
        // Indicators can only read state from series and bars, and for convenience
        // the pressure is expressed as an indicator.
        val operations = mutableListOf<Operation>()
        if (autobuyPeriod != 0) {
            operations.addAll(doAutobuyInSeries(
                series = timeSeries,
                seriesPeriod = period,
                autobuyPeriod = autobuyPeriod,
                autobuyBatch = autobuyBatch,
                autobuyOffset = autobuyOffset,
                warmupTicks = warmupTicks,
                type = OperationType.SELL,
                comparator = { a, b -> a > b }
            ))
            val buys = doAutobuyInSeries(
                series = timeSeries,
                seriesPeriod = period,
                autobuyPeriod = autobuyPeriod,
                autobuyBatch = autobuyBatch,
                autobuyOffset = autobuyOffset,
                warmupTicks = warmupTicks,
                type = OperationType.BUY,
                comparator = { a, b -> a < b }
            )
            operations.addAll(buys)
            val epochsWithBuys = mutableSetOf<Long>()
            for (op in buys) epochsWithBuys.add(op.timestamp)
            for (bar in timeSeries.barData) {
                val hasTrades = bar.endTime.toEpochSecond() in epochsWithBuys
                if (hasTrades) bar.addTrade(timeSeries.numOf(0), bar.minPrice)
            }
        }

        // Run strategy and plot indicators (aka features)
        val strategy = Strategy(
            output = out,
            series = timeSeries,
            period = period.toLong(),
            training = true,
            exchange = PoloniexBacktestExchange(),
            input = input
        )
        strategy.init()
        for (i in warmupTicks..timeSeries.endIndex) {
            val tick = timeSeries.getBar(i)
            val epoch = tick.endTime.toEpochSecond()
            strategy.onDrawChart(chartWriter, epoch, i)
            candles.add(Candle(
                epoch,
                tick.openPrice.doubleValue(),
                tick.closePrice.doubleValue(),
                tick.maxPrice.doubleValue(),
                tick.minPrice.doubleValue()))
        }
        out.write("Done")

        chartData.candles = candles
        chartData.operations = operations
        chartData.priceIndicators = chartWriter.priceIndicators
        chartData.extraIndicators = chartWriter.extraIndicators
        state.chartVersion++
        state.statusText = "Train mode"
        state.statusPositiveness = 1
        state.stateVersion++
    }

    private fun doAutobuyInSeries(
        series: TimeSeries,
        seriesPeriod: Int,
        autobuyPeriod: Int,
        autobuyBatch: Int,
        autobuyOffset: Int,
        warmupTicks: Int,
        type: OperationType,
        comparator: (Double, Double) -> Boolean // should return true if a is a "better point" than b, false otherwise
    ): List<Operation> {
        val result = mutableListOf<Operation>()

        // Add best points iterating from warmupTicks to the end, in jumps of autobuyPeriod ticks.
        // Get smallest point in i..(i+period) = r. At the next iteration, start from r
        var i = warmupTicks
        while (i + autobuyPeriod < series.endIndex) {
            val (idx, bar) = getMinCandle(series, i, i + autobuyPeriod, comparator)
            val barOffset = series.getBar(idx + autobuyOffset)
            result.add(Operation(barOffset.endTime.toEpochSecond(), type, barOffset.closePrice.doubleValue()))
            i = idx + autobuyOffset + 1
        }

        // Filter points too close
        // If we find two consecutive points p1,p2 whose distance in ticks is < autobuyBatch, only keep the best.
        // Keep going until we don't find such points in result
        while (true) {
            var foundBadPoints = false
            for (pIdx in 0 until (result.size - 1)) {
                val p1 = result[pIdx]
                val p2 = result[pIdx + 1]
                val distanceInTicks = (p2.timestamp - p1.timestamp) / seriesPeriod
                if (distanceInTicks < autobuyBatch) {
                    if (comparator(p1.price, p2.price)) result.remove(p2)
                    else result.remove(p1)
                    foundBadPoints = true
                    break
                }
            }
            if (!foundBadPoints) break
        }
        return result
    }

    private fun getMinCandle(series: TimeSeries, fromInclusive: Int, toInclusive: Int, comparator: (Double, Double) -> Boolean): Pair<Int, Bar> {
        var minIdx = fromInclusive
        var minVal = series.getBar(fromInclusive).closePrice.doubleValue()
        for (i in fromInclusive .. toInclusive) {
            val c = series.getBar(i).closePrice.doubleValue()
            if (comparator(c, minVal)) {
                minVal = c
                minIdx = i
            }
        }
        return Pair(minIdx, series.getBar(minIdx))
    }

    override fun onToggleCandle(candleEpoch: Long, toggle: Int) {
        val candleAtThisEpoch = chartData.candles.firstOrNull { it.timestamp == candleEpoch } ?: error("can't find candle")
        if (toggle != 0) {
            // build tick description, with all the useful info
            val tickDescription = buildString {
                append("Price: $${candleAtThisEpoch.close}\n")
                for ((chartName, chartSeries) in chartData.extraIndicators) {
                    for ((series, seriesPoints) in chartSeries) {
                        for ((epoch, value) in seriesPoints) {
                            if (epoch == candleEpoch) {
                                append("$chartName:$series: $value\n")
                            }
                        }
                    }
                }
            }
            // add tick to the chart
            val type = if (toggle == -1) OperationType.BUY else OperationType.SELL
            chartData.operations += Operation(candleEpoch, type, candleAtThisEpoch.close, tickDescription)
        } else {
            // remove tick from the chart
            val op = chartData.operations.firstOrNull { it.timestamp == candleEpoch } ?: error("cant find op")
            chartData.operations -= op
        }
        state.chartVersion++
    }

    private fun runCommand(command: List<String>, onStdOut: (String) -> Unit): Int {
        val pb = ProcessBuilder()
            .command(*command.toTypedArray())
            .redirectErrorStream(true)
        val p = pb.start()
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        while (true) {
            val out = reader.readLine() ?: break
            onStdOut(out)
        }
        val exitValue = p.exitValue()
        p.destroy()
        return exitValue
    }
}