package app.apexview.tiled
import org.junit.Assert.*
import org.junit.Test
class MediaPressurePolicyTest {
    @Test fun pressureTrimsAtMostOncePerCooldownAndRecoveryNeedsSustainedMargin() {
        val p = MediaPressurePolicy(30_000, 10_000)
        val threshold = 100L * 1024 * 1024
        fun h(low: Boolean, available: Long) = NativeStitchingDeviceBudget.Headroom(false,low,available,threshold,available)
        assertEquals(MediaPressurePolicy.Decision(true,true), p.observe(h(true,threshold),0))
        assertEquals(MediaPressurePolicy.Decision(true,false), p.observe(h(true,threshold),1000))
        assertTrue(p.observe(h(false,threshold+1),2000).constrained)
        assertTrue(p.observe(h(false,2*threshold),3000).constrained)
        assertTrue(p.observe(h(false,2*threshold),12999).constrained)
        assertFalse(p.observe(h(false,2*threshold),13000).constrained)
        assertFalse(p.observe(h(true,threshold),14000).trimUnused)
        assertTrue(p.observe(h(true,threshold),30000).trimUnused)
    }
}
