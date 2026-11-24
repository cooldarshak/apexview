package app.apexview.scene

import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

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

/** Main-thread reference to the actual display list drawn above the viewer video. */
internal class BakedCastOverlay(
    val placement: () -> BakedCastVideoPlacement,
    val backgroundArgb: Int,
) {
    var layer: GraphicsLayer? = null
        private set

    fun bind(layer: GraphicsLayer) { this.layer = layer }
    fun unbind(layer: GraphicsLayer) { if (this.layer === layer) this.layer = null }
}

@Composable
internal fun Modifier.recordBakedCastOverlay(overlay: BakedCastOverlay?): Modifier {
    if (overlay == null) return this
    val layer = rememberGraphicsLayer()
    DisposableEffect(overlay, layer) {
        overlay.bind(layer)
        onDispose { overlay.unbind(layer) }
    }
    return drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
}

/** Replays the same Compose layer into a transparent GPU buffer. Main thread only. */
internal class BakedCastOverlayTarget(
    surface: Surface,
    private val width: Int,
    private val height: Int,
) : AutoCloseable {
    private val root = RenderNode("PitwallCastOverlay").apply { setPosition(0, 0, width, height) }
    private val draw = CanvasDrawScope()
    private val renderer = HardwareRenderer().apply {
        try {
            setOpaque(false)
            setSurface(surface)
            setContentRoot(root)
        } catch (failure: Exception) {
            destroy()
            throw failure
        }
    }

    fun submit(layer: GraphicsLayer): Boolean {
        val size = layer.size
        if (size.width <= 0 || size.height <= 0) return false
        val canvas = root.beginRecording()
        try {
            canvas.scale(width.toFloat() / size.width, height.toFloat() / size.height)
            draw.draw(Density(1f), LayoutDirection.Ltr, Canvas(canvas), Size(size.width.toFloat(), size.height.toFloat())) {
                drawLayer(layer)
            }
        } finally {
            root.endRecording()
        }
        val result = renderer.createRenderRequest().syncAndDraw()
        check(result and (HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
            HardwareRenderer.SYNC_CONTEXT_IS_STOPPED) == 0) { "Overlay render target unavailable" }
        return result and HardwareRenderer.SYNC_FRAME_DROPPED == 0
    }

    override fun close() {
        renderer.destroy()
        root.discardDisplayList()
    }
}
