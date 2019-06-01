package com.lleps.tradexchange

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("<mode (server/client)> host")
            return
        }

        val mode = args[0]
        if (mode == "server") {
            ServerMain.main(arrayOf())
        } else if (mode == "client") {
            ClientMain.main(arrayOf())
        }
    }
}