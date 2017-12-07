import indicator.MaximumIndicator
import indicator.MinimumIndicator
import indicator.NormalizationIndicator
import indicator.OBVOscillatorIndicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator

class Strategy(private val series: TimeSeries,
               private val chart: TradeChart,
               private val exchange: Exchange) {

    private val close = ClosePriceIndicator(series)
    private val max = MaximumIndicator(close, 48*2)
    private val min = MinimumIndicator(close, 48*2)
    private val obvOscillator = NormalizationIndicator(OBVOscillatorIndicator(series, 3*2))
    private val macd = NormalizationIndicator(MACDIndicator(close, 12, 26))
    private val longSMA = EMAIndicator(close, 48*2)
    private val shortSMA = EMAIndicator(close, 48/2)
    private val avg14 = EMAIndicator(close, 14)
    private val sd14 = StandardDeviationIndicator(close, 14)
    private val middleBBand = BollingerBandsMiddleIndicator(avg14)
    private val lowBBand = BollingerBandsLowerIndicator(middleBBand, sd14)
    private val upBBand = BollingerBandsUpperIndicator(middleBBand, sd14)

    private var timePassed = 0
    private val maxTime = 24*2 // Limit time for a trade
    private var wantToSell = true

    fun onTick(i: Int) {
        timePassed++

        var action = false
        val epoch = series.getTick(i).endTime.toEpochSecond()
        val percent = avg(obvOscillator[i] to 1, macd[i] to 2)

        if (wantToSell) {
            if (close[i] > upBBand[i]) {
                val passed = (timePassed / maxTime).toDouble()
                val chanceOfSell = avg(passed to 1, percent to 6)
                if (chanceOfSell > .8) {
                    action = true
                    chart.addPoint("Sell", epoch, close[i])
                    timePassed = 0
                    exchange.sell(exchange.coinBalance, close[i])
                    wantToSell = false
                }
            }
        }

        // Waiting to buy
        else if (!wantToSell) {
            if (close[i] < lowBBand[i]) {
                val passed = (timePassed / maxTime).toDouble()
                val chanceOfBuy = avg(passed to 1, (1.0 - percent) to 6)
                if (chanceOfBuy > .8) {
                    action = true
                    chart.addPoint("Buy", epoch, close[i])
                    timePassed = 0
                    exchange.buy(exchange.moneyBalance / close[i], close[i])
                    wantToSell = true
                }
            }
        }

        if (!action) {
            chart.addPoint("Price", epoch, close[i])
        }

        chart.addPoint("bbUpper", epoch, upBBand[i])
        chart.addPoint("bbLower", epoch, lowBBand[i])
        chart.addPointExtra("MACD-OBV", "macd", epoch, macd[i])
        chart.addPointExtra("MACD-OBV", "obv", epoch, obvOscillator[i])
        chart.addPointExtra("MACD-OBV", "both", epoch, percent)
        chart.addPointExtra("BALANCE", "money", epoch, exchange.moneyBalance)
    }
}