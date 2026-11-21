package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

/** The one-decoder rolling DASH endpoint must not clone every CMAF read or erase
 * an in-flight fragment when the rolling manifest advances. */
class ProtectedDashResourceStoreTest {
    private fun pkg(vararg resources: Pair<String, ByteArray>) =
        ProtectedHevcComposedDashPackageBuilder.Package(
            manifest = "<MPD/>", resources = linkedMapOf(*resources),
            width = 1920, height = 2176, sampleCount = 288,
            fragmentCount = resources.size, startNumber = 1,
            presentationTimeOffsetTicks = 0, availabilityStartTimeMs = 1
        )

    @Test fun borrowedCmafIsNotCopiedAndIsRetainedUntilLastReaderCloses() {
        val bytes = byteArrayOf(17, 29, 41, 53)
        val store = ProtectedDashResourceStore()
        store.replace(pkg("segment-1.m4s" to bytes))
        val a = requireNotNull(store.acquire("segment-1.m4s"))
        val b = requireNotNull(store.acquire("segment-1.m4s"))
        assertSame("The protected endpoint must not copy megabytes for every reader",
            bytes, a.bytes)
        assertSame(bytes, b.bytes)
        store.replace(pkg("segment-2.m4s" to byteArrayOf(7, 8, 9)))
        assertArrayEquals(byteArrayOf(17, 29, 41, 53), bytes)
        a.close()
        assertArrayEquals(byteArrayOf(17, 29, 41, 53), bytes)
        b.close()
        assertArrayEquals("Retired encrypted bytes must be zeroed after last read",
            byteArrayOf(0, 0, 0, 0), bytes)
        b.close() // A duplicate close must not underflow the generation.
        store.close()
    }

    @Test fun closingEndpointCannotCorruptActiveReadersOrBeAcquiredAgain() {
        val first = byteArrayOf(31, 32, 33)
        val second = byteArrayOf(60, 61)
        val store = ProtectedDashResourceStore()
        store.replace(pkg("one.m4s" to first))
        assertNull(store.acquire("unknown"))
        val old = requireNotNull(store.acquire("one.m4s"))
        store.replace(pkg("two.m4s" to second))
        assertNull(store.acquire("one.m4s"))
        val live = requireNotNull(store.acquire("two.m4s"))
        store.close()
        assertNull(store.acquire("two.m4s"))
        assertArrayEquals(byteArrayOf(31, 32, 33), old.bytes)
        assertArrayEquals(byteArrayOf(60, 61), live.bytes)
        old.close()
        live.close()
        assertArrayEquals(byteArrayOf(0, 0, 0), first)
        assertArrayEquals(byteArrayOf(0, 0), second)
    }

    @Test fun fullyUnreferencedGenerationIsImmediatelyZeroed() {
        val bytes = byteArrayOf(1, 2, 3)
        val store = ProtectedDashResourceStore()
        store.replace(pkg("fragment" to bytes))
        store.close()
        assertArrayEquals(byteArrayOf(0, 0, 0), bytes)
    }
}
