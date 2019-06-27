package com.lleps.tradexchange.server

import com.lleps.tradexchange.*
import com.lleps.tradexchange.strategy.ChartWriterImpl
import com.lleps.tradexchange.strategy.PredictionModel
import com.lleps.tradexchange.strategy.Strategy
import com.lleps.tradexchange.util.markAs
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

    private var predictionModel: PredictionModel? = null

    override fun getRequiredInput(): Map<String, String> {
        return mapOf(
            "pair" to "USDT_ETH",
            "period" to "300",
            "trainType" to "buy",
            "autobuyPeriod" to "100",
            "autosellPeriod" to "100",
            "autobuyBatch" to "10",
            "autobuySeBatch" to "10",
            "autobuyOffset" to "1",
            "trainEpochs" to "15",
            "trainBatchSize" to "32",
            "trainTimesteps" to "7",
            "warmupTicks" to "300") +
            fetchTicksRequiredInput() +
            PredictionModel.getRequiredInput()
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

    // this doesn't need changes. Just exports what sees on extra charts.
    private fun exportAndBuildModelType(
        type: OperationType,
        epochs: Int,
        batchSize: Int,
        timesteps: Int,
        csvPath: String,
        modelPath: String
    ) {
        // Features are the extra chart series flat-mapped

        // for BUY should export all the extra charts.
        // however, for SELL should?
        // may use an extra charts to isolate features from the main concept. But
        // how to export it in csv format?
        // as long as there are 1 sell per moment, sells may be added as global
        // data. how?
        // first. the same price indicator.
        // second. an "in sell" indicator. can be a binary indicator, or "timeSinceBuy" with a sell expiry.
        // goes to zero if the last move was a sell. starts counting if the last mark is a buy.
        // thrid. the percent up since the buy was made. PercentSinceBuyIndicator.
        // can I just do this disabling all the buy noise?

        // ok. so extra charts should depend on the trainType (sell or buy).
        // and under "model" you should have instead, model.sell.* and model.buy.* so you
        // can actually select which features go on each one?
        // or you could do it sequentially?

        // the displayIndicators should be "buy" or "sell"?
        // maybe when you build the model you should be able to build them separately, the buy
        // model and the sell model.
        // so you set the trainType, and build just does it for the type you give. its ok.

        // so, to implement this...
        // first, make model.buy.* and model.sell.* features in the model input.
        //

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
        val cmd = listOf(
            "model/venv/bin/python",
            "model/buildmodel.py",
            epochs.toString(),
            batchSize.toString(),
            timesteps.toString(),
            csvPath,
            modelPath)
        out.write("$type: Invoke '$cmd'...")
        val exit = runCommand(cmd, onStdOut = { outStr -> out.write(outStr)})
        out.write("$type: Exit code: $exit.")
        if (exit != 0) out.write("$type: Something went wrong. Check the output.")
    }

    private fun exportAndBuildModel(input: Map<String, String>) {
        val epochs = input.getValue("trainEpochs").toInt()
        val batchSize = input.getValue("trainBatchSize").toInt()
        val timesteps = input.getValue("trainTimesteps").toInt()
        File("data/trainings").mkdir()
        File("data/models").mkdir()
        val buysCsv = "data/trainings/$instance-open.csv"
        val sellsCsv = "data/trainings/$instance-close.csv"
        val buysModel = "data/models/$instance-open.h5"
        val sellsModel = "data/models/$instance-close.h5"
        predictionModel!!.saveMetadata(instance)
        exportAndBuildModelType(OperationType.BUY, epochs, batchSize, timesteps, buysCsv, buysModel)
        exportAndBuildModelType(OperationType.SELL, epochs, batchSize, timesteps, sellsCsv, sellsModel)
    }

    private fun resetTrain(input: Map<String, String>) {
        state.output = ""

        val pair = input.getValue("pair")
        val type = OperationType.valueOf(input.getValue("trainType").toUpperCase())
        val period = input.getValue("period").toInt()
        val warmupTicks = input.getValue("warmupTicks").toInt()
        val autobuyPeriod = input.getValue("autobuyPeriod").toInt()
        val autosellPeriod = input.getValue("autosellPeriod").toInt()
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
            var i = warmupTicks
            var code = 1
            var buyPrice = 0.0
            val sellComparator: (Double, Double) -> Boolean = { a, b -> a > b }
            val buyComparator: (Double, Double) -> Boolean = { a, b -> a < b }
            var profitSum = 0.0
            while (i < timeSeries.endIndex - autobuyPeriod - 1) {
                if (buyPrice == 0.0) {
                    val idx = getBestBar(timeSeries, i, i + autobuyPeriod, buyComparator).first + autobuyOffset
                    val bar = timeSeries.getBar(idx)
                    val desc = "#$code at ${bar.closePrice}"
                    operations.add(Operation(
                        timestamp = bar.endTime.toEpochSecond(),
                        type = OperationType.BUY,
                        price = bar.closePrice.doubleValue(),
                        description = desc,
                        code = code))
                    buyPrice = bar.closePrice.doubleValue()
                    i = idx + 1
                } else {
                    val idx = getBestBar(timeSeries, i, i + autosellPeriod, sellComparator).first + autobuyOffset
                    val bar = timeSeries.getBar(idx)
                    val profit = (bar.closePrice.doubleValue() - buyPrice) / buyPrice * 100.0
                    val desc = "#$code at ${bar.closePrice} (%.2f%s)".format(profit, "%")
                    operations.add(Operation(
                        timestamp = bar.endTime.toEpochSecond(),
                        type = OperationType.SELL,
                        price = bar.closePrice.doubleValue(),
                        description = desc,
                        code = code))
                    buyPrice = 0.0
                    profitSum += profit
                    code++
                    i = idx + 1
                }
            }
            // remove the last unmatched buy
            if (operations.isNotEmpty() && operations.last().type == OperationType.BUY) {
                operations.removeAt(operations.size - 1)
            }
            val tradeCount = code - 1
            val trainDays = (
                timeSeries.lastBar.endTime.toEpochSecond() -
                    timeSeries.getBar(warmupTicks).endTime.toEpochSecond()) / 3600 / 24
            val tradesPerDay = tradeCount / trainDays.toDouble()
            out.write("$trainDays days")
            out.write("%d trades, avg %.1f%s (sum %.1f%s)"
                .format(tradeCount, profitSum / tradeCount.toDouble(), "%", profitSum, "%"))
            out.write("trades/day %.1f, profit/day %.1f%s"
                .format(tradesPerDay, profitSum / trainDays.toDouble(), "%"))

            // Mark series bars with trades metadata so pressure indicators can read them
            val epochsWithOps = mutableMapOf<Long, Int>() // timestamp->type (1 buy, 2 sell)
            for (op in operations) epochsWithOps[op.timestamp] = if (op.type == OperationType.SELL) 2 else 1
            for (bar in timeSeries.barData) {
                val opType = epochsWithOps[bar.endTime.toEpochSecond()]
                if (opType != null) bar.markAs(opType)
            }
        }

        // Draw features
        predictionModel = PredictionModel.createModel(timeSeries, input)
        val predictionModel = this.predictionModel!!
        for (i in warmupTicks..timeSeries.endIndex) {
            val tick = timeSeries.getBar(i)
            val epoch = tick.endTime.toEpochSecond()
            predictionModel.drawFeatures(type, i, epoch, chartWriter)
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

    private fun getBestBar(
        series: TimeSeries,
        fromInclusive: Int,
        toInclusive: Int,
        comparator: (Double, Double) -> Boolean
    ): Pair<Int, Bar> {
        var bestIdx = fromInclusive
        var bestVal = series.getBar(fromInclusive).closePrice.doubleValue()
        for (i in fromInclusive .. toInclusive) {
            val c = series.getBar(i).closePrice.doubleValue()
            if (comparator(c, bestVal)) {
                bestVal = c
                bestIdx = i
            }
        }
        return Pair(bestIdx, series.getBar(bestIdx))
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