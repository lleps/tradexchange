package com.lleps.tradexchange.strategy

import com.lleps.tradexchange.util.*
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.ATRIndicator
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import org.ta4j.core.num.Num

class CloseStrategy(val cfg: Config, val timeSeries: TimeSeries, val buyTick: Int, val buyPrice: Double) {

    companion object {
        private lateinit var shortEma: EMAIndicator
        private lateinit var longEma: EMAIndicator
        private lateinit var middleBBand: BollingerBandsMiddleIndicator
        private lateinit var lowBBand: BollingerBandsLowerIndicator
        private lateinit var upBBand: BollingerBandsUpperIndicator
        private lateinit var close: ClosePriceIndicator
        private lateinit var atrIndicator: Indicator<Num>

        var inited = false
        private var lastCfg: Config = Config()
    }

    data class Config(
        val expiry: Int = 200,
        // barriers
        var topBarrierMultiplier: Double = 2.0,
        var bottomBarrierMultiplier: Double = 2.0,
        var priceWeightTop: Double = 0.0,
        var timeWeightTop: Double = 0.0,
        var priceWeightBottom: Double = 0.0,
        var timeWeightBottom: Double = 0.0,
        // to detect volatility
        var atrPeriod: Int = 24,
        var atrAvgPeriod: Int = 24,
        // various indicators, 0 means not used
        val bbPeriod: Int = 14,
        val longEmaPeriod: Int = 20,
        val shortEmaPeriod: Int = 3,
        // to bulk-test, not used in the strategy itself but in the executor
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
            shortEma = EMAIndicator(close, cfg.shortEmaPeriod) // To detect market trend change
            longEma = EMAIndicator(close, cfg.longEmaPeriod) // To detect market trend change
            val avg14 = EMAIndicator(close, cfg.bbPeriod)
            val sd14 = StandardDeviationIndicator(close, cfg.bbPeriod)
            middleBBand = BollingerBandsMiddleIndicator(avg14)
            lowBBand = BollingerBandsLowerIndicator(middleBBand, sd14)
            upBBand = BollingerBandsUpperIndicator(middleBBand, sd14)
            val atr = ATRIndicator(timeSeries, cfg.atrPeriod)
            atrIndicator = EMAIndicator(atr, cfg.atrAvgPeriod)
            inited = true
        }
    }

    private var topBarrier: Double = buyPrice + pctToPrice(cfg.topBarrierMultiplier)
    private var bottomBarrier: Double = buyPrice - pctToPrice(cfg.bottomBarrierMultiplier)
    private var timePassed: Int = 0
    private var startedDowntrend = false
    private var firstTick = true

    /** Process the tick. Returns an string describing the trigger if should close, null otherwise. */
    fun doTick(i: Int, globalSellPrediction: Double, chart: Strategy.ChartWriter?): String? {
        val price = close[i]
        val epoch = timeSeries.getBar(i).endTime.toEpochSecond()
        val timePassed = (this.timePassed++).toDouble()
        val priceIncreasePct = priceToPct(price - buyPrice)

        // update state
        if (firstTick) {
            topBarrier = buyPrice + (atrIndicator[i] * cfg.topBarrierMultiplier)
            bottomBarrier = buyPrice - (atrIndicator[i] * cfg.bottomBarrierMultiplier)
            startedDowntrend = close[i] < shortEma[i]
            firstTick = false
        }
        topBarrier += priceIncreasePct*cfg.priceWeightTop - timePassed*cfg.timeWeightTop
        bottomBarrier += priceIncreasePct.coerceAtLeast(0.0)*cfg.priceWeightBottom + timePassed*cfg.timeWeightBottom

        // draw state
        if (chart != null) {
            //chart.priceIndicator("ema", epoch, ema[i])
            if (cfg.bbPeriod != 0) {
                chart.priceIndicator("bb", epoch, middleBBand[i])
                chart.priceIndicator("low", epoch, lowBBand[i])
                chart.priceIndicator("up", epoch, upBBand[i])
            }
            if (cfg.shortEmaPeriod != 0) chart.priceIndicator("shortEma", epoch, shortEma[i])
            if (cfg.longEmaPeriod != 0) chart.priceIndicator("longEma", epoch, longEma[i])
            if (cfg.topBarrierMultiplier != 0.0) chart.priceIndicator("topBarrier", epoch, topBarrier)
            if (cfg.bottomBarrierMultiplier != 0.0) chart.priceIndicator("bottomBarrier", epoch, bottomBarrier)
            chart.extraIndicator("atr", "atr", epoch, atrIndicator[i])
            chart.extraIndicator("ml", "ml", epoch, globalSellPrediction)
        }

        // check triggers
        if (cfg.topBarrierMultiplier != 0.0 && price > topBarrier) return "topBarrier"
        if (cfg.bottomBarrierMultiplier != 0.0 && price < bottomBarrier) return "bottomBarrier"
        //if ((timePassed >= 10.0 && ema.crossUnder(middleBBand, i))) return "middle"
        //if (ema.crossUnder(upBBand, i)) return "upbband"
        //if (ema.crossUnder(lowBBand, i)) return "lowbband"
        if (cfg.expiry != 0 && timePassed >= cfg.expiry) return "expiry"
        return null
    }

    private fun pctToPrice(pct: Double) = buyPrice * (pct / 100.0)
    private fun priceToPct(price: Double) = (price / buyPrice) * 100.0

}