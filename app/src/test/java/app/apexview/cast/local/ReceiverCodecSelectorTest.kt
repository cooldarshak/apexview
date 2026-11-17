package app.apexview.cast.local

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiverCodecSelectorTest {
    @Test fun aSoftwareAacDecoderSurvivesWhileSoftwareVideoFallbackIsRejectedAndSecureFlagsAreForwarded() {
        fun codec(name: String, mime: String, hardware: Boolean) = MediaCodecInfo.newInstance(name, mime, mime, null,
            hardware, !hardware, hardware, false, false)
        val aac = codec("c2.android.aac.decoder", "audio/mp4a-latm", false)
        val hevc = codec("OMX.amlogic.hevc.decoder.awesome2", "video/hevc", true)
        val softHevc = codec("c2.android.hevc.decoder", "video/hevc", false)
        val calls = mutableListOf<Triple<String, Boolean, Boolean>>()
        val selector = ReceiverCodecSelector(MediaCodecSelector { mime, secure, tunnel ->
            calls += Triple(mime, secure, tunnel)
            if (mime == "video/hevc") listOf(hevc, softHevc) else listOf(aac)
        })
        assertEquals(listOf(aac), selector.getDecoderInfos("audio/mp4a-latm", false, false))
        assertEquals(listOf(hevc), selector.getDecoderInfos("video/hevc", true, true))
        assertEquals(Triple("video/hevc", true, true), calls.last())
    }
}
