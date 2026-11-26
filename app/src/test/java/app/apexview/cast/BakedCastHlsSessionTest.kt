package app.apexview.cast

import java.net.InetAddress
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BakedCastHlsSessionTest {
    @Test fun startupRequiresThreeAdvertisedTargetDurations() {
        assertFalse(BakedCastStartupPolicy.ready(3, 3_000_000L))
        assertFalse(BakedCastStartupPolicy.ready(11, 11_999_999L))
        assertFalse(BakedCastStartupPolicy.ready(2, 12_000_000L))
        assertTrue(BakedCastStartupPolicy.ready(12, 12_000_000L))
    }

    @Test
    fun playlistRollsAndPublishesMuxedVideoAndCommentary() {
        // These payloads exercise transport framing and sequence retention.
        val video = byteArrayOf(0, 0, 0, 1, 0x65)
        val audio = BakedAacAdts.wrap(byteArrayOf(1), 48_000, 2)
        BakedCastHlsSession("127.0.0.1", InetAddress.getLoopbackAddress()).use { session ->
            repeat(32) { index ->
                val time = index * 1_000_000L
                session.append(listOf(
                    BakedCastTransportStream.Sample(time, video, video = true, keyFrame = true),
                    BakedCastTransportStream.Sample(time, audio, video = false),
                ), 1_000_000L)
            }
            val playlist = URL(session.url).readText()
            assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:20"))
            assertTrue(playlist.contains("view-31.ts"))
            assertFalse(playlist.contains("view-19.ts"))
            assertEquals(12_000_000L, session.advertisedDurationUs)
            assertTrue(runCatching { URL(URL(session.url), "view-0.ts").readBytes() }.isFailure)
            val segment = URL(URL(session.url), "view-19.ts").readBytes()
            assertEquals(0, segment.size % 188)
            val pids = (segment.indices step 188).map { offset ->
                assertEquals(0x47, segment[offset].toInt() and 255)
                ((segment[offset + 1].toInt() and 31) shl 8) or (segment[offset + 2].toInt() and 255)
            }
            assertEquals(listOf(0, 0x1000, 0x100, 0x101), pids)
            session.end()
            assertTrue(URL(session.url).readText().contains("#EXT-X-ENDLIST"))
        }
    }

    @Test fun invalidAdtsLengthIsRejectedBeforeMux() {
        val video = BakedCastTransportStream.Sample(0, byteArrayOf(0,0,0,1,0x65), true, true)
        val audio = BakedAacAdts.wrap(byteArrayOf(1,2,3),48_000,2)
        val muxer = BakedCastTransportStream()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            muxer.segment(listOf(video,BakedCastTransportStream.Sample(0,audio.copyOf(audio.size-1),false)))
        }
        assertEquals(0,muxer.segment(listOf(video,BakedCastTransportStream.Sample(0,audio,false))).size%188)
    }

    @Test
    fun ffmpegDecodesBothTracksFromTheSameTransportStream() {
        assumeTrue(runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        val directory = Files.createTempDirectory("pitwall-baked-test").toFile()
        try {
            val video = directory.resolve("picture.h264")
            val audio = directory.resolve("commentary.aac")
            val output = directory.resolve("segment.ts")
            ffmpeg("-f", "lavfi", "-i", "testsrc2=size=128x72:rate=25", "-frames:v", "1",
                "-c:v", "libx264", "-f", "h264", video.path)
            ffmpeg("-f", "lavfi", "-i", "sine=frequency=600:sample_rate=48000", "-frames:a", "8",
                "-c:a", "aac", "-f", "adts", audio.path)
            // FFmpeg may flush more than one ADTS frame, even with -frames:a 1.
            // Each mux sample is one access unit, never the whole ADTS file.
            val audioBytes = audio.readBytes()
            val audioSamples = mutableListOf<BakedCastTransportStream.Sample>()
            var at = 0
            while (at < audioBytes.size) {
                assertTrue(audioBytes.size - at >= 7)
                assertEquals(255, audioBytes[at].toInt() and 255)
                assertEquals(240, audioBytes[at + 1].toInt() and 246)
                assertEquals(3, (audioBytes[at + 2].toInt() ushr 2) and 15) // 48 kHz
                assertEquals(0, audioBytes[at + 6].toInt() and 3) // one raw data block
                val size = ((audioBytes[at + 3].toInt() and 3) shl 11) or
                    ((audioBytes[at + 4].toInt() and 255) shl 3) or
                    ((audioBytes[at + 5].toInt() and 255) ushr 5)
                val header = if (audioBytes[at + 1].toInt() and 1 != 0) 7 else 9
                assertTrue(size > header && size <= audioBytes.size - at)
                audioSamples += BakedCastTransportStream.Sample(
                    audioSamples.size * 1024L * 1_000_000L / 48_000L,
                    audioBytes.copyOfRange(at, at + size), video = false)
                at += size
            }
            assertEquals(audioBytes.size, at)
            assertTrue(audioSamples.size >= 8)
            val muxer = BakedCastTransportStream()
            val videoBytes = video.readBytes()
            val videoSamples = (0..3).map { i ->
                BakedCastTransportStream.Sample(i * 40_000L, videoBytes, video = true, keyFrame = true)
            }
            output.writeBytes(muxer.segment(videoSamples + audioSamples))
            ffmpeg("-xerror", "-f", "mpegts", "-i", output.path, "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-")
        } finally { directory.deleteRecursively() }
    }

    private fun ffmpeg(vararg arguments: String) {
        val process = ProcessBuilder(listOf("ffmpeg", "-nostdin", "-v", "error") + arguments)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }
}
