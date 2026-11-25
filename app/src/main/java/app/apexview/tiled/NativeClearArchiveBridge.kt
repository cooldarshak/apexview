package app.apexview.tiled

import android.content.Context
import android.util.Log
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/**
 * One finite clear archive owns one optional native engine. The existing JVM
 * probe establishes the representation; the first real native fragment in
 * each generation must also match the JVM reference byte for byte. Thereafter
 * Rust produces the published bytes, with independent clear metadata checks.
 *
 * No native calls from the UI, no protected input or alternate DRM path.
 * State changes fence in-flight native results before archive publication.
 */
internal class NativeClearArchiveBridge(
    private val openEngine: (Context, Int, Int) -> NativeStitchingEngine = { context, input, output ->
        val h = requireNotNull(NativeStitchingDeviceBudget.sample(context))
        require(!h.lowMemory)
        val b = NativeStitchingEngine.planClearAtlas(h.lowRam, h.available, h.threshold, h.jvmHeadroom, input, output)
            ?: throw IllegalStateException("Clear atlas memory admission rejected")
        NativeStitchingEngine.open(b)
    },
) : AutoCloseable {
    private data class State(
        val epoch: Long = 0, val requested: Boolean = false, val context: Context? = null,
        val published: Boolean = false, val reason: String? = null, val closed: Boolean = false,
    )
    data class Produced(val output: HevcClearCmafGridComposer.Output, val nativeEpoch: Long? = null)

    private val control = Any()
    @Volatile private var state = State()
    private val work = ReentrantLock()
    // Accessed only under work, except immutable status in state.
    private var engine: NativeStitchingEngine? = null
    private var engineEpoch = -1L
    private var verifiedEpoch = -1L

    val requested: Boolean get() = state.requested
    val candidate: Boolean get() = state.context != null
    val decision: NativeStitchingSelection.Decision get() = state.let {
        NativeStitchingSelection.resolve(it.requested, NativeStitchingSelection.Path.VERIFIED_CLEAR,
            it.published && it.context != null, it.reason)
    }

    fun enable(context: Context) {
        synchronized(control) {
            val old = state
            if (old.closed || old.context != null) return
            state = State(old.epoch + 1, true, context.applicationContext)
        }
        logDecision()
    }

    fun disable(reason: String, requested: Boolean) {
        synchronized(control) {
            val old = state
            if (old.closed) return
            state = State(old.epoch + 1, requested, reason = reason)
        }
        retireDisabled()
        logDecision()
    }

    fun invalidateForSeek() {
        synchronized(control) {
            val old = state
            if (old.context != null && !old.closed)
                state = old.copy(epoch = old.epoch + 1, published = false)
        }
    }

    /** Caller holds the archive cache lock also used by seek/retirement.
     * Fence the cache mutation itself, not just the preceding JNI read. */
    fun <T> publish(epoch: Long?, action: () -> T): T {
        if (epoch == null) return action()
        return synchronized(control) {
            checkCurrent(epoch)
            val result = action()
            state = state.copy(published = true)
            result
        }
    }

    private fun checkCurrent(epoch: Long) {
        val current = state
        if (current.closed || current.context == null || current.epoch != epoch ||
            Thread.currentThread().isInterrupted)
            throw InterruptedIOException("Native clear archive generation cancelled")
    }

    private fun logDecision() {
        Log.i("ApexviewSingleDecoder", decision.diagnostic(NativeStitchingSelection.Path.VERIFIED_CLEAR))
    }

    fun compose(request: HevcClearCmafGridComposer.Request, expectedInit: ByteArray,
                foreground: Boolean, timeoutMs: Long, reference: () -> HevcClearCmafGridComposer.Output): Produced {
        val selected = state
        if (selected.context == null) return Produced(reference())
        work.lockInterruptibly()
        var baseline: HevcClearCmafGridComposer.Output? = null
        var computingReference = false
        var nativeOutput: NativeClearArchiveJob.Output? = null
        var packet: ByteArray? = null
        var reason = "NATIVE_CLEAR_VALIDATION_REJECTED"
        fun referenceOutput(): HevcClearCmafGridComposer.Output {
            baseline?.let { return it }
            computingReference = true
            val out = reference()
            computingReference = false
            baseline = out
            return out
        }
        fun fallback(failureReason: String): Produced {
            checkCurrent(selected.epoch)
            synchronized(control) {
                checkCurrent(selected.epoch)
                state = state.copy(context = null, published = false, reason = failureReason)
            }
            logDecision()
            // Retire and join the failed native owner before computing JVM
            // fallback. Do not retain its arena or run both stitchers live.
            try { closeOwned() }
            catch (_: RuntimeException) { Log.w("ApexviewSingleDecoder", "GB_CLEAR_NATIVE_CLOSE_FAILED") }
            catch (_: LinkageError) { Log.w("ApexviewSingleDecoder", "GB_CLEAR_NATIVE_CLOSE_FAILED") }
            // The existing authorized JVM composer independently enforces all
            // compatibility checks. Its exceptions are never swallowed.
            val result = referenceOutput()
            baseline = null
            return Produced(result)
        }
        try {
            checkCurrent(selected.epoch)
            val sources = request.feeds.map { feed ->
                val initReport = requireNotNull(feed.validatedInitialization)
                val track = initReport.tracks.single()
                requireClear(track)
                val media = feed.mediaFragments.single()
                val fragment = requireNotNull(feed.inspectedMedia).single().reportFor(media, initReport).fragments.single()
                requireClear(fragment)
                require(fragment.trackId == track.trackId && fragment.timescale == track.timescale)
                media to fragment
            }
            val outputReport = CmafBoxInspector.inspect(expectedInit)
            require(outputReport.errors.isEmpty())
            val track = outputReport.tracks.single()
            requireClear(track)
            val cfg = requireNotNull(track.hevcConfiguration)
            val sps = cfg.sps.single()
            val pps = cfg.pps.single()
            val geometry = NativeClearArchiveJob.Geometry(sps.width, sps.height,
                requireNotNull(pps.tileColumnWidthsInCtbs), requireNotNull(pps.tileRowHeightsInCtbs), request.cells)
            require(geometry.columns.size == request.outputGrid.columns && geometry.rows.size == request.outputGrid.rows)
            val inits = request.feeds.map { it.initializationSegment }
            val media = sources.map { it.first }
            val inputSize = NativeClearArchiveJob.size(geometry, inits, media)
            if (engine != null && engineEpoch != selected.epoch) {
                check(requireNotNull(engine).seek() > 0)
                engineEpoch = selected.epoch
                verifiedEpoch = -1
            }
            val needReference = engine == null || verifiedEpoch != selected.epoch ||
                inputSize > requireNotNull(engine).budget.inputBytes
            if (needReference) {
                val ref = referenceOutput()
                checkCurrent(selected.epoch)
                require(ref.initializationSegment.contentEquals(expectedInit))
                val required = 8L + expectedInit.size + ref.mediaFragments.single().size
                require(required in 1..NativeClearArchiveJob.MAX_PACKET.toLong())
                if (engine == null || inputSize > requireNotNull(engine).budget.inputBytes ||
                    required > requireNotNull(engine).budget.outputBytes) {
                    closeOwned()
                    reason = "NATIVE_CLEAR_MEMORY_OR_ENGINE_ADMISSION"
                    // Exact observed sizes, no silent quality reduction or
                    // overlapping old/new native arenas.
                    engine = openEngine(requireNotNull(selected.context), inputSize, required.toInt())
                    engineEpoch = selected.epoch
                    verifiedEpoch = -1
                }
            }
            reason = "NATIVE_CLEAR_WORKER_REJECTED"
            val active = requireNotNull(engine)
            val encoded = NativeClearArchiveJob.encode(request.outputTrackId, request.firstOutputSequenceNumber,
                request.frameRateNumerator, request.frameRateDenominator, geometry, inits, media).bytes
            packet = encoded
            val started = System.nanoTime()
            val out = NativeClearArchiveJob.execute(active, encoded, foreground, timeoutMs) { checkCurrent(selected.epoch) }
            nativeOutput = out
            reason = "NATIVE_CLEAR_PARITY_MISMATCH"
            require(out.initialization.contentEquals(expectedInit))
            baseline?.let {
                require(out.initialization.contentEquals(it.initializationSegment) &&
                    out.media.contentEquals(it.mediaFragments.single()))
            }
            // Independently verify every output sample's timing and flags.
            // Sizes change by design when tiles are composed.
            reason = "NATIVE_CLEAR_OUTPUT_VALIDATION"
            val report = CmafBoxInspector.inspect(out.media, outputReport)
            require(report.errors.isEmpty())
            val fragment = report.fragments.single()
            requireClear(fragment)
            val first = sources.first().second
            require(fragment.trackId == request.outputTrackId && fragment.timescale == first.timescale &&
                fragment.baseDecodeTimeTicks == first.baseDecodeTimeTicks &&
                fragment.sampleCount == first.sampleCount && fragment.totalDurationTicks == first.totalDurationTicks &&
                fragment.sampleTimings.zip(first.sampleTimings).all { (a, b) ->
                    a.decodeTimeTicks == b.decodeTimeTicks && a.presentationTimeTicks == b.presentationTimeTicks &&
                        a.durationTicks == b.durationTicks && a.compositionOffsetTicks == b.compositionOffsetTicks &&
                        a.sampleFlags == b.sampleFlags && (a.sampleSizeBytes ?: 0) > 0
                })
            checkCurrent(selected.epoch)
            val stats = active.copyStats()
            Log.i("ApexviewSingleDecoder", "GB_CLEAR_NATIVE_FRAGMENT generation=${selected.epoch} " +
                "referenceCompared=${baseline != null} wallMs=${(System.nanoTime() - started) / 1_000_000L} " +
                "inputBytes=${encoded.size} outputBytes=${out.media.size} javaInputBytes=${stats.javaInputBytes} " +
                "nativeInputBytes=${stats.nativeInputBytes} nativeOutputBytes=${stats.nativeOutputBytes} " +
                "javaOutputBytes=${stats.javaOutputBytes} rejectedSubmits=${stats.rejectedSubmits}")
            verifiedEpoch = selected.epoch
            val result = HevcClearCmafGridComposer.Output(out.initialization, listOf(out.media),
                sps.width, sps.height, sps.displayWidth, sps.displayHeight, fragment.sampleCount, 1, emptyList())
            nativeOutput = null // transfer only exact, independently verified clear output
            return Produced(result, selected.epoch)
        } catch (cancelled: InterruptedException) {
            verifiedEpoch = -1
            throw cancelled
        } catch (cancelled: InterruptedIOException) {
            verifiedEpoch = -1
            throw cancelled
        } catch (error: RuntimeException) {
            if (computingReference) throw error
            return fallback(reason)
        } catch (error: LinkageError) {
            if (computingReference) throw error
            return fallback("NATIVE_CLEAR_LIBRARY_UNAVAILABLE")
        } finally {
            packet?.fill(0)
            nativeOutput?.wipe()
            baseline?.let { it.initializationSegment.fill(0); it.mediaFragments.forEach { bytes -> bytes.fill(0) } }
            work.unlock()
            retireDisabled()
        }
    }

    private fun requireClear(track: CmafBoxInspector.TrackMetadata) {
        require(track.handler == "vide" && track.sampleEntry in setOf("hvc1", "hev1") &&
            !track.encrypted && track.protectionScheme == null && track.trackEncryption == null &&
            !track.sampleEncryptionBoxPresent && !track.sampleAuxiliarySizesPresent &&
            !track.sampleAuxiliaryOffsetsPresent && !track.sampleGroupDescriptionPresent && !track.sampleToGroupPresent &&
            track.sampleDescriptionCount == 1)
        val cfg = requireNotNull(track.hevcConfiguration)
        require(cfg.nalLengthSize == 4 && cfg.parameterSetErrors.isEmpty() &&
            cfg.vps.size == 1 && cfg.sps.size == 1 && cfg.pps.size == 1)
    }

    private fun requireClear(fragment: CmafBoxInspector.FragmentMetadata) {
        require(!fragment.sampleEncryptionBoxPresent && !fragment.sampleAuxiliarySizesPresent &&
            !fragment.sampleAuxiliaryOffsetsPresent && !fragment.sampleGroupDescriptionPresent &&
            !fragment.sampleToGroupPresent && !fragment.sampleGroupEncryptionPresent &&
            fragment.timingComplete && fragment.sampleCount > 0 &&
            fragment.sampleTimings.size == fragment.sampleCount)
    }

    private fun closeOwned() {
        val old = engine
        engine = null
        engineEpoch = -1
        verifiedEpoch = -1
        if (old != null) old.close() // worker only; native joins before re-admission
    }

    /** UI retirement never waits for a producer or a native worker join. The
     * existing producer retries retirement after releasing its lock. Only one
     * actual engine can exist process-wide, so detached closes are bounded. */
    private fun retireDisabled() {
        if (state.context != null || !work.tryLock()) return
        var old: NativeStitchingEngine? = null
        try {
            if (state.context == null) {
                old = engine
                engine = null
                engineEpoch = -1
                verifiedEpoch = -1
            }
        } finally { work.unlock() }
        old?.let { retiring ->
            retirement.execute {
                try { retiring.close() }
                catch (_: RuntimeException) { Log.w("ApexviewSingleDecoder", "GB_CLEAR_NATIVE_CLOSE_FAILED") }
                catch (_: LinkageError) { Log.w("ApexviewSingleDecoder", "GB_CLEAR_NATIVE_CLOSE_FAILED") }
            }
        }
    }

    override fun close() {
        synchronized(control) {
            val old = state
            state = State(old.epoch + 1, old.requested, reason = "ARCHIVE_CLOSED", closed = true)
        }
        retireDisabled()
    }

    companion object {
        private val retirement by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "PitwallRustRetire").apply { isDaemon = true } }
        }
    }
}
