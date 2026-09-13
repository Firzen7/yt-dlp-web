package net.firzen.web.network

import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import net.firzen.web.Resolution
import net.firzen.web.logging.Logger
import net.firzen.web.tools.AUDIO_CONVERSION_MP3
import net.firzen.web.tools.JS_RUNTIME_PATH
import net.firzen.web.tools.JS_RUNTIME_TYPE
import net.firzen.web.tools.PROCESS_TIMEOUT
import net.firzen.web.tools.isValidUrl
import net.firzen.web.tools.runProcess
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

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
internal fun downloadMedia(
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
internal fun mediaUrlError(rawUrl: String): String? {
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
