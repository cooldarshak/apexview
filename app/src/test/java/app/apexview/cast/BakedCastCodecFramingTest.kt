package app.apexview.cast

import app.apexview.scene.BakedAvcAccessUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BakedCastCodecFramingTest {
    @Test fun joinedAudioReadsBothExtentsWithoutMaterializingTheWholeStream() {
        val init = byteArrayOf(1,2,3)
        val media = byteArrayOf(4,5,6,7)
        val joined = init + media
        for (position in -1L..8L) for (requested in 0..8) {
            val target = ByteArray(12) { 99 }
            val expected = if (position < 0 || position >= joined.size) -1
                else minOf(requested, joined.size-position.toInt())
            assertEquals(expected,readJoinedAudio(init,media,position,target,2,requested))
            if (expected >= 0) {
                assertArrayEquals(joined.copyOfRange(position.toInt(),position.toInt()+expected),
                    target.copyOfRange(2,2+expected))
            }
            assertEquals(99,target[0].toInt())
            assertEquals(99,target[11].toInt())
        }
        val source=java.nio.ByteBuffer.wrap(media)
        assertArrayEquals(BakedAacAdts.wrap(media,48_000,2),
            BakedAacAdts.wrap(source,media.size,48_000,2,false) { assertEquals(false,it) })
    }

    @Test fun avccAccessUnitsBecomeAnnexBWithoutLosingTheNalUnits() {
        val avcc = byteArrayOf(0, 0, 0, 2, 0x65, 0x12, 0, 0, 0, 3, 0x41, 0x34, 0x56)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x12, 0, 0, 0, 1, 0x41, 0x34, 0x56),
            BakedAvcAccessUnit.annexB(avcc))
    }

    @Test fun existingAnnexBHeadersRemainUnchanged() {
        val annex = byteArrayOf(0, 0, 1, 0x67, 0x42, 0, 0, 1, 0x68, 0x23)
        assertArrayEquals(annex, BakedAvcAccessUnit.annexB(annex))
        assertThrows(IllegalArgumentException::class.java) {
            BakedAvcAccessUnit.annexB(byteArrayOf(0, 0, 0, 8, 0x65))
        }
    }

    @Test fun adtsWrapsOriginalStereo48kAacAndKeepsExactFrameSize() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val adts = BakedAacAdts.wrap(payload, 48_000, 2)
        assertEquals(12, adts.size)
        assertEquals(0xff, adts[0].toInt() and 255)
        assertEquals(0xf1, adts[1].toInt() and 255)
        assertEquals(3, (adts[2].toInt() ushr 2) and 15) // 48 kHz index
        assertEquals(2, ((adts[2].toInt() and 1) shl 2) or ((adts[3].toInt() ushr 6) and 3))
        val declaredSize = ((adts[3].toInt() and 3) shl 11) or
            ((adts[4].toInt() and 255) shl 3) or ((adts[5].toInt() ushr 5) and 7)
        assertEquals(adts.size, declaredSize)
        assertArrayEquals(payload, adts.copyOfRange(7, adts.size))
    }

    @Test fun adtsRejectsUnsupportedAacRates() {
        assertThrows(IllegalArgumentException::class.java) { BakedAacAdts.wrap(byteArrayOf(1), 12_345, 2) }
    }
}
