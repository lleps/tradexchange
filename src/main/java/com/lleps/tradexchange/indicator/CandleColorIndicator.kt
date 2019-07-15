package com.lleps.tradexchange.indicator

import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.num.Num

class CandleColorIndicator(val series: TimeSeries) : Indicator<Num> {
    // todo maybe use max and min as well, to give more info about the size of the candle
    override fun getTimeSeries(): TimeSeries {
        return series
    }

    override fun numOf(num: Number?): Num {
        return series.numOf(num)
    }

    override fun getValue(i: Int): Num {
        return series.getBar(i).closePrice - series.getBar(i).openPrice
    }
}