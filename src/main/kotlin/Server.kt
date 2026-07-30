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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.firzen.web.logging.LogLevel
import net.firzen.web.logging.Logger
import net.firzen.web.logging.PersistentLogger
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class UserSession(val username: String)

data class DownloadTask(
    val status: String,
    val filePath: String? = null,
    val error: String? = null,
    val progress: Double? = null
)

private data class PasswordChangeRequest(
    val currentPassword: String,
    val newPassword: String,
    val confirmation: String
)

private data class DownloadRequest(
    val url: String,
    val format: String,
    val audioConversion: String,
    val customFilename: String
) {
    val audioOnly: Boolean get() = format == "mp3"
    val forceMp3Conversion: Boolean get() = audioOnly && audioConversion == "mp3"
}

private data class DownloadContext(
    val taskId: String,
    val username: String,
    val request: DownloadRequest
)

private data class TaskDownloadResult(
    val exitCode: Int,
    val output: String,
    val taskDir: File
)

private data class MediaDownloadOptions(
    val outputDir: File,
    val audioOnly: Boolean,
    val forceMp3Conversion: Boolean,
    val customFilename: String?,
    val progressCallback: (Double?) -> Unit,
    val processCallback: (Process?) -> Unit
)

private val tasks = ConcurrentHashMap<String, DownloadTask>()
private val taskJobs = ConcurrentHashMap<String, Job>()
private val taskProcesses = ConcurrentHashMap<String, Process>()

private fun appVersion(): String = BuildConfig.VERSION

fun startServer() {
    Logger.i("startServer()")
    val userManager = UserManager(File(USERS_FILE))
    embeddedServer(Netty, SERVER_PORT) {
        configureServer(userManager)
    }.start(wait = true)
}

private fun Application.configureServer(userManager: UserManager) {
    configureSessions()
    configureErrorHandling()
    configureRoutes(userManager)
    Logger.i("yt-dlp-web version ${appVersion()} started")
}

private fun Application.configureSessions() {
    install(Sessions) {
        cookie<UserSession>("SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = LOGIN_SESSION_LENGTH
        }
    }
}

private fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            respondWithServerError(call, cause)
        }
    }
}

private suspend fun respondWithServerError(call: ApplicationCall, cause: Throwable) {
    val response = JSONObject()
        .put("status", HttpStatusCode.InternalServerError.value)
        .put("message", cause.message)
        .put("version", appVersion())
    call.respondText(
        response.toString(),
        ContentType.Application.Json,
        HttpStatusCode.InternalServerError
    )
}

private fun Application.configureRoutes(userManager: UserManager) {
    routing {
        post("/api/login") { performLogin(userManager, call) }
        post("/api/logout") { performLogout(call) }
        get("/api/user") { provideUserInfo(call) }
        post("/api/download") { performDownload(call) }
        post("/api/cancel/{taskId}") { cancelDownload(call) }
        get("/api/status/{taskId}") { reportTaskStatus(call) }
        get("/api/file/{taskId}") { provideDownloadedFile(call) }
        get("/api/version") { provideVersion(call) }
        get("/") { provideWebpage(call) }
        get("/index.html") { provideProtectedIndex(call) }
        get("/login.html") { provideProtectedLogin(call) }
        post("/api/title") { handleVideoTitleRequest(call) }
        post("/api/decode") { decodeBase64Url(call) }
        post("/api/change-password") { handleChangePassword(userManager, call) }
        post("/api/password-entropy") { handlePasswordEntropy(userManager, call) }
        staticResources("/", "static")
    }
}

private suspend fun performLogin(userManager: UserManager, call: RoutingCall) {
    val body = JSONObject(call.receiveText())
    val username = body.optString("username", "")
    val password = body.optString("password", "")
    if (userManager.validateUser(username, password)) {
        completeLogin(call, username)
    } else {
        rejectLogin(call, username)
    }
}

private suspend fun completeLogin(call: RoutingCall, username: String) {
    call.sessions.set(UserSession(username))
    call.respondJson("""{"ok": true}""")
    PersistentLogger.logAction(LogLevel.INFO, username, "Successful login")
}

