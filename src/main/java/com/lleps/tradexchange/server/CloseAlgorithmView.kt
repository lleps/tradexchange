package com.lleps.tradexchange.server

import com.google.gson.Gson
import com.lleps.tradexchange.util.gson
import com.lleps.tradexchange.util.loadFrom
import com.lleps.tradexchange.util.saveTo
import com.lleps.tradexchange.util.toJsonString
import javafx.application.Application
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.scene.Scene
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.Button
import javafx.scene.control.TextArea
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import kotlin.concurrent.thread

/*

posibles priceWeightTop: 0.1,0.3,0.5,0.7
posibles priceWeightBottom: 0.1,0.3,0.5,0.7
posibles timeWeightTop: 0.002, 0.004
posibles timeWeightBottom: 0.002, 0.004

cuando abre posicion:
    calcularWeights()
         mira el open pasado y 60 ticks
         ejecuta ese trade intercalando valores posibles
         mira la combinacion de valores que dio mejor resultado
         usar esa combinacion para el trade actual

 */
class CloseAlgorithmView : Application() {
    private lateinit var chart: LineChart<Number, Number>
    private lateinit var chartPriceSeries: XYChart.Series<Number, Number>
    private lateinit var topBarrierSeries: XYChart.Series<Number, Number>
    private lateinit var bottomBarrierSeries: XYChart.Series<Number, Number>
    private lateinit var configJson: TextArea

    private data class RunConfig(
        val ticks: Int = 20,
        var topBarrierInitial: Double = 2.0,
        var bottomBarrierInitial: Double = 2.0,
        var priceWeightTop: Double = 2.0,
        var timeWeightTop: Double = 2.0,
        var priceWeightBottom: Double = 2.0,
        var timeWeightBottom: Double = 2.0,
        val runSleepMillis: Int = 5
    )

    private fun runStrategyWithCfg(prices: List<Double>, cfg: RunConfig): Double {
        var result = 0.0
        for ((index, price) in prices.withIndex()) {
            update(index.toLong(), price, cfg)
            if (price > topBarrier || price < bottomBarrier) {
                result = price
                break
            }
        }
        return result
    }

    private fun calculateBestParametersBruteForce(initialCfg: RunConfig) {
        val configPoints = mutableMapOf<RunConfig,Int>()
        val gson = Gson()
        repeat(5000) { i ->
            println("Calculate... $i/5000")
            val prices = takeRandomPrices(initialCfg.ticks.toLong())
            val (bestStrategy, strategyScore) = calculateBestParameters(prices, initialCfg)
            configPoints[bestStrategy] = (configPoints[bestStrategy]?:0) + 1

        }
        println("best strategies: ")
        configPoints.entries
                .sortedByDescending { it.value }
                .forEachIndexed { index, (strategy, points) ->
                    println("#$index: $points points :: ${gson.toJson(strategy)}")
                }
    }

    /** Takes a series and default parameters, returns the best possible price. */
    private fun calculateBestParameters(prices: List<Double>, initialCfg: RunConfig): Pair<RunConfig, Double> {
        val priceWeighTops = listOf(0.2, 0.4, 0.6, 0.8)
        val timeWeightTops = listOf(0.0004, 0.0008, 0.001)
        val priceWeighBottoms = listOf(0.2, 0.4, 0.6, 0.8)
        val timeWeightBottom = listOf(0.002, 0.004, 0.006, 0.001)
        val topBarriersPct = listOf(0.25, 0.5, 1.0, 2.0)
        val bottomBarriersPct = listOf(1.0, 2.0, 3.0, 4.0)
        val cfgCopy = initialCfg.copy()
        var cfgIndex = 0
        var bestResult = 0.0
        var bestConfig = RunConfig()

        for (tbp in topBarriersPct) {
            for (bbp in bottomBarriersPct) {
                for (pwt in priceWeighTops) {
                    for (twt in timeWeightTops) {
                        for (pwb in priceWeighBottoms) {
                            for (twb in timeWeightBottom) {
                                cfgCopy.topBarrierInitial = tbp
                                cfgCopy.bottomBarrierInitial = bbp
                                cfgCopy.priceWeightBottom = pwb
                                cfgCopy.priceWeightTop = pwt
                                cfgCopy.timeWeightBottom = twb
                                cfgCopy.timeWeightTop = twt
                                val resultWithThisCfg = runStrategyWithCfg(prices, cfgCopy)
                                if (resultWithThisCfg > bestResult) {
                                    bestResult = resultWithThisCfg
                                    bestConfig = cfgCopy.copy()
                                    //println("new best #${cfgIndex++}, result: \$$resultWithThisCfg ::: $cfgCopy")
                                }
                            }
                        }
                    }
                }
            }
        }
        return Pair(bestConfig, bestResult)
    }

    private var lastPrice = 0.0
    private var topBarrier = 0.0
    private var bottomBarrier = 0.0

    private fun update(tickId: Long, price: Double, cfg: RunConfig) {
        if (tickId == 0L) {
            lastPrice = price
            val topOffset = price * (cfg.topBarrierInitial / 100.0)
            topBarrier = price + topOffset
            val bottomOffset = price * (cfg.bottomBarrierInitial / 100.0)
            bottomBarrier = price - bottomOffset
            return
        }

        // TODO: use percent on priceIncrease.
        // also should use percent to get the initial position for the barriers!
        val timePassed = tickId.toDouble()
        val priceIncrease = price - lastPrice
        lastPrice = price
        topBarrier += priceIncrease*cfg.priceWeightTop - timePassed*cfg.timeWeightTop
        bottomBarrier += priceIncrease.coerceAtLeast(0.0)*cfg.priceWeightBottom + timePassed*cfg.timeWeightBottom
    }

