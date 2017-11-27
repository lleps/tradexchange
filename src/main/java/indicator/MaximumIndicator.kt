package indicator

import org.ta4j.core.Decimal
import org.ta4j.core.Indicator
import org.ta4j.core.indicators.CachedIndicator

class MaximumIndicator(private val indicator: Indicator<Decimal>, val ticks: Int) : CachedIndicator<Decimal>(indicator) {
    public override fun calculate(index: Int): Decimal {
        var max = Double.MIN_VALUE
        for (i in (index-ticks)..index) {
            val value = indicator.getValue(i).toDouble()
            if (value > max) max = value
        }
        check(max != Double.MIN_VALUE) { "Can't get a maximum if time series is empty." }
        return Decimal.valueOf(max)
    }
}