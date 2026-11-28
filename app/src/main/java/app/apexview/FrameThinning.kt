package app.apexview

import android.content.Context
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import app.apexview.tiled.HevcBitstream
import app.apexview.tiled.HevcParameterSets

/**
 * Picks which frames a sped-up feed shows, evenly, so it hands the screen no more frames a
 * second than at normal speed. Tiles draw their pictures through the app (so they can overlap),
 * and the app redraws only so many times a second: three feeds at 1.5× outran it, and the
 * player then threw frames away wherever it fell behind, which looked choppy. Skipping the
 * same share of frames every time keeps the motion steady.
 */
internal class FrameThinner {
    private var lastPtsUs = C.TIME_UNSET
    private var nextDueUs = C.TIME_UNSET
    /** The stream's own time between frames, learnt from the frames themselves. */
    private var intervalUs = 0L
    private var previousCapFps = Float.NaN

    fun estimatedSourceFps(): Double = if (intervalUs > 0L) 1_000_000.0 / intervalUs else 0.0

    fun reset() {
        lastPtsUs = C.TIME_UNSET
        nextDueUs = C.TIME_UNSET
        intervalUs = 0L
        previousCapFps = Float.NaN
    }

    /**
     * outputCapFps is the presentation capability, NOT the media timebase.
     * The default remains source-fps bounded for legacy callers and multiple
     * decoder mode. Composed SINGLE may use 75fps at 1.5x on a cool 120Hz phone.
     */
    fun keep(ptsUs: Long, speed: Float, outputCapFps: Float = Float.NaN): Boolean {
        val last = lastPtsUs
        lastPtsUs = ptsUs
        if (last != C.TIME_UNSET && ptsUs < last) {
            // Went back (a seek, or a new stream): start over.
            nextDueUs = C.TIME_UNSET
        } else if (last != C.TIME_UNSET) {
            val gap = ptsUs - last
            if (gap in 1..MAX_INTERVAL_US && (intervalUs == 0L || gap < intervalUs)) intervalUs = gap
        }
        // At an adaptive-mode/thermal change, restart the skip schedule at the
        // next real picture. Never carry a stale 50fps skip deadline into 75fps.
        val fallback = if (intervalUs > 0L) 1_000_000f / intervalUs else 0f
        val cap = outputCapFps.takeIf { it.isFinite() && it > 0f } ?: fallback
        if (cap > 0f && (!previousCapFps.isFinite() ||
                kotlin.math.abs(cap - previousCapFps) >= 0.5f)) {
            nextDueUs = C.TIME_UNSET
            previousCapFps = cap
        }
        val step = if (cap > 0f)
            ((1_000_000.0 * speed / cap).toLong()).coerceAtLeast(intervalUs)
        else (intervalUs * speed).toLong()
        val due = nextDueUs
        if (speed <= THIN_ABOVE || intervalUs == 0L || due == C.TIME_UNSET) {
            nextDueUs = ptsUs + step
            return true
        }
        if (ptsUs < due - intervalUs / 2) return false
        // After a gap (frames the player skipped itself), count on from this one.
        nextDueUs = if (due + step <= ptsUs) ptsUs + step else due + step
        return true
    }

    private companion object {
        const val THIN_ABOVE = 1.15f
        const val MAX_INTERVAL_US = 100_000L
    }
}

/** Media3 reports the cropped display size. The decoder still needs the full coded picture. */
internal fun croppedHevcCodedSize(format: Format): Pair<Int, Int>? {
    if (format.sampleMimeType != MimeTypes.VIDEO_H265 || format.width <= 0 || format.height <= 0) return null
    return format.initializationData.asSequence()
        .flatMap { csd -> runCatching { HevcBitstream.splitAnnexB(csd) }.getOrDefault(emptyList()).asSequence() }
        .filter { it.type == 33 }
        .mapNotNull { runCatching { HevcParameterSets.parseSps(it.bytes) }.getOrNull() }
        .firstOrNull { sps -> sps.displayWidth == format.width && sps.displayHeight == format.height &&
            (sps.width > sps.displayWidth || sps.height > sps.displayHeight) }
        ?.let { it.width to it.height }
}

/**
 * Amlogic maximum coded dimensions may be absent from Media3's MediaFormat.
 * Never call getInteger(KEY_MAX_*) on a key that is not present; also never
 * shrink an existing maximum. This is a decoder configuration, not a crop.
 */
internal fun effectiveHevcCodedMaximum(actual: Int, advertised: Int?): Int =
    maxOf(actual, advertised?.takeIf { it > 0 } ?: 0)

/** Compare actual SPS coded pictures, not just equal cropped display rectangles. */
internal fun tclCodedSizeChanged(oldFormat: Format, newFormat: Format): Boolean {
    if (oldFormat.width <= 0 || oldFormat.height <= 0 ||
        newFormat.width <= 0 || newFormat.height <= 0) return false
    val oldCoded = croppedHevcCodedSize(oldFormat) ?: (oldFormat.width to oldFormat.height)
    val newCoded = croppedHevcCodedSize(newFormat) ?: (newFormat.width to newFormat.height)
    return oldCoded != newCoded
}

