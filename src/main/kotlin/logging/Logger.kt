package net.firzen.web.logging

/**
 * A custom Logger class with an interface similar to Timber.
 * Currently uses println for output.
 */
object Logger {
    /**
     * Writes a diagnostic message at debug level.
     */
    fun d(message: String) {
        println("${LogLevel.DEBUG}: $message")
    }

    /**
     * Writes a routine operational message at information level.
     */
    fun i(message: String) {
        println("${LogLevel.INFO}: $message")
    }

    /**
     * Writes a message describing a recoverable or suspicious condition.
     */
    fun w(message: String) {
        println("${LogLevel.WARNING}: $message")
    }

    /**
     * Writes an error message and prints the associated exception when one is available.
     */
    fun e(message: String, throwable: Throwable? = null) {
        println("${LogLevel.ERROR}: $message")
        throwable?.printStackTrace()
    }
}
