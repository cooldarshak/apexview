package app.apexview.cast.local

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** Hardware-only applies to video. AAC on many TVs is legitimately a software audio codec. */
internal class ReceiverCodecSelector(private val upstream: MediaCodecSelector = MediaCodecSelector.DEFAULT) : MediaCodecSelector {
    override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> {
        val codecs = upstream.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        return if (mimeType.startsWith("video/")) codecs.filter { it.hardwareAccelerated } else codecs
    }
}
