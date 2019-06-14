package com.lleps.tradexchange.server

import com.lleps.tradexchange.*
import com.lleps.tradexchange.strategy.ChartWriterImpl
import com.lleps.tradexchange.strategy.Strategy
import com.lleps.tradexchange.util.getTicksFromPoloniex
import org.ta4j.core.BaseTimeSeries
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.concurrent.thread

class LiveInstanceController(
    val instance: String,
    val state: InstanceState,
    val chartData: InstanceChartData,
    val out: Strategy.OutputWriter
) : InstanceController {

    override fun getRequiredInput(): Map<String, String> {
        return mapOf(
            "period" to "300",
            "pair" to "USDT_ETH",
            "warmupDays" to "5",
            "poloniex.apiKey" to "",
            "poloniex.apiSecret" to "") +
            Strategy.REQUIRED_INPUT
    }

    override fun onLoaded() {
        state.live = false
        state.action1 = "Start"
        state.action2 = ""
        // should init the thread if live mode on
    }

    override fun onCreated() {
    }

    override fun onDeleted() {
        // should destroy the thread
    }

    override fun onExecute(input: Map<String, String>, button: Int) {
        if (!state.live) {
            state.live = true
            state.action1 = "Stop"
            out.write("Starting live trading thread...")
            thread(start = true, isDaemon = false) {
                try {
                    liveTradingInfiniteLoop(input)
                } catch (e: Exception) {
                    state.live = false
                    out.write("Error in live trading thread.")
                    out.write("Live mode stopped.")
                    out.write(StringWriter().let { sw -> e.printStackTrace(PrintWriter(sw)) }.toString())
                }
            }
        } else {
            state.live = false
            state.action1 = "Start"
            out.write("Stop signal sent...")
        }
        state.stateVersion++
    }

    override fun onToggleCandle(candleEpoch: Long, toggle: Boolean) {
    }

    private fun liveTradingInfiniteLoop(input: Map<String, String>) {
        // Parse input
        val pair = input.getValue("pair")
        val period = input.getValue("period").toInt()
        val warmupDays = input.getValue("warmupDays").toInt()
        val apiKey = input.getValue("poloniex.apiKey")
        val apiSecret = input.getValue("poloniex.apiSecret")

        // Fetch past ticks, create exchange and strategy instances
        out.write("Fetch chart data from poloniex to initialize indicators...")
        val ticks = getTicksFromPoloniex(pair, period, daysBack = warmupDays+2)
        val series = BaseTimeSeries(ticks)
        val exchange = PoloniexLiveExchange(
            pair = pair,
            apiKey = apiKey,
            apiSecret = apiSecret)
        val strategy = Strategy(
            output = out,
            series = series,
            training = false,
            exchange = exchange,
            period = period.toLong(),
            input = input)
        out.write("Initialize model...")
        strategy.init()

        // init chart data. Should paint some past data to get better feedback
        // also plot past trades reported by the exchange.
        val chartWriter = ChartWriterImpl()
        val chartOperations = mutableListOf<Operation>()
        val candles = mutableListOf<Candle>()

        out.write("Plot ticks from 100 until ${ticks.size} for reference.")
        for (i in 100 until ticks.size) {
            val tick = series.getBar(i)
            val epoch = tick.endTime.toEpochSecond()
            strategy.onDrawChart(chartWriter, epoch, i)
            candles.add(Candle(
                epoch,
                tick.openPrice.doubleValue(),
                tick.closePrice.doubleValue(),
                tick.maxPrice.doubleValue(),
                tick.minPrice.doubleValue()))
        }
        // Add past trades as well
        chartOperations.addAll(exchange.pastTrades.map { t ->
            val type = if (t.type == "buy") OperationType.BUY else OperationType.SELL
            Operation(t.epoch, type, t.price, "past ${t.type}.\ncoins: ${t.coins}\ntotal: ${t.total}")
        })
        chartData.operations = chartOperations.toList()
        chartData.candles = candles.toList()
        chartData.priceIndicators = chartWriter.priceIndicators.toMap()
        chartData.extraIndicators = chartWriter.extraIndicators.toMap()
        state.chartVersion++

        out.write("Livemode thread working")
        while (true) {
            // step 1 - Fetch candle from the exchange
            var nextCandle = System.currentTimeMillis() + (period * 1000)
            var ticker = exchange.fetchTicker()
            val open = ticker.last
            var max = ticker.last
            var min = ticker.last
            while (System.currentTimeMillis() < nextCandle) { // keep building the candle
                ticker = exchange.fetchTicker()
                max = maxOf(max, ticker.last)
                min = minOf(min, ticker.last)
                if (!state.live) {
                    out.write("Livemode thread stopped.")
                    return
                }
                out.write("[building candle] max: $max min: $min")
                Thread.sleep(3000)
            }
            nextCandle += (period * 1000)

            // now the candle is done. pass it to the strategy
            val close = exchange.fetchTicker().last
            val epoch = Instant.now().atOffset(ZoneOffset.UTC).toEpochSecond()
            series.addBar(
                Duration.ofSeconds(period.toLong()),
                Instant.now().atOffset(ZoneOffset.UTC).toZonedDateTime(),
                series.numOf(open),
                series.numOf(max),
                series.numOf(min),
                series.numOf(close), // close
                series.numOf(0.0), // volume
                series.numOf(0.0) // amount
            )
            val tick = series.lastBar
            out.write("Candle built: $tick")

            // Now, run the strategy
            val tickNumber = series.endIndex
            val operations = strategy.onTick(tickNumber)
            strategy.onDrawChart(chartWriter, epoch, tickNumber)

            // Update local candles and operation list, and pass copies of it to the chart
            candles.add(Candle(
                epoch,
                tick.openPrice.doubleValue(),
                tick.closePrice.doubleValue(),
                tick.maxPrice.doubleValue(),
                tick.minPrice.doubleValue()))
            chartOperations.addAll(operations.map { op ->
                val type = if (op.type == Strategy.OperationType.BUY)
                    OperationType.BUY
                else
                    OperationType.SELL
                Operation(epoch, type, tick.closePrice.doubleValue(), op.description)
            })
            chartData.operations = chartOperations.toList()
            chartData.candles = candles.toList()
            chartData.priceIndicators = chartWriter.priceIndicators.toMap()
            chartData.extraIndicators = chartWriter.extraIndicators.toMap()
            state.chartVersion++

            // Update trades table in the instance state. Also print closed trades
            val trades = state.trades.toMutableList()
            for (op in operations) {
                if (op.type == Strategy.OperationType.SELL) {
                    trades.add(TradeEntry(op.code, op.buyPrice, tick.closePrice.doubleValue(), op.amount))
                    out.write("Close trade #${op.code}. buy: ${op.buyPrice} sell: ${tick.closePrice.doubleValue()}. amount: ${op.amount}")
                }
            }
            state.trades = trades
            state.stateVersion++
        }
    }
}