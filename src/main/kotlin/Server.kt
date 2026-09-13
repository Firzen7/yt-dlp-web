package net.firzen.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.origin
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
import kotlinx.serialization.json.Json
import net.firzen.web.logging.LogLevel
import net.firzen.web.logging.Logger
import net.firzen.web.logging.PersistentLogger
import net.firzen.web.tools.AUDIO_CONVERSION_MP3
import net.firzen.web.tools.AUDIO_MODE
import net.firzen.web.tools.DOWNLOAD_DIRECTORY
import net.firzen.web.tools.JS_RUNTIME_PATH
import net.firzen.web.tools.JS_RUNTIME_TYPE
import net.firzen.web.tools.LOGIN_SESSION_LENGTH
import net.firzen.web.tools.PROCESS_TIMEOUT
import net.firzen.web.tools.SERVER_PORT
import net.firzen.web.tools.UNKNOWN_USER
import net.firzen.web.tools.USERS_FILE
import net.firzen.web.tools.VIDEO_MODE
import net.firzen.web.tools.await
import net.firzen.web.tools.destroyProcessTree
import net.firzen.web.tools.downloadFile
import net.firzen.web.tools.isValidUrl
import net.firzen.web.tools.respondJson
import net.firzen.web.tools.runProcess
import net.firzen.web.tools.sanitizeVideoUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Stores the authenticated username in the browser session.
 *
 * @param username authenticated account name
 */
@Serializable
data class UserSession(val username: String)

/**
 * Describes the current state and result of an asynchronous download.
 *
 * @param status current task state
 * @param filePath completed output path, or `null` before completion
 * @param error failure details, or `null` when no failure occurred
 * @param progress download percentage, or `null` when unavailable
 */
data class DownloadTask(
    val status: String,
    val filePath: String? = null,
    val error: String? = null,
    val progress: Double? = null
)

/**
 * Groups the fields submitted when an authenticated user changes their password.
 *
 * @param currentPassword current account password
 * @param newPassword proposed replacement password
 * @param confirmation repeated replacement password
 */
private data class PasswordChangeRequest(
    val currentPassword: String,
    val newPassword: String,
    val confirmation: String
)

/**
 * Holds a normalized download request and exposes its derived media settings.
 *
 * @param url sanitized media URL
 * @param format requested video or audio mode
 * @param audioConversion requested audio-conversion strategy
 * @param resolution requested video resolution, or `null` for the best available
 * @param customFilename requested output filename without unsafe characters
 */
private data class DownloadRequest(
    val url: String,
    val format: String,
    val audioConversion: String,
    val resolution: Resolution?,
    val customFilename: String
) {
    val audioOnly: Boolean get() = format == AUDIO_CONVERSION_MP3
    val forceMp3Conversion: Boolean get() = audioOnly && audioConversion == AUDIO_CONVERSION_MP3
}

/**
 * Carries task identity, user, client address, and options through the download workflow.
 *
 * @param taskId unique task identifier
 * @param username authenticated account that started the task
 * @param clientAddress network address of the requesting client
 * @param request normalized download request
 */
private data class DownloadContext(
    val taskId: String,
    val username: String,
    val clientAddress: String,
    val request: DownloadRequest
)

/**
 * Combines the yt-dlp result with the directory where its output should appear.
 *
 * @param exitCode yt-dlp process exit code
 * @param output collected process output
 * @param taskDir directory containing task output
 */
private data class TaskDownloadResult(
    val exitCode: Int,
    val output: String,
    val taskDir: File
)

/**
 * Collects the file, format, and callback options needed to run yt-dlp.
 *
 * @param outputDir working and output directory for yt-dlp
 * @param audioOnly whether only an audio stream should be downloaded
 * @param forceMp3Conversion whether audio must be converted to MP3
 * @param resolution exact requested video resolution, or `null` for the best available
 * @param customFilename custom output filename, or `null` to use yt-dlp metadata
 * @param progressCallback callback notified of download progress
 * @param processCallback callback notified when the child process changes
 */
private data class MediaDownloadOptions(
    val outputDir: File,
    val audioOnly: Boolean,
    val forceMp3Conversion: Boolean,
    val resolution: Resolution?,
    val customFilename: String?,
    val progressCallback: (Double?) -> Unit,
    val processCallback: (Process?) -> Unit
)

