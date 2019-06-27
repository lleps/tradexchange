package com.lleps.tradexchange.indicator

import com.lleps.tradexchange.util.getMark
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.num.Num

/**
 * Starts counting since the last buy. The difference with BuyPressureIndicator, is that resets to zero when
 * a sell occurs. If the last op is a sell, this is zero.
 * Maybe will need to keep separate instances of this indicator to meet concurrent trades. because
 * different trades may have different pressures at time i.
 * Or this can be fixed using mutators for this indicator, like calculate(i, tradeIdx) and setTradeIdx(i, idx)
 * Or maybe use a different indicatorGroup in the model (for this one only)
 */
class SellPressureIndicator(
    series: TimeSeries,
    val expiry: Int
) : CachedIndicator<Num>(series) {

    private fun getPressure(tradeTick: Int, currentTick: Int, expiry: Int)
        = minOf(1.0, (currentTick - tradeTick).toDouble() / expiry.toDouble())

    override fun calculate(i: Int): Num {
        var lastBuyTick = 0
        for (j in (i - 1) downTo (i - expiry)) {
            val mark = timeSeries.getBar(j).getMark()
            if (mark == 1/*buy*/) {
                lastBuyTick = j
                break
            } else if (mark == 2/*sell. return 0*/) {
                return numOf(0.0)
            }
        }

        if (lastBuyTick == 0) return numOf(0.0)

        return numOf(getPressure(lastBuyTick, i, this.expiry))
    }
}