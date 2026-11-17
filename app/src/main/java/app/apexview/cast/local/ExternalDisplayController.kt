package app.apexview.cast.local

import android.app.Presentation
import android.content.Context
import android.media.MediaRouter
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.apexview.*
import app.apexview.cast.CastTimingLayer
import app.apexview.cast.CastTimingPublisher
import app.apexview.cast.CastTimingReceiver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/** Public Android presentation route only. OEM Miracast negotiation remains with the OS. */
internal class ExternalDisplayController(private val activity: FragmentActivity, private val app: AppState) : AutoCloseable {
    private val router = activity.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    private var presentation: TvPresentation? = null
    private var requestedDisplay = -1
    private var closed = false
    private var checkJob: Job
    private val callback = object : MediaRouter.SimpleCallback() {
        override fun onRouteSelected(router: MediaRouter, type: Int, route: MediaRouter.RouteInfo) = update()
        override fun onRouteUnselected(router: MediaRouter, type: Int, route: MediaRouter.RouteInfo) = update()
        override fun onRoutePresentationDisplayChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = update()
    }
    init {
        app.stopExternalDisplay = ::stop
        router.addCallback(MediaRouter.ROUTE_TYPE_LIVE_VIDEO, callback)
        checkJob = activity.lifecycleScope.launch { while (true) { update(); delay(500) } }
    }
    fun update() {
        if (closed) return
        val display = if (app.externalDisplayRequested && app.tiles.isNotEmpty())
            router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)?.presentationDisplay else null
        if (display == null || !display.isValid || display.displayId == activity.display?.displayId || display.displayId == Display.DEFAULT_DISPLAY) {
            if (presentation != null) stop()
            return
        }
        if (app.singleDecoderScenePlayer == null) app.tiles.forEach { it.feed.setExternalQualityFloor(true) }
        if (presentation?.display?.displayId == display.displayId) return
        if (requestedDisplay != display.displayId) {
            dismiss(); requestedDisplay = display.displayId
            Log.i("PitwallMiracast", "EXTERNAL_DISPLAY id=${display.displayId} name=${display.name} size=${display.mode.physicalWidth}x${display.mode.physicalHeight} flags=${display.flags}")
        }
        if (min(display.mode.physicalWidth, display.mode.physicalHeight) < 720) {
            app.reportExternalDisplayStatus("The wireless display exposes less than 720p. Choose an HD display mode in Smart View or use the Pitwall TV receiver.")
            return
        }
        val protectedVideo = app.tiles.any { it.feed.sourceStream.licenseUrl != null && !it.feed.usesSoftwareDrm }
        if (protectedVideo && !supportsProtectedDisplay(display.flags)) {
            app.reportExternalDisplayStatus("This wireless display does not advertise protected video support. Use the signed-in Pitwall TV receiver.")
            return
        }
        if (!app.externalDisplayQualityReady()) {
            app.reportExternalDisplayStatus("Preparing at least 720p source feeds for the wireless display…")
            return
        }
        // Remove phone video views before attaching those same players to the Presentation.
        app.setExternalDisplay(true, display.name)
        try {
            val next = TvPresentation(activity, display, app, protectedVideo)
            presentation = next
            next.setOnDismissListener { if (presentation === next) { presentation = null; app.setExternalDisplay(false) } }
            next.show()
            app.reportExternalDisplayStatus("Video moved to ${display.name}. This phone controls playback. Miracast encoding quality is negotiated by the devices.")
        } catch (error: WindowManager.InvalidDisplayException) {
            presentation = null; app.setExternalDisplay(false)
            app.reportExternalDisplayStatus("Android could not open a separate wireless display. Use normal screen mirroring or the Pitwall TV receiver.")
            Log.w("PitwallMiracast", "PRESENTATION_REJECTED ${error.javaClass.simpleName}")
        }
    }
    private fun dismiss() { val previous = presentation; presentation = null; previous?.dismiss(); app.setExternalDisplay(false) }
    fun stop() { dismiss(); requestedDisplay = -1; app.clearExternalDisplayRequest() }
    override fun close() {
        closed = true; checkJob.cancel(); router.removeCallback(callback); stop(); app.stopExternalDisplay = {}
    }
    companion object {
        internal fun supportsProtectedDisplay(flags: Int) = flags and Display.FLAG_SECURE != 0 && flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0
    }
}

private class TvPresentation(private val activity: FragmentActivity, display: Display, private val app: AppState,
                             private val protectedVideo: Boolean) : Presentation(activity, display) {
    private val owner = PresentationOwner()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (protectedVideo) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { AppTheme { ExternalCanvas(activity, app) } }
        })
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = app.onRemoteKey(event) || super.onKeyDown(keyCode, event)
    override fun dismiss() { super.dismiss(); owner.registry.currentState = Lifecycle.State.DESTROYED }
}

/** The external window stays active when the phone opens system controls or turns its display off. */
private class PresentationOwner : LifecycleOwner, SavedStateRegistryOwner {
    val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    init { saved.performAttach(); saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
}

@Composable
private fun ExternalCanvas(activity: FragmentActivity, app: AppState) {
    val state = remember { CastTimingReceiver() }
    val publisher = remember { CastTimingPublisher(activity, app) }
    val metrics = activity.resources.displayMetrics
    val sourceDensity = remember { Density(metrics.density, activity.resources.configuration.fontScale) }
    LaunchedEffect(publisher) {
        while (true) {
            publisher.messages("presentation").forEach { state.accept(it, "presentation") }
            delay(100)
        }
    }
    val (width, height) = app.canvasSize
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.background), contentAlignment = Alignment.Center) {
        if (width > 0 && height > 0) {
            val ratio = width / height
            val fit = if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio)
                else Modifier.fillMaxWidth().aspectRatio(ratio)
            Box(fit) {
                CompositionLocalProvider(LocalDensity provides sourceDensity) {
                    Layout(content = { PlaybackVideoCanvas(app, width, height) }, modifier = Modifier.fillMaxSize()) { children, constraints ->
                        val child = children.single().measure(Constraints.fixed(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)))
                        val scale = min(constraints.maxWidth / width, constraints.maxHeight / height)
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            child.placeWithLayer(0, 0) {
                                scaleX = scale; scaleY = scale
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            }
                        }
                    }
                }
                CastTimingLayer(state, width, height, { app.castFramePositionMs }, { number, pit ->
                    if (pit) app.pitDriver = if (app.pitDriver == number) null else number else app.showOnboard(number)
                }, { _, _ -> })
            }
        }
    }
}
