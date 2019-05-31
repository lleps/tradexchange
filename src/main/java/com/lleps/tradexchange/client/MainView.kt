package com.lleps.tradexchange.client

import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color

/**
 * Main client view. Contains controls to backtest, livetrade, etc.
 * Also contains chart and trade tables.
 * View events are passed via callbacks. But all the view state is set
 * through setters passing bulk data.
 */
class MainView {
    class TradeEntry(val id: Int, val buy: Double, val sell: Double, val amount: Double)

    private lateinit var chart: FullChart
    private lateinit var outputPane: TextArea
    private lateinit var executeButton: Button
    private lateinit var tradeTableContainer: BorderPane
    private lateinit var inputPane: VBox
    private val inputUIElements = mutableListOf<Pair<Label, TextField>>()

    private var onExecute: (Map<String, String>) -> Unit = {}

    fun initJavaFxContent(): Parent {
        // Main components
        chart = FullChart()
        val controlPane = VBox()
        val mainPane = BorderPane(chart, null, controlPane, null, null)
        outputPane = TextArea()

        // Input
        inputPane = VBox(5.0)
        executeButton = Button("Execute").apply {
            setOnAction { onExecute(readInput()) }
        }

        // Tabs
        tradeTableContainer = BorderPane()
        val tabOutput = Tab("Output", outputPane)
        val tabTrades = Tab("Trades", tradeTableContainer)
        val tabPane = TabPane(tabOutput, tabTrades)
        tabPane.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE

        outputPane.prefWidth = 300.0
        controlPane.children.add(inputPane)
        controlPane.children.add(executeButton)
        controlPane.children.add(tabPane)
        return mainPane
    }

    private fun readInput(): Map<String, String>{
        val result = mutableMapOf<String, String>()
        for ((label, field) in inputUIElements) result[label.text] = field.text
        return result
    }

    fun onExecute(callback: (Map<String, String>) -> Unit) {
        onExecute = callback
    }

    fun toggleExecute(toggle: Boolean) {
        Platform.runLater {
            executeButton.isDisable = !toggle
        }
    }

    fun setInput(input: Map<String, Any>) {
        Platform.runLater {
            inputPane.children.clear()
            inputUIElements.clear()
            for ((itemName, itemDefaultValue) in input) {
                val label = Label(itemName)
                val field = TextField(itemDefaultValue.toString())
                inputUIElements += Pair(label, field)
                inputPane.children.add(BorderPane(null, null, field, null, label))
            }
        }
    }

    private var lastOutput = ""
    fun setOutput(output: String) {
        if (output == lastOutput) return
        lastOutput = output
        Platform.runLater {
            outputPane.text = output
            outputPane.appendText("")
        }
    }

    fun setChart(
        candles: List<FullChart.Candle>,
        operations: List<FullChart.Operation>,
        priceIndicators: Map<String, Map<Long, Double>>,
        extraIndicators: Map<String, Map<String, Map<Long, Double>>>) {
        if (chart.priceData == candles &&
            chart.operations == operations &&
            chart.priceIndicators == priceIndicators &&
            chart.extraIndicators == extraIndicators) return
        chart.priceData = candles
        chart.operations = operations
        chart.priceIndicators = priceIndicators
        chart.extraIndicators = extraIndicators
        Platform.runLater { chart.fill() }
    }

    private var lastTrades = emptyList<TradeEntry>()
    fun setTrades(trades: List<TradeEntry>) {
        if (trades == lastTrades) return
        lastTrades = trades
        Platform.runLater { tradeTableContainer.center = createTradesTable(trades) }
    }

    private fun createTradesTable(trades: List<TradeEntry>): TableView<TradeEntry> {
        val items = FXCollections.observableArrayList<TradeEntry>(*trades.toTypedArray())
        val table = TableView(items)
        // this cell factory format objects as prices if isMoney, otherwise as raw decimals but with fewer digits
        val doubleFormattingCellFactory = { isMoney: Boolean ->
            object : TableCell<TradeEntry, Any>() {
                override fun updateItem(item: Any?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (item == null || empty) {
                        text = null
                        style = ""
                    } else {
                        val formatted = if (isMoney) "$%.04f" else "%.4f"
                        text = formatted.format(item)
                    }
                }
            }
        }
        table.columns.add(TableColumn<TradeEntry, Any>("#").apply {
            cellValueFactory = PropertyValueFactory("id")
        })
        table.columns.add(TableColumn<TradeEntry, Any>("buy").apply {
            cellValueFactory = PropertyValueFactory("buy")
            setCellFactory { doubleFormattingCellFactory(true) }
        })
        table.columns.add(TableColumn<TradeEntry, Any>("sell")
            .apply {
                cellValueFactory = PropertyValueFactory("sell")
                setCellFactory { doubleFormattingCellFactory(true) }
            })
        table.columns.add(TableColumn<TradeEntry, Any>("amount")
            .apply {
                cellValueFactory = PropertyValueFactory("amount")
                setCellFactory { doubleFormattingCellFactory(false/*not money*/) }
            })
        table.columns.add(TableColumn<TradeEntry, TradeEntry>("profit")
            .apply {
                setCellValueFactory { cellData -> SimpleObjectProperty(cellData.value) }
                setCellFactory { object : TableCell<TradeEntry, TradeEntry>() {
                    override fun updateItem(item: TradeEntry?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (item == null || empty) {
                            text = null
                            style = ""
                        } else {
                            val profit = (item.sell-item.buy) * item.amount
                            val color = if (profit <= 0.0) Color.ORANGERED else Color.GREEN
                            text = "$%.3f".format(profit)
                            textFill = color
                        }
                    }
                } }
            })
        return table
    }
}