package net.firzen.web

import java.io.Console
import java.io.File

/**
 * Keeps a password and its confirmation together so both character arrays can be erased after use.
 */
private data class PasswordInput(
    val value: String,
    private val passwordChars: CharArray,
    private val confirmationChars: CharArray
) {
    /**
     * Overwrites both password arrays to reduce how long sensitive values remain in memory.
     */
    fun clear() {
        passwordChars.fill(' ')
        confirmationChars.fill(' ')
    }

    /**
     * Compares password inputs by their text and by the contents of both backing arrays.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PasswordInput

        if (value != other.value) return false
        if (!passwordChars.contentEquals(other.passwordChars)) return false
        if (!confirmationChars.contentEquals(other.confirmationChars)) return false

        return true
    }

    /**
     * Produces a hash code that matches the content-based equality check.
     */
    override fun hashCode(): Int {
        var result = value.hashCode()

        result = 31 * result + passwordChars.contentHashCode()
        result = 31 * result + confirmationChars.contentHashCode()

        return result
    }
}

/**
 * Reads a new user's credentials from the console and creates the account.
 */
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

/**
 * Reads a replacement password from the console and applies it to an existing account.
 */
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

/**
 * Returns the active system console or explains why an interactive command cannot continue.
 */
private fun availableConsole(): Console? {
    val console = System.console()

    if (console == null) {
        println("Environments without console are not supported!")
    }

    return console
}

/**
 * Prompts for a username and rejects empty or reserved names.
 */
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

/**
 * Reads and validates a password together with its confirmation.
 */
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

/**
 * Overwrites every password array that was successfully read from the console.
 */
private fun clearPasswordArrays(vararg arrays: CharArray?) {
    arrays.forEach { it?.fill(' ') }
}
