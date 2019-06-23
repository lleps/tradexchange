package com.lleps.tradexchange.indicator

import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.CachedIndicator
import org.ta4j.core.num.Num

class BuyPressureIndicator(
    series: TimeSeries,
    val expiry: Int,
    val concurrentTrades: Int,
    val warmupTicks: Int // to not add pressure on those ticks. Otherwise the model will be more likely to buy at the beginning.
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
            if (timeSeries.getBar(j).trades > 0) {
                lastTradesTick[count++] = j
                if (count == concurrentTrades) break
            }
        }

        val totalPressure = lastTradesTick.sumByDouble { tick -> getPressure(tick, i, this.expiry) }
        return timeSeries.numOf(totalPressure / concurrentTrades.toDouble())
    }
}