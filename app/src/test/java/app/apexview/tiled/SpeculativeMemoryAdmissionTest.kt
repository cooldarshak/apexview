package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class SpeculativeMemoryAdmissionTest {
    @Test fun overlappingForecastsShareOneLimitAndReleaseOnce() {
        val gate = SpeculativeMemoryAdmission()
        val first = requireNotNull(gate.tryReserve(40, 100))
        val second = requireNotNull(gate.tryReserve(60, 100))
        assertNull(gate.tryReserve(1, 100))
        assertNull(gate.tryReserve(1, 20))
        assertEquals(100L to 100L, gate.snapshot())
        first.close(); first.close()
        assertEquals(60L to 100L, gate.snapshot())
        second.close()
        assertEquals(0L to 100L, gate.snapshot())
        assertNull(gate.tryReserve(Long.MAX_VALUE, 100))
    }
    @Test fun invalidObservationsAndPressureDoNotCreateAReservation() {
        assertNull(SpeculativeMemoryAdmission.charge(0, 10))
        assertNull(SpeculativeMemoryAdmission.charge(Long.MAX_VALUE, 10))
        assertEquals(8L * 1024 * 1024 + 50L, SpeculativeMemoryAdmission.charge(10, 20))
        assertEquals(0L, SpeculativeMemoryAdmission.limit(NativeStitchingDeviceBudget.Headroom(true, true, 1000, 100, 1000)))
        assertEquals(50L, SpeculativeMemoryAdmission.limit(NativeStitchingDeviceBudget.Headroom(false, false, 1000, 200, 100)))
    }

    @Test fun foregroundIsNeverRejectedAndBlocksOnlyNewSpeculation() {
        val gate = SpeculativeMemoryAdmission()
        val optional = requireNotNull(gate.tryReserve(40, 100))
        val mandatory = gate.beginForeground(90)
        assertNull(gate.tryReserve(1, 100))
        assertEquals(90L to 90L, gate.foregroundSnapshot())
        mandatory.close(); mandatory.close()
        val later = requireNotNull(gate.tryReserve(60, 100))
        later.close(); optional.close()
        assertEquals(0L to 100L, gate.snapshot())
        assertEquals(0L to 90L, gate.foregroundSnapshot())
    }

}
