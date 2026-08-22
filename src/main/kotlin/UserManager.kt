package net.firzen.web

import org.bouncycastle.crypto.generators.SCrypt
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages user credentials stored in a file.
 *
 * File format (Werkzeug-compatible):
 *   username:scrypt:N:r:p$salt$hex_derived_key
 *
 * Example:
 *   admin:scrypt:32768:8:1$7tV3rzHzyFEf7IEC$82f747...
 */
class UserManager(private val usersFile: File) {

    /**
     * Defines the scrypt parameters and byte lengths used for stored credentials.
     */
    companion object {
        // Scrypt parameters matching Werkzeug defaults
        private const val SCRYPT_N = 32768
        private const val SCRYPT_R = 8
        private const val SCRYPT_P = 1
        private const val KEY_LENGTH = 64 // bytes

        private const val SALT_LENGTH = 16 // bytes
    }

    private val lock = Any()

    /**
     * Creates a new user with the given username and password.
     * The password is hashed using scrypt in a format compatible with Werkzeug.
     *
     * @throws IllegalArgumentException if the username already exists or is invalid
     */
    fun createUser(username: String, password: String) {
        require(username.isNotBlank()) { "Username must not be blank" }
        require(!username.contains(':')) { "Username must not contain ':'" }
        require(password.isNotEmpty()) { "Password must not be empty" }

        synchronized(lock) {
            if (userExists(username)) {
                throw IllegalArgumentException("User '$username' already exists")
            }

            val salt = generateSalt()
            val hash = hashPassword(password, salt)
            val saltEncoded = String(salt, Charsets.UTF_8)
            val hashHex = hash.toHexString()

            val line = "$username:scrypt:$SCRYPT_N:$SCRYPT_R:$SCRYPT_P\$$saltEncoded\$$hashHex"

            usersFile.appendText("$line\n")
        }
    }

