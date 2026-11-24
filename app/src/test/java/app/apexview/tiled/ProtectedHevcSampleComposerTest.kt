package app.apexview.tiled

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtectedHevcSampleComposerTest {
    @Test fun keepsAdjacentEncryptedSubsampleBoundariesInOutputMap() {
        val nal = byteArrayOf(0x02, 0x01, 0x7e, 0x55)
        val rewritten = ProtectedHevcSliceHeaderRewriter.Result(
            nal = nal,
            protectionRanges = listOf(
                ProtectedHevcSampleLayout.ProtectionRange(0, 2, true),
                ProtectedHevcSampleLayout.ProtectionRange(2, 1, false),
                ProtectedHevcSampleLayout.ProtectionRange(3, 1, false)
            ),
            destinationAddress = 0,
            sourceTailOffset = 2,
            outputTailOffset = 2,
            copiedTailBytes = 2,
            encryptedByteCount = 2L
        )

        val output = ProtectedHevcSampleComposer.compose(listOf(rewritten), nalLengthSize = 4)

        assertEquals(
            listOf(
                CmafBoxInspector.SubsampleEncryptionRange(6, 1),
                CmafBoxInspector.SubsampleEncryptionRange(0, 1)
            ),
            output.subsampleRanges
        )
        val layout = ProtectedHevcSampleLayout.inspect(
            output.sampleBytes, output.nalLengthSize, output.subsampleRanges
        )
        assertEquals(
            listOf(
                ProtectedHevcSampleLayout.ProtectionRange(0, 2, true),
                ProtectedHevcSampleLayout.ProtectionRange(2, 1, false),
                ProtectedHevcSampleLayout.ProtectionRange(3, 1, false)
            ),
            layout.nals.single().protectionRanges
        )
    }

    @Test fun packsMultipleProtectedNalsExactlyAtEveryAllowedPrefixWidth() {
        val nals = listOf(
            byteArrayOf(0x02, 0x01, 0x7e, 0x55),
            byteArrayOf(0x02, 0x01, 0x44, 0x66, 0x77),
        )
        val slices = nals.map { nal ->
            ProtectedHevcSliceHeaderRewriter.Result(
                nal = nal,
                protectionRanges = listOf(
                    ProtectedHevcSampleLayout.ProtectionRange(0, 2, true),
                    ProtectedHevcSampleLayout.ProtectionRange(2, nal.size - 2, false),
                ),
                destinationAddress = 0,
                sourceTailOffset = 2,
                outputTailOffset = 2,
                copiedTailBytes = nal.size - 2,
                encryptedByteCount = (nal.size - 2).toLong(),
            )
        }
        for (prefixWidth in 1..4) {
            val output = ProtectedHevcSampleComposer.compose(slices, prefixWidth)
            val expected = buildList<Byte> {
                nals.forEach { nal ->
                    for (shift in prefixWidth - 1 downTo 0)
                        add(((nal.size ushr (shift * 8)) and 0xff).toByte())
                    addAll(nal.toList())
                }
            }.toByteArray()
            assertArrayEquals(expected, output.sampleBytes)
            assertEquals(nals.map { CmafBoxInspector.SubsampleEncryptionRange(
                prefixWidth + 2, (it.size - 2).toLong()) }, output.subsampleRanges)
            assertEquals(2, ProtectedHevcSampleLayout.inspect(
                output.sampleBytes, prefixWidth, output.subsampleRanges
            ).nals.size)
        }
    }

    @Test fun longClearRunStillSplitsAtTheCencUint16Boundary() {
        val nal = ByteArray(70_000) { 0x4f }
        nal[0] = 0x02
        nal[1] = 0x01
        val clearNalBytes = nal.size - 16
        val slice = ProtectedHevcSliceHeaderRewriter.Result(
            nal = nal,
            protectionRanges = listOf(
                ProtectedHevcSampleLayout.ProtectionRange(0, clearNalBytes, true),
                ProtectedHevcSampleLayout.ProtectionRange(clearNalBytes, 16, false),
            ),
            destinationAddress = 0, sourceTailOffset = 2, outputTailOffset = 2,
            copiedTailBytes = nal.size - 2, encryptedByteCount = 16L,
        )
        val output = ProtectedHevcSampleComposer.compose(listOf(slice), 4)
        assertEquals(nal.size + 4, output.sampleBytes.size)
        assertArrayEquals(nal, output.sampleBytes.copyOfRange(4, output.sampleBytes.size))
        assertEquals(listOf(
            CmafBoxInspector.SubsampleEncryptionRange(65_535, 0),
            CmafBoxInspector.SubsampleEncryptionRange(4 + clearNalBytes - 65_535, 16),
        ), output.subsampleRanges)
    }

    @Test fun rejectsNalWithIncompleteProtectionMap() {
        val rewritten = ProtectedHevcSliceHeaderRewriter.Result(
            nal = byteArrayOf(0x02, 0x01, 0x7e),
            protectionRanges = listOf(
                ProtectedHevcSampleLayout.ProtectionRange(0, 2, true)
            ),
            destinationAddress = 0,
            sourceTailOffset = 2,
            outputTailOffset = 2,
            copiedTailBytes = 1,
            encryptedByteCount = 0L
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcSampleComposer.compose(listOf(rewritten), 4)
        }
    }
    @Test fun borrowedSampleWritesExactCiphertextToFinalOffsetAndDoesNotOwnInput() {
        val backing = byteArrayOf(99, 0x02, 0x01, 0x4a, 0x55, 0x66, 99)
        val original = backing.copyOf()
        val parts = HevcSliceHeaderRewriter.Parts(byteArrayOf(0x02, 0x01, 0x7e), backing,
            4, 2, 0, 1, 1, 3, 0)
        val ranges = listOf(
            ProtectedHevcSampleLayout.ProtectionRange(0, 3, true),
            ProtectedHevcSampleLayout.ProtectionRange(3, 1, false),
            ProtectedHevcSampleLayout.ProtectionRange(4, 1, false)
        )
        val borrowed = ProtectedHevcSliceHeaderRewriter.Borrowed(parts, ranges, 0, 3, 3, 2, 2)
        val legacy = ProtectedHevcSliceHeaderRewriter.Result(byteArrayOf(0x02, 0x01, 0x7e, 0x55, 0x66),
            ranges, 0, 3, 3, 2, 2)
        val expected = ProtectedHevcSampleComposer.compose(listOf(legacy), 4)
        val plan = ProtectedHevcSampleComposer.planBorrowed(listOf(borrowed), 4, "test")
        val actual = ByteArray(plan.size + 6) { 42 }
        plan.copyInto(actual, 3)
        assertArrayEquals(expected.sampleBytes, actual.copyOfRange(3, 3 + plan.size))
        assertEquals(expected.subsampleRanges, plan.subsampleRanges)
        assertEquals(expected.encryptedByteCount, plan.encryptedByteCount)
        assertEquals(42.toByte(), actual[2])
        assertEquals(42.toByte(), actual[3 + plan.size])
        plan.close(); plan.close()
        assertArrayEquals(original, backing)
        assertThrows(IllegalStateException::class.java) { plan.copyInto(actual, 3) }
    }
}
