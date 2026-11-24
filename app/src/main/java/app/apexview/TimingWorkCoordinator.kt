package app.apexview

import kotlinx.coroutines.Job

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
    fun failSpeed(request: SpeedRequest) {
        if (speedRequest !== request || !owns(request.session)) return
        speedRequest = null
        speedJob = null
        archiveSpeeds = emptyMap()
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
}
