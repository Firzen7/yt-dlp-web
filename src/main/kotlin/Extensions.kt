package net.firzen.web

fun String.isValidUrl() : Boolean = try {
    val uri = java.net.URI(this)
    uri.scheme != null && uri.host != null
} catch (_: Exception) {
    false
}
