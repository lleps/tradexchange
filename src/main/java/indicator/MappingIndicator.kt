package indicator

import get
import org.ta4j.core.Decimal
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.indicators.helpers.HighestValueIndicator
import org.ta4j.core.indicators.helpers.LowestValueIndicator

/** A transformation in top of an indicator. */
class MappingIndicator(private val indicator: Indicator<Decimal>, private val function: (Double) -> Double) : CachedIndicator<Decimal>(indicator) {
    override fun calculate(index: Int): Decimal {
        return Decimal.valueOf(function(indicator[index]))
    }
}