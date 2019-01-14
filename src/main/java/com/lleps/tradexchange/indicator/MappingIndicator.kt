package com.lleps.tradexchange.indicator

import com.lleps.tradexchange.get
import org.ta4j.core.Decimal
import org.ta4j.core.Indicator
import org.ta4j.core.indicators.CachedIndicator

/** A transformation in top of an com.tradexchange.indicator. */
class MappingIndicator(private val indicator: Indicator<Decimal>, private val function: (Double) -> Double) : CachedIndicator<Decimal>(indicator) {
    override fun calculate(index: Int): Decimal {
        return Decimal.valueOf(function(indicator[index]))
    }
}