private val tasks = ConcurrentHashMap<String, DownloadTask>()
private val taskJobs = ConcurrentHashMap<String, Job>()
private val taskProcesses = ConcurrentHashMap<String, Process>()
private val YOUTUBE_DOMAINS = setOf("youtube.com", "youtu.be", "youtube-nocookie.com")
private val YOUTUBE_VIDEO_ID_PATTERN = Regex("^[A-Za-z0-9_-]{11}$")

private const val MEDIA_FORMAT_TEMPLATE =
    "%(formats.:.{format_id,width,height,fps,ext,vcodec,acodec})#j"

/**
 * Returns the application version generated from the Gradle project version.
 *
 * @return application version string
 */
private fun appVersion(): String = BuildConfig.VERSION

/**
 * Returns the client address reconstructed from trusted proxy headers.
 *
 * @receiver request call whose origin should be resolved
 * @return resolved client network address
 */
private fun ApplicationCall.clientIpAddress(): String {
    return request.origin.remoteAddress
}

/**
 * Records a web action with the resolved network address of its client.
 *
 * @receiver request call associated with the action
 * @param logLevel severity assigned to the persistent entry
 * @param username authenticated username, or `null` when unavailable
 * @param action action description to record
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
 *
 * @receiver Ktor application to configure
 * @param userManager credential manager shared by request handlers
 */
private fun Application.configureServer(userManager: UserManager) {
    install(XForwardedHeaders) {
        useLastProxy()
    }

    configureSessions()
    configureErrorHandling()
    configureRoutes(userManager)

    Logger.i("yt-dlp-web version ${appVersion()} started")
}

/**
 * Configures the secure browser cookie used for authenticated sessions.
 *
 * @receiver Ktor application receiving session support
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
 *
 * @receiver Ktor application receiving error handling
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
 *
 * @param call request call receiving the error response
 * @param cause uncaught failure being reported
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
 *
 * @receiver Ktor application receiving routes
 * @param userManager credential manager used by authentication routes
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
        get("/login") { provideLoginPage(call) }
        get("/index.html") { redirectToCanonicalPage(call, "/") }
        get("/login.html") { redirectToCanonicalPage(call, "/login") }
        post("/api/title") { handleVideoTitleRequest(call) }
        post("/api/resolutions") { handleResolutionRequest(call) }
        post("/api/decode") { decodeBase64Url(call) }
        post("/api/change-password") { handleChangePassword(userManager, call) }
        post("/api/password-entropy") { handlePasswordEntropy(userManager, call) }
        staticResources("/", "static")
    }
}

/**
 * Validates submitted credentials and completes or rejects the login attempt.
 *
 * @param userManager credential manager used for validation
 * @param call login request call
 * @throws org.json.JSONException when the request body is not valid JSON
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
 *
 * @param call authenticated login request call
 * @param username account that successfully authenticated
 */
private suspend fun completeLogin(call: RoutingCall, username: String) {
    call.sessions.set(UserSession(username))
    call.respondJson("""{"ok": true}""")

    call.logPersistentAction(LogLevel.INFO, username, "Successful login")
}

/**
 * Returns an authentication error and records the failed login attempt.
 *
 * @param call rejected login request call
 * @param username submitted username recorded in the audit log
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
 *
 * @param call logout request call
 */
private suspend fun performLogout(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()

    call.sessions.clear<UserSession>()
    call.respondJson("""{"ok": true}""")

    call.logPersistentAction(LogLevel.INFO, session?.username, "Logout")
}

/**
 * Authorizes, parses, and validates a request before changing the user's password.
 *
 * @param userManager credential manager used to update the password
 * @param call password-change request call
 * @throws org.json.JSONException when the request body is not valid JSON
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
 *
 * @param call request containing password-change JSON
 * @return parsed password-change fields
 * @throws org.json.JSONException when the request body is not valid JSON
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
 *
 * @param request password-change values to validate
 * @return validation message, or `null` when the request is valid
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
 *
 * @param userManager credential manager used to persist the replacement password
 * @param call request call receiving the outcome
 * @param username account whose password should change
 * @param request validated password-change values
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
 *
 * @param call request call receiving the success response
 * @param username account whose password changed
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
 *
 * @param call request call receiving the rejection response
 * @param username account associated with the rejected change
 * @param error validation failure to report
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
 *
 * @param call request call receiving the failure response
 * @param username account associated with the failed change
 * @param error unexpected failure to record
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
 *
 * @param userManager manager providing the entropy calculation
 * @param call request containing the password and receiving the response
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
 *
 * @param call request call receiving account information or an error
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
 *
 * @param call download request call
 * @throws org.json.JSONException when the request body is not valid JSON
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
 *
 * @param call request call whose session should be inspected
 * @return authenticated username, or `null` after sending an error response
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
 *
 * @param call request containing download JSON
 * @return normalized download request
 * @throws org.json.JSONException when the request body is not valid JSON
 */
