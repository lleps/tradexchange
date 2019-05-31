package com.lleps.tradexchange

import com.lleps.tradexchange.client.MainView
import com.lleps.tradexchange.client.RESTClient
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

class ClientMain : Application() {
    private lateinit var view: MainView
    private val connection: RESTInterface = RESTClient()
    private val instance = "default"

    override fun start(stage: Stage) {
        view = MainView()
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
        stage.scene = Scene(view.initJavaFxContent())
        stage.icons.add(Image("money-icon.png"))
        stage.title = "Tradexchange"
        stage.show()

        // this should run in a timer, every 1 sec or something?
        connection.getInstanceState(instance) { data, _ ->
            view.setInput(data.input)
            view.setOutput(data.output)
            view.setTrades(data.trades)
        }
        // this should be executed only when a backtest occurs or something.
        connection.getInstanceChartData(instance) { data, _ ->
            view.setChart(data.candles, data.operations, data.priceIndicators, data.extraIndicators)
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(ClientMain::class.java)

        @JvmStatic
        fun main(args: Array<String>) {
            launch(ClientMain::class.java)
        }
    }
}