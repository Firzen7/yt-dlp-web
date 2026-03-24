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
import net.firzen.web.logging.LogLevel
import net.firzen.web.logging.Logger
import net.firzen.web.logging.PersistentLogger

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
            post("/api/login") { performLogin(userManager, call) }
            post("/api/logout") { performLogout(call) }
            get("/api/user") { provideUserInfo(call) }
            post("/api/download") { performDownload(call) }
            get("/api/status/{taskId}") { reportTaskStatus(call) }
            get("/api/file/{taskId}") { provideDownloadedFile(call) }
            get("/api/version") { provideVersion(call) }
            get("/") { provideWebpage(call) }
            get("/index.html") { provideProtectedIndex(call) }
            get("/login.html") { provideProtectedLogin(call) }
            post("/api/title") { fetchVideoTitle(call) }

            staticResources("/", "static")
        }

        println("yt-dlp-web version ${BuildConfig.VERSION} started")
    }.start(wait = true)
}

private suspend fun fetchVideoTitle(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()
    if (session == null) {
        return call.respondJson("""{"error": "Unauthorized"}""", HttpStatusCode.Unauthorized)
    }

    val body = JSONObject(call.receiveText())
    val rawUrl = body.optString("url", "")
    val url = rawUrl.sanitizeVideoUrl()

    if (url.isBlank()) {
        PersistentLogger.logAction(LogLevel.WARNING, session.username, "Attempted to get video title from blank URL")
        return call.respondJson("""{"error": "URL is required"}""", HttpStatusCode.BadRequest)
    }

    Logger.i("Getting title for url: $url")
    val title = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("yt-dlp", "--get-title", url).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output
        } catch (e: Exception) {
            Logger.e("Failed to get title: ${e.message}")
            null
        }
    }

    if (title.isNullOrEmpty()) {
        call.respondJson("""{"error": "Failed to get title"}""", HttpStatusCode.InternalServerError)
        PersistentLogger.logAction(LogLevel.ERROR, session.username, "Failed to get title of $url")
    } else {
        val obj = JSONObject()
        obj.put("title", title)
        call.respondJson(obj.toString())

        PersistentLogger.logAction(LogLevel.INFO, session.username, "Title of $url determined as \"$title\"")
    }
}

private suspend fun performLogin(userManager: UserManager, call: RoutingCall) {
    val body = JSONObject(call.receiveText())
    val username = body.optString("username", "")
    val password = body.optString("password", "")

    if (userManager.validateUser(username, password)) {
        call.sessions.set(UserSession(username))
        call.respondJson("""{"ok": true}""")

        PersistentLogger.logAction(LogLevel.INFO, username, "Successful login")
    } else {
        call.respondJson(
            """{"error": "Nesprávné jméno nebo heslo."}""",
            HttpStatusCode.Unauthorized
        )

        PersistentLogger.logAction(LogLevel.WARNING, UNKNOWN_USER, "Login failed (user: $username)")
    }
}

private suspend fun performLogout(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()
    call.sessions.clear<UserSession>()
    call.respondJson("""{"ok": true}""")

    PersistentLogger.logAction(LogLevel.INFO, session?.username, "Logout")
}

private suspend fun provideUserInfo(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()

    if (session != null) {
        call.respondJson("""{"username": "${session.username}"}""")
    } else {
        call.respondJson("""{"error": "Unauthorized"}""", HttpStatusCode.Unauthorized)
    }
}

private suspend fun performDownload(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()
    if(session?.username == null) {
        Logger.e("User not logged in!")
        PersistentLogger.logAction(LogLevel.WARNING, UNKNOWN_USER, "Non-logged user attempt to download")
        return call.respondJson("""{"error": "User not logged in!"}""", HttpStatusCode.BadRequest)
    }

    val body = JSONObject(call.receiveText())
    val rawInputUrl = body.optString("url", "")
    val format = body.optString("format", "video")
    val customFilename = body.optString("filename", "")

    val url = rawInputUrl.sanitizeVideoUrl()

    if (url.isBlank()) {
        return call.respondJson("""{"error": "URL is required"}""", HttpStatusCode.BadRequest)
    }

    val taskId = UUID.randomUUID().toString()
    Logger.i("Received download request. Task ID: $taskId, URL: $url, Format: $format")
    PersistentLogger.logAction(LogLevel.INFO, session.username, "Started $format download of $url (custom name: $customFilename)")

    tasks[taskId] = DownloadTask(status = "processing")

    // Run download in background
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val audioOnly = format == "mp3"
            val taskDir = File(DOWNLOAD_DIRECTORY, taskId)
            if (!taskDir.exists()) taskDir.mkdirs()

            val customName = customFilename.takeIf { it.isNotBlank() }
            val exitCode = downloadMedia(url, taskDir.absolutePath, audioOnly, customName) { percent ->
                val currentTask = tasks[taskId]
                if (currentTask != null) {
                    tasks[taskId] = currentTask.copy(progress = percent)
                }
            }

            if (exitCode == 0) {
                // Find the downloaded file inside the unique task directory
                val latestFile = taskDir.listFiles()?.firstOrNull { it.isFile }

                if (latestFile != null) {
                    Logger.i("yt-dlp finished for taskId=$taskId. File resolved to: ${latestFile.absolutePath}")
                    val currentTask = tasks[taskId]
                    if (currentTask != null) {
                        tasks[taskId] = currentTask.copy(
                            status = "completed",
                            filePath = latestFile.absolutePath
                        )

                        PersistentLogger.logAction(LogLevel.INFO, session.username, "Completed $format download of $url (custom name: $customFilename)")
                    }
                } else {
                    Logger.e("yt-dlp finished with exit code 0 but NO FILE was found in ${taskDir.absolutePath}")
                    PersistentLogger.logAction(LogLevel.ERROR, session.username, "Downloaded file not found for (format: $format, url: $url, custom name: $customFilename)")

                    tasks[taskId] = DownloadTask(
                        status = "error",
                        error = "Staženo, ale soubor nenalezen"
                    )
                }
            } else {
                Logger.e("yt-dlp failed for taskId=$taskId. Exit code: $exitCode")
                PersistentLogger.logAction(LogLevel.ERROR, session.username, "yt-dlp failed (exit-code: $exitCode, format: $format, url: $url, custom name: $customFilename)")

                tasks[taskId] = DownloadTask(
                    status = "error",
                    error = "Něco se pokazilo (yt-dlp: $exitCode)"
                )
            }
        } catch (e: Exception) {
            Logger.e("Exception during download for taskId=$taskId: ${e.message}", e)
            PersistentLogger.logAction(LogLevel.ERROR, session.username, "Exception during download! (msg: ${e.message}, format: $format, url: $url, custom name: $customFilename)")

            tasks[taskId] = DownloadTask(
                status = "error",
                error = e.message ?: "Neznámá chyba"
            )
        }
    }

    call.respondJson("""{"task_id": "$taskId"}""")
}

