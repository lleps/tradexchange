package indicator

import eu.verdelhan.ta4j.Decimal
import eu.verdelhan.ta4j.Indicator
import eu.verdelhan.ta4j.indicators.CachedIndicator

class MinimumIndicator(private val indicator: Indicator<Decimal>, val ticks: Int) : CachedIndicator<Decimal>(indicator) {
    override fun calculate(index: Int): Decimal {
        var min = Double.MAX_VALUE
        for (i in (index-ticks)..index) {
            val value = indicator.getValue(i).toDouble()
            if (value < min) min = value
        }
        check(min != Double.MAX_VALUE) { "Can't get a minimum if time series is empty." }
        return Decimal.valueOf(min)
    }
}