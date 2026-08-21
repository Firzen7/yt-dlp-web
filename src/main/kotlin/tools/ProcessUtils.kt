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
