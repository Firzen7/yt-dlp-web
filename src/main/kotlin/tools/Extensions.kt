package net.firzen.web.tools

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.firzen.web.logging.LogLevel
import net.firzen.web.logging.Logger
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import java.io.BufferedReader
import java.net.URI

/**
 * Reports whether this string contains an absolute URL with both a scheme and host.
 *
 * @receiver text to validate as a URL
 * @return `true` when the text is an absolute URL, otherwise `false`
 */
fun String.isValidUrl(): Boolean {
    Logger.i("isValidUrl()")

    return try {
        val uri = URI(this)
        uri.scheme != null && uri.host != null
    } catch (_: Exception) {
        false
    }
}

/**
 * Removes YouTube playlist parameters while leaving invalid input unchanged for later validation.
 *
 * @receiver video URL to sanitize
 * @return sanitized URL, or the original text when it cannot be parsed
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
 *
 * @receiver routing call that receives the response
 * @param json serialized JSON response body
 * @param status HTTP status assigned to the response
 */
suspend fun RoutingCall.respondJson(json: String, status: HttpStatusCode = HttpStatusCode.OK) {
    Logger.i("respondJson($json)")

    respondText(json, ContentType.Application.Json, status)
}

/**
 * Formats this timestamp for persistent log entries.
 *
 * @receiver timestamp to format
 * @return timestamp formatted as `d.M.yyyy HH:mm`
 */
fun DateTime.dateTimeString(): String {
    return DateTimeFormat.forPattern("d.M.yyyy HH:mm").print(this)
}

/**
 * Consumes process output asynchronously, appends tagged lines, and reports parsed percentages.
 *
 * @receiver buffered process-output reader to consume
 * @param tag label prepended to every collected line
 * @param fullLog destination that receives collected output
 * @param progressCallback callback notified when a progress percentage is parsed
 * @return coroutine job consuming the reader
 */
fun BufferedReader.consumeLines(
    tag: String,
    fullLog: StringBuilder,
    progressCallback: (Double?) -> Unit
): Job {
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
