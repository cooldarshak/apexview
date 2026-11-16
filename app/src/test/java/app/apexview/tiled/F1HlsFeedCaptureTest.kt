package app.apexview.tiled

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.apexview.ManifestProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
class F1HlsFeedCaptureTest {
    private class Network(val files: Map<String, ByteArray>) {
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val factory = DataSource.Factory {
            object : DataSource {
                private var delegate: ByteArrayDataSource? = null
                override fun addTransferListener(listener: TransferListener) {}
                override fun open(spec: DataSpec): Long {
                    requested += spec.uri.toString()
                    delegate = ByteArrayDataSource(files[spec.uri.toString()] ?: error("Unexpected media request"))
                    return delegate!!.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate!!.read(buffer, offset, length)
                override fun getUri(): Uri? = delegate?.uri
                override fun close() { delegate?.close() }
            }
        }
    }

    @Test fun webDashPlayerTmHlsFeedsReachARealClearComposedOutput() = runBlocking {
        for (count in 2..4) {
            val network = network(count)
            val capture = F1ArchiveFeedCapture("test", "authorised-test", "entitled-test", network.factory)
            val feeds = feeds(count)
            val snapshot = capture.captureAtPosition("native-hls-$count", feeds, 100)
            assertNull(snapshot.detail, snapshot.failure)
            assertEquals(count, snapshot.capturedFeeds)
            assertEquals(setOf(ManifestProtocol.HLS), snapshot.actualManifests.values.toSet())
            assertTrue(capture.clearOutput)
            val output = capture.outputWindow("native-hls-$count")!!
            assertEquals(1, output.fragments.size)
            assertEquals(960 * count, output.fragments.single().width)
            assertEquals(540, output.fragments.single().height)
            val packaged = ProtectedHevcComposedDashPackageBuilder.build(output, null,
                output.fragments.first().baseDecodeTimeTicks, System.currentTimeMillis(), clearOutput = true)
            assertEquals(1, Regex("<Representation ").findAll(packaged.manifest).count())
            assertFalse(packaged.manifest.contains("ContentProtection"))
            assertEquals(1, CmafBoxInspector.inspect(output.initializationSegment).tracks.size)
            assertTrue(network.requested.none { it.contains("CONTENT/PLAY") || it.contains("license") })
            packaged.zero(); output.zero(); capture.reset()
        }
    }

    @Test fun capturesNextContiguousHlsSegmentAndReusesTheVodManifests() = runBlocking {
        val network = network(2)
        val capture = F1ArchiveFeedCapture("test", "auth", "entitled", network.factory)
        val first = capture.captureAtPosition("hls-next", feeds(2), 100)
        assertNull(first.detail, first.failure)
        val second = capture.captureAtPosition("hls-next", feeds(2), 1100)
        assertNull(second.detail, second.failure)
        assertEquals(2, second.capturedFeeds)
        val output = capture.outputWindow("hls-next")!!
        assertEquals(2, output.fragments.size)
        assertEquals(listOf(1L, 2L), output.fragments.map { it.sequenceNumber })
        assertEquals(4, network.requested.count { it.endsWith(".m3u8") }) // two masters and two media playlists
        val evidence = System.getenv("APEXVIEW_HEVC_OUTPUT_PARAMETER_DIR")?.let(::File)
        if (evidence != null) {
            evidence.mkdirs()
            File(evidence, "native_hls_archive_grid.mp4").writeBytes(
                output.fragments.fold(output.initializationSegment) { bytes, fragment -> bytes + fragment.mediaFragment })
            val packaged = ProtectedHevcComposedDashPackageBuilder.build(output, null, 0, System.currentTimeMillis(), clearOutput = true)
            File(evidence, "native_hls_archive_grid.mpd").writeText(packaged.manifest)
            packaged.zero()
        }
        output.zero(); capture.reset()
    }

    @Test fun extensionlessOffersRouteFromBodyDetectionIntoNativeHls() = runBlocking {
        val original = network(2)
        val files = original.files.toMutableMap()
        for (i in 0..1) files["https://media.test/f$i/offer"] = files.getValue("https://media.test/f$i/master.m3u8")
        val network = Network(files)
        val capture = F1ArchiveFeedCapture("test", "auth", "entitled", network.factory)
        val snapshot = capture.captureAtPosition("hls-extensionless", feeds(2).mapIndexed { i, feed -> feed.copy(manifestUrl = "https://media.test/f$i/offer") }, 100)
        assertNull(snapshot.detail, snapshot.failure)
        assertNotNull(capture.outputWindow("hls-extensionless")?.also { it.zero() })
        capture.reset()
    }

