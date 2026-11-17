package app.apexview.cast

import android.view.ContextThemeWrapper
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import app.apexview.AppState
import app.apexview.Ink
import app.apexview.pressable
import com.google.android.gms.cast.framework.CastButtonFactory

/** The Cast route picker stays in the player chrome instead of being buried in Layout settings. */
@Composable
internal fun GoogleCastPlayerButton(app: AppState) {
    Box(Modifier.size(44.dp).focusable().pressable(onClick = app::openCastPicker), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Cast, contentDescription = "Cast video feeds", tint = Ink.primary)
    }
}

/** Standard Google route chooser remains available inside the transport picker. */
@Composable
internal fun GoogleRoutePickerButton(app: AppState) {
    if (!app.googleCastAvailable) return
    AndroidView(
        factory = { viewContext ->
            val themed = ContextThemeWrapper(viewContext, androidx.appcompat.R.style.Theme_AppCompat)
            object : MediaRouteButton(themed) {
                override fun performClick(): Boolean {
                    app.chooseGoogleCast()
                    return super.performClick()
                }
            }.apply {
                contentDescription = "Choose a Google Cast device"
                isFocusable = true
                CastButtonFactory.setUpMediaRouteButton(themed, this)
            }
        },
        modifier = Modifier.size(48.dp).focusable(),
    )
}
