package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcPocTrackerTest {
    @Test fun legacyPocDerivationStillSupportsSequencesWithoutAnInitialIrap() {
        val tracker = HevcPocTracker()
        assertEquals(
            3L,
            tracker.derive(
                nalUnitType = 1,
                temporalIdPlus1 = 1,
                pocLsb = 3,
                log2MaxPocLsb = 8
            )
        )
        assertTrue(tracker.hasReferenceAnchor)
        assertFalse(tracker.hasIrapAnchor)
    }

    @Test fun idrEstablishesAnchorForFollowingInterPictures() {
        val tracker = HevcPocTracker()
        assertEquals(
            0L,
            tracker.derive(
                nalUnitType = 20,
                temporalIdPlus1 = 1,
                pocLsb = 0,
                log2MaxPocLsb = 8
            )
        )
        assertTrue(tracker.hasReferenceAnchor)
        assertTrue(tracker.hasIrapAnchor)
        assertEquals(
            1L,
            tracker.derive(
                nalUnitType = 1,
                temporalIdPlus1 = 1,
                pocLsb = 1,
                log2MaxPocLsb = 8
            )
        )
        tracker.reset()
        assertFalse(tracker.hasReferenceAnchor)
        assertFalse(tracker.hasIrapAnchor)
    }

    @Test fun craWithoutNoRaslOutputFlagUsesExistingPrevTid0ButDoesNotEstablishIrapAnchor() {
        val tracker = HevcPocTracker()
        assertEquals(
            12L,
            tracker.derive(
                nalUnitType = 21,
                temporalIdPlus1 = 1,
                pocLsb = 12,
                log2MaxPocLsb = 8,
                noRaslOutputFlag = false
            )
        )
        assertTrue(tracker.hasReferenceAnchor)
        assertFalse(tracker.hasIrapAnchor)
    }

    @Test fun blaAndCraWithNoRaslOutputFlagEstablishAnAnchor() {
        val bla = HevcPocTracker()
        assertEquals(
            12L,
            bla.derive(
                nalUnitType = 16,
                temporalIdPlus1 = 1,
                pocLsb = 12,
                log2MaxPocLsb = 8
            )
        )
        assertTrue(bla.hasReferenceAnchor)
        assertTrue(bla.hasIrapAnchor)

        val cra = HevcPocTracker()
        assertEquals(
            21L,
            cra.derive(
                nalUnitType = 21,
                temporalIdPlus1 = 1,
                pocLsb = 21,
                log2MaxPocLsb = 8,
                noRaslOutputFlag = true
            )
        )
        assertTrue(cra.hasReferenceAnchor)
        assertTrue(cra.hasIrapAnchor)
    }
}
