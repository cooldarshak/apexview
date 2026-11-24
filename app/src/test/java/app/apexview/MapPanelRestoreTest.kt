package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MapPanelRestoreTest {
    @Test fun restoredOverlayPositionScalesToPhoneAndTvCanvas() {
        val panel = MapPanel()
        panel.x = 180f; panel.y = 90f; panel.w = 240f; panel.h = 180f
        panel.rotation = -12f
        val serialized = panel.toJson(600f, 400f, 0.65f)
        val tv = MapPanel()
        assertEquals(0.65f, tv.restore(serialized, 1200f, 800f)!!, 0.0001f)
        assertEquals(360f, tv.x, 0.0001f)
        assertEquals(180f, tv.y, 0.0001f)
        assertEquals(480f, tv.w, 0.0001f)
        assertEquals(360f, tv.h, 0.0001f)
        assertEquals(-12f, tv.rotation, 0.0001f)
        assertTrue(tv.placed)
    }

    @Test fun invalidBackupNeverPartiallyMovesTheVisiblePanel() {
        val panel = MapPanel()
        panel.place(Area(5f, 6f, 220f, 260f))
        val invalid = """{"x":0.9,"y":0.1,"w":0.3,"h":0,"rotation":0,"opacity":0.7}"""
        assertNull(panel.restore(invalid, 900f, 600f))
        assertEquals(5f, panel.x, 0f)
        assertEquals(6f, panel.y, 0f)
        assertEquals(220f, panel.w, 0f)
        assertEquals(260f, panel.h, 0f)
        assertFalse(panel.placed)
        assertNull(panel.restore("""{"x":0.2,"y":0.2,"w":0.2}""", 900f, 600f))
        assertEquals(5f, panel.x, 0f)
    }
}
