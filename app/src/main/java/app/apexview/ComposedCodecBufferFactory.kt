package app.apexview

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import app.apexview.tiled.HevcBitstream
import app.apexview.tiled.HevcParameterSets

/** M51/M52: opt-in supported low-latency decoding, not a forced buffer count.
 * The original Media3 configuration is untouched. Protected/unknown/noncomposed
 * sources and receivers retain their original path. A rejected trial is not
 * retried on the same renderer. No hidden vendor keys or SPS mutation. */
@OptIn(UnstableApi::class)
internal class ComposedCodecBufferFactory(
    private val delegate: MediaCodecAdapter.Factory,
    private val cadence: AdaptivePlaybackCadenceObserver,
) : MediaCodecAdapter.Factory {
    private var rejected = false

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val info = configuration.codecInfo
        val format = configuration.format
        val hevc = format.sampleMimeType == MimeTypes.VIDEO_H265
        val protected = info.secure || configuration.crypto != null || format.drmInitData != null
        val supported = Build.VERSION.SDK_INT >= 30 &&
            runCatching { info.capabilities?.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency) == true }
                .getOrDefault(false)
        val enabled = cadence.decoderLowLatencyTrial && !rejected
        val existing = configuration.mediaFormat.containsKey(MediaFormat.KEY_LOW_LATENCY)
        val trial = ComposedCodecBufferPolicy.eligible(Build.VERSION.SDK_INT, enabled,
            cadence.composedSingle, hevc, info.hardwareAccelerated, protected,
            configuration.surface != null, supported, existing)
        if (cadence.composedSingle && hevc && DiagnosticLogs.recording) {
            // Exact coded DPB/reorder declarations, not actual driver pool depth.
            val sps = format.initializationData.asSequence()
                .flatMap { runCatching { HevcBitstream.splitAnnexB(it) }.getOrDefault(emptyList()).asSequence() }
                .filter { it.type == 33 }
                .mapNotNull { runCatching { HevcParameterSets.parseSps(it.bytes) }.getOrNull() }
                .firstOrNull()
            // Report the configured adaptive envelope independently of coded SPS.
            // Reading these public keys cannot change codec or secure configuration.
            fun configured(key: String): Int =
                runCatching { configuration.mediaFormat.getInteger(key) }.getOrDefault(-1)
            val canonical = runCatching {
                android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
                    .firstOrNull { it.name == info.name }?.canonicalName
            }.getOrNull() ?: "UNREPORTED"
            val operatingRate = runCatching { configuration.mediaFormat.getFloat(MediaFormat.KEY_OPERATING_RATE) }.getOrNull()
            val envelope = CodecEnvelopeAudit.classify(sps?.width ?: -1, sps?.height ?: -1,
                configured(MediaFormat.KEY_MAX_WIDTH), configured(MediaFormat.KEY_MAX_HEIGHT))
            Log.i("PitwallGraphics", "CODEC_BUFFER_POLICY codec=${info.name} canonical=$canonical " +
                "sdk=${Build.VERSION.SDK_INT} device=${Build.DEVICE} model=${Build.MODEL.replace(' ', '_')} hardware=${info.hardwareAccelerated} secureCodec=${info.secure} " +
                "adaptive=${info.adaptive} envelope=$envelope " +
                "surface=${configuration.surface != null} operatingRate=${operatingRate ?: "UNREPORTED"} " +
                "profile=${configured(MediaFormat.KEY_PROFILE)} level=${configured(MediaFormat.KEY_LEVEL)} " +
                "format=${format.width}x${format.height} " +
                "configured=${configured(MediaFormat.KEY_WIDTH)}x${configured(MediaFormat.KEY_HEIGHT)} " +
                "adaptiveMax=${configured(MediaFormat.KEY_MAX_WIDTH)}x${configured(MediaFormat.KEY_MAX_HEIGHT)} " +
                "maxInputBytes=${configured(MediaFormat.KEY_MAX_INPUT_SIZE)} " +
                "spsDisplay=${sps?.displayWidth ?: -1}x${sps?.displayHeight ?: -1} " +
                "spsBitDepth=${sps?.bitDepthLuma ?: -1}:${sps?.bitDepthChroma ?: -1} " +
                "spsChroma=${sps?.chromaFormatIdc ?: -1} " +
                "coded=${sps?.width ?: -1}x${sps?.height ?: -1} " +
                "spsDpb=${sps?.maxDecPicBuffering?.joinToString(",") ?: "UNKNOWN"} " +
                "spsReorder=${sps?.maxNumReorderPics?.joinToString(",") ?: "UNKNOWN"} " +
                "lowLatencySupported=$supported requested=$trial protected=$protected " +
                "vendorPoolBytes=NOT_MEASURED")
        }
        if (!trial) return delegate.createAdapter(configuration)
        // MediaFormat copy constructor is public from API 29. This is a new
        // configuration with the exact same codec, format, surface and crypto.
        val trialFormat = MediaFormat(configuration.mediaFormat).apply {
            // Duplicate only ByteBuffer views (not encoded data) so a rejected
            // codec configuration cannot move the baseline CSD read positions.
            configuration.mediaFormat.keys.forEach { key ->
                if (configuration.mediaFormat.getValueTypeForKey(key) == MediaFormat.TYPE_BYTE_BUFFER) {
                    configuration.mediaFormat.getByteBuffer(key)?.let { setByteBuffer(key, it.duplicate()) }
                }
            }
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val trialConfiguration = MediaCodecAdapter.Configuration.createForVideoDecoding(
            info, trialFormat, format, configuration.surface, configuration.crypto)
        val result = ComposedCodecBufferPolicy.create(
            trial = { delegate.createAdapter(trialConfiguration) },
            baseline = { delegate.createAdapter(configuration) },
            rejected = { error ->
                rejected = true
                Log.w("PitwallGraphics", "CODEC_LOW_LATENCY_REJECTED codec=${info.name} type=${error.javaClass.simpleName} retry=ORIGINAL_SAME_CODEC")
            },
        )
        if (!rejected) Log.i("PitwallGraphics", "CODEC_LOW_LATENCY_CONFIGURED codec=${info.name} actualPool=NOT_MEASURED")
        return result
    }
}
