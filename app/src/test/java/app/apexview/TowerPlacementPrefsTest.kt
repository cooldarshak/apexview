package app.apexview

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TowerPlacementPrefsTest {
    @Test fun restoresSavedLandscapeAndPortraitSeparatelyAndReadsTheLegacyPosition() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("tower-test", Context.MODE_PRIVATE)
        prefs.edit().putString("tower", "0.5,0.125,0.5,0.5").commit()
        val tower = FloatingSpot(prefs, "tower", perOrientation = true)
        assertEquals(listOf(0.5f, 0.125f, 0.5f, 0.5f), tower.saved(800f, 400f))
        tower.place(Area(600f, 100f, 200f, 300f))
        tower.store(800f, 400f)
        tower.place(Area(100f, 400f, 200f, 400f))
        tower.store(400f, 800f)
        val reopened = FloatingSpot(prefs, "tower", perOrientation = true)
        assertEquals(listOf(0.75f, 0.25f, 0.25f, 0.75f), reopened.saved(800f, 400f))
        assertEquals(listOf(0.25f, 0.5f, 0.5f, 0.5f), reopened.saved(400f, 800f))
    }
}
