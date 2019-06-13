package com.lleps.tradexchange.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.lleps.tradexchange.*
import com.lleps.tradexchange.strategy.Strategy
import com.lleps.tradexchange.util.*
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread


/** Server main class. This handles the instances at a higher level and takes care of persistence. */
@RestController
class RESTServer {
    private val mapper = ObjectMapper()

    // Server state
    private data class InstancesWrapper(var list: List<String> = emptyList())
    private val loadedInstances = ConcurrentHashMap<String, Unit>()
    private val instances: InstancesWrapper
    private val instanceState = ConcurrentHashMap<String, InstanceState>()
    private val instanceController = ConcurrentHashMap<String, InstanceController>()
    private val instanceChartData = ConcurrentHashMap<String, InstanceChartData>()

    init {
        File("data").mkdir()
        File("data/instances").mkdir()
        instances = loadInstanceList()
    }

    @GetMapping("/instances")
    fun getInstances(): List<String> = instances.list.toList()

    @GetMapping("/instanceState/{instance}")
    fun getInstanceState(@PathVariable instance: String): InstanceState {
        loadInstanceIfNecessary(instance)
        return instanceState.getValue(instance).copy()
    }

    // This is sent compressed - big charts may take like 20MBs.
    @GetMapping("/instanceChartData/{instance}")
    fun getInstanceChartData(@PathVariable instance: String): String {
        loadInstanceIfNecessary(instance)
        val fullString = mapper.writeValueAsString(instanceChartData.getValue(instance).copy())
        return Base64.getEncoder().encodeToString(GZIPCompression.compress(fullString))
    }

    @PostMapping("/updateInput/{instance}/{button}")
    fun updateInput(@PathVariable instance: String, @PathVariable button: Int, @RequestBody input: Map<String, String>) {
        loadInstanceIfNecessary(instance)
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

    @PutMapping("/createInstance/{instanceQuery}")
    fun createInstance(@PathVariable instanceQuery: String): String {
        // parse query
        val parts = instanceQuery.split(":")
        if (parts.size < 2 || parts.size > 3) error("query should be <type>:<name>:<copyFrom?>")
        val instanceTypeStr = parts[0]
        val instanceType = InstanceType.valueOf(instanceTypeStr.toUpperCase())
        val instanceName = "[${instanceType.toString().toLowerCase()}]${parts[1]}"
        if (instanceState.containsKey(instanceName)) error("instance with name '$instanceName' already exists.")

        // build instance
        val state = InstanceState(instanceType)
        val chartData = InstanceChartData()
        val controller = makeControllerForInstance(instanceName, state, chartData)
        controller.onCreated()
        state.input = controller.getRequiredInput() // overwrite input, only when its created.

        // Save
        instanceState[instanceName] = state
        instanceChartData[instanceName] = chartData
        instanceController[instanceName] = controller
        instances.list = instances.list + instanceName
        loadedInstances[instanceName] = Unit
        saveInstance(instanceName)
        saveInstanceList()
        return instanceName
    }

    @DeleteMapping("/deleteInstance/{instance}")
    fun deleteInstance(@PathVariable instance: String) {
        loadInstanceIfNecessary(instance)
        instanceController.getValue(instance).onDeleted()
        instanceState.remove(instance)
        instanceChartData.remove(instance)
        instanceController.remove(instance)
        instances.list = instances.list - instance
        loadedInstances.remove(instance)
        deleteInstanceFiles(instance)
        saveInstanceList()
    }

    @GetMapping("/getInstanceVersion/{instance}")
    fun getInstanceVersion(@PathVariable instance: String): String {
        loadInstanceIfNecessary(instance)
        val state = instanceState.getValue(instance)
        return "${state.stateVersion}:${state.chartVersion}"
    }

    @PostMapping("/toggleCandleState/{instance}/{candleEpoch}/{toggle}")
    fun toggleCandleState(
        @PathVariable instance: String,
        @PathVariable candleEpoch: Long,
        @PathVariable toggle: Boolean
    ) {
        loadInstanceIfNecessary(instance)
        instanceController.getValue(instance).onToggleCandle(candleEpoch, toggle)
        saveInstance(instance)
    }

    private fun makeControllerForInstance(instanceName: String, state: InstanceState, chartData: InstanceChartData): InstanceController {
        val out = object : Strategy.OutputWriter {
            override fun write(string: String) {
                LOGGER.info("$instanceName: $string")
                state.output += "$string\n"
                state.stateVersion++
            }
        }
        val result = when (state.type) {
            InstanceType.BACKTEST -> BacktestInstanceController(instanceName, state, chartData, out)
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

    private fun loadInstanceIfNecessary(instance: String) {
        if (!loadedInstances.containsKey(instance)) {
            val state = loadFrom<InstanceState>("data/instances/state-$instance.json") ?: error("instance: $instance")
            val chartData = loadFrom<InstanceChartData>("data/instances/chartData-$instance.json") ?: error("instance: $instance")
            val controller = makeControllerForInstance(instance, state, chartData)
            instanceState[instance] = state
            instanceChartData[instance] = chartData
            instanceController[instance] = controller
            loadedInstances[instance] = Unit
        }
    }

    private fun saveInstance(instance: String) {
        val state = instanceState.getValue(instance)
        val chartData = instanceChartData.getValue(instance)
        state.saveTo("data/instances/state-$instance.json")
        chartData.saveTo("data/instances/chartData-$instance.json")
    }

    private fun deleteInstanceFiles(instance: String) {
        Files.delete(Paths.get("data/instances/state-$instance.json"))
        Files.delete(Paths.get("data/instances/chartData-$instance.json"))
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(RESTServer::class.java)
    }
}