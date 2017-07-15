package indicator

import eu.verdelhan.ta4j.Decimal
import eu.verdelhan.ta4j.TimeSeries
import eu.verdelhan.ta4j.indicators.CachedIndicator
import eu.verdelhan.ta4j.indicators.volume.OnBalanceVolumeIndicator

class OBVOscillatorIndicator(private val series: TimeSeries, val ticks: Int) : CachedIndicator<Decimal>(series) {
    private val obv = OnBalanceVolumeIndicator(series)

    override fun calculate(index: Int): Decimal {
        return Decimal.valueOf(obv.getValue(index).toDouble() - obv.getValue(index - ticks).toDouble())
    }
}