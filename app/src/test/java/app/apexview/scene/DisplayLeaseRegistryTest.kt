package app.apexview.scene

import org.junit.Assert.*
import org.junit.Test

class DisplayLeaseRegistryTest {
    @Test fun overlappingOwnersCannotTerminateEachOthersDisplay() {
        var initialized = 0
        var terminated = 0
        val registry = DisplayLeaseRegistry<String>({ initialized++; true }, { terminated++; true })
        repeat(1000) {
            val viewer = registry.acquire("default")
            val encoder = registry.acquire("default")
            assertEquals(2, registry.count("default"))
            viewer.close(); viewer.close()
            assertEquals(it, terminated)
            val replacement = registry.acquire("default")
            encoder.close()
            assertEquals(it, terminated)
            replacement.close()
            assertEquals(0, registry.count("default"))
        }
        assertEquals(1000, initialized)
        assertEquals(initialized, terminated)
    }

    @Test fun failedInitializationDoesNotCreateAReleaseObligation() {
        var terminated = false
        val registry = DisplayLeaseRegistry<String>({ false }, { terminated = true; true })
        assertThrows(IllegalStateException::class.java) { registry.acquire("default") }
        assertEquals(0, registry.count("default"))
        assertFalse(terminated)
    }
}
