package app.apexview.scene

import java.util.concurrent.atomic.AtomicLong

/** App-owned EGL objects, not driver allocations or SurfaceFlinger buffers.
 * Store scalar evidence only; never retain a View, Surface, texture or player.
 */
internal class SceneGraphicsOwners(private val onChange: (Long, Owner?, Owner?) -> Unit = { _, _, _ -> }) {
    data class Owner(
        val backend: String, val surfaceEpoch: Long, val readinessEpoch: Long,
        val contexts: Int, val windows: Int, val decoderTextures: Int,
        val atlasWidth: Int, val atlasHeight: Int, val outputWidth: Int, val outputHeight: Int,
        val encoderInputs: Int = 0, val captureTextures: Int = 0,
        val captureQueues: Int = 0, val pbuffers: Int = 0,
        val captureWidth: Int = 0, val captureHeight: Int = 0,
        val localBufferWidth: Int = 0, val localBufferHeight: Int = 0,
        val virtualDisplays: Int = 0, val imageReaders: Int = 0,
    ) {
        val capturePixels: Long get() = captureWidth.toLong() * captureHeight
        val active: Boolean get() = contexts != 0 || windows != 0 || decoderTextures != 0 ||
            encoderInputs != 0 || captureTextures != 0 || captureQueues != 0 || pbuffers != 0 || virtualDisplays != 0 || imageReaders != 0
        val atlasPixels: Long get() = atlasWidth.toLong() * atlasHeight
        val outputPixels: Long get() = outputWidth.toLong() * outputHeight
        val observedLocalBufferPixels: Long get() = localBufferWidth.toLong() * localBufferHeight
    }
    private val next = AtomicLong()
    private val owners = linkedMapOf<Long, Owner>()
    private val pending = mutableSetOf<Long>()
    private var unverifiedRetirements = 0L
    private var failedRetirementSteps = 0L
    private var peakOwners = 0
    private var peakContexts = 0
    private var peakWindows = 0
    private var peakDecoderTextures = 0
    fun newId(): Long = next.incrementAndGet()
    @Synchronized fun record(id: Long, owner: Owner) {
        require(owner.contexts in 0..2 && owner.windows >= 0 && owner.decoderTextures in 0..1)
        // Two contexts are the render + surfaceless latch pair; neither a
        // second decoder texture nor a second decoder is implied.
        require(owner.contexts < 2 || owner.decoderTextures == 1)
        require(owner.atlasWidth >= 0 && owner.atlasHeight >= 0 && owner.outputWidth >= 0 && owner.outputHeight >= 0)
        require(owner.encoderInputs in 0..1 && owner.captureTextures in 0..1 && owner.captureQueues in 0..1 &&
            owner.pbuffers in 0..1 && owner.captureWidth >= 0 && owner.captureHeight >= 0)
        require(owner.localBufferWidth >= 0 && owner.localBufferHeight >= 0)
        require(owner.virtualDisplays in 0..1 && owner.imageReaders in 0..1)
        val previous = owners[id]
        val current = owner.takeIf { it.active }
        if (current == null) owners.remove(id)
        else owners[id] = current
        if (previous != current) onChange(id, previous, current)
        peakOwners = maxOf(peakOwners, owners.size)
        peakContexts = maxOf(peakContexts, owners.values.sumOf { it.contexts })
        peakWindows = maxOf(peakWindows, owners.values.sumOf { it.windows })
        peakDecoderTextures = maxOf(peakDecoderTextures, owners.values.sumOf { it.decoderTextures })
    }
    @Synchronized fun beginRetirement(id: Long) {
        if (id in owners) pending.add(id)
    }
    @Synchronized fun retirementFailure(failedSteps: Int = 1) {
        require(failedSteps > 0)
        unverifiedRetirements++
        failedRetirementSteps += failedSteps
    }
    @Synchronized fun remove(id: Long, failedSteps: Int = 0) {
        require(failedSteps >= 0)
        // Cleared application handles are not proof the driver released them.
        // Keep bounded, process-lifetime scalar failure evidence even if there
        // was no fully initialized owner to register.
        if (failedSteps > 0) retirementFailure(failedSteps)
        pending.remove(id)
        owners.remove(id)?.let { onChange(id, it, null) }
    }
    @Synchronized fun snapshot(): Map<Long, Owner> = LinkedHashMap(owners)
    @Synchronized private fun diagnosticSnapshot() = LinkedHashMap(owners) to
        " scenePendingRetirements=${pending.size} sceneUnverifiedRetirements=$unverifiedRetirements sceneFailedRetirementSteps=$failedRetirementSteps scenePeakOwners=$peakOwners scenePeakEglContexts=$peakContexts scenePeakEglWindows=$peakWindows scenePeakDecoderTextures=$peakDecoderTextures scenePeakScope=PROCESS_LIFETIME"
    fun fields(): String {
        val (live, peaks) = diagnosticSnapshot()
        return " sceneGraphicsOwners=${live.size} sceneEglContexts=${live.values.sumOf { it.contexts }}" +
            " sceneEglWindows=${live.values.sumOf { it.windows }}" +
            " sceneDecoderTextures=${live.values.sumOf { it.decoderTextures }}" +
            " sceneAtlasPixels=${live.values.sumOf { it.atlasPixels }}" +
            " sceneOutputPixels=${live.values.sumOf { it.outputPixels }}" +
            " sceneObservedLocalBufferPixels=${live.values.sumOf { it.observedLocalBufferPixels }}" +
            " sceneObservedLocalBufferOwners=${live.values.count { it.observedLocalBufferPixels > 0 }}" +
            " sceneEncoderInputs=${live.values.sumOf { it.encoderInputs }}" +
            " sceneCaptureTextures=${live.values.sumOf { it.captureTextures }}" +
            " sceneCaptureQueues=${live.values.sumOf { it.captureQueues }}" +
            " sceneEglPbuffers=${live.values.sumOf { it.pbuffers }}" +
            " sceneVirtualDisplays=${live.values.sumOf { it.virtualDisplays }} sceneImageReaders=${live.values.sumOf { it.imageReaders }}" +
            " sceneCapturePixels=${live.values.sumOf { it.capturePixels }}" +
            " sceneGraphicsDetail=" + live.entries.joinToString(",") { (id, owner) ->
                "${id}:${owner.backend}:${owner.surfaceEpoch}:${owner.readinessEpoch}:" +
                    "${owner.contexts}:${owner.windows}:${owner.decoderTextures}:" +
                    "${owner.atlasWidth}x${owner.atlasHeight}:${owner.outputWidth}x${owner.outputHeight}"
            }.ifEmpty { "NONE" } +
            " sceneCaptureDetail=" + live.entries.filter { (_, owner) ->
                owner.encoderInputs != 0 || owner.captureTextures != 0 || owner.captureQueues != 0 || owner.pbuffers != 0
            }.joinToString(",") { (id, owner) ->
                "${id}:${owner.backend}:${owner.encoderInputs}:${owner.captureTextures}:" +
                    "${owner.captureQueues}:${owner.pbuffers}:${owner.captureWidth}x${owner.captureHeight}"
            }.ifEmpty { "NONE" } + " sceneGraphicsBytes=NOT_MEASURED" + peaks
    }
    companion object {
        val process = SceneGraphicsOwners { id, previous, current ->
            if (app.apexview.DiagnosticLogs.recording) {
                val owner = current ?: previous!!
                android.util.Log.i("PitwallGraphics", "GRAPHICS_OWNER_EVENT uptimeMs=${android.os.SystemClock.elapsedRealtime()} " +
                    "owner=$id backend=${owner.backend} surfaceEpoch=${owner.surfaceEpoch} readinessEpoch=${owner.readinessEpoch} " +
                    "event=${if (current == null) "RELEASE" else if (previous == null) "CREATE" else "CHANGE"} " +
                    "contexts=${current?.contexts ?: 0} windows=${current?.windows ?: 0} decoderTextures=${current?.decoderTextures ?: 0} " +
                    "atlas=${owner.atlasWidth}x${owner.atlasHeight} output=${owner.outputWidth}x${owner.outputHeight} " +
                    "encoderInputs=${current?.encoderInputs ?: 0} captureTextures=${current?.captureTextures ?: 0} " +
                    "captureQueues=${current?.captureQueues ?: 0} pbuffers=${current?.pbuffers ?: 0} vendorBytes=NOT_MEASURED")
            }
        }
    }
}