private suspend fun readDownloadRequest(call: RoutingCall): DownloadRequest {
    val body = JSONObject(call.receiveText())
    val format = body.optString("format", VIDEO_MODE)
        .takeIf { it == VIDEO_MODE || it == AUDIO_CONVERSION_MP3 } ?: VIDEO_MODE
    val audioConversion = body.optString("audioConversion", "fastest")
        .lowercase()
        .takeIf { it == "fastest" || it == AUDIO_CONVERSION_MP3 } ?: "fastest"

    return DownloadRequest(
        body.optString("url", "").sanitizeVideoUrl(),
        format,
        audioConversion,
        readSelectedResolution(body).takeIf { format == VIDEO_MODE },
        sanitizeFilename(body.optString("filename", ""))
    )
}

/**
 * Reads a positive width and height from an optional resolution request object.
 *
 * @param body download request JSON containing an optional resolution object
 * @return selected positive resolution, or `null` when absent or invalid
 */
private fun readSelectedResolution(body: JSONObject): Resolution? {
    val resolution = body.optJSONObject("resolution") ?: return null
    val width = resolution.optInt("width").takeIf { it > 0 } ?: return null
    val height = resolution.optInt("height").takeIf { it > 0 } ?: return null

    return Resolution(width, height)
}

/**
 * Removes characters that cannot safely be used in a downloaded file name.
 *
 * @param filename requested filename to sanitize
 * @return filename without unsupported characters
 */
private fun sanitizeFilename(filename: String): String {
    return filename.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "")
}

/**
 * Creates and launches a tracked background task for a download request.
 *
 * @param username authenticated account starting the download
 * @param clientAddress network address of the requesting client
 * @param request normalized download request
 * @return unique identifier assigned to the new task
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
 *
 * @param context task context to record
 */
private fun logDownloadStarted(context: DownloadContext) {
    val request = context.request

    Logger.i(
        "Received download request. Task ID: ${context.taskId}, URL: ${request.url}, " +
            "Format: ${request.format}, Resolution: ${request.resolution ?: "best"}, " +
            "Audio conversion: ${request.audioConversion}"
    )

    val mode = if(request.audioOnly) AUDIO_MODE else VIDEO_MODE

    PersistentLogger.logAction(
        LogLevel.INFO,
        context.username,
        context.clientAddress,
        "Started $mode download of ${request.url}" +
            " (${downloadDetails(request)})"
    )
}

/**
 * Runs a download task and handles success, failure, cancellation, and cleanup.
 *
 * @param context task context to execute
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
 *
 * @param context task context containing request and destination details
 * @return child-process result and task output directory
 */
private fun runTaskDownload(context: DownloadContext): TaskDownloadResult {
    val request = context.request
    val taskDir = File(DOWNLOAD_DIRECTORY, context.taskId)

    if (!taskDir.exists()) taskDir.mkdirs()

    val result = downloadMedia(
        rawUrl = request.url,
        outputDir = taskDir.absolutePath,
        audioOnly = request.audioOnly,
        forceMp3Conversion = request.forceMp3Conversion,
        resolution = request.resolution,
        customFilename = request.customFilename.takeIf { it.isNotBlank() },
        progressCallback = { updateTaskProgress(context.taskId, it) },
        processCallback = { trackTaskProcess(context.taskId, it) }
    )

    return TaskDownloadResult(result.first, result.second, taskDir)
}

/**
 * Updates progress only while a download remains active.
 *
 * @param taskId task whose progress should change
 * @param progress latest percentage, or `null` when progress is unavailable
 */
private fun updateTaskProgress(taskId: String, progress: Double?) {
    val task = tasks[taskId]

    if (task?.status == "processing") {
        tasks[taskId] = task.copy(progress = progress)
    }
}

/**
 * Tracks the active process or terminates it immediately when its task was cancelled.
 *
 * @param taskId task associated with the process
 * @param process active process, or `null` when process tracking should be cleared
 * @throws InterruptedException when termination is interrupted for an already cancelled task
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
 *
 * @param context completed task context
 * @param result yt-dlp process result
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
 *
 * @param context completed task context
 * @param taskDir directory expected to contain downloaded output
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
 *
 * @param request completed download request
 * @return formatted persistent-log message
 */
