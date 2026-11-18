package app.apexview.cast

import app.apexview.ManifestProtocol
import app.apexview.tiled.*
import androidx.media3.datasource.DataSource
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Decoder-free protected/live producer. The TV independently obtains its Widevine licence. */
internal class NativeComposedCastSession(
    val feeds: List<F1DashFeedCapture.Feed>,
    userAgent: String, ascendon: String, entitlement: String, factory: DataSource.Factory,
    address: String, peer: InetAddress, private val preferredHeight: Int, private val language: String,
    val live: Boolean, val durationMs: Long, private val dashClockOffsetMs: Long, private val hlsClockOffsetMs: Long,
    val audioLabel: String, private val startAtLiveEdge: Boolean = false, val phoneWindowStartMs: Long? = null,
) : AutoCloseable {
    val sourceLabels = feeds.map { it.label }
    private val group = "cast-${UUID.randomUUID()}"
    val compositionGroup: String get() = group
    private val capture = F1ArchiveFeedCapture(userAgent, ascendon, entitlement, factory, 0, preferredHeight, fullPictures = true)
    private val audio = CmafCastAudio(CmafHttpReader(factory), language)
    private val relay = ComposedCmafRelay(address, peer)
    val mediaUrl get() = relay.url
    private var sourceStartUs: Long? = null
    private var pto = 0L
    private val ast = System.currentTimeMillis() - 60_000L
    private var captured = false
    private val gate = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var closed = false
    var originMs = 0L
        private set
    var geometry: ProtectedHevcLiveComposition.SourceGeometry? = null
        private set
    var width = 0
        private set
    var height = 0
        private set
    val ready get() = geometry != null
    var protectedOutput = true
        private set
    init {
        require(feeds.size in 2..6)
        ProtectedHevcLiveComposition.selectFeeds(group, sourceLabels, fullPictures = true)
    }
    suspend fun update(phoneClockMs: Long): F1DashFeedCapture.Snapshot = withContext(Dispatchers.IO) {
      gate.withLock {
        check(!closed)
        val ahead = if (captured) 12_000 else 0L
        val snapshot = if (live && startAtLiveEdge && !captured) capture.captureNext(group, feeds)
            else if (live) capture.captureLiveAtPosition(group, feeds,
                (phoneClockMs - dashClockOffsetMs + ahead).coerceAtLeast(0), (phoneClockMs - hlsClockOffsetMs + ahead).coerceAtLeast(0))
            else capture.captureAtPosition(group, feeds, (phoneClockMs + ahead).coerceAtLeast(0))
        coroutineContext.ensureActive(); check(!closed)
        snapshot.failure?.takeUnless { it.retryable }?.let { throw PermanentCaptureException(it.reason) }
        ProtectedHevcLiveComposition.rejectionReason(group)?.let { throw PermanentCaptureException(it) }
        if (snapshot.capturedFeeds == 0) return@withContext snapshot
        val sourceUs = requireNotNull(snapshot.alignedSegmentTimeUs)
        val window = capture.outputWindow(group) ?: return@withContext snapshot
        val pssh = capture.widevinePssh()
        var packaged: ProtectedHevcComposedDashPackageBuilder.Package? = null
        try {
            val shape = requireNotNull(window.sourceGeometry) { "Full source-picture geometry was not prepared" }
            if (sourceStartUs == null) {
                sourceStartUs = sourceUs; pto = window.fragments.first().baseDecodeTimeTicks
                val actualHls = snapshot.actualManifests.values.firstOrNull() == ManifestProtocol.HLS
                originMs = sourceUs / 1000 + if (live) if (actualHls) hlsClockOffsetMs else dashClockOffsetMs else 0L
            }
            val selected = feeds.firstOrNull { it.label == audioLabel } ?: feeds.first()
            audio.capture(selected.manifestUrl, sourceUs, live)
            val video = ProtectedHevcComposedDashPackageBuilder.build(window, pssh, pto, ast, clearOutput = capture.clearOutput)
            packaged = video
            val merged = audio.appendTo(video, requireNotNull(sourceStartUs))
            // appendTo shares the video byte arrays. The relay now owns this single package.
            packaged = merged
            check(!closed); coroutineContext.ensureActive()
            relay.publish(merged); packaged = null
            geometry = shape; width = merged.width; height = merged.height
            protectedOutput = !capture.clearOutput || audio.encrypted
            captured = true
            android.util.Log.i("PitwallCast", "PROTECTED_CMAF_RELAY sources=${feeds.size} source=${shape.displayWidth}x${shape.displayHeight} atlas=${width}x$height encrypted=$protectedOutput frames=${merged.sampleCount} fullPictures=true")
        } finally { packaged?.zero(); window.zero(); pssh?.fill(0) }
        snapshot
      }
    }
    override fun close() {
        if (closed) return
        closed = true; relay.close()
        cleanup.launch {
            try { gate.withLock { capture.reset(); audio.close(); ProtectedHevcLiveComposition.clearSelection(group, discardInitialization = true) } }
            finally { cleanup.cancel() }
        }
    }
}
