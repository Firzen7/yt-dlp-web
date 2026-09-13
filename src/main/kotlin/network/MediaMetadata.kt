package net.firzen.web.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.firzen.web.MediaFormat
import net.firzen.web.Resolution
import net.firzen.web.logging.Logger
import net.firzen.web.tools.JS_RUNTIME_PATH
import net.firzen.web.tools.JS_RUNTIME_TYPE
import net.firzen.web.tools.PROCESS_TIMEOUT
import net.firzen.web.tools.await
import net.firzen.web.tools.downloadFile
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

private val YOUTUBE_DOMAINS = setOf("youtube.com", "youtu.be", "youtube-nocookie.com")
private val YOUTUBE_VIDEO_ID_PATTERN = Regex("^[A-Za-z0-9_-]{11}$")
private const val MEDIA_FORMAT_TEMPLATE =
    "%(formats.:.{format_id,width,height,fps,ext,vcodec,acodec})#j"

/**
 * Chooses the appropriate title provider and shields callers from lookup failures.
 *
 * @param url media URL whose title should be resolved
 * @return resolved title, or `null` when lookup fails
 */
internal suspend fun resolveVideoTitle(url: String): String? {
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
