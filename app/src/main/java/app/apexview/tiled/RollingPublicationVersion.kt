package app.apexview.tiled

/**
 * A live CBCS output window is expensive to duplicate and publish to DASH.
 * Network polling and an unchanged fragment are not new composed video.
 *
 * Only a successfully published output advances the version. Failures remain
 * retryable and a new session has an independent gate. No encrypted payload
 * or license data is retained here, only the media decode-time position.
 */
internal class RollingPublicationVersion {
    private var lastPublishedStartUs: Long? = null

    fun shouldPublish(latestOutputStartUs: Long?): Boolean =
        latestOutputStartUs != null && latestOutputStartUs != lastPublishedStartUs

    fun published(latestOutputStartUs: Long) {
        require(latestOutputStartUs >= 0L)
        lastPublishedStartUs = latestOutputStartUs
    }
}
