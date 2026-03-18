package net.firzen.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

@Serializable
data class UserSession(val username: String)

data class DownloadTask(
    val status: String,
    val filePath: String? = null,
    val error: String? = null,
    val progress: Double? = null
)

private val tasks = ConcurrentHashMap<String, DownloadTask>()

fun startServer() {
    Logger.i("startServer()")
    println("Yt-dlp-web is starting ...")

    val userManager = UserManager(File(USERS_FILE))

    embeddedServer(Netty, SERVER_PORT) {
        install(Sessions) {
            cookie<UserSession>("SESSION") {
                cookie.path = "/"
                cookie.httpOnly = true
            }
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                val code = HttpStatusCode.InternalServerError
                val obj = JSONObject()
                obj.put("status", code.value)
                obj.put("message", cause.message)
                obj.put("version", BuildConfig.VERSION)

                call.respondText(
                    text = obj.toString(),
                    status = code,
                    contentType = ContentType.Application.Json
                )
            }
        }

        routing {
            // --- Auth API ---

            post("/api/login") {
                val body = JSONObject(call.receiveText())
                val username = body.optString("username", "")
                val password = body.optString("password", "")

                if (userManager.validateUser(username, password)) {
                    call.sessions.set(UserSession(username))
                    call.respondJson("""{"ok": true}""")
                } else {
                    call.respondJson(
                        """{"error": "Nesprávné jméno nebo heslo."}""",
                        HttpStatusCode.Unauthorized
                    )
                }
            }

            post("/api/logout") {
                call.sessions.clear<UserSession>()
                call.respondJson("""{"ok": true}""")
            }

            // --- Download API (auth required) ---

            post("/api/download") {
                val session = call.sessions.get<UserSession>()
                    ?: return@post call.respondJson(
                        """{"error": "Unauthorized"}""",
                        HttpStatusCode.Unauthorized
                    )

                val body = JSONObject(call.receiveText())
                val url = body.optString("url", "")
                val format = body.optString("format", "video")

                if (url.isBlank()) {
                    return@post call.respondJson(
                        """{"error": "URL is required"}""",
                        HttpStatusCode.BadRequest
                    )
                }

                val taskId = UUID.randomUUID().toString()
                tasks[taskId] = DownloadTask(status = "processing")

                // Run download in background
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val audioOnly = format == "mp3"
                        val exitCode = downloadMedia(url, DOWNLOAD_DIRECTORY, audioOnly) { percent ->
                            val currentTask = tasks[taskId]
                            if (currentTask != null) {
                                tasks[taskId] = currentTask.copy(progress = percent)
                            }
                        }

                        if (exitCode == 0) {
                            // Find the most recently created file in download dir
                            val downloadDir = File(DOWNLOAD_DIRECTORY)
                            val latestFile = downloadDir.listFiles()
                                ?.filter { it.isFile }
                                ?.maxByOrNull { it.lastModified() }

                            if (latestFile != null) {
                                tasks[taskId] = DownloadTask(
                                    status = "completed",
                                    filePath = latestFile.absolutePath
                                )
                            } else {
                                tasks[taskId] = DownloadTask(
                                    status = "error",
                                    error = "Download completed but file not found"
                                )
                            }
                        } else {
                            tasks[taskId] = DownloadTask(
                                status = "error",
                                error = "yt-dlp exited with code $exitCode"
                            )
                        }
                    } catch (e: Exception) {
                        tasks[taskId] = DownloadTask(
                            status = "error",
                            error = e.message ?: "Unknown error"
                        )
                    }
                }

                call.respondJson("""{"task_id": "$taskId"}""")
            }

            get("/api/status/{taskId}") {
                call.sessions.get<UserSession>()
                    ?: return@get call.respondJson(
                        """{"error": "Unauthorized"}""",
                        HttpStatusCode.Unauthorized
                    )

                val taskId = call.parameters["taskId"] ?: ""
                val task = tasks[taskId]
                    ?: return@get call.respondJson(
                        """{"error": "Task not found"}""",
                        HttpStatusCode.NotFound
                    )

                when (task.status) {
                    "completed" -> call.respondJson(
                        """{"status": "completed", "download_url": "/api/file/$taskId"}"""
                    )
                    else -> {
                        val obj = JSONObject()
                        obj.put("status", task.status)
                        if (task.error != null) obj.put("error", task.error)
                        if (task.progress != null) obj.put("progress", task.progress)
                        call.respondJson(obj.toString())
                    }
                }
            }

            get("/api/file/{taskId}") {
                call.sessions.get<UserSession>()
                    ?: return@get call.respondJson(
                        """{"error": "Unauthorized"}""",
                        HttpStatusCode.Unauthorized
                    )

                val taskId = call.parameters["taskId"] ?: ""
                val task = tasks[taskId]

                if (task == null || task.status != "completed" || task.filePath == null) {
                    return@get call.respondText("File not ready", status = HttpStatusCode.BadRequest)
                }

                val file = File(task.filePath)
                if (!file.exists()) {
                    return@get call.respondText("File not found", status = HttpStatusCode.NotFound)
                }

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName, file.name
                    ).toString()
                )
                call.respondFile(file)
            }

            // --- Protected index: redirect to login if not authenticated ---

            get("/") {
                val session = call.sessions.get<UserSession>()
                if (session == null) {
                    call.respondRedirect("/login.html")
                } else {
                    call.respondRedirect("/index.html")
                }
            }

            // --- Static files ---

            staticResources("/", "static")
        }

        println("Started yt-dlp-web version ${BuildConfig.VERSION}")
    }.start(wait = true)
}

