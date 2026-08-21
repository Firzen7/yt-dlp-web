package net.firzen.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies detection of supported YouTube URL forms.
 */
class YoutubeUrlTest {
    /**
     * Verifies regular, shortened, mobile, music, and embedded YouTube URLs.
     */
    @Test
    fun `recognizes YouTube URL variants`() {
        val urls = listOf(
            "https://youtube.com/watch?v=video",
            "https://www.youtube.com/shorts/video",
            "http://m.youtube.com/watch?v=video",
            "https://music.youtube.com/watch?v=video",
            "https://youtu.be/video",
            "https://www.youtube-nocookie.com/embed/video",
            "HTTPS://YOUTUBE.COM:443/live/video",
            "https://youtube.com./watch?v=video"
        )

        urls.forEach { url -> assertTrue(isYoutubeUrl(url), url) }
    }

    /**
     * Verifies malformed, non-web, and deceptively named hosts are rejected.
     */
    @Test
    fun `rejects non-YouTube URLs`() {
        val urls = listOf(
            "https://example.com/watch?v=video",
            "https://youtube.com.example.org/watch?v=video",
            "https://notyoutube.com/watch?v=video",
            "https://youtube.com@evil.example/watch?v=video",
            "ftp://youtube.com/watch?v=video",
            "youtube.com/watch?v=video",
            "not a URL"
        )

        urls.forEach { url -> assertFalse(isYoutubeUrl(url), url) }
    }

    /**
     * Verifies that full YouTube URLs retain their resource on the canonical host.
     */
    @Test
    fun `normalizes YouTube domains`() {
        assertEquals(
            "https://www.youtube.com/watch?v=video&t=10#details",
            normalizeYoutubeUrl("http://music.youtube.com:80/watch?v=video&t=10#details")
        )
        assertEquals(
            "https://www.youtube.com/embed/video?start=10",
            normalizeYoutubeUrl("https://www.youtube-nocookie.com/embed/video?start=10")
        )
    }

    /**
     * Verifies that shortened links become canonical watch URLs.
     */
    @Test
    fun `normalizes shortened YouTube links`() {
        assertEquals(
            "https://www.youtube.com/watch?v=QRmu99WDxik&t=10#details",
            normalizeYoutubeUrl("https://youtu.be/QRmu99WDxik?t=10#details")
        )
    }

    /**
     * Verifies that a long-form watch path on a shortened host is not converted twice.
     */
    @Test
    fun `normalizes watch paths on shortened host`() {
        assertEquals(
            "https://www.youtube.com/watch?v=QRmu99WDxik",
            normalizeYoutubeUrl("https://youtu.be/watch?v=QRmu99WDxik")
        )
    }
}
