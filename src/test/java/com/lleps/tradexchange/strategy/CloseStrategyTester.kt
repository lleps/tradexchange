package com.lleps.tradexchange.strategy

import com.lleps.tradexchange.Candle
import com.lleps.tradexchange.Operation
import com.lleps.tradexchange.OperationType
import com.lleps.tradexchange.client.FullChart
import com.lleps.tradexchange.util.*
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.ta4j.core.BaseTimeSeries
import org.ta4j.core.TimeSeries
import kotlin.random.Random

/** To quickly test the strategy visually */
class CloseStrategyTester : Application() {
    private var initialTick = 0
    private var finalTick = 0
    private lateinit var chart: FullChart
    private var profitSum: Double = 0.0
    private var randomSum: Double = 0.0
    private lateinit var profitLabel: Label

    private fun execute(cfg: CloseStrategy.Config, retry: Boolean = false) {
        if (!retry) {
            // take candles at rsi>35
            while (true) {
                initialTick = 500 + Random.nextInt(series.barCount - cfg.expiry - 550)
                finalTick = initialTick + cfg.expiry + 1
                break
                //if (selectionIndicator[initialTick + 50] < selectionIndicator[finalTick]) break
            }
        }
        val initialPrice = series.getBar(initialTick).closePrice.doubleValue()
        val closeStrategy = CloseStrategy(cfg, series, initialTick, initialPrice)
        val multipleTimes = cfg.times > 1
        val chartWriter = if (!multipleTimes) ChartWriterImpl() else null
        val operations = mutableListOf<Operation>()
        var maxPrice = 0.0
        var minPrice = 999999.0
        var sellPrice = 0.0
        for (i in initialTick..finalTick) {
            val tickPrice = series.getBar(i).closePrice.doubleValue()
            maxPrice = maxOf(maxPrice, tickPrice)
            minPrice = minOf(minPrice, tickPrice)
            val result = closeStrategy.doTick(i, 0.0, chartWriter)
            if (result != null) {
                operations.add(Operation(
                    series.getBar(i).endTime.toEpochSecond(),
                    OperationType.SELL,
                    tickPrice,
                    result)
                )
                sellPrice = tickPrice
                break
            }
        }
        val expiryPrice = series.getBar(finalTick).closePrice.doubleValue()

        if (sellPrice == 0.0) {
            if (!multipleTimes) {
                profitLabel.text = "no sell"
                println("no sell")
            }
            return
        }

        // calculate results for strategy
        fun calcPercent(p: Double) = (p / initialPrice) * 100.0
        val profit = sellPrice - initialPrice
        val maxConst = maxPrice - initialPrice
        val minConst = minPrice - initialPrice
        val holdingConst = expiryPrice - initialPrice
        profitSum += calcPercent(profit)

        // calculate results for random
        val profitRandom = series.getBar(finalTick).closePrice.doubleValue() - initialPrice
        randomSum += calcPercent(profitRandom)

        // display stuff if necessary
        if (!multipleTimes && chartWriter != null) {
            chart.priceData = (initialTick..finalTick).map {
                val bar = series.getBar(it)
                Candle(
                    bar.endTime.toEpochSecond(),
                    bar.openPrice.doubleValue(),
                    bar.closePrice.doubleValue(),
                    bar.maxPrice.doubleValue(),
                    bar.minPrice.doubleValue())
            }
            chart.operations = operations
            chart.priceIndicators = chartWriter.priceIndicators
            chart.extraIndicators = chartWriter.extraIndicators
            chart.fill()
            profitLabel.text =
                ("Profit: $%.3f (%.1f%s)\n" +
                    "Max: $%.3f (%.1f%s)\n" +
                    "Min: $%.3f (%.1f%s)\n" +
                    "Holding: $%.3f (%.3f%s)") // TODO: para holding tendria que esperar a que termine.
                    .format(
                        profit, calcPercent(profit), "%",
                        maxConst, calcPercent(maxConst), "%",
                        minConst, calcPercent(minConst), "%",
                        holdingConst, calcPercent(holdingConst), "%"
                    )
            println("profit: ${calcPercent(profit)}%. sum: $profitSum%")
            if (profit < 0.0) {
                profitLabel.textFill = Color.RED
            } else {
                profitLabel.textFill = Color.GREEN
            }
        }
    }

    private lateinit var series: TimeSeries
    override fun start(stage: Stage) {
        val paths = listOf(
            //"../Bitfinex-historical-data/ETHUSD/Candles_1m/2017/merged.csv",
            //"../Bitfinex-historical-data/ETHUSD/Candles_1m/2018/merged.csv"
            "../Bitfinex-historical-data/ETHUSD/Candles_1m/2019/merged.csv"
        )
        println("loading...")
        val candles = parseCandlesFrom1MinCSV(paths.random(), 300)
        series = BaseTimeSeries(candles)
        println("loaded.")

        val initialRunConfig = loadFrom<CloseStrategy.Config>("CloseStrategyTestConfig.json")
            ?: CloseStrategy.Config()
        val jsonTextArea = TextArea(initialRunConfig.toJsonString(true))
        val runButton = Button("Run").apply {
            setOnAction {
                val cfg = parseJson<CloseStrategy.Config>(jsonTextArea.text)
                cfg.saveTo("CloseStrategyTestConfig.json")
                if (cfg.times > 1) {
                    profitSum = 0.0
                    randomSum = 0.0
                    repeat(cfg.times) {
                        execute(cfg)
                    }
                    val times = cfg.times
                    println(" ")
                    println("trading: $profitSum% ($times times). profit per trade avg: ${profitSum / times}%")
                    println("random: $randomSum% ($times times). profit per trade avg: ${randomSum / times}%")
                } else {
                    execute(cfg)
                }
            }
        }
        val retryButton = Button("Retry").apply {
            setOnAction {
                val cfg = parseJson<CloseStrategy.Config>(jsonTextArea.text)
                cfg.saveTo("CloseStrategyTestConfig.json")
                execute(cfg, retry = true)
            }
        }
        profitLabel = Label("profit: ???")
        chart = FullChart(useCandles = false)
        stage.scene = Scene(HBox(chart, VBox(jsonTextArea, runButton, retryButton, profitLabel)))
        stage.show()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            launch(CloseStrategyTester::class.java)
        }
    }
}