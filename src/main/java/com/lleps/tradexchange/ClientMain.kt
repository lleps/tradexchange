package com.lleps.tradexchange

import com.lleps.tradexchange.client.MainView
import com.lleps.tradexchange.client.RESTClient
import com.lleps.tradexchange.util.loadFrom
import com.lleps.tradexchange.util.saveTo
import javafx.application.Application
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.layout.*
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread
import javafx.stage.Modality
import javafx.stage.WindowEvent
import java.io.PrintWriter
import java.io.StringWriter
import javafx.scene.control.ButtonType
import javafx.scene.control.Alert
import javafx.scene.image.ImageView
import java.io.File

class ClientMain : Application() {
    private lateinit var tabPane: TabPane
    private val tabs = mutableMapOf<String, Tab>()
    private val views = mutableMapOf<String, MainView>()
    private lateinit var connection: RESTClient
    private val gotInput = mutableMapOf<String, Unit>() // to fetch input only once per instance
    private var waitingToAcceptError = false // if true, the background thread shouldn't try to update
    class ClientData(val host: String = "http://localhost:8080")

    private var clientData = ClientData()
    private var stateVersion = mutableMapOf<String, Int>().withDefault { 0 }
    private var chartVersion = mutableMapOf<String, Int>().withDefault { 0 }
    private lateinit var stage: Stage

    private val configPath = "data/tradexchange_client.json"
    
    override fun start(stage: Stage) {
        File("data").mkdir()
        this.stage = stage
        clientData = loadFrom<ClientData>(configPath) ?: ClientData()
        connection = RESTClient(clientData.host)
        fetchCurrentInstanceDataThread()

        // Controls
        val selectServerButton = Button("IP").apply {
            setOnAction {
                val textInput = TextInputDialog(connection.host)
                textInput.title = "Set host (http://ip:port)"
                textInput.headerText = null
                textInput.dialogPane.contentText = ""
                textInput.showAndWait()
                    .ifPresent { response ->
                        clientData = ClientData(host = response)
                        stateVersion.clear()
                        chartVersion.clear()
                        clientData.saveTo(configPath)
                        connection.host = response
                        Platform.runLater {
                            stage.title = "Tradexchange (at ${clientData.host})"
                            tabPane.tabs.clear()
                            tabs.clear()
                            views.clear()
                            gotInput.clear()
                            fetchInstances()
                        }
                    }
            }
        }
        val createInstanceButton = Button("+").apply {
            setOnAction {
                val textInput = TextInputDialog("type:name:{source?}")
                textInput.title = "Add instance"
                textInput.headerText = null
                textInput.dialogPane.contentText = "types: backtest, live, train. ej 'live:ethbot'"
                textInput.showAndWait()
                    .ifPresent { response ->
                        if (!response.isEmpty()) {
                            if (tabs.containsKey(response)) {
                                showError("name already used")
                                return@ifPresent
                            }
                            connection.createInstance(response) { newInstance, throwable ->
                                if (throwable != null) {
                                    showError("createInstance", throwable)
                                    return@createInstance
                                }
                                registerTab(newInstance, select = true)
                            }
                        }
                    }
            }
        }
        val controlsHBox = HBox(5.0, selectServerButton, createInstanceButton)

        // Tab pane
        tabPane = TabPane()
        tabPane.tabClosingPolicy = TabPane.TabClosingPolicy.SELECTED_TAB
        tabPane.styleClass.add(TabPane.STYLE_CLASS_FLOATING)

        // Anchor the controls
        val anchor = AnchorPane()
        anchor.children.addAll(tabPane, controlsHBox)
        AnchorPane.setTopAnchor(controlsHBox, 3.0)
        AnchorPane.setLeftAnchor(controlsHBox, 5.0)
        AnchorPane.setTopAnchor(tabPane, 1.0)
        AnchorPane.setRightAnchor(tabPane, 1.0)
        AnchorPane.setLeftAnchor(tabPane, 1.0)
        AnchorPane.setBottomAnchor(tabPane, 1.0)

        // Create scene
        val root = BorderPane(anchor)
        root.stylesheets.add("JMetroLightTheme.css")
        root.stylesheets.add("CustomStyles.css")
        stage.scene = Scene(root)
        stage.icons.add(Image("money-icon.png"))
        stage.title = "Tradexchange (at ${clientData.host})"
        stage.onCloseRequest = EventHandler<WindowEvent> { Platform.exit(); System.exit(0) }
        stage.isMaximized = true
        fetchInstances()
        stage.show()
    }

    private fun fetchInstances() {
        // Fetch data
        connection.getInstances { instances, throwable ->
            if (throwable != null) {
                showError("", throwable)
                return@getInstances
            }
            for (instance in instances) registerTab(instance)
        }
    }

