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
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import org.ta4j.core.num.DoubleNum
import org.ta4j.core.num.Num
import kotlin.random.Random

class CloseStrategy(val cfg: Config, val timeSeries: TimeSeries, val buyTick: Int, val buyPrice: Double) {

    companion object {

        private lateinit var ema: EMAIndicator
        private lateinit var avg14: EMAIndicator
        private lateinit var sd14: StandardDeviationIndicator
        private lateinit var middleBBand: BollingerBandsMiddleIndicator
        private lateinit var lowBBand: BollingerBandsLowerIndicator
        private lateinit var upBBand: BollingerBandsUpperIndicator
        private lateinit var close: ClosePriceIndicator

        var inited = false
        private var lastCfg: Config = Config()
    }

    data class Config(
        var topBarrierInitial: Double = 2.0,
        var bottomBarrierInitial: Double = 2.0,
        var priceWeightTop: Double = 2.0,
        var timeWeightTop: Double = 2.0,
        var priceWeightBottom: Double = 2.0,
        var timeWeightBottom: Double = 2.0,
        val bbk: Int = 2,
        val avgPeriod: Int = 14,
        val sdPeriod: Int = 14,
        val expiry: Int = 200,
        val longEmaPeriod: Int = 20,
        val shortEmaPeriod: Int = 3,
        val rsiPeriod: Int = 14,
        val openRsiValue: Int = 30,
        val rsiPeriodEnter: Int = 60,
        var times: Int = 1
    )

    init {
        // if the series change, should change too.
        if (inited && lastCfg != cfg) {
            inited = false
            lastCfg = cfg.copy()
        }
        if (!inited) {
            close = ClosePriceIndicator(timeSeries)
            ema = EMAIndicator(close, cfg.longEmaPeriod) // To detect market trend change
            avg14 = EMAIndicator(close, cfg.avgPeriod)
            sd14 = StandardDeviationIndicator(close, cfg.sdPeriod)
            middleBBand = BollingerBandsMiddleIndicator(avg14)
            lowBBand = BollingerBandsLowerIndicator(middleBBand, sd14, DoubleNum.valueOf(cfg.bbk))
            upBBand = BollingerBandsUpperIndicator(middleBBand, sd14, DoubleNum.valueOf(cfg.bbk))
            inited = true
        }
    }

    private var topBarrier: Double = buyPrice + pctToPrice(cfg.topBarrierInitial)
    private var bottomBarrier: Double = buyPrice - pctToPrice(cfg.bottomBarrierInitial)
    private var topBarrierCrossed = false
    private var timePassed: Int = 0
    private var startedDowntrend = false
    private var firstTick = true
    private var minWin = 0.0// how much win is secured

    /** Process the tick. Returns an string describing the trigger if should close, null otherwise. */
    fun doTick(i: Int, chart: Strategy.ChartWriter?): String? {
        val price = close[i]
        val epoch = timeSeries.getBar(i).endTime.toEpochSecond()
        val timePassed = (this.timePassed++).toDouble()
        val priceIncreasePct = priceToPct(price - buyPrice)

        if (firstTick) {
            startedDowntrend = close[i] < ema[i]
            firstTick = false
        }


        topBarrier += priceIncreasePct*cfg.priceWeightTop - timePassed*cfg.timeWeightTop
        bottomBarrier += priceIncreasePct.coerceAtLeast(0.0)*cfg.priceWeightBottom + timePassed*cfg.timeWeightBottom

        if (chart != null) {
            chart.priceIndicator("ema", epoch, ema[i])
            chart.priceIndicator("bb", epoch, middleBBand[i])
            chart.priceIndicator("low", epoch, lowBBand[i])
            chart.priceIndicator("up", epoch, upBBand[i])
            //chart.priceIndicator("topBarrier", epoch, topBarrier)
            //chart.priceIndicator("bottomBarrier", epoch, bottomBarrier)
            //chart.priceIndicator("minWin", epoch, minWin)
        }

        if ((timePassed >= 10.0 && ema.crossUnder(middleBBand, i))) return "middle"
        if (ema.crossUnder(upBBand, i)) return "upbband"
        if (ema.crossUnder(lowBBand, i)) return "lowbband"
        if (timePassed >= cfg.expiry) return "expiry"
        return null
    }

    private fun pctToPrice(pct: Double) = buyPrice * (pct / 100.0)
    private fun priceToPct(price: Double) = (price / buyPrice) * 100.0

    /** To quickly test the strategy visually */
    class Tester : Application() {
        private var initialTick = 0
        private var finalTick = 0
        private lateinit var chart: FullChart
        private var profitSum: Double = 0.0
        private var randomSum: Double = 0.0
        private lateinit var profitLabel: Label
        private lateinit var selectionIndicator: Indicator<Num>
        private var lastRsiPeriod = 0


        private fun execute(cfg: Config, retry: Boolean = false) {
            if (!retry) {
                // take candles at rsi>35
                while (true) {
                    initialTick = Random.nextInt(series.barCount - 200)
                    finalTick = initialTick + 200
                    break
                    //if (selectionIndicator[initialTick + 50] < selectionIndicator[finalTick]) break
                }
            }
            val initialTick = initialTick + 50
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
                val result = closeStrategy.doTick(i, chartWriter)
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
                            999999.0, 0.0, "%"
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
                "../Bitfinex-historical-data/ETHUSD/Candles_1m/2017/merged.csv"//,
                //"../Bitfinex-historical-data/ETHUSD/Candles_1m/2018/merged.csv"
                //"../Bitfinex-historical-data/ETHUSD/Candles_1m/2019/merged.csv"
            )
            println("loading...")
            val candles = parseCandlesFromCSV(paths.random(), 60)
            series = BaseTimeSeries(candles)
            println("loaded.")

            val initialRunConfig = loadFrom<Config>("CloseStrategyTestConfig.json") ?: Config()
            val jsonTextArea = TextArea(initialRunConfig.toJsonString(true))
            val runButton = Button("Run").apply {
                setOnAction {
                    val cfg = parseJson<Config>(jsonTextArea.text)
                    cfg.saveTo("CloseStrategyTestConfig.json")
                    if (lastRsiPeriod != cfg.rsiPeriodEnter) {
                        selectionIndicator = SMAIndicator(ClosePriceIndicator(series), cfg.rsiPeriodEnter)
                        lastRsiPeriod = cfg.rsiPeriodEnter
                    }
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
                    val cfg = parseJson<Config>(jsonTextArea.text)
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
                launch(Tester::class.java)
            }
        }
    }
}