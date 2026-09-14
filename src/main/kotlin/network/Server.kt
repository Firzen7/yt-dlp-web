package net.firzen.web.network

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import net.firzen.web.BuildConfig
import net.firzen.web.UserManager
import net.firzen.web.logging.Logger
import net.firzen.web.tools.LOGIN_SESSION_LENGTH
import net.firzen.web.tools.SERVER_PORT
import net.firzen.web.tools.SESSION_DIRECTORY
import net.firzen.web.tools.USERS_FILE
import org.json.JSONObject
import java.io.File

/**
 * Returns the application version generated from the Gradle project version.
 *
 * @return application version string
 */
internal fun appVersion(): String = BuildConfig.VERSION

/**
 * Creates the user manager and starts the embedded HTTP server.
 */
fun startServer() {
    Logger.i("startServer()")

    val userManager = UserManager(File(USERS_FILE))

    embeddedServer(Netty, SERVER_PORT) {
        configureServer(userManager)
    }.start(wait = true)
}

/**
 * Installs server features, registers routes, and records that startup completed.
 *
 * @receiver Ktor application to configure
 * @param userManager credential manager shared by request handlers
 */
private fun Application.configureServer(userManager: UserManager) {
    install(XForwardedHeaders) {
        useLastProxy()
    }

    configureSessions()
    configureErrorHandling()
    configureRoutes(userManager)

    Logger.i("yt-dlp-web version ${appVersion()} started")
}

/**
 * Configures the browser cookie and file storage used for authenticated sessions.
 *
 * @receiver Ktor application receiving session support
 */
private fun Application.configureSessions() {
    val storage = UserFileSessionStorage(
        File(SESSION_DIRECTORY),
        LOGIN_SESSION_LENGTH
    )

    install(Sessions) {
        cookie<UserSession>("SESSION", storage) {
            serializer = UserSessionSerializer
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = LOGIN_SESSION_LENGTH
        }
    }
}

/**
 * Converts uncaught request errors into consistent JSON error responses.
 *
 * @receiver Ktor application receiving error handling
 */
private fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            respondWithServerError(call, cause)
        }
    }
}

/**
 * Sends details about an unexpected server error as a JSON response.
 *
 * @param call request call receiving the error response
 * @param cause uncaught failure being reported
 */
private suspend fun respondWithServerError(call: ApplicationCall, cause: Throwable) {
    val response = JSONObject()
        .put("status", HttpStatusCode.InternalServerError.value)
        .put("message", cause.message)
        .put("version", appVersion())

    call.respondText(
        response.toString(),
        ContentType.Application.Json,
        HttpStatusCode.InternalServerError
    )
}

/**
 * Registers every HTTP endpoint and the bundled static resources.
 *
 * @receiver Ktor application receiving routes
 * @param userManager credential manager used by authentication routes
 */
private fun Application.configureRoutes(userManager: UserManager) {
    val taskManager = DownloadTaskManager()

    routing {
        registerAuthRoutes(userManager)
        registerDownloadRoutes(taskManager)
        registerPageRoutes()
        registerMediaRoutes()
        staticResources("/", "static")
    }
}
