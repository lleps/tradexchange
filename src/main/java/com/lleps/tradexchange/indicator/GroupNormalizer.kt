package com.lleps.tradexchange.indicator

import com.lleps.tradexchange.util.get
import org.ta4j.core.Indicator
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.num.Num

/** Normalizes [indicator], but using min and max from the given [builder], instead of the indicator. */
class GroupNormalizer(
    val indicator: Indicator<Num>,
    val builder: Builder,
    val period: Int
) : CachedIndicator<Num>(indicator) {

    /**
     * This builder is just a container to hold all the
     * indicators used to normalize. Also caches min and max values
     * for each timestep.
     */
    class Builder {
        private val indicators = mutableListOf<Indicator<Num>>()
        private val cache = hashMapOf<Int, Pair<Double, Double>>()

        fun addIndicator(indicator: Indicator<Num>) { indicators.add(indicator) }

        fun getMinMax(i: Int): Pair<Double, Double> {
            return cache.getOrPut(i) {
                var max = 0.0
                var min = 0.0
                for (ind in indicators) {
                    val indValue = ind[i]
                    if (min == 0.0 || indValue < min) min = indValue
                    if (max == 0.0 || indValue > max) max = indValue
                }
                Pair(min, max)
            }
        }
    }

    /** This calculates the (min,max) point between (i - period + 1) .. i and normalizes [indicator] based on those. */
    override fun calculate(i: Int): Num {
        var max: Double = 0.0
        var min: Double = 0.0
        for (tick in (i - period + 1) .. i) {
            val (localMin, localMax) = builder.getMinMax(tick)
            if (min == 0.0 || localMin < min) min = localMin
            if (max == 0.0 || localMax > max) max = localMax
        }
        return numOf((indicator[i] - min) / (max - min))
    }
}