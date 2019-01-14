package com.lleps.tradexchange.indicator

import org.ta4j.core.Decimal
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator

class OBVOscillatorIndicator(private val series: TimeSeries, val ticks: Int) : CachedIndicator<Decimal>(series) {
    private val obv = OnBalanceVolumeIndicator(series)

    override fun calculate(index: Int): Decimal {
        return Decimal.valueOf(obv.getValue(index).toDouble() - obv.getValue(index - ticks).toDouble())
    }
}