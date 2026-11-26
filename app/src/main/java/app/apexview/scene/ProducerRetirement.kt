package app.apexview.scene

/** One producer owns a native window until it has detached on its own looper.
 * Retirement never waits for that looper or calls release under the lock. */
internal class ProducerRetirement {
    private val lock = Any()
    private var attached = false
    private var retired = false
    private var release: (() -> Unit)? = null
    val closed: Boolean get() = synchronized(lock) { retired }

    fun attach(): Boolean = synchronized(lock) {
        if (retired) false else {
            check(!attached)
            attached = true
            true
        }
    }

    fun detach() {
        val action = synchronized(lock) {
            if (!attached) return
            attached = false
            if (retired) release.also { release = null } else null
        }
        action?.invoke()
    }

    fun retire(action: () -> Unit) {
        val now = synchronized(lock) {
            if (retired) return
            retired = true
            if (attached) { release = action; null } else action
        }
        now?.invoke()
    }
}
