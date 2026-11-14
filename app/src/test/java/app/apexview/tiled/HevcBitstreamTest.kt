package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HevcBitstreamTest {

    @Test fun splitsThreeAndFourByteAnnexBStartCodes() {
        val stream = byteArrayOf(
            0, 0, 0, 1, 0x40, 0x01, 0x55,
            0, 0, 1, 0x42, 0x01, 0x66,
        )
        val nals = HevcBitstream.splitAnnexB(stream)
        assertEquals(2, nals.size)
        assertEquals(32, nals[0].type)
        assertEquals(33, nals[1].type)
    }

    @Test fun parsesLayerAndTemporalId() {
        // nal_unit_type=19, nuh_layer_id=3, nuh_temporal_id_plus1=2
        val nal = byteArrayOf(0x27, 0x1a, 0x00)
        val parsed = HevcBitstream.parseNalHeader(nal)
        assertEquals(19, parsed.type)
        assertEquals(3, parsed.layerId)
        assertEquals(2, parsed.temporalIdPlus1)
    }

    @Test fun removesEmulationPreventionBytes() {
        val ebsp = byteArrayOf(0x00, 0x00, 0x03, 0x01, 0x7f)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x01, 0x7f), HevcBitstream.ebspToRbsp(ebsp))
    }

    @Test fun readsBitsAndUnsignedExpGolomb() {
        val reader = HevcBitReader(byteArrayOf(0b1010_0110.toByte()))
        assertEquals(1, reader.readBit())
        assertEquals(2, reader.readBits(2))
        // Remaining bits 0110: ue(v) = 2
        assertEquals(2L, reader.readUnsignedExpGolomb())
    }

    @Test fun rejectsMissingStartCode() {
        assertThrows(IllegalArgumentException::class.java) {
            HevcBitstream.splitAnnexB(byteArrayOf(0x40, 0x01, 0x55))
        }
    }

    @Test fun rejectsInvalidTemporalId() {
        assertThrows(IllegalArgumentException::class.java) {
            HevcBitstream.parseNalHeader(byteArrayOf(0x40, 0x00))
        }
    }

    @Test fun rejectsTruncatedNalHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            HevcBitstream.parseNalHeader(byteArrayOf(0x40))
        }
    }
}
