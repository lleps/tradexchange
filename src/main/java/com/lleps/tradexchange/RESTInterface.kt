package com.lleps.tradexchange

/** Interface to connect client<->server. */
interface RESTInterface {
    fun getInstances(onResult: (List<String>, Throwable?) -> Unit)
    fun getInstanceState(instance: String, onResult: (InstanceState, Throwable?) -> Unit)
    fun getInstanceChartData(instance: String, onResult: (InstanceChartData, Throwable?) -> Unit)
    fun updateInput(instance: String, input: Map<String, String>, button: Int, onResult: (Unit, Throwable?) -> Unit)
    fun createInstance(instanceQuery: String, onResult: (String, Throwable?) -> Unit)
    fun deleteInstance(instance: String, onResult: (Unit, Throwable?) -> Unit)
    fun getInstanceVersion(instance: String, onResult: (Pair<Int, Int>, Throwable?) -> Unit)
    fun toggleCandleState(instance: String, candleEpoch: Long, toggle: Int, onResult: (Unit, Throwable?) -> Unit)
}

// Shared data

enum class InstanceType { BACKTEST, LIVE, TRAIN }

/** Encapsulates main instance state. */
data class InstanceState(
    val type: InstanceType = InstanceType.LIVE,
    var input: Map<String, String> = emptyMap(),
    var output: String = "",
    var trades: List<TradeEntry> = emptyList(),
    var statusText: String = "not initialized",
    var statusPositiveness: Int = 0,
    var stateVersion: Int = 1, // those 2 should increase when a change occurs, so the client knows it needs the data
    var chartVersion: Int = 1,
    var action1: String = "",
    var action2: String = "",
    var live: Boolean = false // trading in live mode
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
    val amount: Double = 0.0
)