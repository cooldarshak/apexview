package app.apexview.tiled

import app.apexview.ManifestProtocol
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

internal interface CmafCaptureLifecycle { fun reset() }

/** Selects a real protocol acquisition path. The DASH parser still rejects HLS. */
internal class F1ArchiveFeedCapture(
    userAgent: String, ascendon: String, entitlement: String,
    dataSourceFactory: DataSource.Factory? = null,
    private val minimumHeight: Int = 0, private val maximumHeight: Int = 2160,
) : CmafCaptureLifecycle {
    private val factory = dataSourceFactory ?: DefaultHttpDataSource.Factory().setUserAgent(userAgent)
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(mapOf("ascendontoken" to ascendon, "entitlementtoken" to entitlement))
    private val dash = F1DashFeedCapture(userAgent, ascendon, entitlement, dataSourceFactory = factory, maximumVideoHeight = maximumHeight, minimumVideoHeight = minimumHeight)
    private val hls = F1HlsFeedCapture(factory, maximumHeight, minimumCaptureHeight = minimumHeight)
    private var usingHls = false
    val clearOutput get() = usingHls && hls.clearOutput == true
    val protection get() = if (usingHls) when (hls.clearOutput) { true -> "CLEAR"; false -> "CBCS"; null -> "UNVERIFIED" } else "CBCS"

    suspend fun prepareVod(feeds: List<F1DashFeedCapture.Feed>, positionMs: Long, clockLabel: String,
                           preferredHeight: Int, commentary: String, minimumHeight: Int = 0): HevcArchiveVodSession {
        // A cancellation/reset of an older capture cannot zero an in-flight preparation's maps.
        val worker = F1HlsFeedCapture(factory)
        return try { worker.prepareVod(feeds, positionMs, clockLabel, preferredHeight, commentary, minimumHeight) }
        finally { worker.reset() }
    }

    suspend fun captureNext(group: String, feeds: List<F1DashFeedCapture.Feed>): F1DashFeedCapture.Snapshot {
        if (feeds.all { ManifestProtocol.fromUrl(it.manifestUrl) == ManifestProtocol.HLS }) usingHls = true
        if (usingHls) return hls.captureAtPosition(group, feeds, 0, live = true)
        val snapshot = dash.captureNext(group, feeds)
        if (snapshot.failure != null && snapshot.actualManifests.values.any { it == ManifestProtocol.HLS } &&
            snapshot.actualManifests.values.none { it == ManifestProtocol.DASH }) {
            usingHls = true; return hls.captureAtPosition(group, feeds, 0, live = true)
        }
        return snapshot
    }

    suspend fun captureLiveAtPosition(group: String, feeds: List<F1DashFeedCapture.Feed>, dashPositionMs: Long,
                                      hlsPositionMs: Long): F1DashFeedCapture.Snapshot {
        if (feeds.all { ManifestProtocol.fromUrl(it.manifestUrl) == ManifestProtocol.HLS }) usingHls = true
        if (usingHls) return hls.captureAtPosition(group, feeds, hlsPositionMs, live = true, followLiveEdge = false)
        val snapshot = dash.captureAtPosition(group, feeds, dashPositionMs)
        if (snapshot.failure != null && snapshot.actualManifests.values.any { it == ManifestProtocol.HLS } &&
            snapshot.actualManifests.values.none { it == ManifestProtocol.DASH }) {
            usingHls = true
            return hls.captureAtPosition(group, feeds, hlsPositionMs, live = true, followLiveEdge = false)
        }
        return snapshot
    }

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
        if (snapshot.alignedSegmentTimeUs != null) {
            val timing = ProtectedHevcLiveComposition.outputTiming(group)
            val latestStartUs = ProtectedHevcLiveComposition.latestOutputStartUs(group)
            if (timing != null && latestStartUs != null) {
                val offsetUs = snapshot.alignedSegmentTimeUs - latestStartUs
                return snapshot.copy(outputTiming = timing.shifted(offsetUs))
            }
        }
        return snapshot
    }

    fun outputWindow(group: String) = if (usingHls) hls.outputWindow(group) else ProtectedHevcLiveComposition.outputWindow(group)
    fun widevinePssh(): ByteArray? = if (usingHls) hls.widevinePssh() else dash.widevinePssh()
    override fun reset() { dash.reset(); hls.reset(); usingHls = false }
}