    private fun startFillingThread(cfg: RunConfig, prices: List<Double>) {
        thread(start = true) {
            println("starting")
            var tickId = 0L

            for (data in prices) {
                update(tickId, data, cfg)
                val tickNum = tickId
                val topBarrierConst = topBarrier
                val bottomBarrierConst = bottomBarrier
                Platform.runLater {
                    topBarrierSeries.data.add(XYChart.Data(tickNum, topBarrierConst))
                    bottomBarrierSeries.data.add(XYChart.Data(tickNum, bottomBarrierConst))
                    chartPriceSeries.data.add(XYChart.Data(tickNum, data))
                }
                if (data > topBarrier || data < bottomBarrier) break
                tickId++
                Thread.sleep(cfg.runSleepMillis.toLong())
            }
        }
    }

    private var lastTicks = listOf<Double>()
    private fun execute(cfg: RunConfig, retry: Boolean = false) {
        val maxTicks = cfg.ticks
        val ticks = if (!retry) {
            // generate new ticks
            takeRandomPrices(maxTicks.toLong())
        } else {
            // use ticks from last execution
            lastTicks
        }
        this.lastTicks = ticks
        chartPriceSeries.data.clear()
        topBarrierSeries.data.clear()
        bottomBarrierSeries.data.clear()
        (chart.xAxis as NumberAxis).upperBound = maxTicks.toDouble()
        //val (bestCfg, value) = calculateBestParameters(ticks, cfg)
        //println("Best value: $value. CFG: $bestCfg")
        //configJson.text = bestCfg.toJsonString()
        startFillingThread(cfg, ticks)
    }

    override fun start(primaryStage: Stage) {
        var type = 1
        if (type == 0) {
            calculateBestParametersBruteForce(RunConfig(
                    ticks = 120,
                    topBarrierInitial = 3.0,
                    bottomBarrierInitial = 4.0
            ))
        } else {
            // init chart ui
            val xAxis = NumberAxis(1.0, 2.0, 1.0)
            val yAxis = NumberAxis(160.0, 240.0, 1.0)
            yAxis.isForceZeroInRange = false
            yAxis.isAutoRanging = true
            chart = LineChart<Number, Number>(xAxis, yAxis)
            chart.animated = false
            chartPriceSeries = XYChart.Series()
            chartPriceSeries.name = "price"
            topBarrierSeries = XYChart.Series()
            topBarrierSeries.name = "topBarrier"
            bottomBarrierSeries = XYChart.Series()
            bottomBarrierSeries.name = "bottomBarrier"
            chart.data = FXCollections.observableArrayList(chartPriceSeries, bottomBarrierSeries, topBarrierSeries)

            // init config ui
            val initialRunConfig = loadFrom<RunConfig>("runConfig.json") ?: RunConfig()
            configJson = TextArea(initialRunConfig.toJsonString())
            val runButton = Button("Run").apply {
                setOnAction {
                    val config = gson.fromJson<RunConfig>(configJson.text, RunConfig::class.java)
                    config.saveTo("runConfig.json")
                    execute(config)
                }
            }
            val retryButton = Button("Retry").apply {
                setOnAction {
                    val config = gson.fromJson<RunConfig>(configJson.text, RunConfig::class.java)
                    config.saveTo("runConfig.json")
                    execute(config, retry = true)
                }
            }

            primaryStage.scene = Scene(BorderPane(chart, null, VBox(5.0, configJson, runButton, retryButton), null, null))
            primaryStage.show()
        }
    }

    private val filePrices = mutableMapOf<String, List<Double>>()
    private fun getFilePrices(path: String): List<Double> {
        return filePrices.getOrPut(path) {
            val result = mutableListOf<Double>()
            var lineNumber = 0
            for (line in Files.lines(Paths.get(path))) {
                if (lineNumber == 0) { // csv header.
                    lineNumber = 1
                }
                lineNumber++
                result.add(line.split(",")[2].toDouble())
            }
            result
        }
    }

    private fun takeRandomPrices(ticks: Long, path: String = ""): List<Double> {
        val paths = listOf(
                "../Bitfinex-historical-data/ETHUSD/Candles_1m/2017/merged.csv"
                //,
                //"../Bitfinex-historical-data/ETHUSD/Candles_1m/2018/merged.csv",
                //"../Bitfinex-historical-data/ETHUSD/Candles_1m/2019/merged.csv"
        )
        val pathString = if (path != "") path else paths[Random().nextInt(paths.size)]
        val startReading = 1 + Random().nextInt(400000)
        val result = mutableListOf<Double>()
        for (price in getFilePrices(pathString).subList(startReading, (startReading+ticks).toInt())) {
            result.add(price)
        }
        return result
    }

    private fun takeRandomPricesSlow(ticks: Long, path: String = ""): List<Double> {
        val paths = listOf(
            //"../Bitfinex-historical-data/BTCUSD/Candles_1m/2017/merged.csv",
            "../Bitfinex-historical-data/BTCUSD/Candles_1m/2018/merged.csv"
            //"../Bitfinex-historical-data/BTCUSD/Candles_1m/2019/merged.csv"
                )
        val pathString = if (path != "") path else paths[Random().nextInt(paths.size)]
        val result = mutableListOf<Double>()
        val startReading = 1 + Random().nextInt(600000)
        var lineNumber = 0
        for (line in Files.lines(Paths.get(pathString))) {
            lineNumber++
            if (lineNumber < startReading) continue
            else if (lineNumber >= startReading + ticks) break
            result.add(line.split(",")[2].toDouble())
        }
        return result
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            println()
            launch(CloseAlgorithmView::class.java)
        }
    }
}