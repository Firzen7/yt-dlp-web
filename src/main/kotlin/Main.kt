package net.firzen.web

import net.firzen.web.logging.Logger

/**
 * Dispatches the command-line request to the server or user-management command.
 */
fun main(args: Array<String>) {
    Logger.i("main()")

    if (args.isEmpty()) {
        println("Missing command. Use 'server' to start the server, 'adduser' to add a new user, or 'passwd <username>' to change a user's password.")
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
        "passwd" -> {
            if (args.size < 2) {
                println("Missing username. Usage: passwd <username>")
            } else {
                changePassword(args[1])
            }
        }
        else -> {
            println("Unknown command: $command. Use 'server', 'adduser', or 'passwd <username>'.")
        }
    }
}
