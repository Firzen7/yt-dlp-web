package net.firzen.web.logging

import net.firzen.web.LOG_DIRECTORY
import net.firzen.web.UNKNOWN_USER
import net.firzen.web.dateTimeString
import org.joda.time.DateTime
import java.io.File

/**
 * Appends timestamped application actions to a separate log file for each user.
 */
object PersistentLogger {
    /**
     * Records an action at the requested severity under the resolved username.
     */
    fun logAction(logLevel: LogLevel, rawUser: String?, action: String) {
        val currentTime = DateTime().dateTimeString()
        val user = rawUser ?: UNKNOWN_USER
        val logPath = "${LOG_DIRECTORY}/$user.log"

        try {
            val logfile = File(logPath)

            if (!logfile.exists()) {
                logfile.createNewFile()
            }

            if (logfile.canWrite()) {
                logfile.appendText("$logLevel $currentTime: $action\n")
            } else {
                Logger.e("Could not write into logfile! Path: $logPath")
            }
        } catch (_: Exception) {
            Logger.e("Could not write into logfile! Path: $logPath")
        }
    }
}
