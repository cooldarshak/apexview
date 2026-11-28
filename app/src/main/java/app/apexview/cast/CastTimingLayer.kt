package app.apexview.cast

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.apexview.*
import kotlin.math.min
import kotlin.math.roundToInt

/** Native UI overlays share the source window canvas and the receiver's playback clock. */
@Composable
internal fun CastTimingLayer(state: CastTimingReceiver, canvasWidth: Float, canvasHeight: Float, positionMs: () -> Long,
                             onDriver: (String, Boolean) -> Unit, onDrawn: (Int, Int) -> Unit,
                             nativeRequested: Boolean = false) {
    // Counts are read in draw, not composition. Pitwall Cast's packet/UI contract
    // is unchanged; this records receiver-specific render costs every five seconds.
    val drawProbe = remember(state) {
        app.apexview.OverlayPerformanceProbe("pitwall-cast-receiver-overlay-draw")
    }
    val counts by remember(state) {
        derivedStateOf(structuralEqualityPolicy()) {
            val frame = state.frame
            val geometry = state.geometry
            val tower = state.towerFrame
            val rows = if (tower?.tower != null && state.towerGeometry != null)
                (state.currentTable ?: tower.table).rows.size else 0
            val cars = when {
                frame == null || geometry == null || frame.geometryId != geometry.id -> 0
                frame.mapMode == MapMode.OFF -> 0
                frame.mapMode.inWindow && frame.map == null -> 0
                frame.mapMode == MapMode.PIT -> frame.circle?.ring?.size ?: 0
                geometry.timing.outline.size < 4 -> 0
                else -> frame.cars.size
            }
            rows to cars
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = constraints.maxWidth / canvasWidth
        Box(Modifier.fillMaxSize().drawWithContent {
            val startedNs = System.nanoTime()
            drawContent()
            val (rows, cars) = counts
            drawProbe.record(System.nanoTime() - startedNs, rows + cars, rows + cars)
            onDrawn(rows, cars)
        }) {
            ReceiverTower(state, scale, onDriver)
            ReceiverTrack(state, canvasWidth, canvasHeight, scale, positionMs, onDriver, nativeRequested)
        }
    }
}

private data class TowerFrame(
    val panel: CastTimingProtocol.Panel, val geometryId: String,
    val table: CastTimingProtocol.Table, val header: TowerSessionHeaderData,
    val density: Float, val fontScale: Float, val towerFontScale: Float, val opacity: Float,
    val headerPx: Float, val onTop: String?, val speedUnit: String, val tvLayout: Boolean, val emptyMessage: String?,
)

/** Car motion packets update the map independently of table publication. */
@Composable
private fun ReceiverTower(state: CastTimingReceiver, scale: Float, onDriver: (String, Boolean) -> Unit) {
    val tower by remember(state) {
        derivedStateOf(structuralEqualityPolicy()) {
            val f = state.towerFrame ?: return@derivedStateOf null
            val p = f.tower ?: return@derivedStateOf null
            val updatedTable = state.currentTable ?: f.table
            TowerFrame(p, f.geometryId, updatedTable, f.header, f.density, f.fontScale, f.towerFontScale, f.towerOpacity,
                f.towerHeaderPx, f.onTop, f.speedUnit, f.tvLayout, f.message.takeIf { updatedTable.rows.isEmpty() })
        }
    }
    val f = tower ?: return
    val timing = state.towerGeometry?.takeIf { it.id == f.geometryId }?.timing ?: return
    val density = Density(f.density * scale, f.fontScale * f.towerFontScale)
    CompositionLocalProvider(LocalDensity provides density) {
        Box(Modifier.offset { IntOffset((f.panel.x * scale).roundToInt(), (f.panel.y * scale).roundToInt()) }
            .requiredSize((f.panel.width / f.density).dp, (f.panel.height / f.density).dp)
            .graphicsLayer {
                alpha = f.opacity
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .72f))
            .padding(top = (f.headerPx / f.density).dp)) {
            if (f.table.rows.isEmpty()) {
                Text(f.emptyMessage ?: "Waiting for timing rows…", style = Type.footnote, modifier = Modifier.padding(12.dp))
            } else {
                TimingTowerContents(timing, f.table.rows, f.header, f.table.tyres, f.table.speeds,
                    f.onTop, f.speedUnit, f.tvLayout) { onDriver(it, false) }
            }
        }
    }
}

