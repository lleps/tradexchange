package com.lleps.tradexchange.server

import com.lleps.tradexchange.indicator.CompositeIndicator
import com.lleps.tradexchange.indicator.NormalizationIndicator
import com.lleps.tradexchange.util.get
import org.slf4j.LoggerFactory
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
import java.util.*

class Strategy(
    private val series: TimeSeries,
    private val period: Long,
    private val backtest: Boolean,
    private val epochStopBuy: Long,
    private val exchange: Exchange,
    private val input: Map<String, String>) {

    /** A strategy can output some chart data */
    interface ChartWriter {
        fun priceIndicator(name: String, epoch: Long, value: Double)
        fun extraIndicator(chart: String, name: String, epoch: Long, value: Double)
    }

    enum class OperationType { BUY, SELL }
    class Operation(val type: OperationType,
                    val amount: Double,
                    val description: String? = null,
                    val buyPrice: Double = 0.0/* used only for Type.SELL to know the trade profit */)

    var tradeCount = 0
        private set

    // State
    private class OpenTrade(
            val buyPrice: Double,
            val amount: Double,
            val epoch: Long,
            val code: Int,
            var passed1Barrier: Boolean = false
    )
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
    private val avg14 = EMAIndicator(close, 12)
    private val sd14 = StandardDeviationIndicator(close, 12)
    private val middleBBand = BollingerBandsMiddleIndicator(avg14)
    private val lowBBand = BollingerBandsLowerIndicator(middleBBand, sd14)
    private val upBBand = BollingerBandsUpperIndicator(middleBBand, sd14)
    private val volatiltyIndicatorBB = CompositeIndicator(upBBand, lowBBand) { up, low -> up - low }
    private val normalMacd2 = NormalizationIndicator(macd, 200)
    private val obvIndicator = OnBalanceVolumeIndicator(series)
    private val obvIndicatorNormal = NormalizationIndicator(obvIndicator, 80)

    private val openTradesCount = input.getValue("openTradesCount").toInt()

    // Strategy config
    companion object {
        private val LOGGER = LoggerFactory.getLogger(Strategy::class.java)
        val requiredInput = mapOf(
            "openTradesCount" to 5,
            "tradeExpiry" to 12*5,
            "marginToSell" to 1,
            "buyCooldown" to 5,
            "topLoss" to -10,
            "sellBarrier1" to 1.0,
            "sellBarrier2" to 3.0
        )
    }
    private val tradeExpiry = input.getValue("tradeExpiry").toInt() // give up if can't meet the margin
    private val marginToSell = input.getValue("marginToSell").toFloat()
    private val buyCooldown = input.getValue("buyCooldown").toInt() // 4h. During cooldown won't buy anything
    private val topLoss = input.getValue("topLoss").toFloat()
    private val sellBarrier1 = input.getValue("sellBarrier1").toFloat()
    private val sellBarrier2 = input.getValue("sellBarrier2").toFloat()

    private fun shouldOpen(i: Int, epoch: Long): Boolean {
        return macd[i] < 0f //&& rsi[i] < 60f
    }

    private fun shouldClose(i: Int, epoch: Long, trade: OpenTrade): Boolean {
        if (epoch - trade.epoch > tradeExpiry * period) return true // trade expire at this point

        val diff = close[i] - trade.buyPrice
        if (diff < topLoss) {
            return true // panic - sell.
        }

        if (diff > sellBarrier1) {
            trade.passed1Barrier = true
            if (diff > sellBarrier2) {
                return true
            }
        } else if (diff < sellBarrier1 && trade.passed1Barrier) {
            return true
        }

        return false
        // V1: return close[i] > trade.buyPrice + marginToSell || trade.buyPrice - close[i] > -topLoss
    }

    fun onDrawChart(chart: ChartWriter, epoch: Long, i: Int) {
        //chart.priceIndicator("BB Lower", epoch, lowBBand[i])
        chart.priceIndicator("short MA", epoch, shortMA[i])
        //chart.priceIndicator("long MA", epoch, longMA[i])

        // RSI
        chart.extraIndicator("RSI", "rsi", epoch, rsi[i])
        //chart.extraIndicator("RSI", "line30", epoch, 30.0)
        //chart.extraIndicator("RSI", "line70", epoch, 70.0)

        // MACD
        chart.extraIndicator("MACD", "macd", epoch, macd[i])
        //chart.extraIndicator("MACD", "signal", epoch, macdSignal[i])
        //chart.extraIndicator("MACD", "histogram", epoch, macdHistogram[i])

        // OBV
        //chart.extraIndicator("OBV", "obv", epoch, obvIndicator[i])
    }

    fun onTick(i: Int): List<Operation> {
        val epoch = series.getTick(i).endTime.toEpochSecond()
        var boughtSomething = false
        var operations = emptyList<Operation>()

        // Try to buy
        if (openTrades.size < openTradesCount && (!backtest || epoch < epochStopBuy)) { // BUY
            if (actionLock > 0) {
                actionLock--
            } else {
                if (shouldOpen(i, epoch)) {
                    val amountOfMoney = (exchange.moneyBalance) / (openTradesCount - openTrades.size).toDouble()
                    val amountOfCoins = amountOfMoney / close[i]
                    val trade = OpenTrade(close[i], amountOfCoins, epoch, Random().nextInt(2000))
                    exchange.buy(amountOfCoins, close[i]) // TODO: the runner should do this
                    boughtSomething = true
                    openTrades = openTrades + trade
                    operations = operations + Operation(
                        OperationType.BUY,
                        trade.amount,
                        "Open #%d at $%.03f".format(trade.code, trade.buyPrice))
                    actionLock = buyCooldown
                }
            }
        }

        // Try to sell
        if (!boughtSomething) {
            val closedTrades = openTrades.filter { shouldClose(i, epoch, it) }
            for (trade in closedTrades) {
                exchange.sell(trade.amount * 0.99999, close[i]) // TODO: the runner should do this
                val diff = close[i] - trade.buyPrice
                val tradeStrLog =
                    "Trade %.03f'c    buy $%.03f    sell $%.03f    diff $%.03f    won $%.03f"
                        .format(trade.amount, trade.buyPrice, close[i], diff, diff*trade.amount)
                LOGGER.info(tradeStrLog)
                val tooltip =
                    ("Close #%d: won $%.03f (diff $%.03f)\n" +
                    "Buy $%.03f   Sell $%.03f\n" +
                    "Time %d min (%d ticks)").format(
                    trade.code,
                    diff*trade.amount,
                    diff,
                    trade.buyPrice,
                    close[i],
                    (epoch - trade.epoch) / 60,
                    (epoch - trade.epoch) / period
                )
                operations = operations + Operation(OperationType.SELL, trade.amount, tooltip, trade.buyPrice)
                openTrades = openTrades - trade
                tradeCount++
            }
        }
        return operations
    }
}