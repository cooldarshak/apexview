package app.apexview.tiled

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal open class ArchiveVodBackendRequired(message: String) : IOException(message)
internal class ProtectedArchiveBackendRequired : ArchiveVodBackendRequired("Protected archive requires the existing Widevine composition backend")
internal class ArchiveVodMediaException(val reason: String, val retryable: Boolean) : IOException(reason)

internal class ArchiveVodLoadErrorPolicy : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val failure = generateSequence(info.exception as Throwable) { it.cause }.filterIsInstance<ArchiveVodMediaException>().firstOrNull()
        return if (failure?.retryable == false) C.TIME_UNSET else super.getRetryDelayMsFor(info)
    }
}

/**
 * Tizen-style replay delivery, implemented natively: one finite MPD, numbered on-demand
 * composed segments, a bounded cache and one warm-ahead task. No source player clock drives it.
 * Audio is supplied to the same Media3 player through an audio-only native HLS media source.
 * The clear composer independently rejects protected tracks; no DRM data is removed.
 */
internal class HevcArchiveVodSession(
    private val reader: CmafHttpReader,
    private val inputs: List<Input>,
    val clockLabel: String,
    private val maximumOutputWidth: Int = 4096,
    val audioUri: Uri? = null,
    val audioUris: Map<String, Uri> = emptyMap(),
    stableAudioUris: Set<Uri> = emptySet(),
    captionSources: Map<String, ArchiveCaptionSource> = emptyMap(),
    val offeredSourceDimensions: Map<String, List<Pair<Int, Int>>> = emptyMap(),
) : AutoCloseable {
    class Input(val label: String, val playlist: HlsMediaPlaylist, val format: Format?,
                val initialization: ByteArray, val report: CmafBoxInspector.Report,
                val initializationMap: HlsMediaPlaylist.Segment)
    private data class Group(val startUs: Long, val durationUs: Long, val indexes: List<Int>)
    internal data class TrackOffers(val stableAudioUris: Set<Uri>, val captions: Map<String, ArchiveCaptionSource>)
    internal data class ProgrammeOffer(val stableAudio: Uri?, val captions: ArchiveCaptionSource?)
    @Volatile private var preparedTracks = TrackOffers(stableAudioUris.toSet(), captionSources.toMap())
    val stableAudioUris: Set<Uri> get() = preparedTracks.stableAudioUris
    val captionSources: Map<String, ArchiveCaptionSource> get() = preparedTracks.captions
    internal fun trackOffersSnapshot(): TrackOffers = preparedTracks

    private val sidecars = ArchiveSidecarDiscovery<ProgrammeOffer>(failure = { _, error ->
        android.util.Log.w("ApexviewPlaybackOffer", "ARCHIVE_OPTIONAL_SIDECAR_FAILED type=${error.javaClass.simpleName}")
    })

    /** Primary/selected programme validation is required before handoff.
     * The video-only proof does not depend on separate sidecar playlists. */
    internal fun installPreparedTracks(audio: Set<Uri>, captions: Map<String, ArchiveCaptionSource>) {
        synchronized(cacheLock) {
            checkOpen()
            require(audio.all { it in audioUris.values })
            require(captions.keys.all { it in audioUris })
            preparedTracks = TrackOffers(audio.toSet(), captions.toMap())
        }
    }

    /** Alternate programmes are discovered after the primary is validated.
     * Their exact authorized offer and metadata are captured by the loader.
     * A consumer can wait for its requested programme, never the entire list. */
    internal fun discoverOptionalTracks(labels: List<String>, load: suspend (String) -> ProgrammeOffer) {
        require(labels.all { it in audioUris })
        sidecars.start(labels, load) { label, offer ->
            synchronized(cacheLock) {
                checkOpen()
                require(offer.stableAudio == null || audioUris[label] == offer.stableAudio)
                val current = preparedTracks
                preparedTracks = TrackOffers(
                    current.stableAudioUris + listOfNotNull(offer.stableAudio),
                    if (offer.captions == null) current.captions else current.captions + (label to offer.captions))
            }
        }
    }

    internal fun programmePending(label: String): Boolean = sidecars.isPending(label)
    internal suspend fun awaitProgramme(label: String) = sidecars.await(label)

    private val groups = align(inputs, clockLabel)
    // Re-parse each exact owned initialization ONCE, not once per 5.76-second segment.
    // The initial read also checks that these immutable reports correspond to the bytes we own.
    private val validatedInitializations = inputs.map { input -> CmafBoxInspector.inspect(input.initialization).also {
            require(it.errors.isEmpty() && it.tracks.size == 1) { "Invalid archive source initialization" }
        }
    }
    private val sourceInitialization = ArchiveInitializationBacking(inputs.map { it.initialization })
    private val nativeClear = NativeClearArchiveBridge()
    private val nativeBudgetObservation = NativeStitchingBudgetObservation()
    internal val bufferProduction = app.apexview.ArchiveForwardBufferPolicy.ProductionWindow()
    @Volatile private var nativeBudgetContext: android.content.Context? = null
    private val httpCoalescedBytes = java.util.concurrent.atomic.AtomicLong()
    private val media3DeliveredBytes = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var nativeDemand: ArchiveBufferPolicy.NativeDemand? = null

    fun updateNativePlaybackDemand(positionMs: Long, bufferedMs: Long, speed: Float, advancing: Boolean) {
        nativeDemand = ArchiveBufferPolicy.NativeDemand(positionMs.coerceAtLeast(0L), bufferedMs.coerceAtLeast(0L),
            speed, advancing, android.os.SystemClock.elapsedRealtime())
        if (advancing && positionMs >= 0L) synchronized(cacheLock) {
            if (!closed && owners.get() == 1 && !baseReleased.get())
                cache.retirePlayed(segmentAt(ArchiveTimelineSearch.msToUs(positionMs)), localDeliveryEpoch)
        }
    }

    /** Enable only for an explicit Rust request. No arena allocation in G-A. */
    fun configureNativeBudget(context: android.content.Context) {
        nativeBudgetContext=context.applicationContext
        nativeBudgetObservation.log(context.applicationContext)
    }

    val nativeStitchingDecision: NativeStitchingSelection.Decision get() = nativeClear.decision
    val nativeStitchingCandidate: Boolean get() = nativeClear.candidate

    /** The cold local clear probe uses its JVM result for the first native comparison. */
    internal fun enableColdProbe(context: android.content.Context) {
        if (inputs.size > 1) nativeClear.enable(context.applicationContext)
    }

    fun configureNativeClear(context: android.content.Context) {
        if (inputs.size < 2) return
        synchronized(cacheLock) {
            if (closed || initialization == null) return
            nativeClear.enable(context)
        }
        configureNativeBudget(context)
    }

    fun disableNativeClear(reason: String, requested: Boolean) {
        synchronized(cacheLock) { nativeClear.disable(reason, requested) }
        // Memory pressure policy applies to Kotlin as well as Rust. Keep the
        // application context until the archive owner retires.
    }

    private val cache = BoundedArchiveFragmentCache()
    private val compatibleMaps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val cacheLock = Any()
    private val production = ArchiveSegmentSingleFlight()
    private val delivery = ArchiveSegmentDelivery(
        cache = cache, cacheLock = cacheLock, production = production,
        checkOpen = ::checkOpen, composeAndPublish = ::composeAndPublish,
        onCancelledWarmupRecovery = { number ->
            android.util.Log.i("ApexviewSingleDecoder",
                "ARCHIVE_VOD_FOREGROUND_RECOVER segment=$number cause=CANCELLED_PREFETCH")
        },
        onEvictionRace = { number, attempt ->
            android.util.Log.w("ApexviewSingleDecoder",
                "ARCHIVE_VOD_DELIVERY_CACHE_RACE segment=$number retry=$attempt")
        },
    )
    @Volatile private var cachedMixedAtlas: HevcMixedArchiveAtlasPlan.Plan? = null

    // Sources, SPS/PPS and coded atlas geometry cannot change within this one
    // finite CMAF session. Previously every ~5.76s segment reallocated source
    // descriptors, searched the uniform atlas and reconstructed all tile cells.
    // Keep the immutable geometry once; each fragment still independently
    // validates its HEVC initialization, sync picture and sample timing.
    private val sourceTiles by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        inputs.map { input ->
            val cfg = requireNotNull(input.report.tracks.single { it.handler == "vide" }.hevcConfiguration)
            HevcMixedArchiveAtlasPlan.Source(cfg.vps.single(), cfg.sps.single(), cfg.pps.single())
        }
    }
    private val uniformAtlasPlan: HevcArchiveAtlasPlan? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val sps = sourceTiles.first().sps
        HevcArchiveAtlasPlan.choose(inputs.size, sps.width, sps.height,
            maximumOutputWidth, HevcArchiveAtlasPlan.MAX_CODED_HEIGHT)
    }
    private val uniformCells: List<HevcTizenGridComposer.CellSelection> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val plan = requireNotNull(uniformAtlasPlan) { "No valid equal-source atlas geometry" }
        val pps = sourceTiles.first().pps
        val columns = if (pps.tilesEnabled) pps.numTileColumns else 1
        val rows = if (pps.tilesEnabled) pps.numTileRows else 1
        (0 until plan.columns * plan.rows).flatMap { slot ->
            val feed = slot.coerceAtMost(inputs.lastIndex)
            (0 until rows).flatMap { row -> (0 until columns).map { column ->
                HevcTizenGridComposer.CellSelection(feed, row * columns + column,
                    (slot % plan.columns) * columns + column,
                    (slot / plan.columns) * rows + row)
            } }
        }
    }
    // Separate bounded execution lanes. The group permit limits admission before
    // submitting per-feed jobs; a canceled Future is purged rather than occupying
    // a queue slot until a slow HTTP operation finishes.
    private val foregroundAdmission = ArchiveWorkAdmission(maxWaiters = 4)
    private val prefetchAdmission = ArchiveWorkAdmission(maxWaiters = 1)
    private fun boundedFetchPool(name: String): ThreadPoolExecutor {
        val workers = inputs.size.coerceIn(1, 4)
        return ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(inputs.size.coerceAtLeast(1)),
            java.util.concurrent.ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } })
    }
    private val fetchers = boundedFetchPool("ApexviewVodFetch")
    private val prefetchFetchers = boundedFetchPool("ApexviewVodPrefetchNet")
    private val warmer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), java.util.concurrent.ThreadFactory { r -> Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "ApexviewVodWarm").apply { isDaemon = true } })
    private val warmLock = Any()
    private var warmActiveNumber: Int? = null
    private var warmActiveTid: Int? = null
    @Volatile private var warmTask: Future<*>? = null
    @Volatile private var warmLimit = ArchiveBufferPolicy.speculativeWarmLimit(-1)

    fun updateThermalStatus(status: Int) {
        val next = ArchiveBufferPolicy.speculativeWarmLimit(status)
        if (warmLimit == next) return
        warmLimit = next
        android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_PREFETCH_THERMAL status=$status maxFollowing=$next foregroundUnchanged=true")
    }
    @Volatile private var closed = false
    private val owners = java.util.concurrent.atomic.AtomicInteger(1)
    private val baseReleased = java.util.concurrent.atomic.AtomicBoolean(false)
    // Guarded by cacheLock. Shared relay ownership and seeks invalidate local delivery proof.
    private var localDeliveryEpoch = 0L
    // Separate production generation: relay lease changes do not invalidate valid media.
    @Volatile private var productionEpoch = 0L
    private fun captureLocalDeliveryEpoch(): Long? = synchronized(cacheLock) {
        if (!closed && owners.get() == 1 && !baseReleased.get()) localDeliveryEpoch else null
    }
    private fun completeLocalDelivery(number: Int, lease: ArchiveFragmentLease, epoch: Long) {
        val freed = synchronized(cacheLock) {
            if (closed || owners.get() != 1 || baseReleased.get() || epoch != localDeliveryEpoch) 0L
            else cache.completeLocalDelivery(number, lease, epoch)
        }
        if (freed > 0L) android.util.Log.i("ApexviewSingleDecoder",
            "GC_ARCHIVE_DELIVERED_TRIM number=$number retiredLogicalBytes=$freed foregroundUnchanged=true")
    }
    fun acquireLease(): AutoCloseable {
        synchronized(cacheLock) {
            checkOpen()
            cache.invalidateLocalDelivery()
            owners.incrementAndGet()
            localDeliveryEpoch++
        }
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable { if (released.compareAndSet(false, true)) releaseOwner() }
    }
    private var initialization: HeapFragmentLease? = null
    private var presentationOffsetTicks: Long? = null
    private var timescale: Long = 0
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var frameRate: Double = 0.0
        private set
    var codedWidth: Int = 0
        private set
    var codedHeight: Int = 0
        private set
    var atlasColumns: Int = 0
        private set
    var atlasRows: Int = 0
        private set
    var sourceCodedWidth: Int = 0
        private set
    var sourceCodedHeight: Int = 0
        private set
    var sourceDisplayWidth: Int = 0
        private set
    var sourceDisplayHeight: Int = 0
        private set
    var mixedSourceRects: List<HevcMixedArchiveAtlasPlan.SourceRect> = emptyList()
        private set
    val durationUs: Long get() = groups.last().let { it.startUs + it.durationUs }
    val sourceLabels: List<String> get() = inputs.map { it.label }
    val segmentCount: Int get() = groups.size
    /** Actual visible source pictures from the selected hvcC/SPS, not the user's quality cap. */
    val actualSourceDimensions: Map<String, Pair<Int, Int>> by lazy { inputs.associate { input ->
        val sps = requireNotNull(input.report.tracks.single { it.handler == "vide" }
            .hevcConfiguration).sps.single()
        input.label to (sps.displayWidth to sps.displayHeight)
    } }
    val selectedHeight: Int? get() = inputs.first().format?.height
    val archiveStartTimeMs: Long? get() = inputs.firstOrNull { it.label == clockLabel }?.playlist?.let {
        if (it.hasProgramDateTime) it.startTimeUs / 1000L else null
    }

    /** A fixed probe proves composition. It is never required to catch another player's clock. */
    fun prepare(positionMs: Long) {
        val started = System.nanoTime()
        require(inputs.size in 1..app.apexview.AppState.MAX_SINGLE_DECODER_FEEDS)
        inputs.forEach { input ->
            val track = input.report.tracks.single { it.handler == "vide" }
            if (track.encrypted) throw ProtectedArchiveBackendRequired()
            require(track.hevcConfiguration != null) { "Archive input is not HEVC CMAF" }
        }
        val probe = segmentAt(ArchiveTimelineSearch.msToUs(positionMs))
        val coldNativeRequested = nativeClear.requested
        // Prepare and verify the manifest without borrowing a full extra CMAF copy.
        delivery.obtain(probe, copyForCaller = false)
        val mediaReadyMs = (System.nanoTime() - started) / 1_000_000L
        // Validate the complete finite manifest with Media3 before any decoder is released.
        val parsed = androidx.media3.exoplayer.dash.manifest.DashManifestParser().parse(MANIFEST_URI,
            manifest().inputStream())
        require(!parsed.dynamic && parsed.periodCount == 1) { "Archive MPD must have one finite period" }
        android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_PREPARED feeds=${inputs.size} segments=$segmentCount " +
            "sourceHeight=$selectedHeight output=${width}x$height probe=$probe fullFrames=true clock=ONE_PLAYER " +
            "probeReadyMs=$mediaReadyMs preparedMs=${(System.nanoTime() - started) / 1_000_000L} " +
            "coldRequestedRust=$coldNativeRequested coldActual=${nativeClear.decision.actual} " +
            "coldReason=${nativeClear.decision.reason}")
        android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_SOURCE_RENDITIONS " +
            inputs.joinToString(", ") { input ->
                val actual = actualSourceDimensions.getValue(input.label)
                val sps = requireNotNull(input.report.tracks.single { it.handler == "vide" }
                    .hevcConfiguration).sps.single()
                "${input.label}=${actual.first}x${actual.second}" +
                    "(coded=${sps.width}x${sps.height}," +
                    "hls=${input.format?.width ?: "unknown"}x${input.format?.height ?: "unknown"})"
            })
    }

    /** A single catch-up group after the cold proof, before disturbing a working
     * decoder. Retain the verified geometry and first fragment, never restart
     * manifests or native proof for ordinary forward clock movement. The caller
     * chooses the final handoff clock once; this must not chase it in a loop. */
    internal fun prepareHandoffAt(positionMs: Long) {
        val target = segmentAt(ArchiveTimelineSearch.msToUs(positionMs))
        val cached = synchronized(cacheLock) { checkOpen(); cache.contains(target) }
        val started = System.nanoTime()
        if (!cached) delivery.obtainLease(target).close()
        android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_HANDOFF_PREPARE segment=$target cached=$cached " +
            "waitMs=${(System.nanoTime()-started)/1_000_000L} proof=REUSED")
    }

    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory { ResourceDataSource(this) }

    fun segmentAt(positionUs: Long): Int = ArchiveTimelineSearch.floorIndex(groups.size, positionUs) { groups[it].startUs }

    /** A scrub preview is not a committed seek. Cancel/promote speculative
     * work early but keep the current loader generation valid until commit. */
    fun hintSeek(positionMs: Long) {
        val target = segmentAt(ArchiveTimelineSearch.msToUs(positionMs))
        preemptUnrelatedWarmup(target, seek = true)
    }

    /** Fence publication exactly once at the committed Media3 seek. */
    fun prioritizeSeek(positionMs: Long) {
        val started = System.nanoTime()
        val target = segmentAt(ArchiveTimelineSearch.msToUs(positionMs))
        nativeDemand = null // never use the pre-seek clock for the new target
        bufferProduction.reset()
        synchronized(cacheLock) {
            if (!closed) {
                cache.invalidateLocalDelivery()
                localDeliveryEpoch++
                productionEpoch++
                nativeClear.invalidateForSeek()
            }
        }
        preemptUnrelatedWarmup(target, seek = true)
        android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_SEEK_COMMIT epoch=$productionEpoch targetMs=$positionMs segment=$target indexAndFenceMs=${(System.nanoTime() - started) / 1_000_000L}")
    }

    // Cast/HTTP callers keep their existing independent mutable payload contract.
    fun segment(number: Int): ByteArray =
        requestSegment(number, { delivery.obtain(number, copyForCaller = true)!! }, { it.fill(0) })

    internal fun segmentLease(number: Int): ArchiveFragmentLease =
        requestSegment(number, { delivery.obtainLease(number) }, { it.close() })

    // Only the local Media3 loader is seek-fenced. Independent Cast HTTP readers
    // keep their immutable segment contract even if the local clock changes.
    private fun localSegmentLease(number: Int, epoch: Long): ArchiveFragmentLease =
        requestSegment(number, { delivery.obtainLease(number) { !closed && productionEpoch == epoch } },
            { it.close() }, epoch)

    private fun <T> requestSegment(number: Int, acquire: () -> T, discard: (T) -> Unit,
                                   localEpoch: Long? = null): T {
        require(number in groups.indices) { "Archive segment is outside the finite timeline" }
        if (localEpoch != null && localEpoch != productionEpoch) throw ArchiveWarmupCancelledException()
        preemptUnrelatedWarmup(number)
        val requestEpoch = productionEpoch
        val started = System.nanoTime()
        val wasCached = synchronized(cacheLock) { cache.contains(number) }
        val resource = acquire()
        try {
        if (localEpoch != null && localEpoch != productionEpoch) throw ArchiveWarmupCancelledException()
        val waitMs = (System.nanoTime() - started) / 1_000_000L
        // Record every foreground request. Only logging misses/slow hits biases the
        // sample and makes a real p95 impossible to calculate from exported logs.
        android.util.Log.i("ApexviewSingleDecoder",
            "ARCHIVE_VOD_REQUEST number=$number cached=$wasCached waitMs=$waitMs")
        val headroom = nativeBudgetContext?.let { context ->
            runCatching { NativeStitchingDeviceBudget.sample(context) }.getOrNull()
        }
        val constrained = NativeStitchingDeviceBudget.observePressure(headroom)
        if (headroom != null && (headroom.lowMemory || headroom.available <= headroom.threshold)) {
            val freed = cache.trimBehind(number)
            if (freed > 0L) android.util.Log.i("ApexviewSingleDecoder",
                "GC_ARCHIVE_PRESSURE_TRIM behindBytes=$freed foregroundUnchanged=true")
        }
        val observed = nativeBudgetObservation.sizes()
        val localLimit = ArchiveBufferPolicy.localWarmLimit(warmLimit, nativeDemand,
            android.os.SystemClock.elapsedRealtime(), owners.get() == 1 && !baseReleased.get())
        val memoryWarmLimit = if (constrained) 0 else ArchiveBufferPolicy.memoryWarmLimit(localLimit, headroom, observed.input, observed.output)
        synchronized(warmLock) {
            if (!closed && requestEpoch == productionEpoch && memoryWarmLimit > 0 && number < groups.lastIndex && (warmTask == null || warmTask!!.isDone)) {
                val next = ArchiveBufferPolicy.warmFollowing(number, groups.lastIndex, memoryWarmLimit)
                warmer.purge()
                warmTask = warmer.submit {
                    for (candidate in next) {
                        val currentLimit = ArchiveBufferPolicy.localWarmLimit(warmLimit, nativeDemand,
                            android.os.SystemClock.elapsedRealtime(), owners.get() == 1 && !baseReleased.get())
                        if (closed || requestEpoch != productionEpoch || Thread.currentThread().isInterrupted || candidate - number > currentLimit) break
                        val currentMemory = nativeBudgetContext?.let { context ->
                            runCatching { NativeStitchingDeviceBudget.sample(context) }.getOrNull()
                        }
                        if (NativeStitchingDeviceBudget.observePressure(currentMemory)) break
                        val currentSizes = nativeBudgetObservation.sizes()
                        if (candidate - number > ArchiveBufferPolicy.memoryWarmLimit(warmLimit, currentMemory,
                            currentSizes.input, currentSizes.output)) break
                        if (!cache.canSpeculate(candidate, currentSizes.output)) break
                        val estimate = SpeculativeMemoryAdmission.charge(currentSizes.input, currentSizes.output)
                        val reservation = if (currentMemory != null && estimate != null) {
                            SpeculativeMemoryAdmission.process.tryReserve(estimate,
                                SpeculativeMemoryAdmission.limit(currentMemory)) ?: break
                        } else null
                        try {
                            synchronized(warmLock) {
                                warmActiveNumber = candidate
                                warmActiveTid = android.os.Process.myTid()
                            }
                            // Prefetch populates the bounded cache, not a DataSource reader.
                            // Do NOT allocate a second multi-MB ByteArray just to zero it.
                            try {
                                delivery.obtain(candidate, copyForCaller = false) { !closed && requestEpoch == productionEpoch }
                            } catch (failure: Exception) {
                                // The previous runCatching().isFailure silently hid production errors.
                                // Never include exception messages: URLs can carry F1 credentials.
                                val reason = ArchiveWarmupFailurePolicy.classify(
                                    failure, closed, Thread.currentThread().isInterrupted)
                                val entry = "ARCHIVE_VOD_PREFETCH_STOP segment=$candidate " +
                                    "reason=$reason exception=${failure.javaClass.simpleName}"
                                if (reason == ArchiveWarmupStopReason.CANCELLED)
                                    android.util.Log.i("ApexviewSingleDecoder", entry)
                                else android.util.Log.w("ApexviewSingleDecoder", entry)
                                if (failure is InterruptedException) Thread.currentThread().interrupt()
                                break
                            }
                        } finally {
                            reservation?.close()
                            synchronized(warmLock) {
                                warmActiveNumber = null
                                warmActiveTid = null
                                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                            }
                        }
                    }
                }
            }
        }
            if (localEpoch != null && localEpoch != productionEpoch) throw ArchiveWarmupCancelledException()
            return resource
        } catch (failure: Throwable) {
            discard(resource)
            throw failure
        }
    }

    private fun preemptUnrelatedWarmup(number: Int, seek: Boolean = false) {
        val cached = synchronized(cacheLock) {
            // A UI prefetch-priority hint can race normal session retirement.
            // Foreground DataSource requests still fail closed as before.
            if (seek && closed) return
            checkOpen(); cache.contains(number)
        }
        synchronized(warmLock) {
            if (!cached && warmActiveNumber == number && warmTask?.isDone == false) {
                // Playback is joining this single-flight producer, not speculative work anymore.
                // Promote only this group; its finally block restores background priority.
                warmActiveTid?.let { tid ->
                    runCatching { android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_DEFAULT) }
                        .onFailure { android.util.Log.w("ApexviewSingleDecoder", "ARCHIVE_VOD_PREFETCH_PROMOTION_UNAVAILABLE") }
                }
            }
            if (warmTask?.isDone == false && ArchiveBufferPolicy.preemptWarmup(
                    number, cached, warmActiveNumber, seek)) {
                warmTask?.cancel(true)
                warmer.purge()
            }
        }
    }

    private fun composeAndPublish(number: Int) {
        val epoch = synchronized(cacheLock) { checkOpen(); productionEpoch }
        require(number in groups.indices) { "Archive segment is outside the finite timeline" }
        val warm = Thread.currentThread().name == "ApexviewVodWarm"
        val admission = if (warm) prefetchAdmission else foregroundAdmission
        val requestedAt = System.nanoTime()
        try {
            admission.run(ArchiveFetchDeadline.remainingNs(requestedAt, System.nanoTime())) {
                checkOpen()
                android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_WORK_ADMISSION segment=$number " +
                    "worker=${if (warm) "PREFETCH" else "PLAYBACK"} " +
                    "waitMs=${(System.nanoTime() - requestedAt) / 1_000_000L} queuedGroups=${admission.queued()}")
                val observed = nativeBudgetObservation.sizes()
                val foreground = if (warm) null else SpeculativeMemoryAdmission.process.beginForeground(
                    SpeculativeMemoryAdmission.charge(observed.input, observed.output)
                        ?: SpeculativeMemoryAdmission.sourceForecast(inputs.size, 8 * 1024 * 1024))
                try { composeAndPublishAdmitted(number, requestedAt, epoch) }
                finally { foreground?.close() }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ArchiveWarmupCancelledException()
        }
    }

    private fun composeAndPublishAdmitted(number: Int, requestedAt: Long, epoch: Long) {
        synchronized(cacheLock) {
            checkOpen()
            if (productionEpoch != epoch) throw ArchiveWarmupCancelledException()
        }
        val started = System.nanoTime()
        val cpuAtStart = android.os.Debug.threadCpuTimeNanos()
        // ART process-wide counters include concurrent player/UI allocation; this is a
        // diagnostic upper bound during production, not thread-exclusive compositor bytes.
        val allocatedAtStart = android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
        val gcAtStart = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
        val batches = mutableListOf<List<ByteArray>>()
        val sourceOwner = Any()
        try {
            val anchor = number
            batches += fetch(number, requestedAt, sourceOwner)
            // A finite DASH index may seek directly to any advertised chunk. A continuation
            // fragment cannot be advertised as an independent seek point merely because an
            // earlier GOP was inspected. Never feed a decoder a missing-reference picture.
            val inspectionStarted = System.nanoTime()
            val inspectedMedia = inputs.indices.map { i ->
                HevcClearCmafGridComposer.InspectedMedia.inspect(batches.single()[i], validatedInitializations[i])
            }
            val inspected = inspectedMedia.mapIndexed { i, media ->
                val report = media.reportFor(batches.single()[i], validatedInitializations[i])
                require(report.errors.isEmpty()) { "Invalid source CMAF fragment" }
                report.fragments.single()
            }
            val preflightInspectMs = (System.nanoTime() - inspectionStarted) / 1_000_000L
            require(inspected.all { fragment ->
                fragment.sampleTimings.first().sampleFlags?.let { (it and 0x00010000L) == 0L } == true
            }) {
                "Archive segment starts mid-GOP; independent CMAF segment starts are required for native replay seeking"
            }
            val fetchMs = (System.nanoTime() - started) / 1_000_000L
            checkOpen() // A seek may have interrupted a background fetch.
            val firstTrack = inputs.first().report.tracks.single { it.handler == "vide" }
            val config = requireNotNull(firstTrack.hevcConfiguration)
            val sourceSps = config.sps.single()
            val sourcePps = config.pps.single()
            val columns = if (sourcePps.tilesEnabled) sourcePps.numTileColumns else 1
            val rows = if (sourcePps.tilesEnabled) sourcePps.numTileRows else 1
            val mixedInputs = sourceTiles
            val mixed = mixedInputs.any { it.sps.width != sourceSps.width || it.sps.height != sourceSps.height }
            // Resolve the real per-sample rate before choosing geometry. A smaller
            // atlas that cannot decode at the advertised rate is not eligible.
            val trackScale = requireNotNull(firstTrack.timescale)
            val firstFragment = inspected.first()
            val step = requireNotNull(firstFragment.sampleTimings.first().durationTicks)
            require(firstFragment.sampleTimings.all { it.durationTicks == step }) { "Mixed source sample cadence" }
            require(step > 0L && trackScale > 0L) { "Invalid source sample cadence" }
            val sourceRate = trackScale.toDouble() / step.toDouble()
            val mixedAtlas = if (mixed) requireNotNull(cachedMixedAtlas ?: HevcMixedArchiveAtlasPlan.choose(mixedInputs,
                maximumOutputWidth, HevcArchiveAtlasPlan.MAX_CODED_HEIGHT,
                outputEligible = { width, height ->
                    HevcArchiveDecoderCapability.supportsClearAtlas(width, height, sourceRate)
                })?.also { candidate ->
                    synchronized(cacheLock) { checkOpen(); if (cachedMixedAtlas == null) cachedMixedAtlas = candidate }
                }) {
                "Selected source resolutions have incompatible HEVC tile geometry for one decoder"
            } else null
            val atlas = if (mixedAtlas == null) requireNotNull(uniformAtlasPlan) {
                "Full archive feeds exceed composed picture size; choose fewer feeds or a lower common HEVC rendition"
            } else null
            val (frameRateNumerator, frameRateDenominator) = reducePositiveHevcFrameRate(trackScale, step)
            val cells = mixedAtlas?.cells ?: uniformCells
            checkOpen()
            val sourceInputBytes=batches.sumOf { batch -> batch.sumOf { it.size.toLong() } }
            val compositionStarted = System.nanoTime()
            val produced = sourceInitialization.read { inits ->
                // Exact source arrays are immutable and borrowed through the
                // entire synchronous reference/native job. Closing the session
                // retires the owner without erasing in-flight validation data.
                val composingFeeds = inputs.mapIndexed { i, input -> HevcClearCmafGridComposer.Feed(
                    inits[i], batches.map { it[i] }, input.label, validatedInitializations[i], listOf(inspectedMedia[i])) }
                checkOpen()
                if (inputs.size == 1) NativeClearArchiveBridge.Produced(
                    HevcClearCmafGridComposer.passThrough(composingFeeds.single(), transferMediaOwnership = true))
                else {
                    val request = HevcClearCmafGridComposer.Request(
                        composingFeeds,
                        mixedAtlas?.grid ?: HevcOutputGridDimensions(columns * requireNotNull(atlas).columns, rows * atlas.rows), cells,
                        firstOutputSequenceNumber = anchor + 1L,
                        frameRateNumerator = frameRateNumerator, frameRateDenominator = frameRateDenominator)
                    val expectedInit = if (nativeClear.candidate) synchronized(cacheLock) {
                        checkOpen(); initialization?.copyBytes()
                    } else null
                    if (!nativeClear.candidate) NativeClearArchiveBridge.Produced(HevcClearCmafGridComposer.compose(request))
                    else try {
                        val timeout = ArchiveBufferPolicy.nativeJobTimeoutMs(groups[number].startUs / 1_000L,
                            groups[number].durationUs / 1_000L, nativeDemand, android.os.SystemClock.elapsedRealtime())
                        nativeClear.compose(request, expectedInit,
                            foreground = Thread.currentThread().name != "ApexviewVodWarm",
                            timeoutMs = timeout, retainNativeArchiveMedia = true) {
                            HevcClearCmafGridComposer.compose(request)
                        }
                    } finally { expectedInit?.fill(0) }
                }
            }
            val composed = produced.output
            var publishedFragment: ByteArray? = null
            var publishedInitialization = false
            val nativeFragment = produced.nativeMedia
            var publishedNative = false
            try {
                // Validation/composition has finished borrowing the source.
                // The single-source pass-through transferred its source
                // backing; every other fetched array can now be wiped.
                batches.forEach { batch -> batch.forEach { source ->
                    if (composed.mediaFragments.none { it === source }) source.fill(0)
                } }
                batches.clear()
                app.apexview.MediaMemoryAccounting.process.record(sourceOwner, app.apexview.MediaMemoryAccounting.Kind.CLEAR_SOURCE, 0L)
                checkOpen()
                val compositionMs = (System.nanoTime() - compositionStarted) / 1_000_000L
                val verificationStarted = System.nanoTime()
                // Native bridge already independently inspected these exact
                // transferred immutable bytes and checked all sample timing.
                // JVM/reference routes have no such proof and inspect as before.
                val report = produced.verifiedInitialization ?: CmafBoxInspector.inspect(composed.initializationSegment)
                val outputTrack = report.tracks.single()
                // Container sync flags are insufficient proof of random access. The clear
                // writer emits moof + mdat containing length-prefixed VCL NALs only.
                val outputBytes = if (nativeFragment == null) composed.mediaFragments.single() else {
                    check(composed.mediaFragments.isEmpty()) { "Native fragment must not also retain a JVM media copy" }
                    null
                }
                val outputSize = nativeFragment?.size ?: requireNotNull(outputBytes).size
                fun readHeader(offset: Int, count: Int): ByteArray {
                    require(offset >= 0 && count >= 0 && offset <= outputSize - count)
                    return ByteArray(count).also { bytes ->
                        if (nativeFragment != null) nativeFragment.readTo(bytes, 0, offset, count)
                        else requireNotNull(outputBytes).copyInto(bytes, 0, offset, offset + count)
                    }
                }
                val mdat = java.nio.ByteBuffer.wrap(readHeader(0, 4)).int
                val firstNal = Math.addExact(mdat, 8 + config.nalLengthSize)
                require(inputs.size == 1 || ((readHeader(firstNal, 1)[0].toInt() ushr 1) and 63) in 16..21) {
                    "Archive sync sample does not begin with a real HEVC IRAP picture"
                }
                val last = produced.verifiedFragments?.single() ?: (nativeFragment?.inspect(report)
                    ?: CmafBoxInspector.inspect(requireNotNull(outputBytes), report)).fragments.single()
                val offset = requireNotNull(last.baseDecodeTimeTicks) - scaleUs(groups[number].startUs, trackScale)
                checkOpen()
                val verificationMs = (System.nanoTime() - verificationStarted) / 1_000_000L
                var atlasDiagnostic: String? = null
                // Protect only state ownership/publication. Debug/ART counters and logcat
                // writes can block on JVM/system locks and must not delay a foreground
                // DataSource request behind a speculative producer's cacheLock.
                val (cacheEntries, cachedByteCount) = synchronized(cacheLock) {
                    checkOpen()
                    if (productionEpoch != epoch) throw ArchiveWarmupCancelledException()
                    nativeClear.publish(produced.nativeEpoch) {
                    if (initialization == null) {
                        initialization = HeapFragmentLease.take(composed.initializationSegment)
                        publishedInitialization = true
                        timescale = trackScale
                        presentationOffsetTicks = offset
                        width = requireNotNull(outputTrack.width)
                        height = requireNotNull(outputTrack.height)
                        frameRate = trackScale.toDouble() / step
                        codedWidth = composed.codedWidth
                        codedHeight = composed.codedHeight
                        atlasColumns = atlas?.columns ?: minOf(mixedAtlas!!.grid.columns, inputs.size)
                        atlasRows = atlas?.rows ?: mixedAtlas!!.grid.rows
                        sourceCodedWidth = sourceSps.width; sourceCodedHeight = sourceSps.height
                        sourceDisplayWidth = sourceSps.displayWidth; sourceDisplayHeight = sourceSps.displayHeight
                        mixedSourceRects = mixedAtlas?.sourceRects.orEmpty()
                        val uniqueSourcePixels = mixedInputs.sumOf { it.sps.width.toLong() * it.sps.height }
                        val decodedPixels = composed.codedWidth.toLong() * composed.codedHeight
                        atlasDiagnostic = "ARCHIVE_VOD_ATLAS feeds=${inputs.size} cells=${atlasColumns}x${atlasRows} " +
                            "mixed=$mixed sourceCoded=${sourceSps.width}x${sourceSps.height} " +
                            "sourceDisplay=${sourceSps.displayWidth}x${sourceSps.displayHeight} ctb=${sourceSps.ctbSize} " +
                            "decodedPixels=$decodedPixels uniqueSourcePixels=$uniqueSourcePixels " +
                            "atlasExtraPixels=${decodedPixels - uniqueSourcePixels} " +
                            "atlasShapesEvaluated=${mixedAtlas?.evaluatedShapes ?: 0} " +
                            "atlasGridValidationsRejected=${mixedAtlas?.rejectedGridValidations ?: 0} " +
                            "atlasDecoderCandidatesRejected=${mixedAtlas?.rejectedDecoderCapabilities ?: 0} " +
                            "atlasMinimumAreaProven=${mixedAtlas?.smallestAreaProven ?: (atlas != null)} " +
                            "atlasSearchBudgetLimited=${mixedAtlas?.searchBudgetLimited ?: false}"
                    } else {
                        require(initialization!!.matches(composed.initializationSegment)) {
                            "Archive representation changed its initialization"
                        }
                        require(abs(requireNotNull(presentationOffsetTicks) - offset) <= 1L) {
                            "Archive decode timestamps differ from the VOD timeline"
                        }
                    }
                    // Independent immutable read lease survives cache eviction/engine retirement.
                    if (nativeFragment != null) {
                        cache.putNative(number, nativeFragment)
                        publishedNative = true
                    } else {
                        val output = requireNotNull(outputBytes)
                        cache.put(number, output)
                        publishedFragment = output
                    }
                    cache.size to cache.bytesRetained
                    }
                }
                if (produced.nativeEpoch != null) android.util.Log.i("ApexviewSingleDecoder",
                    nativeClear.decision.diagnostic(NativeStitchingSelection.Path.VERIFIED_CLEAR))
                atlasDiagnostic?.let { android.util.Log.i("ApexviewSingleDecoder", it) }
                val worker = if (Thread.currentThread().name == "ApexviewVodWarm") "PREFETCH" else "PLAYBACK"
                val allocatedDuringProduction = allocatedAtStart?.let { first ->
                    android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                        ?.let { (it - first).coerceAtLeast(0L) }
                }
                val gcDuringProduction = gcAtStart?.let { first ->
                    android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                        ?.let { (it - first).coerceAtLeast(0L) }
                }
                // Reuse the exact verified fragment already in scope, not a
                // second local with the same name or a new payload allocation.
                if(nativeBudgetObservation.observe(sourceInputBytes,outputSize.toLong())) {
                    nativeBudgetContext?.let { nativeBudgetObservation.log(it) }
                }
                val producerMs = (System.nanoTime() - started) / 1_000_000L
                val mediaMs = groups[number].durationUs / 1000L
                bufferProduction.record(producerMs, mediaMs, android.os.SystemClock.elapsedRealtime())
                val memory = cache.memorySnapshot()
                android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_SEGMENT number=$number fetchMs=$fetchMs " +
                    "composeMs=$compositionMs verifyMs=$verificationMs totalMs=${(System.nanoTime() - started) / 1_000_000L} " +
                    "parseMs=${composed.parseMs} rewriteMs=${composed.rewriteMs} packageMs=${composed.packageMs} " +
                    "parseCpuMs=${composed.parseCpuMs} rewriteCpuMs=${composed.rewriteCpuMs} " +
                    "packageCpuMs=${composed.packageCpuMs} " +
                    "cmafInspectorMs=${composed.cmafInspectorMs + preflightInspectMs} reusedCmafReports=${inspectedMedia.size} nalInspectorMs=${composed.nalInspectorMs} " +
                    "producerCpuMs=${((android.os.Debug.threadCpuTimeNanos() - cpuAtStart) / 1_000_000L).coerceAtLeast(0)} " +
                    "processAllocBytesDuringSegment=${allocatedDuringProduction ?: -1L} " +
                    "processGcDuringSegment=${gcDuringProduction ?: -1L} " +
                    "sourceInputBytes=$sourceInputBytes httpCoalescedBytesTotal=${httpCoalescedBytes.get()} " +
                    "media3DeliveredBytesTotal=${media3DeliveredBytes.get()} " +
                    "outputBytes=$outputSize archiveStorage=${if (nativeFragment != null) "NATIVE_FRAGMENT" else "HEAP"} " +
                    "sourceAnnexBBytesAvoided=${composed.sourceAnnexBBytesAvoided} " +
                    "sourceNalCopyBytesAvoided=${composed.sourceNalCopyBytesAvoided} " +
                    "rewrittenTailStagingBytesAvoided=${composed.rewrittenTailStagingBytesAvoided} " +
                    "durationMs=$mediaMs producerSlackMs=${mediaMs - producerMs} " +
                    "producerSlackAt1_5xMs=${mediaMs * 2 / 3 - producerMs} " +
                    "worker=$worker actualStitcher=${if (produced.nativeEpoch != null) "RUST" else "JVM"} " +
                    "requestedRust=${nativeClear.requested} stitcherReason=${nativeClear.decision.reason} " +
                    "clock=PLAYER_REQUEST cache=$cacheEntries cachedBytes=$cachedByteCount " +
                    "cacheUniqueBytes=${memory.unique} cachePinnedBytes=${memory.pinned} cacheRetiredPinnedBytes=${memory.retiredPinned} " +
                    "cacheDeferredRetiredBytesTotal=${memory.deferredRetiredBytes} cacheDeferredRetiredEntriesTotal=${memory.deferredRetiredEntries}")
            } finally {
                if (!publishedInitialization) composed.initializationSegment.fill(0)
                composed.mediaFragments.forEach { if (it !== publishedFragment) it.fill(0) }
                if (!publishedNative) nativeFragment?.close()
            }
        } catch (error: Exception) {
            bufferProduction.reset()
            val root = (error as? java.util.concurrent.ExecutionException)?.cause as? Exception ?: error
            // A socket timeout is a network failure, not a seek cancellation:
            // SocketTimeoutException extends InterruptedIOException.
            if (root is ArchiveWarmupCancelledException) throw root
            if (Thread.currentThread().isInterrupted ||
                root is InterruptedException || root is java.util.concurrent.CancellationException ||
                (root is java.io.InterruptedIOException && root !is java.net.SocketTimeoutException)) {
                Thread.currentThread().interrupt()
                throw ArchiveWarmupCancelledException()
            }
            if (error is ProtectedArchiveBackendRequired) throw error
            val message = if (root is IllegalArgumentException || root is IllegalStateException)
                app.apexview.DiagnosticRedaction.redact(root.message.orEmpty()).take(220)
                else CaptureFailure.from(root).reason
            val retryable = root !is IllegalArgumentException && root !is IllegalStateException && CaptureFailure.from(root).retryable
            throw ArchiveVodMediaException("Archive segment $number: $message", retryable)
        } finally {
            batches.forEach { batch -> batch.forEach { it.fill(0) } }
            app.apexview.MediaMemoryAccounting.process.record(sourceOwner, app.apexview.MediaMemoryAccounting.Kind.CLEAR_SOURCE, 0L)
        }
    }

    private fun fetch(number: Int, fetchStartedNs: Long, sourceOwner: Any): List<ByteArray> {
        val lane = if (Thread.currentThread().name == "ApexviewVodWarm") prefetchFetchers else fetchers
        val retained = mutableListOf<ByteArray>()
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        val futures = mutableListOf<Future<ByteArray>>()
        val queueMs = LongArray(inputs.size)
        val readMs = LongArray(inputs.size)
        val readBytes = IntArray(inputs.size)
        try {
        checkOpen()
        if (ArchiveFetchDeadline.remainingNs(fetchStartedNs, System.nanoTime()) == 0L)
            throw java.util.concurrent.TimeoutException("Archive fetch deadline expired during admission")
        lane.purge()
        android.util.Log.i("ApexviewSingleDecoder", "ARCHIVE_VOD_FETCH_ADMISSION segment=$number " +
            "worker=${if (lane === prefetchFetchers) "PREFETCH" else "PLAYBACK"} " +
            "waitMs=${(System.nanoTime() - fetchStartedNs) / 1_000_000L} queued=${lane.queue.size} active=${lane.activeCount}")
        val discontinuities = inputs.mapIndexed { i, input ->
            val segment = input.playlist.segments[groups[number].indexes[i]]
            input.playlist.discontinuitySequence + segment.relativeDiscontinuitySequence
        }
        require(discontinuities.distinct().size == 1) {
            "Archive segment $number crosses incompatible feed discontinuities"
        }
        require(inputs.indices.all { i ->
            abs(inputs[i].playlist.segments[groups[number].indexes[i]].durationUs - groups[number].durationUs) <= 1_000L
        }) { "Archive segment $number has incompatible source durations" }
        inputs.forEachIndexed { i, input ->
            val enqueuedNs = System.nanoTime()
            futures += lane.submit<ByteArray> {
            queueMs[i] = (System.nanoTime() - enqueuedNs) / 1_000_000L
            checkOpen()
            val segment = input.playlist.segments[groups[number].indexes[i]]
            require(!segment.hasGapTag && segment.fullSegmentEncryptionKeyUri == null) { "Unsupported HLS gap or full-segment encryption" }
            val map = requireNotNull(segment.initializationSegment) { "Archive segment has no CMAF map" }
            require(map.fullSegmentEncryptionKeyUri == null) { "Encrypted HLS initialization is unsupported" }
            val expected = input.initializationMap
            if (map.url != expected.url || map.byteRangeOffset != expected.byteRangeOffset ||
                map.byteRangeLength != expected.byteRangeLength) {
                // A producer may rotate the EXT-X-MAP URI across a VOD discontinuity without
                // changing its bytes. Allow only exact equality with our validated init.
                val identity = "$i:${map.url}:${map.byteRangeOffset}:${map.byteRangeLength}"
                if (!compatibleMaps.contains(identity)) {
                    val alternate = reader.read(UriUtil.resolveToUri(input.playlist.baseUri, map.url),
                        2 * 1024 * 1024, map.byteRangeOffset, map.byteRangeLength).bytes
                    try {
                        sourceInitialization.read { inits ->
                            require(alternate.contentEquals(inits[i])) {
                                "Archive initialization changed at segment $number; a new decoder period is required"
                            }
                        }
                        checkOpen()
                        compatibleMaps.add(identity)
                    } finally { alternate.fill(0) }
                }
            }
            val readStarted = System.nanoTime()
            val response = reader.read(UriUtil.resolveToUri(input.playlist.baseUri, segment.url), 8 * 1024 * 1024,
                segment.byteRangeOffset, segment.byteRangeLength)
            readMs[i] = (System.nanoTime() - readStarted) / 1_000_000L
            readBytes[i] = response.bytes.size
            httpCoalescedBytes.addAndGet(response.coalescedCopyBytes)
            val bytes = response.bytes
            synchronized(retained) {
                if (aborted.get() || closed || Thread.currentThread().isInterrupted) {
                    bytes.fill(0); throw IOException("Archive session closed")
                }
                retained += bytes
                // Charge actual fetched arrays, including simultaneous foreground
                // and prefetch groups, until compose consumes or transfers them.
                app.apexview.MediaMemoryAccounting.process.record(sourceOwner,
                    app.apexview.MediaMemoryAccounting.Kind.CLEAR_SOURCE,
                    retained.sumOf { it.size.toLong() })
            }
            bytes
        } }
            val result = futures.mapIndexed { index, future ->
                try {
                    // With eight sources and only four bounded HTTP workers, per-future
                    // 25-second waits could accumulate serially into 50+ seconds.
                    // Every source instead shares one wall-clock group deadline.
                    val remaining = ArchiveFetchDeadline.remainingNs(fetchStartedNs, System.nanoTime())
                    if (remaining == 0L) throw java.util.concurrent.TimeoutException(
                        "Archive parallel feed capture exceeded its shared deadline")
                    future.get(remaining, TimeUnit.NANOSECONDS)
                } catch (timeout: java.util.concurrent.TimeoutException) {
                    // Never include F1 URL or credential-bearing HTTP headers.
                    android.util.Log.w("ApexviewSingleDecoder", "ARCHIVE_VOD_FETCH_TIMEOUT " +
                        "segment=$number feed=${inputs[index].label} groupTimeoutMs=${ArchiveFetchDeadline.GROUP_TIMEOUT_MS} " +
                        "worker=${if (lane === prefetchFetchers) "PREFETCH" else "PLAYBACK"}")
                    throw timeout
                }
            }
            checkOpen()
            // Future.get publishes each worker's scalar timings. No URLs or headers.
            if (app.apexview.DiagnosticLogs.recording) android.util.Log.i("ApexviewSingleDecoder",
                "ARCHIVE_VOD_FETCH_DETAIL segment=$number worker=${if (lane === prefetchFetchers) "PREFETCH" else "PLAYBACK"} " +
                    "feedQueueMs=${queueMs.joinToString(",")} feedReadMs=${readMs.joinToString(",")} " +
                    "feedBytes=${readBytes.joinToString(",")} feedOrder=SOURCE_INDEX " +
                    "groupElapsedMs=${(System.nanoTime() - fetchStartedNs) / 1_000_000L}")
            return result
        } catch (error: Exception) {
            // Future.get clears the interrupt flag before throwing InterruptedException.
            // Restore it so cancelled warm producers cannot publish stale media.
            if (error is InterruptedException) Thread.currentThread().interrupt()
            aborted.set(true)
            futures.forEach { it.cancel(true) }
            lane.purge()
            synchronized(retained) {
                retained.forEach { it.fill(0) }
                retained.clear()
                app.apexview.MediaMemoryAccounting.process.record(sourceOwner,
                    app.apexview.MediaMemoryAccounting.Kind.CLEAR_SOURCE, 0L)
            }
            throw error
        }
    }

    private fun randomAccess(bytes: List<ByteArray>): Boolean = inputs.indices.all { i ->
        val first = CmafBoxInspector.inspect(bytes[i], inputs[i].report).fragments.single()
        first.sampleTimings.first().sampleFlags?.let { (it and 0x00010000L) == 0L } == true
    }

    // Preflight and local DASH previously built the same immutable manifest.
    private var manifestBacking: HeapFragmentLease? = null
    fun manifest(): ByteArray = synchronized(cacheLock) {
        checkOpen(); manifestOwner().copyBytes()
    }
    private fun manifestLease(): ArchiveFragmentLease = synchronized(cacheLock) {
        checkOpen(); ArchiveFragmentLease.shared(manifestOwner())
    }
    private fun manifestOwner(): HeapFragmentLease {
        manifestBacking?.let { return it }
        val pto = requireNotNull(presentationOffsetTicks) { "Archive session is not prepared" }
        val timeline = groups.joinToString("") { "<S t=\"${pto + scaleUs(it.startUs, timescale)}\" d=\"${scaleUs(it.durationUs, timescale)}\"/>" }
        val urls = groups.indices.joinToString("") { "<SegmentURL media=\"s-$it.m4s\"/>" }
        val duration = java.math.BigDecimal.valueOf(durationUs, 6).stripTrailingZeros().toPlainString()
        // Preserve the verified HLS epoch as presentation metadata. Media3 intentionally leaves
        // static DASH windowStartTimeMs unset; FeedPlayer uses archiveStartTimeMs for overlays.
        val epoch = archiveStartTimeMs?.let { " availabilityStartTime=\"${java.time.Instant.ofEpochMilli(it)}\"" }.orEmpty()
        val bytes = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" minBufferTime="PT1S" mediaPresentationDuration="PT${duration}S"$epoch>
<Period id="archive-grid" start="PT0S" duration="PT${duration}S"><AdaptationSet contentType="video" mimeType="video/mp4" segmentAlignment="true">
<Representation id="archive-grid" codecs="hvc1" width="$width" height="$height" bandwidth="${inputs.sumOf { it.format?.bitrate?.coerceAtLeast(0) ?: 0 }.coerceAtLeast(1)}">
<SegmentList timescale="$timescale" presentationTimeOffset="$pto" startNumber="0"><Initialization sourceURL="init.mp4"/><SegmentTimeline>$timeline</SegmentTimeline>$urls</SegmentList>
</Representation></AdaptationSet></Period></MPD>""".toByteArray()
        return HeapFragmentLease.take(bytes).also { manifestBacking = it }
    }

    fun hlsPlaylist(): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MEDIA-SEQUENCE:0\n")
        append("#EXT-X-TARGETDURATION:${kotlin.math.ceil(groups.maxOf { it.durationUs } / 1_000_000.0).toInt()}\n")
        append("#EXT-X-MAP:URI=\"init.mp4\"\n")
        archiveStartTimeMs?.let { append("#EXT-X-PROGRAM-DATE-TIME:${java.time.Instant.ofEpochMilli(it)}\n") }
        groups.forEachIndexed { i, group -> append("#EXTINF:${java.math.BigDecimal.valueOf(group.durationUs, 6).toPlainString()},\ns-$i.m4s\n") }
        append("#EXT-X-ENDLIST\n")
    }

    fun codecString(): String {
        val bytes = initialization()
        try {
            val c = requireNotNull(CmafBoxInspector.inspect(bytes).tracks.single().hevcConfiguration)
            val compatibility = java.lang.Integer.toHexString(java.lang.Integer.reverse(c.compatibilityFlags.toInt())).uppercase()
            val space = listOf("", "A", "B", "C")[c.profileSpace]
            val constraints = (0..5).map { ((c.constraintIndicatorFlags ushr ((5 - it) * 8)) and 255).toInt() }.toMutableList()
            while (constraints.isNotEmpty() && constraints.last() == 0) constraints.removeAt(constraints.lastIndex)
            return "hvc1.$space${c.profileIdc}.$compatibility.${if (c.tierFlag) "H" else "L"}${c.levelIdc}" +
                constraints.joinToString("") { ".%02X".format(it) }
        } finally { bytes.fill(0) }
    }

    fun initialization(): ByteArray = synchronized(cacheLock) { checkOpen(); requireNotNull(initialization).copyBytes() }

    internal fun initializationLease(): ArchiveFragmentLease = synchronized(cacheLock) {
        checkOpen(); ArchiveFragmentLease.shared(requireNotNull(initialization))
    }

    override fun close() {
        if (baseReleased.compareAndSet(false, true)) releaseOwner()
    }

    private fun releaseOwner() {
        val last = synchronized(cacheLock) {
            val remaining = owners.decrementAndGet()
            if (remaining == 0) { closed = true; true } else false
        }
        if (last) shutdown()
    }

    private fun shutdown() {
        closed = true
        // Cancel before clearing the immutable session's authorized offer
        // owner. No optional network completion can publish into a closed session.
        val retirement = ArchiveRetirementSteps()
        retirement.run { sidecars.close() }
        // Wake foreground waiters even if native cleanup failed.
        retirement.run { production.close() }
        retirement.run { foregroundAdmission.close() }
        retirement.run { prefetchAdmission.close() }
        retirement.run { synchronized(warmLock) { warmTask?.cancel(true) } }
        retirement.run { warmer.shutdownNow() }
        retirement.run { fetchers.shutdownNow() }
        retirement.run { prefetchFetchers.shutdownNow() }
        retirement.run { nativeClear.close() }
        synchronized(cacheLock) {
            val initOwner = initialization
            initialization = null
            retirement.run { initOwner?.close() }
            val manifestOwner = manifestBacking
            manifestBacking = null
            retirement.run { manifestOwner?.close() }
            retirement.run { cache.clear() }
            retirement.run { sourceInitialization.close() }
        }
        retirement.run { compatibleMaps.clear() }
        nativeBudgetContext = null
        retirement.finish()
    }

    private fun checkOpen() { if (closed || Thread.currentThread().isInterrupted) throw IOException("Archive VOD session closed") }

    private class ResourceDataSource(private val session: HevcArchiveVodSession) : DataSource {
        private var uri: Uri? = null
        private var resource: ArchiveFragmentLease? = null
        private var cursor = 0
        private var end = 0
        private var delivered = 0L
        private var deliveryNumber: Int? = null
        private var deliveryEpoch: Long? = null
        override fun addTransferListener(listener: TransferListener) {}
        override fun getUri(): Uri? = uri
        override fun open(spec: DataSpec): Long {
            close(); uri = spec.uri
            val body = when (val name = spec.uri.lastPathSegment) {
                "manifest.mpd" -> session.manifestLease()
                "init.mp4" -> session.initializationLease()
                else -> {
                    val number = name?.let { Regex("s-(\\d+)\\.m4s").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                        ?: throw IOException("Unknown archive resource")
                    val epoch = session.captureLocalDeliveryEpoch()
                    val production = session.productionEpoch
                    session.localSegmentLease(number, production).also {
                        deliveryNumber = number
                        deliveryEpoch = epoch
                    }
                }
            }
            if (spec.position !in 0..body.size.toLong()) { body.close(); throw IOException("Archive range is out of bounds") }
            if (spec.length < 0L && spec.length != C.LENGTH_UNSET.toLong()) {
                body.close()
                throw IOException("Invalid archive resource range length")
            }
            resource = body
            cursor = spec.position.toInt()
            // Avoid position+length overflow on a malformed DataSpec. Do not
            // over-read or silently publish a negative length to Media3.
            val available = body.size.toLong() - spec.position
            end = (spec.position + if (spec.length == C.LENGTH_UNSET.toLong())
                available else minOf(available, spec.length)).toInt()
            return (end - cursor).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > buffer.size - length) {
                throw IndexOutOfBoundsException("Invalid archive data source read bounds")
            }
            if (length == 0) return 0
            val body = resource ?: throw IOException("Archive resource is closed")
            if (cursor >= end) return C.RESULT_END_OF_INPUT
            val count = minOf(length, end - cursor)
            body.readInto(buffer, offset, cursor, count)
            delivered += count
            cursor += count
            return count
        }
        override fun close() {
            val body = resource
            val number = deliveryNumber
            val epoch = deliveryEpoch
            if (delivered > 0) session.media3DeliveredBytes.addAndGet(delivered)
            try {
                if (body != null && number != null && epoch != null &&
                    delivered == body.size.toLong() && cursor == end && end == body.size) {
                    session.completeLocalDelivery(number, body, epoch)
                }
            } finally {
                delivered = 0
                deliveryNumber = null; deliveryEpoch = null
                resource = null; uri = null; cursor = 0; end = 0
                body?.close()
            }
        }
    }

    companion object {
        val MANIFEST_URI: Uri = Uri.parse("apexview-archive:///manifest.mpd")
        private fun scaleUs(us: Long, scale: Long): Long = scaleHevcMicrosToTicks(us, scale)

        private fun align(inputs: List<Input>, clockLabel: String): List<Group> {
            require(inputs.isNotEmpty())
            val clock = inputs.firstOrNull { it.label == clockLabel } ?: inputs.first()
            val usePdt = inputs.all { it.playlist.hasProgramDateTime }
            val offsets = inputs.map { if (usePdt) it.playlist.startTimeUs - clock.playlist.startTimeUs else 0L }
            val starts = inputs.mapIndexed { i, input -> input.playlist.segments.map { it.relativeStartTimeUs + offsets[i] } }
            return clock.playlist.segments.mapNotNull { reference ->
                val indexes = inputs.indices.map { i ->
                    val found = starts[i].binarySearch(reference.relativeStartTimeUs)
                    if (found >= 0) found else {
                        val at = -found - 1
                        listOf(at - 1, at).filter { it in starts[i].indices }.minByOrNull { abs(starts[i][it] - reference.relativeStartTimeUs) } ?: -1
                    }
                }
                if (indexes.indices.any { i -> indexes[i] < 0 || abs(starts[i][indexes[i]] - reference.relativeStartTimeUs) > 250_000 }) null else {
                    // Preserve the finite timeline even if a future chunk is unavailable or
                    // has an incompatible duration. Fetch validates each requested chunk;
                    // an unrelated later bad chunk must not block a valid replay start.
                    Group(reference.relativeStartTimeUs, reference.durationUs, indexes)
                }
            }.also { groups ->
                require(groups.isNotEmpty()) { "Archive feeds have no aligned segments" }
                require(groups.first().startUs == 0L) { "Archive feeds do not share the audio timeline's beginning" }
                groups.zipWithNext().forEach { (a, b) -> require(abs(a.startUs + a.durationUs - b.startUs) <= 1_000L) { "Archive timeline has a feed coverage gap" } }
            }
        }
    }
}
