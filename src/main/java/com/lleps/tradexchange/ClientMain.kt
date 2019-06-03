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

class ClientMain : Application() {
    private lateinit var tabPane: TabPane
    private val tabs = mutableMapOf<String, Tab>()
    private val views = mutableMapOf<String, MainView>()
    private lateinit var connection: RESTClient
    private val gotInput = mutableMapOf<String, Unit>() // to fetch input only once per instance
    private var waitingToAcceptError = false // if true, the background thread shouldn't try to update
    class ClientData(val host: String = "http://localhost:8080")

    private var clientData = ClientData()
    private lateinit var stage: Stage

    override fun start(stage: Stage) {
        this.stage = stage
        clientData = loadFrom<ClientData>("tradexchange_data.json") ?: ClientData()
        connection = RESTClient(clientData.host)
        initUpdaterThread()

        // Controls
        val selectServerButton = Button("Set host").apply {
            setOnAction {
                val textInput = TextInputDialog(connection.host)
                textInput.title = "Set host (http://ip:port)"
                textInput.headerText = null
                textInput.dialogPane.contentText = ""
                textInput.showAndWait()
                    .ifPresent { response ->
                        clientData = ClientData(host = response)
                        clientData.saveTo("tradexchange_data.json")
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
        val createInstanceButton = Button("Add instance").apply {
            setOnAction {
                val textInput = TextInputDialog("new-instance|settingsFrom")
                textInput.title = "Add instance"
                textInput.headerText = null
                textInput.dialogPane.contentText = ""
                textInput.showAndWait()
                    .ifPresent { response ->
                        if (!response.isEmpty()) {
                            if (tabs.containsKey(response)) {
                                showError("name already used")
                                return@ifPresent
                            }
                            connection.createInstance(response) { _, throwable ->
                                if (throwable != null) {
                                    showError("createInstance", throwable)
                                    return@createInstance
                                }
                                registerTab(response, select = true)
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
        AnchorPane.setRightAnchor(controlsHBox, 5.0)
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
            val tab = Tab(instance, view.initJavaFxContent())
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
            view.onExecute {
                connection.updateInput(instance, it) { _, throwable ->
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
    private fun initUpdaterThread() {
        thread(start = true, name = "backgroundUpdateThread", isDaemon = true) {
            var iteration = 0
            var lastInstance = ""
            while (true) {
                if (waitingToAcceptError) continue

                val instance = tabs.entries.firstOrNull { it.value.isSelected }?.key
                if (instance != null && views.containsKey(instance)) {
                    if (instance != lastInstance) {
                        iteration = 0
                        lastInstance = instance
                    }
                    val view = views.getValue(instance)
                    if (iteration % (1*4) == 0) {
                        connection.getInstanceState(instance) { data, throwable ->
                            if (throwable != null) {
                                showError("getInstanceState", throwable)
                                return@getInstanceState
                            }
                            if (!gotInput.containsKey(instance)) {
                                gotInput[instance] = Unit
                                view.setInput(data.input)
                            }
                            view.setOutput(data.output)
                            view.setTrades(data.trades)
                            view.setStatus(data.statusText, data.statusPositiveness)
                        }
                    }
                    if (iteration % (3*4) == 0) {
                        connection.getInstanceChartData(instance) { data, throwable ->
                            if (throwable != null) {
                                showError("getInstanceChartData", throwable)
                                return@getInstanceChartData
                            }
                            view.setChart(data.candles, data.operations, data.priceIndicators, data.extraIndicators)
                        }
                    }
                    iteration++
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