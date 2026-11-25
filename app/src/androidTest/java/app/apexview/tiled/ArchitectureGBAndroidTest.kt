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
