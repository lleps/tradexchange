package com.lleps.tradexchange.client

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lleps.tradexchange.RESTInterface
import com.mashape.unirest.http.Unirest
import java.util.ArrayList
import java.util.concurrent.Executors

class RESTClient(private val host: String = "http://localhost:8080") : RESTInterface {
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()

    /** Execute [request] in parallel, handling exceptions and default value. */
    private fun <T> makeRequest(request: () -> T, errorValue: T, resultCallback: (T, Throwable?) -> Unit) {
        executor.execute {
            var throwable: Throwable? = null
            val result = try {
                request()
            } catch (e: Throwable) {
                throwable = e
                errorValue
            }
            resultCallback(result, throwable)
        }
    }

    override fun getInstances(onResult: (List<String>, Throwable?) -> Unit) {
        makeRequest<List<String>>(
            request = {
                val response = Unirest.get("$host/instances").asString()
                if (response.status != 200) error(response.statusText)
                val listType = object : TypeToken<ArrayList<String>>() {}.type
                gson.fromJson(response.body, listType)
            },
            errorValue = emptyList(),
            resultCallback = onResult
        )
    }

    override fun getInstanceState(instance: String, onResult: (RESTInterface.InstanceState, Throwable?) -> Unit) {
        makeRequest<RESTInterface.InstanceState>(
            request = {
                val response = Unirest.get("$host/instanceState/$instance").asString()
                if (response.status != 200) error(response.statusText)
                gson.fromJson(response.body, RESTInterface.InstanceState::class.java)
            },
            errorValue = RESTInterface.InstanceState(),
            resultCallback = onResult
        )
    }

    override fun getInstanceChartData(instance: String, onResult: (RESTInterface.InstanceChartData, Throwable?) -> Unit) {
        makeRequest<RESTInterface.InstanceChartData>(
            request = {
                val response = Unirest.get("$host/instanceChartData/$instance").asString()
                if (response.status != 200) error(response.statusText)
                gson.fromJson(response.body, RESTInterface.InstanceChartData::class.java)
            },
            errorValue = RESTInterface.InstanceChartData(),
            resultCallback = onResult
        )
    }

    override fun updateInput(instance: String, input: Map<String, String>, onResult: (Unit, Throwable?) -> Unit) {
        makeRequest(
            request = {
                val response = Unirest.post("$host/updateInput/$instance")
                    .header("accept", "application/json")
                    .header("Content-Type", "application/json")
                    .body(gson.toJson(input))
                    .asString()
                if (response.status != 200) error(response.statusText)
                Unit
            },
            errorValue = Unit,
            resultCallback = onResult
        )
    }
}