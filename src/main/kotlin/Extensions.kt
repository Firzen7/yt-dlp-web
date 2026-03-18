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

suspend fun RoutingCall.respondJson(json: String, status: HttpStatusCode = HttpStatusCode.OK) {
    Logger.i("respondJson($json)")
    respondText(json, ContentType.Application.Json, status)
}