private suspend fun rejectLogin(call: RoutingCall, username: String) {
    call.respondJson(
        """{"error": "Nesprávné jméno nebo heslo."}""",
        HttpStatusCode.Unauthorized
    )
    PersistentLogger.logAction(
        LogLevel.WARNING,
        UNKNOWN_USER,
        "Login failed (user: $username)"
    )
}

private suspend fun performLogout(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()
    call.sessions.clear<UserSession>()
    call.respondJson("""{"ok": true}""")
    PersistentLogger.logAction(LogLevel.INFO, session?.username, "Logout")
}

private suspend fun handleChangePassword(userManager: UserManager, call: RoutingCall) {
    val session = call.sessions.get<UserSession>()
        ?: return call.respondJson(
            """{"error": "Unauthorized"}""",
            HttpStatusCode.Unauthorized
        )
    val request = readPasswordChangeRequest(call)
    val validationError = validatePasswordChange(request)
    if (validationError != null) {
        return call.respondJson(
            """{"error": "$validationError"}""",
            HttpStatusCode.BadRequest
        )
    }
    changeUserPassword(userManager, call, session.username, request)
}

private suspend fun readPasswordChangeRequest(call: RoutingCall): PasswordChangeRequest {
    val body = JSONObject(call.receiveText())
    return PasswordChangeRequest(
        body.optString("currentPassword", ""),
        body.optString("newPassword", ""),
        body.optString("confirmNewPassword", "")
    )
}

private fun validatePasswordChange(request: PasswordChangeRequest): String? {
    if (request.newPassword.isEmpty()) {
        return "Nové heslo nesmí být prázdné."
    }
    if (request.newPassword != request.confirmation) {
        return "Nová hesla se neshodují."
    }
    return null
}

private suspend fun changeUserPassword(
    userManager: UserManager,
    call: RoutingCall,
    username: String,
    request: PasswordChangeRequest
) {
    try {
        userManager.changePassword(username, request.currentPassword, request.newPassword)
        completePasswordChange(call, username)
    } catch (e: IllegalArgumentException) {
        rejectPasswordChange(call, username, e)
    } catch (e: Exception) {
        failPasswordChange(call, username, e)
    }
}

private suspend fun completePasswordChange(call: RoutingCall, username: String) {
    call.sessions.clear<UserSession>()
    call.respondJson("""{"ok": true}""")
    PersistentLogger.logAction(
        LogLevel.INFO,
        username,
        "Password changed successfully. User logged out."
    )
}

private suspend fun rejectPasswordChange(
    call: RoutingCall,
    username: String,
    error: IllegalArgumentException
) {
    val message = error.message ?: "Chyba při změně hesla."
    call.respondJson("""{"error": "$message"}""", HttpStatusCode.BadRequest)
    PersistentLogger.logAction(
        LogLevel.WARNING,
        username,
        "Failed to change password: ${error.message}"
    )
}

private suspend fun failPasswordChange(
    call: RoutingCall,
    username: String,
    error: Exception
) {
    call.respondJson(
        """{"error": "Chyba při změně hesla."}""",
        HttpStatusCode.InternalServerError
    )
    PersistentLogger.logAction(
        LogLevel.ERROR,
        username,
        "Exception while changing password: ${error.message}"
    )
}

private suspend fun handlePasswordEntropy(userManager: UserManager, call: RoutingCall) {
    val username = call.sessions.get<UserSession>()?.username
    try {
        val password = JSONObject(call.receiveText()).optString("password", "")
        call.respondJson("""{"entropy": ${userManager.computeEntropy(password)}}""")
    } catch (_: Exception) {
        PersistentLogger.logAction(
            LogLevel.WARNING,
            username,
            "Error while computing password entropy!"
        )
        call.respondJson(
            """{"error": "Chyba při výpočtu entropie."}""",
            HttpStatusCode.InternalServerError
        )
    }
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
    val username = requireDownloadUser(call) ?: return
    val request = readDownloadRequest(call)
    if (request.url.isBlank()) {
        return call.respondJson(
            """{"error": "URL is required"}""",
            HttpStatusCode.BadRequest
        )
    }
    val taskId = startDownloadTask(username, request)
    call.respondJson("""{"task_id": "$taskId"}""")
}