private fun completedDownloadLog(request: DownloadRequest): String {
    return "Completed ${request.format} download of ${request.url}" +
        " (${downloadDetails(request)})"
}

/**
 * Marks a task as failed when yt-dlp exits successfully but creates no file.
 *
 * @param context failed task context
 * @param taskDir directory where output was expected
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
 *
 * @param context failed task context
 * @param result failed yt-dlp process result
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
 *
 * @param taskId cancelled task identifier
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
 *
 * @param context failed task context
 * @param error unexpected download failure
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
 *
 * @param request request whose options should be formatted
 * @return formatted request details
 */
private fun downloadDetails(request: DownloadRequest): String {
    return "format: ${request.format}, audio conversion: ${request.audioConversion}, " +
        "resolution: ${request.resolution ?: "best"}, url: ${request.url}, " +
        "custom name: ${request.customFilename}"
}

/**
 * Authorizes a cancellation request and stops an active download task.
 *
 * @param call cancellation request call
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
 *
 * @param taskId task identifier to cancel
 * @param task current task state to update
 * @throws InterruptedException when process termination is interrupted
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
 *
 * @param call cancellation request used for persistent logging
 * @param taskId cancelled task identifier
 * @param username account that requested cancellation
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
 *
 * @param call status request call
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
 *
 * @param task task state to serialize
 * @return JSON representation of task status, error, and progress
 */
private fun taskStatusJson(task: DownloadTask): JSONObject {
    val response = JSONObject().put("status", task.status)

    if (task.error != null) response.put("error", task.error)
    if (task.progress != null) response.put("progress", task.progress)

    return response
}

/**
 * Authorizes a file request, verifies the result, and serves the downloaded file.
 *
 * @param call file-download request call
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
 *
 * @param call request call receiving an error when the file is unavailable
 * @param taskId completed task identifier
 * @return downloaded file, or `null` after sending an error response
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
 *
 * @param call file request receiving the not-found response
 * @param username account requesting the file
 * @param taskId completed task identifier
 * @param file expected output file
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
 *
 * @param call file request receiving the response
 * @param username account requesting the file
 * @param taskId completed task identifier
 * @param file output file to serve
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
 *
 * @param file file whose name should be encoded
 * @return attachment Content-Disposition header value
 * @throws java.io.UnsupportedEncodingException when UTF-8 encoding is unavailable
 */
private fun contentDisposition(file: File): String {
    val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
    val quotedName = file.name.replace("\"", "\\\"")

    return "attachment; filename=\"$quotedName\"; filename*=UTF-8''$encodedName"
}

/**
 * Returns the version currently embedded in the running application.
 *
 * @param call version request call
 */
private suspend fun provideVersion(call: RoutingCall) {
    call.respondJson("""{"version": "${appVersion()}"}""")
}

/**
 * Serves the application at the root URL or redirects unauthenticated visitors to login.
 *
 * @param call root-page request call
 */
private suspend fun provideWebpage(call: RoutingCall) {
    if (call.sessions.get<UserSession>() == null) {
        call.respondRedirect(call.pathWithQuery("/login"))
    } else {
        respondStaticHtml(call, "static/index.html")
    }
}

/**
 * Serves the login page or returns authenticated visitors to the application root.
 *
 * @param call login-page request call
 */
private suspend fun provideLoginPage(call: RoutingCall) {
    if (call.sessions.get<UserSession>() != null) {
        call.respondRedirect(call.pathWithQuery("/"))
    } else {
        respondStaticHtml(call, "static/login.html")
    }
}

/**
 * Permanently redirects an old HTML page URL to its canonical route.
 *
 * @param call legacy-page request call
 * @param path canonical redirect destination
 */
private suspend fun redirectToCanonicalPage(call: RoutingCall, path: String) {
    call.respondRedirect(call.pathWithQuery(path), permanent = true)
}

/**
 * Appends the current request query string to a redirect destination.
 *
 * @receiver request call containing the query string
 * @param path redirect destination path
 * @return destination with the current query string appended when present
 */
private fun ApplicationCall.pathWithQuery(path: String): String {
    val queryString = request.queryString()

    return if (queryString.isEmpty()) path else "$path?$queryString"
}

