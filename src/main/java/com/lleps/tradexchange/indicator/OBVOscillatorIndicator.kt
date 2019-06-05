package com.lleps.tradexchange.indicator

import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator
import org.ta4j.core.num.Num

class OBVOscillatorIndicator(
    private val series: TimeSeries,
    val ticks: Int
) : CachedIndicator<Num>(series) {
    private val obv = OnBalanceVolumeIndicator(series)

    override fun calculate(index: Int): Num {
        return obv.getValue(index).minus(obv.getValue(index - ticks))
    }
}