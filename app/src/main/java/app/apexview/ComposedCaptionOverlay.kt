package app.apexview

import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.CueGroup
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView

/**
 * PlayerView already renders cues in standard single/multiple-decoder tiles.
 * Custom HEVC composition surfaces do not use PlayerView, so only those routes
 * install this independent text view. Never allocate another video decoder.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun ComposedCaptionOverlay(
    player: Player?, enabled: Boolean, appearance: CaptionAppearance, modifier: Modifier = Modifier,
) {
    var cues by remember(player) { mutableStateOf<List<Cue>>(emptyList()) }
    DisposableEffect(player, enabled) {
        if (player == null || !enabled) {
            cues = emptyList()
            onDispose { }
        } else {
            cues = player.currentCues.cues.toList()
            val listener = object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    cues = cueGroup.cues.toList()
                }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
    }
    if (player != null && enabled) AndroidView(
        factory = { context ->
            SubtitleView(context).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setBottomPaddingFraction(0.13f)
            }
        },
        update = {
            it.applyCaptionAppearance(appearance)
            it.setCues(cues)
        },
        modifier = modifier,
    )
}