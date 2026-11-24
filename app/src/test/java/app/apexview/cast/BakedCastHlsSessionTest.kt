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
    @Test
    fun playlistRollsAndPublishesMuxedVideoAndCommentary() {
        // These payloads exercise transport framing and sequence retention.
        val video = byteArrayOf(0, 0, 0, 1, 0x65)
        val audio = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, 0x80.toByte(), 0, 0xff.toByte(), 0xfc.toByte())
        BakedCastHlsSession("127.0.0.1", InetAddress.getLoopbackAddress()).use { session ->
            repeat(10) { index ->
                val time = index * 1_000_000L
                session.append(listOf(
                    BakedCastTransportStream.Sample(time, video, video = true, keyFrame = true),
                    BakedCastTransportStream.Sample(time, audio, video = false),
                ), 1_000_000L)
            }
            val playlist = URL(session.url).readText()
            assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:5"))
            assertTrue(playlist.contains("view-9.ts"))
            assertFalse(playlist.contains("view-4.ts"))
            val segment = URL(URL(session.url), "view-4.ts").readBytes()
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
            ffmpeg("-f", "lavfi", "-i", "sine=frequency=600:sample_rate=48000", "-frames:a", "1",
                "-c:a", "aac", "-f", "adts", audio.path)
            val muxer = BakedCastTransportStream()
            output.writeBytes(muxer.segment((0..3).flatMap { i ->
                listOf(BakedCastTransportStream.Sample(i * 40_000L, video.readBytes(), video = true, keyFrame = true),
                    BakedCastTransportStream.Sample(i * 40_000L, audio.readBytes(), video = false))
            }))
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
