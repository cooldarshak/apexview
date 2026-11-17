package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DecoderModeSettingsTest {
    @Test fun singleDecoderChoiceSurvivesSettingsRecreationAndCanBeChangedBack() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("f1mv", android.content.Context.MODE_PRIVATE)
        val old = prefs.getString("decoderMode", null)
        try {
            prefs.edit().remove("decoderMode").commit()
            assertEquals(DecoderMode.MULTIPLE, Settings(context).decoderMode)
            Settings(context).updateDecoderMode(DecoderMode.SINGLE)
            assertEquals(DecoderMode.SINGLE, Settings(context).decoderMode)
            Settings(context).updateDecoderMode(DecoderMode.MULTIPLE)
            assertEquals(DecoderMode.MULTIPLE, Settings(context).decoderMode)
            prefs.edit().putString("decoderMode", "unknown").commit()
            assertEquals(DecoderMode.MULTIPLE, Settings(context).decoderMode)
        } finally {
            if (old == null) prefs.edit().remove("decoderMode").commit()
            else prefs.edit().putString("decoderMode", old).commit()
        }
    }
}
