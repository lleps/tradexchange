package com.lleps.tradexchange.indicator

import com.lleps.tradexchange.util.get
import org.ta4j.core.Indicator
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.num.DoubleNum
import org.ta4j.core.num.Num

/** An com.tradexchange.indicator which computes their value based on two other indicators with a transformation */
class CompositeIndicator(
    private val indicatorA: Indicator<Num>,
    private val indicatorB: Indicator<Num>,
    private val function: (indicatorA: Double, indicatorB: Double) -> Double
) : CachedIndicator<Num>(indicatorA) {
    override fun calculate(index: Int): Num {
        return DoubleNum.valueOf(function(indicatorA[index], indicatorB[index]))
    }
}