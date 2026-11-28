package app.apexview.scene

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Per-texture access only. Terminal invalidation never waits for a vendor
 * updateTexImage call which may be holding the image lock on another looper. */
internal class SceneLatchAccess {
    private val lock = ReentrantLock()
    private val terminal = AtomicBoolean()
    val closed: Boolean get() = terminal.get()
    fun beginClose(): Boolean = terminal.compareAndSet(false, true)
    fun <T> worker(block: () -> T): T = lock.withLock(block)
    fun <T> render(timeoutMs: Long, block: () -> T): T? {
        if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return null
        try { return block() } finally { lock.unlock() }
    }
}
