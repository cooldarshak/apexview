package app.apexview.tiled

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcSliceHeaderRewriterTest {
    @Test
    fun rewritesSinglePictureCellIntoOutputGridAndPreservesCabacRbsp() {
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val sourcePps = HevcParameterSets.parsePps(nal(34, ppsBits(0, tiles = false)), sourceSps)
        val outputPps = HevcParameterSets.parsePps(nal(34, ppsBits(1, tiles = true)), outputSps)

        assertFalse(sourcePps.tilesEnabled)
        assertTrue(outputPps.tilesEnabled)
        assertEquals(2, outputPps.numTileColumns)
        assertEquals(1, outputPps.numTileRows)

        val sourceNal = nal(1, pSliceBits() + byteArrayOf(
            0x12, 0x34, 0x00, 0x00, 0x01, 0x56, 0x78, 0x22
        ))
        val sourceHeader = HevcSliceHeaderParser.parse(sourceNal, sourceSps, sourcePps)
        val originalRbsp = HevcBitstream.ebspToRbsp(sourceNal.copyOfRange(2, sourceNal.size))
        val originalCabac = originalRbsp.copyOfRange(sourceHeader.bitsConsumed / 8, originalRbsp.size)

        val rewritten = HevcSliceHeaderRewriter.rewrite(
            nal = sourceNal,
            sourceSps = sourceSps,
            sourcePps = sourcePps,
            outputSps = outputSps,
            outputPps = outputPps,
            destinationAddress = 30
        )
        val outputHeader = HevcSliceHeaderParser.parse(rewritten.nal, outputSps, outputPps)
        val outputRbsp = HevcBitstream.ebspToRbsp(rewritten.nal.copyOfRange(2, rewritten.nal.size))
        val outputCabac = outputRbsp.copyOfRange(outputHeader.bitsConsumed / 8, outputRbsp.size)

        assertFalse(outputHeader.firstSliceSegmentInPicture)
        assertEquals(1, outputHeader.ppsId)
        assertEquals(30, outputHeader.sliceSegmentAddress)
        assertEquals(sourceHeader.picOrderCntLsb, outputHeader.picOrderCntLsb)
        assertEquals(sourceHeader.sliceType, outputHeader.sliceType)
        assertEquals(sourceHeader.references, outputHeader.references)
        assertArrayEquals(originalCabac, outputCabac)
        assertTrue(rewritten.copiedTailBytes > 0)
        assertArrayEquals(
            sourceNal.copyOfRange(rewritten.sourceTailOffset, sourceNal.size),
            rewritten.nal.copyOfRange(rewritten.nal.size - rewritten.copiedTailBytes, rewritten.nal.size)
        )
    }

    @Test
    fun preservesTizenStylePerSliceDeblockingOverrideWhenRewritingCellHeader() {
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val sourcePps = HevcParameterSets.parsePps(
            nal(34, ppsBits(0, tiles = false, deblockingOverride = true)), sourceSps
        )
        val outputPps = HevcParameterSets.parsePps(
            nal(34, ppsBits(1, tiles = true, deblockingOverride = true)), outputSps
        )
        assertTrue(sourcePps.deblockingFilterOverrideEnabled)
        assertTrue(outputPps.deblockingFilterOverrideEnabled)

        val sourceNal = nal(
            1,
            pSliceWithDeblockingOverrideBits() + byteArrayOf(0x21, 0x32, 0x43, 0x54)
        )
        val sourceHeader = HevcSliceHeaderParser.parse(sourceNal, sourceSps, sourcePps)
        assertEquals(true, sourceHeader.deblockingFilterOverride)
        assertEquals(false, sourceHeader.deblockingFilterDisabled)
        assertEquals(2, sourceHeader.betaOffsetDiv2)
        assertEquals(-1, sourceHeader.tcOffsetDiv2)

        val rewritten = HevcSliceHeaderRewriter.rewrite(
            nal = sourceNal,
            sourceSps = sourceSps,
            sourcePps = sourcePps,
            outputSps = outputSps,
            outputPps = outputPps,
            destinationAddress = 30
        )
        val outputHeader = HevcSliceHeaderParser.parse(rewritten.nal, outputSps, outputPps)
        assertEquals(30, outputHeader.sliceSegmentAddress)
        assertEquals(sourceHeader.deblockingFilterOverride, outputHeader.deblockingFilterOverride)
        assertEquals(sourceHeader.deblockingFilterDisabled, outputHeader.deblockingFilterDisabled)
        assertEquals(sourceHeader.betaOffsetDiv2, outputHeader.betaOffsetDiv2)
        assertEquals(sourceHeader.tcOffsetDiv2, outputHeader.tcOffsetDiv2)

        val sourceRbsp = HevcBitstream.ebspToRbsp(sourceNal.copyOfRange(2, sourceNal.size))
        val outputRbsp = HevcBitstream.ebspToRbsp(rewritten.nal.copyOfRange(2, rewritten.nal.size))
        assertArrayEquals(
            sourceRbsp.copyOfRange(sourceHeader.bitsConsumed / 8, sourceRbsp.size),
            outputRbsp.copyOfRange(outputHeader.bitsConsumed / 8, outputRbsp.size)
        )
    }

    @Test
    fun makesDestinationAddressZeroTheOutputFirstSlice() {
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val sourcePps = HevcParameterSets.parsePps(nal(34, ppsBits(0, tiles = false)), sourceSps)
        val outputPps = HevcParameterSets.parsePps(nal(34, ppsBits(1, tiles = true)), outputSps)
        val sourceNal = nal(1, pSliceBits() + byteArrayOf(0x31, 0x22, 0x11, 0x45))

        val rewritten = HevcSliceHeaderRewriter.rewrite(
            sourceNal, sourceSps, sourcePps, outputSps, outputPps, destinationAddress = 0
        )
        val parsed = HevcSliceHeaderParser.parse(rewritten.nal, outputSps, outputPps)
        assertTrue(parsed.firstSliceSegmentInPicture)
        assertEquals(0, parsed.sliceSegmentAddress)
        assertEquals(1, parsed.ppsId)
    }

    @Test
    fun rejectsInsufficientClearPrefixAndOutOfRangeDestination() {
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val sourcePps = HevcParameterSets.parsePps(nal(34, ppsBits(0, tiles = false)), sourceSps)
        val outputPps = HevcParameterSets.parsePps(nal(34, ppsBits(1, tiles = true)), outputSps)
        val sourceNal = nal(1, pSliceBits() + byteArrayOf(0x31, 0x22, 0x11, 0x45))

        assertRejected("clear") {
            HevcSliceHeaderRewriter.rewrite(
                sourceNal, sourceSps, sourcePps, outputSps, outputPps, 30,
                clearNalBytes = 2
            )
        }
        assertRejected("destination") {
            HevcSliceHeaderRewriter.rewrite(
                sourceNal, sourceSps, sourcePps, outputSps, outputPps, 2000
            )
        }
    }

    @Test
    fun rejectsWppAndDependentSliceSegments() {
        val sourceSps = HevcParameterSets.parseSps(nal(33, spsBits(1920, 1080)))
        val outputSps = HevcParameterSets.parseSps(nal(33, spsBits(3840, 1080)))
        val wppPps = HevcParameterSets.parsePps(
            nal(34, ppsBits(0, tiles = false, wpp = true)), sourceSps
        )
        val outputPps = HevcParameterSets.parsePps(nal(34, ppsBits(1, tiles = true)), outputSps)
        val sourceNal = nal(1, pSliceBits() + byteArrayOf(0x31, 0x22, 0x11, 0x45))
        assertRejected("WPP") {
            HevcSliceHeaderRewriter.rewrite(
                sourceNal, sourceSps, wppPps, outputSps, outputPps, 30
            )
        }
        val nonFirstDependent = BitWriter().apply {
            write(0, 1)
            writeUe(0)
            write(1, 1)
            write(1, 9)
        }.finish()
        val sourcePps = HevcParameterSets.parsePps(nal(34, ppsBits(0, tiles = false)), sourceSps)
        assertRejected("Dependent") {
            HevcSliceHeaderRewriter.rewrite(
                nal(1, nonFirstDependent), sourceSps, sourcePps, outputSps, outputPps, 30
            )
        }
    }

    private fun assertRejected(expectedText: String, action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected rejection containing: " + expectedText)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains(expectedText, true))
        }
    }

    private fun spsBits(width: Int, height: Int): ByteArray = BitWriter().apply {
        write(1, 4); write(0, 3); write(1, 1)
        write(0, 8 + 32 + 4 + 44 + 8)
        writeUe(0); writeUe(1)
        writeUe(width); writeUe(height); write(0, 1)
        writeUe(0); writeUe(0); writeUe(4)
        write(1, 1); writeUe(4); writeUe(2); writeUe(0)
        writeUe(0); writeUe(3)
        writeUe(0); writeUe(0); writeUe(0); writeUe(0)
        write(0, 1) // scaling lists disabled
        write(0, 1) // AMP disabled
        write(1, 1) // SAO enabled
        write(0, 1) // PCM disabled
        writeUe(0) // no short-term reference picture sets
        write(0, 1) // long-term refs absent
        write(1, 1) // temporal MVP enabled
        write(1, 1) // strong smoothing
        write(0, 1) // no VUI
        write(0, 1) // no SPS extension
    }.finish()

    private fun ppsBits(id: Int, tiles: Boolean, wpp: Boolean = false, deblockingOverride: Boolean = false): ByteArray =
        BitWriter().apply {
            writeUe(id); writeUe(0)
            write(1, 1); write(0, 1); write(2, 3)
            write(0, 1); write(0, 1)
            writeUe(0); writeUe(0); writeSe(0)
            write(0, 1); write(0, 1); write(0, 1)
            writeSe(0); writeSe(0)
            write(0, 1); write(0, 1); write(0, 1); write(0, 1)
            write(if (tiles) 1 else 0, 1)
            write(if (wpp) 1 else 0, 1)
            if (tiles) {
                writeUe(1); writeUe(0); write(1, 1); write(1, 1)
            }
            write(1, 1) // loop_filter_across_slices_enabled_flag
            write(if (deblockingOverride) 1 else 0, 1) // deblocking_filter_control_present_flag
            if (deblockingOverride) {
                write(1, 1) // pps_deblocking_filter_override_enabled_flag
                write(0, 1) // pps_deblocking_filter_disabled_flag
                writeSe(0); writeSe(0) // default beta/tc offsets
            }
            write(0, 1) // pps_scaling_list_data_present_flag
            write(0, 1) // lists_modification_present_flag
            writeUe(0)
            write(0, 1) // slice_segment_header_extension_present_flag
            write(0, 1) // pps_extension_present_flag
        }.finish()

    private fun pSliceWithDeblockingOverrideBits(): ByteArray = BitWriter().apply {
        write(1, 1); writeUe(0); write(0, 2); writeUe(1)
        write(42, 8)
        write(0, 1); writeUe(2); writeUe(0)
        writeUe(0); write(1, 1); writeUe(2); write(0, 1)
        write(1, 1); write(1, 1); write(1, 1)
        write(0, 1); writeUe(0); writeSe(0)
        write(1, 1) // slice_deblocking_filter_override_flag
        write(0, 1) // slice_deblocking_filter_disabled_flag
        writeSe(2); writeSe(-1) // slice beta/tc offsets
        write(1, 1) // loop_filter_across_slices_enabled_flag
    }.finish()

    private fun pSliceBits(): ByteArray = BitWriter().apply {
        write(1, 1); writeUe(0); write(0, 2); writeUe(1)
        write(42, 8)
        write(0, 1); writeUe(2); writeUe(0)
        writeUe(0); write(1, 1); writeUe(2); write(0, 1)
        write(1, 1); write(1, 1); write(1, 1)
        write(0, 1); writeUe(0); writeSe(0)
        write(1, 1) // loop_filter_across_slices_enabled_flag
    }.finish()

    private fun nal(type: Int, rbsp: ByteArray): ByteArray {
        val header = byteArrayOf((type shl 1).toByte(), 1)
        return header + escape(rbsp)
    }

    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        var zeroCount = 0
        rbsp.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (zeroCount >= 2 && value <= 3) {
                out += 3.toByte()
                zeroCount = 0
            }
            out += byte
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun write(value: Int, count: Int) {
            for (index in count - 1 downTo 0) bits += (value ushr index) and 1
        }
        fun writeBit(value: Int) = write(value, 1)
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
