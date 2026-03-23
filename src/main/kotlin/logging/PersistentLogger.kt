package net.firzen.web.logging

import net.firzen.web.LOG_DIRECTORY
import net.firzen.web.dateTimeString
import org.joda.time.DateTime
import java.io.File

object PersistentLogger {
    fun logAction(user: String, action: String) {
        val currentTime = DateTime().dateTimeString()
        File("${LOG_DIRECTORY}/$user.log").appendText("$currentTime: $action")
    }
}
