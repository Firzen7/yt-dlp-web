package net.firzen.web

import java.io.File

fun main(args: Array<String>) {
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
            val userManager = UserManager(File(USERS_FILE))
            val console = System.console()

            if (console != null) {
                val username = console.readLine("Enter username: ")
                if (username.isNullOrBlank()) {
                    println("Username cannot be empty")
                    return
                }

                val passwordArray = console.readPassword("Enter password: ")
                val passwordArrayConfirm = console.readPassword("Confirm password: ")

                if (passwordArray == null || passwordArrayConfirm == null) {
                    println("Password reading failed")
                    return
                }

                val password = String(passwordArray)
                val passwordConfirm = String(passwordArrayConfirm)

                if (password != passwordConfirm) {
                    println("Passwords do not match!")
                    return
                }

                if (password.isEmpty()) {
                    println("Password cannot be empty")
                    return
                }

                try {
                    userManager.createUser(username, password)
                    println("User $username created successfully.")
                } catch (e: Exception) {
                    println("Failed to create user: ${e.message}")
                }

                // Clear passwords from memory
                passwordArray.fill(' ')
                passwordArrayConfirm.fill(' ')
            } else {
                // Fallback for execution environments without a real console (like Gradle)
                println("Console not available. Password input will NOT be hidden.")
                print("Enter username: ")
                val username = readlnOrNull()
                if (username.isNullOrBlank()) {
                    println("Username cannot be empty")
                    return
                }

                print("Enter password: ")
                val password = readlnOrNull()

                print("Confirm password: ")
                val passwordConfirm = readlnOrNull()

                if (password == null || passwordConfirm == null || password != passwordConfirm) {
                    println("Passwords do not match or are empty")
                    return
                }

                try {
                    userManager.createUser(username, password)
                    println("User '$username' created successfully.")
                } catch (e: Exception) {
                    println("Failed to create user: ${e.message}")
                }
            }
        }
        else -> {
            println("Unknown command: $command. Use 'server' or 'adduser'.")
        }
    }
}
