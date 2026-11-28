package app.apexview.scene

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGL15
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSync
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * One decoder texture, two EGL command streams. The decoder's SurfaceTexture
 * is latched only by its own surfaceless shared context. Presentation samples
 * the exact same external image, never a copied atlas. A sampling fence MUST
 * signal before the worker releases the old BufferQueue image. An acquire
 * fence orders the next draw behind updateTexImage. eglSwapBuffers is outside
 * this lock and outside the latch context.
 *
 * Protected creation requires a verified protected render AND latch context;
 * no protected image is ever attached to an unprotected context. Unsupported
 * drivers retain the original single-context path before decoder attachment.
 */
internal class SceneAsyncTextureLatch private constructor(
    private val display: EGLDisplay,
    private val context: EGLContext,
    val frames: SurfaceTexture,
    private val thread: HandlerThread,
    private val worker: Handler,
    private val render: Handler,
) : AutoCloseable {
    /** A fault of the optional independent path, not of the decoder or DRM. */
    class Fault(cause: Exception) : IllegalStateException("Independent texture latch failed", cause)

    private val access = SceneLatchAccess()
    private val closed get() = access.closed
    @Volatile private var faulted = false
    @Volatile private var image = false
    private fun <T : Any> renderAccess(block: () -> T): T =
        access.render(1_000L, block) ?: throw Fault(IllegalStateException("Decoder latch owner did not respond"))
    private var discardedAtNs = Long.MIN_VALUE
    private var acquired: EGLSync? = null
    private var sampled: EGLSync? = null
    private val matrix = FloatArray(16)
    private var latchedAt = 0L
    private val notificationPosted = java.util.concurrent.atomic.AtomicBoolean()
    private var ingress: SceneDecoderIngress? = null
    private var pending = 0L
    private var first = 0L
    private var last = 0L
    private var retryPosted = false
    private var retrySince = 0L
    private var notifications = 0L
    private var latches = 0L
    private var coalesced = 0L
    private var maxWaitNs = 0L
    private var lastTimestampNs = 0L
    private var regressions = 0L
    private var fenceWaits = 0L
    private var maxFenceWaitNs = 0L
    private var fenceWaitTotalNs = 0L

    data class Event(val notifications: Long, val firstNs: Long, val lastNs: Long,
                     val timestampNs: Long, val startedNs: Long, val latchedNs: Long)
    data class Stats(val notifications: Long, val latches: Long, val coalesced: Long,
                     val maxWaitNs: Long, val regressions: Long, val fenceWaits: Long,
                     val maxFenceWaitNs: Long, val fenceWaitTotalNs: Long)
    fun takeStats(): Stats? = access.render(0L) {
        Stats(notifications, latches, coalesced, maxWaitNs, regressions, fenceWaits, maxFenceWaitNs, fenceWaitTotalNs).also {
            notifications = 0L; latches = 0L; coalesced = 0L; maxWaitNs = 0L; regressions = 0L
            fenceWaits = 0L; maxFenceWaitNs = 0L; fenceWaitTotalNs = 0L
        }
    }
    @Volatile private var report: ((Event) -> Unit)? = null
    @Volatile private var signal: (() -> Unit)? = null
    @Volatile private var failure: ((Exception) -> Unit)? = null
    private val notifyRender = Runnable {
        notificationPosted.set(false)
        if (!closed && !faulted && image) signal?.invoke()
    }
    private val retry = Runnable { retryPosted = false; latch() }

    fun start(report: (Event) -> Unit, signal: () -> Unit, failure: (Exception) -> Unit) {
        check(ingress == null)
        this.report = report; this.signal = signal; this.failure = failure
        val owner = SceneDecoderIngress(worker) { count, from, to ->
            if (pending == 0L) first = from
            pending += count
            last = to
            if (!retryPosted) latch()
        }
        ingress = owner
        frames.setOnFrameAvailableListener({ owner.offer() }, owner.handler)
    }

    private fun newFence(): EGLSync {
        val sync = EGL15.eglCreateSync(display, EGL15.EGL_SYNC_FENCE,
            longArrayOf(EGL14.EGL_NONE.toLong()), 0)
        check(sync != null && sync != EGL15.EGL_NO_SYNC) { "EGL texture fence unavailable" }
        GLES20.glFlush()
        return sync
    }

    private fun latch() {
        if (pending == 0L) return
        try {
            val event = access.worker {
                if (closed || faulted) return@worker null
                if (last <= discardedAtNs) { pending = 0L; retrySince = 0L; return@worker null }
                sampled?.let { fence ->
                    when (EGL15.eglClientWaitSync(display, fence, 0, 0L)) {
                        EGL15.EGL_TIMEOUT_EXPIRED -> {
                            val now = System.nanoTime()
                            if (retrySince == 0L) { retrySince = now; fenceWaits++ }
                            check(now - retrySince < 1_000_000_000L) { "GPU sampling fence did not complete" }
                            retryPosted = worker.postDelayed(retry, 1L)
                            check(retryPosted)
                            return@worker null
                        }
                        EGL15.EGL_CONDITION_SATISFIED -> {
                            check(EGL15.eglDestroySync(display, fence))
                            sampled = null
                        }
                        else -> error("GPU sampling fence wait failed")
                    }
                }
                val started = System.nanoTime()
                if (retrySince != 0L) {
                    val waited = (started - retrySince).coerceAtLeast(0L)
                    fenceWaitTotalNs += waited
                    maxFenceWaitNs = maxOf(maxFenceWaitNs, waited)
                    retrySince = 0L
                }
                android.os.Trace.beginSection("PitwallAsyncTextureLatch")
                try { frames.updateTexImage() } finally { android.os.Trace.endSection() }
                frames.getTransformMatrix(matrix)
                val replacement = newFence()
                val old = acquired
                acquired = replacement
                old?.let { check(EGL15.eglDestroySync(display, it)) }
                latchedAt = System.nanoTime()
                image = true
                val timestamp = frames.timestamp
                notifications += pending
                latches++
                coalesced += pending - 1L
                maxWaitNs = maxOf(maxWaitNs, (started - first).coerceAtLeast(0L))
                if (timestamp > 0L) {
                    if (lastTimestampNs > 0L && timestamp < lastTimestampNs) regressions++
                    lastTimestampNs = timestamp
                }
                Event(pending, first, last, timestamp, started, latchedAt).also { pending = 0L }
            } ?: return
            if (!closed) report?.invoke(event)
            if (!closed && !faulted && notificationPosted.compareAndSet(false, true)) {
                if (!render.post(notifyRender)) notificationPosted.set(false)
            }
        } catch (error: Exception) {
            faulted = true
            render.post { if (!closed) failure?.invoke(Fault(error)) }
        }
    }

    /** Render context only; never include eglSwapBuffers in draw. */
    fun sample(transform: FloatArray, draw: () -> Unit): Long = renderAccess {
        if (closed || faulted) throw Fault(IllegalStateException("Independent decoder texture is unavailable"))
        if (!image) return@renderAccess 0L
        try {
            acquired?.let { check(EGL15.eglWaitSync(display, it, 0)) { "GPU acquire wait failed" } }
        } catch (error: Exception) {
            faulted = true
            throw Fault(error)
        }
        matrix.copyInto(transform)
        try { draw() } finally {
            try {
                val replacement = newFence()
                val old = sampled
                sampled = replacement
                old?.let { check(EGL15.eglDestroySync(display, it)) }
            } catch (error: Exception) {
                // Do not let the worker retire a sampled image without a fence.
                faulted = true
                GLES20.glFinish()
                throw Fault(error)
            }
        }
        latchedAt
    }

    /** Preserve fences when invalidating a source epoch. A newly latched
     * image is required before the next output-ready acknowledgement. */
    fun discard() {
        renderAccess { image = false; discardedAtNs = System.nanoTime() }
    }

    override fun close() {
        if (!access.beginClose()) return
        // Invalidate callbacks without acquiring the vendor-held image lock.
        report = null; signal = null; failure = null
        val outerSteps = SceneReleaseSteps { name, error ->
            android.util.Log.w("PitwallGraphics", "LATCH_RETIRE_FAILED step=$name", error)
        }
        outerSteps.run("listener") { frames.setOnFrameAvailableListener(null) }
        outerSteps.run("ingress") { ingress?.close() }
        ingress = null
        render.removeCallbacks(notifyRender)
        worker.removeCallbacksAndMessages(null)
        // No more renderer sampling can occur. Wait for the last submitted
        // sampling fence before retiring the decoder image on its owner.
        // Hold an independent EGL display lease while the worker retires. If
        // the driver stalls, parent teardown can finish without terminating
        // the display or deleting the shared texture under this worker.
        val lease = SceneEglDisplays.acquire()
        if (lease.display != display) { lease.close(); error("Unexpected EGL display during retirement") }
        val retired = java.util.concurrent.CompletableFuture<Unit>()
        val retirementLock = Any()
        val ledger = SceneGraphicsOwners.process
        var quarantinedId: Long? = null
        var completed = false
        val fenceDeadlineNs = System.nanoTime() + 15_000_000_000L
        val task = object : Runnable {
            override fun run() {
                try {
                    for (fence in listOfNotNull(sampled, acquired)) {
                        when (EGL15.eglClientWaitSync(display, fence, 0, 250_000_000L)) {
                            EGL15.EGL_TIMEOUT_EXPIRED -> {
                                // Never release the BufferQueue image on a timeout.
                                // Bound polling; preserve unresolved ownership on terminal failure.
                                check(System.nanoTime() < fenceDeadlineNs) { "GPU retirement deadline exceeded" }
                                check(worker.postDelayed(this, 25L))
                                return
                            }
                            EGL15.EGL_CONDITION_SATISFIED -> Unit
                            else -> error("GPU texture retirement fence failed")
                        }
                    }
                    val steps = SceneReleaseSteps { name, error ->
                        Log.w("PitwallGraphics", "LATCH_RETIRE_FAILED step=$name", error)
                    }
                    steps.run("sample-fence-destroy") { sampled?.let { check(EGL15.eglDestroySync(display, it)) } }
                    steps.run("acquire-fence-destroy") { acquired?.let { check(EGL15.eglDestroySync(display, it)) } }
                    sampled = null; acquired = null
                    steps.run("surface-texture") { frames.release() }
                    steps.run("egl-unbind") { check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) }
                    steps.run("egl-context") { check(EGL14.eglDestroyContext(display, context)) }
                    steps.run("egl-thread") { check(EGL14.eglReleaseThread()) }
                    steps.run("egl-display") { lease.close() }
                    synchronized(retirementLock) {
                        completed = true
                        quarantinedId?.let {
                            ledger.remove(it, steps.failedCount)
                            quarantined.remove(it)
                        }
                    }
                    thread.quitSafely()
                    check(steps.failedCount == 0) { "Unverified latch retirement: ${steps.failedCount} steps" }
                    retired.complete(Unit)
                } catch (error: Exception) {
                    // A failed fence cannot authorize image release. Keep this
                    // worker/context/lease alive until process shutdown rather
                    // than pretend a failed wait completed the GPU commands.
                    retired.completeExceptionally(error)
                }
            }
        }
        fun quarantine(): Unit = synchronized(retirementLock) {
            if (!completed && quarantinedId == null) {
                val id = ledger.newId()
                quarantinedId = id
                // Retain the actual image owner, not only its scalar ledger entry.
                quarantined[id] = this@SceneAsyncTextureLatch
                ledger.record(id, SceneGraphicsOwners.Owner("LATCH_RETIRING", 0, 0,
                    1, 0, 1, 0, 0, 0, 0))
                ledger.beginRetirement(id)
            }
        }
        try {
            check(worker.post(task)) { "Decoder latch retirement queue closed" }
            retired.get(5L, TimeUnit.SECONDS)
        } catch (error: Exception) {
            quarantine()
            throw Fault(error)
        }
        check(outerSteps.failedCount == 0) { "Unverified latch callback retirement: ${outerSteps.failedCount} steps" }
    }

    companion object {
        private const val PROTECTED = 0x32C0
        private val quarantined = java.util.concurrent.ConcurrentHashMap<Long, SceneAsyncTextureLatch>()

        /** Called on the existing render context before attaching the codec. */
        fun tryCreate(display: EGLDisplay, config: EGLConfig, renderContext: EGLContext,
                      texture: Int, protected: Boolean, render: Handler): SceneAsyncTextureLatch? {
            val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)?.split(' ').orEmpty()
            if ("EGL_KHR_surfaceless_context" !in extensions) return null
            val version = EGL14.eglQueryString(display, EGL14.EGL_VERSION).orEmpty()
            if (!version.startsWith("1.5")) return null
            if (protected && "EGL_EXT_protected_content" !in extensions) return null
            fun isProtected(ctx: EGLContext): Boolean {
                val out = IntArray(1)
                return EGL14.eglQueryContext(display, ctx, PROTECTED, out, 0) && out[0] == EGL14.EGL_TRUE
            }
            if (protected && !isProtected(renderContext)) return null
            // Verify fence entrypoints on the render context before adding a worker.
            val probe = runCatching {
                EGL15.eglCreateSync(display, EGL15.EGL_SYNC_FENCE, longArrayOf(EGL14.EGL_NONE.toLong()), 0)
            }.getOrNull()
            if (probe == null || probe == EGL15.EGL_NO_SYNC) return null
            val renderFenceWorks = try {
                GLES20.glFlush()
                EGL15.eglClientWaitSync(display, probe, 0, 100_000_000L) == EGL15.EGL_CONDITION_SATISFIED &&
                    EGL15.eglWaitSync(display, probe, 0)
            } finally { EGL15.eglDestroySync(display, probe) }
            if (!renderFenceWorks) return null
            val thread = HandlerThread("PitwallTextureLatch", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
            val worker = Handler(thread.looper)
            val init = FutureTask<SceneAsyncTextureLatch?> {
                var ctx = EGL14.EGL_NO_CONTEXT
                var success = false
                try {
                    val attributes = if (protected) intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                        PROTECTED, EGL14.EGL_TRUE, EGL14.EGL_NONE)
                    else intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                    ctx = EGL14.eglCreateContext(display, config, renderContext, attributes, 0)
                    if (ctx == EGL14.EGL_NO_CONTEXT || protected && !isProtected(ctx)) return@FutureTask null
                    if (!EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, ctx))
                        return@FutureTask null
                    val glExtensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)?.split(' ').orEmpty()
                    if ("GL_OES_surfaceless_context" !in glExtensions ||
                        protected && "GL_EXT_protected_textures" !in glExtensions ||
                        !GLES20.glIsTexture(texture)) return@FutureTask null
                    val sync = EGL15.eglCreateSync(display, EGL15.EGL_SYNC_FENCE,
                        longArrayOf(EGL14.EGL_NONE.toLong()), 0)
                    if (sync == null || sync == EGL15.EGL_NO_SYNC) return@FutureTask null
                    val fenceWorks = try {
                        GLES20.glFlush()
                        EGL15.eglClientWaitSync(display, sync, 0, 100_000_000L) == EGL15.EGL_CONDITION_SATISFIED &&
                            EGL15.eglWaitSync(display, sync, 0)
                    } finally { EGL15.eglDestroySync(display, sync) }
                    if (!fenceWorks) return@FutureTask null
                    val owner = SceneAsyncTextureLatch(display, ctx, SurfaceTexture(texture), thread, worker, render)
                    success = true
                    owner
                } finally {
                    if (!success) {
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
                        EGL14.eglReleaseThread()
                    }
                }
            }
            val owner = try {
                if (worker.post(init)) init.get() else null
            } catch (error: Exception) {
                Log.w("ApexviewSingleDecoder", "SCENE_ASYNC_LATCH_UNAVAILABLE protected=$protected", error)
                null
            }
            if (owner == null) { thread.quitSafely(); thread.join() }
            Log.i("ApexviewSingleDecoder", "SCENE_ASYNC_LATCH enabled=${owner != null} protected=$protected " +
                "fullFrameCopies=0 extraWindowSurfaces=0 physicalGain=NOT_MEASURED")
            return owner
        }
    }
}
