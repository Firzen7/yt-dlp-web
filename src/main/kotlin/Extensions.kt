package net.firzen.web

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.firzen.web.logging.Logger
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import java.io.BufferedReader

fun String.isValidUrl(): Boolean {
    Logger.i("isValidUrl()")
    return try {
        val uri = java.net.URI(this)
        uri.scheme != null && uri.host != null
    } catch (_: Exception) {
        false
    }
}

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

suspend fun RoutingCall.respondJson(json: String, status: HttpStatusCode = HttpStatusCode.OK) {
    Logger.i("respondJson($json)")
    respondText(json, ContentType.Application.Json, status)
}

fun DateTime.dateTimeString() : String {
    return DateTimeFormat.forPattern("d.M.yyyy HH:mm").print(this)
}

fun String.startsWithAny(vararg prefixes: String) : Boolean {
    return prefixes.any { this.startsWith(it) }
}

fun BufferedReader.consumeLines(tag: String, fullLog: StringBuilder,
                                progressCallback: (Double?) -> Unit) : kotlinx.coroutines.Job {

    return CoroutineScope(Dispatchers.IO).launch {
        forEachLine { line ->
            synchronized(fullLog) {
                fullLog.appendLine("[$tag] $line")
            }
            val percent = Regex("""\d+(\.\d+)?%""")
                .find(line)
                ?.value?.filter { it.isDigit() || it == '.' }?.toDouble()

            if (tag == OUT_TAG && percent != null) {
                progressCallback(percent)
            }
        }
    }
}
