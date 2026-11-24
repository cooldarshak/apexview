package app.apexview

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on Android 29+ hardware/instrumentation, not the JVM fixture runner.
 * Exercises the native RenderNode recording API used by all tower regions.
 */
@RunWith(AndroidJUnit4::class)
class OverlayRetainedRenderNodeAndroidTest {
    @Test fun threeNativeRegionsAreIndependentlyInvalidatedAcrossFrameReplays() {
        val layers = RetainedTowerRowLayers()
        val keys = Array(3) { Any() }
        try {
            layers.regions.forEachIndexed { index, retained ->
                val first = retained.beginIfChanged(keys[index], 722, 68,
                    index, 2f)
                assertNotNull("region $index must record on first frame", first)
                first!!.drawColor(Color.BLUE)
                retained.finish(keys[index], 722, 68)
                assertTrue(retained.node.hasDisplayList())
                repeat(10) {
                    assertNull("unchanged region $index must reuse its display list",
                        retained.beginIfChanged(keys[index], 722, 68, index, 2f))
                }
                assertEquals(1L, retained.validity.recordings)
                assertEquals(10L, retained.validity.replays)
            }
            val sector = layers.regions[1]
            val changedSector = Any()
            val redraw = sector.beginIfChanged(changedSector, 722, 68, 1, 2f)
            assertNotNull(redraw)
            redraw!!.drawColor(Color.GREEN)
            sector.finish(changedSector, 722, 68)
            assertEquals(2L, sector.validity.recordings)
            assertEquals(1L, layers.regions[0].validity.recordings)
            assertEquals(1L, layers.regions[2].validity.recordings)
        } finally {
            layers.discard()
        }
    }

    @Test fun retainedTowerNodeRecordsAndReplaysUntilVisualKeyOrSizeChanges() {
        val node = RetainedTowerRenderNode()
        val first = Any()
        val recorded = node.beginIfChanged(first, 361, 34)
        assertNotNull(recorded)
        recorded!!.drawColor(Color.BLACK)
        node.finish(first, 361, 34)
        assertTrue(node.node.hasDisplayList())
        assertNull(node.beginIfChanged(first, 361, 34))
        assertEquals(1L, node.validity.recordings)
        assertEquals(1L, node.validity.replays)

        val second = Any()
        val updated = node.beginIfChanged(second, 361, 34)
        assertNotNull(updated)
        updated!!.drawColor(Color.BLUE)
        node.finish(second, 361, 34)
        assertNull(node.beginIfChanged(second, 361, 34))
        assertTrue(node.node.hasDisplayList())
        assertEquals(2L, node.validity.recordings)
        // Renderer may evict the native display list when a LazyColumn row scrolls away.
        node.node.discardDisplayList()
        assertFalse(node.node.hasDisplayList())
        val rerecord = node.beginIfChanged(second, 361, 34)
        assertNotNull("evicted list must never draw blank on return", rerecord)
        rerecord!!.drawColor(Color.GREEN)
        node.finish(second, 361, 34)
        assertTrue(node.node.hasDisplayList())
        assertEquals(3L, node.validity.recordings)
        node.discard()
    }
}
