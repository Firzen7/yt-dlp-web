package net.firzen.web.tools

import com.natpryce.konfig.ConfigurationProperties
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import com.natpryce.konfig.EnvironmentVariables
import com.natpryce.konfig.Key
import com.natpryce.konfig.intType
import com.natpryce.konfig.longType
import com.natpryce.konfig.overriding
import com.natpryce.konfig.stringType
import net.firzen.web.logging.Logger
import java.io.File

private val conf = systemProperties() overriding
        EnvironmentVariables() overriding
        ConfigurationProperties.fromFile(File(availableConfigPath()))

// Primary configuration path used by packaged installations.
const val CONFIG_FILE = "/opt/yt-dlp-web/config.conf"
// Bundled configuration used when the installation-specific file is unavailable.
const val DEFAULTS_CONF_FILE = "src/main/resources/defaults.conf"
// Port on which the HTTP server accepts connections.
val SERVER_PORT = conf[Key("server.port", intType)]
// Directory where completed downloads are stored.
val DOWNLOAD_DIRECTORY = conf[Key("fs.download_directory", stringType)]
// Directory where per-user action logs are stored.
val LOG_DIRECTORY = conf[Key("fs.log_directory", stringType)]
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

/**
 * Selects the installed configuration or creates it from the bundled defaults.
 */
private fun availableConfigPath(): String {
    Logger.i("availableConfigPath()")

    if (File(CONFIG_FILE).exists()) {
        return CONFIG_FILE
    } else {
        File(DEFAULTS_CONF_FILE).copyTo(
            File(CONFIG_FILE)
        )

        return DEFAULTS_CONF_FILE
    }
}
