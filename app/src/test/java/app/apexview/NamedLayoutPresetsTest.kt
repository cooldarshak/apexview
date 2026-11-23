package app.apexview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * No fabricated F1 stream or video performance assertions. These checks
 * exercise actual Android preferences and the versioned user layout format.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NamedLayoutPresetsTest {
    private fun preset(id: String, name: String, feeds: Int, width: Float, height: Float): NamedLayoutPreset {
        val boxes = List(feeds) { i ->
            Slot(i * width / feeds, 0f, width / feeds)
        }
        return NamedLayoutPreset(id, name, feeds, width > height,
            SavedLayout.of(boxes, width, height),
            JSONObject().put("mapMode", "TRACK")
                .put("mapPanel", JSONObject().put("x", 0.7).put("y", 0.3)
                    .put("w", 0.2).put("h", 0.25).put("rotation", 18)
                    .put("opacity", 0.65))
                .put("towerOpen", true)
                .put("towerReserve", false)
                .put("towerFontScale", 0.85)
                .put("onboardBadgeScale", 1.1)
                .put("onboardBadgeAnchor", "TOP_RIGHT")
                .put("onboardBadgeX", -12.0)
                .put("onboardBadgeY", 18.0)
                .put("onboardBadgeOpacity", 0.55)
                .put("towerPanel", JSONObject().put("x", 0.6).put("y", 0.0)
                    .put("w", 0.4).put("h", 0.7))
                .toString(),
            createdMs = 1740000000000
        )
    }

    @Test fun multipleNamesAndPanelGeometrySurvivePreferenceReload() {
        val originals = listOf(
            preset("pilot-001", "Qualifying four onboard", 4, 1920f, 1080f),
            preset("pilot-002", "Portrait tower and map", 3, 1080f, 2316f)
        )
        val encoded = NamedLayoutPresets.encode(originals)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("f1mv", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("namedLayoutPresetsV1", encoded).commit())
        val restored = NamedLayoutPresets.parse(
            ctx.getSharedPreferences("f1mv", Context.MODE_PRIVATE)
                .getString("namedLayoutPresetsV1", null)
        )
        assertEquals(2, restored.size)
        assertEquals(listOf("Qualifying four onboard", "Portrait tower and map"),
            restored.map { it.name })
        assertEquals(listOf(4, 3), restored.map { it.feedCount })
        assertEquals(listOf(true, false), restored.map { it.landscape })
        assertEquals(originals[0].layout.boxes[1].toList(),
            restored[0].layout.boxes[1].toList())
        val overlays = JSONObject(restored[0].overlaySnapshot)
        assertEquals("TRACK", overlays.getString("mapMode"))
        assertEquals(18, overlays.getJSONObject("mapPanel").getInt("rotation"))
        assertEquals(0.4, overlays.getJSONObject("towerPanel").getDouble("w"), 0.0001)
        assertEquals(0.85, overlays.getDouble("towerFontScale"), 0.0001)
        assertEquals("TOP_RIGHT", overlays.getString("onboardBadgeAnchor"))
        assertEquals(-12.0, overlays.getDouble("onboardBadgeX"), 0.0001)
        assertEquals(18.0, overlays.getDouble("onboardBadgeY"), 0.0001)
        assertEquals(0.55, overlays.getDouble("onboardBadgeOpacity"), 0.0001)
        assertFalse("Layout backup must contain no credentials", encoded.contains("ascendon"))
    }

    @Test fun extendedEightAndTwentyFourFeedLayoutsRestoreWithBothOverlays() {
        val eight = preset("pilot-008", "Eight feed", 8, 3840f, 2160f)
        val twentyFour = preset("pilot-024", "Twenty-four clear", 24, 3840f, 2160f)
        val restored = NamedLayoutPresets.parse(NamedLayoutPresets.encode(listOf(eight, twentyFour)))
        assertEquals(listOf(8, 24), restored.map { it.feedCount })
        assertEquals(24, restored.last().layout.boxes.size)
        assertEquals("TRACK", JSONObject(restored.first().overlaySnapshot).getString("mapMode"))
        assertTrue(JSONObject(restored.last().overlaySnapshot).getBoolean("towerOpen"))
        assertTrue(NamedLayoutPresets.parse("x".repeat(1_000_001)).isEmpty())
    }

    @Test fun rejectsUnsupportedFormatsAndMaliciousOrCorruptStoredEntries() {
        assertTrue(NamedLayoutPresets.parse(null).isEmpty())
        assertTrue(NamedLayoutPresets.parse("invalid json").isEmpty())
        assertTrue(NamedLayoutPresets.parse("""{"version":99,"presets":[]}""").isEmpty())
        assertFalse(NamedLayoutPresets.validName("\n"))
        assertFalse(NamedLayoutPresets.validName("A".repeat(49)))
        val valid = preset("pilot-123", "Good", 4, 1920f, 1080f)
        val invalid = valid.toJson().put("feeds", 25)
        val text = JSONObject().put("version", 1).put("presets",
            org.json.JSONArray().put(invalid).put(valid.toJson())).toString()
        assertEquals(listOf("Good"), NamedLayoutPresets.parse(text).map { it.name })
    }
}
