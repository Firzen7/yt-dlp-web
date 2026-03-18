package net.firzen.web

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

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

