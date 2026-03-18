package net.firzen.web

import com.natpryce.konfig.ConfigurationProperties
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import com.natpryce.konfig.EnvironmentVariables
import com.natpryce.konfig.Key
import com.natpryce.konfig.intType
import com.natpryce.konfig.overriding
import com.natpryce.konfig.stringType
import java.io.File
import kotlin.text.lowercase

const val CONFIG_FILE = "/opt/yt-dlp-web/config.conf"
const val DEFAULTS_CONF_FILE = "src/main/resources/defaults.conf"

private val conf = systemProperties() overriding
        EnvironmentVariables() overriding
        ConfigurationProperties.fromFile(File(availableConfigPath()))

val SERVER_PROTOCOL = conf[Key("server.protocol", stringType)].lowercase()
val SERVER_PORT = conf[Key("server.port", intType)]
val SERVER_HOSTNAME = conf[Key("server.hostname", stringType)]

val DOWNLOAD_DIRECTORY = conf[Key("fs.download_directory", stringType)]



private fun availableConfigPath() : String {
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
