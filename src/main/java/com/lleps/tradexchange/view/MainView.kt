package com.lleps.tradexchange.view

import javafx.scene.Parent

/**
 * Main client view. Contains controls to backtest, livetrade, etc.
 * Also contains chart and trade tables.
 * View events are passed via callbacks. But all the view state is set
 * through setters passing bulk data.
 */
class MainView {
    class TradeEntry(val id: Int, val buy: Double, val sell: Double)

    fun initJavaFxContent(): Parent {
        TODO()
    }

    fun onExecute(input: (Map<String, String>) -> Unit) {

    }

    fun toggleExecute(toggle: Boolean) {

    }

    fun setInput(input: Map<String, Any>) {

    }

    fun log(message: String) {

    }

    fun setChart(
        candles: List<FullChart.Candle>,
        operations: List<FullChart.Operation>,
        priceIndicators: Map<String, Map<Long, Double>>,
        extraIndicators: Map<String, Map<String, Map<Long, Double>>>) {

    }

    fun setTrades(trades: List<TradeEntry>) {

    }
}