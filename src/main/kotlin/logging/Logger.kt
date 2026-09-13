package net.firzen.web.logging

/**
 * A custom Logger class with an interface similar to Timber.
 * Currently, it uses println for output.
 */
object Logger {
    /**
     * Writes a diagnostic message at debug level.
     *
     * @param message diagnostic text to write
     */
    fun d(message: String) {
        println("${LogLevel.DEBUG}: $message")
    }

    /**
     * Writes a routine operational message at information level.
     *
     * @param message informational text to write
     */
    fun i(message: String) {
        println("${LogLevel.INFO}: $message")
    }

    /**
     * Writes a message describing a recoverable or suspicious condition.
     *
     * @param message warning text to write
     */
    fun w(message: String) {
        println("${LogLevel.WARNING}: $message")
    }

    /**
     * Writes an error message and prints the associated exception when one is available.
     *
     * @param message error text to write
     * @param throwable optional failure whose stack trace should be printed
     */
    fun e(message: String, throwable: Throwable? = null) {
        println("${LogLevel.ERROR}: $message")
        throwable?.printStackTrace()
    }
}
