package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HevcBitstreamTest {
    @org.junit.Test fun bitReaderMatchesScalarReferenceAcrossEveryAlignmentAndWidth() {
        val bytes = ByteArray(12) { (it * 47 + 137).toByte() }
        for (offset in 0..63) for (width in 0..32) {
            val reader = HevcBitReader(bytes)
            repeat(offset) { reader.readBit() }
            var expected = 0
            for (i in offset until offset + width) {
                expected = (expected shl 1) or ((bytes[i / 8].toInt() ushr (7 - i % 8)) and 1)
            }
            org.junit.Assert.assertEquals("offset=$offset width=$width", expected, reader.readBits(width))
            org.junit.Assert.assertEquals(offset + width, reader.positionBits)
        }
    }


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
        val nal = byteArrayOf(0x26, 0x1a, 0x00)
        val parsed = HevcBitstream.parseNalHeader(nal)
        assertEquals(19, parsed.type)
        assertEquals(3, parsed.layerId)
        assertEquals(2, parsed.temporalIdPlus1)
    }

    @Test fun removesEmulationPreventionBytes() {
        val ebsp = byteArrayOf(0x00, 0x00, 0x03, 0x01, 0x7f)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x01, 0x7f), HevcBitstream.ebspToRbsp(ebsp))
    }

    @Test fun boundedEmulationPreventionMatchesOwnedSliceAndRejectsMalformedRanges() {
        val stream = byteArrayOf(0x55, 0x00, 0x00, 0x03, 0x02, 0x77)
        assertArrayEquals(byteArrayOf(0, 0, 2), HevcBitstream.ebspToRbsp(stream, 1, 4))
        assertArrayEquals(HevcBitstream.ebspToRbsp(stream.copyOfRange(1, 5)),
            HevcBitstream.ebspToRbsp(stream, 1, 4))
        assertThrows(IllegalArgumentException::class.java) { HevcBitstream.ebspToRbsp(stream, 5, 2) }
        assertThrows(IllegalArgumentException::class.java) {
            HevcBitstream.ebspToRbsp(byteArrayOf(0, 0, 3), 0, 3)
        }
    }

    @Test fun reusableRbspScratchMatchesOwnedProofAndRejectsInvalidTail() {
        val scratch = ByteArray(8192) { 0x7f }
        val samples = listOf(byteArrayOf(0, 0, 3, 1, 0x7f), byteArrayOf(0x55), byteArrayOf())
        for (sample in samples) {
            val count = HevcBitstream.ebspToRbspInto(sample, 0, sample.size, scratch)
            val expected = HevcBitstream.ebspToRbsp(sample)
            assertArrayEquals(expected, scratch.copyOf(count))
            val bits = HevcBitReader(scratch, count)
            for (value in expected) assertEquals(value.toInt() and 255, bits.readBits(8))
            assertEquals(0, bits.bitsRemaining)
            assertThrows(IllegalArgumentException::class.java) { bits.readBit() }
        }
        // A malformed byte near the end of the original 8 KiB proof range
        // must still reject, even when the syntactic header is short.
        val invalid = ByteArray(8192) { 0x55 }
        invalid[8188] = 0
        invalid[8189] = 0
        invalid[8190] = 3
        invalid[8191] = 4
        assertThrows(IllegalArgumentException::class.java) {
            HevcBitstream.ebspToRbspInto(invalid, 0, invalid.size, scratch)
        }
    }

    @Test fun headerViewDoesNotCopyNALButOwnedParserStillDoes() {
        val hugeNal = ByteArray(500_000)
        hugeNal[0] = 0x26
        hugeNal[1] = 0x01
        assertEquals(19, HevcBitstream.readNalHeader(hugeNal).type)
        val owned = HevcBitstream.parseNalHeader(hugeNal)
        hugeNal[2] = 99
        assertEquals(0, owned.bytes[2].toInt())
    }

    @Test fun splitRetainsOwnedLargeNalPayloadAndIndependentHeaderMetadata() {
        val stream = ByteArray(900_000) { 0x55.toByte() }
        stream[0] = 0; stream[1] = 0; stream[2] = 0; stream[3] = 1
        stream[4] = 0x26; stream[5] = 0x01
        val units = HevcBitstream.splitAnnexB(stream)
        assertEquals(1, units.size)
        assertEquals(19, units.single().type)
        assertEquals(stream.size - 4, units.single().bytes.size)
        assertArrayEquals(stream.copyOfRange(4, stream.size), units.single().bytes)
        stream[6] = 0x33
        assertEquals(0x55, units.single().bytes[2].toInt() and 0xff)
    }

    @Test fun readsBitsAndUnsignedExpGolomb() {
        val reader = HevcBitReader(byteArrayOf(0b1010_0110.toByte()))
        assertEquals(1, reader.readBit())
        assertEquals(1, reader.readBits(2))
        // Remaining bits 00110: ue(v) = 5
        assertEquals(5L, reader.readUnsignedExpGolomb())
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
