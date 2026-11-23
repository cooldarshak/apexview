package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionDiagnosticOwnerTest {
    @Test fun removingFeedsThreeToTwoToOneCannotResurrectCancelledComposition() {
        val owner = CompositionDiagnosticOwner()
        var displayed: String? = null
        val three = owner.begin()
        if (owner.isOwner(three)) displayed = "Composition feeds: 3 · Playback: ACTIVE"
        val two = owner.begin()
        displayed = null
        if (owner.isOwner(three)) displayed = "Composition feeds: 3 · Playback: CANCELLED"
        assertNull(displayed)
        assertTrue(owner.isOwner(two))
        displayed = "Composition feeds: 2 · Playback: ACTIVE"

        // Removing the last extra feed returns to the native single-feed player.
        owner.invalidate()
        displayed = null
        if (owner.isOwner(two)) displayed = "Composition feeds: 2 · Playback: CANCELLED"
        assertNull(displayed)
        assertFalse(owner.isOwner(three))
        assertFalse(owner.isOwner(two))
    }

    @Test fun rapidSelectionsAndReplacementAllowOnlyLatestPublisher() {
        val owner = CompositionDiagnosticOwner()
        val generations = (0 until 1000).map { owner.begin() }
        assertEquals(generations.size, generations.distinct().size)
        assertTrue(owner.isOwner(generations.last()))
        generations.dropLast(1).forEach { assertFalse(owner.isOwner(it)) }
    }

    @Test fun seekDecoderSwitchAndSessionCloseRevokePreviousAttempt() {
        val owner = CompositionDiagnosticOwner()
        val beforeSeek = owner.begin()
        owner.invalidate()
        assertFalse(owner.isOwner(beforeSeek))
        val restarted = owner.begin()
        assertTrue(owner.isOwner(restarted))
        val replacement = owner.begin() // switch to another decoder/composition
        assertFalse(owner.isOwner(restarted))
        assertTrue(owner.isOwner(replacement))
        owner.invalidate() // closing the session
        assertFalse(owner.isOwner(replacement))
    }

    @Test fun oldProtectedFailureCannotBecomeCurrentAfterNewClearAttempt() {
        val owner = CompositionDiagnosticOwner()
        val protected = owner.begin()
        val clear = owner.begin()
        assertFalse(owner.isOwner(protected))
        assertTrue(owner.isOwner(clear))
        owner.invalidate()
        assertFalse(owner.isOwner(clear))
        assertTrue(owner.isOwner(owner.begin()))
    }
}
