package app.apexview.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.apexview.*
import kotlin.math.min
import kotlin.math.roundToInt

/** Native UI overlays share the source window canvas. They create no media players or codecs. */
@Composable
internal fun CastTimingLayer(state: CastTimingReceiver, canvasWidth: Float, canvasHeight: Float, positionMs: () -> Long,
                             onDriver: (String, Boolean) -> Unit, onDrawn: (Int, Int) -> Unit) {
    val frame = state.frame ?: return
    val timing = state.geometry?.takeIf { it.id == frame.geometryId }?.timing
    var position by remember { mutableLongStateOf(frame.positionMs) }
    LaunchedEffect(positionMs) {
        var last = 0L
        while (true) withFrameMillis { time -> if (time - last >= MAP_RENDER_INTERVAL_MS) { position = positionMs(); last = time } }
    }
    val cars = frame.carsAt(position)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = constraints.maxWidth / canvasWidth
        val density = Density(frame.density * scale, frame.fontScale)
        fun panel(p: CastTimingProtocol.Panel) = Modifier
            .offset { IntOffset((p.x * scale).roundToInt(), (p.y * scale).roundToInt()) }
            .requiredSize((p.width / frame.density).dp, (p.height / frame.density).dp)
        CompositionLocalProvider(LocalDensity provides density) {
            Box(Modifier.fillMaxSize().drawWithContent { drawContent(); onDrawn(frame.table.rows.size, cars.size) }) {
                if (timing != null) frame.tower?.let { p ->
                    Box(panel(p).graphicsLayer(alpha = frame.towerOpacity).clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = .72f)).padding(top = (frame.towerHeaderPx / frame.density).dp)) {
                        val base = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * frame.towerFontScale)) {
                            if (frame.table.rows.isEmpty()) Text(frame.message ?: "Waiting for timing rows…", style = Type.footnote, modifier = Modifier.padding(12.dp))
                            else TimingTowerContents(timing, frame.table.rows, frame.header, frame.table.tyres, frame.table.speeds,
                                frame.onTop, frame.speedUnit, frame.tvLayout) { onDriver(it, false) }
                        }
                    }
                }
                if (timing != null && frame.mapMode == MapMode.EDGE) {
                    EdgeDrawingContent(timing, canvasWidth * scale, canvasHeight * scale,
                        with(density) { 30.dp.toPx() }, 0f, frame.mapOpacity, frame.utcMs + position - frame.positionMs,
                        cars, frame.stop, frame.flags, frame.leader) { targets -> DriverTargets(targets) { onDriver(it, false) } }
                }
                if (timing != null && frame.mapMode.inWindow) frame.map?.let { p ->
                    Box(panel(p)) {
                        if (frame.mapMode == MapMode.PIT) {
                            val left = (-p.x).coerceIn(0f, p.width); val top = (-p.y).coerceIn(0f, p.height)
                            val right = (canvasWidth - p.x).coerceIn(left, p.width)
                            val bottom = (canvasHeight - p.y).coerceIn(top, p.height)
                            val centre = Offset((left + right) / 2 * scale, (top + bottom) / 2 * scale)
                            val radius = (min(right - left, bottom - top) / 2 - LABEL_ROOM.value * frame.density).coerceAtLeast(1f) * scale
                            PitCircleDrawing(timing, frame.circle, p.width * scale, p.height * scale, centre, radius,
                                frame.mapOpacity) { targets -> DriverTargets(targets) { onDriver(it, true) } }
                        } else TrackDrawingContent(timing, p.width * scale, p.height * scale, frame.mapRotation,
                            frame.mapOpacity, frame.dotSize, frame.labelSize, frame.utcMs + position - frame.positionMs,
                            cars, frame.stop, frame.flags, frame.leader) { targets -> DriverTargets(targets) { onDriver(it, false) } }
                    }
                }
                if (frame.message != null && (timing == null || frame.table.rows.isEmpty() && cars.isEmpty())) {
                    Text(frame.message, style = Type.footnote, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DriverTargets(targets: List<Pair<String, Offset>>, selected: (String) -> Unit) {
    val density = LocalDensity.current
    targets.forEach { (number, p) ->
        Box(Modifier.offset { IntOffset(p.x.roundToInt() - with(density) { 12.dp.toPx() }.roundToInt(), p.y.roundToInt() - with(density) { 12.dp.toPx() }.roundToInt()) }
            .size(24.dp).focusable().pressable { selected(number) })
    }
}
