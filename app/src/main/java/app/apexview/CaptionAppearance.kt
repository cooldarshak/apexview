package app.apexview

import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlin.math.roundToInt

data class CaptionAppearance(
    val textSizePercent: Int = 100,
    val backgroundOpacityPercent: Int? = null,
) {
    init {
        require(textSizePercent in TEXT_SIZE_RANGE)
        require(backgroundOpacityPercent == null || backgroundOpacityPercent in 0..100)
    }

    companion object {
        val TEXT_SIZE_RANGE = 50..200
    }
}

@OptIn(UnstableApi::class)
internal fun SubtitleView.applyCaptionAppearance(appearance: CaptionAppearance) {
    val captioning = context.getSystemService(CaptioningManager::class.java)
    val opacity = appearance.backgroundOpacityPercent
    if (opacity == null) {
        setUserDefaultStyle()
    } else {
        val style = captioning?.takeIf { it.isEnabled }?.let {
            CaptionStyleCompat.createFromCaptionStyle(it.userStyle)
        } ?: CaptionStyleCompat.DEFAULT
        val alpha = (opacity * 255 + 50) / 100
        setStyle(CaptionStyleCompat(style.foregroundColor,
            (style.backgroundColor and 0x00ffffff) or (alpha shl 24),
            style.windowColor, style.edgeType, style.edgeColor, style.typeface))
    }
    setApplyEmbeddedStyles(opacity == null)
    setApplyEmbeddedFontSizes(appearance.textSizePercent == 100)
    val userScale = captioning?.fontScale ?: 1f
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * userScale * appearance.textSizePercent / 100f)
}

@Composable
internal fun CaptionAppearanceControls(
    appearance: CaptionAppearance,
    onChange: (CaptionAppearance) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text("Text size · ${appearance.textSizePercent}%", style = Type.subhead)
        AppleSlider(
            value = appearance.textSizePercent.toFloat(),
            onValueChange = { onChange(appearance.copy(textSizePercent = it.roundToInt())) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Caption text size" },
            valueRange = CaptionAppearance.TEXT_SIZE_RANGE.first.toFloat()..CaptionAppearance.TEXT_SIZE_RANGE.last.toFloat(),
            onValueChangeFinished = onCommit,
        )
        val backgroundLabel = appearance.backgroundOpacityPercent?.let { "$it%" } ?: "System default"
        Text("Text background opacity · $backgroundLabel", style = Type.subhead)
        AppleSlider(
            value = (appearance.backgroundOpacityPercent ?: 100).toFloat(),
            onValueChange = { onChange(appearance.copy(backgroundOpacityPercent = it.roundToInt())) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Caption text background opacity" },
            valueRange = 0f..100f,
            onValueChangeFinished = onCommit,
        )
        if (appearance != CaptionAppearance()) {
            SecondaryButton("Reset caption appearance") {
                onChange(CaptionAppearance())
                onCommit()
            }
        }
    }
}
