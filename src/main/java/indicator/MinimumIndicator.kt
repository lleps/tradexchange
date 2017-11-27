package indicator

import org.ta4j.core.Decimal
import org.ta4j.core.Indicator
import org.ta4j.core.indicators.CachedIndicator


class MinimumIndicator(private val indicator: Indicator<Decimal>, val ticks: Int) : CachedIndicator<Decimal>(indicator) {
    public override fun calculate(index: Int): Decimal {
        var min = Double.MAX_VALUE
        for (i in (index-ticks)..index) {
            val value = indicator.getValue(i).toDouble()
            if (value < min) min = value
        }
        check(min != Double.MAX_VALUE) { "Can't get a minimum if time series is empty." }
        return Decimal.valueOf(min)
    }
}