    @Test fun hlsWithoutHevcFailsBeforeAnySegmentRead() = runBlocking {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,CODECS=\"avc1.640028\",RESOLUTION=960x540\navc.m3u8\n"
        val files = feeds(2).associate { it.manifestUrl to master.toByteArray() }
        val network = Network(files)
        val snapshot = F1ArchiveFeedCapture("test", "auth", "entitled", network.factory).captureAtPosition("hls-avc", feeds(2), 0)
        assertEquals(false, snapshot.failure?.retryable)
        assertTrue(snapshot.detail.orEmpty().contains("no supported HEVC"))
        assertTrue(network.requested.all { it.endsWith("master.m3u8") })
    }

    @Test fun aes128NeverRequestsAKeyOrReleasesSourceDecoders() = runBlocking {
        val media = """#EXTM3U
#EXT-X-TARGETDURATION:2
#EXT-X-KEY:METHOD=AES-128,URI="never-request.key",IV=0x00000000000000000000000000000001
#EXT-X-MAP:URI="init.mp4"
#EXTINF:2,
seg.m4s
#EXT-X-ENDLIST
"""
        val network = Network(feeds(2).associate { it.manifestUrl to media.toByteArray() })
        var released = false
        try {
            ArchiveCompositionStartup().start<Unit>(preflight = {
                val snapshot = F1ArchiveFeedCapture("test", "auth", "entitled", network.factory).captureAtPosition("hls-aes", feeds(2), 0)
                assertEquals(false, snapshot.failure?.retryable)
                throw PermanentCaptureException(snapshot.detail.orEmpty())
            }, releaseSources = { released = true }, replaceHost = {}, restoreSources = {}, dispose = {})
        } catch (error: PermanentCaptureException) { assertTrue(error.message.orEmpty().contains("AES-128")) }
        assertFalse(released)
        assertTrue(network.requested.none { it.endsWith(".key") || it.endsWith(".m4s") })
    }

    @Test fun byteRangesArePassedToTheDataSource() {
        val network = Network(mapOf("https://media.test/file" to byteArrayOf(1, 2, 3, 4, 5, 6)))
        val result = CmafHttpReader(network.factory).read(Uri.parse("https://media.test/file"), 8, 2, 3)
        assertArrayEquals(byteArrayOf(3, 4, 5), result.bytes)
    }

