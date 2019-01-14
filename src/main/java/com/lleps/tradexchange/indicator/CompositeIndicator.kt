package com.lleps.tradexchange.indicator

import com.lleps.tradexchange.get
import org.ta4j.core.Decimal
import org.ta4j.core.Indicator
import org.ta4j.core.indicators.CachedIndicator

/** An com.tradexchange.indicator which computes their value based on two other indicators with a transformation */
class CompositeIndicator(private val indicatorA: Indicator<Decimal>,
                         private val indicatorB: Indicator<Decimal>,
                         private val function: (indicatorA: Double, indicatorB: Double) -> Double) : CachedIndicator<Decimal>(indicatorA) {
    override fun calculate(index: Int): Decimal {
        return Decimal.valueOf(function(indicatorA[index], indicatorB[index]))
    }
}