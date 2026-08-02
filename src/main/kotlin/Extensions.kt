package net.firzen.web

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.firzen.web.logging.LogLevel
import net.firzen.web.logging.Logger
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import java.io.BufferedReader

/**
 * Reports whether this string contains an absolute URL with both a scheme and host.
 */
fun String.isValidUrl(): Boolean {
    Logger.i("isValidUrl()")

    return try {
        val uri = java.net.URI(this)
        uri.scheme != null && uri.host != null
    } catch (_: Exception) {
        false
    }
}

/**
 * Removes YouTube playlist parameters while leaving invalid input unchanged for later validation.
 */
fun String.sanitizeVideoUrl(): String {
    Logger.i("sanitizeVideoUrl()")

    return try {
        val parsed = Url(this)
        val builder = URLBuilder(parsed)

        builder.parameters.remove("list")
        builder.parameters.remove("start_radio")
        builder.parameters.remove("index")

        builder.build().toString()
    } catch (_: Exception) {
        this
    }
}

/**
 * Sends a JSON string with the requested HTTP status.
 */
suspend fun RoutingCall.respondJson(json: String, status: HttpStatusCode = HttpStatusCode.OK) {
    Logger.i("respondJson($json)")

    respondText(json, ContentType.Application.Json, status)
}

/**
 * Formats this timestamp for persistent log entries.
 */
fun DateTime.dateTimeString(): String {
    return DateTimeFormat.forPattern("d.M.yyyy HH:mm").print(this)
}

/**
 * Reports whether this string starts with at least one supplied prefix.
 */
fun String.startsWithAny(vararg prefixes: String): Boolean {
    return prefixes.any { this.startsWith(it) }
}

/**
 * Consumes process output asynchronously, appends tagged lines, and reports parsed percentages.
 */
fun BufferedReader.consumeLines(
    tag: String,
    fullLog: StringBuilder,
    progressCallback: (Double?) -> Unit
): kotlinx.coroutines.Job {
    return CoroutineScope(Dispatchers.IO).launch {
        forEachLine { line ->
            synchronized(fullLog) {
                fullLog.appendLine("[$tag] $line")
            }

            val percent = Regex("""\d+(\.\d+)?%""")
                .find(line)
                ?.value?.filter { it.isDigit() || it == '.' }?.toDouble()

            if (tag == LogLevel.INFO.toString() && percent != null) {
                progressCallback(percent)
            }
        }
    }
}
