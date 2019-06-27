package com.lleps.tradexchange.strategy

import com.lleps.tradexchange.Candle
import com.lleps.tradexchange.OperationType
import com.lleps.tradexchange.server.Exchange
import com.lleps.tradexchange.util.get
import com.lleps.tradexchange.util.markAs
import org.ta4j.core.TimeSeries
import org.ta4j.core.indicators.EMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.num.DoubleNum

class Strategy(
    private val output: OutputWriter,
    private val series: TimeSeries,
    private val period: Long,
    private val exchange: Exchange,
    input: Map<String, String>) {

    interface OutputWriter {
        fun write(string: String)
    }

    interface ChartWriter {
        fun priceIndicator(name: String, epoch: Long, value: Double)
        fun extraIndicator(chart: String, name: String, epoch: Long, value: Double)
    }

    enum class OperationType { BUY, SELL }
    class Operation(val type: OperationType,
                    val amount: Double,
                    val description: String? = null,
                    val buyPrice: Double = 0.0/* used only for Type.SELL to know the trade profit */,
                    val code: Int = 0,
                    val chart: ChartWriterImpl)

    companion object {
        val REQUIRED_INPUT = mapOf(
            "strategy.model" to "",
            "strategy.buyOnly" to "0",
            "strategy.balanceMultiplier" to "0.4",
            "strategy.openTradesCount" to "5",
            "strategy.buyCooldown" to "5",
            "strategy.mlBuyTrigger" to "over:0.5",
            "strategy.emaPeriods" to "12,26",
            "strategy.close.atrPeriod" to "24",
            "strategy.close.tradeExpiry" to "300",
            "strategy.close.topLoss" to "10",
            "strategy.close.sellBarrier1" to "10",
            "strategy.close.BBPeriod" to "20"
        )
    }

    // Parse input
    private val modelName = input.getValue("strategy.model")
    private val buyOnly = input.getValue("strategy.buyOnly").toInt() != 0
    private val balanceMultiplier = input.getValue("strategy.balanceMultiplier").toFloat()
    private val openTradesCount = input.getValue("strategy.openTradesCount").toInt()
    private val buyCooldown = input.getValue("strategy.buyCooldown").toInt() // 4h. During cooldown won't buy anything
    private val mlBuyTrigger = input.getValue("strategy.mlBuyTrigger")
    private val emaPeriods = input.getValue("strategy.emaPeriods").split(",").map { it.toInt() }
    private val tradeExpiry = input.getValue("strategy.close.tradeExpiry").toInt() // give up if can't meet the margin
    private val topLoss = input.getValue("strategy.close.topLoss").toFloat()
    private val sellBarrier1 = input.getValue("strategy.close.sellBarrier1").toFloat()
    private val closeBBPeriod = input.getValue("strategy.close.BBPeriod").toInt()

    // Strategy state. This should be persisted
    private class OpenTrade(
        val buyPrice: Double,
        val amount: Double,
        val epoch: Long,
        val code: Int,
        val closeStrategy: CloseStrategy,
        val chartWriter: ChartWriterImpl
    )
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

    // Transient state
    private val emaShort = EMAIndicator(ClosePriceIndicator(series), emaPeriods[0])
    private val emaLong = EMAIndicator(ClosePriceIndicator(series), emaPeriods[1])
    private val close = ClosePriceIndicator(series)
    private lateinit var predictionModel: PredictionModel
    private lateinit var closeConfig: CloseStrategy.Config

    // Functions
    fun init() {
        predictionModel = PredictionModel.createFromFile(series, "[train]$modelName")
        predictionModel.loadPredictionsModel(modelName)
        closeConfig = CloseStrategy.Config(
            topBarrierMultiplier = sellBarrier1.toDouble(),
            bottomBarrierMultiplier = topLoss.toDouble(),
            bbPeriod = closeBBPeriod,
            shortEmaPeriod = 3,
            expiry = tradeExpiry
        )
        CloseStrategy.inited = false // cache trick. to rebuild the indicators on the new timeseries.
        if (buyOnly) output.write("Using buy only mode!")
    }

    private fun calculatePredictions(i: Int) {
        val (newBuyPrediction, newSellPrediction) = predictionModel.calculateBuySellPredictions(i)
        buyPredictionLastLast = buyPredictionLast
        buyPredictionLast = buyPrediction
        sellPredictionLastLast = sellPredictionLast
        sellPredictionLast = sellPrediction
        buyPrediction = newBuyPrediction
        sellPrediction = newSellPrediction
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
        chart.priceIndicator("emaShort", epoch, emaShort[i])
        chart.priceIndicator("emaLong", epoch, emaLong[i])
        chart.extraIndicator("ml", "buy", epoch, buyPrediction)
        chart.extraIndicator("ml", "sell", epoch, sellPrediction)
        chart.extraIndicator("ml", "buyvalue", epoch, mlBuyTrigger.split(":")[1].toDouble())
        chart.extraIndicator("$", "profit", epoch, tradeSum)
        predictionModel.drawFeatures(com.lleps.tradexchange.OperationType.BUY, i, epoch, chart, limit = 3)
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
                    // TODO: the strategy doesn't update the buypressure indicator. check why
                    bar.markAs(1/*buy*/) // to udate pressure indicators
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
            var sold = false
            for (trade in openTrades) {
                trade.chartWriter.candles.add(candle)
                var shouldClose = trade.closeStrategy.doTick(i, sellPrediction, trade.chartWriter)
                if (checkTrigger(mlBuyTrigger, sellPredictionLastLast, sellPredictionLast, sellPrediction)) {
                    shouldClose = "prediction: %.4f".format(sellPrediction)
                }
                if (shouldClose == null) continue
                if (sold) continue
                sold = true
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
                    "Time %d min (%d tiualmcks)").format(
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