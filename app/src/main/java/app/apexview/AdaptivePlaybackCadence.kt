package app.apexview

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.os.SystemClock

/**
 * Actual *output* frames/s, independent of media timeline rate.
 * The existing accelerated-frame thinning remains the safe fallback.
 * A single composed codec may show its full 75fps at 1.5x only when the
 * actual display can present it and thermal headroom exists. Multiple
 * decoders do not opt into this extra output demand.
 */
internal object AdaptivePlaybackCadence {
    fun outputFps(source: Double, speed: Float, displayHz: Float,
                  thermalStatus: Int, composedSingle: Boolean,
                  pipelineHealthy: Boolean = true): Float {
        val media = source * speed
        if (!source.isFinite() || source <= 0.0 ||
            !speed.isFinite() || speed <= 0f || !media.isFinite() ||
            media !in 1.0..240.0) return 0f
        if (speed <= 1.15f) return media.toFloat()
        val fallback = source.toFloat()
        // Unknown/unsupported modes and thermal conditions must NEVER promote
        // more decoder frames just because a device advertises 120Hz capability.
        val canDisplayAll = displayHz.isFinite() && displayHz > 0f &&
            displayHz + 0.5f >= media && media <= 120.0
        return if (composedSingle && pipelineHealthy && canDisplayAll && thermalStatus in 0..1)
            media.toFloat() else fallback
    }
}

/**
 * Decoder-thread observer, with max 1 query per second. Does not install
 * listeners, retain a View, alter display modes or own another codec.
 */
internal class AdaptivePlaybackCadenceObserver(
    context: Context,
    private val correlation: app.apexview.scene.SceneCodecTimestampCorrelation? = null,
) {
    private val displayManager =
        context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val power =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    @Volatile var composedSingle: Boolean = false
    @Volatile var decoderLowLatencyTrial: Boolean = false
    // The scene provides its ACTUAL display ID. DEFAULT_DISPLAY can be wrong
    // for external monitors / TV handoffs. -1 means no attached output and
    // disables the unverified high-rate path, without hiding a playback error.
    @Volatile var activeDisplayId: Int = -1

    private var lastReadMs = Long.MIN_VALUE
    private var lastReadDisplayId = Int.MIN_VALUE
    private var displayHz = 0f
    private var thermalStatus = Int.MAX_VALUE

    fun outputFps(sourceFps: Double, speed: Float): Float {
        val now = SystemClock.elapsedRealtime()
        val requestedDisplay = activeDisplayId
        if (lastReadMs == Long.MIN_VALUE || now < lastReadMs ||
            now - lastReadMs >= 1000L || requestedDisplay != lastReadDisplayId) {
            displayHz = if (requestedDisplay >= 0)
                displayManager.getDisplay(requestedDisplay)?.refreshRate ?: 0f else 0f
            thermalStatus = power.currentThermalStatus
            lastReadMs = now
            lastReadDisplayId = requestedDisplay
        }
        return AdaptivePlaybackCadence.outputFps(
            sourceFps, speed, displayHz, VideoThermalBudget.effectiveStatus(thermalStatus), composedSingle,
            pipelineHealthy = correlation?.allowsAcceleratedOutput(System.nanoTime()) == true)
    }
}
