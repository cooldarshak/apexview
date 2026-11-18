package app.apexview

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
    speedUnit: String, highlight: Boolean, rowHeight: Dp, onClick: () -> Unit,
) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val name = driver?.tla ?: number
    val lastLap = when {
        state.retired -> "OUT"
        state.inPit -> "PIT"
        else -> state.lastLap?.time?.ifBlank { "–" } ?: "–"
    }
    val bestLap = state.bestLap?.time?.ifBlank { "–" } ?: "–"
    val position = state.position?.toString() ?: "–"
    val interval = state.ahead ?: "–"
    val leader = state.toLeader ?: "–"
    val gearText = gear?.let { if (it == 0) "N" else it.toString() } ?: "–"
    val speed = kmh?.let { speedIn(it, speedUnit).toString() } ?: "–"
    val sectors = (0..2).map { state.sectors.getOrNull(it) ?: state.lapSectors.getOrNull(it) }
    val description = buildString {
        append("Position $position, $name, gear $gearText, $speed $speedUnit. Last $lastLap, best $bestLap. Interval $interval, leader $leader.")
        sectors.forEachIndexed { index, sector -> sector?.time?.takeIf { it.isNotBlank() }?.let { append(" S${index + 1} $it.") } }
        tyre?.let { append(" ${it.compound}, ${it.laps} laps.") }
    }
    Canvas(Modifier.fillMaxWidth().height(rowHeight).tvFocusRing().clickable(onClick = onClick)
        .semantics { contentDescription = description }.clipToBounds()) {
        val canvas = drawContext.canvas.nativeCanvas
        // Match the column widths used by TowerHeader. Drawing in dp keeps phone-supplied
        // panel scaling and the user's font-scale setting effective on the Cast receiver.
        val unit = minOf(density, size.width / 361f)
        if (unit <= 0 || size.height <= 0) return@Canvas
        val height = size.height / unit
        val mid = height / 2
        val upper = height * .29f
        val lower = height * .75f
        val secondary = Ink.secondary
        fun label(text: String, x: Float, y: Float, fontSize: Float, width: Float,
                  color: Color = Color.White, bold: Boolean = false, centered: Boolean = false) {
            paint.color = color.toArgb()
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textSize = minOf(fontSize * fontScale, height * .61f)
            paint.textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
            paint.textScaleX = 1f
            val measured = paint.measureText(text)
            if (measured > width) paint.textScaleX = width / measured
            canvas.drawText(text, x, y - (paint.ascent() + paint.descent()) / 2f, paint)
        }
        fun ring(x: Float, y: Float, radius: Float, color: Color) {
            paint.color = color.toArgb()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.3f
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.FILL
        }
        val save = canvas.save()
        try {
            canvas.scale(unit, unit)
            if (highlight || state.retired) {
                paint.color = Color.White.copy(alpha = if (highlight) .12f else .025f).toArgb()
                canvas.drawRoundRect(0f, 0f, size.width / unit, height, 3f, 3f, paint)
            }
            val delta = state.positionChange ?: 0
            label(position, 9f, if (delta == 0) mid else upper, 12f, 18f, bold = true, centered = true)
            if (delta != 0) label((if (delta > 0) "▲" else "▼") + abs(delta), 9f, lower, 6f, 18f,
                if (delta > 0) Color(0xFF30D158) else Ink.live, centered = true)
            val badge = minOf(11.5f, mid - 1f).coerceAtLeast(0f)
            paint.color = driver?.color ?: Color.DarkGray.toArgb()
            canvas.drawRoundRect(22f, mid - badge, 64f, mid + badge, 3f, 3f, paint)
            label(name, 43f, mid, 10f, 40f, bold = true, centered = true)
            val radius = minOf(7f, height * .21f)
            ring(79f, upper, radius, Color(0xFF28A8F8))
            label(gearText, 79f, upper, 8f, radius * 2, bold = true, centered = true)
            label(speed, 79f, lower, 8f, 29f, secondary, centered = true)
            label("LAST", 96f, upper, 6f, 18f, secondary)
            label("BEST", 96f, lower, 6f, 18f, secondary)
            label(lastLap, 115f, upper, 9f, 40f, if (state.inPit) Ink.live else lapColor(state.lastLap?.pace), bold = true)
            label(bestLap, 115f, lower, 9f, 40f, lapColor(state.bestLap?.pace))
            label("INT", 158f, upper, 6f, 13f, secondary)
            label("LDR", 158f, lower, 6f, 13f, secondary)
            label(interval, 172f, upper, 8f, 29f)
            label(leader, 172f, lower, 8f, 29f, secondary)
            sectors.forEachIndexed { index, sector ->
                val left = 202f + index * 43f
                val values = sector?.segments.orEmpty()
                val count = if (values.isEmpty()) 5 else values.size
                val step = 42f / count
                val gap = minOf(step / 2f, 1f)
                val halfBar = minOf(3f, upper * .7f)
                repeat(count) { i ->
                    paint.color = segmentColor(values.getOrNull(i) ?: 0).toArgb()
                    canvas.drawRoundRect(left + i * step, upper - halfBar, left + (i + 1) * step - gap,
                        upper + halfBar, .7f, .7f, paint)
                }
                label(sector?.time?.ifBlank { "–" } ?: "–", left + 21f, lower, 8f, 41f,
                    lapColor(sector?.pace), centered = true)
            }
            if (tyre == null) label("–", 346f, mid, 9f, 29f, secondary, centered = true)
            else {
                val color = TYRE_COLORS[tyre.compound] ?: secondary
                ring(346f, upper, radius, color)
                label(tyre.compound.take(1), 346f, upper, 8f, radius * 2, color, bold = true, centered = true)
                label(tyre.laps.toString(), 346f, lower, 7f, 29f, secondary, centered = true)
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }
}
