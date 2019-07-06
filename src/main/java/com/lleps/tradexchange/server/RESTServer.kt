package com.lleps.tradexchange.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.lleps.tradexchange.InstanceChartData
import com.lleps.tradexchange.InstanceState
import com.lleps.tradexchange.InstanceType
import com.lleps.tradexchange.strategy.Strategy
import com.lleps.tradexchange.util.*
import io.javalin.Javalin
import org.slf4j.LoggerFactory
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.contains
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlin.collections.getValue
import kotlin.collections.iterator
import kotlin.collections.listOf
import kotlin.collections.minus
import kotlin.collections.plus
import kotlin.collections.set
import kotlin.collections.toList
import kotlin.collections.toMap
import kotlin.collections.toMutableMap
import kotlin.concurrent.thread

fun main() {
    RESTServer()
}

/** Server main class. This handles the instances at a higher level and takes care of persistence. */
class RESTServer {
    private val mapper = ObjectMapper()

    // Server state
    private data class InstancesWrapper(var list: List<String> = emptyList())
    private val loadedInstances = ConcurrentHashMap<String, Unit>()
    private val instances: InstancesWrapper
    private val instanceState = ConcurrentHashMap<String, InstanceState>()
    private val instanceController = ConcurrentHashMap<String, InstanceController>()
    private val instanceChartData = ConcurrentHashMap<String, InstanceChartData>()
    data class InstanceOperationCharts(var map: Map<Int, InstanceChartData> = emptyMap())
    private val instanceOperationCharts = ConcurrentHashMap<String, InstanceOperationCharts>()

    init {
        println("Loading data...")
        File("data").mkdir()
        File("data/instances").mkdir()
        instances = loadInstanceList()

        println("Initialize api...")
        val app = Javalin.create().start(8080)
        app.get("/instances") { ctx ->
            ctx.result(getInstances().toJsonString())
        }
        app.get("/instanceState/:instance") { ctx ->
            val instance = ctx.pathParam("instance")
            ctx.result(getInstanceState(instance).toJsonString())
        }
        app.get("/instanceChartData/:instance") { ctx ->
            val instance = ctx.pathParam("instance")
            ctx.result(getInstanceChartData(instance))
        }
        app.get("/operationChartData/:instance/:operationCode") { ctx ->
            val instance = ctx.pathParam("instance")
            val operationCode = ctx.pathParam("operationCode").toInt()
            ctx.result(getInstanceOperationChartData(instance, operationCode).toJsonString())
        }
        app.get("/getInstanceVersion/:instance") { ctx ->
            val instance = ctx.pathParam("instance")
            ctx.result(getInstanceVersion(instance))
        }
        app.post("/updateInput/:instance/:button") { ctx ->
            val instance = ctx.pathParam("instance")
            val button = ctx.pathParam("button").toInt()
            val input = parseJsonMap(ctx.body())
            updateInput(instance, button, input)
            ctx.result("")
        }
        app.post("/toggleCandleState/:instance/:candleEpoch/:toggle") { ctx ->
            val instance = ctx.pathParam("instance")
            val candleEpoch = ctx.pathParam("candleEpoch").toLong()
            val toggle = ctx.pathParam("toggle").toInt()
            toggleCandleState(instance, candleEpoch, toggle)
            ctx.result("")
        }
        app.put("/createInstance/:instanceQuery") { ctx ->
            val instanceQuery = ctx.pathParam("instanceQuery")
            ctx.result(createInstance(instanceQuery))
        }
        app.delete("/deleteInstance/:instance") { ctx ->
            val instance = ctx.pathParam("instance")
            deleteInstance(instance)
            ctx.result("")
        }
    }

    private fun getInstances(): List<String> = instances.list.toList()

    private fun getInstanceState(instance: String): InstanceState {
        checkInstance(instance)
        val state = instanceState.getValue(instance)
        val controller = instanceController.getValue(instance)
        // Sanitize input. Inject new required keys to the input and drop deleted ones.
        val currentInput = state.input
        val newInput = controller.getRequiredInput().toMutableMap()
        for ((k, v) in currentInput) {
            // drop deleted variables
            if (k in newInput) newInput[k] = v
        }
        state.input = newInput
        return instanceState.getValue(instance).copy()
    }

    // This is sent compressed - big charts may take like 20MBs.
    private fun getInstanceChartData(instance: String): String {
        checkInstance(instance)
        val fullString = mapper.writeValueAsString(instanceChartData.getValue(instance).copy())
        return Base64.getEncoder().encodeToString(GZIPCompression.compress(fullString))
    }

    private fun getInstanceOperationChartData(instance: String, operationCode: Int): InstanceChartData {
        checkInstance(instance)
        val opChartData = instanceOperationCharts.getValue(instance)
        return opChartData.map.getValue(operationCode)
    }

    private fun updateInput(instance: String, button: Int, input: Map<String, String>) {
        checkInstance(instance)
        val state = instanceState.getValue(instance)
        val controller = instanceController.getValue(instance)
        state.input = input
        state.stateVersion++
        thread(start = true, isDaemon = true) {
            try {
                controller.onExecute(input, button)
            } catch (e: Exception) {
                LOGGER.info("$instance: error: $e", e)
                val logString = "\nError.\n" + StringWriter().also { sw -> e.printStackTrace(PrintWriter(sw)) }.toString()
                state.output += logString
                state.statusPositiveness = -1
                state.statusText = "Error. check output"
                state.stateVersion++
            }
            saveInstance(instance)
        }
        saveInstance(instance)
    }

