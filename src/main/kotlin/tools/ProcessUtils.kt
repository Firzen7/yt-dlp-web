package net.firzen.web.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.firzen.web.logging.LogLevel
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs a process in the given directory while collecting output and reporting download progress.
 *
 * @param command executable and arguments to run
 * @param outputDir working directory for the child process
 * @param fullLog destination that receives standard output and error text
 * @param progressCallback callback notified of parsed download progress
 * @param processCallback callback notified when the process starts and when it is cleared
 * @return child-process exit code
 * @throws java.io.IOException when the process cannot be started or its streams cannot be closed
 * @throws TimeoutCancellationException when the configured process timeout expires
 * @throws CancellationException when the calling coroutine is cancelled
 */
suspend fun runProcess(
    command: List<String>,
    outputDir: File,
    fullLog: StringBuilder,
    progressCallback: (Double?) -> Unit,
    processCallback: (Process?) -> Unit = {}
): Int {
    val process = ProcessBuilder(command)
        .directory(outputDir)
        .redirectErrorStream(false)
        .start()
    processCallback(process)

    val outJob = process.inputStream.bufferedReader().consumeLines(LogLevel.INFO.toString(), fullLog, progressCallback)
    val errJob = process.errorStream.bufferedReader().consumeLines(LogLevel.ERROR.toString(), fullLog, progressCallback)

    try {
        return withTimeout(PROCESS_TIMEOUT * 1000) {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    process.waitFor()
                }
            }
        }
    } finally {
        destroyProcessTree(process)

        outJob.cancelAndJoin()
        errJob.cancelAndJoin()

        process.inputStream.close()
        process.errorStream.close()
        process.outputStream.close()
        processCallback(null)
    }
}

/**
 * Waits for a process to finish and terminates its process tree on timeout or cancellation.
 *
 * @receiver process to await
 * @param timeoutMs maximum wait duration in milliseconds
 * @return child-process exit code
 * @throws TimeoutCancellationException when [timeoutMs] expires
 * @throws CancellationException when the calling coroutine is cancelled
 */
suspend fun Process.await(timeoutMs: Long): Int {
    return try {
        withTimeout(timeoutMs) {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    waitFor()
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        destroyProcessTree(this)
        throw e
    } catch (e: CancellationException) {
        destroyProcessTree(this)
        throw e
    }
}

/**
 * Requests graceful termination of a process tree before forcibly stopping any survivors.
 *
 * @param process root process to terminate
 * @throws InterruptedException when the termination grace-period sleep is interrupted
 */
fun destroyProcessTree(process: Process) {
    val handle = process.toHandle()

    handle.descendants().forEach {
        it.destroy()
    }

    handle.destroy()

    Thread.sleep(1000)

    handle.descendants().forEach {
        if (it.isAlive) {
            it.destroyForcibly()
        }
    }

    if (handle.isAlive) {
        handle.destroyForcibly()
    }
}
