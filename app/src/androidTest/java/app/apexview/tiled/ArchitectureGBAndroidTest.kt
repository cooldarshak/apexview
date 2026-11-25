package app.apexview.tiled

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.InterruptedIOException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchitectureGBAndroidTest {
    @Test
    fun stitchedTiledFragmentUsesNativeWorkerAndGenerationFencedLease() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val input = assets.open("gb_clear_job.bin").use { it.readBytes() }
        val expected = assets.open("gb_clear_expected.bin").use { it.readBytes() }
        val budget = requireNotNull(NativeStitchingEngine.plan(
            true, 256L * 1024 * 1024, 32L * 1024 * 1024, 32L * 1024 * 1024,
            input.size, expected.size,
        ))
        NativeStitchingEngine.open(budget).use { engine ->
            val generation = engine.generation()
            val token = engine.submitClearAtlas(generation, input, true, 5_000)
            assertTrue("Clear atlas submission failed: $token", token > 0)
            assertEquals(expected.size.toLong(), await(engine, token))
            val actual = ByteArray(expected.size)
            assertEquals(expected.size.toLong(), engine.read(token, 0, actual, 0, actual.size))
            assertArrayEquals(expected, actual)
            assertEquals(generation + 1, engine.seek())
            assertEquals(NativeStitchingEngine.STALE, engine.read(token, 0, actual, 0, actual.size))
            assertEquals(1L, engine.release(token))

            val malformed = input.copyOf(input.size - 1)
            val rejected = engine.submitClearAtlas(engine.generation(), malformed, true, 5_000)
            assertTrue(rejected > 0)
            assertEquals(NativeStitchingEngine.INVALID, await(engine, rejected))
            assertEquals(1L, engine.release(rejected))
            Log.i("PitwallGB", "GB clear tiled worker byte parity and lease fencing PASS")
        }
        verifyArchiveBridge(input, expected)
    }

    /** Use the existing independently encoded, exact JVM/Rust fixture, not
     * a mocked native result. Keep the original worker/seek assertions above. */
    private fun verifyArchiveBridge(input: ByteArray, expected: ByteArray) {
        val packet = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4); packet.get(magic)
        assertArrayEquals(byteArrayOf(0x47, 0x42, 0x43, 0x31), magic)
        val track = packet.int.toLong() and 0xffffffffL
        val sequence = packet.int.toLong() and 0xffffffffL
        val width = packet.int; val height = packet.int
        val numerator = packet.int; val denominator = packet.int
        val feeds = packet.short.toInt() and 0xffff
        val columns = packet.short.toInt() and 0xffff
        val rows = packet.short.toInt() and 0xffff
        assertEquals(0, packet.short.toInt())
        val colWidths = List(columns) { packet.int }
        val rowHeights = List(rows) { packet.int }
        val cells = List(columns * rows) { i ->
            HevcTizenGridComposer.CellSelection(packet.short.toInt() and 0xffff,
                packet.short.toInt() and 0xffff, i % columns, i / columns)
        }
        val inits = mutableListOf<ByteArray>()
        val media = mutableListOf<ByteArray>()
        repeat(feeds) {
            val initSize = packet.int; val mediaSize = packet.int
            inits += ByteArray(initSize).also { packet.get(it) }
            media += ByteArray(mediaSize).also { packet.get(it) }
        }
        assertFalse(packet.hasRemaining())
        val geometry = NativeClearArchiveJob.Geometry(width, height, colWidths, rowHeights, cells)
        assertArrayEquals(input, NativeClearArchiveJob.encode(track, sequence, numerator, denominator,
            geometry, inits, media).bytes)
        val sources = inits.indices.map { i ->
            val report = CmafBoxInspector.inspect(inits[i])
            HevcClearCmafGridComposer.Feed(inits[i], listOf(media[i]), "fixture-$i", report,
                listOf(HevcClearCmafGridComposer.InspectedMedia.inspect(media[i], report)))
        }
        val request = HevcClearCmafGridComposer.Request(sources, HevcOutputGridDimensions(columns, rows),
            cells, track, sequence, numerator, denominator)
        val expectedBuffer = ByteBuffer.wrap(expected).order(ByteOrder.BIG_ENDIAN)
        val initSize = expectedBuffer.int; val mediaSize = expectedBuffer.int
        val expectedInit = ByteArray(initSize).also { expectedBuffer.get(it) }
        val expectedMedia = ByteArray(mediaSize).also { expectedBuffer.get(it) }
        assertFalse(expectedBuffer.hasRemaining())
        verifyProtectedMetadata(expectedInit)
        verifyClearVariant(request, geometry, inits, media, expectedInit, expectedMedia)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var references = 0
        fun reference(): HevcClearCmafGridComposer.Output {
            references++
            return HevcClearCmafGridComposer.compose(request)
        }
        fun verify(out: NativeClearArchiveBridge.Produced) {
            assertArrayEquals(expectedInit, out.output.initializationSegment)
            assertArrayEquals(expectedMedia, out.output.mediaFragments.single())
            out.output.initializationSegment.fill(0)
            out.output.mediaFragments.single().fill(0)
        }

        NativeClearArchiveBridge { _, i, o ->
            val budget = requireNotNull(NativeStitchingEngine.planClearAtlas(true,
                256L * 1024 * 1024, 32L * 1024 * 1024, 32L * 1024 * 1024, i, o))
            NativeStitchingEngine.open(budget)
        }.use { bridge ->
            bridge.enable(context)
            val first = bridge.compose(request, expectedInit, true, 5_000, ::reference)
            assertTrue(first.nativeEpoch != null)
            assertEquals(NativeStitchingSelection.Route.JVM, bridge.decision.actual)
            bridge.publish(first.nativeEpoch) { verify(first) }
            assertEquals(NativeStitchingSelection.Route.RUST, bridge.decision.actual)
            assertEquals(1, references)
            val second = bridge.compose(request, expectedInit, true, 5_000, ::reference)
            assertTrue(second.nativeEpoch != null)
            assertEquals(1, references)
            bridge.invalidateForSeek()
            var stalePublished = false
            try {
                bridge.publish(second.nativeEpoch) { stalePublished = true }
                throw AssertionError("Stale native publication was accepted")
            } catch (_: InterruptedIOException) { assertFalse(stalePublished) }
            verify(second)
            assertEquals(NativeStitchingSelection.Route.JVM, bridge.decision.actual)
            val next = bridge.compose(request, expectedInit, true, 5_000, ::reference)
            assertTrue(next.nativeEpoch != null)
            bridge.publish(next.nativeEpoch) { verify(next) }
            assertEquals(2, references)
            assertEquals(NativeStitchingSelection.Route.RUST, bridge.decision.actual)
            // A later invalid native command must not hide an error in the
            // reference path or silently leave the engine marked active.
            try {
                bridge.compose(request.copy(firstOutputSequenceNumber = 0), expectedInit, true, 5_000) {
                    HevcClearCmafGridComposer.compose(request.copy(firstOutputSequenceNumber = 0))
                }
                throw AssertionError("Invalid sequence was accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(NativeStitchingSelection.Route.JVM, bridge.decision.actual)
            assertTrue(bridge.decision.requested)
            assertFalse(bridge.candidate)
            val fallback = bridge.compose(request, expectedInit, true, 5_000, ::reference)
            assertNull(fallback.nativeEpoch)
            verify(fallback)
            assertEquals(3, references)
        }
        var attempts = 0
        NativeClearArchiveBridge { _, _, _ ->
            attempts++
            throw IllegalStateException("Test bounded admission rejection")
        }.use { bridge ->
            bridge.enable(context)
            repeat(2) {
                val fallback = bridge.compose(request, expectedInit, true, 5_000, ::reference)
                assertNull(fallback.nativeEpoch)
                verify(fallback)
            }
            assertEquals(1, attempts)
            assertEquals(NativeStitchingSelection.Route.JVM, bridge.decision.actual)
            assertEquals("NATIVE_CLEAR_MEMORY_OR_ENGINE_ADMISSION", bridge.decision.reason)
            assertTrue(bridge.decision.requested)
        }
        Log.i("PitwallGB", "GB archive route exact parity, native-only next fragment, seek publication fence and explicit fallback PASS")
    }

    /** Same actual JVM composer and native worker, but the second source has
     * three-byte lengths, exact in-band sets, AUD/SEI and terminal padding. */
    private fun verifyClearVariant(
        request: HevcClearCmafGridComposer.Request, geometry: NativeClearArchiveJob.Geometry,
        inits: List<ByteArray>, media: List<ByteArray>, expectedInit: ByteArray, expectedMedia: ByteArray,
    ) {
        val init = inits[1].copyOf()
        val hvcc = findType(init, "hvcC") + 4
        init[hvcc + 21] = ((init[hvcc + 21].toInt() and 0xfc) or 2).toByte()
        val report = CmafBoxInspector.inspect(init)
        assertTrue(report.errors.isEmpty())
        val track = report.tracks.single()
        val config = requireNotNull(track.hevcConfiguration)
        assertEquals(3, config.nalLengthSize)
        val source = media[1]
        val previous = request.feeds[1].validatedInitialization!!
        val fragment = CmafBoxInspector.inspect(source, previous).fragments.single()
        val data = ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN)
        val moofSize = data.int
        data.position(moofSize + 8)
        val samples = fragment.sampleTimings.mapIndexed { index, timing ->
            val end = data.position() + requireNotNull(timing.sampleSizeBytes).toInt()
            val nals = mutableListOf<ByteArray>()
            if (index == 0) {
                nals += config.rawVpsNals + config.rawSpsNals + config.rawPpsNals
                nals += byteArrayOf(0x46, 0x01, 0x50) // AUD
            }
            while (data.position() < end) {
                val length = data.int
                require(length in 2..(end - data.position()))
                val nal = ByteArray(length).also { data.get(it) }
                nals += if (index == 0 && nals.size == 4) nal + byteArrayOf(0, 0) else nal
            }
            assertEquals(end, data.position())
            if (index == 0) nals += byteArrayOf(0x50, 0x01, 0x80.toByte()) // suffix SEI
            HevcClearCmafFragmentWriter.NalSample(nals, requireNotNull(timing.durationTicks),
                requireNotNull(timing.compositionOffsetTicks), requireNotNull(timing.sampleFlags))
        }
        assertEquals(source.size, data.position())
        fun fragmentFor(samples: List<HevcClearCmafFragmentWriter.NalSample>) =
            HevcClearCmafFragmentWriter.writeNalSamples(requireNotNull(track.trackId), 1,
                requireNotNull(fragment.baseDecodeTimeTicks), samples, 3, false, false)
        val variant = fragmentFor(samples)
        val variantFeed = HevcClearCmafGridComposer.Feed(init, listOf(variant), "variant",
            report, listOf(HevcClearCmafGridComposer.InspectedMedia.inspect(variant, report)))
        val variantRequest = request.copy(feeds = request.feeds.toMutableList().apply { set(1, variantFeed) })
        val reference = HevcClearCmafGridComposer.compose(variantRequest)
        assertArrayEquals(expectedInit, reference.initializationSegment)
        assertArrayEquals(expectedMedia, reference.mediaFragments.single())
        val variantInits = inits.toMutableList().apply { set(1, init) }
        val variantMedia = media.toMutableList().apply { set(1, variant) }
        fun encode(m: List<ByteArray>) = NativeClearArchiveJob.encode(request.outputTrackId,
            request.firstOutputSequenceNumber, request.frameRateNumerator, request.frameRateDenominator,
            geometry, variantInits, m).bytes
        val packet = encode(variantMedia)
        val budget = requireNotNull(NativeStitchingEngine.planClearAtlas(true,
            256L * 1024 * 1024, 32L * 1024 * 1024, 32L * 1024 * 1024,
            packet.size, 8 + expectedInit.size + expectedMedia.size))
        NativeStitchingEngine.open(budget).use { engine ->
            val result = NativeClearArchiveJob.execute(engine, packet, true, 5_000) {}
            try {
                assertArrayEquals(reference.initializationSegment, result.initialization)
                assertArrayEquals(reference.mediaFragments.single(), result.media)
            } finally { result.wipe() }
            // Invalid in-band VPS identity must fail, including after the
            // worker has cached this exact initialization and geometry.
            val badSets = samples.first().nals.toMutableList()
            badSets[0] = badSets[0].copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            val bad = fragmentFor(samples.toMutableList().apply {
                set(0, first().copy(nals = badSets))
            })
            val badPacket = encode(variantMedia.toMutableList().apply { set(1, bad) })
            val token = engine.submitClearAtlas(engine.generation(), badPacket, true, 5_000)
            assertTrue(token > 0)
            assertEquals(NativeStitchingEngine.INVALID, await(engine, token))
            assertEquals(1L, engine.release(token))
        }
        reference.initializationSegment.fill(0)
        reference.mediaFragments.single().fill(0)
        Log.i("PitwallGB", "GB clear mixed NAL lengths, in-band identity, non-VCL, terminal padding and cached rejection PASS")
    }

    /** Synthetic CBCS metadata and opaque dummy payload only. No real protected
     * media, key, license or CDM enters this automated differential. */
    private fun verifyProtectedMetadata(clearInit: ByteArray) {
        val trackId = requireNotNull(CmafBoxInspector.inspect(clearInit).tracks.single().trackId)
        for (ivWidth in listOf(0, 8, 16)) {
            val init = syntheticProtectedInit(clearInit, ivWidth)
            val protection = requireNotNull(CmafBoxInspector.inspect(init).tracks.single().trackEncryption)
            assertEquals(ivWidth, protection.perSampleIvSize)
            for (dts in listOf(0xffffffffL, 0x100000000L)) {
                val samples = List(2) { index ->
                    val iv = if (ivWidth == 0) null else ByteArray(ivWidth) { (it + index + 1).toByte() }
                    ProtectedHevcCmafFragmentWriter.Sample(
                        sampleBytes = ByteArray(52) { (it + index + 1).toByte() },
                        subsampleRanges = listOf(CmafBoxInspector.SubsampleEncryptionRange(4, 16),
                            CmafBoxInspector.SubsampleEncryptionRange(0, 32)),
                        perSampleIvBytes = iv,
                        perSampleIvIdentity = iv?.let { ProtectedValueFingerprint.fromBytes("iv", it) },
                        durationTicks = 3600,
                        sampleFlags = 0x02000000,
                        compositionOffsetTicks = if (index == 0) -3 else 5,
                    )
                }
                val request = ProtectedHevcCmafFragmentWriter.Request(trackId, dts, 7, ivWidth, samples)
                val baseline = ProtectedHevcCmafFragmentWriter.write(init, request)
                var matched = false
                val candidate = ProtectedHevcCmafFragmentWriter.write(init, request,
                    preferNativeHeader = true, onNativeHeaderResult = { matched = it })
                assertTrue("Native protected metadata must not silently fall back", matched)
                assertArrayEquals(baseline, candidate)
                val moof = ByteBuffer.wrap(baseline).int
                val header = baseline.copyOfRange(0, moof)
                val auxiliarySizes = List(2) { ivWidth + 14 }
                val packet = NativeProtectedHeader.encode(request, auxiliarySizes)
                assertArrayEquals(header, requireNotNull(NativeStitchingEngine.protectedHeader(packet)))
                assertNull(NativeStitchingEngine.protectedHeader(packet.copyOf(packet.size - 1)))
                val corrupted = packet.copyOf().apply { ByteBuffer.wrap(this).putInt(32, 53) }
                assertNull(NativeStitchingEngine.protectedHeader(corrupted))
                assertNull(NativeProtectedHeader.matchingHeader(header, request, auxiliarySizes) { ByteArray(header.size) })
                assertNull(NativeProtectedHeader.matchingHeader(header, request, auxiliarySizes) { null })
                // The dummy opaque payload remains byte-for-byte identical
                // in the real JVM writer's final mdat, with no native access.
                assertArrayEquals(samples[0].sampleBytes + samples[1].sampleBytes,
                    candidate.copyOfRange(moof + 8, candidate.size))
                packet.fill(0); corrupted.fill(0); baseline.fill(0); candidate.fill(0)
            }
            ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(init, init.copyOf(),
                "synthetic metadata-only differential")
            init.fill(0)
        }
        Log.i("PitwallGB", "GB CBCS metadata-only native/JVM exact moof, IV modes, tfdt versions, opaque payload preservation and negative fallback PASS")
    }

    private fun syntheticProtectedInit(clear: ByteArray, ivWidth: Int): ByteArray {
        val entryStart = findType(clear, "stsd") + 12
        val entrySize = ByteBuffer.wrap(clear).getInt(entryStart)
        val end = entryStart + entrySize
        val constantIv = if (ivWidth == 0) byteArrayOf(16) + ByteArray(16) { 23 } else ByteArray(0)
        val tenc = box("tenc", byteArrayOf(1, 0, 0, 0, 0, 0x19, 1, ivWidth.toByte()) +
            ByteArray(16) { 35 } + constantIv)
        val sinf = box("sinf", box("frma", "hvc1".toByteArray(Charsets.US_ASCII)) +
            box("schm", byteArrayOf(0, 0, 0, 0) + "cbcs".toByteArray(Charsets.US_ASCII) + u32(0x00010000)) +
            box("schi", tenc))
        val altered = clear.copyOfRange(0, end) + sinf + clear.copyOfRange(end, clear.size)
        "encv".toByteArray(Charsets.US_ASCII).copyInto(altered, entryStart + 4)
        ByteBuffer.wrap(altered).putInt(entryStart, entrySize + sinf.size)
        for (name in listOf("moov", "trak", "mdia", "minf", "stbl", "stsd")) {
            val start = findType(clear, name) - 4
            ByteBuffer.wrap(altered).putInt(start, ByteBuffer.wrap(clear).getInt(start) + sinf.size)
        }
        return altered + box("pssh", byteArrayOf(0, 0, 0, 0) + ByteArray(16) + u32(0))
    }

    private fun findType(data: ByteArray, name: String): Int {
        val type = name.toByteArray(Charsets.US_ASCII)
        return (4..data.size - type.size).first { i -> type.indices.all { data[i + it] == type[it] } }
    }
    private fun u32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun box(type: String, data: ByteArray) =
        u32(data.size + 8) + type.toByteArray(Charsets.US_ASCII) + data

    private fun await(engine: NativeStitchingEngine, token: Long): Long {
        val stop = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < stop) {
            val result = engine.acquire(token)
            if (result != 0L) return result
            Thread.sleep(2)
        }
        throw AssertionError("Clear native atlas worker timeout")
    }
}
