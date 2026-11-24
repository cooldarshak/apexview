package app.apexview

import android.content.ComponentName
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastChooserThemeTest {
    @Test fun mainActivityThemeCanOpenTheActualCastChooserFragment() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(R.style.Theme_Pitwall, info.theme)
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        val activity = controller.get()
        activity.setTheme(info.theme)
        controller.setup()
        try {
            val chooser = MediaRouteChooserDialogFragment()
            chooser.showNow(activity.supportFragmentManager, "cast-chooser")
            assertNotNull(chooser.dialog)
            chooser.dismissNow()
        } finally { controller.pause().stop().destroy() }
    }
}
