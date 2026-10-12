package net.firzen.web.tools

import com.natpryce.konfig.ConfigurationProperties
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import com.natpryce.konfig.EnvironmentVariables
import com.natpryce.konfig.Key
import com.natpryce.konfig.intType
import com.natpryce.konfig.longType
import com.natpryce.konfig.overriding
import com.natpryce.konfig.stringType
import net.firzen.web.UserManager
import java.io.File

private const val DEFAULT_LOGIN_SESSION_LENGTH = 30 * 24 * 60 * 60L
private const val DEFAULT_MINIMUM_PASSWORD_ENTROPY = 32

// Path of the configuration file selected for this application process.
val ACTIVE_CONFIG_FILE = availableConfigPath()

private val conf = systemProperties() overriding
        EnvironmentVariables() overriding
        ConfigurationProperties.fromFile(File(ACTIVE_CONFIG_FILE))

// Configuration file in the application's working directory.
const val CONFIG_FILE = "./config.conf"
// Classpath resource containing defaults for new configuration files.
const val DEFAULTS_CONF_FILE = "defaults.conf"
// Port on which the HTTP server accepts connections.
val SERVER_PORT = conf[Key("server.port", intType)]
// Directory where completed downloads are stored.
val DOWNLOAD_DIRECTORY = conf[Key("fs.download_directory", stringType)]
// Directory where per-user action logs are stored.
val LOG_DIRECTORY = conf[Key("fs.log_directory", stringType)]
// Directory where server-side login sessions are stored.
val SESSION_DIRECTORY = try {
    conf[Key("fs.session_directory", stringType)]
} catch (_: Exception) {
    "./sessions"
}
// JavaScript runtime identifier passed to yt-dlp.
val JS_RUNTIME_TYPE = conf[Key("fs.js_runtime_type", stringType)]
// Filesystem path to the JavaScript runtime executable.
val JS_RUNTIME_PATH = conf[Key("fs.js_runtime_path", stringType)]
// Maximum number of seconds a child process may run.
val PROCESS_TIMEOUT = conf[Key("process.timeout", longType)]

// Path to the user credential file, with a local fallback for older configurations.
val USERS_FILE = try {
    conf[Key("auth.users_file", stringType)]
} catch (_: Exception) {
    "./users.conf"
}

// Lifetime of an authenticated browser session in seconds.
val LOGIN_SESSION_LENGTH = try {
    conf[Key("auth.login_session_length", longType)]
} catch (_: Exception) {
    DEFAULT_LOGIN_SESSION_LENGTH
}

// Minimum estimated entropy accepted for a new password.
val MINIMUM_PASSWORD_ENTROPY = try {
    conf[Key("auth.minimum_password_entropy", intType)]
        .takeIf { it > 0 } ?: DEFAULT_MINIMUM_PASSWORD_ENTROPY
} catch (_: Exception) {
    DEFAULT_MINIMUM_PASSWORD_ENTROPY
}

/**
 * Selects the working-directory configuration or creates it from bundled defaults.
 *
 * @return path of the configuration file that should be loaded
 * @throws java.io.IOException when the default configuration cannot be copied
 * @throws IllegalStateException when the bundled defaults resource is missing
 */
private fun availableConfigPath(): String {
    val configFile = File(CONFIG_FILE)

    if (!configFile.exists()) {
        val defaults = checkNotNull(
            UserManager::class.java.classLoader.getResourceAsStream(DEFAULTS_CONF_FILE)
        ) { "Bundled configuration defaults are missing" }

        defaults.use { input ->
            configFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    return CONFIG_FILE
}
