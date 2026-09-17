package net.firzen.web.network

/**
 * Defines the supported client platforms and their names in session records.
 *
 * @param displayName platform name used in browser hints and session output
 */
internal enum class ClientOperatingSystem(val displayName: String) {
    WINDOWS("Windows"),
    MACOS("macOS"),
    ANDROID("Android"),
    IOS("iOS"),
    LINUX("Linux"),
    CHROME_OS("Chrome OS"),
    UNKNOWN("unknown")
}

/**
 * Identifies the operating system reported by a browser without storing raw headers.
 *
 * @param platform optional Sec-CH-UA-Platform request header
 * @param userAgent optional User-Agent request header
 * @return recognized operating system name, or `unknown` when unavailable
 */
internal fun detectOperatingSystem(platform: String?, userAgent: String?): String {
    val reportedPlatform = platform?.trim()?.trim('"')
    ClientOperatingSystem.entries.firstOrNull {
        it != ClientOperatingSystem.UNKNOWN &&
            it.displayName.equals(reportedPlatform, ignoreCase = true)
    }?.let {
        return it.displayName
    }

    val agent = userAgent.orEmpty().lowercase()

    return when {
        "android" in agent -> ClientOperatingSystem.ANDROID
        "iphone" in agent || "ipad" in agent || "ipod" in agent -> ClientOperatingSystem.IOS
        "windows" in agent -> ClientOperatingSystem.WINDOWS
        "cros" in agent -> ClientOperatingSystem.CHROME_OS
        "macintosh" in agent || "mac os x" in agent -> ClientOperatingSystem.MACOS
        "linux" in agent -> ClientOperatingSystem.LINUX
        else -> ClientOperatingSystem.UNKNOWN
    }.displayName
}
