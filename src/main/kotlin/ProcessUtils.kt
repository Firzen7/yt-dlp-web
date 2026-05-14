package net.firzen.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

suspend fun runProcess(command: List<String>, outputDir: File, fullLog: StringBuilder,
                       progressCallback: (Double?) -> Unit) : Int {

    val process = ProcessBuilder(command)
        .directory(outputDir)
        .redirectErrorStream(false)
        .start()

    val outJob = process.inputStream.bufferedReader().consumeLines(OUT_TAG, fullLog, progressCallback)
    val errJob = process.errorStream.bufferedReader().consumeLines(ERROR_TAG, fullLog, progressCallback)

    try {
        return withTimeout(PROCESS_TIMEOUT) {
            withContext(Dispatchers.IO) {
                process.waitFor()
            }
        }
    } finally {
        destroyProcessTree(process)

        outJob.cancelAndJoin()
        errJob.cancelAndJoin()

        process.inputStream.close()
        process.errorStream.close()
        process.outputStream.close()
    }
}

suspend fun Process.await(timeoutMs: Long): Int {
    return try {
        withTimeout(timeoutMs) {
            withContext(Dispatchers.IO) {
                waitFor()
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