/**
 * Loads an HTML resource from the application and returns a not-found response when absent.
 *
 * @param call page request receiving the resource or not-found response
 * @param resourcePath classpath path of the HTML resource
 * @throws java.io.IOException when an existing resource cannot be read
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
 * Authorizes a request and returns the video resolutions reported by yt-dlp.
 *
 * @param call resolution request call
 * @throws org.json.JSONException when the request body is not valid JSON
 * @throws CancellationException when the request coroutine is cancelled
 */
private suspend fun handleResolutionRequest(call: RoutingCall) {
    val username = call.sessions.get<UserSession>()?.username
        ?: return call.respondJson(
            """{"error": "Unauthorized"}""",
            HttpStatusCode.Unauthorized
        )
    val url = JSONObject(call.receiveText())
        .optString("url", "")
        .sanitizeVideoUrl()

    if (url.isBlank()) {
        return call.respondJson(
            """{"error": "URL is required"}""",
            HttpStatusCode.BadRequest
        )
    }

    try {
        call.respondJson(resolutionsJson(getAvailableVideoResolutions(url)))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        respondWithResolutionError(call, username, url, error)
    }
}

/**
 * Serializes available resolutions from largest to smallest for the Web UI.
 *
 * @param resolutions unique resolutions to serialize
 * @return JSON response containing resolutions in descending order
 */
private fun resolutionsJson(resolutions: Set<Resolution>): String {
    val values = JSONArray()
    val sorted = resolutions.sortedWith(
        compareByDescending<Resolution> { it.height }
            .thenByDescending { it.width }
    )

    sorted.forEach { resolution ->
        values.put(
            JSONObject()
                .put("width", resolution.width)
                .put("height", resolution.height)
        )
    }

    return JSONObject().put("resolutions", values).toString()
}

/**
 * Reports a failed yt-dlp resolution query without exposing process output to the client.
 *
 * @param call request call receiving the failure response
 * @param username account that requested resolutions
 * @param url media URL whose resolution query failed
 * @param error query failure to report and record
 */
private suspend fun respondWithResolutionError(
    call: RoutingCall,
    username: String,
    url: String,
    error: Exception
) {
    Logger.e("Failed to get resolutions for $url: ${error.message}", error)
    call.logPersistentAction(
        LogLevel.WARNING,
        username,
        "Failed to get video resolutions for $url: ${error.message}"
    )

    val status = if (error is IllegalArgumentException) {
        HttpStatusCode.BadRequest
    } else {
        HttpStatusCode.BadGateway
    }

    call.respondJson("""{"error": "Could not load resolutions"}""", status)
}

/**
 * Authorizes and validates a request before resolving a video's title.
 *
 * @param call title request call
 * @throws org.json.JSONException when the request body is not valid JSON
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
 *
 * @param call request call receiving the validation response
 * @param username account that submitted the request
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
 *
 * @param call title request receiving the result
 * @param username account that requested the title
 * @param url media URL whose title should be resolved
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
 *
 * @param call decode request call
 * @throws org.json.JSONException when the request body is not valid JSON
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
 *
 * @param call request call receiving the validation response
 * @param username account that submitted the request
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
 *
 * @param call decode request receiving the result
 * @param username account that submitted the encoded value
 * @param encodedUrl Base64 text to decode
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
 *
 * @param call request call used for persistent logging
 * @param username account that submitted the URL
 * @param decodedUrl decoded URL to record
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
 *
 * @param url media URL whose title should be resolved
 * @return resolved title, or `null` when lookup fails
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
 * Reports whether an HTTP URL belongs to a recognized YouTube domain family.
 *
 * @param url absolute URL to inspect
 * @return `true` when the URL uses HTTP or HTTPS and a recognized YouTube host
 */
internal fun isYoutubeUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return false
    }

    if (!uri.scheme.equals("http", ignoreCase = true) &&
        !uri.scheme.equals("https", ignoreCase = true)
    ) {
        return false
    }

    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false

    return YOUTUBE_DOMAINS.any { domain ->
        host == domain || host.endsWith(".$domain")
    }
}

/**
 * Fetches a YouTube title through the public oEmbed endpoint.
 *
 * @param url recognized YouTube URL to query
 * @return title returned by oEmbed, or `null` when the response has no title
 * @throws java.io.IOException when the oEmbed request fails
 * @throws org.json.JSONException when the response is not valid JSON
 */
