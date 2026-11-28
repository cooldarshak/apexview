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
}
