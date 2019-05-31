package com.lleps.tradexchange

import com.lleps.tradexchange.client.FullChart
import com.lleps.tradexchange.client.MainView

/** Interface to connect client<->server. */
interface RESTInterface {
    /** Encapsulates main instance state. */
    data class InstanceState(
        var input: Map<String, String> = emptyMap(),
        var output: String = "",
        var trades: List<MainView.TradeEntry> = emptyList()
    )

    /** Chart data (separated since it's too big to bundle with regular state). */
    data class InstanceChartData(
        var candles: List<FullChart.Candle> = emptyList(),
        var operations: List<FullChart.Operation> = emptyList(),
        var priceIndicators: Map<String, Map<Long, Double>> = emptyMap(),
        var extraIndicators: Map<String, Map<String, Map<Long, Double>>> = emptyMap()
    )

    fun getInstances(onResult: (List<String>, Throwable?) -> Unit)
    fun getInstanceState(instance: String, onResult: (InstanceState, Throwable?) -> Unit)
    fun getInstanceChartData(instance: String, onResult: (InstanceChartData, Throwable?) -> Unit)
    fun updateInput(instance: String, input: Map<String, String>, onResult: (Unit, Throwable?) -> Unit)
}