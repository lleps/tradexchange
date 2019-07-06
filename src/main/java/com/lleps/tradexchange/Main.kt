package com.lleps.tradexchange

import com.lleps.tradexchange.server.RESTServer

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty() || args[0] == "--client") {
            try {
                ClientMain.main(arrayOf())
            } catch (e: Throwable) {
                println("Error executing as client: $e")
                e.printStackTrace()
                println("")
                println("Pass '--server' to run as a server.")
            }
            return
        }

        if (args[0] == "--server") {
            RESTServer()
        } else {
            println("Invalid mode: ${args[0]}. Allowed: --server or empty (aka --client)")
        }
    }
}