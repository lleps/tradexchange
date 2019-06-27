package com.lleps.tradexchange.indicator

import com.lleps.tradexchange.util.getMark
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.num.Num

/**
 * Starts counting since the last buy. The difference with BuyPressureIndicator, is that resets to zero when
 * a sell occurs. If the last op is a sell, this is zero.
 */
class SellPressureIndicator(
    series: TimeSeries,
    val expiry: Int
) : SellIndicator(series) {

    private fun getPressure(tradeTick: Int, currentTick: Int, expiry: Int)
        = minOf(1.0, (currentTick - tradeTick).toDouble() / expiry.toDouble())

    override fun getValue(i: Int): Num {
        val t = calculateBuyTick(i, expiry)
        if (t == 0) return numOf(0.0)

        return numOf(getPressure(t, i, this.expiry))
    }
}