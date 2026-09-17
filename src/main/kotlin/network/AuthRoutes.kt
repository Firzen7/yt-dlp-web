package net.firzen.web.network

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import net.firzen.web.UserManager
import net.firzen.web.logging.LogLevel
import net.firzen.web.tools.MINIMUM_PASSWORD_ENTROPY
import net.firzen.web.tools.UNKNOWN_USER
import net.firzen.web.tools.respondJson
import org.json.JSONObject

/**
 * Groups the fields submitted when an authenticated user changes their password.
 *
 * @param currentPassword current account password
 * @param newPassword proposed replacement password
 * @param confirmation repeated replacement password
 */
private data class PasswordChangeRequest(
    val currentPassword: String,
    val newPassword: String,
    val confirmation: String
)

/**
 * Describes a password-change validation failure returned to the Web UI.
 *
 * @param message localized fallback message
 * @param code stable error code used by the Web UI
 */
private data class PasswordValidationError(
    val message: String,
    val code: String
)

/**
 * Registers authentication and account-management endpoints.
 *
 * @receiver route receiving authentication endpoints
 * @param userManager credential manager used by the handlers
 */
internal fun Route.registerAuthRoutes(userManager: UserManager) {
    post("/api/login") { performLogin(userManager, call) }
    post("/api/logout") { performLogout(call) }
    get("/api/user") { provideUserInfo(call) }
    post("/api/change-password") { handleChangePassword(userManager, call) }
    post("/api/password-entropy") { handlePasswordEntropy(userManager, call) }
}

/**
 * Validates submitted credentials and completes or rejects the login attempt.
 *
 * @param userManager credential manager used for validation
 * @param call login request call
 * @throws org.json.JSONException when the request body is not valid JSON
 */
private suspend fun performLogin(userManager: UserManager, call: RoutingCall) {
    val body = JSONObject(call.receiveText())
    val username = body.optString("username", "")
    val password = body.optString("password", "")

    if (userManager.validateUser(username, password)) {
        completeLogin(call, username)
    } else {
        rejectLogin(call, username)
    }
}

/**
 * Creates a user session, confirms the login, and records the successful action.
 *
 * @param call authenticated login request call
 * @param username account that successfully authenticated
 */
private suspend fun completeLogin(call: RoutingCall, username: String) {
    val operatingSystem = detectOperatingSystem(
        call.request.headers["Sec-CH-UA-Platform"],
        call.request.headers["User-Agent"]
    )
    call.sessions.set(UserSession(username, call.clientIpAddress(), operatingSystem))
    call.respondJson("""{"ok": true}""")

    call.logPersistentAction(LogLevel.INFO, username, "Successful login")
}

/**
 * Returns an authentication error and records the failed login attempt.
 *
 * @param call rejected login request call
 * @param username submitted username recorded in the audit log
 */
private suspend fun rejectLogin(call: RoutingCall, username: String) {
    call.respondJson(
        """{"error": "Nesprávné jméno nebo heslo."}""",
        HttpStatusCode.Unauthorized
    )

    call.logPersistentAction(
        LogLevel.WARNING,
        UNKNOWN_USER,
        "Login failed (user: $username)"
    )
}

/**
 * Clears the active session, confirms logout, and records the action.
 *
 * @param call logout request call
 */
private suspend fun performLogout(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()

    call.sessions.clear<UserSession>()
    call.respondJson("""{"ok": true}""")

    call.logPersistentAction(LogLevel.INFO, session?.username, "Logout")
}

/**
 * Authorizes, parses, and validates a request before changing the user's password.
 *
 * @param userManager credential manager used to update the password
 * @param call password-change request call
 * @throws org.json.JSONException when the request body is not valid JSON
 */
private suspend fun handleChangePassword(userManager: UserManager, call: RoutingCall) {
    val session = call.sessions.get<UserSession>()
        ?: return call.respondJson(
            """{"error": "Unauthorized"}""",
            HttpStatusCode.Unauthorized
        )

    val request = readPasswordChangeRequest(call)
    val validationError = validatePasswordChange(userManager, request)

    if (validationError != null) {
        return call.respondJson(
            JSONObject()
                .put("error", validationError.message)
                .put("code", validationError.code)
                .toString(),
            HttpStatusCode.BadRequest
        )
    }

    changeUserPassword(userManager, call, session.username, request)
}

/**
 * Parses password-change fields from the request body.
 *
 * @param call request containing password-change JSON
 * @return parsed password-change fields
 * @throws org.json.JSONException when the request body is not valid JSON
 */
private suspend fun readPasswordChangeRequest(call: RoutingCall): PasswordChangeRequest {
    val body = JSONObject(call.receiveText())

    return PasswordChangeRequest(
        body.optString("currentPassword", ""),
        body.optString("newPassword", ""),
        body.optString("confirmNewPassword", "")
    )
}