    private fun registerTab(instance: String, select: Boolean = false) {
        Platform.runLater {
            val view = MainView()
            val img = when {
                instance.startsWith("[live]") -> "liveicon.png"
                instance.startsWith("[backtest]") -> "backtesticon.png"
                instance.startsWith("[train]") -> "trainicon.png"
                else -> null
            }
            val tabName = if (img == null)
                instance
            else
                instance.replace("[live]","").replace("[backtest]","").replace("[train]","")

            val tab = Tab(tabName, view.initJavaFxContent())
            if (img != null) {
                tab.graphic = ImageView(Image(img, true))
            }
            view.onSelectCandle { candle ->
                val op = view.chart.operations.firstOrNull { op -> op.timestamp == candle.timestamp }
                connection.toggleCandleState(instance, candle.timestamp, toggle = op == null) { _, throwable3 ->
                    if (throwable3 != null) {
                        showError("toggleCandleState", throwable3)
                        return@toggleCandleState
                    }

                    // once we get the ok response from the server, proceed to (un)plot it locally
                    if (op == null) {
                        val newOp = Operation(
                            candle.timestamp,
                            OperationType.BUY,
                            candle.close,
                            "buy train point")
                        view.chart.operations += newOp
                    } else {
                        view.chart.operations -= op
                    }
                    view.chart.updateOperations()
                }
            }
            tab.setOnCloseRequest { e ->
                e.consume()
                val alert = Alert(Alert.AlertType.CONFIRMATION, "")
                alert.initModality(Modality.APPLICATION_MODAL)
                alert.dialogPane.contentText = "Confirm delete $instance?"
                alert.dialogPane.headerText = null
                alert.showAndWait()
                    .filter { response -> response == ButtonType.OK }
                    .ifPresent {
                        Platform.runLater {
                            connection.deleteInstance(instance) { _, throwable ->
                                if (throwable != null) showError("deleteInstance", throwable)
                                else Platform.runLater {
                                    tabPane.tabs.remove(tab)
                                    tabs.remove(instance)
                                    views.remove(instance)
                                }
                            }
                        }
                    }
            }
            tabs[instance] = tab
            views[instance] = view
            tabPane.tabs.add(tab)
            if (select) tabPane.selectionModel.select(tab)
            view.onAction1 {
                connection.updateInput(instance, it, 1) { _, throwable ->
                    if (throwable != null) showError("updateInput", throwable)
                }
            }
            view.onAction2 {
                connection.updateInput(instance, it, 2) { _, throwable ->
                    if (throwable != null) showError("updateInput", throwable)
                }
            }
        }
    }

    private fun showError(string: String, throwable: Throwable? = null) {
        waitingToAcceptError = true
        Platform.runLater {
            val dialog = Dialog<ButtonType>()
            dialog.title = "Error"
            val dialogPane = dialog.dialogPane
            dialogPane.contentText = "Details of the problem:"
            dialogPane.buttonTypes.addAll(ButtonType.OK)
            dialogPane.contentText = string + "\n" + (throwable?.message ?: "")
            dialog.initModality(Modality.APPLICATION_MODAL)

            if (throwable != null) {
                val label = Label("Exception stacktrace:")
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.close()

                val textArea = TextArea(sw.toString())
                textArea.isEditable = false
                textArea.isWrapText = true
                textArea.maxWidth = Double.MAX_VALUE
                textArea.maxHeight = Double.MAX_VALUE
                GridPane.setVgrow(textArea, Priority.ALWAYS)
                GridPane.setHgrow(textArea, Priority.ALWAYS)

                val root = GridPane()
                root.isVisible = false
                root.maxWidth = Double.MAX_VALUE
                root.add(label, 0, 0)
                root.add(textArea, 0, 1)
                dialogPane.expandableContent = root
            }
            dialog.showAndWait()
            waitingToAcceptError = false
        }
    }

    /** This thread fetches the state from the server every second and update UIs accordingly */
    private fun fetchCurrentInstanceDataThread() {
        thread(start = true, name = "backendDataFetchThread", isDaemon = true) {
            var lastInstance = ""
            while (true) {
                if (waitingToAcceptError) continue

                val instance = tabs.entries.firstOrNull { it.value.isSelected }?.key
                if (instance != null && views.containsKey(instance)) {
                    if (instance != lastInstance) {
                        lastInstance = instance
                    }
                    val view = views.getValue(instance)
                    connection.getInstanceVersion(instance) { (newStateVersion,newChartVersion), throwable ->
                        if (throwable != null) {
                            showError("getInstanceVersion", throwable)
                            return@getInstanceVersion
                        }
                        if (newStateVersion > stateVersion.getValue(instance)) {
                            connection.getInstanceState(instance) { data, throwable2 ->
                                if (throwable2 != null) {
                                    showError("getInstanceState", throwable2)
                                    return@getInstanceState
                                }
                                if (!gotInput.containsKey(instance)) {
                                    gotInput[instance] = Unit
                                    view.setInput(data.input)
                                    // TODO: should update the input. but how
                                    // to update it without overwriting made changes?
                                }
                                view.setOutput(data.output)
                                view.setTrades(data.trades)
                                view.setStatus(data.statusText, data.statusPositiveness)
                                view.setAction1(data.action1)
                                view.setAction2(data.action2)
                                stateVersion[instance] = data.stateVersion
                            }
                        }
                        if (newChartVersion > chartVersion.getValue(instance)) {
                            connection.getInstanceChartData(instance) { data, throwable2 ->
                                if (throwable2 != null) {
                                    showError("getInstanceChartData", throwable2)
                                    return@getInstanceChartData
                                }
                                view.setChart(data.candles, data.operations, data.priceIndicators, data.extraIndicators)
                                chartVersion[instance] = newChartVersion
                            }
                        }
                    }
                }
                Thread.sleep(250)
            }
        }
    }

    companion object {
        private lateinit var args: Array<String>
        private val LOGGER = LoggerFactory.getLogger(ClientMain::class.java)

        @JvmStatic
        fun main(args: Array<String>) {
            this.args = args
            launch(ClientMain::class.java)
        }
    }
}