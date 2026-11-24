package app.apexview.tiled

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ArchiveCompositionStartupTest {
    @Test fun permanentPreflightFailurePreservesPlayingSources() = runBlocking {
        for (reason in listOf("HLS-only offer", "DASH unavailable", "HEVC incompatible", "Fragment alignment failure", "DRM incompatible")) {
            var playingSources = 3
            val startup = ArchiveCompositionStartup()
            try {
                startup.start<Unit>(
                    preflight = { throw IOException(reason) },
                    releaseSources = { playingSources = 0 },
                    replaceHost = { fail("Must not replace a playing source") },
                    restoreSources = { fail("Preflight must not touch sources needing restoration") },
                    dispose = { fail("No output was acquired") },
                )
                fail("Expected failure")
            } catch (expected: IOException) { assertEquals(reason, expected.message) }
            assertEquals(3, playingSources)
            assertFalse(startup.sourceDecodersTouched)
            assertFalse(startup.hostReplaced)
        }
    }

    @Test fun releasesOnlyAfterPreparedOutputThenStartsTheHost() = runBlocking {
        val events = mutableListOf<String>()
        ArchiveCompositionStartup().start(
            preflight = { events += "validated"; "output" },
            releaseSources = { events += "released" },
            replaceHost = { assertEquals("output", it); events += "started" },
            restoreSources = { fail("Unexpected rollback") },
            dispose = { events += "zeroed" },
        )
        assertEquals(listOf("validated", "released", "started", "zeroed"), events)
    }

    @Test fun failedHostReplacementRestoresTheOriginalHostAndZeroesOutput() = runBlocking {
        var restored = false
        var zeroed = false
        try {
            ArchiveCompositionStartup().start(
                preflight = { byteArrayOf(1) }, releaseSources = {},
                replaceHost = { throw IOException("decoder initialization failed") },
                restoreSources = { replaced -> assertTrue(replaced); restored = true },
                dispose = { it.fill(0); zeroed = it.all { b -> b == 0.toByte() } },
            )
            fail("Expected failure")
        } catch (_: IOException) {}
        assertTrue(restored)
        assertTrue(zeroed)
    }

    @Test fun cancelledDecoderReleaseRestoresVideoWithoutReplacingTheHost() = runBlocking {
        var restored = false
        try {
            ArchiveCompositionStartup().start(
                preflight = { Unit }, releaseSources = { throw CancellationException("cancel") },
                replaceHost = { fail("Cancelled handoff") },
                restoreSources = { replaced -> assertFalse(replaced); restored = true }, dispose = {},
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertTrue(restored)
    }
}
