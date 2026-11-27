package app.apexview

import android.content.Context
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-looper process owner, retained only for an explicitly active Cast.
 * The service and private presentation have no Activity or Activity scope.
 * Controller callbacks are cleared on detach. Non-Cast playback still retires
 * when the controller is destroyed; task removal/service stop retires Cast. */
internal object PlaybackLifetime {
    private var current: Owner? = null
    fun attach(context: Context, userAgent: String, chromeMajor: String, token: Any,
        login: () -> Unit, secure: () -> Boolean): Owner {
        val owner = current ?: Owner(context.applicationContext, userAgent, chromeMajor).also { current = it }
        owner.attach(token, login, secure)
        return owner
    }
    fun detach(owner: Owner, token: Any) {
        if (current !== owner || !owner.detach(token)) return
        if (!owner.app.retainForCast()) retire(owner)
    }
    fun stopFromService(): Boolean {
        val owner = current ?: return false
        owner.app.stopFullSceneCast()
        if (!owner.attached) retire(owner)
        return true
    }
    fun serviceRetired() {
        val owner = current ?: return
        if (!owner.attached) retire(owner)
    }
    private fun retire(owner: Owner) {
        if (current !== owner) return
        current = null
        owner.close()
    }

    internal class Owner(context: Context, userAgent: String, chromeMajor: String) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var controller: Any? = null
        private var login: (() -> Unit)? = null
        private var started = false
        val attached get() = controller != null
        val app = AppState(context, scope, userAgent, chromeMajor) { login?.invoke() }
        private val castContext = runCatching { CastContext.getSharedInstance(context) }.getOrNull()
    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) =
            app.onGoogleCastSessionAvailable(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) =
            app.onGoogleCastStartFailed("Google Cast could not start (error $error). Check the receiver connection and try again.")
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = app.onGoogleCastSessionEnded(session)
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) =
            app.onGoogleCastSessionAvailable(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) =
            app.onGoogleCastStartFailed("Google Cast could not reconnect (error $error). Choose the device again.")
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

        fun attach(token: Any, login: () -> Unit, secure: () -> Boolean) {
            controller = token
            this.login = login
            app.attachControllerSecurity(secure)
            if (!started) {
                started = true
                app.updateGoogleCastAvailability(castContext != null)
                castContext?.sessionManager?.addSessionManagerListener(castSessionListener, CastSession::class.java)
                app.restore()
                app.updater.checkOnLaunch()
                scope.launch {
                    while (true) {
                        delay(500L)
                        if (!attached) {
                            if (!app.retainForCast()) { retire(this@Owner); break }
                            if (app.bakedCastSession == null) app.syncTick()
                            if (DiagnosticLogs.recording) app.logPlaybackSample()
                        }
                    }
                }
            }
        }
        fun detach(token: Any): Boolean {
            if (controller !== token) return false
            app.detachControllerSecurity()
            controller = null
            login = null
            return true
        }
        fun refreshCastSession() {
            castContext?.sessionManager?.currentCastSession?.let(app::onGoogleCastSessionAvailable)
        }
        override fun close() {
            controller = null
            login = null
            app.detachControllerSecurity()
            castContext?.sessionManager?.removeSessionManagerListener(castSessionListener, CastSession::class.java)
            try { app.stopAll() } finally {
                try { app.close() } finally { scope.cancel() }
            }
        }
    }
}
