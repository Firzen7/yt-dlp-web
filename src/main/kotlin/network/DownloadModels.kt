package net.firzen.web.network

import net.firzen.web.Resolution
import net.firzen.web.tools.AUDIO_CONVERSION_MP3
import java.io.File

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
 * Holds a normalized download request and exposes its derived media settings.
 *
 * @param url sanitized media URL
 * @param format requested video or audio mode
 * @param audioConversion requested audio-conversion strategy
 * @param resolution requested video resolution, or `null` for the best available
 * @param customFilename requested output filename without unsafe characters
 */
internal data class DownloadRequest(
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
internal data class DownloadContext(
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
internal data class TaskDownloadResult(
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
internal data class MediaDownloadOptions(
    val outputDir: File,
    val audioOnly: Boolean,
    val forceMp3Conversion: Boolean,
    val resolution: Resolution?,
    val customFilename: String?,
    val progressCallback: (Double?) -> Unit,
    val processCallback: (Process?) -> Unit
)
