package app.apexview.tiled

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchitectureGBAndroidTest {
    @Test
    fun stitchedTiledFragmentUsesNativeWorkerAndGenerationFencedLease() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val input = assets.open("gb_clear_job.bin").use { it.readBytes() }
        val expected = assets.open("gb_clear_expected.bin").use { it.readBytes() }
        val budget = requireNotNull(NativeStitchingEngine.plan(
            true, 256L * 1024 * 1024, 32L * 1024 * 1024, 32L * 1024 * 1024,
            input.size, expected.size,
        ))
        NativeStitchingEngine.open(budget).use { engine ->
            val generation = engine.generation()
            val token = engine.submitClearAtlas(generation, input, true, 5_000)
            assertTrue("Clear atlas submission failed: $token", token > 0)
            assertEquals(expected.size.toLong(), await(engine, token))
            val actual = ByteArray(expected.size)
            assertEquals(expected.size.toLong(), engine.read(token, 0, actual, 0, actual.size))
            assertArrayEquals(expected, actual)
            assertEquals(generation + 1, engine.seek())
            assertEquals(NativeStitchingEngine.STALE, engine.read(token, 0, actual, 0, actual.size))
            assertEquals(1L, engine.release(token))

            val malformed = input.copyOf(input.size - 1)
            val rejected = engine.submitClearAtlas(engine.generation(), malformed, true, 5_000)
            assertTrue(rejected > 0)
            assertEquals(NativeStitchingEngine.INVALID, await(engine, rejected))
            assertEquals(1L, engine.release(rejected))
            Log.i("PitwallGB", "GB clear tiled worker byte parity and lease fencing PASS")
        }
    }

    private fun await(engine: NativeStitchingEngine, token: Long): Long {
        val stop = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < stop) {
            val result = engine.acquire(token)
            if (result != 0L) return result
            Thread.sleep(2)
        }
        throw AssertionError("Clear native atlas worker timeout")
    }
}
