package com.lleps.tradexchange.server

import com.lleps.tradexchange.*
import com.lleps.tradexchange.strategy.ChartWriterImpl
import com.lleps.tradexchange.strategy.Strategy
import org.ta4j.core.BaseTimeSeries
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
            "autobuyBatch" to "3",
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
        repeat(chartData.candles.size) { i ->
            val features = mutableListOf<Double>()
            val tickTimestamp = chartData.candles[i].timestamp
            features.add(chartData.candles[i].close)
            for ((_, extraChartData) in chartData.extraIndicators) {
                for ((_, series) in extraChartData) {
                    val sorted = series.entries.sortedBy { it.key }.map { it.value }.toList()
                    features.add(sorted[i])
                }
            }
            val op = chartData.operations.firstOrNull { it.timestamp == tickTimestamp && it.type == type }
            val action = if (op != null) 1 else 0
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
        val ticks = fetchTicks(pair, period.toLong(), input, out)

        // Set up
        out.write("Chart data loaded (period: ${period/60} min, ${ticks.size} ticks)")
        val candles = mutableListOf<Candle>()
        val chartWriter = ChartWriterImpl()
        val timeSeries = BaseTimeSeries(ticks)
        val strategy = Strategy(
            output = out,
            series = timeSeries,
            period = period.toLong(),
            training = true,
            exchange = PoloniexBacktestExchange(),
            input = input
        )
        strategy.init()
        val operations = mutableListOf<Operation>()
        var buyCount = 0
        for (i in warmupTicks..timeSeries.endIndex) {
            if (i > warmupTicks && autobuyPeriod != 0 && (i % autobuyPeriod) == 0) {
                // find the lowest point and add autobuyBatch buys
                var minIndex = i
                var minValue = timeSeries.getBar(i - autobuyPeriod).closePrice.doubleValue()
                for (j in (i - autobuyPeriod) until i) {
                    val valueHere = timeSeries.getBar(j).closePrice.doubleValue()
                    if (valueHere < minValue) {
                        minIndex = j
                        minValue = valueHere
                    }
                }
                // add a buy point at j
                val autoBuyBatchSide = autobuyBatch/2
                for (k in (minIndex - autoBuyBatchSide)..(minIndex + autoBuyBatchSide)) {
                    val tick = timeSeries.getBar(k)
                    operations.add(
                        Operation(
                            tick.endTime.toEpochSecond(),
                            OperationType.BUY,
                            tick.closePrice.doubleValue(),
                            "autobuy #${++buyCount}"
                        ))
                }
            }
            val tick = timeSeries.getBar(i)
            val epoch = tick.beginTime.toEpochSecond()
            strategy.onDrawChart(chartWriter, epoch, i)
            candles.add(Candle(
                epoch,
                tick.openPrice.doubleValue(),
                tick.closePrice.doubleValue(),
                tick.maxPrice.doubleValue(),
                tick.minPrice.doubleValue()))
        }

        out.write("Indicators plotted. Click the buy candles and press Export")

        chartData.candles = candles
        chartData.operations = operations
        chartData.priceIndicators = chartWriter.priceIndicators
        chartData.extraIndicators = chartWriter.extraIndicators
        state.chartVersion++
        state.statusText = "Train mode"
        state.statusPositiveness = 1
        state.stateVersion++
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