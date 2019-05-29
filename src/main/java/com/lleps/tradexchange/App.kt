package com.lleps.tradexchange

import com.lleps.tradexchange.view.FullChart
import javafx.application.Application
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.image.Image
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.apache.log4j.AppenderSkeleton
import org.apache.log4j.Logger
import org.apache.log4j.spi.LoggingEvent
import org.slf4j.LoggerFactory
import org.ta4j.core.BaseTimeSeries
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.concurrent.thread

class App : Application() {
    private lateinit var chart: FullChart
    private lateinit var exchange: Exchange
    private lateinit var state: TradePersistentState

    private enum class TradeType { BUY, SELL }

    private data class TradeEvent(
        val id: Int,
        val type: TradeType,
        val coins: Double,
        val price: Double,
        val epoch: Long
    )

    private data class TradePersistentState(val events: List<TradeEvent>, val unSoldCoins: List<TradeEvent>)

    private enum class Mode { BACKTEST, LIVE }

    private fun runStrategy(
        input: Map<String, String>,
        mode: Mode,
        onTrade: (buy: Double, sell: Double, amount: Double) -> Unit,
        onFinish: () -> Unit
    ) {
        val pair = input["pair"] ?: error("pair")
        val days = input["days"]?.toInt() ?: error("days")
        val period = input["period"]?.toLong() ?: error("period")
        val plotChart = input["plotChart"]?.toInt() ?: error("plotIndicators")
        thread(start = true, isDaemon = true) {
            try {
                when (mode) {
                    Mode.BACKTEST -> {
                        // Set up
                        LOGGER.info("Starting backtesting $days-day for $pair... (period: ${period/60} min)")

                        val initialMoney = 1000.0
                        val initialCoins = 0.0

                        // set up view data structures
                        val chartOperations = mutableListOf<FullChart.Operation>()
                        val candles = mutableListOf<FullChart.Candle>()
                        val priceIndicators = mutableMapOf<String, MutableMap<Long, Double>>()
                        val extraIndicators = mutableMapOf<String, MutableMap<String, MutableMap<Long, Double>>>()
                        val chartWriter = object : Strategy.ChartWriter {
                            override fun priceIndicator(name: String, epoch: Long, value: Double) {
                                if (plotChart >= 2) {
                                    val data = priceIndicators.getOrPut(name) { mutableMapOf() }
                                    data[epoch] = value
                                }
                            }

                            override fun extraIndicator(chart: String, name: String, epoch: Long, value: Double) {
                                if (plotChart >= 3) {
                                    val chartData = extraIndicators.getOrPut(chart) { mutableMapOf() }
                                    val data = chartData.getOrPut(name) { mutableMapOf() }
                                    data[epoch] = value
                                }
                            }
                        }

                        exchange = PoloniexBacktestExchange(
                            pair = pair,
                            period = period,
                            fromEpoch = ZonedDateTime.now(ZoneOffset.UTC).minusDays(days.toLong()).toEpochSecond(),
                            warmUpTicks = 100,
                            initialMoney = initialMoney,
                            initialCoins = initialCoins)

                        val allTicks = mutableListOf(*exchange.warmUpHistory.toTypedArray())

                        while (true) {
                            val tick = exchange.fetchTick() ?: break
                            allTicks += tick
                        }

                        // Test
                        val timeSeries = BaseTimeSeries(allTicks)
                        val strategy = Strategy(
                            series = timeSeries,
                            period = period,
                            backtest = true,
                            epochStopBuy = ZonedDateTime.now(ZoneOffset.UTC).minusHours(8).toEpochSecond(),
                            exchange = exchange,
                            input = input
                        )

                        for (i in exchange.warmUpHistory.size..timeSeries.endIndex) {
                            val tick = timeSeries.getTick(i)
                            val epoch = tick.beginTime.toEpochSecond()
                            val operations = strategy.onTick(i)
                            strategy.onDrawChart(chartWriter, epoch, i)
                            candles.add(FullChart.Candle(
                                epoch,
                                tick.openPrice.toDouble(),
                                tick.closePrice.toDouble(),
                                tick.maxPrice.toDouble(),
                                tick.minPrice.toDouble()))
                            chartOperations.addAll(operations.map { op ->
                                val type = if (op.type == Strategy.OperationType.BUY)
                                    FullChart.OperationType.BUY
                                else
                                    FullChart.OperationType.SELL
                                FullChart.Operation(epoch, type, tick.closePrice.toDouble(), op.description)
                            })
                            for (op in operations) {
                                if (op.type == Strategy.OperationType.SELL) {
                                    onTrade(op.buyPrice, tick.closePrice.toDouble(), op.amount)
                                }
                            }
                        }

                        // Resume
                        val firstPrice = ClosePriceIndicator(timeSeries)[exchange.warmUpHistory.size]
                        val latestPrice = ClosePriceIndicator(timeSeries)[timeSeries.endIndex]

                        LOGGER.info(" ______________________________________________________ ")
                        LOGGER.info("                  RESULTS                               ")
                        LOGGER.info("Initial balance        %.03f'c $%.03f"
                            .format(initialCoins, initialMoney))
                        LOGGER.info("Final balance          %.03f'c $%.03f (net %.03f'c \$%.03f)"
                            .format(exchange.coinBalance, exchange.moneyBalance,
                                exchange.coinBalance - initialCoins,
                                exchange.moneyBalance - initialMoney))
                        LOGGER.info("Coin start/end value   $%.03f / $%.03f (net $%.03f)"
                            .format(firstPrice, latestPrice, latestPrice - firstPrice))
                        LOGGER.info("Trades: ${strategy.tradeCount}")
                        LOGGER.info(" ______________________________________________________ ")

                        // Draw chart
                        chart.priceData = if (plotChart >= 1) candles else emptyList()
                        chart.operations = if (plotChart >= 1) chartOperations else emptyList()
                        chart.priceIndicators = if (plotChart >= 1) priceIndicators else emptyMap()
                        chart.extraIndicators = if (plotChart >= 1) extraIndicators else emptyMap()
                        LOGGER.info("Done")
                        Platform.runLater {
                            chart.fill()
                        }
                        onFinish()
                    }
                    Mode.LIVE -> TODO("Implement live mode")
                }
            } catch (e: Exception) {
                LOGGER.info("error: $e", e)
                onFinish()
            }
        }
    }

