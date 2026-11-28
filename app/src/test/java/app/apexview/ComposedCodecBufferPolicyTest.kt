package app.apexview

import org.junit.Assert.*
import org.junit.Test

class ComposedCodecBufferPolicyTest {
    private fun eligible(api: Int = 30, enabled: Boolean = true, composed: Boolean = true,
                         hevc: Boolean = true, hardware: Boolean = true, protected: Boolean = false,
                         surface: Boolean = true, supported: Boolean = true, existing: Boolean = false) =
        ComposedCodecBufferPolicy.eligible(api, enabled, composed, hevc, hardware, protected, surface, supported, existing)

    @Test fun admissionRequiresEveryCapabilityAndUnprotectedComposedSurface() {
        assertTrue(eligible())
        assertFalse(eligible(api = 29))
        assertFalse(eligible(enabled = false))
        assertFalse(eligible(composed = false))
        assertFalse(eligible(hevc = false))
        assertFalse(eligible(hardware = false))
        assertFalse(eligible(protected = true))
        assertFalse(eligible(surface = false))
        assertFalse(eligible(supported = false))
        assertFalse(eligible(existing = true))
    }

    @Test fun successfulTrialNeverConstructsSecondCodec() {
        val events = mutableListOf<String>()
        val result = ComposedCodecBufferPolicy.create(
            { events.add("trial"); 7 }, { events.add("baseline"); 9 }, { events.add("rejected") })
        assertEquals(7, result)
        assertEquals(listOf("trial"), events)
    }

    @Test fun rejectedTrialRetriesOriginalExactlyOnceAndPreservesBothFailures() {
        val first = IllegalArgumentException("trial")
        val second = IllegalStateException("baseline")
        val events = mutableListOf<String>()
        val result = ComposedCodecBufferPolicy.create(
            { events.add("trial"); throw first }, { events.add("baseline"); 9 },
            { assertSame(first, it); events.add("rejected") })
        assertEquals(9, result)
        assertEquals(listOf("trial", "rejected", "baseline"), events)
        events.clear()
        try {
            ComposedCodecBufferPolicy.create<Int>(
                { events.add("trial"); throw first }, { events.add("baseline"); throw second },
                { events.add("rejected") })
            fail("Expected baseline failure")
        } catch (error: IllegalStateException) {
            assertSame(second, error)
            assertSame(first, error.suppressed.single())
        }
        assertEquals(listOf("trial", "rejected", "baseline"), events)
    }

    @Test fun terminalReleaseContinuesAfterIndividualOwnerFailure() {
        val retired = mutableListOf<Int>()
        val failures = mutableListOf<Exception>()
        retirePlaybackOwners(listOf(1, 2, 3), {
            retired.add(it)
            if (it == 2) throw IllegalStateException("release")
        }, { failures.add(it) })
        assertEquals(listOf(1, 2, 3), retired)
        assertEquals(1, failures.size)
    }
}
