package app.apexview.cast

/**
 * Main-owner control session identity. Preparation, connection and media load
 * are independent epochs: replacing media does not invalidate the TCP owner.
 * Late packets cannot rewind the accepted remote clock epoch or control a
 * different load. No Compose, Android, socket, or video resources held here.
 */
internal class CastSessionCoordinator {
    var preparation = 0L
        private set
    var connection = 0L
        private set
    var playbackId = ""
        private set
    var clockEpoch = 0L
        private set

    fun beginPreparation(): Long = ++preparation
    fun beginConnection(): Long = ++connection
    fun beginLoad(id: String) {
        require(id.isNotBlank() && id.length <= 80)
        playbackId = id
        clockEpoch = 0L
    }
    fun endLoad() { playbackId = ""; clockEpoch = 0L }
    fun accepts(id: String): Boolean = playbackId.isNotEmpty() && id == playbackId
    fun acceptClock(id: String?, incoming: Long): Boolean {
        if (playbackId.isEmpty() || (id != null && !accepts(id)) || incoming < clockEpoch) return false
        clockEpoch = incoming
        return true
    }
}
