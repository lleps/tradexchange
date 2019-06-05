package com.lleps.tradexchange.indicator

import com.lleps.tradexchange.util.get
import org.ta4j.core.Indicator
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.num.DoubleNum
import org.ta4j.core.num.Num

/** A transformation in top of an com.tradexchange.indicator. */
class MappingIndicator(
    private val indicator: Indicator<Num>,
    private val function: (Double) -> Double
) : CachedIndicator<Num>(indicator) {
    override fun calculate(index: Int): Num {
        return DoubleNum.valueOf(function(indicator[index]))
    }
}