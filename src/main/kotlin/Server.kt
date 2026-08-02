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

/**
 * Stores the authenticated username in the browser session.
 */
@Serializable
data class UserSession(val username: String)

/**
 * Describes the current state and result of an asynchronous download.
 */
data class DownloadTask(
    val status: String,
    val filePath: String? = null,
    val error: String? = null,
    val progress: Double? = null
)

/**
 * Groups the fields submitted when an authenticated user changes their password.
 */
private data class PasswordChangeRequest(
    val currentPassword: String,
    val newPassword: String,
    val confirmation: String
)

/**
 * Holds a normalized download request and exposes its derived audio settings.
 */
private data class DownloadRequest(
    val url: String,
    val format: String,
    val audioConversion: String,
    val customFilename: String
) {
    val audioOnly: Boolean get() = format == "mp3"
    val forceMp3Conversion: Boolean get() = audioOnly && audioConversion == "mp3"
}

/**
 * Carries task identity, user, client address, and options through the download workflow.
 */
private data class DownloadContext(
    val taskId: String,
    val username: String,
    val clientAddress: String,
    val request: DownloadRequest
)

/**
 * Combines the yt-dlp result with the directory where its output should appear.
 */
private data class TaskDownloadResult(
    val exitCode: Int,
    val output: String,
    val taskDir: File
)

/**
 * Collects the file, format, and callback options needed to run yt-dlp.
 */
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

/**
 * Returns the application version generated from the Gradle project version.
 */
private fun appVersion(): String = BuildConfig.VERSION

/**
 * Returns the direct peer address associated with this HTTP connection.
 */
private fun ApplicationCall.clientIpAddress(): String {
    return request.local.remoteAddress
}

/**
 * Records a web action with the direct network address of its client.
 */
private fun ApplicationCall.logPersistentAction(
    logLevel: LogLevel,
    username: String?,
    action: String
) {
    PersistentLogger.logAction(
        logLevel,
        username,
        clientIpAddress(),
        action
    )
}

/**
 * Creates the user manager and starts the embedded HTTP server.
 */
fun startServer() {
    Logger.i("startServer()")

    val userManager = UserManager(File(USERS_FILE))

    embeddedServer(Netty, SERVER_PORT) {
        configureServer(userManager)
    }.start(wait = true)
}

/**
 * Installs server features, registers routes, and records that startup completed.
 */
private fun Application.configureServer(userManager: UserManager) {
    configureSessions()
    configureErrorHandling()
    configureRoutes(userManager)

    Logger.i("yt-dlp-web version ${appVersion()} started")
}

/**
 * Configures the secure browser cookie used for authenticated sessions.
 */
private fun Application.configureSessions() {
    install(Sessions) {
        cookie<UserSession>("SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = LOGIN_SESSION_LENGTH
        }
    }
}

/**
 * Converts uncaught request errors into consistent JSON error responses.
 */
private fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            respondWithServerError(call, cause)
        }
    }
}

/**
 * Sends details about an unexpected server error as a JSON response.
 */
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

/**
 * Connects each public URL to its request handler and exposes static resources.
 */
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

/**
 * Validates submitted credentials and completes or rejects the login attempt.
 */
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

/**
 * Creates a user session, confirms the login, and records the successful action.
 */
private suspend fun completeLogin(call: RoutingCall, username: String) {
    call.sessions.set(UserSession(username))
    call.respondJson("""{"ok": true}""")

    call.logPersistentAction(LogLevel.INFO, username, "Successful login")
}

/**
 * Returns an authentication error and records the failed login attempt.
 */
private suspend fun rejectLogin(call: RoutingCall, username: String) {
    call.respondJson(
        """{"error": "Nesprávné jméno nebo heslo."}""",
        HttpStatusCode.Unauthorized
    )

    call.logPersistentAction(
        LogLevel.WARNING,
        UNKNOWN_USER,
        "Login failed (user: $username)"
    )
}

/**
 * Clears the active session, confirms logout, and records the action.
 */
