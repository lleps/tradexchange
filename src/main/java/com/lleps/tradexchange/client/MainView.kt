package com.lleps.tradexchange.client

import com.lleps.tradexchange.Candle
import com.lleps.tradexchange.Operation
import com.lleps.tradexchange.TradeEntry
import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color

/**
 * Main client view. Contains controls to backtest, livetrade, etc.
 * Also contains chart and trade tables.
 * View events are passed via callbacks. But all the view state is set
 * through setters passing bulk data.
 */
class MainView {
    lateinit var chart: FullChart
    private lateinit var outputPane: TextArea
    private lateinit var tradeTableContainer: BorderPane
    private lateinit var inputPane: VBox
    private lateinit var statusLabel: Label
    private val inputUIElements = mutableListOf<Pair<Label, TextField>>()
    private lateinit var action1Button: Button
    private lateinit var action2Button: Button
    private var onAction1: (Map<String, String>) -> Unit = {}
    private var onAction2: (Map<String, String>) -> Unit = {}
    private val paneWidth = 300.0
    private var onSelectCandle: ((Candle, MouseButton) -> Unit)? = null
    private var onSelectOperation: ((Operation) -> Unit)? = null

    fun initJavaFxContent(): Parent {
        // Main components
        chart = FullChart(useCandles = true)
        chart.onSelectCandle { c, b -> onSelectCandle?.invoke(c, b) }
        chart.onSelectOperation { op -> onSelectOperation?.invoke(op) }
        val controlPane = VBox(15.0).apply { this.padding = Insets(5.0, 10.0, 1.0, 1.0) }
        controlPane.prefWidth = paneWidth
        val mainPane = BorderPane(chart, null, controlPane, null, null)
        outputPane = TextArea()
        outputPane.prefHeight = 500.0

        // Input
        inputPane = VBox(5.0)
        inputPane.prefWidth = paneWidth

        // Execute and status label
        statusLabel = Label("")
        action1Button = Button("").apply { setOnAction { onAction1(readInput()) } }
        action2Button = Button("").apply { setOnAction { onAction2(readInput()) } }

        // Tabs
        tradeTableContainer = BorderPane()
        val tabOutput = Tab("Output", outputPane)
        val tabTrades = Tab("Trades", tradeTableContainer)
        val tabPane = TabPane(tabOutput, tabTrades)
        tabPane.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        tabPane.styleClass.add(TabPane.STYLE_CLASS_FLOATING)
        tabPane.prefWidth = paneWidth
        tabPane.prefHeight = paneWidth * 0.5
        tabPane.minHeight = paneWidth * 0.5

        outputPane.prefWidth = paneWidth
        controlPane.children.add(ScrollPane(inputPane))
        controlPane.children.add(HBox(statusLabel).apply { alignment = Pos.CENTER_RIGHT; prefWidth = paneWidth })
        controlPane.children.add(HBox(5.0, action2Button, action1Button).apply { alignment = Pos.CENTER_RIGHT; prefWidth = paneWidth })
        controlPane.children.add(tabPane)
        return mainPane
    }

    fun onSelectCandle(callback: (Candle, MouseButton) -> Unit) {
        onSelectCandle = callback
    }

    fun onSelectOperation(callback: (Operation) -> Unit) {
        onSelectOperation = callback
    }

    private fun readInput(): Map<String, String>{
        val result = mutableMapOf<String, String>()
        for ((label, field) in inputUIElements) result[label.text] = field.text
        return result
    }

    fun onAction1(callback: (Map<String, String>) -> Unit) {
        onAction1 = callback
    }

    fun onAction2(callback: (Map<String, String>) -> Unit) {
        onAction2 = callback
    }

    fun setAction1(action1: String) {
        Platform.runLater {
            action1Button.text = action1
            action1Button.isVisible = !action1.isEmpty()
        }
    }

    fun setAction2(action2: String) {
        Platform.runLater {
            action2Button.text = action2
            action2Button.isVisible = !action2.isEmpty()
        }
    }

    private fun updateInput() {
        val keyword = "source"
        var prefix = "none"
        var currentValue = "none"
        for ((label, field) in inputUIElements) {
            if (label.text.endsWith(keyword)) {
                prefix = label.text.replace(keyword, "")
                currentValue = field.text
            } else if (label.text.startsWith(prefix)){
                val shouldBeVisible = label.text.startsWith("$prefix$currentValue")
                label.isDisable = !shouldBeVisible
                field.isDisable = !shouldBeVisible
            }
        }
    }

    fun setStatus(status: String, positiveness: Int) {
        Platform.runLater {
            statusLabel.textFill = if (positiveness > 0) Color.GREEN else if (positiveness < 0) Color.RED else Color.BLACK
            statusLabel.text = status
        }
    }

    fun setInput(input: Map<String, Any>) {
        Platform.runLater {
            val mainInput = VBox(-3.0)
            val strategyInput = VBox(-3.0) // to group this in an accordition
            val modelInput = VBox(-3.0) // to group this in an accordition
            inputUIElements.clear()
            for ((itemName, itemDefaultValue) in input) {
                val label = Label(itemName)
                val field = TextField(itemDefaultValue.toString())
                field.setOnKeyTyped { Platform.runLater { updateInput() } }
                inputUIElements += Pair(label, field)
                val paneToAdd =
                    when {
                        label.text.startsWith("strategy.") -> strategyInput
                        label.text.startsWith("model.") -> modelInput
                        else -> mainInput
                    }
                paneToAdd.children.add(BorderPane(null, null, field, null, label))
            }
            inputPane.children.clear()
            inputPane.children.add(mainInput)
            if (strategyInput.children.isNotEmpty()) inputPane.children.add(TitledPane("Strategy", strategyInput))
            if (modelInput.children.isNotEmpty()) inputPane.children.add(TitledPane("Model", modelInput))
            updateInput()
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
        candles: List<Candle>,
        operations: List<Operation>,
        priceIndicators: Map<String, Map<Long, Double>>,
        extraIndicators: Map<String, Map<String, Map<Long, Double>>>) {
        val priceChanged = chart.priceData != candles
        val operationsChanged = chart.operations != operations
        val priceIndicatorsChanged = chart.priceIndicators != priceIndicators
        val extraIndicatorsChanged = chart.extraIndicators != extraIndicators
        // nothing changed
        if (!priceChanged && !operationsChanged && !priceIndicatorsChanged && !extraIndicatorsChanged) {
            return
        }
        // only operations changed, update them without rebuilding the whole chart
        else if (operationsChanged && !priceChanged && !priceIndicatorsChanged && !extraIndicatorsChanged) {
            chart.operations = operations
            chart.updateOperations()
            return
        }
        // something else, rebuild the chart
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
                            text = "$%.3f".format(profit)
                            val color = if (profit <= 0.0) "orangered" else "lightgreen"
                            style = "-fx-background-color:$color"
                        }
                    }
                } }
            })
        return table
    }
}