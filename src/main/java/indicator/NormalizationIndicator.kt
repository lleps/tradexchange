package indicator

import eu.verdelhan.ta4j.Decimal
import eu.verdelhan.ta4j.Indicator
import eu.verdelhan.ta4j.indicators.CachedIndicator

class NormalizationIndicator(private val indicator: Indicator<Decimal>, private val ticks: Int = -1) : CachedIndicator<Decimal>(indicator) {
    override fun calculate(index: Int): Decimal {
        var min = Double.MAX_VALUE
        var max = Double.MIN_VALUE
        val rangeStart = if (ticks == -1) 0 else index - ticks
        for (i in rangeStart..index) {
            val value = indicator.getValue(i).toDouble()
            if (value > max) max = value
            if (value < min) min = value
        }
        val last = indicator.getValue(index).toDouble()
        return Decimal.valueOf((last - min) / (max - min))
    }
}