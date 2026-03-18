package net.firzen.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

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

fun downloadVideo(rawUrl: String, progressCallback : (Double?) -> Unit = {}) {
    downloadMedia(rawUrl, DOWNLOAD_DIRECTORY, false, progressCallback)
}

fun downloadAudio(rawUrl: String, progressCallback : (Double?) -> Unit = {}) {
    downloadMedia(rawUrl, DOWNLOAD_DIRECTORY, true, progressCallback)
}

fun downloadMedia(rawUrl: String, outputDir: String, audioOnly: Boolean = false,
                  progressCallback : (Double?) -> Unit) {

    val dir = File(outputDir)

    if(!rawUrl.isValidUrl()) {
        println("Error! Invalid url: $rawUrl")
        return
    }

    if(rawUrl.contains("playlist")) {
        println("Error! Cannot download playlists!")
        return
    }

    if((dir.isDirectory || dir.mkdirs()) && dir.canWrite() && dir.canRead()) {
        downloadMedia(Url(rawUrl), dir, audioOnly, progressCallback)
    }
    else {
        println("Error! $dir is not usable directory!")
    }
}

fun downloadMedia(url: Url, outputDir: File, audioOnly: Boolean, progressCallback : (Double?) -> Unit) : Int {
    val outTag = "OUT"
    val errorTag = "ERR"

    fun BufferedReader.consumeLines(tag: String) = CoroutineScope(Dispatchers.IO).launch {
        forEachLine { line ->
            val percent = Regex("""\d+(\.\d+)?%""")
                .find(line)
                ?.value?.filter { it.isDigit() || it == '.' }?.toDouble()

            if(tag == outTag) {
                progressCallback(percent)
            }
        }
    }

    return runBlocking {
        val command = if(audioOnly) {
            "yt-dlp --no-playlist --extract-audio --audio-format mp3 $url"
        }
        else {
            "yt-dlp --no-playlist $url"
        }.split(" ")

        val process = ProcessBuilder(command)
            .directory(outputDir)
            .start()

        val outJob = process.inputStream.bufferedReader().consumeLines(outTag)
        val errJob = process.errorStream.bufferedReader().consumeLines(errorTag)

        val exitCode = withContext(Dispatchers.IO) {
            process.waitFor()
        }

        outJob.join()
        errJob.join()

        return@runBlocking exitCode
    }
}
