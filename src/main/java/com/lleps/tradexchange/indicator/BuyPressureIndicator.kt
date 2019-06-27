package com.lleps.tradexchange.indicator

import com.lleps.tradexchange.util.getMark
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.num.Num

/**
 * Calculates the pressure to buy. The longer since last buy, the higher the pressure.
 * Depends on markAs and getMark() utility calls from ta4jUtil to know when a bar was a buy or not.
 */
class BuyPressureIndicator(
    series: TimeSeries,
    private val expiry: Int,
    private val concurrentTrades: Int,
    private val warmupTicks: Int // to not add pressure on those ticks. Otherwise the model will be more likely to buy at the beginning.
) : CachedIndicator<Num>(series) {

    private fun getPressure(tradeTick: Int, currentTick: Int, expiry: Int)
        = minOf(1.0, (currentTick - tradeTick).toDouble() / expiry.toDouble())

    override fun calculate(i: Int): Num {
        // need to find time since last concurrentTrades trades?
        // with 1 concurrentTrades.
        // if ... should be 1: time since last trade >= expiry.
        // if ... should be .25: time since last trade == expiry / 4
        // with >1 concurrent trade, two "instances" of this algorithm. Each one makes 0.5 of the total.
        // so, run the "pressure" for each past trade, sum it, and return it.
        // Look back to last [concurrentTrades] trades to check the pressure.
        var count = 0
        val lastTradesTick = Array(concurrentTrades) { warmupTicks }
        for (j in (i - 1) downTo (i - expiry)) {
            if (timeSeries.getBar(j).getMark() == 1/*buy*/) {
                lastTradesTick[count++] = j
                if (count == concurrentTrades) break
            }
        }

        val totalPressure = lastTradesTick.sumByDouble { tick -> getPressure(tick, i, this.expiry) }
        return numOf(totalPressure / concurrentTrades.toDouble())
    }
}