package net.firzen.web

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies media-format decoding and video-resolution filtering.
 */
class MediaFormatTest {
    /**
     * Verifies that yt-dlp field names decode into the Kotlin model.
     */
    @Test
    fun `media format decodes yt-dlp fields`() {
        val format = Json.decodeFromString<MediaFormat>(VIDEO_FORMAT_JSON)

        assertEquals("137", format.formatId)
        assertEquals(1920, format.width)
        assertEquals(1080, format.height)
        assertEquals(30.0, format.fps)
        assertEquals("avc1.640028", format.vcodec)
    }

    /**
     * Verifies that audio and incomplete entries are removed and dimensions are unique.
     */
    @Test
    fun `resolution parser returns unique video dimensions`() {
        val output = """[
            $VIDEO_FORMAT_JSON,
            {"format_id":"399","width":1920,"height":1080,"vcodec":"av01","acodec":"none"},
            {"format_id":"140","vcodec":"none","acodec":"mp4a.40.2"},
            {"format_id":"storyboard","width":160,"height":90,"vcodec":"none"},
            {"format_id":"broken","height":720,"vcodec":"avc1"}
        ]""".trimIndent()

        assertEquals(setOf(Resolution(1920, 1080)), parseVideoResolutions(output))
    }

    /**
     * Supplies representative yt-dlp JSON shared by the decoding tests.
     */
    private companion object {
        const val VIDEO_FORMAT_JSON = """
            {"format_id":"137","width":1920,"height":1080,"fps":30,"ext":"mp4","vcodec":"avc1.640028","acodec":"none"}
        """
    }
}
