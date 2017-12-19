package indicator

import get
import org.ta4j.core.Decimal
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.indicators.helpers.HighestValueIndicator
import org.ta4j.core.indicators.helpers.LowestValueIndicator

/** An indicator which computes their value based on two other indicators with a transformation */
class CompositeIndicator(private val indicatorA: Indicator<Decimal>,
                         private val indicatorB: Indicator<Decimal>,
                         private val function: (indicatorA: Double, indicatorB: Double) -> Double) : CachedIndicator<Decimal>(indicatorA) {
    override fun calculate(index: Int): Decimal {
        return Decimal.valueOf(function(indicatorA[index], indicatorB[index]))
    }
}