package app.apexview
import org.junit.Assert.*
import org.junit.Test
class MediaMemoryAccountingTest {
    @Test fun diagnosticsDistinguishSimultaneousPeakFromCurrentAndRetiredOwners() {
        val ledger = MediaMemoryAccounting()
        val a = Any(); val b = Any()
        val kind = MediaMemoryAccounting.Kind.CLEAR_SOURCE
        ledger.record(a, kind, 20)
        ledger.record(b, kind, 30, 10)
        assertTrue(ledger.diagnosticFields().contains("CLEAR_SOURCEPeakOwnedBytes=50"))
        ledger.record(a, kind, 0)
        val current = ledger.diagnosticFields()
        assertTrue(current.contains("CLEAR_SOURCEOwnedBytes=30"))
        assertTrue(current.contains("heapOwnerDetail=2:CLEAR_SOURCE:30:10"))
        ledger.record(b, kind, 0)
        assertTrue(ledger.diagnosticFields().contains("CLEAR_SOURCEOwnedBytes=0"))
        assertTrue(ledger.diagnosticFields().contains("CLEAR_SOURCEPeakOwnedBytes=50"))
        assertTrue(ledger.diagnosticFields().contains("heapOwnerDetail=NONE"))
    }

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