    /**
     * Changes the password for the given user.
     * The password is hashed using scrypt in a format compatible with Werkzeug.
     *
     * @throws IllegalArgumentException if the user does not exist or if the new password is empty
     */
    fun changePassword(username: String, newPassword: String) {
        require(username.isNotBlank()) { "Username must not be blank" }
        require(newPassword.isNotEmpty()) { "New password must not be empty" }

        synchronized(lock) {
            if (!userExists(username)) {
                throw IllegalArgumentException("User '$username' does not exist")
            }

            val salt = generateSalt()
            val hash = hashPassword(newPassword, salt)
            val saltEncoded = String(salt, Charsets.UTF_8)
            val hashHex = hash.toHexString()

            val newEntryLine = "$username:scrypt:$SCRYPT_N:$SCRYPT_R:$SCRYPT_P\$$saltEncoded\$$hashHex"

            val lines = if (usersFile.exists()) usersFile.readLines() else emptyList()
            val updatedLines = lines.map { line ->
                val colonIndex = line.indexOf(':')
                if (colonIndex >= 0 && line.substring(0, colonIndex) == username) {
                    newEntryLine
                } else {
                    line
                }
            }

            usersFile.writeText(updatedLines.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * Changes the password for the given user after validating the current password.
     *
     * @throws IllegalArgumentException if the user does not exist, the current password is incorrect,
     *                                  or if the new password is empty
     */
    fun changePassword(username: String, oldPassword: String, newPassword: String) {
        require(username.isNotBlank()) { "Username must not be blank" }
        require(newPassword.isNotEmpty()) { "New password must not be empty" }

        synchronized(lock) {
            if (!validateUser(username, oldPassword)) {
                throw IllegalArgumentException("Nesprávné aktuální heslo.")
            }

            val salt = generateSalt()
            val hash = hashPassword(newPassword, salt)
            val saltEncoded = String(salt, Charsets.UTF_8)
            val hashHex = hash.toHexString()

            val newEntryLine = "$username:scrypt:$SCRYPT_N:$SCRYPT_R:$SCRYPT_P\$$saltEncoded\$$hashHex"

            val lines = if (usersFile.exists()) usersFile.readLines() else emptyList()
            val updatedLines = lines.map { line ->
                val colonIndex = line.indexOf(':')
                if (colonIndex >= 0 && line.substring(0, colonIndex) == username) {
                    newEntryLine
                } else {
                    line
                }
            }

            usersFile.writeText(updatedLines.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * Validates the provided credentials against the stored users.
     *
     * @return true if the username exists and the password matches
     */
    fun validateUser(username: String, password: String): Boolean {
        val entries = readEntries()
        val entry = entries.find { it.username == username } ?: return false

        val derivedKey = SCrypt.generate(
            password.toByteArray(Charsets.UTF_8),
            entry.salt.toByteArray(Charsets.UTF_8),
            entry.n, entry.r, entry.p,
            KEY_LENGTH
        )

        return MessageDigest.isEqual(derivedKey, entry.keyBytes)
    }

    /**
     * Checks if a user with the given username exists.
     */
    fun userExists(username: String): Boolean {
        return readEntries().any { it.username == username }
    }

    /**
     * Returns all distinct usernames in a stable alphabetical order.
     */
    fun listUsers(): List<String> {
        return synchronized(lock) {
            readEntries().map { it.username }.distinct().sorted()
        }
    }

    /**
     * Removes the given user's credential record from the users file.
     *
     * @throws IllegalArgumentException if the username is blank or does not exist
     */
    fun deleteUser(username: String) {
        require(username.isNotBlank()) { "Username must not be blank" }

        synchronized(lock) {
            if (!userExists(username)) {
                throw IllegalArgumentException("User '$username' does not exist")
            }

            val remainingLines = usersFile.readLines().filterNot { line ->
                val separatorIndex = line.indexOf(':')
                separatorIndex >= 0 && line.substring(0, separatorIndex) == username
            }
            val content = remainingLines.joinToString("\n")

            usersFile.writeText(if (content.isEmpty()) "" else "$content\n")
        }
    }

    // -- Internal helpers --

    /**
     * Generates a random URL-safe salt compatible with Werkzeug password hashes.
     */
    private fun generateSalt(): ByteArray {
        // Generate random bytes and encode as base64url-safe characters (matching Werkzeug)
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()

        return ByteArray(SALT_LENGTH) { chars[random.nextInt(chars.length)].code.toByte() }
    }

    /**
     * Derives a fixed-length scrypt key from a password and salt.
     */
    private fun hashPassword(password: String, salt: ByteArray): ByteArray {
        return SCrypt.generate(
            password.toByteArray(Charsets.UTF_8),
            salt,
            SCRYPT_N, SCRYPT_R, SCRYPT_P,
            KEY_LENGTH
        )
    }

    /**
     * Reads all valid credential records while ignoring blank or malformed lines.
     */
    private fun readEntries(): List<UserEntry> {
        if (!usersFile.exists()) return emptyList()

        return usersFile.readLines()
            .filter { it.isNotBlank() && it.contains(':') }
            .mapNotNull { parseEntry(it) }
    }

    /**
     * Parses a line like:
     *   admin:scrypt:32768:8:1$7tV3rzHzyFEf7IEC$82f747...
     *
     * Format: username:scrypt:N:r:p$salt$hex_key
     */
    private fun parseEntry(line: String): UserEntry? {
        // Split on first ':' to get username and the rest
        val colonIndex = line.indexOf(':')
        if (colonIndex < 0) return null

        val username = line.substring(0, colonIndex)
        val hashPart = line.substring(colonIndex + 1) // e.g. "scrypt:32768:8:1$salt$key"

        // The hash part has the format: scrypt:N:r:p$salt$key
        // Split by '$' to get [scrypt:N:r:p, salt, key]
        val dollarParts = hashPart.split('$')
        if (dollarParts.size != 3) return null

        val params = dollarParts[0]  // "scrypt:32768:8:1"
        val salt = dollarParts[1]
        val hexKey = dollarParts[2]

        // Parse scrypt parameters
        val paramParts = params.split(':')
        if (paramParts.size != 4 || paramParts[0] != "scrypt") return null

        val n = paramParts[1].toIntOrNull() ?: return null
        val r = paramParts[2].toIntOrNull() ?: return null
        val p = paramParts[3].toIntOrNull() ?: return null

        val keyBytes = hexKey.hexToByteArray()

        return UserEntry(username, n, r, p, salt, keyBytes)
    }

    /**
     * Computes the Shannon entropy of a password based on the size of the character pool
     * detected (similar to how KeePassXC calculates entropy for generated/manual character-class combinations).
     */
    fun computeEntropy(password: String): Float {
        if (password.isEmpty()) return 0.0f

        var poolSize = 0
        if (password.any { it.isLowerCase() }) poolSize += 26
        if (password.any { it.isUpperCase() }) poolSize += 26
        if (password.any { it.isDigit() }) poolSize += 10
        if (password.any { !it.isLetterOrDigit() }) poolSize += 32 // Standard symbols set size

        if (poolSize == 0) poolSize = 1 // Prevent log(0)

        val entropy = password.length * (Math.log(poolSize.toDouble()) / Math.log(2.0))
        return entropy.toFloat()
    }

    /**
     * Represents one parsed scrypt credential record from the users file.
     */
    private data class UserEntry(
        val username: String,
        val n: Int,
        val r: Int,
        val p: Int,
        val salt: String,
        val keyBytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as UserEntry

            if (n != other.n) return false
            if (r != other.r) return false
            if (p != other.p) return false
            if (username != other.username) return false
            if (salt != other.salt) return false
            if (!keyBytes.contentEquals(other.keyBytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = n
            result = 31 * result + r
            result = 31 * result + p
            result = 31 * result + username.hashCode()
            result = 31 * result + salt.hashCode()
            result = 31 * result + keyBytes.contentHashCode()
            return result
        }
    }

    /**
     * Encodes this byte array as lowercase hexadecimal text.
     */
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    /**
     * Decodes an even-length hexadecimal string into bytes.
     */
    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }

        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
