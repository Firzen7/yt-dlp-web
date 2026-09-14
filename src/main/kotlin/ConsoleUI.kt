package net.firzen.web

import net.firzen.web.tools.ACTIVE_CONFIG_FILE
import net.firzen.web.tools.LOG_DIRECTORY
import net.firzen.web.tools.SESSION_DIRECTORY
import net.firzen.web.tools.UNKNOWN_USER
import net.firzen.web.tools.USERS_FILE
import java.io.Console
import java.io.File

/**
 * Keeps a password and its confirmation together so both character arrays can be erased after use.
 *
 * @param value password represented as text for credential operations
 * @param passwordChars characters read from the first password prompt
 * @param confirmationChars characters read from the confirmation prompt
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
     *
     * @param other object to compare with this password input
     * @return `true` when all values and arrays contain equal data
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
     *
     * @return content-based hash code for this password input
     */
    override fun hashCode(): Int {
        var result = value.hashCode()

        result = 31 * result + passwordChars.contentHashCode()
        result = 31 * result + confirmationChars.contentHashCode()

        return result
    }
}

/**
 * Reads a new user's password and prompts for the username when none was supplied.
 *
 * @param suppliedUsername username supplied on the command line, or `null` to prompt for one
 */
fun addUser(suppliedUsername: String? = null) {
    val userManager = UserManager(File(USERS_FILE))
    val console = availableConsole() ?: return
    val username = resolveNewUsername(console, suppliedUsername) ?: return

    if (userManager.userExists(username)) {
        println("Error: User '$username' already exists.")
        return
    }

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
 * Uses a supplied username or reads one interactively when it was omitted.
 *
 * @param console terminal used to prompt for a missing username
 * @param suppliedUsername optional username supplied on the command line
 * @return validated username, or `null` when validation fails
 */
private fun resolveNewUsername(console: Console, suppliedUsername: String?): String? {
    if (suppliedUsername == null) {
        return readNewUsername(console)
    }

    val username = validateNewUsername(suppliedUsername) ?: return null
    println("Creating new user: $username")

    return username
}

/**
 * Reads a replacement password from the console and applies it to an existing account.
 *
 * @param username user whose password should be replaced
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
 * Prints every existing username in a compact terminal list.
 */
fun listUsers() {
    val userManager = UserManager(File(USERS_FILE))

    try {
        val users = userManager.listUsers()

        if (users.isEmpty()) {
            println("No users found.")
            return
        }

        println("Users (${users.size}):")
        users.forEach { println("  $it") }
    } catch (e: Exception) {
        println("Failed to list users: ${e.message}")
    }
}

/**
 * Prints the active configuration file location followed by its contents.
 */
fun printConfiguration() {
    val configFile = File(ACTIVE_CONFIG_FILE)

    try {
        val output = configurationText(configFile)

        print(output)
        if (!output.endsWith('\n')) println()
    } catch (e: Exception) {
        println("Failed to read configuration: ${e.message}")
    }
}

/**
 * Formats a configuration file's absolute location and unmodified contents.
 *
 * @param configFile configuration file to describe
 * @return printable configuration location and contents
 * @throws java.io.IOException when the configuration file cannot be read
 */
internal fun configurationText(configFile: File): String {
    return buildString {
        appendLine("Configuration file: ${configFile.absolutePath}")
        appendLine()
        append(configFile.readText())
    }
}

/**
 * Deletes the requested user and reports the result to the terminal.
 *
 * @param username user to delete after confirmation
 */
fun deleteUser(username: String) {
    val userManager = UserManager(File(USERS_FILE))

    if (!userManager.userExists(username)) {
        println("Error: User '$username' does not exist.")
        return
    }

    val console = availableConsole() ?: return
    if (!confirmUserDeletion(console, username)) return
    val deleteLogs = confirmLogDeletion(console, username)

    try {
        userManager.deleteUser(username)

        deleteUserSessions(username)
        if (deleteLogs) deleteUserLog(username)

        println("User $username deleted successfully.")
    } catch (e: Exception) {
        println("Failed to delete user: ${e.message}")
        return
    }
}

/**
 * Invalidates every server-side session belonging to a deleted user.
 *
 * @param username deleted user whose sessions should be removed
 */
private fun deleteUserSessions(username: String) {
    val sessionFile = File(SESSION_DIRECTORY, "$username.sessions")

    try {
        if (sessionFile.exists() && !sessionFile.delete()) {
            println("Warning: Failed to invalidate sessions for user $username.")
        }
    } catch (e: Exception) {
        println("Warning: Failed to invalidate sessions for user $username: ${e.message}")
    }
}

/**
 * Asks for explicit confirmation before deleting the named user.
 *
 * @param console terminal used to read confirmation
 * @param username user whose deletion should be confirmed
 * @return `true` only when the user explicitly confirms deletion
 */
private fun confirmUserDeletion(console: Console, username: String): Boolean {
    val response = console.readLine(
        "Are you sure you want to delete user '%s'? [y/N]: ",
        username
    )
    val confirmed = isDeletionConfirmed(response)

    if (!confirmed) println("User deletion cancelled.")

    return confirmed
}

/**
 * Asks whether the named user's persistent log file should also be removed.
 *
 * @param console terminal used to read confirmation
 * @param username user whose log deletion should be confirmed
 * @return `true` only when log deletion is explicitly confirmed
 */
private fun confirmLogDeletion(console: Console, username: String): Boolean {
    val response = console.readLine(
        "Delete logs for user '%s' too? [y/N]: ",
        username
    )

    return isDeletionConfirmed(response)
}

/**
 * Removes the named user's persistent log file and reports the result.
 *
 * @param username user whose log file should be removed
 */
private fun deleteUserLog(username: String) {
    val logFile = File(LOG_DIRECTORY, "$username.log")

    try {
        when {
            !logFile.exists() -> println("No logs found for user $username.")
            logFile.delete() -> println("Logs for user $username deleted successfully.")
            else -> println("Failed to delete logs for user $username.")
        }
    } catch (e: Exception) {
        println("Failed to delete logs for user $username: ${e.message}")
    }
}

/**
 * Reports whether a terminal response explicitly confirms user deletion.
 *
 * @param response terminal response to evaluate, or `null` when no response was read
 * @return `true` for `y` or `yes`, ignoring case; otherwise `false`
 */
internal fun isDeletionConfirmed(response: String?): Boolean {
    return response.equals("y", ignoreCase = true) ||
        response.equals("yes", ignoreCase = true)
}

/**
 * Returns the active system console or explains why an interactive command cannot continue.
 *
 * @return active system console, or `null` when no console is attached
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
 *
 * @param console terminal used to read the username
 * @return validated username, or `null` when validation fails
 */
private fun readNewUsername(console: Console): String? {
    val username = console.readLine("Enter username: ")

    return validateNewUsername(username)
}

/**
 * Rejects usernames that cannot be stored or used by the application.
 *
 * @param username candidate username, or `null` when input failed
 * @return validated username, or `null` when it is unsupported
 */
private fun validateNewUsername(username: String?): String? {
    if (username.isNullOrBlank()) {
        println("Username cannot be empty")
        return null
    }

    if (!UserManager.isValidUsername(username)) {
        println("Username may only contain letters and digits")
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
 *
 * @param console terminal used to read hidden password input
 * @param prompt text displayed before the password field
 * @param confirmationPrompt text displayed before the confirmation field
 * @return validated password input, or `null` when reading or validation fails
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
 *
 * @param arrays password arrays to overwrite; `null` entries are ignored
 */
private fun clearPasswordArrays(vararg arrays: CharArray?) {
    arrays.forEach { it?.fill(' ') }
}
