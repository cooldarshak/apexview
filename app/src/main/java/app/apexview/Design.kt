package app.apexview

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * Pitwall's racing-first Material 3 Expressive design tokens. The video canvas
 * stays neutral and visually stable; interactive chrome carries the stronger
 * racing accent, depth, accessible type, shapes, and remote focus ring.
 */
object Ink {
    val background = Color(0xFF07090D)
    /** Panels stay dark enough not to compete with 50 fps video windows. */
    val surface = Color(0xFF151922)
    val surfaceHigh = Color(0xFF282F3A)
    val surfaceLow = Color(0xFF10141B)
    val outline = Color(0xFF515D6F)
    val primary = Color(0xFFF7F8FC)
    val secondary = Color(0xFFBAC3D2)
    val tertiary = Color(0xFF8390A2)
    val accent = Color(0xFFE10600)
    /** Race red remains a semantic live/error color as well as a brand accent. */
    val live = Color(0xFFFF453A)
}

/**
 * Compact video-first typography; larger text and touch targets live in browsing and settings.
 */
object Type {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, color = Ink.primary)
    val title1 = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em, color = Ink.primary)
    val title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em, color = Ink.primary)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.005).em, color = Ink.primary)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, color = Ink.primary)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = Ink.secondary)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.005.em, color = Ink.secondary)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.01.em, color = Ink.secondary)
}

/** The one curve every transition uses: fast start, gentle settle. */
val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

object Motion {
    const val PRESS_MS = 90
    const val SCREEN_MS = 380
    const val FADE_MS = 220
    // Browsing should respond quickly even when the video player runs at 50fps.
    // Keep the longer SCREEN_MS for protected SurfaceView/video geometry moves.
    const val BROWSE_NAV_MS = 240
    const val BROWSE_FADE_MS = 140
    // Fast control feedback without changing protected video geometry motion.
    const val CHROME_MS = 140
}

/**
 * Scrolling to whatever the remote moves onto, as on a phone: just far enough to show it. On a
 * TV, Compose instead slides everything focused to a third of the way in, which pulled home's
 * carousel half a slide sideways, where it fought its own snap and shook between the two.
 */
@OptIn(ExperimentalFoundationApi::class)
internal val PhoneScrolling = object : BringIntoViewSpec {}

/** Accessibility: respect Android's system-wide Remove Animations setting. */
internal val LocalReduceMotion = compositionLocalOf { false }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val reducedMotion = !android.animation.ValueAnimator.areAnimatorsEnabled()
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides PhoneScrolling,
        LocalReduceMotion provides reducedMotion,
    ) { Theme(content) }
}

@Composable
private fun Theme(content: @Composable () -> Unit) {
    // Genuine stable Material 3 Expressive theme, including the platform's
    // expressive shape/motion defaults. Keep Pitwall's high-contrast racing palette
    // and app-owned reduced-motion settings; video rendering is unchanged.
    MaterialExpressiveTheme(
        colorScheme = darkColorScheme(
            background = Ink.background,
            surface = Ink.surface,
            surfaceContainer = Ink.surface,
            surfaceContainerLowest = Ink.background,
            surfaceContainerLow = Ink.surfaceLow,
            surfaceContainerHigh = Ink.surfaceHigh,
            outline = Ink.outline,
            outlineVariant = Ink.surfaceHigh,
            primary = Ink.accent,
            onPrimary = Color.White,
            secondary = Ink.secondary,
            onSecondary = Ink.background,
            onSurface = Ink.primary,
            onSurfaceVariant = Ink.secondary,
            surfaceVariant = Ink.surfaceHigh,
            secondaryContainer = Ink.surfaceHigh,
            onSecondaryContainer = Ink.primary,
            error = Ink.live,
        ),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp),
        ),
        typography = Typography(
            displayLarge = Type.largeTitle,
            headlineLarge = Type.title1,
            headlineMedium = Type.title2,
            titleLarge = Type.headline,
            bodyLarge = Type.body,
            bodyMedium = Type.subhead,
            labelLarge = Type.footnote,
            labelMedium = Type.caption,
        ),
        content = content,
    )
}

private const val FOCUS_SCALE = 1.04f

/**
 * Tappable with press feedback on touch-down: a slight shrink that eases back. No ripple,
 * which reads as Android-default rather than designed. Under a remote or keyboard, whatever has
 * focus grows a little inside a white ring, so it's clear what the select button will press.
 */
