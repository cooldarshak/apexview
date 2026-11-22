package app.apexview.tiled

/**
 * A live CBCS output window is expensive to duplicate and publish to DASH.
 * Network polling and an unchanged fragment are not new composed video.
 *
 * Only a successfully published output advances the version. Failures remain
 * retryable and a new session has an independent gate. No encrypted payload
 * or license data is retained here, only the monotonic output version.
 */
internal class RollingPublicationVersion {
    private var lastPublishedVersion: Long? = null

    fun shouldPublish(latestVersion: Long?): Boolean =
        latestVersion != null && latestVersion != lastPublishedVersion

    fun published(latestVersion: Long) {
        require(latestVersion > 0L)
        lastPublishedVersion = latestVersion
    }
}
