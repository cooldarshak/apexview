package app.apexview.tiled

import app.apexview.ManifestProtocol
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

internal interface CmafCaptureLifecycle { fun reset() }

/** Selects a real protocol acquisition path. The DASH parser still rejects HLS. */
internal class F1ArchiveFeedCapture(
    userAgent: String, ascendon: String, entitlement: String,
    dataSourceFactory: DataSource.Factory? = null,
) : CmafCaptureLifecycle {
    private val factory = dataSourceFactory ?: DefaultHttpDataSource.Factory().setUserAgent(userAgent)
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(mapOf("ascendontoken" to ascendon, "entitlementtoken" to entitlement))
    private val dash = F1DashFeedCapture(userAgent, ascendon, entitlement, dataSourceFactory = factory)
    private val hls = F1HlsFeedCapture(factory)
    private var usingHls = false
    val clearOutput get() = usingHls && hls.clearOutput == true
    val protection get() = if (usingHls) when (hls.clearOutput) { true -> "CLEAR"; false -> "CBCS"; null -> "UNVERIFIED" } else "CBCS"

    suspend fun captureAtPosition(group: String, feeds: List<F1DashFeedCapture.Feed>, playbackPositionMs: Long): F1DashFeedCapture.Snapshot {
        if (feeds.all { ManifestProtocol.fromUrl(it.manifestUrl) == ManifestProtocol.HLS }) usingHls = true
        if (usingHls) return hls.captureAtPosition(group, feeds, playbackPositionMs)
        val snapshot = dash.captureAtPosition(group, feeds, playbackPositionMs)
        // Extensionless/redirected HLS responses are identified by their bodies, then reparsed
        // by the HLS parser. This opens no extra F1 playback session.
        if (snapshot.failure != null && snapshot.actualManifests.values.any { it == ManifestProtocol.HLS } &&
            snapshot.actualManifests.values.none { it == ManifestProtocol.DASH }) {
            usingHls = true
            return hls.captureAtPosition(group, feeds, playbackPositionMs)
        }
        return snapshot
    }

    fun outputWindow(group: String) = if (usingHls) hls.outputWindow(group) else ProtectedHevcLiveComposition.outputWindow(group)
    fun widevinePssh(): ByteArray? = if (usingHls) hls.widevinePssh() else dash.widevinePssh()
    override fun reset() { dash.reset(); hls.reset(); usingHls = false }
}
