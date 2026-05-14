package net.firzen.web

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

const val CONFIG_FILE = "/opt/yt-dlp-web/config.conf"
const val DEFAULTS_CONF_FILE = "src/main/resources/defaults.conf"

private val conf = systemProperties() overriding
        EnvironmentVariables() overriding
        ConfigurationProperties.fromFile(File(availableConfigPath()))

val SERVER_PORT = conf[Key("server.port", intType)]

val DOWNLOAD_DIRECTORY = conf[Key("fs.download_directory", stringType)]
val LOG_DIRECTORY = conf[Key("fs.log_directory", stringType)]
val JS_RUNTIME_TYPE = conf[Key("fs.js_runtime_type", stringType)]
val JS_RUNTIME_PATH = conf[Key("fs.js_runtime_path", stringType)]

val USERS_FILE = try {
    conf[Key("auth.users_file", stringType)]
} catch (_: Exception) {
    "./users.conf"
}

// [seconds] timeout of child processes
val PROCESS_TIMEOUT = conf[Key("process.timeout", longType)]

private fun availableConfigPath() : String {
    Logger.i("availableConfigPath()")
    if(File(CONFIG_FILE).exists()) {
        return CONFIG_FILE
    }
    else {
        File(DEFAULTS_CONF_FILE).copyTo(
            File(CONFIG_FILE)
        )
        return DEFAULTS_CONF_FILE
    }
}
