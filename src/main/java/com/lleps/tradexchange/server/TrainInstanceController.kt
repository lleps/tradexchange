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

    private fun exportAndBuildModel(input: Map<String, String>) {
        File("data/trainings").mkdir()
        File("data/models").mkdir()
        val path = "data/trainings/$instance.csv"
        val outModel = "data/models/$instance.h5"
        out.write("Exporting to $path...")
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
            val buy = chartData.operations.firstOrNull { it.timestamp == tickTimestamp }
            val action = if (buy != null) 1 else 0
            sb.append(features.joinToString(separator = ","))
            sb.append(",$action\n")
        }
        Files.write(Paths.get(path), sb.toString().toByteArray(Charset.defaultCharset()))
        val cmd = listOf("model/venv/bin/python", "model/buildmodel.py", path, outModel)
        out.write("Invoke '$cmd'...")
        val exit = runCommand(cmd, onStdOut = { outStr -> out.write(outStr)})
        out.write("Exit code: $exit.")
        if (exit != 0) out.write("Something went wrong. Check the output.")
    }

    private fun resetTrain(input: Map<String, String>) {
        state.output = ""

        val pair = input.getValue("pair")
        val period = input.getValue("period").toInt()
        val warmupTicks = input.getValue("warmupTicks").toInt()
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
        for (i in warmupTicks..timeSeries.endIndex) {
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
        chartData.operations = emptyList()
        chartData.priceIndicators = chartWriter.priceIndicators
        chartData.extraIndicators = chartWriter.extraIndicators
        state.chartVersion++
        state.statusText = "Train mode"
        state.statusPositiveness = 1
        state.stateVersion++
    }

    override fun onToggleCandle(candleEpoch: Long, toggle: Boolean) {
        val candleAtThisEpoch = chartData.candles.firstOrNull { it.timestamp == candleEpoch } ?: error("can't find candle")
        if (toggle) {
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
            chartData.operations += Operation(candleEpoch, OperationType.BUY, candleAtThisEpoch.close, tickDescription)
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