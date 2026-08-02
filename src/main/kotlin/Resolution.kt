package net.firzen.web

import kotlinx.serialization.Serializable

/**
 * Identifies a video resolution by its pixel width and height.
 */
@Serializable
data class Resolution(
    val width: Int,
    val height: Int
)