private fun fetchYoutubeTitle(url: String): String? {
    val normalizedUrl = normalizeYoutubeUrl(url)
    val encodedUrl = URLEncoder.encode(normalizedUrl, Charsets.UTF_8)
    val jsonUrl = "https://www.youtube.com/oembed?url=$encodedUrl&format=json"
    val response = JSONObject(downloadFile(jsonUrl, OkHttpClient()))

    return if (response.has("title")) response.getString("title") else null
}

/**
 * Converts a recognized YouTube URL to the canonical www.youtube.com host.
 *
 * @param url YouTube URL to normalize
 * @return canonical URL, or the original value when it cannot be parsed
 */
internal fun normalizeYoutubeUrl(url: String): String {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return url
    }

    val host = uri.host?.lowercase()?.trimEnd('.') ?: return url

    return if (host == "youtu.be" || host.endsWith(".youtu.be")) {
        normalizeShortYoutubeUrl(uri)
    } else {
        replaceYoutubeHost(uri)
    }
}

/**
 * Converts a shortened YouTube path into the equivalent canonical watch URL.
 *
 * @param uri parsed shortened YouTube URI
 * @return canonical watch URL, or a host-replaced URL for a non-shortened path
 */
private fun normalizeShortYoutubeUrl(uri: URI): String {
    val videoId = uri.rawPath.orEmpty().trim('/').substringBefore('/')

    if (!YOUTUBE_VIDEO_ID_PATTERN.matches(videoId)) {
        return replaceYoutubeHost(uri)
    }

    val query = listOfNotNull(
        "v=$videoId",
        uri.rawQuery?.takeIf { it.isNotEmpty() }
    ).joinToString("&")

    return "https://www.youtube.com/watch?$query${uri.rawFragmentSuffix()}"
}

/**
 * Rebuilds a YouTube URL with its canonical host while preserving its resource.
 *
 * @param uri parsed YouTube URI to rebuild
 * @return HTTPS URL using the canonical YouTube host
 */
private fun replaceYoutubeHost(uri: URI): String {
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"

    return "https://www.youtube.com$path${uri.rawQuerySuffix()}${uri.rawFragmentSuffix()}"
}

/**
 * Returns this URI's encoded query with its separator when one is present.
 *
 * @receiver URI whose raw query should be formatted
 * @return query prefixed with `?`, or an empty string when absent
 */
private fun URI.rawQuerySuffix(): String {
    return rawQuery?.let { "?$it" }.orEmpty()
}

/**
 * Returns this URI's encoded fragment with its separator when one is present.
 *
 * @receiver URI whose raw fragment should be formatted
 * @return fragment prefixed with `#`, or an empty string when absent
 */
private fun URI.rawFragmentSuffix(): String {
    return rawFragment?.let { "#$it" }.orEmpty()
}

/**
 * Uses yt-dlp to read a title for sites that do not use the YouTube oEmbed endpoint.
 *
 * @param url media URL passed to yt-dlp
 * @return trimmed title produced by yt-dlp
 * @throws java.io.IOException when yt-dlp cannot be started or its output cannot be read
 * @throws kotlinx.coroutines.TimeoutCancellationException when the process timeout expires
 * @throws CancellationException when the calling coroutine is cancelled
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
 * Queries yt-dlp and returns the distinct video resolutions available for a URL.
 *
 * @param videoUrl media URL to inspect
 * @return unique positive video resolutions reported by yt-dlp
 * @throws IllegalArgumentException when [videoUrl] is invalid or unsupported
 * @throws IllegalStateException when yt-dlp exits unsuccessfully
 * @throws java.io.IOException when yt-dlp cannot be executed or read
 * @throws CancellationException when the calling coroutine is cancelled
 */
suspend fun getAvailableVideoResolutions(videoUrl: String): Set<Resolution> {
    val validationError = mediaUrlError(videoUrl)

    require(validationError == null) {
        validationError ?: "Invalid media URL"
    }

    val command = buildMediaFormatCommand(videoUrl)
    val output = runMediaFormatCommand(command)

    return parseVideoResolutions(output)
}

/**
 * Builds the metadata-only yt-dlp command used to inspect available streams.
 *
 * @param videoUrl validated media URL to inspect
 * @return complete yt-dlp command and arguments
 */
private fun buildMediaFormatCommand(videoUrl: String): List<String> {
    return listOf(
        "yt-dlp",
        "--ignore-config",
        "--js-runtimes", "${JS_RUNTIME_TYPE}:${JS_RUNTIME_PATH}",
        "--no-cache-dir",
        "--no-playlist",
        "--print", MEDIA_FORMAT_TEMPLATE,
        videoUrl
    )
}

