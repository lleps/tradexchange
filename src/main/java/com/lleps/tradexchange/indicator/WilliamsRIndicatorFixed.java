package com.lleps.tradexchange.indicator;

import org.ta4j.core.Indicator;
import org.ta4j.core.TimeSeries;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.num.Num;

/** Fix NaN problem described in https://github.com/ta4j/ta4j/issues/375 */
public class WilliamsRIndicatorFixed extends CachedIndicator<Num> {
    private final Indicator<Num> indicator;
    private final int barCount;
    private MaxPriceIndicator maxPriceIndicator;
    private MinPriceIndicator minPriceIndicator;
    private final Num multiplier;

    public WilliamsRIndicatorFixed(TimeSeries timeSeries, int barCount) {
        this(new ClosePriceIndicator(timeSeries), barCount, new MaxPriceIndicator(timeSeries), new MinPriceIndicator(timeSeries));
    }

    public WilliamsRIndicatorFixed(Indicator<Num> indicator, int barCount, MaxPriceIndicator maxPriceIndicator, MinPriceIndicator minPriceIndicator) {
        super(indicator);
        this.indicator = indicator;
        this.barCount = barCount;
        this.maxPriceIndicator = maxPriceIndicator;
        this.minPriceIndicator = minPriceIndicator;
        this.multiplier = this.numOf(-100);
    }

    protected Num calculate(int index) {
        HighestValueIndicator highestHigh = new HighestValueIndicator(this.maxPriceIndicator, this.barCount);
        LowestValueIndicator lowestMin = new LowestValueIndicator(this.minPriceIndicator, this.barCount);
        Num highestHighPrice = (Num)highestHigh.getValue(index);
        Num lowestLowPrice = (Num)lowestMin.getValue(index);
        // if highestHighPrice = lowestLowPrice, the division is NaN. Fix that
        if (highestHighPrice.isEqual(lowestLowPrice)) highestHighPrice = highestHighPrice.plus(numOf(0.0000001));
        return highestHighPrice.minus((Num)this.indicator.getValue(index)).dividedBy(highestHighPrice.minus(lowestLowPrice)).multipliedBy(this.multiplier);
    }

    public String toString() {
        return this.getClass().getSimpleName() + " barCount: " + this.barCount;
    }
}
