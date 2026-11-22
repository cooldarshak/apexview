package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class RollingPublicationVersionTest {
    @Test fun duplicatesAndMissingOutputNeverRebuildProtectedDashPackage() {
        val gate = RollingPublicationVersion()
        assertFalse(gate.shouldPublish(null))
        assertTrue(gate.shouldPublish(17_280_000L))
        assertTrue(gate.shouldPublish(17_280_000L)) // failure must remain retryable
        gate.published(17_280_000L)
        assertFalse(gate.shouldPublish(17_280_000L))
        assertTrue(gate.shouldPublish(23_040_000L))
        gate.published(23_040_000L)
        assertFalse(gate.shouldPublish(23_040_000L))
    }

    @Test fun explicitSessionOrSeekResetStartsWithIndependentVersion() {
        val first = RollingPublicationVersion()
        first.published(23_040_000L)
        val nextSession = RollingPublicationVersion()
        assertTrue(nextSession.shouldPublish(23_040_000L))
        assertThrows(IllegalArgumentException::class.java) { nextSession.published(-1L) }
    }
}