fun downloadVideo(rawUrl: String, progressCallback: (Double?) -> Unit = {}) {
    Logger.i("downloadVideo()")
    downloadMedia(rawUrl, DOWNLOAD_DIRECTORY, false, progressCallback)
}

fun downloadAudio(rawUrl: String, progressCallback: (Double?) -> Unit = {}) {
    Logger.i("downloadAudio()")
    downloadMedia(rawUrl, DOWNLOAD_DIRECTORY, true, progressCallback)
}

/**
 * Downloads media using yt-dlp as an external process.
 * Returns the exit code of the process.
 */
fun downloadMedia(
    rawUrl: String, outputDir: String, audioOnly: Boolean = false,
    progressCallback: (Double?) -> Unit = {}
): Int {
    Logger.i("downloadMedia()")

    val dir = File(outputDir)

    if (!rawUrl.isValidUrl()) {
        println("Error! Invalid url: $rawUrl")
        return -1
    }

    if (rawUrl.contains("playlist")) {
        println("Error! Cannot download playlists!")
        return -1
    }

    if ((dir.isDirectory || dir.mkdirs()) && dir.canWrite() && dir.canRead()) {
        return downloadMedia(Url(rawUrl), dir, audioOnly, progressCallback)
    } else {
        println("Error! $dir is not usable directory!")
        return -1
    }
}

fun downloadMedia(
    url: Url, outputDir: File, audioOnly: Boolean,
    progressCallback: (Double?) -> Unit
): Int {
    Logger.i("downloadMedia()")
    val outTag = "OUT"
    val errorTag = "ERR"

    fun BufferedReader.consumeLines(tag: String): kotlinx.coroutines.Job {
        Logger.i("consumeLines()")
        return CoroutineScope(Dispatchers.IO).launch {
            forEachLine { line ->
                val percent = Regex("""\d+(\.\d+)?%""")
                    .find(line)
                    ?.value?.filter { it.isDigit() || it == '.' }?.toDouble()

                if (tag == outTag) {
                    progressCallback(percent)
                }
            }
        }
    }

    return kotlinx.coroutines.runBlocking {
        val command = if (audioOnly) {
            "yt-dlp --no-playlist --extract-audio --audio-format mp3 $url"
        } else {
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
