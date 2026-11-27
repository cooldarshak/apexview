package app.apexview.scene

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RenderNode
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

/** The same normalized video transform used by the two Viewer graphics layers. */
internal data class BakedCastVideoPlacement(val left: Float, val top: Float, val scale: Float) {
    companion object {
        fun viewer(sideScale: Float, feedScale: Float, upwardShiftFraction: Float) =
            BakedCastVideoPlacement(
                sideScale * (1f - feedScale) / 2f,
                (1f - sideScale) / 2f + sideScale * (1f - feedScale - upwardShiftFraction),
                sideScale * feedScale,
            )
    }
}

/** Draw the same native display list in the viewer and on the encoder's overlay Surface. */
internal class BakedCastOverlay(
    val placement: () -> BakedCastVideoPlacement,
    val backgroundArgb: Int,
) {
    private val content = RenderNode("PitwallCastViewerLayers")

    fun record(scope: ContentDrawScope, showOnSender: Boolean = true) {
        val context = scope.drawContext
        val display = context.canvas
        val width = scope.size.width.roundToInt()
        val height = scope.size.height.roundToInt()
        if (width <= 0 || height <= 0 || !display.nativeCanvas.isHardwareAccelerated) {
            clear()
            if (showOnSender) scope.drawContent()
            return
        }
        content.setPosition(0, 0, width, height)
        val recording = content.beginRecording(width, height)
        try {
            context.canvas = Canvas(recording)
            scope.drawContent()
        } finally {
            context.canvas = display
            content.endRecording()
        }
        if (showOnSender) display.nativeCanvas.drawRenderNode(content)
    }

    /** Main thread only. Clear the entire buffer, then post one transparent overlay. */
    fun submit(surface: Surface, width: Int, height: Int): Boolean {
        if (!content.hasDisplayList()) return false
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.scale(width.toFloat() / content.width, height.toFloat() / content.height)
            canvas.drawRenderNode(content)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        return true
    }

    fun clear() { content.discardDisplayList() }
}

@Composable
internal fun Modifier.recordBakedCastOverlay(overlay: BakedCastOverlay?, showOnSender: Boolean = true): Modifier {
    if (overlay == null) return this
    DisposableEffect(overlay) { onDispose { overlay.clear() } }
    return drawWithContent { overlay.record(this, showOnSender) }
}
