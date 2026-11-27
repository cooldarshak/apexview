package app.apexview.cast.local

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.apexview.AppState
import app.apexview.AppTheme
import app.apexview.BakedPresentationCanvas
import app.apexview.cast.BakedCastLiveSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread owner of a non-mirroring, app-private clear scene. The display
 * sink is drained, never read/copied; Cast still uses the existing direct EGL
 * encoder and source-time AAC. No secure content or display capture permission. */
internal class BakedCastPresentationOwner(
    private val context: Context, private val app: AppState,
    private val session: BakedCastLiveSession, private val width: Int, private val height: Int,
    private val densityDpi: Int, private val scope: CoroutineScope,
) : AutoCloseable {
    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var window: ProducerPresentation? = null
    private var tick: Job? = null
    private var closed = false

    fun start() {
        check(!closed && window == null)
        require(width > 0 && height > 0 && densityDpi > 0)
        try {
            val drain = HandlerThread("PitwallPrivateCastSink").apply { start() }
            thread = drain
            val sink = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = sink
            sink.setOnImageAvailableListener({ available ->
                runCatching { available.acquireLatestImage()?.close() }
            }, Handler(drain.looper))
            val manager = context.getSystemService(DisplayManager::class.java)
            val virtual = checkNotNull(manager.createVirtualDisplay("Pitwall private clear scene",
                width, height, densityDpi, sink.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION))
            display = virtual
            val presentation = ProducerPresentation(context, virtual.display, app, width, height)
            window = presentation
            presentation.setOnDismissListener {
                if (!closed && window === presentation)
                    session.captureFailed("Private Cast display dismissed")
            }
            presentation.show()
            tick = scope.launch {
                while (!closed && app.bakedCastSession === session) {
                    app.syncTick()
                    delay(500L)
                }
            }
            android.util.Log.i("PitwallCast", "BAKED_PRIVATE_DISPLAY_STARTED size=${width}x$height display=${virtual.display.displayId} mirror=false secure=false")
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        tick?.cancel(); tick = null
        val oldWindow = window.also { window = null }
        runCatching { oldWindow?.dismiss() }
        runCatching { display?.release() }; display = null
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }; reader = null
        thread?.quitSafely(); thread = null
        android.util.Log.i("PitwallCast", "BAKED_PRIVATE_DISPLAY_STOPPED")
    }

    private class ProducerPresentation(context: Context, display: Display, private val app: AppState,
        private val width: Int, private val height: Int) : Presentation(context, display) {
        private val owner = Owner()
        private var compose: ComposeView? = null
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val output = requireNotNull(window)
            output.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            output.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            output.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            val view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent { AppTheme { BakedPresentationCanvas(app, output, width, height) } }
            }
            compose = view
            setContentView(view)
        }
        override fun dismiss() {
            try { compose?.disposeComposition(); compose = null; super.dismiss() }
            finally { owner.registry.currentState = Lifecycle.State.DESTROYED }
        }
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        init { saved.performAttach(); saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    }
}
