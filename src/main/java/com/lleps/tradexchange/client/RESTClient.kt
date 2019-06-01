package com.lleps.tradexchange.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.lleps.tradexchange.InstanceChartData
import com.lleps.tradexchange.InstanceState
import com.lleps.tradexchange.RESTInterface
import com.mashape.unirest.http.HttpResponse
import com.mashape.unirest.http.Unirest
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.Executors

class RESTClient(private val host: String = "http://localhost:8080") : RESTInterface {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(RESTClient::class.java)
    }

    private val jacksonMapper = ObjectMapper()
    private val executor = Executors.newSingleThreadExecutor()

    /** Execute [request] in parallel, handling exceptions and default value. */
    private fun <T> makeRequest(request: () -> T, errorValue: T, resultCallback: (T, Throwable?) -> Unit) {
        executor.execute {
            var throwable: Throwable? = null
            val result = try {
                request()
            } catch (e: Throwable) {
                LOGGER.error("error in makeRequest", e)
                throwable = e
                errorValue
            }
            resultCallback(result, throwable)
        }
    }

    private fun <T> checkResponse(response: HttpResponse<T>) {
        if (response.status != 200)
            error("error in request: ${response.status} (${response.statusText}) ${response.body}")
    }

    override fun getInstances(onResult: (List<String>, Throwable?) -> Unit) {
        makeRequest<List<String>>(
            request = {
                val response = Unirest.get("$host/instances").asString()
                checkResponse(response)
                val typeRef = object : TypeReference<ArrayList<String>>() {}
                jacksonMapper.readValue(response.body, typeRef)
            },
            errorValue = emptyList(),
            resultCallback = onResult
        )
    }

    override fun getInstanceState(instance: String, onResult: (InstanceState, Throwable?) -> Unit) {
        makeRequest<InstanceState>(
            request = {
                val response = Unirest.get("$host/instanceState/$instance").asString()
                checkResponse(response)
                jacksonMapper.readValue(response.body, InstanceState::class.java)
            },
            errorValue = InstanceState(),
            resultCallback = onResult
        )
    }

    override fun getInstanceChartData(instance: String, onResult: (InstanceChartData, Throwable?) -> Unit) {
        makeRequest<InstanceChartData>(
            request = {
                val response = Unirest.get("$host/instanceChartData/$instance").asString()
                checkResponse(response)
                // Gson doesn't deserialize this properly, creates map<string,double> instead of map<long,double>
                // So use jackson here.
                jacksonMapper.readValue(response.body, InstanceChartData::class.java)
            },
            errorValue = InstanceChartData(),
            resultCallback = onResult
        )
    }

    override fun updateInput(instance: String, input: Map<String, String>, onResult: (Unit, Throwable?) -> Unit) {
        makeRequest(
            request = {
                val response = Unirest.post("$host/updateInput/$instance")
                    .header("accept", "application/json")
                    .header("Content-Type", "application/json")
                    .body(jacksonMapper.writeValueAsString(input))
                    .asString()
                checkResponse(response)
                Unit
            },
            errorValue = Unit,
            resultCallback = onResult
        )
    }

    override fun createInstance(instance: String, onResult: (Unit, Throwable?) -> Unit) {
        makeRequest(
            request = {
                val response = Unirest.put("$host/createInstance/$instance").asString()
                checkResponse(response)
                Unit
            },
            errorValue = Unit,
            resultCallback = onResult
        )
    }

    override fun deleteInstance(instance: String, onResult: (Unit, Throwable?) -> Unit) {
        makeRequest(
            request = {
                val response = Unirest.delete("$host/deleteInstance/$instance").asString()
                checkResponse(response)
                Unit
            },
            errorValue = Unit,
            resultCallback = onResult
        )
    }
}