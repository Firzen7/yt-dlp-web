package net.firzen.web

import java.io.File

fun addUser() {
    val userManager = UserManager(File(USERS_FILE))
    val console = System.console()

    if (console != null) {
        val username = console.readLine("Enter username: ")
        if (username.isNullOrBlank()) {
            println("Username cannot be empty")
            return
        }

        if (username == UNKNOWN_USER) {
            println("This username is not allowed")
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
    }
    else {
        println("Environments without console are not supported!")
    }
}
