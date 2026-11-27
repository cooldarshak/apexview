package app.apexview

import org.junit.Assert.*
import org.junit.Test

class ArchiveForwardBufferPolicyTest {
    private fun observe(policy: ArchiveForwardBufferPolicy, lane: ArchiveForwardBufferPolicy.Lane,
                        first: Long, duration: Long = 500, segment: Long = 6000) {
        repeat(6) {
            val id = first + it
            policy.start(id, lane)
            policy.complete(id, duration, segment, 1000L + it)
        }
    }
    @Test fun needsBothMeasuredMediaLanesAndPreservesConservativeSpeedMargin() {
        val policy = ArchiveForwardBufferPolicy()
        policy.reset(true)
        observe(policy, ArchiveForwardBufferPolicy.Lane.VIDEO, 10)
        assertNull(policy.target(2000, 1f))
        observe(policy, ArchiveForwardBufferPolicy.Lane.AUDIO, 20)
        assertEquals(20_000_000L, policy.target(2000, 1f)?.highUs)
        assertEquals(14_000_000L, policy.target(2000, 1f)?.lowUs)
        assertEquals(21_000_000L, policy.target(2000, 1.5f)?.highUs)
        assertNull(policy.target(2000, 2f))
        assertNull(policy.target(130000, 1f))
    }
    @Test fun slowSourceAndGenerationChangesRestoreNormalPolicy() {
        val policy = ArchiveForwardBufferPolicy()
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
    @Test fun cancelledAndInvalidLoadsCannotQualify() {
        val policy = ArchiveForwardBufferPolicy()
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