    class TradeTableEntry(val buyPrice: Double, val sellPrice: Double, val amount: Double, val profit: Double)

    private fun createTradesTable(trades: List<TradeTableEntry>): TableView<TradeTableEntry> {
        val items = FXCollections.observableArrayList<TradeTableEntry>(*trades.toTypedArray())
        val table = TableView(items)
        // this cell factory format objects as prices
        val cellFactory = {
            object : TableCell<TradeTableEntry, Any>() {
                override fun updateItem(item: Any?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (item == null || empty) {
                        text = null
                        style = ""
                    } else {
                        val color = if (item.toString().contains("-")) Color.ORANGERED else Color.GREEN
                        text = "\$$item"
                        textFill = color
                    }
                }
            }
        }
        table.columns.add(TableColumn<TradeTableEntry, Any>("buy").apply {
            cellValueFactory = PropertyValueFactory("buyPrice")
            setCellFactory { cellFactory() }
        })
        table.columns.add(TableColumn<TradeTableEntry, Any>("sell")
            .apply {
                cellValueFactory = PropertyValueFactory("sellPrice")
                setCellFactory { cellFactory() }
            })
        table.columns.add(TableColumn<TradeTableEntry, Any>("amount")
            .apply {
                cellValueFactory = PropertyValueFactory("amount")
            })
        table.columns.add(TableColumn<TradeTableEntry, Any>("profit")
            .apply {
                cellValueFactory = PropertyValueFactory("profit")
                setCellFactory { cellFactory() }
            })
        return table
    }

    override fun start(stage: Stage) {
        // Init scene, chart and main pane
        chart = FullChart()
        val controlPane = VBox()
        stage.scene = Scene(BorderPane(chart, null, controlPane, null, null))
        stage.icons.add(Image("money-icon.png"))
        stage.title = "Tradexchange"
        stage.show()
        val outputPane = TextArea()

        val tradeTableItems = mutableListOf<TradeTableEntry>()
        val tradeTableContainer = BorderPane()
        val tabOutput = Tab("Output", outputPane)
        val tabTrades = Tab("Trades", tradeTableContainer)
        val tabPane = TabPane(tabOutput, tabTrades)
        tabPane.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE

        // Input pane start
        val defaultInput = mutableMapOf(
            "pair" to "USDT_ETH",
            "period" to "300",
            "days" to "7",
            "initialMoney" to "1000.0",
            "initialCoins" to "0.0",
            "plotChart" to "3"
        )
        Strategy.requiredInput.forEach { key, value -> defaultInput[key] = value.toString() }
        val inputPane = VBox(5.0)
        val inputUIElements = mutableListOf<Pair<Label, TextField>>()
        for ((itemName, itemDefaultValue) in defaultInput) {
            val label = Label(itemName)
            val field = TextField(itemDefaultValue)
            inputUIElements += Pair(label, field)
            inputPane.children.add(BorderPane(null, null, field, null, label))
        }
        inputPane.children.add(Button("Ejecutar").apply {
            setOnAction {
                this.isDisable = true
                // read input from ui
                val map = mutableMapOf<String, String>()
                for ((label, field) in inputUIElements) map[label.text] = field.text
                outputPane.text = ""
                LOGGER.info("Input: $map")
                runStrategy(map, Mode.BACKTEST,
                    onTrade = { buy, sell, amount ->
                        tradeTableItems.add(TradeTableEntry(buy, sell, amount, (sell-buy)*amount))
                    },
                    onFinish = {
                        Platform.runLater {
                            this@apply.isDisable = false
                            tradeTableContainer.center = createTradesTable(tradeTableItems)
                            tradeTableItems.clear()
                        }
                    })
            }
        })
        // ok. ejecutar deberia ejecutar la estrategia...
        controlPane.children.add(inputPane)

        // Log start
        Logger.getRootLogger().addAppender(object : AppenderSkeleton() {
            override fun append(event: LoggingEvent) {
                Platform.runLater { outputPane.text += "${event.message}\n" }
            }
            override fun close() {}
            override fun requiresLayout() = false
        })
        outputPane.prefWidth = 400.0
        controlPane.children.add(tabPane)
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(App::class.java)

        private lateinit var args: Array<String>

        @JvmStatic
        fun main(args: Array<String>) {
            Companion.args = args
            launch(App::class.java)
        }
    }
}