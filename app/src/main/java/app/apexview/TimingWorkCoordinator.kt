package app.apexview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread timing ownership; each open of the same race has a new identity. */
internal class TimingWorkCoordinator {
    class Session internal constructor(val contentId: Long)
    class SpeedRequest internal constructor(val session: Session, val radioBase: String)
    @Volatile private var current: Session? = null
    @Volatile private var speedRequest: SpeedRequest? = null
    val contentId get() = current?.contentId
    val speedsFor get() = speedRequest?.radioBase
    var archiveSpeeds: Map<String, SpeedLog> = emptyMap()
        private set
    var timingJob: Job? = null
    var speedJob: Job? = null
    fun owns(owner: Session) = current === owner
    fun begin(contentId: Long): Session {
        close()
        return Session(contentId).also { current = it }
    }
    data class Target(val reference: SessionRef?, val token: String?, val knownLive: Boolean?)

    /** Own source discovery, retry and live-to-archive handoff for one viewer session. */
    fun load(
        scope: CoroutineScope,
        contentId: Long,
        target: () -> Target,
        probeLive: suspend (Session) -> Boolean?,
        report: (String, String) -> Unit,
        diagnostic: (String) -> Unit,
        live: suspend (Session, SessionRef, String) -> Unit,
        replay: suspend (Session, SessionRef, String, Int) -> Unit,
    ) {
        val owner = begin(contentId)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var attempt = 0
            while (owns(owner)) {
                val (reference, token, knownLive) = target()
                diagnostic("Session metadata: " +
                    (reference?.let { "meeting=${it.meetingKey}, session=${it.sessionKey}, year=${it.year}" } ?: "missing") +
                    "; timing token=" + if (token == null) "missing" else "present")
                val problem = when {
                    reference == null -> "F1 TV didn't say which session this is"
                    token == null -> "not signed in to F1's timing yet"
                    else -> null
                }
                if (problem != null) {
                    report("Map unavailable: $problem. Trying again…", "BLOCKED: $problem")
                } else {
                    val followsLive = knownLive ?: probeLive(owner)
                    if (!owns(owner)) return@launch
                    when (followsLive) {
                        null -> {
                            report("Waiting for the video stream to identify live timing or replay…", "Video timeline not ready")
                            delay(1_000)
                            continue
                        }
                        true -> {
                            report("Connecting to F1 live timing…", "Source selected: LIVE")
                            live(owner, requireNotNull(reference), requireNotNull(token))
                        }
                        false -> {
                            report("Loading F1 timing archive…", "Source selected: ARCHIVE")
                            try {
                                replay(owner, requireNotNull(reference), requireNotNull(token), attempt)
                                return@launch
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (!owns(owner)) return@launch
                                report("Archive failed: ${error.javaClass.simpleName}. Retrying…",
                                    "ARCHIVE ERROR: ${error.javaClass.simpleName}")
                            }
                        }
                    }
                }
                if (!owns(owner)) return@launch
                delay(retryDelayMs(attempt++))
            }
        }
        timingJob = job
        job.start()
    }

    fun loadSpeeds(
        scope: CoroutineScope,
        radioBase: String,
        fetch: suspend () -> Map<String, SpeedLog>,
        publish: (Map<String, SpeedLog>) -> Unit,
        reportFailure: (Exception) -> Unit,
    ) {
        val request = beginSpeed(radioBase) ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var lastFailure: Exception? = null
                for (attempt in 0..3) {
                    if (speedRequest !== request || !owns(request.session)) return@launch
                    try {
                        val loaded = fetch()
                        if (loaded.isNotEmpty()) {
                            if (completeSpeed(request, loaded)) publish(loaded)
                            return@launch
                        }
                        lastFailure = IllegalStateException("Car data archive is empty")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        lastFailure = error
                    }
                    if (attempt < 3) delay(retryDelayMs(attempt))
                }
                if (failSpeed(request)) reportFailure(requireNotNull(lastFailure))
            } catch (cancelled: CancellationException) {
                failSpeed(request)
                throw cancelled
            } catch (error: Exception) {
                if (failSpeed(request)) reportFailure(error)
            }
        }
        speedJob = job
        job.start()
    }

    fun beginSpeed(radioBase: String): SpeedRequest? {
        val owner = current ?: return null
        if (speedRequest?.radioBase == radioBase) return null
        val old = speedJob
        speedJob = null
        val request = SpeedRequest(owner, radioBase)
        speedRequest = request
        archiveSpeeds = emptyMap()
        old?.cancel()
        return request
    }
    fun completeSpeed(request: SpeedRequest, loaded: Map<String, SpeedLog>): Boolean {
        if (speedRequest !== request || !owns(request.session)) return false
        archiveSpeeds = loaded
        return true
    }
    fun failSpeed(request: SpeedRequest): Boolean {
        if (speedRequest !== request || !owns(request.session)) return false
        speedRequest = null
        speedJob = null
        archiveSpeeds = emptyMap()
        return true
    }
    fun close() {
        current = null
        speedRequest = null
        archiveSpeeds = emptyMap()
        val oldTiming = timingJob
        val oldSpeed = speedJob
        timingJob = null
        speedJob = null
        oldTiming?.cancel()
        oldSpeed?.cancel()
    }

    companion object {
        private val retryDelaysMs = longArrayOf(3_000L, 10_000L, 30_000L, 60_000L)
        fun retryDelayMs(attempt: Int) = retryDelaysMs.getOrElse(attempt) { retryDelaysMs.last() }
    }
}
