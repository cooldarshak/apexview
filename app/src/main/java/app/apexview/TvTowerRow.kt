package app.apexview

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Trace
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.abs

/** A single draw surface and one remote focus target per driver on Android TV. */
@Composable
internal fun TvTowerRow(
    driver: DriverInfo?, number: String, state: CardState, tyre: Tyre?, kmh: Int?, gear: Int?,
    speedUnit: String, highlight: Boolean, rowHeight: Dp,
    drawProbe: OverlayPerformanceProbe? = null, onClick: () -> Unit,
) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val widthCache = remember { TowerTextWidthCache() }
    val retained = remember(number) { RetainedTowerRowLayers() }
    DisposableEffect(retained) { onDispose { retained.discard() } }
    val currentDensity = LocalDensity.current
    // A tower sample can be republished while this driver's fields stay identical. Keep the
    // formatted text, synthesized strip cells and accessibility description across those draws.
    val content = remember(number, driver?.tla, state, kmh, gear, speedUnit) {
        Trace.beginSection("PitwallTowerTextPrepare")
        try { TowerRowText.prepare(driver?.tla ?: number, state, kmh, gear, speedUnit) }
        finally { Trace.endSection() }
    }
    val (name, lastLap, bestLap, position, interval, leader, gearText, speed, sectors) = content
    // Keep dirty-field keys in a testable pure contract: sector-only changes
    // cannot invalidate the speed/position display list, and vice versa.
    val visualKeys = remember(driver?.color, state, content, tyre?.compound, tyre?.laps,
        highlight, currentDensity.density, currentDensity.fontScale) {
        towerRegionVisualKeys(driver, state, content, tyre, highlight,
            currentDensity.density, currentDensity.fontScale)
    }
    // RetainedTowerRenderNode now compares immutable visual keys structurally.
    // Passing the actual field data eliminates three remembered identity tokens
    // per row, and preserves existing display lists across equal snapshots.
    val fieldKeys = remember(visualKeys.base, visualKeys.sectors, visualKeys.tyre) {
        arrayOf<Any>(visualKeys.base, visualKeys.sectors, visualKeys.tyre)
    }
    val accessibleSummary = remember(name, state.position, state.inPit, state.retired, state.stopped,
        state.lastLap?.time, state.bestLap?.time, state.ahead, state.toLeader,
        tyre?.compound, tyre?.laps) {
        TowerAccessibilitySummary.prepare(name, state, tyre)
    }
    // The decoder TextureView refreshes around 50 fps, but the tower state changes only
    // a few times per second. Rasterize each visible row to its own bounded hardware
    // layer so an unchanged row is composited instead of replaying dozens of drawing ops.
    // Restore Offscreen after the Build 583 ModulateAlpha experiment coincided with
    // 86.8% tower-only app deadline misses (43.5% in the Build 582 baseline) and
    // substantially longer RenderThread command flushing. Recheck on device.
    // Retain the independent snapshot-deduplication fix from Build 583.
    Canvas(Modifier.fillMaxWidth().height(rowHeight).tvFocusRing().clickable(onClick = onClick)
        .semantics { contentDescription = accessibleSummary }.clipToBounds()
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val startedNs = System.nanoTime()
        val destination = drawContext.canvas.nativeCanvas
        val pixelWidth = size.width.toInt().coerceAtLeast(1)
        val pixelHeight = size.height.toInt().coerceAtLeast(1)
        val unit = minOf(density, size.width / 361f)
        if (unit <= 0f || size.height <= 0f) return@Canvas
        val rowVisualHeight = size.height / unit
        val mid = rowVisualHeight / 2f
        val upper = rowVisualHeight * .29f
        val lower = rowVisualHeight * .75f
        val radius = minOf(7f, rowVisualHeight * .21f)
        val secondary = Ink.secondary
        val hardware = destination.isHardwareAccelerated
        val tracing = Trace.isEnabled()
        var changedRegions = 0

        // One node spans x0..200, one x202..329 and one tyre column.
        // The base region paints the full-width highlight FIRST, preserving the
        // existing alpha, text placement and row state. Each following region
        // only contains its own column commands.
        for (region in 0..2) {
            val retainedRegion = retained.regions[region]
            val recorded = if (hardware)
                retainedRegion.beginIfChanged(fieldKeys[region], pixelWidth, pixelHeight,
                    region, unit)
            else destination
            if (recorded != null) {
                changedRegions++
                val canvas = recorded
                val saved = canvas.save()
                if (tracing) Trace.beginSection("PitwallTowerRowRecord")
                try {
                    canvas.scale(unit, unit)

                    fun label(
                        text: String, x: Float, y: Float, fontSize: Float, width: Float,
                        color: Color = Color.White, bold: Boolean = false,
                        centered: Boolean = false
                    ) {
                        paint.color = color.toArgb()
                        paint.style = Paint.Style.FILL
                        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        paint.textSize = minOf(fontSize * fontScale, rowVisualHeight * .61f)
                        paint.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
                        paint.textScaleX = 1f
                        val measured = widthCache.width(text, paint.textSize, bold) {
                            paint.measureText(text)
                        }
                        if (measured > width) paint.textScaleX = width / measured
                        canvas.drawText(
                            text, x, y - (paint.ascent() + paint.descent()) / 2f, paint
                        )
                    }
                    fun ring(x: Float, y: Float, r: Float, color: Color) {
                        paint.color = color.toArgb()
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 1.3f
                        canvas.drawCircle(x, y, r, paint)
                        paint.style = Paint.Style.FILL
                    }

                    when (region) {
                        0 -> {
                            if (highlight || state.retired) {
                                paint.color = Color.White.copy(
                                    alpha = if (highlight) .12f else .025f
                                ).toArgb()
                                paint.style = Paint.Style.FILL
                                canvas.drawRoundRect(
                                    0f, 0f, size.width / unit,
                                    rowVisualHeight, 3f, 3f, paint
                                )
                            }
                            val delta = state.positionChange ?: 0
                            label(position, 9f, if (delta == 0) mid else upper,
                                12f, 18f, bold = true, centered = true)
                            if (delta != 0) {
                                label((if (delta > 0) "▲" else "▼") + abs(delta),
                                    9f, lower, 6f, 18f,
                                    if (delta > 0) Color(0xFF30D158) else Ink.live,
                                    centered = true)
                            }
                            val badge = minOf(11.5f, mid - 1f).coerceAtLeast(0f)
                            paint.color = driver?.color ?: Color.DarkGray.toArgb()
                            paint.style = Paint.Style.FILL
                            canvas.drawRoundRect(
                                22f, mid - badge, 64f, mid + badge, 3f, 3f, paint
                            )
                            label(name, 43f, mid, 10f, 40f, bold = true, centered = true)
                            ring(79f, upper, radius, Color(0xFF28A8F8))
                            label(gearText, 79f, upper, 8f, radius * 2,
                                bold = true, centered = true)
                            label(speed, 79f, lower, 8f, 29f, secondary, centered = true)
                            label("LAST", 96f, upper, 6f, 18f, secondary)
                            label("BEST", 96f, lower, 6f, 18f, secondary)
                            label(lastLap, 115f, upper, 9f, 40f,
                                if (state.inPit) Ink.live else lapColor(state.lastLap?.pace),
                                bold = true)
                            label(bestLap, 115f, lower, 9f, 40f, lapColor(state.bestLap?.pace))
                            label("INT", 158f, upper, 6f, 13f, secondary)
                            label("LDR", 158f, lower, 6f, 13f, secondary)
                            label(interval, 172f, upper, 8f, 29f)
                            label(leader, 172f, lower, 8f, 29f, secondary)
                        }
                        1 -> {
                            sectors.forEachIndexed { index, sector ->
                                val left = 202f + index * 43f
                                val values = sector?.segments.orEmpty()
                                val count = if (values.isEmpty()) 5 else values.size
                                val step = 42f / count
                                val gap = minOf(step / 2f, 1f)
                                val halfBar = minOf(3f, upper * .7f)
                                repeat(count) { i ->
                                    paint.color = segmentColor(
                                        values.getOrNull(i) ?: 0
                                    ).toArgb()
                                    paint.style = Paint.Style.FILL
                                    canvas.drawRect(
                                        left + i * step, upper - halfBar,
                                        left + (i + 1) * step - gap,
                                        upper + halfBar, paint
                                    )
                                }
                                label(
                                    sector?.time?.ifBlank { "–" } ?: "–",
                                    left + 21f, lower, 8f, 41f,
                                    lapColor(sector?.pace), centered = true
                                )
                            }
                        }
                        2 -> {
                            if (tyre == null) {
                                label("–", 346f, mid, 9f, 29f, secondary,
                                    centered = true)
                            } else {
                                val colour = TYRE_COLORS[tyre.compound] ?: secondary
                                ring(346f, upper, radius, colour)
                                label(tyre.compound.take(1), 346f, upper,
                                    8f, radius * 2, colour, bold = true,
                                    centered = true)
                                label(tyre.laps.toString(), 346f, lower,
                                    7f, 29f, secondary, centered = true)
                            }
                        }
                    }
                } finally {
                    canvas.restoreToCount(saved)
                    if (hardware) retainedRegion.finish(
                        fieldKeys[region], pixelWidth, pixelHeight
                    )
                    if (tracing) Trace.endSection()
                }
            }
            if (hardware) destination.drawRenderNode(retainedRegion.node)
        }
        // On hardware, changedRegions=0 means all three RenderNodes were
        // replayed with NO Kotlin text measuring or per-sector draw commands.
        drawProbe?.record(System.nanoTime() - startedNs, 3, changedRegions)
    }
}

/** Immutable text work shared by the drawing and accessibility passes. */
internal data class TowerRowText(
    val name: String, val lastLap: String, val bestLap: String, val position: String,
    val interval: String, val leader: String, val gearText: String, val speed: String,
    val sectors: List<SectorCell?>,
) {
    companion object {
        fun prepare(name: String, state: CardState, kmh: Int?, gear: Int?, speedUnit: String): TowerRowText {
            val lastLap = when {
                state.retired -> "OUT"
                state.stopped -> "STOP"
                state.inPit -> "PIT"
                else -> state.lastLap?.time?.ifBlank { "–" } ?: "–"
            }
            val bestLap = state.bestLap?.time?.ifBlank { "–" } ?: "–"
            val position = state.position?.toString() ?: "–"
            val interval = state.ahead ?: "–"
            val leader = state.toLeader ?: "–"
            val gearText = gear?.let { if (it == 0) "N" else it.toString() } ?: "–"
            val speed = kmh?.let { speedIn(it, speedUnit).toString() } ?: "–"
            val sectors = (0..2).map(state::towerSector)
            // The live TalkBack summary is prepared separately from stable row fields.
            return TowerRowText(name, lastLap, bestLap, position, interval, leader, gearText, speed, sectors)
        }
    }
}
