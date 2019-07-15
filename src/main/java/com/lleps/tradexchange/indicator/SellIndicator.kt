package com.lleps.tradexchange.indicator

import com.lleps.tradexchange.util.getMark
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.num.Num

/** Base class for sell indicators. Contains a [buyTick] to indicate on which buy we're operating
 * and also implement indicator base methods.
 */
abstract class SellIndicator(val series: TimeSeries) : Indicator<Num> {
    override fun getTimeSeries(): TimeSeries {
        return series
    }

    override fun numOf(n: Number): Num {
        return series.numOf(n)
    }

    /**
     * If this is null the tick is calculated based on the last bar marked as buy.
     * Otherwise, its calculated since this tick.
     * Done this way to support multiple "instances" of buys at the same time "i",
     * for concurrent trades.
     */
    var buyTick: Int? = null

    protected fun calculateBuyTick(i: Int, lookback: Int): Int {
        return if (buyTick == null) {
            var lastBuyTick = 0
            for (j in (i - 1) downTo (i - lookback)) {
                if (j < 0) continue
                val mark = timeSeries.getBar(j).getMark()
                if (mark == 1/*buy*/) {
                    lastBuyTick = j
                    break
                } else if (mark == 2/*sell*/) {
                    break
                }
            }
            lastBuyTick
        } else {
            buyTick!!
        }
    }
}