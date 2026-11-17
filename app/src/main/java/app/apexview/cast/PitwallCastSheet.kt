package app.apexview.cast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.apexview.AppState
import app.apexview.Ink
import app.apexview.PrimaryButton
import app.apexview.SecondaryButton
import app.apexview.Type

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PitwallCastSheet(app: AppState) {
    DisposableEffect(app) {
        app.startLocalDiscovery()
        onDispose { app.stopLocalDiscovery() }
    }
    ModalBottomSheet(onDismissRequest = { app.castPickerOpen = false }, containerColor = Ink.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Cast to TV", style = Type.title2)
            Text("Pitwall TV · same video layout", style = Type.headline)
            Text("On the TV, open Settings → TV casting → Receive from phone. Use the same Wi-Fi or Ethernet network.", style = Type.footnote)
            Text(app.localCastDiscoveryStatus, style = Type.subhead)
            app.localCastDevices.forEach { device -> PrimaryButton(device.name, enabled = !app.castBusy) { app.connectLocalCast(device) } }
            SecondaryButton("Search again") { app.startLocalDiscovery() }
            SecondaryButton("Receive on this device") { app.castPickerOpen = false; app.openTvReceiver() }
            if (app.castingActive || app.castBusy) SecondaryButton("Stop casting") { app.stopFullSceneCast() }
            if (!app.castBusy && app.tiles.isNotEmpty()) SecondaryButton("Retry casting") { app.retryFullSceneCast() }
            app.castStatus?.let { Text(it, style = Type.footnote) }
            Text("Screen mirroring", style = Type.headline)
            Text("Miracast mirrors the phone screen. On Samsung, use Quick Settings → Smart View. Video stays on the phone; quality and protected playback depend on the devices.", style = Type.footnote)
            SecondaryButton("Open wireless display controls") { app.openMiracast() }
            if (app.googleCastAvailable) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Google Cast", style = Type.headline)
                    Text("The standard receiver shows the encoded video grid.", style = Type.footnote)
                }
                GoogleRoutePickerButton(app)
            }
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}
