package app.apexview.tiled

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcClearCmafFragmentWriterTest {
    @Test
    fun writesNalSlicesByteForByteLikeMaterializedSamplesWithoutAllocatingAnIntermediatePayload() {
        val nalsBySample = listOf(
            listOf(ByteArray(1024 * 1024 + 3) { (it * 19).toByte() }, byteArrayOf(0x26, 0x01)),
            listOf(byteArrayOf(0x02, 0x03, 0x04), ByteArray(170_003) { (it * 7).toByte() })
        )
        for (prefixSize in 1..4) {
            // Small samples exercise every permitted NAL prefix; the 4-byte profile
            // additionally proves that multi-megabyte HEVC slices are copied only once.
            val nals = if (prefixSize == 4) nalsBySample else nalsBySample.map { sample ->
                sample.map { it.copyOf(minOf(it.size, 57)) }
            }
            val nalSamples = nals.mapIndexed { index, units ->
                HevcClearCmafFragmentWriter.NalSample(units, 3600L, if (index == 0) 0L else -120L,
                    if (index == 0) 0x02000000L else 0x01010000L)
            }
            val direct = HevcClearCmafFragmentWriter.writeNalSamples(
                7, 12, 90_000, nalSamples, prefixSize, false, false)
            val materialized = HevcClearCmafFragmentWriter.write(
                7, 12, 90_000, nalSamples.map { sample ->
                    val total = sample.nals.sumOf { prefixSize + it.size }
                    val buffer = ByteBuffer.allocate(total)
                    sample.nals.forEach { nal ->
                        for (shift in (prefixSize - 1) downTo 0) {
                            buffer.put(((nal.size ushr (shift * 8)) and 0xff).toByte())
                        }
                        buffer.put(nal)
                    }
                    HevcClearCmafFragmentWriter.Sample(buffer.array(), sample.durationTicks,
                        sample.compositionTimeOffsetTicks, sample.sampleFlags)
                }, false, false)
            assertArrayEquals("NAL streaming must preserve every CMAF byte for $prefixSize-byte prefixes",
                materialized, direct)
            assertEquals(listOf(3600L, 3600L), CmafBoxInspector.inspect(direct).fragments.single()
                .sampleTimings.map { it.durationTicks })
        }
    }

    @Test
    fun directNalWriterRejectsOverflowingNalLengthsAndEncryptedInputs() {
        val nals = listOf(HevcClearCmafFragmentWriter.NalSample(
            listOf(byteArrayOf(0x26, 0x01)), 3600, 0, 0x02000000L))
        assertRejected("length prefix") {
            HevcClearCmafFragmentWriter.writeNalSamples(1, 1, 0, nals, 0, false, false)
        }
        assertRejected("length prefix") {
            HevcClearCmafFragmentWriter.writeNalSamples(1, 1, 0,
                listOf(nals.single().copy(nals = listOf(ByteArray(256)))), 1, false, false)
        }
        assertRejected("protected") {
            HevcClearCmafFragmentWriter.writeNalSamples(1, 1, 0, nals, 4, true, false)
        }
        assertRejected("encryption metadata") {
            HevcClearCmafFragmentWriter.writeNalSamples(1, 1, 0, nals, 4, false, true)
        }
    }

    @Test
    fun writesLargeSamplesDirectlyToOneContiguousMdatWithoutChangingInput() {
        val first = ByteArray(1024 * 1024 + 3) { (it * 17).toByte() }
        val second = ByteArray(768 * 1024 + 7) { (it * 31).toByte() }
        val samples = listOf(
            HevcClearCmafFragmentWriter.Sample(first, 3600, 0, 0x02000000),
            HevcClearCmafFragmentWriter.Sample(second, 3600, -3600, 0x01010000)
        )
        val result = HevcClearCmafFragmentWriter.write(1, 17, 90000, samples, false, false)

        val bytes = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        val moofSize = bytes.getInt(0)
        assertEquals("moof", String(result, 4, 4, Charsets.US_ASCII))
        assertEquals(first.size + second.size + 8, bytes.getInt(moofSize))
        assertEquals("mdat", String(result, moofSize + 4, 4, Charsets.US_ASCII))
        assertEquals(moofSize + 8 + first.size + second.size, result.size)
        assertArrayEquals(first, result.copyOfRange(moofSize + 8, moofSize + 8 + first.size))
        assertArrayEquals(second, result.copyOfRange(moofSize + 8 + first.size, result.size))
        assertEquals(listOf(first.size.toLong(), second.size.toLong()),
            CmafBoxInspector.inspect(result).fragments.single().sampleTimings.map { it.sampleSizeBytes })
        assertEquals((1024 * 1024 + 3 - 1).let { (it * 17).toByte() }, first.last())
        assertEquals((768 * 1024 + 7 - 1).let { (it * 31).toByte() }, second.last())
    }

    @Test
    fun buildsClearMediaFragmentWithExactSampleTimingAndFlags() {
        val samples = listOf(
            HevcClearCmafFragmentWriter.Sample(
                data = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                durationTicks = 3000,
                compositionTimeOffsetTicks = 0,
                sampleFlags = 0x02000000L
            ),
            HevcClearCmafFragmentWriter.Sample(
                data = byteArrayOf(0x11, 0x12, 0x13),
                durationTicks = 3000,
                compositionTimeOffsetTicks = -120,
                sampleFlags = 0x01010000L
            )
        )
        val bytes = HevcClearCmafFragmentWriter.write(
            trackId = 7,
            sequenceNumber = 12,
            baseDecodeTimeTicks = 90000,
            samples = samples,
            sourceTrackEncrypted = false,
            sourceFragmentHasEncryptionMetadata = false
        )

        val report = CmafBoxInspector.inspect(bytes)
        assertEquals(listOf("moof", "mdat"), report.topLevelBoxes)
        val fragment = report.fragments.single()
        assertEquals(7L, fragment.trackId)
        assertEquals(90000L, fragment.baseDecodeTimeTicks)
        assertEquals(2, fragment.sampleCount)
        assertEquals(listOf(3000L, 3000L), fragment.sampleTimings.map { it.durationTicks })
        assertEquals(listOf(0L, -120L), fragment.sampleTimings.map { it.compositionOffsetTicks })
        assertEquals(listOf(0x02000000L, 0x01010000L), fragment.sampleTimings.map { it.sampleFlags })
        assertEquals(listOf(4L, 3L), fragment.sampleTimings.map { it.sampleSizeBytes })
        assertFalse(fragment.sampleEncryptionBoxPresent)
        assertFalse(fragment.sampleAuxiliarySizesPresent)
        assertFalse(fragment.sampleAuxiliaryOffsetsPresent)
        assertFalse(fragment.sampleGroupEncryptionPresent)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun rejectsProtectedSourceAndFragmentEncryptionMetadata() {
        val sample = listOf(
            HevcClearCmafFragmentWriter.Sample(
                byteArrayOf(0x01), 3000, 0, 0x02000000L
            )
        )
        assertRejected("protected") {
            HevcClearCmafFragmentWriter.write(
                1, 1, 0, sample,
                sourceTrackEncrypted = true,
                sourceFragmentHasEncryptionMetadata = false
            )
        }
        assertRejected("encryption metadata") {
            HevcClearCmafFragmentWriter.write(
                1, 1, 0, sample,
                sourceTrackEncrypted = false,
                sourceFragmentHasEncryptionMetadata = true
            )
        }
    }

    @Test
    fun rejectsInvalidTimingAndSampleFlags() {
        assertRejected("duration") {
            HevcClearCmafFragmentWriter.write(
                1, 1, 0,
                listOf(HevcClearCmafFragmentWriter.Sample(byteArrayOf(1), 0, 0, 0)),
                false, false
            )
        }
        assertRejected("flags") {
            HevcClearCmafFragmentWriter.write(
                1, 1, 0,
                listOf(HevcClearCmafFragmentWriter.Sample(
                    byteArrayOf(1), 3000, 0, 0x1_0000_0000L
                )),
                false, false
            )
        }
    }

    private fun assertRejected(text: String, action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected rejection containing " + text)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(text, true))
        }
    }
}
