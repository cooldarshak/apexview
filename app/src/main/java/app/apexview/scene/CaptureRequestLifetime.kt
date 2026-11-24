package app.apexview.scene

/** GL-looper-only ownership of one outstanding PixelCopy target. */
internal class CaptureRequestLifetime {
    private var pending = false
    private var stopping = false
    fun begin(): Boolean {
        if (pending || stopping) return false
        pending = true
        return true
    }
    fun complete() {
        check(pending)
        pending = false
    }
    fun stop(): Boolean {
        stopping = true
        return !pending
    }
}
