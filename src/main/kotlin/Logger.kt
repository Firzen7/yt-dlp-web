package net.firzen.web

/**
 * A custom Logger class with an interface similar to Timber.
 * Currently uses println for output.
 */
object Logger {
    fun i(message: String) {
        println("INFO: $message")
    }

    fun d(message: String) {
        println("DEBUG: $message")
    }

    fun e(message: String, throwable: Throwable? = null) {
        println("ERROR: $message")
        throwable?.printStackTrace()
    }

    fun w(message: String) {
        println("WARN: $message")
    }
}
