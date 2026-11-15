package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcClearCmafFragmentWriterTest {
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