private suspend fun performLogout(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()

    call.sessions.clear<UserSession>()
    call.respondJson("""{"ok": true}""")

    call.logPersistentAction(LogLevel.INFO, session?.username, "Logout")
}

/**
 * Authorizes, parses, and validates a request before changing the user's password.
 */
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

/**
 * Parses password-change fields from the request body.
 */
private suspend fun readPasswordChangeRequest(call: RoutingCall): PasswordChangeRequest {
    val body = JSONObject(call.receiveText())

    return PasswordChangeRequest(
        body.optString("currentPassword", ""),
        body.optString("newPassword", ""),
        body.optString("confirmNewPassword", "")
    )
}

/**
 * Returns a user-facing validation message when a proposed password is invalid.
 */
private fun validatePasswordChange(request: PasswordChangeRequest): String? {
    if (request.currentPassword.isEmpty()) {
        return "Aktuální heslo nesmí být prázdné."
    }

    if (request.newPassword.isEmpty()) {
        return "Nové heslo nesmí být prázdné."
    }

    if (request.newPassword != request.confirmation) {
        return "Nová hesla se neshodují."
    }

    return null
}

/**
 * Applies a password change and maps expected or unexpected failures to responses.
 */
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

/**
 * Ends the current session, confirms the password change, and records the action.
 */
private suspend fun completePasswordChange(call: RoutingCall, username: String) {
    call.sessions.clear<UserSession>()
    call.respondJson("""{"ok": true}""")

    call.logPersistentAction(
        LogLevel.INFO,
        username,
        "Password changed successfully. User logged out."
    )
}

/**
 * Reports a rejected password change caused by invalid user input.
 */
private suspend fun rejectPasswordChange(
    call: RoutingCall,
    username: String,
    error: IllegalArgumentException
) {
    val message = error.message ?: "Chyba při změně hesla."

    call.respondJson("""{"error": "$message"}""", HttpStatusCode.BadRequest)

    call.logPersistentAction(
        LogLevel.WARNING,
        username,
        "Failed to change password: ${error.message}"
    )
}

/**
 * Reports and records an unexpected failure while changing a password.
 */
private suspend fun failPasswordChange(
    call: RoutingCall,
    username: String,
    error: Exception
) {
    call.respondJson(
        """{"error": "Chyba při změně hesla."}""",
        HttpStatusCode.InternalServerError
    )

    call.logPersistentAction(
        LogLevel.ERROR,
        username,
        "Exception while changing password: ${error.message}"
    )
}

/**
 * Calculates password entropy and returns a generic error when calculation fails.
 */