private suspend fun requireDownloadUser(call: RoutingCall): String? {
    val username = call.sessions.get<UserSession>()?.username
    if (username == null) {
        Logger.e("User not logged in!")
        PersistentLogger.logAction(
            LogLevel.WARNING,
            UNKNOWN_USER,
            "Non-logged user attempt to download"
        )
        call.respondJson(
            """{"error": "User not logged in!"}""",
            HttpStatusCode.BadRequest
        )
    }
    return username
}

private suspend fun readDownloadRequest(call: RoutingCall): DownloadRequest {
    val body = JSONObject(call.receiveText())
    val audioConversion = body.optString("audioConversion", "fastest")
        .lowercase()
        .takeIf { it == "fastest" || it == "mp3" } ?: "fastest"
    return DownloadRequest(
        body.optString("url", "").sanitizeVideoUrl(),
        body.optString("format", "video"),
        audioConversion,
        sanitizeFilename(body.optString("filename", ""))
    )
}

private fun sanitizeFilename(filename: String): String {
    return filename.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "")
}

private fun startDownloadTask(username: String, request: DownloadRequest): String {
    val taskId = UUID.randomUUID().toString()
    val context = DownloadContext(taskId, username, request)
    logDownloadStarted(context)
    tasks[taskId] = DownloadTask(status = "processing")
    taskJobs[taskId] = CoroutineScope(Dispatchers.IO).launch {
        executeDownloadTask(context)
    }
    return taskId
}

private fun logDownloadStarted(context: DownloadContext) {
    val request = context.request
    Logger.i(
        "Received download request. Task ID: ${context.taskId}, URL: ${request.url}, " +
            "Format: ${request.format}, Audio conversion: ${request.audioConversion}"
    )
    PersistentLogger.logAction(
        LogLevel.INFO,
        context.username,
        "Started ${request.format} download of ${request.url}" +
            " (audio conversion: ${request.audioConversion}, custom name: ${request.customFilename})"
    )
}

private fun executeDownloadTask(context: DownloadContext) {
    try {
        val result = runTaskDownload(context)
        if (tasks[context.taskId]?.status != "cancelled") {
            handleDownloadResult(context, result)
        }
    } catch (_: CancellationException) {
        handleDownloadCancellation(context.taskId)
    } catch (e: Exception) {
        handleDownloadException(context, e)
    } finally {
        taskJobs.remove(context.taskId)
        taskProcesses.remove(context.taskId)
    }
}

private fun runTaskDownload(context: DownloadContext): TaskDownloadResult {
    val request = context.request
    val taskDir = File(DOWNLOAD_DIRECTORY, context.taskId)
    if (!taskDir.exists()) taskDir.mkdirs()
    val result = downloadMedia(
        request.url,
        taskDir.absolutePath,
        request.audioOnly,
        request.forceMp3Conversion,
        request.customFilename.takeIf { it.isNotBlank() },
        { updateTaskProgress(context.taskId, it) },
        { trackTaskProcess(context.taskId, it) }
    )
    return TaskDownloadResult(result.first, result.second, taskDir)
}

private fun updateTaskProgress(taskId: String, progress: Double?) {
    val task = tasks[taskId]
    if (task?.status == "processing") {
        tasks[taskId] = task.copy(progress = progress)
    }
}

private fun trackTaskProcess(taskId: String, process: Process?) {
    when {
        process == null -> taskProcesses.remove(taskId)
        tasks[taskId]?.status == "cancelled" -> destroyProcessTree(process)
        else -> taskProcesses[taskId] = process
    }
}

private fun handleDownloadResult(context: DownloadContext, result: TaskDownloadResult) {
    if (result.exitCode == 0) {
        completeSuccessfulDownload(context, result.taskDir)
    } else {
        failYtDlpDownload(context, result)
    }
}

