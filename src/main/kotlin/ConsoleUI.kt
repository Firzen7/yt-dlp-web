package net.firzen.web

import java.io.Console
import java.io.File

private data class PasswordInput(
    val value: String,
    private val passwordChars: CharArray,
    private val confirmationChars: CharArray
) {
    fun clear() {
        passwordChars.fill(' ')
        confirmationChars.fill(' ')
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PasswordInput

        if (value != other.value) return false
        if (!passwordChars.contentEquals(other.passwordChars)) return false
        if (!confirmationChars.contentEquals(other.confirmationChars)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = value.hashCode()
        result = 31 * result + passwordChars.contentHashCode()
        result = 31 * result + confirmationChars.contentHashCode()
        return result
    }
}

fun addUser() {
    val userManager = UserManager(File(USERS_FILE))
    val console = availableConsole() ?: return
    val username = readNewUsername(console) ?: return

    val password = readConfirmedPassword(
        console,
        "Enter password: ",
        "Confirm password: "
    ) ?: return

    try {
        userManager.createUser(username, password.value)
        println("User $username created successfully.")
    } catch (e: Exception) {
        println("Failed to create user: ${e.message}")
    } finally {
        password.clear()
    }
}

fun changePassword(username: String) {
    val userManager = UserManager(File(USERS_FILE))
    if (!userManager.userExists(username)) {
        println("Error: User '$username' does not exist.")
        return
    }

    val console = availableConsole() ?: return
    val password = readConfirmedPassword(
        console,
        "Enter new password: ",
        "Confirm new password: "
    ) ?: return

    try {
        userManager.changePassword(username, password.value)
        println("Password for user $username changed successfully.")
    } catch (e: Exception) {
        println("Failed to change password: ${e.message}")
    } finally {
        password.clear()
    }
}

private fun availableConsole(): Console? {
    val console = System.console()

    if (console == null) {
        println("Environments without console are not supported!")
    }

    return console
}

private fun readNewUsername(console: Console): String? {
    val username = console.readLine("Enter username: ")

    if (username.isNullOrBlank()) {
        println("Username cannot be empty")
        return null
    }

    if (username == UNKNOWN_USER) {
        println("This username is not allowed")
        return null
    }

    return username
}

private fun readConfirmedPassword(
    console: Console,
    prompt: String,
    confirmationPrompt: String
): PasswordInput? {
    val password = console.readPassword(prompt)
    val confirmation = console.readPassword(confirmationPrompt)

    if (password == null || confirmation == null) {
        clearPasswordArrays(password, confirmation)
        println("Password reading failed")
        return null
    }

    if (!password.contentEquals(confirmation)) {
        clearPasswordArrays(password, confirmation)
        println("Passwords do not match!")
        return null
    }

    if (password.isEmpty()) {
        clearPasswordArrays(password, confirmation)
        println("Password cannot be empty")
        return null
    }

    return PasswordInput(String(password), password, confirmation)
}

private fun clearPasswordArrays(vararg arrays: CharArray?) {
    arrays.forEach { it?.fill(' ') }
}
