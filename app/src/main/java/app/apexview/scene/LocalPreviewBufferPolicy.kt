package app.apexview.scene

/** The black local sender may use a small aligned buffer. Visible video,
 * source atlas and Cast encoder resolution never use these dimensions. */
internal object LocalPreviewBufferPolicy {
    data class Size(val width: Int, val height: Int)
    fun size(width: Int, height: Int, producerOnly: Boolean): Size? =
        if (width <= 0 || height <= 0) null else if (producerOnly) Size(16, 16) else Size(width, height)
}