    @Test fun protectedPackageModeRejectsAClearInitialization() {
        // The existing protected writers/composer tests cover ciphertext preservation. The
        // clear adapter must keep its own independent metadata guard enabled as well.
        val network = network(2)
        val capture = F1ArchiveFeedCapture("test", "auth", "entitled", network.factory)
        runBlocking { assertNull(capture.captureAtPosition("mode-guard", feeds(2), 0).failure) }
        val output = capture.outputWindow("mode-guard")!!
        try {
            ProtectedHevcComposedDashPackageBuilder.build(output, null, 0, System.currentTimeMillis())
            fail("A clear source must not silently take the protected-package route")
        } catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("CBCS")) }
        output.zero(); capture.reset()
    }

    @Test fun programDateTimeMisalignmentFailsBeforeReadingInitOrMedia() = runBlocking {
        val original = network(2)
        val files = original.files.toMutableMap()
        for (i in 0..1) {
            val uri = "https://media.test/f$i/video.m3u8"
            files[uri] = files.getValue(uri).toString(Charsets.UTF_8).replace("#EXTINF:1,",
                "#EXT-X-PROGRAM-DATE-TIME:2026-11-16T00:00:00.${if (i == 0) "000" else "300"}Z\n#EXTINF:1,")
                .toByteArray()
        }
        val network = Network(files)
        val snapshot = F1ArchiveFeedCapture("test", "auth", "entitled", network.factory)
            .captureAtPosition("hls-misaligned", feeds(2), 100)
        assertEquals(false, snapshot.failure?.retryable)
        assertTrue(snapshot.detail.orEmpty().contains("alignment failure"))
        assertTrue(network.requested.all { it.endsWith(".m3u8") })
    }

    @Test fun acceptsCmafPrefixBoxesTfhdDefaultsAndVersionZeroTruns() = runBlocking {
        val original = network(2)
        val files = original.files.mapValues { (uri, bytes) -> if (uri.endsWith(".m4s")) compactFragment(bytes) else bytes }
        val capture = F1ArchiveFeedCapture("test", "auth", "entitled", Network(files).factory)
        val snapshot = capture.captureAtPosition("hls-defaults", feeds(2), 0)
        assertNull(snapshot.detail, snapshot.failure)
        val output = capture.outputWindow("hls-defaults")!!
        assertEquals(25, output.fragments.single().sampleCount)
        output.zero(); capture.reset()
    }

    private fun compactFragment(bytes: ByteArray): ByteArray {
        fun children(parent: ByteArray): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            var at = 8
            while (at < parent.size) {
                val size = ByteBuffer.wrap(parent, at, 4).int
                result += parent.copyOfRange(at, at + size)
                at += size
            }
            return result
        }
        fun type(box: ByteArray) = box.copyOfRange(4, 8).toString(Charsets.US_ASCII)
        fun box(type: String, payload: ByteArray) = ByteBuffer.allocate(payload.size + 8).putInt(payload.size + 8)
            .put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()
        fun full(type: String, flags: Int, payload: ByteArray) = box(type, ByteBuffer.allocate(payload.size + 4).putInt(flags).put(payload).array())
        val moofSize = ByteBuffer.wrap(bytes, 0, 4).int
        val parts = children(bytes.copyOfRange(0, moofSize))
        val mfhd = parts.single { type(it) == "mfhd" }
        val traf = children(parts.single { type(it) == "traf" })
        val oldHeader = traf.single { type(it) == "tfhd" }
        val tfdt = traf.single { type(it) == "tfdt" }
        val oldRun = ByteBuffer.wrap(traf.single { type(it) == "trun" })
        val count = oldRun.getInt(12)
        oldRun.position(20)
        val entries = ByteBuffer.allocate(count * 8)
        repeat(count) {
            assertEquals(3600, oldRun.int)
            val size = oldRun.int
            val flags = oldRun.int
            assertEquals(0, oldRun.int)
            entries.putInt(size).putInt(flags)
        }
        val tfhd = full("tfhd", 0x02000A, ByteBuffer.allocate(12).putInt(ByteBuffer.wrap(oldHeader).getInt(12)).putInt(1).putInt(3600).array())
        fun moof(offset: Int): ByteArray {
            val trun = full("trun", 0x000601, ByteBuffer.allocate(8 + entries.capacity()).putInt(count).putInt(offset).put(entries.array()).array())
            return box("moof", mfhd + box("traf", tfhd + tfdt + trun))
        }
        val provisional = moof(0)
        val styp = box("styp", "msdh".toByteArray() + ByteArray(4) + "msdhmsix".toByteArray())
        return styp + moof(provisional.size + 8) + bytes.copyOfRange(moofSize, bytes.size)
    }

    private fun feeds(count: Int) = (0 until count).map { F1DashFeedCapture.Feed("channel-$it", "https://media.test/f$it/master.m3u8") }

    private fun network(count: Int): Network {
        val dir = System.getenv("APEXVIEW_HEVC_FIXTURE_DIR")?.let(::File)
        assumeTrue("Independent HEVC source fixtures are not configured", dir != null)
        val files = mutableMapOf<String, ByteArray>()
        for (i in 0 until count) {
            val nals = HevcBitstream.splitAnnexB(File(dir!!, if (i % 2 == 0) "feed_a.hevc" else "feed_b.hevc").readBytes())
            val init = HevcClearCmafInitSegmentWriter.writeSource(i + 1L, 90_000,
                nals.first { it.type == 32 }.bytes, nals.first { it.type == 33 }.bytes, nals.first { it.type == 34 }.bytes)
            val pictures = mutableListOf<MutableList<ByteArray>>()
            nals.filter { it.type in 0..31 }.forEach { nal ->
                if ((nal.bytes[2].toInt() and 0x80) != 0) pictures += mutableListOf<ByteArray>()
                pictures.last() += nal.bytes
            }
            val samples = pictures.map { picture ->
                val bytes = ByteArrayOutputStream()
                picture.forEach { nal -> bytes.write(ByteBuffer.allocate(4).putInt(nal.size).array()); bytes.write(nal) }
                HevcClearCmafFragmentWriter.Sample(bytes.toByteArray(), 3600, 0,
                    if (HevcBitstream.parseNalHeader(picture.first()).type in 16..23) 0x02000000L else 0x01010000L)
            }
            files["https://media.test/f$i/master.m3u8"] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,CODECS=\"hvc1.1.6.L93.B0\",RESOLUTION=960x540\nvideo.m3u8\n".toByteArray()
            files["https://media.test/f$i/video.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1,\ns0.m4s\n#EXTINF:1,\ns1.m4s\n#EXT-X-ENDLIST\n".toByteArray()
            files["https://media.test/f$i/init.mp4"] = init
            samples.chunked(25).forEachIndexed { index, chunk ->
                files["https://media.test/f$i/s$index.m4s"] = HevcClearCmafFragmentWriter.write(i + 1L,
                    index + 1L, index * 90_000L, chunk, false, false)
            }
        }
        return Network(files)
    }
}
