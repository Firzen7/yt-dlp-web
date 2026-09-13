package net.firzen.web.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.firzen.web.logging.LogLevel
import net.firzen.web.logging.Logger
import net.firzen.web.logging.PersistentLogger
import net.firzen.web.tools.AUDIO_MODE
import net.firzen.web.tools.DOWNLOAD_DIRECTORY
import net.firzen.web.tools.VIDEO_MODE
import net.firzen.web.tools.destroyProcessTree
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns download task state, child processes, and background lifecycle transitions.
 */
internal class DownloadTaskManager {
    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val taskJobs = ConcurrentHashMap<String, Job>()
    private val taskProcesses = ConcurrentHashMap<String, Process>()

    /**
     * Creates and launches a tracked background task for a download request.
     *
     * @param username authenticated account starting the download
     * @param clientAddress network address of the requesting client
     * @param request normalized download request
     * @return unique identifier assigned to the new task
     */
    fun start(
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
     * Returns the current state of a download task.
     *
     * @param taskId task identifier to find
     * @return current task state, or `null` when no task exists
     */
    fun find(taskId: String): DownloadTask? {
        return tasks[taskId]
    }

    /**
     * Cancels an active task and returns its resulting state.
     *
     * @param taskId task identifier to cancel
     * @return resulting task state, or `null` when no task exists
     * @throws InterruptedException when process termination is interrupted
     */
    fun cancel(taskId: String): DownloadTask? {
        val task = tasks[taskId] ?: return null

        if (task.status == "processing") {
            stopDownloadTask(taskId, task)
        }

        return tasks[taskId]
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
}
