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

    @Test fun oneRetiredReaderCannotPinUnrelatedProtectedWindowResources() {
        val held = ByteArray(8) { 11 }
        val unrelated = ByteArray(16) { 12 }
        val manifest = ByteArray(3) { 13 }
        val store = ProtectedDashResourceStore()
        store.replace(pkg("a" to held, "b" to unrelated, "manifest" to manifest))
        val reader = requireNotNull(store.acquire("a"))
        store.replace(pkg("new" to ByteArray(10) { 14 }))
        assertArrayEquals(ByteArray(16), unrelated)
        assertArrayEquals(ByteArray(3), manifest)
        assertArrayEquals(ByteArray(8) { 11 }, reader.bytes)
        assertEquals(10L, store.snapshot().currentBytes)
        assertEquals(8L, store.snapshot().retiredReadBytes)
        assertEquals(1, store.snapshot().retiredResources)
        reader.close()
        assertArrayEquals(ByteArray(8), held)
        assertEquals(0L, store.snapshot().retiredReadBytes)
        store.close()
    }

    @Test fun aliasedPublicationCannotZeroOrReplaceCurrentOrLeasedBytes() {
        val bytes = ByteArray(6) { 7 }
        val store = ProtectedDashResourceStore()
        store.replace(pkg("old" to bytes))
        val old = requireNotNull(store.acquire("old"))
        assertThrows(IllegalArgumentException::class.java) {
            store.replace(pkg("alias" to bytes))
        }
        val stillCurrent = requireNotNull(store.acquire("old"))
        stillCurrent.close()
        store.replace(pkg("new" to byteArrayOf(4, 5)))
        assertThrows(IllegalArgumentException::class.java) {
            store.replace(pkg("retiredAlias" to bytes))
        }
        assertArrayEquals(ByteArray(6) { 7 }, old.bytes)
        old.close()
        assertArrayEquals(ByteArray(6), bytes)
        store.close()
    }
}
