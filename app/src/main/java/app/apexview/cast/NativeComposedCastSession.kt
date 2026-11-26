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
    audioLabel: String, private val startAtLiveEdge: Boolean = false, val phoneWindowStartMs: Long? = null,
    tls: app.apexview.cast.local.PitwallTls.Session,
    private val nativeContext: android.content.Context? = null,
    initialNativeRequested: Boolean = false,
    autoBandwidthBps: Long? = null,
) : AutoCloseable {
    val sourceLabels = feeds.map { it.label }
    val offeredSourceDimensions: Map<String, List<Pair<Int, Int>>> get() = capture.offeredSourceDimensions
    private val group = "cast-${UUID.randomUUID()}"
    val compositionGroup: String get() = group
    init { require(feeds.size in 2..6 && sourceLabels.distinct().size == feeds.size) }
    @Volatile private var nativeRequested = initialNativeRequested
    fun setNativePreference(enabled: Boolean) { nativeRequested = enabled }
    private val capture = F1ArchiveFeedCapture(userAgent, ascendon, entitlement, factory, 0, preferredHeight,
        fullPictures = true, nativeContext = nativeContext, nativeRequested = { nativeRequested },
        autoBandwidthBps = autoBandwidthBps)
    private val initialAudioIndex = feeds.indexOfFirst { it.label == audioLabel }.also { require(it >= 0) }
    private val audioReader = CmafHttpReader(factory)
    private val sidecars = RelayMediaBudget()
    private val textTracks = linkedMapOf<Int, CmafCastText>()
    private val audioTracks = linkedMapOf(initialAudioIndex to CmafCastAudio(audioReader, language, initialAudioIndex, sidecars, { nativeRequested }))
    private val audio get() = audioTracks.getValue(initialAudioIndex)
    @Volatile var audioLabel: String = audioLabel
        private set
    @Volatile private var selectableAudioIndices: Set<Int> = emptySet()
    val selectedAudioIndex: Int get() = sourceLabels.indexOf(audioLabel)
    fun selectAudio(label: String): Int? {
        val index = sourceLabels.indexOf(label)
        if (index !in selectableAudioIndices) return null
        audioLabel = label
        return index
    }
    private val relay = ComposedCmafRelay(address, peer, tls = tls)
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
        ProtectedHevcLiveComposition.selectFeeds(group, sourceLabels, fullPictures = true,
            castNativeHeader = true, nativeHeaderRequested = nativeRequested, nativeContext = nativeContext)
    }
    suspend fun update(phoneClockMs: Long): F1DashFeedCapture.Snapshot = withContext(Dispatchers.IO) {
      gate.withLock {
        check(!closed)
        // Preference changes are applied at a complete validated publication boundary.
        ProtectedHevcLiveComposition.updateCastNativeHeaderPreference(group, nativeRequested)
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
        val window = capture.outputWindow(group, shareNative = true) ?: return@withContext snapshot
        val pssh = capture.widevinePssh()
        var packaged: ProtectedHevcComposedDashPackageBuilder.Package? = null
        var windowTransferred = false
        try {
            val shape = requireNotNull(window.sourceGeometry) { "Full source-picture geometry was not prepared" }
            if (sourceStartUs == null) {
                sourceStartUs = sourceUs; pto = window.fragments.first().baseDecodeTimeTicks
                val actualHls = snapshot.actualManifests.values.firstOrNull() == ManifestProtocol.HLS
                originMs = sourceUs / 1000 + if (live) if (actualHls) hlsClockOffsetMs else dashClockOffsetMs else 0L
            }
            val programmes = capture.hlsProgrammes
            val selected = feeds[initialAudioIndex]
            audio.capture(selected.manifestUrl, sourceUs, live, programmes[selected.label])
            if (!captured) {
                // Freeze representation identities before the receiver prepares
                // its dynamic Period. Alternates never appear/disappear mid-load.
                for (index in feeds.indices) {
                    if (index == initialAudioIndex) continue
                    coroutineContext.ensureActive()
                    val alternate = audioTracks[index] ?: CmafCastAudio(audioReader, language, index, sidecars, { nativeRequested })
                    try {
                        alternate.capture(feeds[index].manifestUrl, sourceUs, live, programmes[feeds[index].label])
                        if (alternate.compatibleWith(audio)) audioTracks[index] = alternate
                        else { audioTracks.remove(index); alternate.close() }
                    } catch (failure: Exception) {
                        audioTracks.remove(index)
                        alternate.close()
                        coroutineContext.ensureActive()
                        android.util.Log.i("PitwallCast", "NATIVE_AUDIO_ALTERNATE_UNAVAILABLE index=$index type=${failure.javaClass.simpleName}")
                    }
                }
            } else {
                for ((index, alternate) in audioTracks) {
                    if (index == initialAudioIndex) continue
                    coroutineContext.ensureActive()
                    try { alternate.capture(feeds[index].manifestUrl, sourceUs, live, programmes[feeds[index].label]) }
                    catch (failure: Exception) {
                        coroutineContext.ensureActive()
                        if (index == selectedAudioIndex) throw failure
                        android.util.Log.w("PitwallCast", "NATIVE_AUDIO_ALTERNATE_STALE index=$index type=${failure.javaClass.simpleName}")
                    }
                }
            }
            for (index in audioTracks.keys) {
                val feed = feeds[index]
                if (snapshot.actualManifests[feed.label] == ManifestProtocol.HLS && feed.label !in programmes) continue
                val captions = textTracks.getOrPut(index) { CmafCastText(audioReader, sidecars, index, (8 / feeds.size).coerceIn(1, 4)) }
                try {
                    captions.capture(feed.manifestUrl, sourceUs, requireNotNull(sourceStartUs), programmes[feed.label])
                } catch (failure: Exception) {
                    coroutineContext.ensureActive()
                    android.util.Log.w("PitwallCast", "NATIVE_TEXT_MANIFEST_UNAVAILABLE index=$index type=${failure.javaClass.simpleName}")
                }
            }
            val video = ProtectedHevcComposedDashPackageBuilder.build(window, pssh, pto, ast,
                clearOutput = capture.clearOutput, takeOwnership = true)
            packaged = video
            windowTransferred = true
            // Heap arrays transfer to the package. Its native entries retained
            // separate leases; the detached window's original tokens end here.
            window.fragments.forEach { it.closeRetained() }
            val publication = CastDashPublication(video)
            for (track in audioTracks.values) track.appendTo(publication, requireNotNull(sourceStartUs))
            textTracks.values.forEach { it.appendTo(publication) }
            check(!closed); coroutineContext.ensureActive()
            publication.publish(relay); packaged = null
            selectableAudioIndices = audioTracks.filterValues { it.latestSourceUs == sourceUs }.keys.toSet()
            geometry = shape; width = video.width; height = video.height
            protectedOutput = !capture.clearOutput || audioTracks.values.any { it.encrypted }
            captured = true
            android.util.Log.i("PitwallCast", "GC_CAST_STITCHING requested=${if (nativeRequested) "RUST" else "JVM"} " +
                "route=${capture.stitchingRoute(group)} nativePublishedBytes=${video.nativeResources.values.sumOf { it.size.toLong() }} sharedHeapPublishedBytes=${video.sharedHeapResources.values.sumOf { it.size.toLong() }}")
            android.util.Log.i("PitwallCast", "PROTECTED_CMAF_RELAY sources=${feeds.size} source=${shape.displayWidth}x${shape.displayHeight} atlas=${width}x$height encrypted=$protectedOutput frames=${video.sampleCount} fullPictures=true sharedPublicationBytes=${publication.reusedBytes} sharedRetainedBytes=${sidecars.bytesInUse()}")
        } finally { packaged?.zero(); if (!windowTransferred) window.zero(); pssh?.fill(0) }
        snapshot
      }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        fun release(owner: String, action: () -> Unit) {
            try { action() }
            catch (error: RuntimeException) {
                android.util.Log.w("PitwallCast", "NATIVE_CAST_RELEASE_FAILED owner=$owner", error)
            }
        }
        try { release("relay") { relay.close() } }
        finally {
            cleanup.launch {
                try {
                    gate.withLock {
                        release("capture") { capture.reset() }
                        val audio = audioTracks.values.toList()
                        audioTracks.clear()
                        val text = textTracks.values.toList()
                        textTracks.clear()
                        audio.forEach { release("audio-track") { it.close() } }
                        text.forEach { release("text-track") { it.close() } }
                        release("composition") {
                            ProtectedHevcLiveComposition.clearSelection(group, discardInitialization = true)
                        }
                    }
                } finally { cleanup.cancel() }
            }
        }
    }
}
