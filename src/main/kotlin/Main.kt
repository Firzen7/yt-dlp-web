package net.firzen.web

import net.firzen.web.logging.Logger

fun main(args: Array<String>) {
    Logger.i("main()")
    if (args.isEmpty()) {
        println("Missing command. Use 'server' to start the server or 'adduser' to add a new user.")
        return
    }

    val command = args[0].lowercase()

    when (command) {
        "server" -> {
            startServer()
        }
        "adduser" -> {
            addUser()
        }
        else -> {
            println("Unknown command: $command. Use 'server' or 'adduser'.")
        }
    }
}
