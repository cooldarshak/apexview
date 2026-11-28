package app.apexview

/** Main-looper target ownership. A release of a replaced view cannot detach
 * the new target. No second player or persistent reference to retired views. */
internal class DirectPresentationOwner<T : Any>(private val transfer: (T?, T?) -> Unit) {
    private var active: T? = null
    private var terminal = false
    fun owns(target: T): Boolean = !terminal && active === target
    fun attach(next: T) {
        if (terminal || active === next) return
        val old = active
        transfer(old, next)
        active = next
    }
    fun detach(target: T) {
        if (active !== target) return
        active = null
        transfer(target, null)
    }
    fun close() {
        terminal = true
        active?.let { detach(it) }
    }
}
