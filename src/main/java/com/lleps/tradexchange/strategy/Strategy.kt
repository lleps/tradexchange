package com.lleps.tradexchange.strategy

import com.lleps.tradexchange.ImportKerasModel
import com.lleps.tradexchange.indicator.CompositeIndicator
import com.lleps.tradexchange.indicator.NormalizationIndicator
import com.lleps.tradexchange.indicator.OBVOscillatorIndicator
import com.lleps.tradexchange.server.Exchange
import com.lleps.tradexchange.util.get
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.nd4j.linalg.factory.Nd4j
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.*
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator

class Strategy(
    private val output: OutputWriter,
    private val series: TimeSeries,
    private val period: Long,
    private val training: Boolean,
    private val exchange: Exchange,
    private val input: Map<String, String>) {

    /** Used for client output */
    interface OutputWriter {
        fun write(string: String)
    }

    /** A strategy can output some chart data */
    interface ChartWriter {
        fun priceIndicator(name: String, epoch: Long, value: Double)
        fun extraIndicator(chart: String, name: String, epoch: Long, value: Double)
    }

    enum class OperationType { BUY, SELL }
    class Operation(val type: OperationType,
                    val amount: Double,
                    val description: String? = null,
                    val buyPrice: Double = 0.0/* used only for Type.SELL to know the trade profit */,
                    val code: Int = 0)


    // Strategy config
    companion object {
        val requiredInput = mapOf(
            "strategy.model" to "",
            "strategy.balanceMultiplier" to "0.8",
            "strategy.openTradesCount" to "5",
            "strategy.tradeExpiry" to "300",
            "strategy.buyCooldown" to "5",
            "strategy.topLoss" to "-10",
            "strategy.sellBarrier1" to "1.0",
            "strategy.sellBarrier2" to "3.0",
            "strategy.rsiBuy" to "30",
            "strategy.rsiBuy" to "30",
            "strategy.rsiPeriod" to "14",
            "strategy.obvBuy" to "0.25",
            "strategy.obvNormalPeriod" to "120",
            "strategy.mlBuyValue" to "0.7"
        )
    }

    private val modelName = input.getValue("strategy.model")
    private val tradeExpiry = input.getValue("strategy.tradeExpiry").toInt() // give up if can't meet the margin
    private val buyCooldown = input.getValue("strategy.buyCooldown").toInt() // 4h. During cooldown won't buy anything
    private val topLoss = input.getValue("strategy.topLoss").toFloat()
    private val sellBarrier1 = input.getValue("strategy.sellBarrier1").toFloat()
    private val sellBarrier2 = input.getValue("strategy.sellBarrier2").toFloat()
    private val openTradesCount = input.getValue("strategy.openTradesCount").toInt()
    private val rsiBuy = input.getValue("strategy.rsiBuy").toFloat()
    private val obvBuy = input.getValue("strategy.obvBuy").toFloat()
    private val obvNormalPeriod = input.getValue("strategy.obvNormalPeriod").toInt()
    private val balanceMultiplier = input.getValue("strategy.balanceMultiplier").toFloat()

    var tradeCount = 0
        private set

    var sellOnly = false

    private var buyNumber = 1

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
    private val macd = NormalizationIndicator(MACDIndicator(close, 12, 26), 130)
    private val macdSignal = EMAIndicator(macd, 9)
    private val macdHistogram = CompositeIndicator(macd, macdSignal) { macd, macdSignal -> macd - macdSignal }
    private val normalMacd = NormalizationIndicator(macd, 30)
    private val longMA = EMAIndicator(close, 24)
    private val shortMA = EMAIndicator(close, 12)
    private val rsi = RSIIndicator(close, input.getValue("strategy.rsiPeriod").toInt())
    private val avg14 = EMAIndicator(close, 12)
    private val sd14 = StandardDeviationIndicator(close, 12)
    private val middleBBand = BollingerBandsMiddleIndicator(avg14)
    private val lowBBand = BollingerBandsLowerIndicator(middleBBand, sd14)
    private val upBBand = BollingerBandsUpperIndicator(middleBBand, sd14)
    private val volatiltyIndicatorBB = CompositeIndicator(upBBand, lowBBand) { up, low -> up - low }
    private val normalMacd2 = NormalizationIndicator(macd, 200)
    private val obvIndicator = OBVOscillatorIndicator(series, 38)
    private val obvIndicatorNormal = NormalizationIndicator(obvIndicator, obvNormalPeriod)
    private val mlBuyValue = input.getValue("strategy.mlBuyValue").toFloat()
    private val stochasticIndicatorK = StochasticOscillatorKIndicator(series, input.getValue("strategy.rsiPeriod").toInt())
    private val stochasticIndicatorD = StochasticOscillatorDIndicator(stochasticIndicatorK)
    private val stochasticRSIIndicator = StochasticRSIIndicator(series, input.getValue("strategy.rsiPeriod").toInt())
    private lateinit var model: MultiLayerNetwork

    fun init() {
        val path = "data/models/[train]$modelName.h5"
        model = KerasModelImport.importKerasSequentialModelAndWeights(path);
    }

    private fun shouldOpen(i: Int, epoch: Long): Boolean {
        val macd = macd[i]
        val data = arrayOf(doubleArrayOf(rsi[i] / 100.0, macd))
        val prediction = model.output(Nd4j.create(data)).getDouble(0)
        return prediction > mlBuyValue
    }

    private fun shouldClose(i: Int, epoch: Long, trade: OpenTrade): Boolean {
        if (epoch - trade.epoch > tradeExpiry * period) return true // trade expire at this point

        val diff = close[i] - trade.buyPrice
        val pct = diff * 100.0 / close[i]
        if (pct < topLoss) {
            return true // panic - sell.
        }

        if (pct > sellBarrier1) {
            trade.passed1Barrier = true
            if (pct > sellBarrier2) {
                return true
            }
        } else if (pct < sellBarrier1 && trade.passed1Barrier) {
            return true
        }

        return false
        // V1: return close[i] > trade.buyPrice + marginToSell || trade.buyPrice - close[i] > -topLoss
    }

    fun onDrawChart(chart: ChartWriter, epoch: Long, i: Int) {
        //chart.priceIndicator("BB Lower", epoch, lowBBand[i])
        //chart.priceIndicator("short MA", epoch, shortMA[i])
        //chart.priceIndicator("long MA", epoch, longMA[i])

        // RSI
        chart.extraIndicator("RSI", "rsi", epoch, rsi[i])
        //chart.extraIndicator("RSI", "line30", epoch, 30.0)
        //chart.extraIndicator("RSI", "line70", epoch, 70.0)
        //chart.extraIndicator("stochastic", "k", epoch, stochasticIndicatorK[i])
        //chart.extraIndicator("stochastic", "d", epoch, stochasticIndicatorD[i])
        //chart.extraIndicator("stochastic", "rsi", epoch, stochasticRSIIndicator[i])

        // MACD
        chart.extraIndicator("MACD", "macd", epoch, macd[i])
        //chart.extraIndicator("MACD", "signal", epoch, macdSignal[i])
        //chart.extraIndicator("MACD", "histogram", epoch, macdHistogram[i])

        // OBV
        //chart.extraIndicator("OBV", "obv", epoch, obvIndicatorNormal[i])
    }

    fun onTick(i: Int): List<Operation> {
        val epoch = series.getBar(i).endTime.toEpochSecond()
        var boughtSomething = false
        var operations = emptyList<Operation>()

        // Try to buy
        if (!sellOnly && openTrades.size < openTradesCount) { // BUY
            if (actionLock > 0) {
                actionLock--
            } else {
                if (shouldOpen(i, epoch)) {
                    val amountOfMoney = (exchange.moneyBalance) / (openTradesCount - openTrades.size).toDouble() * balanceMultiplier
                    val amountOfCoins = amountOfMoney / close[i]
                    val buyPrice = exchange.buy(amountOfCoins)
                    val trade = OpenTrade(buyPrice, amountOfCoins, epoch, buyNumber++)
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
                val sellPrice = exchange.sell(trade.amount * 0.9999)
                val diff = sellPrice - trade.buyPrice
                val tradeStrLog =
                    "Trade %.03f'c    buy $%.03f    sell $%.03f    diff $%.03f    won $%.03f"
                        .format(trade.amount, trade.buyPrice, sellPrice, diff, diff*trade.amount)
                output.write(tradeStrLog)
                val tooltip =
                    ("Close #%d: won $%.03f (diff $%.03f)\n" +
                    "Buy $%.03f   Sell $%.03f\n" +
                    "Time %d min (%d ticks)").format(
                        trade.code,
                        diff*trade.amount,
                        diff,
                        trade.buyPrice,
                        sellPrice,
                        (epoch - trade.epoch) / 60,
                        (epoch - trade.epoch) / period
                    )
                operations = operations + Operation(OperationType.SELL, trade.amount, tooltip, trade.buyPrice, trade.code)
                openTrades = openTrades - trade
                tradeCount++
            }
        }
        return operations
    }
}