private fun completeSuccessfulDownload(context: DownloadContext, taskDir: File) {
    val file = taskDir.listFiles()?.firstOrNull { it.isFile }
    if (file == null) {
        handleMissingDownload(context, taskDir)
        return
    }
    Logger.i(
        "yt-dlp finished for taskId=${context.taskId}. File resolved to: ${file.absolutePath}"
    )
    val task = tasks[context.taskId] ?: return
    tasks[context.taskId] = task.copy(status = "completed", filePath = file.absolutePath)
    PersistentLogger.logAction(
        LogLevel.INFO,
        context.username,
        completedDownloadLog(context.request)
    )
}

private fun completedDownloadLog(request: DownloadRequest): String {
    return "Completed ${request.format} download of ${request.url}" +
        " (audio conversion: ${request.audioConversion}, custom name: ${request.customFilename})"
}

private fun handleMissingDownload(context: DownloadContext, taskDir: File) {
    Logger.e(
        "yt-dlp finished with exit code 0 but NO FILE was found in ${taskDir.absolutePath}"
    )
    PersistentLogger.logAction(
        LogLevel.ERROR,
        context.username,
        "Downloaded file not found for (${downloadDetails(context.request)})"
    )
    tasks[context.taskId] = DownloadTask(
        status = "error",
        error = "Staženo, ale soubor nenalezen"
    )
}

private fun failYtDlpDownload(context: DownloadContext, result: TaskDownloadResult) {
    Logger.e(
        "yt-dlp failed for taskId=${context.taskId}. Exit code: ${result.exitCode}"
    )
    PersistentLogger.logAction(
        LogLevel.ERROR,
        context.username,
        "yt-dlp failed (exit-code: ${result.exitCode}, ${downloadDetails(context.request)})" +
            "\n\n--- YT-DLP OUTPUT ---\n${result.output}---------------------\n"
    )
    tasks[context.taskId] = DownloadTask(
        status = "error",
        error = "Něco se pokazilo (yt-dlp: ${result.exitCode})"
    )
}

private fun handleDownloadCancellation(taskId: String) {
    if (tasks[taskId]?.status != "cancelled") {
        tasks[taskId] = DownloadTask(
            status = "cancelled",
            error = "Stahování bylo zrušeno"
        )
    }
    Logger.i("Download cancelled for taskId=$taskId")
}

private fun handleDownloadException(context: DownloadContext, error: Exception) {
    Logger.e(
        "Exception during download for taskId=${context.taskId}: ${error.message}",
        error
    )
    PersistentLogger.logAction(
        LogLevel.ERROR,
        context.username,
        "Exception during download! (msg: ${error.message}, ${downloadDetails(context.request)})"
    )
    tasks[context.taskId] = DownloadTask(
        status = "error",
        error = error.message ?: "Neznámá chyba"
    )
}

private fun downloadDetails(request: DownloadRequest): String {
    return "format: ${request.format}, audio conversion: ${request.audioConversion}, " +
        "url: ${request.url}, custom name: ${request.customFilename}"
}

private suspend fun cancelDownload(call: RoutingCall) {
    val username = call.sessions.get<UserSession>()?.username
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
    if (task.status != "processing") {
        return call.respondJson("""{"ok": true, "status": "${task.status}"}""")
    }
    stopDownloadTask(taskId, task)
    logDownloadCancelled(taskId, username)
    call.respondJson("""{"ok": true, "status": "cancelled"}""")
}

private fun stopDownloadTask(taskId: String, task: DownloadTask) {
    tasks[taskId] = task.copy(
        status = "cancelled",
        error = "Stahování bylo zrušeno"
    )
    taskProcesses.remove(taskId)?.let(::destroyProcessTree)
    taskJobs.remove(taskId)?.cancel(
        CancellationException("Download cancelled by user")
    )
}

private fun logDownloadCancelled(taskId: String, username: String) {
    Logger.i("Cancel requested for taskId=$taskId by user=$username")
    PersistentLogger.logAction(
        LogLevel.INFO,
        username,
        "Cancelled download task $taskId"
    )
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
    if (task.status == "completed") {
        call.respondJson(
            """{"status": "completed", "download_url": "/api/file/$taskId"}"""
        )
    } else {
        call.respondJson(taskStatusJson(task).toString())
    }
}

