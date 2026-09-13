package net.firzen.web

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Describes one media stream reported by yt-dlp for a video URL.
 *
 * @param formatId yt-dlp identifier for this format
 * @param width video width in pixels, or `null` when unavailable
 * @param height video height in pixels, or `null` when unavailable
 * @param fps video frame rate, or `null` when unavailable
 * @param ext media container extension, or `null` when unavailable
 * @param vcodec video codec name, or `null` when unavailable
 * @param acodec audio codec name, or `null` when unavailable
 */
@Serializable
data class MediaFormat(
    @SerialName("format_id") val formatId: String,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val ext: String? = null,
    val vcodec: String? = null,
    val acodec: String? = null
)
