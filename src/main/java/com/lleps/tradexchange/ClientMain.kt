package com.lleps.tradexchange

import com.lleps.tradexchange.client.MainView
import com.lleps.tradexchange.client.RESTClient
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.image.Image
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import kotlin.concurrent.thread

class ClientMain : Application() {
    private val tabs = mutableMapOf<String, Tab>()
    private val views = mutableMapOf<String, MainView>()
    private val connection: RESTInterface = RESTClient(args[0])

    override fun start(stage: Stage) {
        val selectServerButton = Button("Host")
        val createInstanceButton = Button("+")
        val controlsHBox = HBox(5.0, selectServerButton, createInstanceButton)
        controlsHBox.alignment = Pos.TOP_RIGHT
        val tabPane = TabPane()
        tabPane.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        tabPane.styleClass.add(TabPane.STYLE_CLASS_FLOATING)
        stage.scene = Scene(StackPane(controlsHBox, tabPane).apply { isPickOnBounds = true })
        stage.icons.add(Image("money-icon.png"))
        stage.title = "Tradexchange"
        connection.getInstances { instances, _ ->
            println("instances: $instances")
            for (instance in instances) {
                val view = MainView()
                Platform.runLater {
                    val tab = Tab(instance)
                    tab.content = view.initJavaFxContent()
                    tabPane.tabs.add(tab)
                    tabs[instance] = tab
                    view.onExecute {
                        connection.updateInput(instance, it) { _, _ -> }
                        thread {
                            while (true) {
                                connection.getInstanceState(instance) { data, _ ->
                                    view.setOutput(data.output)
                                    view.setTrades(data.trades)
                                }
                                connection.getInstanceChartData(instance) { data, _ ->
                                    view.setChart(data.candles, data.operations, data.priceIndicators, data.extraIndicators)
                                }
                                Thread.sleep(1000)
                            }
                        }
                    }
                    connection.getInstanceState(instance) { data, _ ->
                        view.setInput(data.input)
                        view.setOutput(data.output)
                        view.setTrades(data.trades)
                    }
                    connection.getInstanceChartData(instance) { data, _ ->
                        view.setChart(data.candles, data.operations, data.priceIndicators, data.extraIndicators)
                    }
                }
                views[instance] = view
            }
        }
        stage.show()
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