package app.apexview.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class BakedProducerPressureTest {
    @Test fun hysteresisAndBoundedFailure() {
        val p = BakedProducerPressure()
        assertEquals(BakedProducerPressure.State.RUN, p.update(0, false, true))
        assertEquals(BakedProducerPressure.State.HOLD, p.update(100, true, false))
        assertEquals(BakedProducerPressure.State.HOLD, p.update(500, false, false))
        assertEquals(BakedProducerPressure.State.RUN, p.update(700, false, true))
        assertEquals(BakedProducerPressure.State.HOLD, p.update(800, true, false))
        assertEquals(BakedProducerPressure.State.HOLD, p.update(10_799, true, false))
        assertEquals(BakedProducerPressure.State.EXPIRED, p.update(10_800, true, false))
    }
}