private suspend fun handlePasswordEntropy(userManager: UserManager, call: RoutingCall) {
    val username = call.sessions.get<UserSession>()?.username

    try {
        val password = JSONObject(call.receiveText()).optString("password", "")

        call.respondJson("""{"entropy": ${userManager.computeEntropy(password)}}""")
    } catch (_: Exception) {
        call.logPersistentAction(
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

/**
 * Returns the logged-in username or an unauthorized response.
 */
private suspend fun provideUserInfo(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()

    if (session != null) {
        call.respondJson("""{"username": "${session.username}"}""")
    } else {
        call.respondJson("""{"error": "Unauthorized"}""", HttpStatusCode.Unauthorized)
    }
}

/**
 * Validates a download request, starts its background task, and returns its identifier.
 */
private suspend fun performDownload(call: RoutingCall) {
    val username = requireDownloadUser(call) ?: return
    val request = readDownloadRequest(call)

    if (request.url.isBlank()) {
        return call.respondJson(
            """{"error": "URL is required"}""",
            HttpStatusCode.BadRequest
        )
    }

    val taskId = startDownloadTask(
        username,
        call.clientIpAddress(),
        request
    )

    call.respondJson("""{"task_id": "$taskId"}""")
}

/**
 * Returns the requesting username or responds when no user is logged in.
 */
private suspend fun requireDownloadUser(call: RoutingCall): String? {
    val username = call.sessions.get<UserSession>()?.username

    if (username == null) {
        Logger.e("User not logged in!")

        call.logPersistentAction(
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

/**
 * Parses and normalizes all supported options from a download request.
 */
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

/**
 * Removes characters that cannot safely be used in a downloaded file name.
 */
private fun sanitizeFilename(filename: String): String {
    return filename.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "")
}

/**
 * Creates and launches a tracked background task for a download request.
 */
private fun startDownloadTask(
    username: String,
    clientAddress: String,
    request: DownloadRequest
): String {
    val taskId = UUID.randomUUID().toString()
    val context = DownloadContext(taskId, username, clientAddress, request)

    logDownloadStarted(context)
    tasks[taskId] = DownloadTask(status = "processing")

    taskJobs[taskId] = CoroutineScope(Dispatchers.IO).launch {
        executeDownloadTask(context)
    }

    return taskId
}

/**
 * Records the options and owner of a newly started download.
 */
private fun logDownloadStarted(context: DownloadContext) {
    val request = context.request

    Logger.i(
        "Received download request. Task ID: ${context.taskId}, URL: ${request.url}, " +
            "Format: ${request.format}, Audio conversion: ${request.audioConversion}"
    )

    PersistentLogger.logAction(
        LogLevel.INFO,
        context.username,
        context.clientAddress,
        "Started ${request.format} download of ${request.url}" +
            " (audio conversion: ${request.audioConversion}, custom name: ${request.customFilename})"
    )
}

/**
 * Runs a download task and handles success, failure, cancellation, and cleanup.
 */
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

/**
 * Prepares the task directory and executes the requested media download.
 */
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

/**
 * Updates progress only while a download remains active.
 */
private fun updateTaskProgress(taskId: String, progress: Double?) {
    val task = tasks[taskId]

    if (task?.status == "processing") {
        tasks[taskId] = task.copy(progress = progress)
    }
}

/**
 * Tracks the active process or terminates it immediately when its task was cancelled.
 */
private fun trackTaskProcess(taskId: String, process: Process?) {
    when {
        process == null -> taskProcesses.remove(taskId)
        tasks[taskId]?.status == "cancelled" -> destroyProcessTree(process)
        else -> taskProcesses[taskId] = process
    }
}

/**
 * Dispatches a completed yt-dlp process to the success or failure handler.
 */
private fun handleDownloadResult(context: DownloadContext, result: TaskDownloadResult) {
    if (result.exitCode == 0) {
        completeSuccessfulDownload(context, result.taskDir)
    } else {
        failYtDlpDownload(context, result)
    }
}

/**
 * Locates a completed file, stores its path, and records the successful download.
 */
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
        context.clientAddress,
        completedDownloadLog(context.request)
    )
}

/**
 * Builds the persistent log message for a successfully completed download.
 */
private fun completedDownloadLog(request: DownloadRequest): String {
    return "Completed ${request.format} download of ${request.url}" +
        " (audio conversion: ${request.audioConversion}, custom name: ${request.customFilename})"
}

/**
 * Marks a task as failed when yt-dlp exits successfully but creates no file.
 */
private fun handleMissingDownload(context: DownloadContext, taskDir: File) {
    Logger.e(
        "yt-dlp finished with exit code 0 but NO FILE was found in ${taskDir.absolutePath}"
    )

    PersistentLogger.logAction(
        LogLevel.ERROR,
        context.username,
        context.clientAddress,
        "Downloaded file not found for (${downloadDetails(context.request)})"
    )

    tasks[context.taskId] = DownloadTask(
        status = "error",
        error = "Staženo, ale soubor nenalezen"
    )
}

/**
 * Records yt-dlp output and marks a task as failed when the process exits with an error.
 */
private fun failYtDlpDownload(context: DownloadContext, result: TaskDownloadResult) {
    Logger.e(
        "yt-dlp failed for taskId=${context.taskId}. Exit code: ${result.exitCode}"
    )

    PersistentLogger.logAction(
        LogLevel.ERROR,
        context.username,
        context.clientAddress,
        "yt-dlp failed (exit-code: ${result.exitCode}, ${downloadDetails(context.request)})" +
            "\n\n--- YT-DLP OUTPUT ---\n${result.output}---------------------\n"
    )

    tasks[context.taskId] = DownloadTask(
        status = "error",
        error = "Něco se pokazilo (yt-dlp: ${result.exitCode})"
    )
}

/**
 * Ensures a cancelled task has the correct state and records the cancellation.
 */
private fun handleDownloadCancellation(taskId: String) {
    if (tasks[taskId]?.status != "cancelled") {
        tasks[taskId] = DownloadTask(
            status = "cancelled",
            error = "Stahování bylo zrušeno"
        )
    }

    Logger.i("Download cancelled for taskId=$taskId")
}

/**
 * Records an unexpected download exception and exposes its message through task status.
 */
private fun handleDownloadException(context: DownloadContext, error: Exception) {
    Logger.e(
        "Exception during download for taskId=${context.taskId}: ${error.message}",
        error
    )

    PersistentLogger.logAction(
        LogLevel.ERROR,
        context.username,
        context.clientAddress,
        "Exception during download! (msg: ${error.message}, ${downloadDetails(context.request)})"
    )

    tasks[context.taskId] = DownloadTask(
        status = "error",
        error = error.message ?: "Neznámá chyba"
    )
}

/**
 * Formats the request details shared by download error log messages.
 */
private fun downloadDetails(request: DownloadRequest): String {
    return "format: ${request.format}, audio conversion: ${request.audioConversion}, " +
        "url: ${request.url}, custom name: ${request.customFilename}"
}

/**
 * Authorizes a cancellation request and stops an active download task.
 */
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
    logDownloadCancelled(call, taskId, username)

    call.respondJson("""{"ok": true, "status": "cancelled"}""")
}

/**
 * Marks a task as cancelled, terminates its process, and cancels its coroutine.
 */
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

/**
 * Records who requested cancellation of a download task.
 */
private fun logDownloadCancelled(
    call: RoutingCall,
    taskId: String,
    username: String
) {
    Logger.i("Cancel requested for taskId=$taskId by user=$username")

    call.logPersistentAction(
        LogLevel.INFO,
        username,
        "Cancelled download task $taskId"
    )
}

/**
 * Returns the current task state or the download URL for a completed task.
 */
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

/**
 * Serializes a non-completed task state for the status endpoint.
 */
private fun taskStatusJson(task: DownloadTask): JSONObject {
    val response = JSONObject().put("status", task.status)

    if (task.error != null) response.put("error", task.error)
    if (task.progress != null) response.put("progress", task.progress)

    return response
}

/**
 * Authorizes a file request, verifies the result, and serves the downloaded file.
 */
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

/**
 * Resolves a completed task's file or reports that it is not ready.
 */
private suspend fun resolveDownloadedFile(call: RoutingCall, taskId: String): File? {
    val task = tasks[taskId]

    if (task == null || task.status != "completed" || task.filePath == null) {
        call.respondText("File not ready", status = HttpStatusCode.BadRequest)
        return null
    }

    return File(task.filePath)
}

/**
 * Reports and records that a completed task's output file disappeared.
 */
private suspend fun handleMissingServedFile(
    call: RoutingCall,
    username: String,
    taskId: String,
    file: File
) {
    Logger.e(
        "Failed to serve file for taskId=$taskId: ${file.absolutePath} - File not found!"
    )

    call.logPersistentAction(
        LogLevel.ERROR,
        username,
        "Failed to serve file! (${file.absolutePath} - File not found)"
    )

    call.respondText("File not found", status = HttpStatusCode.NotFound)
}

/**
 * Adds download headers, records the transfer, and sends the requested file.
 */
private suspend fun serveDownloadedFile(
    call: RoutingCall,
    username: String,
    taskId: String,
    file: File
) {
    Logger.i("Serving file to user for taskId=$taskId: ${file.absolutePath}")

    call.logPersistentAction(
        LogLevel.INFO,
        username,
        "Serving file: ${file.absolutePath}"
    )

    call.response.header(HttpHeaders.ContentDisposition, contentDisposition(file))
    call.respondFile(file)
}

/**
 * Builds a Content-Disposition value that supports both basic and UTF-8 file names.
 */
private fun contentDisposition(file: File): String {
    val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
    val quotedName = file.name.replace("\"", "\\\"")

    return "attachment; filename=\"$quotedName\"; filename*=UTF-8''$encodedName"
}

/**
 * Returns the version currently embedded in the running application.
 */
private suspend fun provideVersion(call: RoutingCall) {
    call.respondJson("""{"version": "${appVersion()}"}""")
}

/**
 * Redirects the root URL to the appropriate page while preserving its query string.
 */
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

/**
 * Serves the application page only to authenticated users.
 */
private suspend fun provideProtectedIndex(call: RoutingCall) {
    if (call.sessions.get<UserSession>() == null) {
        call.respondRedirect("/login.html")
    } else {
        respondStaticHtml(call, "static/index.html")
    }
}

/**
 * Serves the login page only when the visitor has no active session.
 */
private suspend fun provideProtectedLogin(call: RoutingCall) {
    if (call.sessions.get<UserSession>() != null) {
        call.respondRedirect("/index.html")
    } else {
        respondStaticHtml(call, "static/login.html")
    }
}

/**
 * Loads an HTML resource from the application and returns a not-found response when absent.
 */
private suspend fun respondStaticHtml(call: RoutingCall, resourcePath: String) {
    val text = UserSession::class.java.classLoader.getResource(resourcePath)?.readText()

    if (text == null) {
        call.respondText("Not found", status = HttpStatusCode.NotFound)
    } else {
        call.respondText(text, ContentType.Text.Html)
    }
}

/**
 * Authorizes and validates a request before resolving a video's title.
 */
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

/**
 * Rejects and records a title request that did not contain a URL.
 */
private suspend fun rejectBlankTitleRequest(call: RoutingCall, username: String) {
    call.logPersistentAction(
        LogLevel.WARNING,
        username,
        "Attempted to get video title from blank URL"
    )

    call.respondJson("""{"error": "URL is required"}""", HttpStatusCode.BadRequest)
}

/**
 * Resolves a title, returns it to the client, and records the outcome.
 */
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

        call.logPersistentAction(LogLevel.ERROR, username, "Failed to get title of $url")
        return
    }

    call.respondJson(JSONObject().put("title", title).toString())

    call.logPersistentAction(
        LogLevel.INFO,
        username,
        "Title of $url determined as \"$title\""
    )
}

