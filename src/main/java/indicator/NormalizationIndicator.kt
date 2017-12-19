package indicator

import get
import org.ta4j.core.Decimal
import org.ta4j.core.Indicator
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.indicators.helpers.HighestValueIndicator
import org.ta4j.core.indicators.helpers.LowestValueIndicator

class NormalizationIndicator(private val indicator: Indicator<Decimal>, timeFrame: Int) : CachedIndicator<Decimal>(indicator) {
    private val highestIndicator = HighestValueIndicator(indicator, timeFrame)
    private val lowestIndicator = LowestValueIndicator(indicator, timeFrame)

    public override fun calculate(index: Int): Decimal {
        val max = highestIndicator[index]
        val min = lowestIndicator[index]
        val last = indicator.getValue(index).toDouble()
        return Decimal.valueOf((last - min) / (max - min))
    }
}