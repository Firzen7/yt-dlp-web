package net.firzen.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.json.JSONObject

fun startServer() {
    println("Yt-dlp-web is starting ...")

    embeddedServer(Netty, SERVER_PORT) {
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                val code = HttpStatusCode.InternalServerError
                val obj = JSONObject()
                obj.put("status", code.value)
                obj.put("message", cause.message)
                obj.put("version", BuildConfig.VERSION)

                call.respondText(text = obj.toString(), status = code, contentType = ContentType.Application.Json)
            }
        }

        routing {
            get("/download{q}") {
                val query = call.request.queryParameters["q"]
                val debug = call.request.queryParameters["debug"].toBoolean()

                call.respondText("Downloading $query", ContentType.Text.Plain)

//                val json = searchReference(query, debug)
//                call.respondText(json.toString(4), ContentType.Application.Json)
            }

            route("{...}") {
                handle {
                    call.respondText(
                        "<html><p>Welcome to yt-dlp-web version ${BuildConfig.VERSION}.</p></html>",
                        ContentType.Text.Html
                    )
                }
            }
        }

        println("Started yt-dlp-web version ${BuildConfig.VERSION}")
    }.start(wait = true)
}