    private fun createInstance(instanceQuery: String): String {
        // parse query
        val bigParts = instanceQuery.split("<")
        val query = bigParts[0]
        val copyFrom = if (bigParts.size == 2) bigParts[1] else null
        val parts = query.split(":")
        if (parts.size < 2 || parts.size > 3) error("query should be type:name<copyFrom>?")
        val instanceTypeStr = parts[0]
        val instanceType = InstanceType.valueOf(instanceTypeStr.toUpperCase())
        val instanceName = "[${instanceType.toString().toLowerCase()}]${parts[1]}"
        if (instanceState.containsKey(instanceName)) error("instance with name '$instanceName' already exists.")
        if (copyFrom != null && !instanceState.containsKey(copyFrom)) error("instance with name '$copyFrom' does not exist.")

        // build instance
        val state = InstanceState(instanceType)
        val chartData = InstanceChartData()
        val operationChartsData = InstanceOperationCharts()
        val controller = makeControllerForInstance(instanceName, state, chartData, operationChartsData)
        controller.onCreated()
        val copyFromInstance = copyFrom?.let { instanceState.getValue(it) }
        state.input = copyFromInstance?.input?.toMap() ?: controller.getRequiredInput()

        // Save
        instanceState[instanceName] = state
        instanceChartData[instanceName] = chartData
        instanceOperationCharts[instanceName] = operationChartsData
        instanceController[instanceName] = controller
        instances.list = listOf(instanceName) + instances.list // at the beginning
        loadedInstances[instanceName] = Unit
        saveInstance(instanceName)
        saveInstanceList()
        return instanceName
    }

    private fun deleteInstance(instance: String) {
        checkInstance(instance)
        instanceController.getValue(instance).onDeleted()
        instanceState.remove(instance)
        instanceChartData.remove(instance)
        instanceOperationCharts.remove(instance)
        instanceController.remove(instance)
        instances.list = instances.list - instance
        loadedInstances.remove(instance)
        deleteInstanceFiles(instance)
        saveInstanceList()
    }

    private fun getInstanceVersion(instance: String): String {
        checkInstance(instance)
        val state = instanceState.getValue(instance)
        return "${state.stateVersion}:${state.chartVersion}"
    }

    private fun toggleCandleState(
        instance: String,
        candleEpoch: Long,
        toggle: Int
    ) {
        checkInstance(instance)
        instanceController.getValue(instance).onToggleCandle(candleEpoch, toggle)
        saveInstance(instance)
    }

    private fun makeControllerForInstance(
        instanceName: String,
        state: InstanceState,
        chartData: InstanceChartData,
        operationChartsData: InstanceOperationCharts
    ): InstanceController {
        val out = object : Strategy.OutputWriter {
            override fun write(string: String) {
                LOGGER.info("$instanceName: $string")
                state.output += "$string\n"
                state.stateVersion++
            }
        }
        val result = when (state.type) {
            InstanceType.BACKTEST -> BacktestInstanceController(instanceName, state, chartData, operationChartsData, out)
            InstanceType.LIVE -> LiveInstanceController(instanceName, state, chartData, out)
            InstanceType.TRAIN -> TrainInstanceController(instanceName, state, chartData, out)
        }
        result.onLoaded()
        return result
    }

    // Persistence

    private fun loadInstanceList(): InstancesWrapper {
        return loadFrom<InstancesWrapper>("data/instances/list.json") ?: InstancesWrapper()
    }

    private fun saveInstanceList() {
        instances.saveTo("data/instances/list.json")
    }

    private fun checkInstance(instance: String) {
        if (!loadedInstances.containsKey(instance)) {
            val state = loadFrom<InstanceState>("data/instances/state-$instance.json")
                ?: error("cant read state: $instance")
            val chartData = loadFrom<InstanceChartData>("data/instances/chartData-$instance.json")
                ?: error("cant read chartData: $instance")
            val operationChartsData = loadFrom<InstanceOperationCharts>("data/instances/operationChartData-$instance.json")
                ?: InstanceOperationCharts()
            val controller = makeControllerForInstance(instance, state, chartData, operationChartsData)
            instanceState[instance] = state
            instanceChartData[instance] = chartData
            instanceController[instance] = controller
            instanceOperationCharts[instance] = operationChartsData
            loadedInstances[instance] = Unit
        }
    }

    private fun saveInstance(instance: String) {
        val state = instanceState.getValue(instance)
        val chartData = instanceChartData.getValue(instance)
        val operationChartsData = instanceOperationCharts.getValue(instance)
        state.saveTo("data/instances/state-$instance.json")
        chartData.saveTo("data/instances/chartData-$instance.json")
        operationChartsData.saveTo("data/instances/operationChartData-$instance.json")
    }

    private fun deleteInstanceFiles(instance: String) {
        Files.delete(Paths.get("data/instances/state-$instance.json"))
        Files.delete(Paths.get("data/instances/chartData-$instance.json"))
        Files.delete(Paths.get("data/instances/operationChartData-$instance.json"))
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(RESTServer::class.java)
    }
}