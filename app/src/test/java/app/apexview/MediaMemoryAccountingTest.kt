package app.apexview
import org.junit.Assert.*
import org.junit.Test
class MediaMemoryAccountingTest {
    @Test fun oneOwnerUpdatesInsteadOfAddingAndPinnedIsSubset() {
        val ledger = MediaMemoryAccounting()
        val a = Any(); val b = Any()
        val kind = MediaMemoryAccounting.Kind.ARCHIVE_CACHE
        ledger.record(a,kind,10,4)
        ledger.record(a,kind,12,5)
        ledger.record(b,kind,7,2)
        assertEquals(MediaMemoryAccounting.Usage(19,7),ledger.snapshot()[kind])
        ledger.record(a,kind,0)
        assertEquals(MediaMemoryAccounting.Usage(7,2),ledger.snapshot()[kind])
        ledger.record(b,kind,0)
        assertTrue(ledger.snapshot().isEmpty())
    }
}
