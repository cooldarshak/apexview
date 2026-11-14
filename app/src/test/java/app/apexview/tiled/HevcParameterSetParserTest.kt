package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class HevcParameterSetParserTest {
    @Test fun parsesVpsIdentityAndLayerLimits() {
        val bits = BitWriter().apply {
            write(5, 4); write(1, 1); write(1, 1); write(0, 6)
            write(1, 3); write(1, 1); write(0xffff, 16)
        }
        val vps = HevcParameterSets.parseVps(nal(32, bits.finish()))
        assertEquals(5, vps.vpsId)
        assertEquals(1, vps.maxLayers)
        assertEquals(2, vps.maxSubLayers)
        assertTrue(vps.temporalIdNesting)
    }

    @Test fun extractsSpsDimensionsPocAndCtbGeometry() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        assertEquals(1, sps.vpsId)
        assertEquals(0, sps.spsId)
        assertEquals(1, sps.maxSubLayers)
        assertEquals(1, sps.chromaFormatIdc)
        assertEquals(1920, sps.width)
        assertEquals(1080, sps.height)
        assertEquals(8, sps.log2MaxPicOrderCntLsb)
        assertEquals(64, sps.ctbSize)
        assertEquals(30, sps.picWidthInCtbs)
        assertEquals(17, sps.picHeightInCtbs)
    }

    @Test fun extractsUniformPpsTileGridAndCalculatesCtbPartitions() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits(uniform = true)), sps)
        assertEquals(0, pps.ppsId)
        assertEquals(0, pps.spsId)
        assertTrue(pps.dependentSliceSegmentsEnabled)
        assertTrue(pps.tilesEnabled)
        assertTrue(pps.uniformTileSpacing == true)
        assertEquals(2, pps.numTileColumns)
        assertEquals(2, pps.numTileRows)
        assertEquals(listOf(15, 15), pps.tileColumnWidthsInCtbs)
        assertEquals(listOf(8, 9), pps.tileRowHeightsInCtbs)
    }

    @Test fun extractsExplicitPpsTileWidthsAndHeights() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits(uniform = false)), sps)
        assertFalse(pps.uniformTileSpacing == true)
        assertEquals(listOf(15, 15), pps.tileColumnWidthsInCtbs)
        assertEquals(listOf(8, 9), pps.tileRowHeightsInCtbs)
    }

    @Test fun rejectsPpsThatReferencesDifferentSps() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val wrongPps = HevcParameterSets.parsePps(nal(34, ppsBits(uniform = true, spsId = 1)))
        assertThrows(IllegalArgumentException::class.java) {
            HevcParameterSets.parsePps(nal(34, ppsBits(uniform = true, spsId = 1)), sps)
        }
        assertEquals(1, wrongPps.spsId)
    }

    @Test fun rejectsWrongNalTypeAndTruncatedVps() {
        assertThrows(IllegalArgumentException::class.java) {
            HevcParameterSets.parseSps(nal(34, spsBits()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HevcParameterSets.parseVps(byteArrayOf(0x40, 0x01))
        }
    }

    private fun spsBits(): ByteArray = BitWriter().apply {
        write(1, 4) // sps_video_parameter_set_id
        write(0, 3) // sps_max_sub_layers_minus1
        write(1, 1) // temporal nesting
        write(0, 8 + 32 + 4 + 44 + 8) // general profile-tier-level
        writeUe(0) // sps id
        writeUe(1) // 4:2:0 chroma
        writeUe(1920)
        writeUe(1080)
        write(0, 1) // no conformance window
        writeUe(0); writeUe(0) // bit depth
        writeUe(4) // log2_max_pic_order_cnt_lsb_minus4 = 4
        write(1, 1) // sub-layer ordering info for all layers
        writeUe(4); writeUe(2); writeUe(0)
        writeUe(0) // log2_min_luma_coding_block_size_minus3
        writeUe(3) // log2_diff_max_min_luma_coding_block_size -> 64x64 CTB
    }.finish()

    private fun ppsBits(uniform: Boolean, spsId: Int = 0): ByteArray = BitWriter().apply {
        writeUe(0) // PPS id
        writeUe(spsId)
        write(1, 1) // dependent slice segments
        write(0, 1) // output flag absent
        write(2, 3) // extra slice-header bits
        write(0, 1); write(0, 1) // sign hiding, CABAC init
        writeUe(0); writeUe(0)
        writeSe(0) // init QP
        write(0, 1); write(0, 1); write(0, 1) // constrained intra, transform skip, CU QP delta
        writeSe(0); writeSe(0) // chroma QP offsets
        write(0, 1) // slice chroma QP offsets
        write(0, 1); write(0, 1); write(0, 1) // weighted pred/bipred/transquant
        write(1, 1) // tiles enabled
        write(0, 1) // entropy coding sync disabled
        writeUe(1); writeUe(1) // 2 columns, 2 rows
        write(if (uniform) 1 else 0, 1)
        if (!uniform) {
            writeUe(14) // first tile column is 15 CTBs
            writeUe(7)  // first tile row is 8 CTBs
        }
        write(1, 1) // loop filter across tiles
    }.finish()

    private fun nal(type: Int, rbsp: ByteArray): ByteArray =
        byteArrayOf((type shl 1).toByte(), 0x01) + rbsp

    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += (value ushr i) and 1
        }
        fun writeUe(value: Int) {
            require(value >= 0)
            val code = value.toLong() + 1L
            val width = 64 - java.lang.Long.numberOfLeadingZeros(code)
            repeat(width - 1) { bits += 0 }
            write(code.toInt(), width)
        }
        fun writeSe(value: Int) = writeUe(if (value <= 0) -2 * value else 2 * value - 1)
        fun finish(): ByteArray {
            bits += 1 // rbsp_stop_one_bit
            while (bits.size % 8 != 0) bits += 0
            return ByteArray(bits.size / 8) { index ->
                var value = 0
                for (bit in 0 until 8) value = (value shl 1) or bits[index * 8 + bit]
                value.toByte()
            }
        }
    }
}