/**
 * Returns a user-facing validation message when a proposed password is invalid.
 *
 * @param userManager manager providing password entropy calculation
 * @param request password-change values to validate
 * @return validation failure, or `null` when the request is valid
 */
private fun validatePasswordChange(
    userManager: UserManager,
    request: PasswordChangeRequest
): PasswordValidationError? {
    if (request.currentPassword.isEmpty()) {
        return PasswordValidationError("Aktuální heslo nesmí být prázdné.", "CURRENT_REQUIRED")
    }

    if (request.newPassword.isEmpty()) {
        return PasswordValidationError("Nové heslo nesmí být prázdné.", "NEW_REQUIRED")
    }

    if (request.newPassword != request.confirmation) {
        return PasswordValidationError("Nová hesla se neshodují.", "PASSWORD_MISMATCH")
    }

    if (userManager.computeEntropy(request.newPassword) < MINIMUM_PASSWORD_ENTROPY) {
        return PasswordValidationError("Nové heslo není dostatečně silné.", "PASSWORD_TOO_WEAK")
    }

    return null
}

/**
 * Applies a password change and maps expected or unexpected failures to responses.
 *
 * @param userManager credential manager used to persist the replacement password
 * @param call request call receiving the outcome
 * @param username account whose password should change
 * @param request validated password-change values
 */
private suspend fun changeUserPassword(
    userManager: UserManager,
    call: RoutingCall,
    username: String,
    request: PasswordChangeRequest
) {
    try {
        userManager.changePassword(username, request.currentPassword, request.newPassword)
        completePasswordChange(call, username)
    } catch (e: IllegalArgumentException) {
        rejectPasswordChange(call, username, e)
    } catch (e: Exception) {
        failPasswordChange(call, username, e)
    }
}

/**
 * Ends the current session, confirms the password change, and records the action.
 *
 * @param call request call receiving the success response
 * @param username account whose password changed
 */
private suspend fun completePasswordChange(call: RoutingCall, username: String) {
    call.sessions.clear<UserSession>()
    call.respondJson("""{"ok": true}""")

    call.logPersistentAction(
        LogLevel.INFO,
        username,
        "Password changed successfully. User logged out."
    )
}

/**
 * Reports a rejected password change caused by invalid user input.
 *
 * @param call request call receiving the rejection response
 * @param username account associated with the rejected change
 * @param error validation failure to report
 */
private suspend fun rejectPasswordChange(
    call: RoutingCall,
    username: String,
    error: IllegalArgumentException
) {
    val message = error.message ?: "Chyba při změně hesla."

    call.respondJson("""{"error": "$message"}""", HttpStatusCode.BadRequest)

    call.logPersistentAction(
        LogLevel.WARNING,
        username,
        "Failed to change password: ${error.message}"
    )
}

/**
 * Reports and records an unexpected failure while changing a password.
 *
 * @param call request call receiving the failure response
 * @param username account associated with the failed change
 * @param error unexpected failure to record
 */
private suspend fun failPasswordChange(
    call: RoutingCall,
    username: String,
    error: Exception
) {
    call.respondJson(
        """{"error": "Chyba při změně hesla."}""",
        HttpStatusCode.InternalServerError
    )

    call.logPersistentAction(
        LogLevel.ERROR,
        username,
        "Exception while changing password: ${error.message}"
    )
}

/**
 * Calculates password entropy and returns a generic error when calculation fails.
 *
 * @param userManager manager providing the entropy calculation
 * @param call request containing the password and receiving the response
 */
private suspend fun handlePasswordEntropy(userManager: UserManager, call: RoutingCall) {
    val username = call.sessions.get<UserSession>()?.username

    try {
        val password = JSONObject(call.receiveText()).optString("password", "")

        val response = JSONObject()
            .put("entropy", userManager.computeEntropy(password))
            .put("minimumEntropy", MINIMUM_PASSWORD_ENTROPY)

        call.respondJson(response.toString())
    } catch (_: Exception) {
        call.logPersistentAction(
            LogLevel.WARNING,
            username,
            "Error while computing password entropy!"
        )

        call.respondJson(
            """{"error": "Chyba při výpočtu entropie."}""",
            HttpStatusCode.InternalServerError
        )
    }
}

/**
 * Returns the logged-in username or an unauthorized response.
 *
 * @param call request call receiving account information or an error
 */
private suspend fun provideUserInfo(call: RoutingCall) {
    val session = call.sessions.get<UserSession>()

    if (session != null) {
        call.respondJson("""{"username": "${session.username}"}""")
    } else {
        call.respondJson("""{"error": "Unauthorized"}""", HttpStatusCode.Unauthorized)
    }
}
