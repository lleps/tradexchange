package com.lleps.tradexchange

import com.lleps.tradexchange.client.MainView
import com.lleps.tradexchange.client.RESTClient
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.layout.*
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import kotlin.concurrent.thread
import javafx.stage.Modality
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Exception

class ClientMain : Application() {
    private lateinit var tabPane: TabPane
    private val tabs = mutableMapOf<String, Tab>()
    private val views = mutableMapOf<String, MainView>()
    private val connection: RESTInterface = RESTClient(args[0])
    private val gotInput = mutableMapOf<String, Unit>() // to fetch input only once per instance

    override fun start(stage: Stage) {
        initUpdaterThread()

        // Controls
        val selectServerButton = Button("Set host")
        val createInstanceButton = Button("Add instance").apply {
            setOnAction {
                val textInput = TextInputDialog("new-instance")
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
                                registerTab(response)
                            }
                        }
                    }
            }
        }
        val controlsHBox = HBox(5.0, selectServerButton, createInstanceButton)

        // Tab pane
        tabPane = TabPane()
        tabPane.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
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
        root.stylesheets.add("JMetroBase.css")
        stage.scene = Scene(root)
        stage.icons.add(Image("money-icon.png"))
        stage.title = "Tradexchange"

        // Fetch data
        connection.getInstances { instances, throwable ->
            if (throwable != null) {
                showError("", throwable)
                return@getInstances
            }
            for (instance in instances) registerTab(instance)
        }
        stage.show()
    }

    private fun registerTab(instance: String) {
        Platform.runLater {
            val view = MainView()
            val tab = Tab(instance, view.initJavaFxContent())
            tabs[instance] = tab
            views[instance] = view
            tabPane.tabs.add(tab)
            view.onExecute {
                connection.updateInput(instance, it) { _, throwable ->
                    if (throwable != null) showError("updateInput", throwable)
                }
            }
        }
    }

    private fun showError(string: String, throwable: Throwable? = null) {
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
        }
    }

    /** This thread fetches the state from the server every second and update UIs accordingly */
    private fun initUpdaterThread() {
        thread(start = true, name = "backgroundUpdateThread", isDaemon = true) {
            var iteration = 0
            while (true) {
                val instance = tabs.entries.firstOrNull { it.value.isSelected }?.key
                if (instance != null && views.containsKey(instance)) {
                    val view = views.getValue(instance)
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
                    }
                    if (iteration++ % 3 == 0) {
                        connection.getInstanceChartData(instance) { data, throwable ->
                            if (throwable != null) {
                                showError("getInstanceChartData", throwable)
                                return@getInstanceChartData
                            }
                            view.setChart(data.candles, data.operations, data.priceIndicators, data.extraIndicators)
                        }
                    }
                }
                Thread.sleep(1000)
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