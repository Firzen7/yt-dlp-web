package net.firzen.web.network

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import net.firzen.web.logging.LogLevel
import net.firzen.web.logging.PersistentLogger

/**
 * Returns the client address reconstructed from trusted proxy headers.
 *
 * @receiver request call whose origin should be resolved
 * @return resolved client network address
 */
internal fun ApplicationCall.clientIpAddress(): String {
    return request.origin.remoteAddress
}

/**
 * Records a web action with the resolved network address of its client.
 *
 * @receiver request call associated with the action
 * @param logLevel severity assigned to the persistent entry
 * @param username authenticated username, or `null` when unavailable
 * @param action action description to record
 */
internal fun ApplicationCall.logPersistentAction(
    logLevel: LogLevel,
    username: String?,
    action: String
) {
    PersistentLogger.logAction(
        logLevel,
        username,
        clientIpAddress(),
        action
    )
}
