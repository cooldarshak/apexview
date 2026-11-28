package app.apexview

import org.junit.Assert.*
import org.junit.Test

class ArchiveForwardBufferPolicyTest {
    private fun policy(producerMs: Long = 500): ArchiveForwardBufferPolicy =
        ArchiveForwardBufferPolicy().apply {
            val producer = ArchiveForwardBufferPolicy.ProductionWindow()
            repeat(6) { producer.record(producerMs, 6000, 1000L + it) }
            bindProduction(producer)
        }
    @Test fun selectedChildCaptionsAndUpstreamAreRequired() {
        val policy = policy(2500)
        policy.select(true, 1, 3)
        fun child(index: Int, first: Long) {
            repeat(6) {
                val task = first + it
                policy.startChild(task, index)
                policy.complete(task, 500, 5760, 1000L + it)
            }
        }
        child(0, 10); child(1, 20); child(2, 30)
        assertNull(policy.target(2000, 1f))
        child(3, 40)
        assertEquals(25_000_000L, policy.target(2000, 1f)?.highUs)
        assertEquals(27_780_000L, policy.target(2000, 1.5f)?.highUs)
        policy.select(true, 2, 3)
        assertNull(policy.target(2000, 1f))
        val slow = policy(6000)
        slow.reset(true)
        observe(slow, ArchiveForwardBufferPolicy.Lane.VIDEO, 100)
        observe(slow, ArchiveForwardBufferPolicy.Lane.AUDIO, 200)
        assertNull(slow.target(2000, 1f))
    }

    @Test fun selectedRetriesRestoreDefaultAndCannotCountAsCleanDelivery() {
        val policy = policy(2500)
        policy.select(true, 1, 3)
        fun child(index: Int, first: Long, count: Int = 6, retry: Int = 0) {
            repeat(count) {
                val task = first + it
                policy.startChild(task, index, retry)
                policy.complete(task, 500, 5760, 1000L + it)
            }
        }
        child(0, 10); child(1, 20); child(3, 30)
        assertNotNull(policy.target(2000, 1f))
        child(2, 40, 1, 1) // Unselected alternate source.
        assertNotNull(policy.target(2000, 1f))
        child(3, 50, 1, 1)
        assertNull(policy.target(2000, 1f))
        child(0, 60); child(1, 70); child(3, 80, 5)
        assertNull(policy.target(2000, 1f))
        child(3, 90, 1)
        assertNotNull(policy.target(2000, 1f))
        policy.startChild(100, 0)
        policy.failure(100)
        assertNull(policy.target(2000, 1f))
        policy.startChild(100, 0, 1)
        policy.complete(100, 1, 5760, 2000)
        child(1, 110); child(3, 120); child(0, 130, 5)
        assertNull(policy.target(2000, 1f))
        child(0, 140, 1)
        assertNotNull(policy.target(2000, 1f))
    }

    private fun observe(policy: ArchiveForwardBufferPolicy, lane: ArchiveForwardBufferPolicy.Lane,
                        first: Long, duration: Long = 500, segment: Long = 6000) {
        repeat(6) {
            val id = first + it
            policy.start(id, lane)
            policy.complete(id, duration, segment, 1000L + it)
        }
    }
    @Test fun needsBothMeasuredMediaLanesAndPreservesConservativeSpeedMargin() {
        val policy = policy()
        policy.reset(true)
        observe(policy, ArchiveForwardBufferPolicy.Lane.VIDEO, 10)
        assertNull(policy.target(2000, 1f))
        observe(policy, ArchiveForwardBufferPolicy.Lane.AUDIO, 20)
        assertEquals(25_000_000L, policy.target(2000, 1f)?.highUs)
        assertEquals(19_000_000L, policy.target(2000, 1f)?.lowUs)
        assertEquals(25_000_000L, policy.target(2000, 1.5f)?.highUs)
        assertNull(policy.target(2000, 2f))
        assertNull(policy.target(130000, 1f))
    }
    @Test fun slowSourceAndGenerationChangesRestoreNormalPolicy() {
        val policy = policy()
        policy.reset(true)
        observe(policy, ArchiveForwardBufferPolicy.Lane.VIDEO, 1, 4500)
        observe(policy, ArchiveForwardBufferPolicy.Lane.AUDIO, 10)
        assertNotNull(policy.target(2000, 1f))
        assertNull(policy.target(2000, 1.5f))
        policy.start(99, ArchiveForwardBufferPolicy.Lane.VIDEO)
        policy.reset()
        policy.complete(99, 1, 6000, 2000)
        assertNull(policy.target(2000, 1f))
        observe(policy, ArchiveForwardBufferPolicy.Lane.VIDEO, 100)
        observe(policy, ArchiveForwardBufferPolicy.Lane.AUDIO, 200)
        assertNotNull(policy.target(2000, 1f))
        policy.reset(false)
        assertNull(policy.target(2000, 1f))
    }
    @Test fun bytePressureNeverCutsBelowMeasuredLowWatermark() {
        val hold = ArchiveBufferHold()
        val target = ArchiveForwardBufferPolicy.Target(19_000_000L, 25_000_000L, 1L)
        val highBytes = 81L * 1024 * 1024
        assertFalse(hold.hold(target, 18_999_999L, highBytes))
        assertTrue(hold.hold(target, 20_000_000L, highBytes))
        assertTrue(hold.hold(target, 20_000_000L, 1L))
        assertFalse(hold.hold(target, 18_999_999L, highBytes))
        assertTrue(hold.hold(target, 25_000_000L, 1L))
        assertFalse(hold.hold(null, 25_000_000L, highBytes))
        assertFalse(hold.hold(target.copy(generation = 2L), 20_000_000L, 1L))
    }

    @Test fun retiredProductionWindowCannotQualifyAnotherSession() {
        val policy = policy()
        policy.reset(true)
        observe(policy, ArchiveForwardBufferPolicy.Lane.VIDEO, 1)
        observe(policy, ArchiveForwardBufferPolicy.Lane.AUDIO, 10)
        assertNotNull(policy.target(2000, 1f))
        policy.unbindProduction()
        policy.reset(true)
        observe(policy, ArchiveForwardBufferPolicy.Lane.VIDEO, 100)
        observe(policy, ArchiveForwardBufferPolicy.Lane.AUDIO, 200)
        assertNull(policy.target(2000, 1f))
    }

    @Test fun cancelledAndInvalidLoadsCannotQualify() {
        val policy = policy()
        policy.reset(true)
        observe(policy, ArchiveForwardBufferPolicy.Lane.VIDEO, 1)
        observe(policy, ArchiveForwardBufferPolicy.Lane.AUDIO, 10)
        policy.start(40, ArchiveForwardBufferPolicy.Lane.VIDEO)
        policy.cancel(40)
        policy.complete(40, 99999, 6000, 2000)
        assertNotNull(policy.target(2000, 1f))
        policy.start(41, ArchiveForwardBufferPolicy.Lane.VIDEO)
        policy.complete(41, 99999, 6000, 2000)
        assertNull(policy.target(2000, 1f))
    }
}
