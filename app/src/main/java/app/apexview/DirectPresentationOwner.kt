package app.apexview

/** Main-looper target ownership. A release of a replaced view cannot detach
 * the new target. Failed transfers restore the old target or fail closed,
 * retaining uncertain references until terminal cleanup. */
internal class DirectPresentationOwner<T : Any>(private val transfer: (T?, T?) -> Unit) {
    private var active: T? = null
    private var uncertain: T? = null
    private var terminal = false
    val current: T? get() = active.takeUnless { terminal }
    fun owns(target: T): Boolean = current === target
    fun attach(next: T) {
        if (terminal || active === next) return
        val old = active
        try {
            transfer(old, next)
            active = next
        } catch (failure: Exception) {
            // switchTargetView attaches the replacement before detaching the
            // old target. Reverse the operation even if it failed midway.
            try { transfer(next, old) }
            catch (rollback: Exception) {
                if (rollback !== failure) failure.addSuppressed(rollback)
                terminal = true
                uncertain = next
            }
            throw failure
        }
    }
    fun detach(target: T) {
        if (active !== target) return
        // Keep the reference when the platform fails; close can retry it.
        transfer(target, null)
        active = null
    }
    fun close() {
        terminal = true
        var failure: Exception? = null
        uncertain?.let {
            try { transfer(it, null); uncertain = null }
            catch (error: Exception) { failure = error }
        }
        active?.let {
            try { detach(it) }
            catch (error: Exception) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
