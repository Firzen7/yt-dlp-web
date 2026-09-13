package net.firzen.web.network

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import net.firzen.web.Resolution
import net.firzen.web.logging.LogLevel
import net.firzen.web.logging.Logger
import net.firzen.web.tools.AUDIO_CONVERSION_MP3
import net.firzen.web.tools.UNKNOWN_USER
import net.firzen.web.tools.VIDEO_MODE
import net.firzen.web.tools.respondJson
import net.firzen.web.tools.sanitizeVideoUrl
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Registers download lifecycle and file-transfer endpoints.
 *
 * @receiver route receiving download endpoints
 * @param taskManager manager that owns download task state
 */
internal fun Route.registerDownloadRoutes(taskManager: DownloadTaskManager) {
    post("/api/download") { performDownload(call, taskManager) }
    post("/api/cancel/{taskId}") { cancelDownload(call, taskManager) }
    get("/api/status/{taskId}") { reportTaskStatus(call, taskManager) }
    get("/api/file/{taskId}") { provideDownloadedFile(call, taskManager) }
}

/**
 * Validates a download request, starts its background task, and returns its identifier.
 *
 * @param call download request call
 * @throws org.json.JSONException when the request body is not valid JSON
 */
private suspend fun performDownload(call: RoutingCall, taskManager: DownloadTaskManager) {
    val username = requireDownloadUser(call) ?: return
    val request = readDownloadRequest(call)

    if (request.url.isBlank()) {
        return call.respondJson(
            """{"error": "URL is required"}""",
            HttpStatusCode.BadRequest
        )
    }

    val taskId = taskManager.start(
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
 * Authorizes a cancellation request and stops an active download task.
 *
 * @param call cancellation request call
 */
private suspend fun cancelDownload(call: RoutingCall, taskManager: DownloadTaskManager) {
    val username = call.sessions.get<UserSession>()?.username
        ?: return call.respondJson(
            """{"error": "Unauthorized"}""",
            HttpStatusCode.Unauthorized
        )

    val taskId = call.parameters["taskId"] ?: ""
    val task = taskManager.find(taskId)
        ?: return call.respondJson(
            """{"error": "Task not found"}""",
            HttpStatusCode.NotFound
        )

    if (task.status != "processing") {
        return call.respondJson("""{"ok": true, "status": "${task.status}"}""")
    }

    taskManager.cancel(taskId)
    logDownloadCancelled(call, taskId, username)

    call.respondJson("""{"ok": true, "status": "cancelled"}""")
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
private suspend fun reportTaskStatus(call: RoutingCall, taskManager: DownloadTaskManager) {
    call.sessions.get<UserSession>()
        ?: return call.respondJson(
            """{"error": "Unauthorized"}""",
            HttpStatusCode.Unauthorized
        )

    val taskId = call.parameters["taskId"] ?: ""
    val task = taskManager.find(taskId)
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
private suspend fun provideDownloadedFile(call: RoutingCall, taskManager: DownloadTaskManager) {
    val username = call.sessions.get<UserSession>()?.username
        ?: return call.respondJson(
            """{"error": "Unauthorized"}""",
            HttpStatusCode.Unauthorized
        )

    val taskId = call.parameters["taskId"] ?: ""
    val file = resolveDownloadedFile(call, taskId, taskManager) ?: return

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
private suspend fun resolveDownloadedFile(
    call: RoutingCall,
    taskId: String,
    taskManager: DownloadTaskManager
): File? {
    val task = taskManager.find(taskId)

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
