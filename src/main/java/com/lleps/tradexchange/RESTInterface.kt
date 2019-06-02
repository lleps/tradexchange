package com.lleps.tradexchange

/** Interface to connect client<->server. */
interface RESTInterface {
    fun getInstances(onResult: (List<String>, Throwable?) -> Unit)
    fun getInstanceState(instance: String, onResult: (InstanceState, Throwable?) -> Unit)
    fun getInstanceChartData(instance: String, onResult: (InstanceChartData, Throwable?) -> Unit)
    fun updateInput(instance: String, input: Map<String, String>, onResult: (Unit, Throwable?) -> Unit)
    fun createInstance(instance: String, onResult: (Unit, Throwable?) -> Unit)
    fun deleteInstance(instance: String, onResult: (Unit, Throwable?) -> Unit)
}

// Shared data

/** Encapsulates main instance state. */
data class InstanceState(
    var input: Map<String, String> = emptyMap(),
    var output: String = "",
    var trades: List<TradeEntry> = emptyList(),
    var statusText: String = "not initialized",
    var statusPositiveness: Int = 0
)

/** Chart data (separated since it's too big to bundle with regular state). */
data class InstanceChartData(
    var candles: List<Candle> = emptyList(),
    var operations: List<Operation> = emptyList(),
    var priceIndicators: Map<String, Map<Long, Double>> = emptyMap(),
    var extraIndicators: Map<String, Map<String, Map<Long, Double>>> = emptyMap()
)

data class Candle(
    val timestamp: Long = 0L,
    val open: Double = 0.0,
    val close: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0
)

enum class OperationType { BUY, SELL }

data class Operation(
    val timestamp: Long = 0L,
    val type: OperationType = OperationType.BUY,
    val price: Double = 0.0,
    val description: String? = null
)

data class TradeEntry(
    val id: Int = 0,
    val buy: Double = 0.0,
    val sell: Double = 0.0,
    val amount: Double = 0.0)