package net.firzen.web.network

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import net.firzen.web.tools.respondJson

/**
 * Registers application pages, redirects, and version reporting.
 *
 * @receiver route receiving page endpoints
 */
internal fun Route.registerPageRoutes() {
    get("/api/version") { provideVersion(call) }
    get("/") { provideWebpage(call) }
    get("/login") { provideLoginPage(call) }
    get("/index.html") { redirectToCanonicalPage(call, "/") }
    get("/login.html") { redirectToCanonicalPage(call, "/login") }
}

/**
 * Returns the version currently embedded in the running application.
 *
 * @param call version request call
 */
private suspend fun provideVersion(call: RoutingCall) {
    call.respondJson("""{"version": "${appVersion()}"}""")
}

/**
 * Serves the application at the root URL or redirects unauthenticated visitors to login.
 *
 * @param call root-page request call
 */
private suspend fun provideWebpage(call: RoutingCall) {
    if (call.sessions.get<UserSession>() == null) {
        call.respondRedirect(call.pathWithQuery("/login"))
    } else {
        respondStaticHtml(call, "static/index.html")
    }
}

/**
 * Serves the login page or returns authenticated visitors to the application root.
 *
 * @param call login-page request call
 */
private suspend fun provideLoginPage(call: RoutingCall) {
    if (call.sessions.get<UserSession>() != null) {
        call.respondRedirect(call.pathWithQuery("/"))
    } else {
        respondStaticHtml(call, "static/login.html")
    }
}

/**
 * Permanently redirects an old HTML page URL to its canonical route.
 *
 * @param call legacy-page request call
 * @param path canonical redirect destination
 */
private suspend fun redirectToCanonicalPage(call: RoutingCall, path: String) {
    call.respondRedirect(call.pathWithQuery(path), permanent = true)
}

/**
 * Appends the current request query string to a redirect destination.
 *
 * @receiver request call containing the query string
 * @param path redirect destination path
 * @return destination with the current query string appended when present
 */
private fun ApplicationCall.pathWithQuery(path: String): String {
    val queryString = request.queryString()

    return if (queryString.isEmpty()) path else "$path?$queryString"
}

/**
 * Loads an HTML resource from the application and returns a not-found response when absent.
 *
 * @param call page request receiving the resource or not-found response
 * @param resourcePath classpath path of the HTML resource
 * @throws java.io.IOException when an existing resource cannot be read
 */
private suspend fun respondStaticHtml(call: RoutingCall, resourcePath: String) {
    val text = UserSession::class.java.classLoader.getResource(resourcePath)?.readText()

    if (text == null) {
        call.respondText("Not found", status = HttpStatusCode.NotFound)
    } else {
        call.respondText(text, ContentType.Text.Html)
    }
}
