package com.lleps.tradexchange.strategy

import com.lleps.tradexchange.indicator.*
import com.lleps.tradexchange.server.Exchange
import com.lleps.tradexchange.util.get
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.nd4j.linalg.factory.Nd4j
import org.ta4j.core.Indicator
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.*
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.num.Num

class Strategy(
    private val output: OutputWriter,
    private val series: TimeSeries,
    private val period: Long,
    private val training: Boolean,
    private val exchange: Exchange,
    private val input: Map<String, String>) {

    // Strategy classes. Used to generalize operations

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
    /** Result of an operation tick. */
    class Operation(val type: OperationType,
                    val amount: Double,
                    val description: String? = null,
                    val buyPrice: Double = 0.0/* used only for Type.SELL to know the trade profit */,
                    val code: Int = 0)

    // Strategy config

    companion object {
        private class IndicatorType(
            val name: String,
            val defaultValue: String,
            val factory: (TimeSeries, Indicator<Num>, List<Int>) -> Indicator<Num>
        )

        private val INDICATOR_TYPES = listOf(
            IndicatorType("bb%", "20,2,300") { _, indicator, input ->
                NormalizationIndicator(PercentBIndicatorFixed(indicator, input[0], input[1].toDouble()), input[2])
            },
            IndicatorType("williamsR%", "14") { s, _, input ->
                MappingIndicator(WilliamsRIndicatorFixed(s, input[0])) { (it + 100.0) / 100.0 }
            },
            IndicatorType("cci", "20,300") { s, _, input ->
                NormalizationIndicator(CCIIndicator(s, input[0]), input[1])
            },
            IndicatorType("roc", "9,300") { _, indicator, input ->
                NormalizationIndicator(ROCIndicator(indicator, input[0]), input[1])
            },
            IndicatorType("rsi", "14") { _, indicator, input ->
                MappingIndicator(RSIIndicator(indicator, input[0])) { it / 100.0 }
            },
            IndicatorType("macd", "12,26,300") { _, indicator, input ->
                NormalizationIndicator(MACDIndicator(indicator, input[0], input[1]), input[2])
            },
            IndicatorType("obv-o", "24,300") { series, _, input ->
                NormalizationIndicator(OBVOscillatorIndicator(series, input[0]), input[1])
            }
        )

        val REQUIRED_INPUT = mapOf(
            "strategy.model" to "",
            "strategy.periodMultiplier" to "1",
            "strategy.balanceMultiplier" to "0.8",
            "strategy.openTradesCount" to "5",
            "strategy.tradeExpiry" to "300",
            "strategy.buyCooldown" to "5",
            "strategy.topLoss" to "-10",
            "strategy.sellBarrier1" to "1.0",
            "strategy.sellBarrier2" to "3.0",
            "strategy.mlBuyValue" to "0.7"
        ) + INDICATOR_TYPES.map { type -> "strategy.ind.${type.name}" to type.defaultValue }
    }

    // Parse input
    private val modelName = input.getValue("strategy.model")
    private val periodMultiplier = input.getValue("strategy.periodMultiplier").toFloat()
    private val balanceMultiplier = input.getValue("strategy.balanceMultiplier").toFloat()
    private val openTradesCount = input.getValue("strategy.openTradesCount").toInt()
    private val tradeExpiry = input.getValue("strategy.tradeExpiry").toInt() // give up if can't meet the margin
    private val buyCooldown = input.getValue("strategy.buyCooldown").toInt() // 4h. During cooldown won't buy anything
    private val topLoss = input.getValue("strategy.topLoss").toFloat()
    private val sellBarrier1 = input.getValue("strategy.sellBarrier1").toFloat()
    private val sellBarrier2 = input.getValue("strategy.sellBarrier2").toFloat()
    private val mlBuyValue = input.getValue("strategy.mlBuyValue").toFloat()

    // State classes
    private class OpenTrade(
            val buyPrice: Double,
            val amount: Double,
            val epoch: Long,
            val code: Int,
            var passed1Barrier: Boolean = false
    )

    // Strategy state. This should be persisted
    var tradeCount = 0
        private set
    var sellOnly = false
    private var buyNumber = 1
    private var openTrades = listOf<OpenTrade>()
    private var actionLock = 0
    private var predictionValue = 0.0
    private var tradeSum = 0.0

    // Indicators
    private val usedIndicators = mutableListOf<Pair<String, Indicator<Num>>>()
    private val close = ClosePriceIndicator(series)
    private fun parseIndicators(input: Map<String, String>) {
        for (type in INDICATOR_TYPES) {
            val key = "strategy.ind.${type.name}"
            val paramsArray = input.getValue(key).split(",").map { (it.toInt() * periodMultiplier).toInt() }
            if (paramsArray[0] == 0) continue // indicator ignored
            val indicator = type.factory(series, close, paramsArray)
            usedIndicators.add(type.name to indicator)
        }
    }

    // Model for ML predictions
    private lateinit var model: MultiLayerNetwork

    // Functions
    fun init() {
        if (!training) {
            val path = "data/models/[train]$modelName.h5"
            model = KerasModelImport.importKerasSequentialModelAndWeights(path);
        }
        parseIndicators(this.input)
    }

    private fun shouldOpen(i: Int, epoch: Long): Boolean {
        val timesteps = 25
        val timestepsArray = Array(usedIndicators.size) { indicatorIndex ->
            DoubleArray(timesteps) { index ->
                usedIndicators[indicatorIndex].second[i - (timesteps - index - 1)]
            }
        }
        val data = arrayOf(timestepsArray)
        predictionValue = model.output(Nd4j.create(data)).getDouble(0)
        return predictionValue > mlBuyValue
    }

    private fun shouldClose(i: Int, epoch: Long, trade: OpenTrade): Boolean {
        if (epoch - trade.epoch > tradeExpiry * period) return true

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
    }


    fun onDrawChart(chart: ChartWriter, epoch: Long, i: Int) {
        var drawCount = 0
        if (!training) {
            //chart.priceIndicator("bbUp", epoch, bbUp[i])
            //chart.priceIndicator("bbDown", epoch, bbDown[i])
            //chart.priceIndicator("bbMa", epoch, bbMa[i])
            chart.extraIndicator("ml", "ml", epoch, predictionValue)
            chart.extraIndicator("ml", "buyvalue", epoch, mlBuyValue.toDouble())
            chart.extraIndicator("$", "$", epoch, tradeSum)
            drawCount += 2
        }
        for (indicator in usedIndicators) {
            chart.extraIndicator(indicator.first, indicator.first, epoch, indicator.second[i])
            drawCount++
            if (!training && drawCount >= 5) break
        }
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
                tradeSum += diff*trade.amount
                operations = operations + Operation(OperationType.SELL, trade.amount, tooltip, trade.buyPrice, trade.code)
                openTrades = openTrades - trade
                tradeCount++
            }
        }
        return operations
    }
}