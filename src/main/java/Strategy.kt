import indicator.MaximumIndicator
import indicator.MinimumIndicator
import indicator.NormalizationIndicator
import indicator.OBVOscillatorIndicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator

class Strategy(private val series: TimeSeries,
               private val chart: TradeChart,
               private val exchange: Exchange) {

    // Each tick is every 30 min, therefore, 2 ticks = 1 hour
    val Int.hours get() = this * 2
    val Int.days get() = this * (2 * 24)

    val close = ClosePriceIndicator(series)
    val max = MaximumIndicator(close, 2.days) // 2-day Maximum price
    val min = MinimumIndicator(close, 2.days) // 2-day Minimum price
    val macd = MACDIndicator(close, 12.hours, 26.hours)
    val longMA = EMAIndicator(close, 5.days)
    val shortMA = EMAIndicator(close, 12.hours)
    val rsi = RSIIndicator(close, 20.hours)
    val obv = OnBalanceVolumeIndicator(series)

    val avg14 = EMAIndicator(close, 14.hours)
    val sd14 = StandardDeviationIndicator(close, 14.hours)
    val middleBBand = BollingerBandsMiddleIndicator(avg14)
    val lowBBand = BollingerBandsLowerIndicator(middleBBand, sd14)
    val upBBand = BollingerBandsUpperIndicator(middleBBand, sd14)

    // Executed for each tick (30 mins)
    fun onTick(i: Int) {
        val epoch = series.getTick(i).endTime.toEpochSecond()

        // Strategy
        if (rsi[i] > 70) {
            chart.addPoint("Sell", epoch, close[i])
            exchange.sell(exchange.coinBalance, close[i])
        } else if (rsi[i] < 30) {
            chart.addPoint("Buy", epoch, close[i])
            exchange.buy(exchange.moneyBalance / close[i], close[i])
        } else {
            chart.addPoint("Price", epoch, close[i])
        }

        // Plot indicators
        chart.addPoint("short MA", epoch, shortMA[i])
        chart.addPoint("long MA", epoch, longMA[i])
        chart.addPoint("BB Upper", epoch, upBBand[i])
        chart.addPoint("BB Lower", epoch, lowBBand[i])
        chart.addPointExtra("MACD", "macd", epoch, macd[i])
        chart.addPointExtra("RSI", "obv", epoch, rsi[i])
        chart.addPointExtra("OBV", "both", epoch, obv[i])
    }
}