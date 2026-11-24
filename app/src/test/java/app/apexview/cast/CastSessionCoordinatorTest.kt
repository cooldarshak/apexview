package app.apexview.cast

import org.junit.Assert.*
import org.junit.Test

class CastSessionCoordinatorTest {
    @Test fun loadAndPreparationHaveIndependentOwnershipAndClockCannotGoBackwards() {
        val owner = CastSessionCoordinator()
        val connection = owner.beginConnection()
        val first = owner.beginPreparation()
        owner.beginLoad("old")
        assertTrue(owner.acceptClock("old", 3))
        assertFalse(owner.acceptClock("old", 2))
        assertFalse(owner.acceptClock("wrong", 100))
        assertEquals(3L, owner.clockEpoch)
        owner.beginLoad("new")
        assertEquals(connection, owner.connection)
        assertEquals(first, owner.preparation)
        assertFalse(owner.accepts("old"))
        assertTrue(owner.acceptClock("new", 0))
        assertFalse(owner.acceptClock("new", -1))
        assertTrue(owner.beginPreparation() > first)
        owner.endLoad()
        assertFalse(owner.accepts(""))
        assertFalse(owner.acceptClock(null, 0))
    }
}