/**
 * Executes a format query while collecting its standard and error output safely.
 *
 * @param command yt-dlp command and arguments to execute
 * @return standard output produced by a successful query
 * @throws java.io.IOException when yt-dlp cannot be executed or read
 * @throws IllegalStateException when yt-dlp exits unsuccessfully
 * @throws kotlinx.coroutines.TimeoutCancellationException when the process timeout expires
 * @throws CancellationException when the calling coroutine is cancelled
 */
private suspend fun runMediaFormatCommand(command: List<String>): String = coroutineScope {
    Logger.i("Executing yt-dlp format query for ${command.last()}")

    val process = ProcessBuilder(command).start()
    val output = async(Dispatchers.IO) {
        process.inputStream.bufferedReader().readText()
    }
    val errors = async(Dispatchers.IO) {
        process.errorStream.bufferedReader().readText()
    }
    val exitCode = process.await(PROCESS_TIMEOUT * 1000)
    val standardOutput = output.await()
    val errorOutput = errors.await().trim()

    check(exitCode == 0) {
        "yt-dlp format query failed with exit code $exitCode: $errorOutput"
    }

    standardOutput
}

/**
 * Parses yt-dlp format JSON and keeps unique dimensions from video streams.
 *
 * @param output serialized format array produced by yt-dlp
 * @return unique positive dimensions belonging to video streams
 * @throws kotlinx.serialization.SerializationException when [output] is invalid
 */
internal fun parseVideoResolutions(output: String): Set<Resolution> {
    val formats = Json.decodeFromString<List<MediaFormat>>(output)

    return formats.asSequence()
        .filter(::isVideoFormat)
        .mapNotNull(::mediaFormatResolution)
        .toSet()
}

/**
 * Reports whether a media format contains a real video stream.
 *
 * @param format media format to inspect
 * @return `true` when the format declares a usable video codec
 */
private fun isVideoFormat(format: MediaFormat): Boolean {
    return !format.vcodec.isNullOrBlank() &&
        !format.vcodec.equals("none", ignoreCase = true)
}

/**
 * Converts valid positive stream dimensions into a resolution.
 *
 * @param format media format whose dimensions should be read
 * @return positive resolution, or `null` when either dimension is unavailable or invalid
 */
private fun mediaFormatResolution(format: MediaFormat): Resolution? {
    val width = format.width?.takeIf { it > 0 } ?: return null
    val height = format.height?.takeIf { it > 0 } ?: return null

    return Resolution(width, height)
}

/**
 * Validates raw download inputs and converts them into options for yt-dlp.
 *
 * @param rawUrl media URL supplied by the user
 * @param outputDir output-directory path
 * @param audioOnly whether to download audio without video
 * @param forceMp3Conversion whether downloaded audio must be converted to MP3
 * @param resolution exact video resolution, or `null` for the best available
 * @param customFilename custom output filename, or `null` to use metadata
 * @param progressCallback callback notified of parsed progress
 * @param processCallback callback notified when the child process changes
 * @return process exit code paired with collected output or validation details
 */
private fun downloadMedia(
    rawUrl: String,
    outputDir: String,
    audioOnly: Boolean = false,
    forceMp3Conversion: Boolean = false,
    resolution: Resolution? = null,
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
        outputDir = directory,
        audioOnly = audioOnly,
        forceMp3Conversion = forceMp3Conversion,
        resolution = resolution,
        customFilename = customFilename,
        progressCallback = progressCallback,
        processCallback = processCallback
    )

    return downloadMedia(Url(rawUrl), options)
}

/**
 * Returns the first URL or output-directory error found for a download request.
 *
 * @param rawUrl media URL to validate
 * @param directory output directory to validate or create
 * @return validation error, or `null` when both values are usable
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
 *
 * @param rawUrl media URL to validate
 * @return validation error, or `null` when the URL is supported
 */
private fun mediaUrlError(rawUrl: String): String? {
    if (!rawUrl.isValidUrl()) return "Error! Invalid url: $rawUrl"
    if (rawUrl.contains("playlist")) return "Error! Cannot download playlists!"

    return null
}

/**
 * Ensures a directory exists and is readable and writable.
 *
 * @param directory directory to inspect or create
 * @return `true` when the directory exists and is readable and writable
 * @throws SecurityException when filesystem access is denied
 */
private fun isUsableDirectory(directory: File): Boolean {
    val exists = directory.isDirectory || directory.mkdirs()

    return exists && directory.canWrite() && directory.canRead()
}

