package net.firzen.web.logging

import net.firzen.web.tools.LOG_DIRECTORY
import net.firzen.web.tools.UNKNOWN_USER
import net.firzen.web.tools.dateTimeString
import org.joda.time.DateTime
import java.io.File

/**
 * Appends timestamped application actions to a separate log file for each user.
 */
object PersistentLogger {
    /**
     * Records an action, its user, and its client's IP address at the requested severity.
     */
    fun logAction(
        logLevel: LogLevel,
        rawUser: String?,
        clientAddress: String,
        action: String
    ) {
        val currentTime = DateTime().dateTimeString()
        val user = rawUser ?: UNKNOWN_USER
        val logPath = "${LOG_DIRECTORY}/$user.log"

        try {
            val logfile = File(logPath)

            if (!logfile.exists()) {
                logfile.createNewFile()
            }

            if (logfile.canWrite()) {
                logfile.appendText(
                    "$logLevel $currentTime [$clientAddress]: $action\n"
                )
            } else {
                Logger.e("Could not write into logfile! Path: $logPath")
            }
        } catch (_: Exception) {
            Logger.e("Could not write into logfile! Path: $logPath")
        }
    }
}
