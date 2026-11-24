package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

/** Executable failure-path proof for the production initialization-copy owner. */
class OwnedArchiveInitializationsTest {
    @Test fun copiesAreIndependentAndWipedAfterCompositionCompletes() {
        val source1 = byteArrayOf(4, 5, 6)
        val source2 = byteArrayOf(7, 8, 9)
        val owned = OwnedArchiveInitializations.snapshot(listOf(source1, source2))
        assertNotSame(source1, owned.copies[0])
        assertNotSame(source2, owned.copies[1])
        assertArrayEquals(source1, owned.copies[0])
        assertArrayEquals(source2, owned.copies[1])
        owned.close()
        assertArrayEquals(ByteArray(3), owned.copies[0])
        assertArrayEquals(ByteArray(3), owned.copies[1])
        assertArrayEquals(byteArrayOf(4, 5, 6), source1)
        assertArrayEquals(byteArrayOf(7, 8, 9), source2)
        owned.close()
    }

    @Test fun precompositionErrorStillWipesOwnedBuffers() {
        val source = byteArrayOf(11, 12, 13, 14)
        var captured = emptyList<ByteArray>()
        assertThrows(IllegalStateException::class.java) {
            val owned = OwnedArchiveInitializations.snapshot(listOf(source))
            try {
                captured = owned.copies
                throw IllegalStateException("seek or compatibility validation failed")
            } finally {
                owned.close()
            }
        }
        assertEquals(1, captured.size)
        assertArrayEquals(ByteArray(4), captured.single())
        assertArrayEquals(byteArrayOf(11, 12, 13, 14), source)
    }
}
