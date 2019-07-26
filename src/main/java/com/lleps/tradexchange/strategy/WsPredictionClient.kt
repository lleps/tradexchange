package com.lleps.tradexchange.strategy

import java.net.URI
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Used to connect to a python server.  */
class WsPredictionClient(serverURI: URI) : WebSocketClient(serverURI) {
    private val sb = StringBuilder()
    private val reqQueue = LinkedBlockingQueue<String>() // messages to send
    private val resQueue = LinkedBlockingQueue<String>() // messages received

    fun loadModel(path: String): Boolean {
        return sendRecv("load:$path") == "ok"
    }

    fun predict(data: Array<DoubleArray>): Double {
        sb.clear()
        sb.append("predict:")
        val timestampCount = data.size
        repeat(data.size) { i ->
            val featureCount = data[i].size
            repeat(data[i].size) { j ->
                sb.append(data[i][j])
                if (j < (featureCount - 1)) sb.append(",")
            }
            if (i < (timestampCount - 1)) sb.append("|")
        }
        return sendRecv(sb.toString()).toDouble()
    }

    private fun sendRecv(msg: String): String {
        reqQueue.put(msg)
        return resQueue.take()
    }

    override fun onOpen(handshakedata: ServerHandshake) {
        // wait for the first msg in the queue to be available
        val msg = reqQueue.take()
        send(msg)
    }

    override fun onMessage(message: String) {
        resQueue.offer(message)
        send(reqQueue.take())
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
    }

    override fun onError(ex: Exception) {
        throw ex
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(WsPredictionClient::class.java)
        private var instance: WsPredictionClient? = null
        private var serverStarted = false

        /** Get the client connection or start the server and make a new one. */
        @Synchronized
        fun getOrCreate(): WsPredictionClient {
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
                instance = WsPredictionClient(URI("ws://$host:$port"))
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
            println(c.loadModel("/media/lleps/Compartido/Dev/tradexchange/data/models/[train]fafafa-open.h5"))
            while (true) {
                totalPrediction += c.predict(features)
                absMsgCounter++
                secMsgCounter++
                if (System.currentTimeMillis() >= lock) {
                    println("msg/sec $secMsgCounter (abs $absMsgCounter, totalPrediction: $totalPrediction)")
                    secMsgCounter = 0
                    lock = System.currentTimeMillis() + 1000
                    totalPrediction = 0.0
                }
            }
        }
    }
}