package net.firzen.web.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies OS detection when browser identifiers overlap or are unavailable.
 */
class ClientOperatingSystemTest {
    /**
     * Verifies specific platforms take precedence over compatibility tokens.
     */
    @Test
    fun `detects browser platforms`() {
        assertEquals("Android", detectOperatingSystem(null, "Mozilla/5.0 (Linux; Android 14)"))
        assertEquals("iOS", detectOperatingSystem(null, "Mozilla/5.0 (iPhone; CPU iPhone OS like Mac OS X)"))
        assertEquals("Windows", detectOperatingSystem(null, "Mozilla/5.0 (Windows NT 10.0; Win64)"))
        assertEquals("macOS", detectOperatingSystem(null, "Mozilla/5.0 (Macintosh; Intel Mac OS X)"))
        assertEquals("Chrome OS", detectOperatingSystem(null, "Mozilla/5.0 (X11; CrOS x86_64)"))
        assertEquals("Linux", detectOperatingSystem(null, "Mozilla/5.0 (X11; Linux x86_64)"))
    }

    /**
     * Verifies recognized platform hints override user-agent compatibility tokens.
     */
    @Test
    fun `platform hints and missing headers are handled`() {
        assertEquals("iOS", detectOperatingSystem("\"iOS\"", "Macintosh"))
        assertEquals("Linux", detectOperatingSystem("\"unrecognized\"", "Linux"))
        assertEquals("unknown", detectOperatingSystem(null, null))
        assertEquals("unknown", detectOperatingSystem(null, "curl"))
        assertEquals("unknown", detectOperatingSystem("Windows\nforged", null))
    }
}