fun Modifier.pressable(enabled: Boolean = true, scaleTo: Float = 0.97f, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isTv = remember(context) { context.packageManager.hasSystemFeature("android.software.leanback") }
    val ringed = focused && (isTv || LocalInputModeManager.current.inputMode == InputMode.Keyboard)
    val reducedMotion = LocalReduceMotion.current
    val scale by animateFloatAsState(
        if (pressed && enabled) scaleTo else if (ringed) FOCUS_SCALE else 1f,
        tween(if (reducedMotion) 0 else Motion.PRESS_MS, easing = EaseOut),
        label = "press",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (ringed) {
                val out = 3.dp.toPx()
                drawRoundRect(
                    Color(0xFF168BFF),
                    Offset(-out, -out),
                    Size(size.width + 2 * out, size.height + 2 * out),
                    CornerRadius(12.dp.toPx()),
                    style = Stroke(2.5.dp.toPx()),
                )
            }
        }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/** A blue focus outline for native Compose controls such as sliders and map handles. */
fun Modifier.tvFocusRing(): Modifier = composed {
    val context = LocalContext.current
    val isTv = remember(context) { context.packageManager.hasSystemFeature("android.software.leanback") }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val ringed = focused && (isTv || keyboard)
    this
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (ringed) {
                val out = 3.dp.toPx()
                drawRoundRect(
                    Color(0xFF168BFF),
                    Offset(-out, -out),
                    Size(size.width + 2 * out, size.height + 2 * out),
                    CornerRadius(8.dp.toPx()),
                    style = Stroke(2.5.dp.toPx()),
                )
            }
        }
}

/**
 * One hundred steps across the slider's range. A held remote key naturally sends repeated
 * KeyDown events; accelerate after eight repeats without depending on Android's native event API.
 */
internal fun sliderDpadValue(
    value: Float,
    min: Float,
    max: Float,
    direction: Int,
    repeatCount: Int,
): Float {
    val span = (max - min).coerceAtLeast(0f)
    val multiplier = if (repeatCount >= 8) 5f else 1f
    return (value + direction * span / 100f * multiplier).coerceIn(min, max)
}

/** Compact racing-themed Material 3 slider retaining explicit TV arrow-key control. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    trackColor: Color = Color.White.copy(alpha = 0.25f),
    /** The remote is on it: the thumb grows, without moving anything around it. */
    emphasised: Boolean = false,
) {
    val reducedMotion = LocalReduceMotion.current
    val thumbScale by animateFloatAsState(if (emphasised) 1.5f else 1f,
        tween(if (reducedMotion) 0 else Motion.PRESS_MS, easing = EaseOut), label = "thumb")
    var dpadRepeats by remember { mutableIntStateOf(0) }
    // Material 3 1.5 moved custom thumb/track slots to the state-based overload.
    // Preserve two-way external state (remote, app settings, restore) as well as
    // direct drag updates. An active gesture must not be reset by recomposition.
    val sliderState = remember(valueRange) { SliderState(value = value, valueRange = valueRange) }
    SideEffect {
        if (!sliderState.isDragging && sliderState.value != value) sliderState.value = value
    }
    androidx.compose.material3.Slider(
        state = sliderState,
        onValueChange = { updated ->
            sliderState.value = updated
            onValueChange(updated)
        },
        modifier = modifier
            .tvFocusRing()
            .onFocusChanged { if (!it.isFocused) dpadRepeats = 0 }
            // Preview intercepts TCL D-pad events before Material3 consumes
            // its own arrow keys. Do not also seek the active media player.
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> when (event.type) {
                        KeyEventType.KeyDown -> {
                            dpadRepeats++
                            val direction = if (event.key == Key.DirectionLeft) -1 else 1
                            onValueChange(
                                sliderDpadValue(
                                    value,
                                    valueRange.start,
                                    valueRange.endInclusive,
                                    direction,
                                    dpadRepeats,
                                )
                            )
                            true
                        }
                        KeyEventType.KeyUp -> {
                            dpadRepeats = 0
                            onValueChangeFinished?.invoke()
                            true
                        }
                        else -> false
                    }
                    else -> false
                }
            }
            .focusable(),
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(22.dp)
                    .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
                    .shadow(3.dp, androidx.compose.foundation.shape.CircleShape)
                    .background(Color.White, androidx.compose.foundation.shape.CircleShape)
            )
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span == 0f) 0f else ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().height(6.dp).background(trackColor, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Ink.accent, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                )
            }
        },
    )
}