/**
 * Authorizes and validates a request before decoding its Base64 URL value.
 */
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

/**
 * Rejects and records a decode request that did not contain Base64 text.
 */
private suspend fun rejectBlankBase64(call: RoutingCall, username: String) {
    call.logPersistentAction(
        LogLevel.WARNING,
        username,
        "Attempted to decode blank base64 string"
    )

    call.respondJson(
        """{"error": "Base64 string is required"}""",
        HttpStatusCode.BadRequest
    )
}

/**
 * Decodes a Base64 URL and returns it, or reports malformed input.
 */
private suspend fun decodeAndRespond(
    call: RoutingCall,
    username: String,
    encodedUrl: String
) {
    Logger.i("Decoding base64 url: $encodedUrl")

    try {
        val decodedUrl = String(Base64.getDecoder().decode(encodedUrl), Charsets.UTF_8)

        logDecodedUrl(call, username, decodedUrl)
        call.respondJson(JSONObject().put("url", decodedUrl).toString())
    } catch (e: Exception) {
        Logger.e("Failed to decode base64 string: ${e.message}")

        call.logPersistentAction(
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

/**
 * Records a successfully decoded URL in both application logs.
 */
private fun logDecodedUrl(
    call: RoutingCall,
    username: String,
    decodedUrl: String
) {
    Logger.i("Successfully decoded base64 URL to: $decodedUrl")

    call.logPersistentAction(
        LogLevel.INFO,
        username,
        "Successfully decoded base64 URL to: $decodedUrl"
    )
}

/**
 * Chooses the appropriate title provider and shields callers from lookup failures.
 */
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

/**
 * Reports whether a URL belongs to one of the supported YouTube host forms.
 */
private fun isYoutubeUrl(url: String): Boolean {
    return url.startsWithAny(
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://youtube.com"
    )
}

/**
 * Fetches a YouTube title through the public oEmbed endpoint.
 */
private fun fetchYoutubeTitle(url: String): String? {
    val jsonUrl = "https://www.youtube.com/oembed?url=$url&format=json"
    val response = JSONObject(downloadFile(jsonUrl, OkHttpClient()))

    return if (response.has("title")) response.getString("title") else null
}

/**
 * Uses yt-dlp to read a title for sites that do not use the YouTube oEmbed endpoint.
 */
private suspend fun fetchGenericTitle(url: String): String = coroutineScope {
    val process = ProcessBuilder("yt-dlp", "--get-title", url).start()

    val outputJob = async(Dispatchers.IO) {
        process.inputStream.bufferedReader().readText().trim()
    }

    process.await(PROCESS_TIMEOUT * 1000)

    outputJob.await()
}

/**
 * Validates raw download inputs and converts them into options for yt-dlp.
 */
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

    val directory = File(outputDir)
    val validationError = downloadRequestError(rawUrl, directory)

    if (validationError != null) {
        Logger.e(validationError)
        return Pair(-1, validationError)
    }

    val options = MediaDownloadOptions(
        directory, audioOnly, forceMp3Conversion, customFilename,
        progressCallback, processCallback
    )

    return downloadMedia(Url(rawUrl), options)
}

/**
 * Returns the first URL or output-directory error found for a download request.
 */
private fun downloadRequestError(rawUrl: String, directory: File): String? {
    return mediaUrlError(rawUrl)
        ?: if (isUsableDirectory(directory)) {
            null
        } else {
            "Error! $directory is not usable directory!"
        }
}

/**
 * Returns a validation error for malformed or unsupported media URLs.
 */
private fun mediaUrlError(rawUrl: String): String? {
    if (!rawUrl.isValidUrl()) return "Error! Invalid url: $rawUrl"
    if (rawUrl.contains("playlist")) return "Error! Cannot download playlists!"

    return null
}

/**
 * Ensures a directory exists and is readable and writable.
 */
private fun isUsableDirectory(directory: File): Boolean {
    val exists = directory.isDirectory || directory.mkdirs()

    return exists && directory.canWrite() && directory.canRead()
}

/**
 * Runs the suspendable yt-dlp workflow from the synchronous download task.
 */
private fun downloadMedia(url: Url, options: MediaDownloadOptions): Pair<Int, String> {
    Logger.i("downloadMedia(url=$url, outputDir=${options.outputDir.absolutePath})")

    val fullLog = StringBuilder()

    return runBlocking {
        runYtDlp(url, options, fullLog, slowAudioConversion = false)
    }
}

/**
 * Executes yt-dlp and retries failed fast audio downloads with MP3 conversion when allowed.
 */
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

/**
 * Assembles the complete yt-dlp command for the selected output options.
 */
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

/**
 * Creates the shared yt-dlp arguments used by every download.
 */
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

/**
 * Adds a custom output template when the user supplied a file name.
 */
private fun addCustomFilename(command: MutableList<String>, customFilename: String?) {
    if (customFilename != null) {
        command.add("-o")
        command.add("$customFilename.%(ext)s")
    }
}

/**
 * Selects fast M4A extraction or explicit MP3 conversion for audio downloads.
 */
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

/**
 * Decides whether a failed fast audio download should be retried as MP3.
 */
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
