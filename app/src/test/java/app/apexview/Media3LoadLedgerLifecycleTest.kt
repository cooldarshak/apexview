package app.apexview

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class Media3LoadLedgerLifecycleTest {
    @Test fun retiredLoadsNeverChargeTheNextLifecycle() {
        val ledger = Media3LoadLedger(2)
        ledger.start(1, C.TRACK_TYPE_VIDEO)
        ledger.start(2, C.TRACK_TYPE_AUDIO)
        ledger.finish(1, 100)
        ledger.clearPending()
        ledger.finish(2, 900)
        assertEquals(0, ledger.snapshot().pending)
        assertEquals(100L, ledger.snapshot().videoBytes)
        assertEquals(0L, ledger.snapshot().audioBytes)
        repeat(1000) { i ->
            ledger.start(100L + i, C.TRACK_TYPE_TEXT)
            ledger.clearPending()
            ledger.finish(100L + i, 50)
        }
        assertEquals(0, ledger.snapshot().pending)
        assertEquals(0L, ledger.snapshot().textBytes)
    }

    @Test fun boundedPendingAndDuplicateTerminalEventsDoNotOvercount() {
        val ledger = Media3LoadLedger(2)
        ledger.start(1, C.TRACK_TYPE_VIDEO)
        ledger.start(2, C.TRACK_TYPE_AUDIO)
        ledger.start(3, C.TRACK_TYPE_UNKNOWN)
        ledger.finish(1, 1000)
        ledger.finish(2, 45)
        ledger.finish(2, 45)
        ledger.finish(3, 10)
        val snapshot = ledger.snapshot()
        assertEquals(0, snapshot.pending)
        assertEquals(1L, snapshot.untracked)
        assertEquals(0L, snapshot.videoBytes)
        assertEquals(45L, snapshot.audioBytes)
        assertEquals(10L, snapshot.otherBytes)
    }
}
