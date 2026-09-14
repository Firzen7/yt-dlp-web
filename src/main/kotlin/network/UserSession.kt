package net.firzen.web.network

import kotlinx.serialization.Serializable

/**
 * Stores server-side identity and login-origin information for an authenticated session.
 *
 * @param username authenticated account name
 * @param clientAddress network address used to create the session
 */
@Serializable
data class UserSession(
    val username: String,
    val clientAddress: String
)
