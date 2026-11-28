package app.apexview

import org.junit.Assert.*
import org.junit.Test

class DirectPresentationOwnerTest {
    @Test fun oldReleaseCannotDetachReplacementAndTerminalCannotReattach() {
        val transitions = mutableListOf<Pair<Any?, Any?>>()
        val owner = DirectPresentationOwner<Any> { old, next -> transitions.add(old to next) }
        val a = Any(); val b = Any()
        owner.attach(a); owner.attach(a); owner.attach(b); owner.detach(a)
        owner.close(); owner.attach(a); owner.detach(b)
        assertEquals(listOf(null to a, a to b, b to null), transitions)
    }

    @Test fun failedReplacementReversesTransferAndPreservesOldOwner() {
        val a = Any(); val b = Any()
        val transitions = mutableListOf<Pair<Any?, Any?>>()
        val owner = DirectPresentationOwner<Any> { old, next ->
            transitions.add(old to next)
            if (old === a && next === b) error("partial transfer")
        }
        owner.attach(a)
        assertThrows(IllegalStateException::class.java) { owner.attach(b) }
        assertTrue(owner.owns(a))
        assertFalse(owner.owns(b))
        owner.detach(b)
        owner.close()
        assertEquals(listOf(null to a, a to b, b to a, a to null), transitions)
    }

    @Test fun failedRollbackClosesBothUncertainTargetsAndRejectsNewAttachment() {
        val a = Any(); val b = Any(); val c = Any()
        val transitions = mutableListOf<Pair<Any?, Any?>>()
        val owner = DirectPresentationOwner<Any> { old, next ->
            transitions.add(old to next)
            if (old != null && next != null) error("transfer failure")
        }
        owner.attach(a)
        val failure = assertThrows(IllegalStateException::class.java) { owner.attach(b) }
        assertEquals(1, failure.suppressed.size)
        assertNull(owner.current)
        owner.attach(c)
        owner.close()
        assertEquals(listOf(null to a, a to b, b to a, b to null, a to null), transitions)
    }

    @Test fun failedDetachRetainsOwnerForTerminalRetry() {
        val target = Any()
        var detachAttempts = 0
        val owner = DirectPresentationOwner<Any> { old, next ->
            if (old === target && next == null && ++detachAttempts == 1) error("detach failure")
        }
        owner.attach(target)
        assertThrows(IllegalStateException::class.java) { owner.detach(target) }
        assertTrue(owner.owns(target))
        owner.close()
        assertEquals(2, detachAttempts)
        assertNull(owner.current)
    }
}