private fun taskStatusJson(task: DownloadTask): JSONObject {
    val response = JSONObject().put("status", task.status)
    if (task.error != null) response.put("error", task.error)
    if (task.progress != null) response.put("progress", task.progress)
    return response
}

private suspend fun provideDownloadedFile(call: RoutingCall) {
    val username = call.sessions.get<UserSession>()?.username
        ?: return call.respondJson(
            """{"error": "Unauthorized"}""",
            HttpStatusCode.Unauthorized
        )
    val taskId = call.parameters["taskId"] ?: ""
    val file = resolveDownloadedFile(call, taskId) ?: return
    if (!file.exists()) {
        handleMissingServedFile(call, username, taskId, file)
        return
    }
    serveDownloadedFile(call, username, taskId, file)
}

private suspend fun resolveDownloadedFile(call: RoutingCall, taskId: String): File? {
    val task = tasks[taskId]
    if (task == null || task.status != "completed" || task.filePath == null) {
        call.respondText("File not ready", status = HttpStatusCode.BadRequest)
        return null
    }
    return File(task.filePath)
}

private suspend fun handleMissingServedFile(
    call: RoutingCall,
    username: String,
    taskId: String,
    file: File
) {
    Logger.e(
        "Failed to serve file for taskId=$taskId: ${file.absolutePath} - File not found!"
    )
    PersistentLogger.logAction(
        LogLevel.ERROR,
        username,
        "Failed to serve file! (${file.absolutePath} - File not found)"
    )
    call.respondText("File not found", status = HttpStatusCode.NotFound)
}

private suspend fun serveDownloadedFile(
    call: RoutingCall,
    username: String,
    taskId: String,
    file: File
) {
    Logger.i("Serving file to user for taskId=$taskId: ${file.absolutePath}")
    PersistentLogger.logAction(
        LogLevel.INFO,
        username,
        "Serving file: ${file.absolutePath}"
    )
    call.response.header(HttpHeaders.ContentDisposition, contentDisposition(file))
    call.respondFile(file)
}

private fun contentDisposition(file: File): String {
    val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
    val quotedName = file.name.replace("\"", "\\\"")
    return "attachment; filename=\"$quotedName\"; filename*=UTF-8''$encodedName"
}

private suspend fun provideVersion(call: RoutingCall) {
    call.respondJson("""{"version": "${appVersion()}"}""")
}

private suspend fun provideWebpage(call: RoutingCall) {
    val queryString = call.request.queryString()
    val suffix = if (queryString.isNotEmpty()) "?$queryString" else ""
    val target = if (call.sessions.get<UserSession>() == null) {
        "/login.html$suffix"
    } else {
        "/index.html$suffix"
    }
    call.respondRedirect(target)
}

private suspend fun provideProtectedIndex(call: RoutingCall) {
    if (call.sessions.get<UserSession>() == null) {
        call.respondRedirect("/login.html")
    } else {
        respondStaticHtml(call, "static/index.html")
    }
}

private suspend fun provideProtectedLogin(call: RoutingCall) {
    if (call.sessions.get<UserSession>() != null) {
        call.respondRedirect("/index.html")
    } else {
        respondStaticHtml(call, "static/login.html")
    }
}

private suspend fun respondStaticHtml(call: RoutingCall, resourcePath: String) {
    val text = UserSession::class.java.classLoader.getResource(resourcePath)?.readText()
    if (text == null) {
        call.respondText("Not found", status = HttpStatusCode.NotFound)
    } else {
        call.respondText(text, ContentType.Text.Html)
    }
}

private suspend fun handleVideoTitleRequest(call: RoutingCall) {
    val username = call.sessions.get<UserSession>()?.username
        ?: return call.respondJson(
            """{"error": "Unauthorized"}""",
            HttpStatusCode.Unauthorized
        )
    val rawUrl = JSONObject(call.receiveText()).optString("url", "")
    val url = rawUrl.sanitizeVideoUrl()
    if (url.isBlank()) {
        rejectBlankTitleRequest(call, username)
        return
    }
    respondWithVideoTitle(call, username, url)
}

