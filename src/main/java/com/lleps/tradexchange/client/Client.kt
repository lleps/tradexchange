package com.lleps.tradexchange.client

import com.lleps.tradexchange.server.LocalRESTServer
import com.lleps.tradexchange.server.RESTServer
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

class Client : Application() {
    private lateinit var view: MainView
    private val server: RESTServer = LocalRESTServer()
    private val instance = "default"

    override fun start(stage: Stage) {
        view = MainView()
        view.onExecute {
            server.updateInput(instance, it) {}
            thread {
                while (true) {
                    server.getInstanceState(instance) { data, _ ->
                        view.setOutput(data.output)
                        view.setTrades(data.trades)
                    }
                    server.getInstanceChartData(instance) { data, _ ->
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
        server.getInstanceState(instance) { data, _ ->
            view.setInput(data.input)
            view.setOutput(data.output)
            view.setTrades(data.trades)
        }
        // this should be executed only when a backtest occurs or something.
        server.getInstanceChartData(instance) { data, _ ->
            view.setChart(data.candles, data.operations, data.priceIndicators, data.extraIndicators)
        }
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(Client::class.java)

        @JvmStatic
        fun main(args: Array<String>) {
            launch(Client::class.java)
        }
    }
}