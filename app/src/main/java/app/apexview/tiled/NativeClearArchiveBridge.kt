package app.apexview.tiled

import android.content.Context
import android.util.Log
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/**
 * One finite clear archive owns one optional native engine. The existing JVM
 * probe establishes the representation and can supply the first comparison; the first native fragment in
 * each generation must also match the JVM reference byte for byte. Thereafter
 * Rust produces the published bytes, with independent clear metadata checks.
 *
 * No native calls from the UI, no protected input or alternate DRM path.
 * State changes fence in-flight native results before archive publication.
 */
internal class NativeClearArchiveBridge(
    private val openEngine: (Context, Int, Int) -> NativeStitchingEngine = { context, input, output ->
      NativeStitchingDeviceBudget.admit {
        val h = NativeStitchingDeviceBudget.sample(context) ?: throw MemoryAdmissionException(input, output)
        val b = if (h.lowMemory) null else
            NativeStitchingEngine.planClearAtlas(h.lowRam, h.available, h.threshold, h.jvmHeadroom, input, output)
        Log.i("ApexviewSingleDecoder", "GB_CLEAR_MEMORY_ADMISSION input=$input output=$output " +
            "available=${h.available} threshold=${h.threshold} jvmHeadroom=${h.jvmHeadroom} " +
            "lowRam=${h.lowRam} lowMemory=${h.lowMemory} admitted=${b != null} " +
            "inputCapacity=${b?.inputBytes ?: 0} outputCapacity=${b?.outputBytes ?: 0} " +
            "arenaBytes=${b?.reservedBytes ?: 0} workspaceBytes=${b?.let { NativeStitchingEngine.atlasWorkspaceBytes(it.inputBytes, it.outputBytes) } ?: 0} " +
            "ceilingBytes=${b?.ceilingBytes ?: 0} " +
            "osSurplusBytes=${(h.available-h.threshold).coerceAtLeast(0)} " +
            "admissionReason=${if (h.lowMemory) "OS_LOW_MEMORY" else if (b == null) NativeStitchingEngine.clearAtlasRejection(h.lowRam,h.available,h.threshold,h.jvmHeadroom,input,output) else if (b.foregroundRecovery) "BOUNDED_FOREGROUND_RECOVERY" else "NORMAL"}")
        val admitted=b ?: throw MemoryAdmissionException(input, output)
        try {
            NativeStitchingEngine.open(admitted)
        } catch (failure: LinkageError) {
            Log.w("ApexviewSingleDecoder", "GB_CLEAR_ENGINE_OPEN rejected=LIBRARY_OR_ABI type=${failure.javaClass.simpleName}")
            throw failure
        } catch (failure: RuntimeException) {
            Log.w("ApexviewSingleDecoder", "GB_CLEAR_ENGINE_OPEN rejected=NATIVE_OPEN type=${failure.javaClass.simpleName}")
            throw failure
        }
      }
    },
) : AutoCloseable {
    private class MemoryAdmissionException(val input: Int, val output: Int) :
        IllegalStateException("Clear atlas memory admission rejected")

    private data class RetryAdmission(val input: Int, val output: Int, val nextCheckMs: Long)

    private data class State(
        val epoch: Long = 0, val requested: Boolean = false, val context: Context? = null,
        val published: Boolean = false, val reason: String? = null, val closed: Boolean = false,
        val retryAdmission: RetryAdmission? = null,
    )
    data class Produced(val output: HevcClearCmafGridComposer.Output, val nativeEpoch: Long? = null,
                        val nativeMedia: NativeFragmentLease? = null)

    private val control = Any()
    @Volatile private var state = State()
    private val work = ReentrantLock()
    // Accessed only under work, except immutable status in state.
    private var engine: NativeStitchingEngine? = null
    private var engineEpoch = -1L
    private var smallerJobs = 0
    private var verifiedEpoch = -1L
    private var verifiedWindowCount = 0
    private var verifiedInits: List<ByteArray> = emptyList()
    private var verifiedGeometry: NativeClearArchiveJob.Geometry? = null
    private var verifiedRate: Pair<Int, Int>? = null
    private var verifiedTrack: Long = 0L

    private fun clearProof() {
        verifiedEpoch = -1
        verifiedWindowCount = 0
        verifiedInits.forEach { it.fill(0) }
        verifiedInits = emptyList()
        verifiedGeometry = null
        verifiedRate = null
        verifiedTrack = 0L
    }

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
                state = old.copy(epoch = old.epoch + 1, published = false, retryAdmission = null, reason = null)
        }
    }

    /** Caller holds the archive cache lock also used by seek/retirement.
     * Fence the cache mutation itself, not just the preceding JNI read. */
    fun <T> publish(epoch: Long?, action: () -> T): T {
        if (epoch == null) return action()
        return synchronized(control) {
            checkCurrent(epoch)
            val result = action()
            state = state.copy(published = true, reason = null, retryAdmission = null)
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
                foreground: Boolean, timeoutMs: Long, reference: () -> HevcClearCmafGridComposer.Output): Produced =
        compose(request, expectedInit, foreground, timeoutMs, false, reference)

    fun compose(request: HevcClearCmafGridComposer.Request, priorInitialization: ByteArray?,
                foreground: Boolean, timeoutMs: Long, retainNativeArchiveMedia: Boolean,
                reference: () -> HevcClearCmafGridComposer.Output): Produced {
        work.lockInterruptibly()
        // Re-read after acquiring the producer lock: another request may have
        // deferred admission while this one was waiting on the same archive.
        val selected = state
        if (selected.context == null) {
            work.unlock()
            return Produced(reference())
        }
        var baseline: HevcClearCmafGridComposer.Output? = null
        var computingReference = false
        var nativeOutput: NativeClearArchiveJob.WindowOutput? = null
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
                state = state.copy(context = null, published = false, reason = failureReason, retryAdmission = null)
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
        fun deferAdmission(input: Int, output: Int, reason: String = "NATIVE_CLEAR_MEMORY_ADMISSION_DEFERRED") {
            synchronized(control) {
                checkCurrent(selected.epoch)
                state = state.copy(published = false, reason = reason,
                    retryAdmission = RetryAdmission(input, output,
                        android.os.SystemClock.elapsedRealtime() + ADMISSION_RECHECK_MS))
            }
        }
        try {
            checkCurrent(selected.epoch)
            // A denied device budget is not a syntax or byte-parity failure.
            // Poll on the existing producer only, with no extra thread, engine,
            // or reference probe until the same planner admits the rejected size.
            selected.retryAdmission?.let { retry ->
                if (android.os.SystemClock.elapsedRealtime() < retry.nextCheckMs) {
                    val result = referenceOutput()
                    checkCurrent(selected.epoch)
                    baseline = null
                    return Produced(result)
                }
                val h = NativeStitchingDeviceBudget.sample(requireNotNull(selected.context))
                val admitted = h != null && !h.lowMemory &&
                    NativeStitchingEngine.planClearAtlas(h.lowRam, h.available, h.threshold,
                        h.jvmHeadroom, retry.input, retry.output) != null
                if (!admitted) {
                    deferAdmission(retry.input, retry.output)
                    val result = referenceOutput()
                    checkCurrent(selected.epoch)
                    baseline = null
                    return Produced(result)
                }
                Log.i("ApexviewSingleDecoder", "GB_CLEAR_MEMORY_ADMISSION_RECHECK admitted=true input=${retry.input} output=${retry.output}")
            }
            val count = request.feeds.first().mediaFragments.size
            require(count in 1..NativeClearArchiveJob.MAX_WINDOW)
            val sources = request.feeds.map { feed ->
                val initReport = requireNotNull(feed.validatedInitialization)
                require(initReport.errors.isEmpty())
                val track = initReport.tracks.single()
                requireClear(track)
                val media = feed.mediaFragments
                val inspected = requireNotNull(feed.inspectedMedia)
                require(media.size == count && inspected.size == count)
                var end: Long? = null
                val fragments = media.mapIndexed { index, bytes ->
                    val report = inspected[index].reportFor(bytes, initReport)
                    require(report.errors.isEmpty())
                    val fragment = report.fragments.single()
                    requireClear(fragment)
                    require(fragment.trackId == track.trackId && fragment.timescale == track.timescale)
                    val start = requireNotNull(fragment.baseDecodeTimeTicks)
                    require(end == null || end == start)
                    end = Math.addExact(start, requireNotNull(fragment.totalDurationTicks))
                    fragment
                }
                media to fragments
            }
            // On the cold local route the mandatory JVM probe is also the exact
            // same-input differential reference for the first native fragment.
            val expectedInit = priorInitialization ?: referenceOutput().initializationSegment
            val outputReport = CmafBoxInspector.inspect(expectedInit)
            require(outputReport.errors.isEmpty())
            val track = outputReport.tracks.single()
            requireClear(track)
            val cfg = requireNotNull(track.hevcConfiguration)
            require(cfg.nalLengthSize == request.feeds.first().validatedInitialization!!.tracks.single().hevcConfiguration!!.nalLengthSize)
            val sps = cfg.sps.single()
            val pps = cfg.pps.single()
            val geometry = NativeClearArchiveJob.Geometry(sps.width, sps.height,
                requireNotNull(pps.tileColumnWidthsInCtbs), requireNotNull(pps.tileRowHeightsInCtbs), request.cells,
                loopAcrossTiles = pps.loopFilterAcrossTiles == true)
            require(geometry.columns.size == request.outputGrid.columns && geometry.rows.size == request.outputGrid.rows)
            val inits = request.feeds.map { it.initializationSegment }
            val media = sources.map { it.first }
            val inputSize = NativeClearArchiveJob.windowSize(geometry, inits, media)
            val generationChanged = engine != null && engineEpoch != selected.epoch
            if (generationChanged) {
                check(requireNotNull(engine).seek() > 0)
                engineEpoch = selected.epoch
                clearProof()
            }
            val rate = request.frameRateNumerator to request.frameRateDenominator
            val proofMatches = verifiedEpoch == selected.epoch && verifiedWindowCount == count &&
                verifiedGeometry == geometry && verifiedRate == rate && verifiedTrack == request.outputTrackId &&
                verifiedInits.size == inits.size &&
                verifiedInits.zip(inits).all { (a, b) -> a.contentEquals(b) }
            var needReference = engine == null || !proofMatches ||
                inputSize > requireNotNull(engine).budget.inputBytes
            fun referenceSize(): Int {
                val ref = referenceOutput()
                checkCurrent(selected.epoch)
                require(ref.initializationSegment.contentEquals(expectedInit))
                require(ref.mediaFragments.size == count)
                val required = 8L + expectedInit.size + ref.mediaFragments.sumOf {
                    it.size.toLong() + if (count > 1) 4L else 0L
                }
                require(required in 1..NativeClearArchiveJob.MAX_PACKET.toLong())
                return required.toInt()
            }
            fun openSized(required: Int) {
                closeOwned()
                checkCurrent(selected.epoch)
                reason = "NATIVE_CLEAR_MEMORY_OR_ENGINE_ADMISSION"
                engine = openEngine(requireNotNull(selected.context), inputSize, required)
                engineEpoch = selected.epoch
                clearProof()
            }
            if (needReference) {
                // Retire undersized native storage before asking the reference
                // composer for the exact size or sampling new headroom.
                if (engine != null && inputSize > requireNotNull(engine).budget.inputBytes) closeOwned()
                val required = referenceSize()
                val existing = engine?.budget
                val shrink = generationChanged && smallerJobs >= 8 && existing != null &&
                    inputSize <= existing.inputBytes / 2 && required <= existing.outputBytes / 2
                if (existing == null || required > existing.outputBytes || shrink) openSized(required)
            }
            reason = "NATIVE_CLEAR_WORKER_REJECTED"
            val encoded = NativeClearArchiveJob.encodeParts(request.outputTrackId, request.firstOutputSequenceNumber,
                request.frameRateNumerator, request.frameRateDenominator, geometry, inits, media)
            val started = System.nanoTime()
            var active = requireNotNull(engine)
            val out = try {
                NativeClearArchiveJob.executeWindow(active, encoded, count, foreground, timeoutMs, selected.context.takeIf { retainNativeArchiveMedia }) { checkCurrent(selected.epoch) }
            } catch (_: NativeClearArchiveJob.OutputCapacityException) {
                // Only an explicitly undersized output lease can trigger a
                // retry. Other invalid syntax/metadata stays a hard failure.
                val priorCapacity = active.budget.outputBytes
                closeOwned()
                checkCurrent(selected.epoch)
                needReference = true
                val required = referenceSize()
                require(required > priorCapacity) { "Native output capacity disagrees with reference" }
                openSized(required)
                active = requireNotNull(engine)
                val remaining = timeoutMs - (System.nanoTime() - started) / 1_000_000L
                reason = "NATIVE_CLEAR_WORKER_REJECTED"
                check(remaining > 0) { "Native clear resize deadline" }
                NativeClearArchiveJob.executeWindow(active, encoded, count, foreground, remaining, selected.context.takeIf { retainNativeArchiveMedia }) { checkCurrent(selected.epoch) }
            }
            nativeOutput = out
            val validationStarted = System.nanoTime()
            reason = "NATIVE_CLEAR_PARITY_MISMATCH"
            require(out.initialization.contentEquals(expectedInit))
            baseline?.let {
                require(out.initialization.contentEquals(it.initializationSegment) &&
                    out.matches(it.mediaFragments))
            }
            // Independently verify every output sample's timing and flags.
            // Sizes change by design when tiles are composed.
            reason = "NATIVE_CLEAR_OUTPUT_VALIDATION"
            require(out.sizes.size == count)
            var sampleCount = 0
            out.sizes.indices.forEach { index ->
                val report = out.inspect(index, outputReport)
                require(report.errors.isEmpty())
                val fragment = report.fragments.single()
                requireClear(fragment)
                val first = sources.first().second[index]
                require(fragment.trackId == request.outputTrackId && fragment.timescale == first.timescale &&
                    fragment.baseDecodeTimeTicks == first.baseDecodeTimeTicks &&
                    fragment.sampleCount == first.sampleCount && fragment.totalDurationTicks == first.totalDurationTicks &&
                    fragment.sampleTimings.zip(first.sampleTimings).all { (a, b) ->
                        a.decodeTimeTicks == b.decodeTimeTicks && a.presentationTimeTicks == b.presentationTimeTicks &&
                            a.durationTicks == b.durationTicks && a.compositionOffsetTicks == b.compositionOffsetTicks &&
                            a.sampleFlags == b.sampleFlags && (a.sampleSizeBytes ?: 0) > 0
                    })
                sampleCount = Math.addExact(sampleCount, fragment.sampleCount)
            }
            checkCurrent(selected.epoch)
            val actualOutput = 8L + out.initialization.size + out.sizes.sumOf { it.toLong() + if (count > 1) 4L else 0L }
            smallerJobs = if (encoded.size <= active.budget.inputBytes / 2 && actualOutput <= active.budget.outputBytes / 2)
                (smallerJobs + 1).coerceAtMost(8) else 0
            val stats = active.copyStats()
            Log.i("ApexviewSingleDecoder", "GB_CLEAR_NATIVE_FRAGMENT generation=${selected.epoch} " +
                "referenceCompared=${baseline != null} sequence=${request.firstOutputSequenceNumber} deadlineMs=$timeoutMs wallMs=${(System.nanoTime() - started) / 1_000_000L} " +
                "inputBytes=${encoded.size} outputBytes=${out.sizes.sumOf { it.toLong() }} fragments=$count javaInputBytes=${stats.javaInputBytes} " +
                "nativeInputBytes=${stats.nativeInputBytes} nativeOutputBytes=${stats.nativeOutputBytes} " +
                "storage=${if (out.nativeMedia != null) "NATIVE_FRAGMENT" else "HEAP"} javaOutputBytes=${stats.javaOutputBytes} rejectedSubmits=${stats.rejectedSubmits} compactAtlas=${active.budget.compactAtlas} " +
                "arenaReservedBytes=${active.budget.reservedBytes} arenaPayloadBytes=${active.allocatedPayloadBytes()} " +
                "submitUs=${out.submitNs / 1000L} waitUs=${out.waitNs / 1000L} readUs=${out.readNs / 1000L} " +
                "validateUs=${(System.nanoTime() - validationStarted) / 1000L}")
            if (needReference) {
                clearProof()
                verifiedInits = inits.map { it.copyOf() }
                verifiedGeometry = geometry
                verifiedRate = rate
                verifiedTrack = request.outputTrackId
                verifiedEpoch = selected.epoch
                verifiedWindowCount = count
            }
            val result = HevcClearCmafGridComposer.Output(out.initialization, out.media,
                sps.width, sps.height, sps.displayWidth, sps.displayHeight, sampleCount, count, emptyList())
            nativeOutput = null // transfer only exact, independently verified clear output
            return Produced(result, selected.epoch, out.nativeMedia)
        } catch (cancelled: InterruptedException) {
            clearProof()
            throw cancelled
        } catch (cancelled: InterruptedIOException) {
            clearProof()
            throw cancelled
        } catch (error: NativeClearArchiveJob.BackpressureException) {
            if (computingReference) throw error
            deferAdmission(error.input, error.output, "NATIVE_CLEAR_WORKER_BACKPRESSURE_DEFERRED")
            try { closeOwned() }
            catch (_: RuntimeException) { Log.w("ApexviewSingleDecoder", "GB_CLEAR_NATIVE_CLOSE_FAILED") }
            catch (_: LinkageError) { Log.w("ApexviewSingleDecoder", "GB_CLEAR_NATIVE_CLOSE_FAILED") }
            logDecision()
            val result = referenceOutput()
            checkCurrent(selected.epoch)
            baseline = null
            return Produced(result)
        } catch (error: MemoryAdmissionException) {
            if (computingReference) throw error
            deferAdmission(error.input, error.output)
            try { closeOwned() }
            catch (_: RuntimeException) { Log.w("ApexviewSingleDecoder", "GB_CLEAR_NATIVE_CLOSE_FAILED") }
            catch (_: LinkageError) { Log.w("ApexviewSingleDecoder", "GB_CLEAR_NATIVE_CLOSE_FAILED") }
            logDecision()
            val result = referenceOutput()
            checkCurrent(selected.epoch)
            baseline = null
            return Produced(result)
        } catch (error: RuntimeException) {
            if (computingReference) throw error
            return fallback(reason)
        } catch (error: LinkageError) {
            if (computingReference) throw error
            return fallback("NATIVE_CLEAR_LIBRARY_UNAVAILABLE")
        } finally {
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
        require(cfg.nalLengthSize in 1..4 && cfg.parameterSetErrors.isEmpty() &&
            cfg.vps.size == 1 && cfg.sps.size == 1 && cfg.pps.size == 1)
    }

    private fun requireClear(fragment: CmafBoxInspector.FragmentMetadata) {
        require(!fragment.sampleEncryptionBoxPresent && !fragment.sampleAuxiliarySizesPresent &&
            !fragment.sampleAuxiliaryOffsetsPresent && !fragment.sampleGroupDescriptionPresent &&
            !fragment.sampleToGroupPresent && !fragment.sampleGroupEncryptionPresent &&
            !fragment.durationIsEmpty && fragment.timingComplete && fragment.sampleCount > 0 &&
            fragment.sampleTimings.size == fragment.sampleCount &&
            fragment.sampleTimings.all { timing ->
                timing.sampleFlags?.let { (it and 0xf0000000L) == 0L } == true
            })
    }

    private fun closeOwned() {
        val old = engine
        engine = null
        engineEpoch = -1
        smallerJobs = 0
        clearProof()
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
                smallerJobs = 0
                clearProof()
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
        private const val ADMISSION_RECHECK_MS = 10_000L
        private val retirement by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "PitwallRustRetire").apply { isDaemon = true } }
        }
    }
}
