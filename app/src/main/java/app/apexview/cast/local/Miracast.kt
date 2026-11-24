package app.apexview.cast.local

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

internal object Miracast {
    /** Public Android entry point. Samsung may expose mirroring only through its Smart View tile. */
    fun open(context: Context): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
        true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
}
