package com.lleps.tradexchange

import com.lleps.tradexchange.view.MainView
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

class App : Application() {
    private lateinit var view: MainView
    private val server: RESTInterface = LocalRESTServer()
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
        private val LOGGER = LoggerFactory.getLogger(App::class.java)

        @JvmStatic
        fun main(args: Array<String>) {
            launch(App::class.java)
        }
    }
}