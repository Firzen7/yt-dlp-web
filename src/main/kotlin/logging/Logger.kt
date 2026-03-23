package net.firzen.web.logging

/**
 * A custom Logger class with an interface similar to Timber.
 * Currently uses println for output.
 */
object Logger {
    fun d(message: String) {
        println("${LogLevel.DEBUG}: $message")
    }

    fun i(message: String) {
        println("${LogLevel.INFO}: $message")
    }

    fun w(message: String) {
        println("${LogLevel.WARNING}: $message")
    }

    fun e(message: String, throwable: Throwable? = null) {
        println("${LogLevel.ERROR}: $message")
        throwable?.printStackTrace()
    }
}