private suspend fun reportTaskStatus(call: RoutingCall) {
    call.sessions.get<UserSession>()
        ?: return call.respondJson(
            """{"error": "Unauthorized"}""",
            HttpStatusCode.Unauthorized
        )

    val taskId = call.parameters["taskId"] ?: ""
    val task = tasks[taskId]
        ?: return call.respondJson(
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

private suspend fun provideDownloadedFile(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()

    session ?: return call.respondJson(
        """{"error": "Unauthorized"}""",
        HttpStatusCode.Unauthorized
    )

    val taskId = call.parameters["taskId"] ?: ""
    val task = tasks[taskId]

    if (task == null || task.status != "completed" || task.filePath == null) {
        return call.respondText("File not ready", status = HttpStatusCode.BadRequest)
    }

    val file = File(task.filePath)
    if (!file.exists()) {
        Logger.e("Failed to serve file for taskId=$taskId: ${file.absolutePath} - File not found!")
        PersistentLogger.logAction(LogLevel.ERROR, session.username, "Failed to serve file! (${file.absolutePath} - File not found)")

        return call.respondText("File not found", status = HttpStatusCode.NotFound)
    }

    Logger.i("Serving file to user for taskId=$taskId: ${file.absolutePath}")
    PersistentLogger.logAction(LogLevel.INFO, session.username, "Serving file: ${file.absolutePath}")

    call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(
            ContentDisposition.Parameters.FileName, file.name
        ).toString()
    )
    call.respondFile(file)
}

private suspend fun provideVersion(call: RoutingCall) {
    call.respondJson("""{"version": "${BuildConfig.VERSION}"}""")
}

private suspend fun provideWebpage(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()
    if (session == null) {
        call.respondRedirect("/login.html")
    } else {
        call.respondRedirect("/index.html")
    }
}

private suspend fun provideProtectedIndex(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()
    if (session == null) {
        call.respondRedirect("/login.html")
    } else {
        val text = UserSession::class.java.classLoader.getResource("static/index.html")?.readText()
        if (text != null) {
            call.respondText(text, ContentType.Text.Html)
        } else {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
        }
    }
}

private suspend fun provideProtectedLogin(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()
    if (session != null) {
        call.respondRedirect("/index.html")
    } else {
        val text = UserSession::class.java.classLoader.getResource("static/login.html")?.readText()
        if (text != null) {
            call.respondText(text, ContentType.Text.Html)
        } else {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
        }
    }
}

/**
 * Downloads media using yt-dlp as an external process.
 * Returns the exit code of the process.
 */
fun downloadMedia(rawUrl: String, outputDir: String, audioOnly: Boolean = false, customFilename: String? = null,
                  progressCallback: (Double?) -> Unit = {}): Int {

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
        return downloadMedia(Url(rawUrl), dir, audioOnly, customFilename, progressCallback)
    } else {
        println("Error! $dir is not usable directory!")
        return -1
    }
}

fun downloadMedia(url: Url, outputDir: File, audioOnly: Boolean, customFilename: String?,
                  progressCallback: (Double?) -> Unit): Int {
    Logger.i("downloadMedia(url=$url, outputDir=${outputDir.absolutePath})")
    val outTag = "OUT"
    val errorTag = "ERR"

    fun BufferedReader.consumeLines(tag: String) : kotlinx.coroutines.Job {
        return CoroutineScope(Dispatchers.IO).launch {
            forEachLine { line ->
                val percent = Regex("""\d+(\.\d+)?%""")
                    .find(line)
                    ?.value?.filter { it.isDigit() || it == '.' }?.toDouble()

                if (tag == outTag && percent != null) {
                    progressCallback(percent)
                }
            }
        }
    }

    return kotlinx.coroutines.runBlocking {
        val commandList = mutableListOf("yt-dlp", "--no-cache-dir", "--no-playlist", "--paths", outputDir.absolutePath)
        if (customFilename != null) {
            commandList.add("-o")
            commandList.add("$customFilename.%(ext)s")
        }
        if (audioOnly) {
            commandList.addAll(listOf("--extract-audio", "--audio-format", "mp3"))
        }
        commandList.add(url.toString())

        Logger.i("Executing yt-dlp with arguments: ${commandList.joinToString(" ")}")

        val process = ProcessBuilder(commandList)
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
