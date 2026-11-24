package app.apexview.cast

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
            Text("On the TV, open TV casting → Receive from phone. Both devices need this Pitwall version and the same Wi-Fi or Ethernet network.", style = Type.footnote)
            Text(app.localCastDiscoveryStatus, style = Type.subhead)
            app.localCastDevices.forEach { device -> PrimaryButton(device.name, enabled = !app.castBusy) { app.connectLocalCast(device) } }
            SecondaryButton("Search again") { app.startLocalDiscovery() }
            SecondaryButton("Receive on this device") { app.castPickerOpen = false; app.openTvReceiver() }
            if (app.castVideoOnlyOffer) {
                Text("This Google Cast receiver cannot display the native timing tower or track map.",
                    style = Type.footnote)
                SecondaryButton("Cast video only (without map or tower)") { app.confirmGoogleVideoOnlyCast() }
            }
            if (app.castingActive || app.castBusy) SecondaryButton("Stop casting") { app.stopFullSceneCast() }
            if (!app.castBusy && app.tiles.isNotEmpty()) SecondaryButton("Retry casting") { app.retryFullSceneCast() }
            app.castStatus?.let { Text(it, style = Type.footnote) }
            Text("Screen mirroring", style = Type.headline)
            Text("On Samsung, connect through Quick Settings → Smart View. When Android exposes a separate TV display, Pitwall moves video there and leaves this phone as the controller. Otherwise the system mirrors the phone screen.", style = Type.footnote)
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

/** No media is sent to an unconfirmed TLS identity. */
@Composable
internal fun PitwallPairingDialog(code: String, onDecision: (Boolean) -> Unit, receiver: Boolean = false) {
    AlertDialog(onDismissRequest = { onDecision(false) },
        title = { Text(if (receiver) "Confirm the phone connection" else "Confirm the TV connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Compare every group with the code on your ${if (receiver) "phone" else "TV"}. Only confirm if they match exactly.")
                Text(code, style = Type.headline)
                Text("Also choose Match on your ${if (receiver) "phone" else "TV"}. If the codes differ, cancel on both devices.", style = Type.footnote)
            }
        },
        confirmButton = { TextButton(onClick = { onDecision(true) }) { Text("Match") } },
        dismissButton = { TextButton(onClick = { onDecision(false) }) { Text("Cancel") } })
}
