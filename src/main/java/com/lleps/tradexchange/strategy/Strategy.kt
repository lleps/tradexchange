package com.lleps.tradexchange.strategy

import com.lleps.tradexchange.Candle
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
                    val code: Int = 0,
                    val chart: ChartWriterImpl)

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
            IndicatorType("obvo", "24,300") { series, _, input ->
                NormalizationIndicator(OBVOscillatorIndicator(series, input[0]), input[1])
            }
        )

        val REQUIRED_INPUT = mapOf(
            "strategy.model" to "",
            "strategy.buyOnly" to "0",
            "strategy.periodMultiplier" to "1",
            "strategy.balanceMultiplier" to "0.8",
            "strategy.openTradesCount" to "5",
            "strategy.buyCooldown" to "5",
            "strategy.mlBuyTrigger" to "over:0.5",
            "strategy.emaPeriods" to "12,26",
            "strategy.close.atrPeriod" to "24",
            "strategy.close.tradeExpiry" to "300",
            "strategy.close.topLoss" to "-10",
            "strategy.close.sellBarrier1" to "1.0",
            "strategy.close.sellBarrier2" to "3.0",
            "strategy.close.BBPeriod" to "20"
        ) + INDICATOR_TYPES.map { type -> "indicator.${type.name}" to type.defaultValue }
    }

    // Parse open input
    private val modelName = input.getValue("strategy.model")
    private val buyOnly = input.getValue("strategy.buyOnly").toInt() != 0
    private val periodMultiplier = input.getValue("strategy.periodMultiplier").toFloat()
    private val balanceMultiplier = input.getValue("strategy.balanceMultiplier").toFloat()
    private val openTradesCount = input.getValue("strategy.openTradesCount").toInt()
    private val buyCooldown = input.getValue("strategy.buyCooldown").toInt() // 4h. During cooldown won't buy anything
    private val mlBuyTrigger = input.getValue("strategy.mlBuyTrigger")
    private val emaPeriods = input.getValue("strategy.emaPeriods").split(",").map { it.toInt() }
    // Parse close input
    private val atrPeriod = input.getValue("strategy.close.atrPeriod").toInt()
    private val tradeExpiry = input.getValue("strategy.close.tradeExpiry").toInt() // give up if can't meet the margin
    private val topLoss = input.getValue("strategy.close.topLoss").toFloat()
    private val sellBarrier1 = input.getValue("strategy.close.sellBarrier1").toFloat()
    private val sellBarrier2 = input.getValue("strategy.close.sellBarrier2").toFloat()
    private val closeBBPeriod = input.getValue("strategy.close.BBPeriod").toInt()
    // Indicators
    private val atr = EMAIndicator(ATRIndicator(series, atrPeriod), atrPeriod)
    private val emaShort = EMAIndicator(ClosePriceIndicator(series), emaPeriods[0])
    private val emaLong = EMAIndicator(ClosePriceIndicator(series), emaPeriods[1])

    // State for an open position
    private class OpenTrade(
            val buyPrice: Double,
            val amount: Double,
            val epoch: Long,
            val code: Int,
            val closeStrategy: CloseStrategy,
            val chartWriter: ChartWriterImpl
    )

    // Strategy state. This should be persisted
    var tradeCount = 0
        private set
    var sellOnly = false
    private var buyNumber = 1
    private var openTrades = listOf<OpenTrade>()
    private var buyLock = 0
    private var sellLock = 0
    private var boughtInCrest = false
    private var buyPredictionLastLast = 0.0
    private var buyPredictionLast = 0.0
    private var buyPrediction = 0.0
    private var soldInCrest = false
    private var sellPredictionLastLast = 0.0
    private var sellPredictionLast = 0.0
    private var sellPrediction = 0.0
    private var tradeSum = 0.0

    // Indicators
    private val usedIndicators = mutableListOf<Pair<String, Indicator<Num>>>()
    private val close = ClosePriceIndicator(series)
    private fun parseIndicators(input: Map<String, String>) {
        for (type in INDICATOR_TYPES) {
            val key = "indicator.${type.name}"
            val paramsArray = input.getValue(key).split(",").map { (it.toInt() * periodMultiplier).toInt() }
            if (paramsArray[0] == 0) continue // indicator ignored
            val indicator = type.factory(series, close, paramsArray)
            usedIndicators.add(type.name to indicator)
        }
    }

    // Model for ML predictions
    private lateinit var buyModel: MultiLayerNetwork
    private lateinit var sellModel: MultiLayerNetwork
    private lateinit var closeConfig: CloseStrategy.Config

    // Functions
    fun init() {
        if (!training) {
            val buyPath = "data/models/[train]$modelName-open.h5"
            val sellPath = "data/models/[train]$modelName-close.h5"
            buyModel = KerasModelImport.importKerasSequentialModelAndWeights(buyPath)
            sellModel = KerasModelImport.importKerasSequentialModelAndWeights(sellPath)
            closeConfig = CloseStrategy.Config(
                topBarrierInitial = sellBarrier1.toDouble(),
                bottomBarrierInitial = topLoss.toDouble(),
                avgPeriod = closeBBPeriod,
                sdPeriod = closeBBPeriod,
                shortEmaPeriod = 3,
                expiry = tradeExpiry
            )
            CloseStrategy.inited = false // cache trick. to rebuild the indicators on the new timeseries.
            if (buyOnly) output.write("Using buy only mode!")
        }
        parseIndicators(this.input)
    }

    private fun calculatePredictions(i: Int) {
        val timesteps = 7
        val timestepsArray = Array(usedIndicators.size) { indicatorIndex ->
            DoubleArray(timesteps) { index ->
                usedIndicators[indicatorIndex].second[i - (timesteps - index - 1)]
            }
        }
        val data = arrayOf(timestepsArray)
        buyPredictionLastLast = buyPredictionLast
        buyPredictionLast = buyPrediction
        sellPredictionLastLast = sellPredictionLast
        sellPredictionLast = sellPrediction
        buyPrediction = buyModel.output(Nd4j.create(data)).getDouble(0)
        sellPrediction = sellModel.output(Nd4j.create(data)).getDouble(0)
    }

    private fun checkCrossOver(barrier: Float, last: Double, current: Double): Boolean {
        return last < barrier && current > barrier
    }

    private fun checkCrossUnder(barrier: Float, last: Double, current: Double): Boolean {
        return last > barrier && current < barrier
    }

    private fun checkAfterCrest(barrier: Float, lastLast: Double, last: Double, current: Double): Boolean {
        return last > barrier && last > lastLast && last > current
    }

    /** To Generalize ML triggers (under:x, over:x, peak:x) */
    private fun checkTrigger(trigger: String, lastLast: Double, last: Double, current: Double): Boolean {
        val parts = trigger.split(":")
        val barrier = parts[1].toFloat()
        return when (parts[0]) {
            "over" -> checkCrossOver(barrier, last, current)
            "under" -> checkCrossUnder(barrier, last, current)
            "peak" -> checkAfterCrest(barrier, lastLast, last, current)
            else -> error("unsupported trigger: ${parts[0]}")
        }
    }

    private fun shouldOpen(i: Int, epoch: Long): String? {
        // 3 types of close:
        if (checkTrigger(mlBuyTrigger, buyPredictionLastLast, buyPredictionLast, buyPrediction)) {
            if (!boughtInCrest) {
                boughtInCrest = true
                return "prediction: %.4f".format(buyPrediction)
            }
        }
        if (buyPrediction < mlBuyTrigger.split(":")[1].toFloat()) boughtInCrest = false
        return null
    }

    fun onDrawChart(chart: ChartWriter, epoch: Long, i: Int) {
        var drawCount = 0
        if (!training) {
            chart.priceIndicator("emaShort", epoch, emaShort[i])
            chart.priceIndicator("emaLong", epoch, emaLong[i])
            chart.extraIndicator("ml", "buy", epoch, buyPrediction)
            chart.extraIndicator("ml", "sell", epoch, sellPrediction)
            chart.extraIndicator("ml", "buyvalue", epoch, mlBuyTrigger.split(":")[1].toDouble())
            chart.extraIndicator("$", "profit", epoch, tradeSum)
            chart.extraIndicator("atr", "atr", epoch, atr[i])
            drawCount += 3
        }
        for (indicator in usedIndicators) {
            chart.extraIndicator(indicator.first, indicator.first, epoch, indicator.second[i])
            drawCount++
            if (!training && drawCount >= 5) break
        }
    }

    // Depending on volatility, should be close/open barriers
    private fun rebuildCloseConfigByATR(i: Int) {
        val atrPct = atr[i] / close[i] * 100.0
        closeConfig = CloseStrategy.Config(
            topBarrierInitial = atrPct * sellBarrier1.toDouble(),
            bottomBarrierInitial = atrPct * topLoss.toDouble(),
            avgPeriod = closeBBPeriod,
            sdPeriod = closeBBPeriod,
            shortEmaPeriod = 3,
            expiry = tradeExpiry
        )
    }

    fun onTick(i: Int): List<Operation> {
        val epoch = series.getBar(i).endTime.toEpochSecond()
        var operations = emptyList<Operation>()
        val bar = series.getBar(i)
        val candle = Candle(
            bar.endTime.toEpochSecond(),
            bar.openPrice.doubleValue(),
            bar.closePrice.doubleValue(),
            bar.maxPrice.doubleValue(),
            bar.minPrice.doubleValue())

        calculatePredictions(i)
        rebuildCloseConfigByATR(i)

        // Try to buy
        if (!sellOnly && (buyOnly || openTrades.size < openTradesCount)) { // BUY
            if (buyLock > 0) {
                buyLock--
            } else {
                val open = shouldOpen(i, epoch)
                if (open != null) {
                    var amountOfMoney = (exchange.moneyBalance) / (openTradesCount - openTrades.size).toDouble() * balanceMultiplier
                    if (buyOnly) {
                        output.write("Open buy position (buyOnly, always at 1.5usd)")
                        amountOfMoney = 1.5
                    }
                    val amountOfCoins = amountOfMoney / close[i]
                    val buyPrice = exchange.buy(amountOfCoins)
                    val chart = ChartWriterImpl()
                    chart.candles.add(candle)
                    val closeStrategy = CloseStrategy(closeConfig, series, i, buyPrice)
                    val trade = OpenTrade(buyPrice, amountOfCoins, epoch, buyNumber++, closeStrategy, chart)
                    openTrades = openTrades + trade
                    operations = operations + Operation(
                        OperationType.BUY,
                        trade.amount,
                        "Open #%d at $%.03f\n________\n$open".format(trade.code, trade.buyPrice),
                        0.0,
                        trade.code,
                        trade.chartWriter)
                    buyLock = buyCooldown
                }
            }
        }

        if (sellLock > 0) sellLock--

        // Try to sell
        if (!buyOnly) {
            for (trade in openTrades) {
                trade.chartWriter.candles.add(candle)
                val shouldClose = trade.closeStrategy.doTick(i, sellPrediction, trade.chartWriter) ?: continue
                val sellPrice = exchange.sell(trade.amount * 0.9999)
                val diff = sellPrice - trade.buyPrice
                val pct = diff * 100.0 / trade.buyPrice
                sellLock = buyCooldown
                val tradeStrLog =
                    "Trade %.03f'c    buy $%.03f    sell $%.03f    diff $%.03f    won $%.03f"
                        .format(trade.amount, trade.buyPrice, sellPrice, diff, diff*trade.amount)
                output.write(tradeStrLog)
                val tooltip =
                    ("Close #%d at %.1f%s (earnings $%.03f)\n" +
                    "Buy $%.03f   Sell $%.03f\n" +
                    "Time %d min (%d ticks)").format(
                        trade.code,
                        pct,
                        "%",
                        diff*trade.amount,
                        trade.buyPrice,
                        sellPrice,
                        (epoch - trade.epoch) / 60,
                        (epoch - trade.epoch) / period
                    ) + "\n________\n$shouldClose"
                tradeSum += diff*trade.amount
                operations = operations + Operation(OperationType.SELL, trade.amount, tooltip, trade.buyPrice, trade.code, trade.chartWriter)
                openTrades = openTrades - trade
                tradeCount++
            }
        }
        return operations
    }
}