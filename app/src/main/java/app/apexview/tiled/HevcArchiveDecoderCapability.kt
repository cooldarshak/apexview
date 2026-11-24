package app.apexview.tiled

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/** Bounded clear-HEVC size/rate preflight using the same hardware requirement as
 * FeedPlayer.requireArchiveHardwareSupport. This never decodes content or alters DRM. */
internal object HevcArchiveDecoderCapability {
    fun supportsClearAtlas(width: Int, height: Int, fps: Double): Boolean {
        if (width !in 1..4096 || height !in 1..HevcArchiveAtlasPlan.MAX_CODED_HEIGHT ||
            !fps.isFinite() || fps <= 0.0 || fps > 120.0) return false
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
                !codec.isEncoder && codec.isHardwareAccelerated &&
                    codec.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) } &&
                    runCatching {
                        val capabilities = codec.getCapabilitiesForType("video/hevc")
                        !capabilities.isFeatureRequired(
                            MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback
                        ) && capabilities.videoCapabilities?.areSizeAndRateSupported(
                            width, height, fps
                        ) == true
                    }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }
}
