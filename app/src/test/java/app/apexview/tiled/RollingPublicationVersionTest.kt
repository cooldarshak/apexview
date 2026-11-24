package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class RollingPublicationVersionTest {
    @Test fun duplicatesAndMissingOutputNeverRebuildProtectedDashPackage() {
        val gate = RollingPublicationVersion()
        assertFalse(gate.shouldPublish(null))
        assertTrue(gate.shouldPublish(1L))
        assertTrue(gate.shouldPublish(1L)) // failure must remain retryable
        gate.published(1L)
        assertFalse(gate.shouldPublish(1L))
        assertTrue(gate.shouldPublish(2L))
        gate.published(2L)
        assertFalse(gate.shouldPublish(2L))
    }

    @Test fun explicitSessionOrSeekResetStartsWithIndependentVersion() {
        val first = RollingPublicationVersion()
        first.published(2L)
        val nextSession = RollingPublicationVersion()
        assertTrue(nextSession.shouldPublish(1L))
        assertThrows(IllegalArgumentException::class.java) { nextSession.published(0L) }
        // A fragment's timestamp may rebase, but a new composed output has
        // a fresh publication version and is never silently skipped.
        nextSession.published(1L)
        assertTrue(nextSession.shouldPublish(2L))
    }
}
