package net.firzen.web.logging

import org.slf4j.LoggerFactory

/**
 * Sends application diagnostics through SLF4J using a compact Timber-style API.
 */
object Logger {
    private val logger = LoggerFactory.getLogger(Logger::class.java)

    /**
     * Writes a diagnostic message at debug level.
     *
     * @param message diagnostic text to write
     */
    fun d(message: String) {
        logger.debug(message)
    }

    /**
     * Writes a routine operational message at information level.
     *
     * @param message informational text to write
     */
    fun i(message: String) {
        logger.info(message)
    }

    /**
     * Writes a message describing a recoverable or suspicious condition.
     *
     * @param message warning text to write
     */
    fun w(message: String) {
        logger.warn(message)
    }

    /**
     * Writes an error message and prints the associated exception when one is available.
     *
     * @param message error text to write
     * @param throwable optional failure whose stack trace should be printed
     */
    fun e(message: String, throwable: Throwable? = null) {
        logger.error(message, throwable)
    }
}
