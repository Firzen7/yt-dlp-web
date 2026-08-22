package net.firzen.web

import net.firzen.web.logging.Logger
import java.io.File

private const val DEVELOPMENT_JAR_NAME = "yt-dlp-web.jar"

/**
 * Dispatches the command-line request to the server or user-management command.
 */
fun main(args: Array<String>) {
    Logger.i("main()")

    if (args.isEmpty()) {
        printUsageError("Missing command.")
        return
    }

    dispatchCommand(args)
}

/**
 * Runs the command represented by the supplied command-line arguments.
 */
private fun dispatchCommand(args: Array<String>) {
    when (val command = args.first().lowercase()) {
        "server" -> startServer()
        "adduser" -> addUser()
        "passwd" -> runUsernameCommand(args, "passwd", ::changePassword)
        "listusers" -> listUsers()
        "deluser" -> runUsernameCommand(args, "deluser", ::deleteUser)
        "--help", "-h", "help" -> printUsage()
        else -> printUsageError("Unknown command: $command")
    }
}

/**
 * Runs a user command or reports that its required username is missing.
 */
private fun runUsernameCommand(
    args: Array<String>,
    command: String,
    action: (String) -> Unit
) {
    val username = args.getOrNull(1)

    if (username == null) {
        printUsageError("Missing username. Usage: $command <username>")
    } else {
        action(username)
    }
}

/**
 * Prints an error followed by the complete command-line usage text.
 */
private fun printUsageError(message: String) {
    println(message)
    println()
    printUsage()
}

/**
 * Prints structured command-line usage information.
 */
private fun printUsage() {
    println(usageText())
}

/**
 * Returns the complete command-line help text.
 */
internal fun usageText(jarName: String = currentJarName()): String {
    return """
        Usage:
          java -jar $jarName <command> [arguments]

        Commands:
          server                   Start the embedded web server
          adduser                  Interactively create a user
          passwd <username>        Interactively change a user's password
          listusers                List all existing users
          deluser <username>       Delete a user
          --help, -h, help         Show this help
    """.trimIndent()
}

/**
 * Returns the running JAR's filename or a development fallback outside a packaged JAR.
 */
private fun currentJarName(): String {
    return try {
        val location = UserManager::class.java.protectionDomain.codeSource.location
        val applicationFile = File(location.toURI())

        if (applicationFile.isFile && applicationFile.extension.equals("jar", ignoreCase = true)) {
            applicationFile.name
        } else {
            DEVELOPMENT_JAR_NAME
        }
    } catch (_: Exception) {
        DEVELOPMENT_JAR_NAME
    }
}
