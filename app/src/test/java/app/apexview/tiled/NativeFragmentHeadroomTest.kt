package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class NativeFragmentHeadroomTest {
    @Test fun recoveryTierReservesStagingAndResamplesLowMemory() {
        val vectors = listOf(
            longArrayOf(5474084,7002945,621977600,150994944,303428224),
            longArrayOf(5226177,6258463,651563008,150994944,321931440),
            longArrayOf(5591105,6324612,619515904,150994944,268680776),
            longArrayOf(5221449,6295460,672854016,150994944,323391488),
        )
        for (v in vectors) {
            val h = NativeStitchingDeviceBudget.Headroom(false,false,v[2],v[3],v[4])
            val budget = requireNotNull(NativeStitchingEngine.planClearAtlas(false,v[2],v[3],v[4],v[0].toInt(),v[1].toInt()))
            assertTrue(budget.foregroundRecovery)
            val staging = budget.inputBytes.toLong()+2L*budget.outputBytes+8L*1024*1024
            assertEquals(((h.available-h.threshold)/4-staging).coerceAtLeast(0),
                NativeStitchingDeviceBudget.fragmentHeadroom(h,budget))
            assertEquals((h.available-h.threshold)/8,NativeStitchingDeviceBudget.fragmentHeadroom(h))
            assertEquals(0L,NativeStitchingDeviceBudget.fragmentHeadroom(h.copy(jvmHeadroom=staging-1),budget))
            assertEquals(0L,NativeStitchingDeviceBudget.fragmentHeadroom(h.copy(lowMemory=true),budget))
            assertEquals((h.available-h.threshold)/8,NativeStitchingDeviceBudget.fragmentHeadroom(h.copy(lowRam=true),budget))
            assertEquals(0L,NativeStitchingDeviceBudget.fragmentHeadroom(h.copy(available=h.threshold),budget))
        }
        assertEquals(0L,NativeStitchingDeviceBudget.fragmentHeadroom(null))
    }
}