/**
 * Runs the suspendable yt-dlp workflow from the synchronous download task.
 *
 * @param url validated media URL
 * @param options yt-dlp output and format options
 * @return child-process exit code paired with collected output
 * @throws java.io.IOException when yt-dlp cannot be executed or read
 * @throws CancellationException when the workflow is cancelled
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
 *
 * @param url media URL passed to yt-dlp
 * @param options yt-dlp output and format options
 * @param fullLog destination collecting output across attempts
 * @param slowAudioConversion whether this attempt uses MP3 conversion fallback
 * @return final process exit code paired with collected output
 * @throws java.io.IOException when yt-dlp cannot be executed or read
 * @throws kotlinx.coroutines.TimeoutCancellationException when the process timeout expires
 * @throws CancellationException when the calling coroutine is cancelled
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
 *
 * @param url media URL appended to the command
 * @param options yt-dlp output and format options
 * @param slowAudioConversion whether to use the MP3 conversion fallback
 * @return complete yt-dlp command and arguments
 */
private fun buildYtDlpCommand(
    url: Url,
    options: MediaDownloadOptions,
    slowAudioConversion: Boolean
): List<String> {
    val command = baseYtDlpCommand(options.outputDir)

    addCustomFilename(command, options.customFilename)
    addVideoArguments(command, options)
    addAudioArguments(command, options, slowAudioConversion)
    command.add(url.toString())

    return command
}

/**
 * Creates the shared yt-dlp arguments used by every download.
 *
 * @param outputDir directory where yt-dlp should write output
 * @return mutable command containing shared yt-dlp arguments
 */
private fun baseYtDlpCommand(outputDir: File): MutableList<String> {
    return mutableListOf(
        "yt-dlp", "-v",
        "--js-runtimes", "${JS_RUNTIME_TYPE}:${JS_RUNTIME_PATH}",
        "--downloader-args", "ffmpeg:-timeout ${PROCESS_TIMEOUT * 1000000}",
        "--match-filters", "!is_live",
        "--no-cache-dir", "--no-playlist",
        "--paths", outputDir.absolutePath
    )
}

/**
 * Adds a custom output template when the user supplied a file name.
 *
 * @param command mutable yt-dlp command to update
 * @param customFilename custom filename, or `null` to retain yt-dlp naming
 */
private fun addCustomFilename(command: MutableList<String>, customFilename: String?) {
    if (customFilename != null) {
        command.add("-o")
        command.add("$customFilename.%(ext)s")
    }
}

/**
 * Restricts video downloads to the dimensions selected by the user.
 *
 * @param command mutable yt-dlp command to update
 * @param options media options containing the optional resolution
 */
private fun addVideoArguments(
    command: MutableList<String>,
    options: MediaDownloadOptions
) {
    val resolution = options.resolution ?: return

    if (!options.audioOnly) {
        command.addAll(listOf("-f", videoFormatSelector(resolution)))
    }
}

/**
 * Builds an exact-resolution selector with separate and combined stream fallbacks.
 *
 * @param resolution exact dimensions required from yt-dlp
 * @return yt-dlp format-selector expression
 */
internal fun videoFormatSelector(resolution: Resolution): String {
    val dimensions = "[width=${resolution.width}][height=${resolution.height}]"

    return "bestvideo$dimensions+bestaudio/best$dimensions/bestvideo$dimensions"
}

/**
 * Selects fast M4A extraction or explicit MP3 conversion for audio downloads.
 *
 * @param command mutable yt-dlp command to update
 * @param options media options describing the requested audio behavior
 * @param slowAudioConversion whether this attempt uses MP3 fallback conversion
 */
private fun addAudioArguments(
    command: MutableList<String>,
    options: MediaDownloadOptions,
    slowAudioConversion: Boolean
) {
    if (!options.audioOnly) return

    if (options.forceMp3Conversion || slowAudioConversion) {
        command.addAll(listOf("--extract-audio", "--audio-format", AUDIO_CONVERSION_MP3))
    } else {
        command.addAll(listOf("-f", "bestaudio[ext=m4a]"))
    }
}

/**
 * Decides whether a failed fast audio download should be retried as MP3.
 *
 * @param options media options describing the requested audio behavior
 * @param exitCode exit code from the fast audio attempt
 * @param slowAudioConversion whether the failed attempt already used MP3 conversion
 * @return `true` when one MP3 fallback attempt should be made
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
