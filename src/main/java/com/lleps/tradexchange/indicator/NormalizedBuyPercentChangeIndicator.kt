package com.lleps.tradexchange.indicator

import com.lleps.tradexchange.util.get
import com.lleps.tradexchange.util.getMark
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.num.Num

/**
 * Calculates the percent since the last buy (or a buy set through [buyTick]).
 * The results starts with 0.5, and the top/bottom is touched on the given percentTop.
 */
class NormalizedBuyPercentChangeIndicator(
    series: TimeSeries,
    private val maxPercent: Double = 10.0,
    private val indicator: ClosePriceIndicator = ClosePriceIndicator(series)
) : SellIndicator(series) {

    override fun getValue(i: Int): Num {
        val t = calculateBuyTick(i, 300) // idk how far in the past to detect it. This is only for training anyway
        if (t == 0 || t >= i) return numOf(0.5)

        val initial = indicator[t]
        val pct = (indicator[i] - initial) / initial
        val maxPct = maxPercent / 100.0
        return numOf(0.5 + ((pct / 2.0) / maxPct))
    }
}