@Composable
private fun BoxScope.ReceiverTrack(state: CastTimingReceiver, canvasWidth: Float, canvasHeight: Float,
                                   scale: Float, positionMs: () -> Long, onDriver: (String, Boolean) -> Unit,
                                   nativeRequested: Boolean) {
    val frame = state.frame ?: return
    val timing = state.geometry?.takeIf { it.id == frame.geometryId }?.timing
    if (timing == null) {
        frame.message?.let { Text(it, style = Type.footnote, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) }
        return
    }
    if (frame.mapMode != MapMode.OFF) {
        val density = Density(frame.density * scale, frame.fontScale)
        CompositionLocalProvider(LocalDensity provides density) {
            ReceiverMap(timing, frame, canvasWidth, canvasHeight, scale, density, positionMs, onDriver,
                frame.nativeOverlayRequested ?: nativeRequested)
        }
    }
}

@Composable
private fun ReceiverMap(timing: SessionTiming, frame: CastTimingProtocol.Frame, canvasWidth: Float,
                        canvasHeight: Float, scale: Float, density: Density, positionMs: () -> Long,
                        onDriver: (String, Boolean) -> Unit, nativeRequested: Boolean) {
    // The last validated packet supplies static hit-target locations. The 60Hz
    // draw-only clock below animates actual car markers using receiver video time.
    // Previously rememberTvMapTime(positionMs) recomposed the entire Map subtree
    // at 30Hz *in addition* to this draw clock, causing avoidable TV UI jank.
    val position = frame.positionMs
    val cars = frame.carsAt(position)
    // Keep map state changes on Cast packets and position-only motion in drawing.
    val drawClock = rememberOverlayDrawClock(timing, positionMs, "pitwall-cast-map-motion-clock")
    val diagnostics = remember { ReceiverMotionDiagnostics() }
    val sampler = remember(frame, nativeRequested) { ReceiverMotionSampler(frame, nativeRequested, diagnostics) }
    val motion = remember(sampler, drawClock) {
        OverlayMotionSource(drawClock, sampler::sample)
    }
    fun panel(p: CastTimingProtocol.Panel) = Modifier
        .offset { IntOffset((p.x * scale).roundToInt(), (p.y * scale).roundToInt()) }
        .requiredSize((p.width / frame.density).dp, (p.height / frame.density).dp)
    if (frame.mapMode == MapMode.EDGE) {
        EdgeDrawingContent(timing, canvasWidth * scale, canvasHeight * scale,
            with(density) { 30.dp.toPx() }, 0f, frame.mapOpacity, frame.utcMs + position - frame.positionMs,
            cars, frame.stop, frame.flags, frame.leader, frame.dotSize, frame.labelSize,
            motion = motion, nativeRequested = nativeRequested) {
            targets -> DriverTargets(targets) { onDriver(it, false) }
        }
    }
    if (frame.mapMode.inWindow) frame.map?.let { p ->
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
                cars, frame.stop, frame.flags, frame.leader,
                motion = motion, nativeRequested = nativeRequested) { targets -> DriverTargets(targets) { onDriver(it, false) } }
            if (cars.isEmpty() && frame.message != null) {
                Text(frame.message, style = Type.footnote, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun DriverTargets(targets: List<Pair<String, Offset>>, selected: (String) -> Unit) {
    DriverFocusTargets(targets, with(LocalDensity.current) { 12.dp.toPx() }, selected, selected)
}
