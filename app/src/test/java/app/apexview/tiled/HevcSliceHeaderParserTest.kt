package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcSliceHeaderParserTest {
    @Test
    fun parsesSlicePocAndUsedShortTermReferenceDeltas() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        val slice = HevcSliceHeaderParser.parse(nal(1, pSliceBits()), sps, pps)

        assertTrue(slice.firstSliceSegmentInPicture)
        assertEquals(1, slice.sliceType)
        assertEquals(42, slice.picOrderCntLsb)
        assertFalse(slice.shortTermRpsFromSps!!)
        assertEquals(listOf(-1, -3), slice.references.map { it.deltaPoc })
        assertEquals(listOf(true, false), slice.references.map { it.usedByCurrent })
        assertTrue(slice.temporalMvpEnabled!!)
        assertTrue(slice.sampleAdaptiveOffsetLuma!!)
        assertTrue(slice.sampleAdaptiveOffsetChroma!!)
        assertTrue(slice.bitsConsumed > 0)
    }

    @Test
    fun resolvesShortTermReferenceSetSelectedFromSps() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits(includeSpsRps = true)))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        val bits = BitWriter().apply {
            write(1, 1) // first slice
            writeUe(0) // PPS id
            write(0, 2) // reserved header bits
            writeUe(1) // P slice
            write(43, 8)
            write(1, 1) // use SPS short-term RPS; only one, so no index bits
            write(1, 1) // temporal MVP
            write(1, 1); write(0, 1) // SAO luma/chroma
        }.finish()
        val slice = HevcSliceHeaderParser.parse(nal(1, bits), sps, pps)

        assertTrue(slice.shortTermRpsFromSps!!)
        assertEquals(0, slice.shortTermRpsIndex)
        assertEquals(listOf(-1, 1), slice.references.map { it.deltaPoc })
        assertEquals(listOf(true, true), slice.references.map { it.usedByCurrent })
    }

    @Test
    fun parsesIdrAndInfersPocZeroWithoutReferenceSets() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        val bits = BitWriter().apply {
            write(1, 1) // first slice
            write(1, 1) // IRAP no_output_of_prior_pics_flag
            writeUe(0) // PPS id
            write(0, 2) // reserved header bits
            writeUe(2) // I slice
        }.finish()
        val slice = HevcSliceHeaderParser.parse(nal(20, bits), sps, pps)

        assertTrue(slice.noOutputOfPriorPictures!!)
        assertEquals(0, slice.picOrderCntLsb)
        assertTrue(slice.references.isEmpty())
    }

    @Test
    fun parsesDependentSliceAddressWithoutInventingPictureIdentity() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        val bits = BitWriter().apply {
            write(0, 1) // not first slice in picture
            writeUe(0) // PPS id
            write(1, 1) // dependent slice segment
            write(5, 9) // address in the 510-CTB picture
        }.finish()
        val slice = HevcSliceHeaderParser.parse(nal(1, bits), sps, pps)

        assertTrue(slice.dependentSliceSegment)
        assertEquals(5, slice.sliceSegmentAddress)
        assertNull(slice.picOrderCntLsb)
        assertNull(slice.sliceType)
    }

    @Test
    fun rejectsPredictedShortTermRpsInsteadOfGuessingReferences() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits(includeSpsRps = true)))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        val bits = BitWriter().apply {
            write(1, 1); writeUe(0); write(0, 2); writeUe(1)
            write(44, 8)
            write(0, 1) // inline RPS
            write(1, 1) // unsupported inter-RPS prediction flag (inline set is index 1)
            writeUe(0) // delta_idx_minus1
        }.finish()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            HevcSliceHeaderParser.parse(nal(1, bits), sps, pps)
        }
    }

    @Test
    fun derivesPocAcrossLsbWrap() {
        val tracker = HevcPocTracker()
        assertEquals(0L, tracker.derive(20, 1, 0, 8))
        for (poc in 1..255) {
            assertEquals(poc.toLong(), tracker.derive(1, 1, poc, 8))
        }
        assertEquals(256L, tracker.derive(1, 1, 0, 8))
        assertEquals(257L, tracker.derive(1, 1, 1, 8))
    }

    private fun spsBits(includeSpsRps: Boolean = false): ByteArray = BitWriter().apply {
        write(1, 4); write(0, 3); write(1, 1)
        write(0, 8 + 32 + 4 + 44 + 8)
        writeUe(0); writeUe(1)
        writeUe(1920); writeUe(1080); write(0, 1)
        writeUe(0); writeUe(0); writeUe(4)
        write(1, 1); writeUe(4); writeUe(2); writeUe(0)
        writeUe(0); writeUe(3)
        writeUe(0); writeUe(0); writeUe(0); writeUe(0)
        write(0, 1) // scaling lists disabled
        write(0, 1) // AMP disabled
        write(1, 1) // SAO enabled
        write(0, 1) // PCM disabled
        writeUe(if (includeSpsRps) 1 else 0)
        if (includeSpsRps) {
            writeUe(1); writeUe(1) // one negative and one positive reference
            writeUe(0); write(1, 1) // delta -1, used
            writeUe(0); write(1, 1) // delta +1, used
        }
        write(0, 1) // long-term refs absent
        write(1, 1) // temporal MVP enabled
        write(1, 1) // strong smoothing
        write(0, 1) // no VUI
    }.finish()

    private fun ppsBits(): ByteArray = BitWriter().apply {
        writeUe(0); writeUe(0)
        write(1, 1); write(0, 1); write(2, 3)
        write(0, 1); write(0, 1)
        writeUe(0); writeUe(0)
        writeSe(0)
        write(0, 1); write(0, 1); write(0, 1)
        writeSe(0); writeSe(0)
        write(0, 1); write(0, 1); write(0, 1); write(0, 1)
        write(1, 1); write(0, 1)
        writeUe(1); writeUe(1); write(1, 1)
        write(1, 1)
    }.finish()

    private fun pSliceBits(): ByteArray = BitWriter().apply {
        write(1, 1); writeUe(0); write(0, 2); writeUe(1)
        write(42, 8); write(0, 1)
        writeUe(2); writeUe(0)
        writeUe(0); write(1, 1)
        writeUe(2); write(0, 1)
        write(1, 1) // temporal MVP enabled
        write(1, 1); write(1, 1) // SAO luma/chroma
    }.finish()

    private fun nal(type: Int, rbsp: ByteArray): ByteArray =
        byteArrayOf((type shl 1).toByte(), 0x01) + rbsp

    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += (value ushr i) and 1
        }
        fun writeUe(value: Int) {
            val code = value.toLong() + 1L
            val width = 64 - java.lang.Long.numberOfLeadingZeros(code)
            repeat(width - 1) { bits += 0 }
            write(code.toInt(), width)
        }
        fun writeSe(value: Int) = writeUe(if (value <= 0) -2 * value else 2 * value - 1)
        fun finish(): ByteArray {
            bits += 1
            while (bits.size % 8 != 0) bits += 0
            return ByteArray(bits.size / 8) { index ->
                var value = 0
                for (bit in 0 until 8) value = (value shl 1) or bits[index * 8 + bit]
                value.toByte()
            }
        }
    }
}
