package net.firzen.web

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Describes one media stream reported by yt-dlp for a video URL.
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
