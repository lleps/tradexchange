package com.lleps.tradexchange.strategy

import java.net.URI
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Used to connect to a python tensorflow server through websockets to train and predict with models.  */
class TensorflowClient(serverURI: URI) : WebSocketClient(serverURI) {
    private val sb = StringBuilder()
    private val resQueue = LinkedBlockingQueue<String>() // messages received

    fun requestInitTrain(trainCsvPath: String, timesteps: Int) {
        val result = sendRecv("train_init:$trainCsvPath,$timesteps")
        if (result.startsWith("error:")) {
            error("tf server error: ${result.split(":", limit = 1)[1]}")
        }
    }

    fun requestDoTrain(epochs: Int, batchSize: Int): String {
        val result = sendRecv("train_fit:$epochs,$batchSize")
        if (result.startsWith("error:")) {
            error("tf server error: ${result.split(":", limit = 1)[1]}")
        }
        return result
    }

    fun requestSaveTrain(path: String) {
        val result = sendRecv("train_save:$path")
        if (result.startsWith("error:")) {
            error("tf server error: ${result.split(":", limit = 1)[1]}")
        }
    }

    fun requestLoadBuyModel(path: String): Boolean = sendRecv("buy_load:$path") == "ok"

    fun requestLoadSellModel(path: String): Boolean = sendRecv("sell_load:$path") == "ok"

    fun requestBuyPrediction(data: Array<DoubleArray>) = requestPrediction("buy_predict:", data)

    fun requestSellPrediction(data: Array<DoubleArray>) = requestPrediction("sell_predict:", data)

    private fun requestPrediction(prefix: String, data: Array<DoubleArray>): Double {
        sb.clear()
        sb.append(prefix)
        val timestampCount = data.size
        repeat(timestampCount) { i ->
            val featureCount = data[i].size
            repeat(featureCount) { j ->
                sb.append(data[i][j])
                if (j < (featureCount - 1)) sb.append(",")
            }
            if (i < (timestampCount - 1)) sb.append("|")
        }
        return sendRecv(sb.toString()).toDouble()
    }

    private fun sendRecv(msg: String): String {
        send(msg)
        return resQueue.take()
    }

    override fun onOpen(handshakedata: ServerHandshake) {
    }

    override fun onMessage(message: String) {
        resQueue.put(message)
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
    }

    override fun onError(ex: Exception) {
        throw ex
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(TensorflowClient::class.java)
        private var instance: TensorflowClient? = null
        private var serverStarted = false
        private var outputCallback: (String) -> Unit = { }

        fun setServerOutputCallback(callback: (String) -> Unit) {
            outputCallback = callback
        }

        /** Get the client connection or start the server and make a new one. */
        @Synchronized
        fun getOrCreate(): TensorflowClient {
            if (instance == null) {
                val host = "localhost"
                val port = "8081"

                // create a thread with the server process attached
                if (!serverStarted) {
                    thread {
                        LOGGER.info("Initialize prediction server process")
                        val process = ProcessBuilder()
                            .command("model/venv/bin/python", "model/predictionserver.py", host, port)
                            .redirectErrorStream(true)
                            .start()

                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        LOGGER.info("Waiting for the first output line...")

                        // wait for the first line so we're sure the process is ready
                        while (true) {
                            val line = reader.readLine() ?: break
                            LOGGER.info("predictionserver.py: $line")
                            outputCallback(line)
                            if (!serverStarted) {
                                serverStarted = true
                                LOGGER.info("now proceed.")
                            }
                        }
                        LOGGER.info("prediction server exit code: ${process.exitValue()}")
                        process.destroy()
                    }
                }

                // Wait til the server process starts
                while (!serverStarted) Thread.sleep(100)

                // connect
                LOGGER.info("Connect to $host:$port...")
                instance = TensorflowClient(URI("ws://$host:$port"))
                instance!!.connect()
                LOGGER.info("connected. all ok")
            }
            return instance!!
        }

        // Used to test the throughput
        @JvmStatic
        fun main(args: Array<String>) {
            val c = getOrCreate()
            var absMsgCounter = 0
            var secMsgCounter = 0
            var lock = System.currentTimeMillis() + 1000
            var totalPrediction = 0.0
            val features = arrayOf(
                doubleArrayOf(0.9, 0.2),
                doubleArrayOf(0.4, 0.2),
                doubleArrayOf(0.01, 0.19)
            )
            println(c.requestLoadSellModel("/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.pb"))
            println(c.requestLoadBuyModel("/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.pb"))
            var counter = 0
            while (true) {
                val prediction1 = c.requestBuyPrediction(features)
                //val prediction2 = c.requestSellPrediction(features)
                absMsgCounter++
                secMsgCounter++
                if (System.currentTimeMillis() >= lock) {
                    println("msg/sec $secMsgCounter (abs $absMsgCounter, totalPrediction: $totalPrediction)")
                    secMsgCounter = 0
                    lock = System.currentTimeMillis() + 1000
                    totalPrediction = 0.0
                    if (++counter % 100 == 0) {
                        println("reload...")
                        println(c.requestLoadSellModel("/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.pb"))
                        println(c.requestLoadBuyModel("/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.pb"))
                    }
                }
            }
        }
    }
}