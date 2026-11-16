package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcPocTrackerTest {
    @Test fun refusesToInventFullPocBeforeAnIrapAnchor() {
        val tracker = HevcPocTracker()
        val error = assertThrows(IllegalArgumentException::class.java) {
            tracker.derive(
                nalUnitType = 1,
                temporalIdPlus1 = 1,
                pocLsb = 3,
                log2MaxPocLsb = 8
            )
        }
        assertTrue(error.message!!.contains("before a valid IRAP reference anchor"))
        assertFalse(tracker.hasReferenceAnchor)
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
    }

    @Test fun craWithoutNoRaslOutputFlagRequiresAValidEarlierAnchor() {
        val tracker = HevcPocTracker()
        val error = assertThrows(IllegalArgumentException::class.java) {
            tracker.derive(
                nalUnitType = 21,
                temporalIdPlus1 = 1,
                pocLsb = 12,
                log2MaxPocLsb = 8,
                noRaslOutputFlag = false
            )
        }
        assertTrue(error.message!!.contains("before a valid IRAP reference anchor"))
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
    }
}
