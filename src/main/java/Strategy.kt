import indicator.NormalizationIndicator
import indicator.OBVOscillatorIndicator
import org.slf4j.LoggerFactory
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.MACDIndicator
import org.ta4j.core.indicators.ROCIndicator
import org.ta4j.core.indicators.RSIIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.helpers.HighestValueIndicator
import org.ta4j.core.indicators.helpers.LowestValueIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator

class Strategy(private val series: TimeSeries,
               private val chart: TradeChart,
               private val exchange: Exchange) {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(Strategy::class.java)
    }

    // Each tick is every 30 min, therefore, 2 ticks = 1 hour
    val Int.hours get() = this * 2
    val Int.days get() = this * (2 * 24)

    val close = ClosePriceIndicator(series)
    val max = HighestValueIndicator(close, 4.hours) // 2-day Maximum price
    val min = LowestValueIndicator(close, 4.hours) // 2-day Minimum price
    val normalMacd = NormalizationIndicator(MACDIndicator(close, 12.hours, 26.hours), 4.days)
    val macd = MACDIndicator(close, 12.hours, 26.hours)
    val longMA = EMAIndicator(close, 5.days)
    val shortMA = EMAIndicator(close, 12.hours)
    val rsi = RSIIndicator(close, 20.hours)
    val obv = OnBalanceVolumeIndicator(series)

    val avg14 = EMAIndicator(close, 24.hours)
    val sd14 = StandardDeviationIndicator(close, 24.hours)
    val middleBBand = BollingerBandsMiddleIndicator(avg14)
    val lowBBand = BollingerBandsLowerIndicator(middleBBand, sd14)
    val upBBand = BollingerBandsUpperIndicator(middleBBand, sd14)
    val obvOscillator = OBVOscillatorIndicator(series, 5.days)

    var wantToSell = true

    private val OPEN_TRADES_COUNT = 7
    // Strategy a. Hold up to MAX_COINS coins. Sell when their price is up 10%

    class OpenTrade(val buyPrice: Double, val amount: Double)
    var openTrades = listOf<OpenTrade>()

    var actionLock = 0
    val roc = ROCIndicator(rsi, 9)

    // Executed for each tick (30 mins)
    fun onTick(i: Int) {
        val epoch = series.getTick(i).endTime.toEpochSecond()
        var action = false

        if (actionLock > 0) actionLock--
        // Strategy
        else if (close[i] >= upBBand[i]) {
            if (!openTrades.isEmpty()) {
                // Check if there are any coin to sell (the coin whose price is up +20 usd)
                // Sell ALL coins that match the predicate? Or only one? For now, one.

                // TODO check for recent sells?
                val coinToSell = openTrades.firstOrNull { close[i] > it.buyPrice + 20 }
                if (coinToSell != null) {
                    exchange.sell(coinToSell.amount, close[i])
                    chart.addPoint("Sell", epoch, close[i])
                    action = true
                    openTrades -= coinToSell
                    actionLock = 4.hours
                    LOGGER.info("Trade %.03f'c    buy $%.03f    sell $%.03f    won $%.03f"
                            .format(coinToSell.amount, coinToSell.buyPrice, close[i], close[i] - coinToSell.buyPrice))
                }
            }
        } else if (close[i] <= lowBBand[i] && exchange.moneyBalance > 0) {
            if (openTrades.size < OPEN_TRADES_COUNT) {
                // amount = if (unsoldCoins == 0) exchange.moneyBalance / 5
                val amountOfMoney = exchange.moneyBalance / (OPEN_TRADES_COUNT - openTrades.size).toDouble()
                val amountOfCoins = amountOfMoney / close[i]
                chart.addPoint("Buy", epoch, close[i])
                exchange.buy(amountOfCoins, close[i])
                action = true
                openTrades += OpenTrade(close[i], amountOfCoins)
                actionLock = 4.hours
            }
        }

        if (!action) {
            chart.addPoint("Price", epoch, close[i])
        }

        // Plot indicators
        chart.addPoint("short MA", epoch, shortMA[i])
        chart.addPoint("long MA", epoch, longMA[i])
        chart.addPoint("BB Upper", epoch, upBBand[i])
        chart.addPoint("BB Lower", epoch, lowBBand[i])
        chart.addPointExtra("MACD", "macd", epoch, macd[i])
        //chart.addPointExtra("RSI", "obv", epoch, rsi[i])
        chart.addPointExtra("OBV-O", "obv-o", epoch, obvOscillator[i])
        //chart.addPointExtra("OBV", "both", epoch, obv[i])
    }
}