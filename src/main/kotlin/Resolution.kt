package net.firzen.web

import kotlinx.serialization.Serializable

/**
 * Identifies a video resolution by its pixel width and height.
 *
 * @param width video width in pixels
 * @param height video height in pixels
 */
@Serializable
data class Resolution(
    val width: Int,
    val height: Int
)
