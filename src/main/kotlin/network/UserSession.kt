package net.firzen.web.network

import kotlinx.serialization.Serializable

/**
 * Stores the authenticated username in the browser session.
 *
 * @param username authenticated account name
 */
@Serializable
data class UserSession(val username: String)
