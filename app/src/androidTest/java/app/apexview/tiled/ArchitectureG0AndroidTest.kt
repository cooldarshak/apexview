package app.apexview.tiled

import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly run only on G.0 debug APKs with the native diagnostic packaged. */
@RunWith(AndroidJUnit4::class)
class ArchitectureG0AndroidTest {
    @Test
    fun libraryLoadsAndRejectsInvalidBuffers() {
        assertEquals(2, ArchitectureG0Probe.loadAndVersion())
        assertTrue(ArchitectureG0Probe.rejectsBadBuffers())
        ArchitectureG0Probe.Session(32).use { s ->
            val g = s.generation()
            assertFalse(s.load(g, ByteArray(33)))
            assertTrue(s.load(g, byteArrayOf(2, 2, 1)))
            assertEquals(6, s.convertLoaded(g, 1))
            val out = ByteArray(6)
            assertEquals(6, s.copyOutput(g, out))
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 2, 1), out)
            assertTrue(s.load(g, byteArrayOf(2, 2, 1, 3, 2, 1)))
            assertEquals(ArchitectureG0Probe.INVALID, s.convertLoaded(g, 1))
            assertEquals(ArchitectureG0Probe.INVALID, s.copyOutput(g, out))
        }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val version = ctx.packageManager.getPackageInfo(ctx.packageName,0).versionName
        Log.i(TAG, "G0 runtime sdk=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString()} process64Bit=${android.os.Process.is64Bit()} pageBytes=${Os.sysconf(OsConstants._SC_PAGESIZE)} package=${ctx.packageName} version=$version")
    }

    @Test
    fun concurrentInvalidationAndCloseNeverExposeStaleOutput() {
        val s = ArchitectureG0Probe.Session(64)
        val old = s.generation()
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val worker = executor.submit<Boolean> {
                check(go.await(5, TimeUnit.SECONDS))
                repeat(200) {
                    if (s.load(old, byteArrayOf(2, 2, 1))) {
                        val result = s.convertLoaded(old, 1)
                        check(result == 6 || result == ArchitectureG0Probe.STALE)
                        val read = s.copyOutput(old, ByteArray(6))
                        check(read == 6 || read == ArchitectureG0Probe.STALE)
                    }
                }
                true
            }
            go.countDown()
            val current = s.invalidate()
            assertNotEquals(old, current)
            assertFalse(s.load(old, byteArrayOf(2, 2, 1)))
            assertEquals(ArchitectureG0Probe.STALE, s.convertLoaded(old, 1))
            assertEquals(ArchitectureG0Probe.STALE, s.copyOutput(old, ByteArray(6)))
            assertTrue(s.load(current, byteArrayOf(2, 2, 1)))
            assertEquals(6, s.convertLoaded(current, 1))
            s.close()
            s.close()
            assertEquals(ArchitectureG0Probe.STALE, s.convertLoaded(current, 1))
            assertTrue(worker.get(10, TimeUnit.SECONDS))
        } finally {
            s.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun realEncodedClearFixturesMatchJvmAndReportRuntimeTiming() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val names = listOf("source_tiled.hevc", "feed_left.hevc", "feed_right.hevc",
            "source_tiled_2x2.hevc", "feed_tile_0.hevc", "feed_tile_1.hevc",
            "feed_tile_2.hevc", "feed_tile_3.hevc")
        for (name in names) {
            val source = assets.open(name).use { it.readBytes() }
            val groups = HevcBitstream.splitAnnexB(source).map { it.bytes }.chunked(8)
            assertTrue("empty real fixture: $name", groups.isNotEmpty())
            val digest = MessageDigest.getInstance("SHA-256").digest(source)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            // Budget derived from actual fixture sizes; no persistent native allocation.
            val capacity = groups.maxOf { group -> group.sumOf { it.size + 4 } }
            var verified = 0
            ArchitectureG0Probe.Session(capacity).use { s ->
                val g = s.generation()
                for ((index, group) in groups.withIndex()) {
                    for (prefix in 1..4) {
                        val maximum = if (prefix == 4) Int.MAX_VALUE else (1 shl (prefix * 8)) - 1
                        if (group.any { it.size > maximum }) continue
                        val input = lengthPrefixed(group, prefix)
                        val expected = ByteArrayOutputStream().apply {
                            group.forEach { write(byteArrayOf(0, 0, 0, 1)); write(it) }
                        }.toByteArray()
                        assertEquals(expected.size, HevcClearCmafGridComposer.annexBSizeForSample(
                            input, 0, input.size, prefix))
                        assertTrue(s.load(g, input))
                        assertEquals(expected.size, s.convertLoaded(g, prefix))
                        val actual = ByteArray(expected.size)
                        assertEquals(expected.size, s.copyOutput(g, actual))
                        assertArrayEquals("$name group=$index prefix=$prefix", expected, actual)
                        verified++
                        if (index == 0 && prefix == 4) {
                            val jvmOutput = ByteArray(expected.size)
                            val jvm = measure { check(jvmConvert(input, prefix, jvmOutput) == expected.size) }
                            val rust = measure { check(s.convertLoaded(g, prefix) == expected.size) }
                            assertArrayEquals(expected, jvmOutput)
                            assertEquals(expected.size, s.copyOutput(g, actual))
                            assertArrayEquals(expected, actual)
                            Log.i(TAG, "G0 fixture=$name sha256=$digest bytes=${input.size} prefix=$prefix " +
                                "jvmP50Ns=${jvm[25]} jvmP95Ns=${jvm[48]} jvmMaxNs=${jvm[50]} " +
                                "rustP50Ns=${rust[25]} rustP95Ns=${rust[48]} rustMaxNs=${rust[50]} " +
                                "loadAndCopyExcluded=true")
                        }
                    }
                }
            }
            assertTrue(verified >= groups.size)
            Log.i(TAG, "G0 equivalence fixture=$name sha256=$digest groups=${groups.size} checked=$verified")
        }
    }

    @Test
    fun batchedClearConversionIncludesPublicationCopyCosts() {
        val asset = InstrumentationRegistry.getInstrumentation().context.assets
            .open("source_tiled.hevc").use { it.readBytes() }
        val group = HevcBitstream.splitAnnexB(asset).map { it.bytes }.take(8)
        val sample = lengthPrefixed(group, 4)
        val expected = ByteArray(sample.size)
        assertEquals(expected.size, jvmConvert(sample, 4, expected))
        for (count in listOf(1, 4, 16, 64)) {
            val input = ByteArray(sample.size * count)
            val reference = ByteArray(expected.size * count)
            repeat(count) { index ->
                sample.copyInto(input, index * sample.size)
                expected.copyInto(reference, index * expected.size)
            }
            val nativeOutput = ByteArray(reference.size)
            val jvmOutput = ByteArray(expected.size)
            val jvmBatch = ByteArray(reference.size)
            ArchitectureG0Probe.Session(input.size).use { s ->
                val g = s.generation()
                assertTrue(s.load(g, input))
                assertEquals(reference.size, s.convertBatchLoaded(g, 4, count))
                assertEquals(reference.size, s.copyOutput(g, nativeOutput))
                assertArrayEquals(reference, nativeOutput)
                // Malformed later sample must invalidate publication.
                val bad = input.copyOf()
                bad[(count - 1) * sample.size + 4] = 0x80.toByte()
                assertTrue(s.load(g, bad))
                assertEquals(ArchitectureG0Probe.INVALID, s.convertBatchLoaded(g, 4, count))
                assertEquals(ArchitectureG0Probe.INVALID, s.copyOutput(g, nativeOutput))
                assertTrue(s.load(g, input))
                val jvmWork = {
                    repeat(count) { index ->
                        check(jvmConvert(sample, 4, jvmOutput) == expected.size)
                        jvmOutput.copyInto(jvmBatch, index * expected.size)
                    }
                }
                val nativeWork = {
                    check(s.convertBatchLoaded(g, 4, count) == reference.size)
                }
                val copyWork = {
                    check(s.load(g, input))
                    check(s.convertBatchLoaded(g, 4, count) == reference.size)
                    check(s.copyOutput(g, nativeOutput) == reference.size)
                }
                val singleSession = ArchitectureG0Probe.Session(sample.size)
                singleSession.use { single ->
                    val sg = single.generation()
                    val singleOut = ByteArray(expected.size)
                    val unbatched = {
                        repeat(count) { index ->
                            check(single.load(sg, sample))
                            check(single.convertLoaded(sg, 4) == expected.size)
                            check(single.copyOutput(sg, singleOut) == expected.size)
                            singleOut.copyInto(nativeOutput, index * expected.size)
                        }
                    }
                    // Alternate case order between sample counts to reduce warmup bias.
                    val measurements = if (count == 1 || count == 16) {
                        listOf(measureBatch(jvmWork), measureBatch(nativeWork), measureBatch(copyWork), measureBatch(unbatched))
                    } else {
                        val u = measureBatch(unbatched); val c = measureBatch(copyWork)
                        val n = measureBatch(nativeWork); val k = measureBatch(jvmWork)
                        listOf(k,n,c,u)
                    }
                    assertArrayEquals(reference, nativeOutput)
                    assertArrayEquals(reference, jvmBatch)
                    val labels = listOf("jvmArray", "rustComputeOnly", "rustCopyInclusive", "rustUnbatchedCopyInclusive")
                    for ((i, timings) in measurements.withIndex()) {
                        Log.i(TAG, "G0 batch sdk=${Build.VERSION.SDK_INT} count=$count bytes=${input.size} " +
                            "case=${labels[i]} p50Ns=${timings[15]} p95Ns=${timings[29]} maxNs=${timings[30]} " +
                            "jniCalls=${if (i == 0) 0 else if (i == 3) count else 1} " +
                            "bridgeCopyBytes=${if (i >= 2) input.size.toLong() * 2 else 0}")
                    }
                }
            }
        }
    }

    private fun measureBatch(block: () -> Unit): List<Long> {
        repeat(32) { block() }
        return LongArray(31) {
            val start = System.nanoTime()
            block()
            System.nanoTime() - start
        }.sorted()
    }

    private fun lengthPrefixed(group: List<ByteArray>, prefix: Int): ByteArray {
        val output = ByteBuffer.allocate(group.sumOf { it.size + prefix })
        for (nal in group) {
            for (shift in prefix - 1 downTo 0) output.put((nal.size ushr (shift * 8)).toByte())
            output.put(nal)
        }
        return output.array()
    }

    // Test-only independently written byte-array conversion control. It validates
    // every length/header even for 4-byte prefixes, as does the Rust converter.
    private fun jvmConvert(input: ByteArray, prefix: Int, output: ByteArray): Int {
        fun walk(write: Boolean): Int {
            var pos = 0
            var out = 0
            var count = 0
            while (pos < input.size) {
                require(input.size - pos >= prefix)
                var length = 0L
                repeat(prefix) { length = (length shl 8) or (input[pos++].toLong() and 255L) }
                require(length >= 2 && length <= input.size - pos)
                val n = length.toInt()
                HevcBitstream.readNalHeader(input, pos, n)
                require(++count <= 4096 && output.size - out >= 4 + n)
                if (write) {
                    output[out] = 0; output[out+1] = 0; output[out+2] = 0; output[out+3] = 1
                    input.copyInto(output, out+4, pos, pos+n)
                }
                out += 4 + n
                pos += n
            }
            return out
        }
        check(walk(false) == output.size)
        return walk(true)
    }

    private fun measure(block: () -> Unit): List<Long> {
        repeat(20) { repeat(16) { block() } }
        return LongArray(51) {
            val start = System.nanoTime()
            repeat(16) { block() }
            (System.nanoTime() - start) / 16
        }.sorted()
    }

    companion object { private const val TAG = "PitwallG0" }
}
