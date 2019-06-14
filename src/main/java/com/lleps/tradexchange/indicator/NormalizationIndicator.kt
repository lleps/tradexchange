package com.lleps.tradexchange.indicator

import org.ta4j.core.Indicator
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.indicators.helpers.HighestValueIndicator
import org.ta4j.core.indicators.helpers.LowestValueIndicator
import org.ta4j.core.num.Num

/** Normalize an indicator based on the timeFrame given. If timeFrame is 0, do not normalize at all. */
class NormalizationIndicator(
    private val indicator: Indicator<Num>,
    private val timeFrame: Int
) : CachedIndicator<Num>(indicator) {
    private val highestIndicator = HighestValueIndicator(indicator, timeFrame)
    private val lowestIndicator = LowestValueIndicator(indicator, timeFrame)

    public override fun calculate(index: Int): Num {
        if (timeFrame == 0) return indicator.getValue(index)
        val max = highestIndicator.getValue(index)
        val min = lowestIndicator.getValue(index)
        val last = indicator.getValue(index)
        return last.minus(min).dividedBy(max.minus(min))
    }
}