package app.apexview.tiled

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ArchiveCompositionStartupTest {
    @Test fun supersededSelectionCannotReleaseOrReplaceDecoder() = runBlocking {
        var disposed = false
        val events = mutableListOf<String>()
        try {
            ArchiveCompositionStartup(stage = { stage, _ -> events += stage }).start(
                preflight = { Unit }, releaseSources = { fail("Obsolete selection released decoder") },
                replaceHost = { fail("Obsolete selection replaced decoder") },
                restoreSources = { fail("Untouched decoder needs no rollback") },
                dispose = { disposed = true },
                validatePrepared = { throw CancellationException("New quality") })
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertTrue(disposed)
        assertEquals(listOf("PREFLIGHT", "VALIDATED", "SUPERSEDED"), events)
    }

    @Test fun cancellationAfterDecoderReleaseRestoresAndDoesNotInstallStaleHost() = runBlocking {
        var released = false
        var restored = false
        try {
            ArchiveCompositionStartup().start(preflight = { Unit },
                releaseSources = { released = true },
                replaceHost = { fail("Stale host") },
                restoreSources = { assertFalse(it); restored = true }, dispose = {},
                validatePrepared = { if (released) throw CancellationException("Superseded") })
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertTrue(restored)
    }

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
    @Test fun cancelledPreflightResultIsDisposedBeforeDecoderHandoff() = runBlocking {
        var disposed = false
        val startup = ArchiveCompositionStartup()
        val task = launch {
            startup.start(
                preflight = {
                    kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                    byteArrayOf(1)
                },
                releaseSources = { fail("Cancelled preflight must not release source decoders") },
                replaceHost = { fail("Cancelled preflight must not replace host") },
                restoreSources = { fail("Untouched decoders need no rollback") },
                dispose = { it.fill(0); disposed = true },
            )
        }
        task.join()
        assertTrue(disposed)
        assertFalse(startup.sourceDecodersTouched)
        assertFalse(startup.hostReplaced)
    }
}