private suspend fun rejectBlankTitleRequest(call: RoutingCall, username: String) {
    PersistentLogger.logAction(
        LogLevel.WARNING,
        username,
        "Attempted to get video title from blank URL"
    )
    call.respondJson("""{"error": "URL is required"}""", HttpStatusCode.BadRequest)
}

private suspend fun respondWithVideoTitle(
    call: RoutingCall,
    username: String,
    url: String
) {
    Logger.i("Getting title for url: $url")
    val title = resolveVideoTitle(url)
    if (title.isNullOrEmpty()) {
        call.respondJson(
            """{"error": "Failed to get title"}""",
            HttpStatusCode.InternalServerError
        )
        PersistentLogger.logAction(LogLevel.ERROR, username, "Failed to get title of $url")
        return
    }
    call.respondJson(JSONObject().put("title", title).toString())
    PersistentLogger.logAction(
        LogLevel.INFO,
        username,
        "Title of $url determined as \"$title\""
    )
}

private suspend fun decodeBase64Url(call: RoutingCall) {
    val username = call.sessions.get<UserSession>()?.username
        ?: return call.respondJson(
            """{"error": "Unauthorized"}""",
            HttpStatusCode.Unauthorized
        )
    val encodedUrl = JSONObject(call.receiveText()).optString("base64", "")
    if (encodedUrl.isBlank()) {
        rejectBlankBase64(call, username)
        return
    }
    decodeAndRespond(call, username, encodedUrl)
}

private suspend fun rejectBlankBase64(call: RoutingCall, username: String) {
    PersistentLogger.logAction(
        LogLevel.WARNING,
        username,
        "Attempted to decode blank base64 string"
    )
    call.respondJson(
        """{"error": "Base64 string is required"}""",
        HttpStatusCode.BadRequest
    )
}

private suspend fun decodeAndRespond(
    call: RoutingCall,
    username: String,
    encodedUrl: String
) {
    Logger.i("Decoding base64 url: $encodedUrl")
    try {
        val decodedUrl = String(Base64.getDecoder().decode(encodedUrl), Charsets.UTF_8)
        logDecodedUrl(username, decodedUrl)
        call.respondJson(JSONObject().put("url", decodedUrl).toString())
    } catch (e: Exception) {
        Logger.e("Failed to decode base64 string: ${e.message}")
        PersistentLogger.logAction(
            LogLevel.ERROR,
            username,
            "Failed to decode base64 string: $encodedUrl"
        )
        call.respondJson(
            """{"error": "Invalid base64 string"}""",
            HttpStatusCode.BadRequest
        )
    }
}

private fun logDecodedUrl(username: String, decodedUrl: String) {
    Logger.i("Successfully decoded base64 URL to: $decodedUrl")
    PersistentLogger.logAction(
        LogLevel.INFO,
        username,
        "Successfully decoded base64 URL to: $decodedUrl"
    )
}

private suspend fun resolveVideoTitle(url: String): String? {
    Logger.i("Getting title for url: $url")
    return withContext(Dispatchers.IO) {
        try {
            if (isYoutubeUrl(url)) fetchYoutubeTitle(url) else fetchGenericTitle(url)
        } catch (e: Exception) {
            Logger.e("Failed to get title: ${e.message}")
            null
        }
    }
}

private fun isYoutubeUrl(url: String): Boolean {
    return url.startsWithAny(
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://youtube.com"
    )
}

private fun fetchYoutubeTitle(url: String): String? {
    val jsonUrl = "https://www.youtube.com/oembed?url=$url&format=json"
    val response = JSONObject(downloadFile(jsonUrl, OkHttpClient()))
    return if (response.has("title")) response.getString("title") else null
}

private suspend fun fetchGenericTitle(url: String): String = coroutineScope {
    val process = ProcessBuilder("yt-dlp", "--get-title", url).start()
    val outputJob = async(Dispatchers.IO) {
        process.inputStream.bufferedReader().readText().trim()
    }
    process.await(PROCESS_TIMEOUT * 1000)
    outputJob.await()
}

