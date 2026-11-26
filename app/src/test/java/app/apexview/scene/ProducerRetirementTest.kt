package app.apexview.scene

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ProducerRetirementTest {
    @Test fun retirementWaitsForAttachedProducerAndRejectsReplacement() {
        val owner = ProducerRetirement()
        val released = AtomicInteger()
        assertTrue(owner.attach())
        owner.retire { released.incrementAndGet() }
        assertTrue(owner.closed)
        assertEquals(0, released.get())
        assertFalse(owner.attach())
        owner.detach()
        owner.detach()
        owner.retire { released.incrementAndGet() }
        assertEquals(1, released.get())
    }

    @Test fun racingRetirementAndDetachRetireExactlyOnce() {
        repeat(200) {
            val owner = ProducerRetirement()
            val released = AtomicInteger()
            val go = CountDownLatch(1)
            assertTrue(owner.attach())
            val detach = Thread { go.await(); owner.detach() }
            val retire = Thread { go.await(); owner.retire { released.incrementAndGet() } }
            detach.start(); retire.start(); go.countDown()
            detach.join(); retire.join()
            assertEquals(1, released.get())
            assertFalse(owner.attach())
        }
    }

    @Test fun neverAttachedRetiresImmediately() {
        val owner = ProducerRetirement()
        var released = false
        owner.retire { released = true }
        assertTrue(released)
        assertFalse(owner.attach())
    }
}
