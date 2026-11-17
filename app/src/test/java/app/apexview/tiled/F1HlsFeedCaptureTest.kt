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
        var beforeOpen: ((DataSpec) -> Unit)? = null
        val factory = DataSource.Factory {
            object : DataSource {
                private var delegate: ByteArrayDataSource? = null
                override fun addTransferListener(listener: TransferListener) {}
                override fun open(spec: DataSpec): Long {
                    requested += spec.uri.toString()
                    beforeOpen?.invoke(spec)
                    delegate = ByteArrayDataSource(files[spec.uri.toString()] ?: error("Unexpected media request"))
                    return delegate!!.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate!!.read(buffer, offset, length)
                override fun getUri(): Uri? = delegate?.uri
                override fun close() { delegate?.close() }
            }
        }
    }

    @Test fun fourCameraVodPreparationFetchesIndependentOffersAndFragmentsConcurrently() = runBlocking {
        val network = network(4)
        val manifests = java.util.concurrent.CountDownLatch(4)
        val fragments = java.util.concurrent.CountDownLatch(4)
        network.beforeOpen = { spec ->
            val barrier = when {
                spec.uri.toString().endsWith("master.m3u8") -> manifests
                spec.uri.toString().endsWith(".m4s") -> fragments
                else -> null
            }
            if (barrier != null) {
                barrier.countDown()
                check(barrier.await(8, java.util.concurrent.TimeUnit.SECONDS)) { "Independent feeds were loaded serially" }
            }
        }
        val capture = F1HlsFeedCapture(network.factory)
        val session = capture.prepareVod(feeds(4), 100, "channel-0")
        try {
            assertEquals(0L, manifests.count)
            assertEquals(0L, fragments.count)
            assertEquals(3840, session.width)
        } finally { session.close(); capture.reset() }
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

    @Test fun pausedArchiveCanPrefetchBeforeTheNextSegmentBoundary() = runBlocking {
        val capture = F1ArchiveFeedCapture("test", "auth", "entitled", network(2).factory)
        val first = capture.captureAtPosition("hls-prefetch", feeds(2), 100)
        val next = capture.captureAtPosition("hls-prefetch", feeds(2), 100)
        assertNull(first.detail, first.failure)
        assertNull(next.detail, next.failure)
        assertEquals(2, next.capturedFeeds)
        assertEquals(CmafWindowTiming(0, 2_000_000), next.outputTiming)
        assertTrue(ArchiveBufferPolicy.ready(next.outputTiming, 100_000, 2_000_000))
        capture.reset()
    }

    @Test fun epochDecodeTimesAndPrefetchDoNotMoveTheArchiveTimelineOrigin() = runBlocking {
        val epochTicks = 1_700_000_000L * 90_000L
        val capture = F1ArchiveFeedCapture("test", "auth", "entitled", network(2, epochTicks).factory)
        val first = capture.captureAtPosition("hls-epoch", feeds(2), 100)
        val next = capture.captureAtPosition("hls-epoch", feeds(2), 100)
        assertNull(first.detail, first.failure)
        assertNull(next.detail, next.failure)
        assertEquals(CmafWindowTiming(0, 1_000_000), first.outputTiming)
        assertEquals(CmafWindowTiming(0, 2_000_000), next.outputTiming)
        assertEquals(1_000_000L, next.alignedSegmentTimeUs)
        capture.reset()
    }

    @Test fun startupSelectsTheCurrentSegmentInsteadOfTheCloserFutureSegment() = runBlocking {
        val network = network(2)
        val capture = F1ArchiveFeedCapture("test", "auth", "entitled", network.factory)
        val snapshot = capture.captureAtPosition("hls-no-future-start", feeds(2), 900)
        assertNull(snapshot.detail, snapshot.failure)
        assertEquals(0L, snapshot.alignedSegmentTimeUs)
        assertTrue(network.requested.none { it.endsWith("s1.m4s") })
        capture.reset()
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

    @Test fun cancelledCmafReadStopsBeforeConsumingResponseBytes() {
        val network = Network(mapOf("https://media.test/file" to byteArrayOf(1, 2, 3)))
        val alreadyInterrupted = Thread.interrupted()
        try {
            Thread.currentThread().interrupt()
            try {
                CmafHttpReader(network.factory).read(Uri.parse("https://media.test/file"), 8)
                fail("An interrupted warm-up must not keep reading")
            } catch (_: java.io.InterruptedIOException) { }
        } finally {
            Thread.interrupted()
            if (alreadyInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test fun seekCancelsAnUnrelatedFragmentWarmup() = runBlocking {
        val network = network(2)
        val capture = F1HlsFeedCapture(network.factory)
        val session = capture.prepareVod(feeds(2), 0, "channel-0")
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        network.beforeOpen = { spec ->
            if (spec.uri.toString().endsWith("s1.m4s")) {
                entered.countDown()
                try { release.await() } catch (error: InterruptedException) { cancelled.countDown(); throw error }
            }
        }
        try {
            session.segment(0).fill(0) // schedules segment 1 on the warm-ahead worker
            assertTrue("Warm-ahead did not start", entered.await(8, java.util.concurrent.TimeUnit.SECONDS))
            session.prioritizeSeek(0)
            assertTrue("Stale download was not cancelled", cancelled.await(8, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown(); session.close(); capture.reset()
        }
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
            files[uri] = files.getValue(uri).toString(Charsets.UTF_8).replace("#EXTINF:1.0,",
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

    @Test fun nativeVodHasOneFiniteVideoTrackAndSupportsNonSequentialReads() = runBlocking {
        val network = network(2, 1_700_000_000L * 90_000L)
        val capture = F1HlsFeedCapture(network.factory)
        val session = capture.prepareVod(feeds(2), 1100, "channel-0")
        try {
            val manifest = androidx.media3.exoplayer.dash.manifest.DashManifestParser().parse(
                HevcArchiveVodSession.MANIFEST_URI, session.manifest().inputStream())
            assertFalse(manifest.dynamic)
            assertEquals(2000L, manifest.durationMs)
            assertEquals(1, manifest.getPeriod(0).adaptationSets.size)
            assertEquals(1, manifest.getPeriod(0).adaptationSets.single().representations.size)
            assertEquals(2, session.segmentCount)
            assertEquals(0, session.segmentAt(999_999))
            assertEquals(1, session.segmentAt(1_000_000))
            val init = session.initialization()
            val report = CmafBoxInspector.inspect(init)
            assertEquals(1, report.tracks.size)
            assertFalse(report.tracks.single().encrypted)
            for (number in listOf(1, 0, 1)) {
                val bytes = session.segment(number)
                val fragment = CmafBoxInspector.inspect(bytes, report).fragments.single()
                assertEquals(25, fragment.sampleCount)
                assertEquals(1_700_000_000L * 90_000L + number * 90_000L, fragment.baseDecodeTimeTicks)
                bytes.fill(0)
            }
            assertFalse(session.manifest().toString(Charsets.UTF_8).contains("ContentProtection"))
            init.fill(0)
        } finally { session.close(); capture.reset() }
        assertTrue(network.requested.none { it.contains("CONTENT/PLAY") || it.contains("license") || it.endsWith(".key") })
    }

    @Test fun nativeVodPreservesVerifiedHlsEpochForTimingAndMapReplay() = runBlocking {
        val original = network(2)
        val files = original.files.mapValues { (uri, bytes) -> if (uri.endsWith("video.m3u8"))
            bytes.toString(Charsets.UTF_8).replace("#EXT-X-MAP:", "#EXT-X-PROGRAM-DATE-TIME:2026-11-16T14:00:00.000Z\n#EXT-X-MAP:").toByteArray() else bytes }
        val capture = F1HlsFeedCapture(Network(files).factory)
        val session = capture.prepareVod(feeds(2), 1100, "channel-0")
        try {
            val expected = java.time.Instant.parse("2026-11-16T14:00:00Z").toEpochMilli()
            assertEquals(expected, session.archiveStartTimeMs)
            val manifest = androidx.media3.exoplayer.dash.manifest.DashManifestParser().parse(
                HevcArchiveVodSession.MANIFEST_URI, session.manifest().inputStream())
            assertEquals(expected, manifest.availabilityStartTimeMs)
            assertFalse(manifest.dynamic)
            assertEquals(2000L, manifest.durationMs)
        } finally { session.close(); capture.reset() }
    }

    @Test fun fixedProbeDoesNotChaseAnAdvancingSourceClock() = runBlocking {
        val network = network(3)
        val clockMs = java.util.concurrent.atomic.AtomicLong(100)
        network.beforeOpen = { spec -> if (spec.uri.toString().endsWith(".m4s")) clockMs.addAndGet(10_000) }
        val capture = F1HlsFeedCapture(network.factory)
        val session = capture.prepareVod(feeds(3), clockMs.get(), "channel-0")
        try {
            assertTrue(clockMs.get() > 2_000)
            assertEquals(3, network.requested.count { it.endsWith("s0.m4s") })
            assertTrue(network.requested.none { it.endsWith("s1.m4s") })
            assertEquals(2880, session.width)
            assertEquals(540, session.height)
        } finally { session.close(); capture.reset() }
    }

    @Test fun sourceReadsRunInParallelWithABoundedFourRequestLimit() = runBlocking {
        val network = network(4)
        val latch = java.util.concurrent.CountDownLatch(4)
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        network.beforeOpen = { spec ->
            if (spec.uri.toString().endsWith(".m4s")) {
                val current = inFlight.incrementAndGet()
                peak.updateAndGet { maxOf(it, current) }
                latch.countDown()
                try { assertTrue("Four source reads must overlap", latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) }
                finally { inFlight.decrementAndGet() }
            }
        }
        val capture = F1HlsFeedCapture(network.factory)
        val session = capture.prepareVod(feeds(4), 0, "channel-0")
        try { assertEquals(4, peak.get()); assertEquals(4, network.requested.count { it.endsWith(".m4s") }) }
        finally { session.close(); capture.reset() }
    }

    @Test fun choosesCommon540RungInsteadOfHighestAvailable1080ForThreeFeeds() = runBlocking {
        val original = network(3)
        val files = original.files.mapValues { (uri, bytes) -> if (uri.endsWith("master.m3u8")) {
            bytes.toString(Charsets.UTF_8).replace("#EXTM3U\n", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,CODECS=\"hvc1.1.6.L123.B0\",RESOLUTION=1920x1080\nhigh.m3u8\n").toByteArray()
        } else bytes }
        val network = Network(files)
        val capture = F1HlsFeedCapture(network.factory)
        val session = capture.prepareVod(feeds(3), 0, "channel-0")
        try { assertEquals(540, session.selectedHeight); assertTrue(network.requested.none { it.contains("high.m3u8") }) }
        finally { session.close(); capture.reset() }
    }

    @Test fun unavailableLegalRenditionFailsBeforeSourceDecoderRelease() = runBlocking {
        val original = network(4)
        val files = original.files.mapValues { (uri, bytes) -> if (uri.endsWith("master.m3u8"))
            bytes.toString(Charsets.UTF_8).replace("960x540", "3840x2160").toByteArray() else bytes }
        val network = Network(files)
        var released = false
        val capture = F1HlsFeedCapture(network.factory)
        try {
            ArchiveCompositionStartup().start(
                preflight = { capture.prepareVod(feeds(4), 0, "channel-0") },
                releaseSources = { released = true }, replaceHost = { fail("No legal rendition") },
                restoreSources = { fail("Sources must be unchanged") }, dispose = { it.close() })
            fail("Expected rendition rejection")
        } catch (expected: PermanentCaptureException) {
            assertTrue(expected.message.orEmpty().contains("common HEVC rendition"))
        } finally { capture.reset() }
        assertFalse(released)
        assertTrue(network.requested.all { it.endsWith("master.m3u8") })
    }

    @Test fun nativeAudioIsSelectedFromTheSameAuthorizedOfferWithoutReadingVideoTwice() = runBlocking {
        val original = network(2)
        val files = original.files.toMutableMap()
        for (i in 0..1) {
            val uri = "https://media.test/f$i/master.m3u8"
            files[uri] = files.getValue(uri).toString(Charsets.UTF_8).replace("#EXTM3U\n", "#EXTM3U\n" +
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"French\",LANGUAGE=\"fr\",URI=\"fr.m3u8\"\n" +
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"English\",LANGUAGE=\"en\",URI=\"en.m3u8\"\n")
                .replace("BANDWIDTH=1000000,", "BANDWIDTH=1000000,AUDIO=\"audio\",").toByteArray()
            files["https://media.test/f$i/en.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\na0.aac\n#EXTINF:1,\na1.aac\n#EXT-X-ENDLIST\n".toByteArray()
        }
        val network = Network(files)
        val capture = F1HlsFeedCapture(network.factory)
        val session = capture.prepareVod(feeds(2), 0, "channel-1", commentary = "en")
        try {
            assertEquals("https://media.test/f1/en.m3u8", session.audioUri.toString())
            assertTrue(network.requested.none { it.endsWith("fr.m3u8") || it.endsWith(".aac") || it.contains("CONTENT/PLAY") })
        } finally { session.close(); capture.reset() }
    }

    @Test fun missingSeparateAudioUsesTheSameAuthorizedOfferForMedia3Audio() = runBlocking {
        val network = network(2)
        val capture = F1HlsFeedCapture(network.factory)
        val session = capture.prepareVod(feeds(2), 0, "channel-0", commentary = "en")
        try {
            assertEquals("https://media.test/f0/master.m3u8", session.audioUri.toString())
            assertEquals(session.audioUri, session.audioUris["channel-0"])
            assertEquals(1920, session.width)
            assertTrue(network.requested.none { it.contains("CONTENT/PLAY") || it.contains("license") })
        } finally { session.close(); capture.reset() }
    }

    @Test fun silentSecondaryFeedDoesNotBlockTheSelectedFeedsAudio() = runBlocking {
        val original = network(2)
        val files = original.files.toMutableMap()
        val first = "https://media.test/f0/master.m3u8"
        files[first] = files.getValue(first).toString(Charsets.UTF_8).replace("#EXTM3U\n", "#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"English\",LANGUAGE=\"en\",URI=\"en.m3u8\"\n")
            .replace("BANDWIDTH=1000000,", "BANDWIDTH=1000000,AUDIO=\"audio\",").toByteArray()
        files["https://media.test/f0/en.m3u8"] = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\na0.aac\n#EXTINF:1,\na1.aac\n#EXT-X-ENDLIST\n".toByteArray()
        val capture = F1HlsFeedCapture(Network(files).factory)
        val session = capture.prepareVod(feeds(2), 0, "channel-0", commentary = "en")
        try {
            assertEquals("https://media.test/f0/en.m3u8", session.audioUri.toString())
            assertFalse(session.audioUris.containsKey("channel-1"))
        } finally { session.close(); capture.reset() }
    }

    @Test fun nativeVodRejectsDeclaredProtectionOnClearTracksBeforeReadingMedia() = runBlocking {
        val original = network(2)
        val files = original.files.mapValues { (uri, bytes) -> if (uri.endsWith("video.m3u8"))
            bytes.toString(Charsets.UTF_8).replace("#EXT-X-MAP:", "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://not-a-key-request\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXT-X-MAP:").toByteArray() else bytes }
        val network = Network(files)
        val capture = F1HlsFeedCapture(network.factory)
        try { capture.prepareVod(feeds(2), 0, "channel-0"); fail("Protection cannot be discarded") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("declares protection")) }
        finally { capture.reset() }
        assertTrue(network.requested.none { it.endsWith(".m4s") || it.contains("not-a-key") })
    }

    @Test fun midGopProbeFailsBeforeReleaseInsteadOfAdvertisingAnUndecodableSeekPoint() = runBlocking {
        val network = network(2, samplesPerFragment = 10)
        val capture = F1HlsFeedCapture(network.factory)
        var released = false
        try {
            ArchiveCompositionStartup().start(preflight = { capture.prepareVod(feeds(2), 600, "channel-0") },
                releaseSources = { released = true }, replaceHost = { fail("Missing decoder reference pictures") },
                restoreSources = { fail("Sources must remain playing") }, dispose = { it.close() })
            fail("Expected random-access rejection")
        } catch (expected: java.io.IOException) { assertTrue(expected.message.orEmpty().contains("mid-GOP")) }
        finally { capture.reset() }
        assertFalse(released)
        assertEquals(2, network.requested.count { it.endsWith("s1.m4s") })
    }

    @Test fun containerSyncFlagCannotTurnAPictureWithReferencesIntoAnIrap() = runBlocking {
        val capture = F1HlsFeedCapture(network(2, samplesPerFragment = 10, forceSyncFlags = true).factory)
        try { capture.prepareVod(feeds(2), 600, "channel-0"); fail("An IRAP NAL must be verified") }
        catch (expected: ArchiveVodMediaException) {
            assertFalse(expected.retryable)
            assertTrue(expected.reason, expected.reason.contains("IRAP"))
        } finally { capture.reset() }
    }

    @Test fun permanentCompositionFailureIsNotRetriedButTransientReadFailureIs() {
        val policy = ArchiveVodLoadErrorPolicy()
        fun info(retryable: Boolean) = androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo(
            androidx.media3.exoplayer.source.LoadEventInfo(1, DataSpec(Uri.parse("apexview-archive:///s-0.m4s")), 0),
            androidx.media3.exoplayer.source.MediaLoadData(androidx.media3.common.C.DATA_TYPE_MEDIA),
            ArchiveVodMediaException("Safe diagnostic", retryable), 2)
        assertEquals(androidx.media3.common.C.TIME_UNSET, policy.getRetryDelayMsFor(info(false)))
        assertTrue(policy.getRetryDelayMsFor(info(true)) >= 0)
    }

    @Test fun nativeVodExposesFiniteResourcesAndCannotReadAfterClose() = runBlocking {
        val capture = F1HlsFeedCapture(network(2).factory)
        val session = capture.prepareVod(feeds(2), 0, "channel-0")
        val manifest = session.manifest()
        val source = session.dataSourceFactory().createDataSource()
        try {
            assertEquals(16L, source.open(DataSpec.Builder().setUri(HevcArchiveVodSession.MANIFEST_URI).setPosition(5).setLength(16).build()))
            val bytes = ByteArray(16)
            assertEquals(16, source.read(bytes, 0, bytes.size))
            assertArrayEquals(manifest.copyOfRange(5, 21), bytes)
            assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT, source.read(bytes, 0, bytes.size))
        } finally { source.close(); session.close(); capture.reset() }
        try { session.initialization(); fail("Closed session must not expose retained media") } catch (_: java.io.IOException) {}
    }

    @Test fun nativeVodOutputIsStagedForIndependentFfmpegPixelComparison() = runBlocking {
        for (count in 2..4) {
            val capture = F1HlsFeedCapture(network(count).factory)
            val session = capture.prepareVod(feeds(count), 0, "channel-0")
            try {
                val evidence = System.getenv("APEXVIEW_HEVC_OUTPUT_PARAMETER_DIR")?.let(::File)
                if (evidence != null) {
                    evidence.mkdirs()
                    File(evidence, "native_vod_${count}_feeds.mp4").outputStream().use { output ->
                        output.write(session.initialization())
                        for (number in 0 until session.segmentCount) output.write(session.segment(number))
                    }
                    File(evidence, "native_vod_${count}_feeds.mpd").writeBytes(session.manifest())
                }
                assertEquals(960 * count, session.width)
                assertEquals(540, session.height)
            } finally { session.close(); capture.reset() }
        }
    }

    @Test fun tiledSourcePreservesEveryTileOfBothFullFeeds() = runBlocking {
        val tileDir = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")?.let(::File)
        assumeTrue("Tiled VOD fixture is not configured", tileDir != null)
        val source = File(tileDir!!, "source_vod_tiled_2x2.hevc")
        assertTrue(source.isFile)
        val capture = F1HlsFeedCapture(network(2, samplesPerFragment = 50, annexBFile = source).factory)
        val session = capture.prepareVod(feeds(2), 0, "channel-0")
        try {
            val report = CmafBoxInspector.inspect(session.initialization())
            val config = report.tracks.single().hevcConfiguration!!
            assertEquals(4, config.pps.single().numTileColumns)
            assertEquals(2, config.pps.single().numTileRows)
            assertEquals(512, session.width)
            assertEquals(128, session.height)
            val evidence = System.getenv("APEXVIEW_HEVC_OUTPUT_PARAMETER_DIR")?.let(::File)
            if (evidence != null) {
                File(evidence, "native_vod_full_tiled_feeds.mp4").outputStream().use { output ->
                    output.write(session.initialization())
                    for (number in 0 until session.segmentCount) output.write(session.segment(number))
                }
            }
        } finally { session.close(); capture.reset() }
    }

    @Test fun fiveTiledSourcesProduceOneFiniteTrackWithoutDiscardingCameraTiles() = runBlocking {
        val tileDir = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")?.let(::File)
        assumeTrue("Tiled VOD fixture is not configured", tileDir != null)
        val source = File(tileDir!!, "source_vod_tiled_2x2.hevc")
        val capture = F1HlsFeedCapture(network(5, samplesPerFragment = 50, annexBFile = source).factory)
        val session = capture.prepareVod(feeds(5), 0, "channel-0")
        try {
            val report = CmafBoxInspector.inspect(session.initialization())
            val config = report.tracks.single().hevcConfiguration!!
            assertEquals(10, config.pps.single().numTileColumns)
            assertEquals(2, config.pps.single().numTileRows)
            assertEquals(1280, session.width)
            assertEquals(128, session.height)
            val evidence = System.getenv("APEXVIEW_HEVC_OUTPUT_PARAMETER_DIR")?.let(::File)
            if (evidence != null) File(evidence, "native_vod_five_tiled_feeds.mp4").outputStream().use { output ->
                output.write(session.initialization())
                for (number in 0 until session.segmentCount) output.write(session.segment(number))
            }
        } finally { session.close(); capture.reset() }
    }

    @Test fun fiveTiledSourcesCanComposeAcrossMultipleEncodedRows() = runBlocking {
        val tileDir = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")?.let(::File)
        assumeTrue("Tiled VOD fixture is not configured", tileDir != null)
        val source = File(tileDir!!, "source_vod_tiled_2x2.hevc")
        val capture = F1HlsFeedCapture(network(5, samplesPerFragment = 50, annexBFile = source).factory,
            maximumOutputWidth = 1024)
        val session = capture.prepareVod(feeds(5), 0, "channel-0")
        try {
            assertEquals(2, session.atlasColumns)
            assertEquals(3, session.atlasRows)
            assertEquals(512, session.width)
            assertEquals(384, session.height)
            val report = CmafBoxInspector.inspect(session.initialization())
            assertEquals(1, report.tracks.size)
            assertEquals(4, report.tracks.single().hevcConfiguration!!.pps.single().numTileColumns)
            assertEquals(6, report.tracks.single().hevcConfiguration!!.pps.single().numTileRows)
            val evidence = System.getenv("APEXVIEW_HEVC_OUTPUT_PARAMETER_DIR")?.let(::File)
            if (evidence != null) File(evidence, "native_vod_five_tiled_rows.mp4").outputStream().use { output ->
                output.write(session.initialization())
                for (number in 0 until session.segmentCount) output.write(session.segment(number))
            }
        } finally { session.close(); capture.reset() }
    }

    @Test fun castReceiverPairsBeforeServingOnlyTheAuthorizedSceneResources() = runBlocking {
        val network = network(2)
        val capture = F1HlsFeedCapture(network.factory)
        val session = capture.prepareVod(feeds(2), 0, "channel-0")
        val relay = app.apexview.cast.SceneCastRelay(org.robolectric.RuntimeEnvironment.getApplication(), session,
            CmafHttpReader(network.factory), "127.0.0.1")
        try {
            assertEquals(404, relayRequest(relay.receiverUrl + "s/guessed/init.mp4").first)
            val paired = relayRequest(relay.receiverUrl + "pair", relay.pairingCode)
            assertEquals(200, paired.first)
            val prefix = paired.second.toString(Charsets.UTF_8).removeSuffix("receiver.html")
            relay.publish(app.apexview.scene.VideoScene(1920f, 1080f, session.width, session.height, 2,
                listOf(app.apexview.scene.VideoWindow(1, 1, 50f, 80f, 960f, 540f, 3f))), 600, true, 1f, false, 1, 0)
            val scene = relayRequest(relay.receiverUrl.removeSuffix("/") + prefix + "scene.json")
            assertEquals(200, scene.first)
            val json = org.json.JSONObject(scene.second.toString(Charsets.UTF_8))
            assertEquals(1, json.getJSONArray("windows").getJSONObject(0).getInt("source"))
            assertFalse(scene.second.toString(Charsets.UTF_8).contains("media.test"))
            val init = relayRequest(relay.receiverUrl.removeSuffix("/") + prefix + "init.mp4")
            assertArrayEquals(session.initialization(), init.second)
            assertEquals(404, relayRequest(relay.receiverUrl.removeSuffix("/") + prefix + "http://unlisted.test/token").first)
        } finally { relay.close(); session.close(); capture.reset() }
    }

    @Test fun castPairingLimitsIncorrectAttempts() = runBlocking {
        val network = network(2)
        val capture = F1HlsFeedCapture(network.factory)
        val session = capture.prepareVod(feeds(2), 0, "channel-0")
        val relay = app.apexview.cast.SceneCastRelay(org.robolectric.RuntimeEnvironment.getApplication(), session,
            CmafHttpReader(network.factory), "127.0.0.1")
        try {
            repeat(5) { assertEquals(403, relayRequest(relay.receiverUrl + "pair", "000000").first) }
            assertEquals(429, relayRequest(relay.receiverUrl + "pair", relay.pairingCode).first)
        } finally { relay.close(); session.close(); capture.reset() }
    }

    @Test fun castLeaseKeepsTheEncodedSourceAliveUntilTheLastConsumerStops() = runBlocking {
        val capture = F1HlsFeedCapture(network(2).factory)
        val session = capture.prepareVod(feeds(2), 0, "channel-0")
        val lease = session.acquireLease()
        session.close(); session.close()
        try { assertTrue(session.initialization().isNotEmpty()) }
        finally { lease.close(); lease.close(); capture.reset() }
        try { session.initialization(); fail("The final receiver release must close the source") }
        catch (_: java.io.IOException) {}
    }

    @Test fun castHlsIndexIsFiniteAndItsCodecStringIsParsedByMedia3() = runBlocking {
        val capture = F1HlsFeedCapture(network(2).factory)
        val session = capture.prepareVod(feeds(2), 1100, "channel-0")
        try {
            val playlist = androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser().parse(Uri.parse("http://receiver.test/video.m3u8"),
                session.hlsPlaylist().byteInputStream()) as androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
            assertTrue(playlist.hasEndTag)
            assertEquals(2, playlist.segments.size)
            assertEquals(2_000_000L, playlist.durationUs)
            val format = androidx.media3.common.Format.Builder().setSampleMimeType("video/hevc").setCodecs(session.codecString()).build()
            assertNotNull(androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getCodecProfileAndLevel(format))
            assertFalse(session.hlsPlaylist().contains("media.test"))
        } finally { session.close(); capture.reset() }
    }

    @Test fun oneCameraCastKeepsTheOriginalClearCmafBytesAndDoesNotInventAnotherFeed() = runBlocking {
        val network = network(1)
        val capture = F1HlsFeedCapture(network.factory)
        val session = capture.prepareVod(feeds(1), 0, "channel-0")
        try {
            assertEquals(listOf("channel-0"), session.sourceLabels)
            assertEquals(960, session.width)
            assertArrayEquals(network.files.getValue("https://media.test/f0/s0.m4s"), session.segment(0))
            assertArrayEquals(network.files.getValue("https://media.test/f0/init.mp4"), session.initialization())
        } finally { session.close(); capture.reset() }
    }

    private fun relayRequest(address: String, body: String? = null): Pair<Int, ByteArray> {
        val uri = java.net.URI(address)
        return java.net.Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 10_000
            val bytes = body?.toByteArray() ?: byteArrayOf()
            socket.getOutputStream().write(((if (body == null) "GET" else "POST") + " ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray() + bytes)
            val response = socket.getInputStream().readBytes()
            val separator = "\r\n\r\n".toByteArray()
            val at = (0..response.size - separator.size).first { index -> separator.indices.all { response[index + it] == separator[it] } }
            val code = response.copyOfRange(0, at).toString(Charsets.US_ASCII).lineSequence().first().split(' ')[1].toInt()
            code to response.copyOfRange(at + 4, response.size)
        }
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

    private fun network(count: Int, decodeOffsetTicks: Long = 0, samplesPerFragment: Int = 25, annexBFile: File? = null, forceSyncFlags: Boolean = false): Network {
        val dir = System.getenv("APEXVIEW_HEVC_FIXTURE_DIR")?.let(::File)
        assumeTrue("Independent HEVC source fixtures are not configured", dir != null)
        val files = mutableMapOf<String, ByteArray>()
        for (i in 0 until count) {
            val nals = HevcBitstream.splitAnnexB((annexBFile ?: File(dir!!, if (i % 2 == 0) "feed_a.hevc" else "feed_b.hevc")).readBytes())
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
                    if (forceSyncFlags || HevcBitstream.parseNalHeader(picture.first()).type in 16..23) 0x02000000L else 0x01010000L)
            }
            val sourceSps = HevcParameterSets.parseSps(nals.first { it.type == 33 }.bytes)
            files["https://media.test/f$i/master.m3u8"] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,CODECS=\"hvc1.1.6.L93.B0\",RESOLUTION=${sourceSps.displayWidth}x${sourceSps.displayHeight}\nvideo.m3u8\n".toByteArray()
            val chunks = samples.chunked(samplesPerFragment)
            files["https://media.test/f$i/video.m3u8"] = ("#EXTM3U\n#EXT-X-TARGETDURATION:${kotlin.math.ceil(samplesPerFragment * 0.04).toInt()}\n#EXT-X-MAP:URI=\"init.mp4\"\n" +
                chunks.mapIndexed { index, chunk -> "#EXTINF:${chunk.size * 0.04},\ns$index.m4s\n" }.joinToString("") + "#EXT-X-ENDLIST\n").toByteArray()
            files["https://media.test/f$i/init.mp4"] = init
            chunks.forEachIndexed { index, chunk ->
                files["https://media.test/f$i/s$index.m4s"] = HevcClearCmafFragmentWriter.write(i + 1L,
                    index + 1L, decodeOffsetTicks + index * samplesPerFragment * 3600L, chunk, false, false)
            }
        }
        return Network(files)
    }
}
