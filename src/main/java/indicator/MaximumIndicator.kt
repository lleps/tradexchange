package indicator

import eu.verdelhan.ta4j.Decimal
import eu.verdelhan.ta4j.Indicator
import eu.verdelhan.ta4j.indicators.CachedIndicator

class MaximumIndicator(private val indicator: Indicator<Decimal>, val ticks: Int) : CachedIndicator<Decimal>(indicator) {
    override fun calculate(index: Int): Decimal {
        var max = Double.MIN_VALUE
        for (i in (index-ticks)..index) {
            val value = indicator.getValue(i).toDouble()
            if (value > max) max = value
        }
        check(max != Double.MIN_VALUE) { "Can't get a maximum if time series is empty." }
        return Decimal.valueOf(max)
    }
}