/** The usual video renderer, showing only the frames [FrameThinner] keeps. */
@OptIn(UnstableApi::class)
private class ThinningVideoRenderer(
    context: Context,
    selector: MediaCodecSelector,
    joiningMs: Long,
    fallback: Boolean,
    handler: Handler?,
    listener: VideoRendererEventListener?,
    private val outputCadence: AdaptivePlaybackCadenceObserver,
) : MediaCodecVideoRenderer(
    context, ComposedCodecBufferFactory(DefaultMediaCodecAdapterFactory(context), outputCadence),
    selector, joiningMs, fallback, handler, listener,
    DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
) {
    private val thinner = FrameThinner()

    private val isTv = (context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)
        ?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

    override fun getMediaCodecConfiguration(
        codecInfo: MediaCodecInfo, format: Format, crypto: MediaCrypto?, codecOperatingRate: Float,
    ): MediaCodecAdapter.Configuration {
        val configuration = super.getMediaCodecConfiguration(codecInfo, format, crypto, codecOperatingRate)
        if (!isTclG09() || !codecInfo.name.startsWith("OMX.amlogic.hevc.", ignoreCase = true)) return configuration
        val (width, height) = croppedHevcCodedSize(format) ?: return configuration
        configuration.mediaFormat.apply {
            setInteger(MediaFormat.KEY_WIDTH, width)
            setInteger(MediaFormat.KEY_HEIGHT, height)
            // OMX.amlogic.hevc.* on TCL can omit KEY_MAX_WIDTH/HEIGHT.
            // Accessing absent keys throws and used to abort video activation.
            val previousMaxWidth = if (containsKey(MediaFormat.KEY_MAX_WIDTH))
                runCatching { getInteger(MediaFormat.KEY_MAX_WIDTH) }.getOrNull() else null
            val previousMaxHeight = if (containsKey(MediaFormat.KEY_MAX_HEIGHT))
                runCatching { getInteger(MediaFormat.KEY_MAX_HEIGHT) }.getOrNull() else null
            setInteger(MediaFormat.KEY_MAX_WIDTH, effectiveHevcCodedMaximum(width, previousMaxWidth))
            setInteger(MediaFormat.KEY_MAX_HEIGHT, effectiveHevcCodedMaximum(height, previousMaxHeight))
        }
        android.util.Log.i("PitwallVideo", "TCL_CODED_PICTURE coded=${width}x$height display=${format.width}x${format.height}")
        return configuration
    }

    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        super.onOutputFormatChanged(format, mediaFormat)
        if (outputCadence.composedSingle && DiagnosticLogs.recording) {
            val reported = runCatching {
                mediaFormat?.takeIf { it.containsKey(MediaFormat.KEY_LOW_LATENCY) }
                    ?.getInteger(MediaFormat.KEY_LOW_LATENCY)?.toString()
            }.getOrNull() ?: "UNREPORTED"
            android.util.Log.i("PitwallGraphics", "CODEC_LOW_LATENCY_OUTPUT reported=$reported vendorPoolBytes=NOT_MEASURED")
        }
    }

    override fun canReuseCodec(codecInfo: MediaCodecInfo, oldFormat: Format, newFormat: Format,
        isAdaptiveFormatChange: Boolean): DecoderReuseEvaluation {
        // Media3 1.11.1 includes the adaptive-format-change flag in the base
        // renderer's codec-reuse evaluation. Preserve its reasoning before
        // enforcing TCL coded SPS resolution changes as a hard recreate.
        val reuse = super.canReuseCodec(codecInfo, oldFormat, newFormat, isAdaptiveFormatChange)
        if (isTv && codecInfo.name.startsWith("OMX.amlogic.hevc.", ignoreCase = true) &&
            tclCodedSizeChanged(oldFormat, newFormat)) {
            android.util.Log.i("PitwallVideo", "RECREATE_ON_SIZE_CHANGE codec=${codecInfo.name} " +
                "${oldFormat.width}x${oldFormat.height} -> ${newFormat.width}x${newFormat.height}")
            return DecoderReuseEvaluation(codecInfo.name, oldFormat, newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO,
                reuse.discardReasons or DecoderReuseEvaluation.DISCARD_REASON_VIDEO_RESOLUTION_CHANGED)
        }
        return reuse
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean,
        sampleStreamIsResetToKeyFrame: Boolean) {
        // Media3 1.11.1 tracks keyframe-reset seeks to avoid briefly showing
        // stale frames. Forward the new flag before resetting local thinning.
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
        thinner.reset()
    }

    override fun renderOutputBufferV21(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long) {
        if (thinner.keep(presentationTimeUs, playbackSpeed,
                outputCadence.outputFps(thinner.estimatedSourceFps(), playbackSpeed)))
            super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs)
        else skipOutputBuffer(codec, index, presentationTimeUs)
    }

    @Deprecated("Deprecated in Java")
    override fun renderOutputBuffer(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long) {
        if (thinner.keep(presentationTimeUs, playbackSpeed,
                outputCadence.outputFps(thinner.estimatedSourceFps(), playbackSpeed)))
            @Suppress("DEPRECATION") super.renderOutputBuffer(codec, index, presentationTimeUs)
        else skipOutputBuffer(codec, index, presentationTimeUs)
    }
}

/** The usual renderers, with the video one thinning frames when sped up. */
@OptIn(UnstableApi::class)
internal class ThinningRenderersFactory(
    context: Context,
    private val outputCadence: AdaptivePlaybackCadenceObserver = AdaptivePlaybackCadenceObserver(context),
) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(ThinningVideoRenderer(context, mediaCodecSelector, allowedVideoJoiningTimeMs,
            enableDecoderFallback, eventHandler, eventListener, outputCadence))
    }
}
