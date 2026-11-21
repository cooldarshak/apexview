package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcPocTrackerTest {
    @Test fun repeatedIdrResetsPocButDoesNotEraseTheVerifiedDecodingEpoch() {
        val tracker = HevcPocTracker()
        assertEquals(-1L, tracker.irapEpochOrdinal)
        assertEquals(0L, tracker.derive(20, 1, 0, 8))
        assertEquals(0L, tracker.irapEpochOrdinal)
        assertEquals(1L, tracker.derive(1, 1, 1, 8))
        val before = tracker.fork()
        assertEquals(0L, before.irapEpochOrdinal)
        assertEquals(0L, tracker.derive(20, 1, 0, 8))
        assertEquals(1L, tracker.irapEpochOrdinal)
        assertEquals(0L, tracker.irapAnchorPictureOrderCount)
        assertEquals(1L, tracker.derive(1, 1, 1, 8))
        assertEquals(1L, tracker.irapEpochOrdinal)
        assertEquals(0L, before.irapEpochOrdinal)
        before.commitFrom(tracker)
        assertEquals(1L, before.irapEpochOrdinal)
        tracker.reset()
        assertEquals(-1L, tracker.irapEpochOrdinal)
    }

    @Test fun freshCraAnchorAlsoStartsANewVerifiedEpoch() {
        val tracker = HevcPocTracker()
        assertEquals(10L, tracker.derive(21, 1, 10, 8, noRaslOutputFlag = true))
        assertEquals(0L, tracker.irapEpochOrdinal)
        assertEquals(11L, tracker.derive(1, 1, 11, 8))
        assertEquals(3L, tracker.derive(21, 1, 3, 8, noRaslOutputFlag = true))
        assertEquals(1L, tracker.irapEpochOrdinal)
        assertEquals(3L, tracker.irapAnchorPictureOrderCount)
    }

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
        assertEquals(0L, tracker.irapAnchorPictureOrderCount)
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
        assertEquals(null, tracker.irapAnchorPictureOrderCount)
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
        assertEquals(null, tracker.irapAnchorPictureOrderCount)
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
        assertEquals(12L, bla.irapAnchorPictureOrderCount)

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
        assertEquals(21L, cra.irapAnchorPictureOrderCount)
    }
}
