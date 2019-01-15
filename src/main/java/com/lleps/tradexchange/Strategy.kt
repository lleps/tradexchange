package com.lleps.tradexchange

import com.lleps.tradexchange.indicator.CompositeIndicator
import com.lleps.tradexchange.indicator.NormalizationIndicator
import org.slf4j.LoggerFactory
import org.ta4j.core.Decimal
import org.ta4j.core.Indicator
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
import java.awt.Composite
import java.util.*

class Strategy(
    private val series: TimeSeries,
    private val chart: TradeChart,
    private val period: Long,
    private val backtest: Boolean,
    private val epochStopBuy: Long,
    private val exchange: Exchange) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger(Strategy::class.java)
    }

    // State
    private class OpenTrade(val buyPrice: Double, val amount: Double, val epoch: Long, val code: Int)
    private var openTrades = listOf<OpenTrade>()
    private var actionLock = 0

    // Indicators
    private val close = ClosePriceIndicator(series)
    private val macd = MACDIndicator(close, 12, 26)
    private val macdSignal = EMAIndicator(macd, 9)
    private val macdHistogram = CompositeIndicator(macd, macdSignal) { macd, macdSignal -> macd - macdSignal }
    private val normalMacd = NormalizationIndicator(macd, 30)
    private val longMA = EMAIndicator(close, 24)
    private val shortMA = EMAIndicator(close, 12)
    private val rsi = RSIIndicator(close, 14)
    private val avg14 = EMAIndicator(close, 24)
    private val sd14 = StandardDeviationIndicator(close, 24)
    private val middleBBand = BollingerBandsMiddleIndicator(avg14)
    private val lowBBand = BollingerBandsLowerIndicator(middleBBand, sd14)
    private val upBBand = BollingerBandsUpperIndicator(middleBBand, sd14)
    private val volatiltyIndicatorBB = CompositeIndicator(upBBand, lowBBand) { up, low -> up - low }
    private val normalMacd2 = NormalizationIndicator(macd, 200)
    private val obvIndicator = NormalizationIndicator(OnBalanceVolumeIndicator(series), 80)

    // com.tradexchange.Strategy config
    private val openTradesCount = 10
    private val tradeExpiry = 200 // give up if can't meet the margin
    private val marginToSell = 2

    private fun crossedOver(indicator: Indicator<Decimal>, target: Indicator<Decimal>, i: Int): Boolean {
        return indicator[i] > target[i] && indicator[i - 1] < target[i - 1]
    }

    private fun crossedBelow(indicator: Indicator<Decimal>, target: Indicator<Decimal>, i: Int): Boolean {
        return indicator[i] < target[i] && indicator[i - 1] > target[i - 1]
    }

    private fun shouldOpen(i: Int, epoch: Long): Boolean {
        //return /*close[i] < lowBBand[i] || */rsi[i] < 30 || (macd[i - 1] < 0 && macd[i] > 0)
        return close[i] < lowBBand[i]//rsi[i] < 30 //crossedBelow(macd, macdSignal, i)
    }

    private fun shouldClose(i: Int, epoch: Long, trade: OpenTrade): Boolean {
        if (epoch - trade.epoch > tradeExpiry * period) return true // trade expire at this point
        // TODO why not
        return close[i] > upBBand[i]//close[i] > trade.buyPrice + marginToSell // || crossedOver(macd, macdSignal, i) //rsi[i] > 70 || (macd[i - 1] > 0 && macd[i] < 0)
    }

    fun onTick(i: Int) {
        val epoch = series.getTick(i).endTime.toEpochSecond()
        var action = false

        if (actionLock > 0) {
            actionLock--
        } else {
            // Try to buy
            if (openTrades.size < openTradesCount && (!backtest || epoch < epochStopBuy)) { // BUY
                if (shouldOpen(i, epoch)) {
                    val amountOfMoney = (exchange.moneyBalance) / (openTradesCount - openTrades.size).toDouble()
                    val amountOfCoins = amountOfMoney / close[i]
                    val trade = OpenTrade(close[i], amountOfCoins, epoch, Random().nextInt(2000))
                    chart.addPoint("Buy", epoch, close[i], "Open #%d at $%.03f".format(trade.code, trade.buyPrice))
                    exchange.buy(amountOfCoins, close[i])
                    action = true
                    openTrades += trade
                    actionLock = 5
                }
            }

            // Try to sell
            if (!action) {
                val closedTrades = openTrades.filter { shouldClose(i, epoch, it) }
                openTrades -= closedTrades
                for (trade in closedTrades) {
                    exchange.sell(trade.amount * 0.99999, close[i])
                    val diff = close[i] - trade.buyPrice
                    val tradeStrLog =
                        "Trade %.03f'c    buy $%.03f    sell $%.03f    won $%.03f"
                            .format(trade.amount, trade.buyPrice, close[i], diff)
                    val tooltip = "Close #%d: $%.03f\nBuy $%.03f   Sell $%.03f\nTime %d min (%d ticks)".format(
                        trade.code,
                        diff,
                        trade.buyPrice,
                        close[i],
                        (epoch - trade.epoch) / 60,
                        (epoch - trade.epoch) / period
                    )
                    chart.addPoint(if (diff < 0f) "BadSell" else "GoodSell", epoch, close[i], tooltip)
                    action = true
                    LOGGER.info(tradeStrLog)
                }

                if (!openTrades.isEmpty()) actionLock = 5
            }
        }

        // Main chart
        if (!action) chart.addPoint("Price", epoch, close[i])
        chart.addPoint("BB Upper", epoch, upBBand[i])
        chart.addPoint("BB Lower", epoch, lowBBand[i])
        //chart.addPoint("short MA", epoch, shortMA[i])
        //chart.addPoint("long MA", epoch, longMA[i])

        // RSI
        chart.addPointExtra("RSI", "rsi", epoch, rsi[i])
        chart.addPointExtra("RSI", "line30", epoch, 30.0)
        chart.addPointExtra("RSI", "line70", epoch, 70.0)

        // MACD
        //chart.addPointExtra("MACD", "macd", epoch, macd[i])
        //chart.addPointExtra("MACD", "signal", epoch, macdSignal[i])
        //chart.addPointExtra("MACD", "histogram", epoch, macdHistogram[i])

        // OBV
        chart.addPointExtra("OBV", "obv", epoch, obvIndicator[i])
    }
}