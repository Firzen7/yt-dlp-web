package net.firzen.web.network

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import net.firzen.web.Resolution
import net.firzen.web.logging.LogLevel
import net.firzen.web.logging.Logger
import net.firzen.web.tools.respondJson
import net.firzen.web.tools.sanitizeVideoUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * Registers title, resolution, and shared-link decoding endpoints.
 *
 * @receiver route receiving metadata endpoints
 */
internal fun Route.registerMediaRoutes() {
    post("/api/title") { handleVideoTitleRequest(call) }
    post("/api/resolutions") { handleResolutionRequest(call) }
    post("/api/decode") { decodeBase64Url(call) }
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
