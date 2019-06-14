package com.lleps.tradexchange.indicator;

import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

public class PercentBIndicatorFixed extends CachedIndicator<Num> {
    private final Indicator<Num> indicator;
    private final BollingerBandsUpperIndicator bbu;
    private final BollingerBandsLowerIndicator bbl;

    public PercentBIndicatorFixed(Indicator<Num> indicator, int barCount, double k) {
        super(indicator);
        this.indicator = indicator;
        BollingerBandsMiddleIndicator bbm = new BollingerBandsMiddleIndicator(new SMAIndicator(indicator, barCount));
        StandardDeviationIndicator sd = new StandardDeviationIndicator(indicator, barCount);
        this.bbu = new BollingerBandsUpperIndicator(bbm, sd, this.numOf(k));
        this.bbl = new BollingerBandsLowerIndicator(bbm, sd, this.numOf(k));
    }

    protected Num calculate(int index) {
        Num value = (Num)this.indicator.getValue(index);
        Num upValue = (Num)this.bbu.getValue(index);
        Num lowValue = (Num)this.bbl.getValue(index);
        if (upValue.isEqual(lowValue)) upValue = upValue.plus(numOf(0.000001));
        return value.minus(lowValue).dividedBy(upValue.minus(lowValue));
    }
}
