package com.lleps.tradexchange.indicator

import org.ta4j.core.Indicator
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.indicators.helpers.HighestValueIndicator
import org.ta4j.core.indicators.helpers.LowestValueIndicator
import org.ta4j.core.num.Num

/** Normalize the [indicator] from -1 to 1 based on the given [timeFrame]. If [timeFrame] is 0, do not normalize at all. */
class NormalizationIndicator(
    private val indicator: Indicator<Num>,
    private val timeFrame: Int,
    maxBound: Indicator<Num> = indicator,
    minBound: Indicator<Num> = indicator,
    private val highestIndicator: HighestValueIndicator = HighestValueIndicator(maxBound, timeFrame),
    private val lowestIndicator: LowestValueIndicator = LowestValueIndicator(minBound, timeFrame),
    private val range: ClosedFloatingPointRange<Double> = -1.0 .. 1.0
) : CachedIndicator<Num>(indicator) {

    /** From 0..1 to -range..range */
    private fun applyRange(x: Double): Double {
        val rangeLength = range.endInclusive - range.start
        return range.start + (x * rangeLength)
    }

    public override fun calculate(index: Int): Num {
        if (timeFrame == 0) return indicator.getValue(index)
        val max = highestIndicator.getValue(index).doubleValue()
        val min = lowestIndicator.getValue(index).doubleValue()
        val last = indicator.getValue(index).doubleValue()
        return numOf(applyRange((last - min) / (max - min)))
    }
}