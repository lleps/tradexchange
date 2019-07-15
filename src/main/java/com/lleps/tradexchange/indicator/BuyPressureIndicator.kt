package com.lleps.tradexchange.indicator

import com.lleps.tradexchange.util.getMark
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.num.Num

/**
 * Calculates the pressure to buy. The longer since last buy, the higher the pressure.
 * Depends on markAs and getMark() utility calls from ta4jUtil to know when a bar was a buy or not.
 */
class BuyPressureIndicator(
    private val series: TimeSeries,
    private val expiry: Int,
    private val concurrentTrades: Int,
    private val warmupTicks: Int // to not add pressure on those ticks. Otherwise the model will be more likely to buy at the beginning.
) : Indicator<Num> {

    override fun getTimeSeries(): TimeSeries {
        return series
    }

    override fun numOf(num: Number?): Num {
        return series.numOf(num)
    }

    /** Returns the percent of time (between 0-1) passed since [tradeTick] to [currentTick], being expiry 100%. */
    private fun getPressure(tradeTick: Int, currentTick: Int, expiry: Int): Double {
        val ticksPassed = maxOf(0.0, (currentTick - tradeTick - 1).toDouble())
        return minOf(1.0, ticksPassed / expiry.toDouble())
    }

    override fun getValue(i: Int): Num {
        // need to find time since last concurrentTrades trades.
        // so, run the "pressure" for each past trade, sum it, and return the average.
        var count = 0
        val lastTradesTick = Array(concurrentTrades) { warmupTicks }
        for (j in (i - 1) downTo (i - expiry)) {
            if (j > 1 && timeSeries.getBar(j).getMark() == 1/*buy*/) {
                lastTradesTick[count++] = j
                if (count == concurrentTrades) break
            }
        }

        val totalPressure = lastTradesTick.sumByDouble { tick -> getPressure(tick, i, this.expiry) }
        return numOf(totalPressure / concurrentTrades.toDouble())
    }
}