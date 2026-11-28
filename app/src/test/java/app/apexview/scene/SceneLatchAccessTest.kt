package app.apexview.scene

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class SceneLatchAccessTest {
    @Test fun hungLatchCannotBlockCloseOrAuthorizeAnotherReader() {
        val access = SceneLatchAccess()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val owner = thread {
            access.worker {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNull(access.render(5L) { error("overlapping image access") })
            assertTrue(access.beginClose())
            assertTrue(access.closed)
            assertFalse(access.beginClose())
        } finally {
            resume.countDown()
            owner.join(1000L)
        }
        assertFalse(owner.isAlive)
        assertNull(access.render(5L) { error("closed owner must never admit another sampler") })
    }
}