private fun downloadMedia(
    rawUrl: String,
    outputDir: String,
    audioOnly: Boolean = false,
    forceMp3Conversion: Boolean = false,
    customFilename: String? = null,
    progressCallback: (Double?) -> Unit = {},
    processCallback: (Process?) -> Unit = {}
): Pair<Int, String> {
    Logger.i("downloadMedia()")
    val validationError = mediaUrlError(rawUrl)
    if (validationError != null) {
        Logger.e(validationError)
        return Pair(-1, validationError)
    }
    val directory = File(outputDir)
    if (!isUsableDirectory(directory)) {
        val error = "Error! $directory is not usable directory!"
        Logger.e(error)
        return Pair(-1, error)
    }
    val options = MediaDownloadOptions(
        directory, audioOnly, forceMp3Conversion, customFilename,
        progressCallback, processCallback
    )
    return downloadMedia(Url(rawUrl), options)
}

private fun mediaUrlError(rawUrl: String): String? {
    if (!rawUrl.isValidUrl()) return "Error! Invalid url: $rawUrl"
    if (rawUrl.contains("playlist")) return "Error! Cannot download playlists!"
    return null
}

private fun isUsableDirectory(directory: File): Boolean {
    val exists = directory.isDirectory || directory.mkdirs()
    return exists && directory.canWrite() && directory.canRead()
}

private fun downloadMedia(url: Url, options: MediaDownloadOptions): Pair<Int, String> {
    Logger.i("downloadMedia(url=$url, outputDir=${options.outputDir.absolutePath})")
    val fullLog = StringBuilder()
    return runBlocking {
        runYtDlp(url, options, fullLog, slowAudioConversion = false)
    }
}

private suspend fun runYtDlp(
    url: Url,
    options: MediaDownloadOptions,
    fullLog: StringBuilder,
    slowAudioConversion: Boolean
): Pair<Int, String> {
    val command = buildYtDlpCommand(url, options, slowAudioConversion)
    Logger.i("Executing yt-dlp with arguments: ${command.joinToString(" ")}")
    val exitCode = runProcess(
        command,
        options.outputDir,
        fullLog,
        options.progressCallback,
        options.processCallback
    )
    return if (shouldRetryAudio(options, exitCode, slowAudioConversion)) {
        runYtDlp(url, options, fullLog, slowAudioConversion = true)
    } else {
        Pair(exitCode, fullLog.toString())
    }
}

private fun buildYtDlpCommand(
    url: Url,
    options: MediaDownloadOptions,
    slowAudioConversion: Boolean
): List<String> {
    val command = baseYtDlpCommand(options.outputDir)
    addCustomFilename(command, options.customFilename)
    addAudioArguments(command, options, slowAudioConversion)
    command.add(url.toString())
    return command
}

private fun baseYtDlpCommand(outputDir: File): MutableList<String> {
    return mutableListOf(
        "yt-dlp", "-v",
        "--js-runtimes", "$JS_RUNTIME_TYPE:$JS_RUNTIME_PATH",
        "--downloader-args", "ffmpeg:-timeout ${PROCESS_TIMEOUT * 1000000}",
        "--match-filters", "!is_live",
        "--no-cache-dir", "--no-playlist",
        "--paths", outputDir.absolutePath
    )
}

private fun addCustomFilename(command: MutableList<String>, customFilename: String?) {
    if (customFilename != null) {
        command.add("-o")
        command.add("$customFilename.%(ext)s")
    }
}

private fun addAudioArguments(
    command: MutableList<String>,
    options: MediaDownloadOptions,
    slowAudioConversion: Boolean
) {
    if (!options.audioOnly) return
    if (options.forceMp3Conversion || slowAudioConversion) {
        command.addAll(listOf("--extract-audio", "--audio-format", "mp3"))
    } else {
        command.addAll(listOf("-f", "bestaudio[ext=m4a]"))
    }
}

private fun shouldRetryAudio(
    options: MediaDownloadOptions,
    exitCode: Int,
    slowAudioConversion: Boolean
): Boolean {
    return options.audioOnly &&
        exitCode != 0 &&
        !slowAudioConversion &&
        !options.forceMp3Conversion
}
