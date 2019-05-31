package com.lleps.tradexchange

import com.lleps.tradexchange.view.FullChart
import com.lleps.tradexchange.view.MainView

/** Implements the logic of the backend. */
interface RESTInterface {
    /** Encapsulates main instance state. */
    class InstanceState(
        val input: Map<String, String> = emptyMap(),
        val output: String = "",
        val trades: List<MainView.TradeEntry> = emptyList()
    )

    /** Chart data (separated since it's too big to bundle with regular state). */
    class InstanceChartData(
        val candles: List<FullChart.Candle>,
        val operations: List<FullChart.Operation>,
        val priceIndicators: Map<String, Map<Long, Double>>,
        val extraIndicators: Map<String, Map<String, Map<Long, Double>>>
    )

    fun getInstances(onResult: (List<String>, Throwable?) -> Unit)
    fun getInstanceState(instance: String, onResult: (InstanceState, Throwable?) -> Unit)
    fun getInstanceChartData(instance: String, onResult: (InstanceChartData, Throwable?) -> Unit)
    fun updateInput(instance: String, input: Map<String, String>, onResult: (Throwable?) -> Unit)
}