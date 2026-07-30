package net.firzen.web

/**
 * Placeholder used when an action cannot be associated with an authenticated user.
 */
const val UNKNOWN_USER = "unknown"

/**
 * Tag applied to standard process output in collected logs.
 */
const val OUT_TAG = "OUT"

/**
 * Tag applied to process error output in collected logs.
 */
const val ERROR_TAG = "ERR"

/**
 * Lifetime of an authenticated browser session in seconds.
 */
const val LOGIN_SESSION_LENGTH = 30 * 24 * 60 * 60L
