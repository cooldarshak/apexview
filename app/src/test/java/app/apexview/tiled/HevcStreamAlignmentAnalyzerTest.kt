package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcStreamAlignmentAnalyzerTest {
    @Test
    fun comparesPocSliceTypesAndReferenceSetsAcrossTwoSyntheticFeeds() {
        val feedA = stream(pSlice(1, referenceCount = 1), pSlice(2, referenceCount = 2))
        val feedB = stream(pSlice(1, referenceCount = 1), pSlice(2, referenceCount = 2))
        val a = HevcStreamAlignmentAnalyzer.analyze(feedA)
        val b = HevcStreamAlignmentAnalyzer.analyze(feedB)
        val report = HevcStreamAlignmentAnalyzer.compare(a, b)

        assertEquals(3, report.firstFrameCount)
        assertEquals(3, report.secondFrameCount)
        assertEquals(listOf(0L, 1L, 2L), a.map { it.poc })
        assertTrue(report.pocAligned)
        assertTrue(report.referencesAligned)
        assertTrue(report.fullyAligned)
    }

    @Test
    fun rejectsPictureIdentityMismatchEvenWhenPocAndReferencesMatch() {
        val feedA = streamWithIdrNoOutputPriorPictures(
            true, pSlice(1, referenceCount = 1), pSlice(2, referenceCount = 2)
        )
        val feedB = streamWithIdrNoOutputPriorPictures(
            false, pSlice(1, referenceCount = 1), pSlice(2, referenceCount = 2)
        )
        val report = HevcStreamAlignmentAnalyzer.compare(
            HevcStreamAlignmentAnalyzer.analyze(feedA),
            HevcStreamAlignmentAnalyzer.analyze(feedB)
        )

        assertTrue(report.pocAligned)
        assertTrue(report.referencesAligned)
        assertTrue(!report.pictureIdentityAligned)
        assertTrue(!report.fullyAligned)
        assertTrue(report.mismatches.any { !it.pictureIdentityAligned })
    }

    @Test
    fun reportsPocAndReferenceMismatchesRatherThanTreatingFeedsAsAligned() {
        val feedA = stream(pSlice(1, referenceCount = 1), pSlice(2, referenceCount = 2))
        val feedB = stream(pSlice(1, referenceCount = 1), pSlice(3, referenceCount = 1))
        val report = HevcStreamAlignmentAnalyzer.compare(
            HevcStreamAlignmentAnalyzer.analyze(feedA),
            HevcStreamAlignmentAnalyzer.analyze(feedB)
        )

        assertEquals(3, report.comparedFrameCount)
        assertTrue(report.mismatches.isNotEmpty())
        assertTrue(!report.pocAligned)
        assertTrue(!report.referencesAligned)
    }

    @Test
    fun acceptsValidEmulationPreventionByteAtBoundedSliceInspectionBoundary() {
        val idr = nal(20, idrSlice())
        // The 128-byte VCL inspection window covers nal[2]..nal[129].
        // A valid 00 00 03 01 sequence happens to straddle its final byte.
        val padding = ByteArray(127 - idr.size) { 0x7f }
        val extendedIdr = idr + padding + byteArrayOf(0, 0, 3, 1, 0x7f)
        assertEquals(3, extendedIdr[129].toInt())
        val annexB = listOf(
            nal(33, spsBits()), nal(34, ppsBits()), extendedIdr,
            nal(1, pSlice(1, referenceCount = 1))
        ).fold(ByteArray(0)) { out, n -> out + byteArrayOf(0, 0, 0, 1) + n }

        assertEquals(listOf(0L, 1L), HevcStreamAlignmentAnalyzer.analyze(annexB).map { it.poc })
    }

    private fun stream(vararg pSlices: ByteArray): ByteArray =
        streamWithIdrNoOutputPriorPictures(true, *pSlices)

    private fun streamWithIdrNoOutputPriorPictures(
        noOutputOfPriorPictures: Boolean,
        vararg pSlices: ByteArray
    ): ByteArray {
        val nals = listOf(
            nal(33, spsBits()), nal(34, ppsBits()),
            nal(20, idrSlice(noOutputOfPriorPictures))
        ) + pSlices.map { nal(1, it) }
        return nals.fold(ByteArray(0)) { out, n -> out + byteArrayOf(0, 0, 0, 1) + n }
    }

    private fun spsBits(): ByteArray = BitWriter().apply {
        write(1, 4); write(0, 3); write(1, 1)
        write(0, 8 + 32 + 4 + 44 + 8)
        writeUe(0); writeUe(1); writeUe(1920); writeUe(1080); write(0, 1)
        writeUe(0); writeUe(0); writeUe(4)
        write(1, 1); writeUe(4); writeUe(2); writeUe(0)
        writeUe(0); writeUe(3)
        writeUe(0); writeUe(0); writeUe(0); writeUe(0)
        write(0, 1); write(0, 1); write(1, 1); write(0, 1)
        writeUe(0) // no SPS short-term sets
        write(0, 1); write(1, 1); write(1, 1); write(0, 1)
        write(0, 1) // no SPS extension
    }.finish()

    private fun ppsBits(): ByteArray = BitWriter().apply {
        writeUe(0); writeUe(0); write(1, 1); write(0, 1); write(2, 3)
        write(0, 1); write(0, 1); writeUe(0); writeUe(0); writeSe(0)
        write(0, 1); write(0, 1); write(0, 1); writeSe(0); writeSe(0)
        write(0, 1); write(0, 1); write(0, 1); write(0, 1)
        write(1, 1); write(0, 1); writeUe(1); writeUe(1); write(1, 1); write(1, 1)
        write(1, 1) // loop filter across slices
        write(0, 1) // no deblocking filter control
        write(0, 1) // no PPS scaling-list data
        write(0, 1) // no list modification
        writeUe(0) // parallel merge level
        write(0, 1) // no slice header extension
        write(0, 1) // no PPS extension
    }.finish()

    private fun idrSlice(noOutputOfPriorPictures: Boolean = true): ByteArray = BitWriter().apply {
        write(1, 1); write(if (noOutputOfPriorPictures) 1 else 0, 1); writeUe(0); write(0, 2); writeUe(2)
        write(1, 1); write(1, 1) // SAO luma/chroma
        writeSe(0) // slice_qp_delta
        write(1, 1) // slice_loop_filter_across_slices_enabled_flag
        writeUe(0) // num_entry_point_offsets
    }.finish()

    private fun pSlice(
        poc: Int,
        referenceCount: Int,
        temporalMvpEnabled: Boolean = true
    ): ByteArray = BitWriter().apply {
        write(1, 1); writeUe(0); write(0, 2); writeUe(1)
        write(poc, 8)
        write(0, 1) // short_term_ref_pic_set_sps_flag is always present for non-IDR pictures
        writeUe(referenceCount); writeUe(0)
        repeat(referenceCount) { writeUe(0); write(1, 1) }
        write(if (temporalMvpEnabled) 1 else 0, 1) // temporal MVP
        write(1, 1); write(1, 1) // SAO luma/chroma
        write(0, 1) // num_ref_idx_active_override_flag
        writeUe(0) // five_minus_max_num_merge_cand
        writeSe(0) // slice_qp_delta
        write(1, 1) // slice_loop_filter_across_slices_enabled_flag
        writeUe(0) // num_entry_point_offsets
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
