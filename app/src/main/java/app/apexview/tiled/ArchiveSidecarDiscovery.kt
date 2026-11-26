package app.apexview.tiled

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Session-owned, bounded optional programme validation. It never blocks the
 * prepared primary video/audio source, never publishes after retirement and
 * never makes an unverified rendition eligible for a stable merge. */
internal class ArchiveSidecarDiscovery<T>(
    parent: CoroutineScope = CoroutineScope(Dispatchers.IO),
    concurrency: Int = 2,
    private val failure: (String, Exception) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val lock = Any()
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val lanes = Semaphore(concurrency.also { require(it in 1..4) })
    private val pending = linkedMapOf<String, CompletableDeferred<Unit>>()
    private var closed = false
    private var started = false

    fun start(labels: List<String>, load: suspend (String) -> T, publish: (String, T) -> Unit) {
        val jobs = synchronized(lock) {
            check(!closed && !started)
            require(labels.size <= 8 && labels.distinct().size == labels.size)
            started = true
            labels.map { label ->
                val done = CompletableDeferred<Unit>()
                pending[label] = done
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        lanes.withPermit {
                            val result = load(label)
                            synchronized(lock) {
                                if (!closed) publish(label, result)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        runCatching { failure(label, error) }
                    }
                }
                job.invokeOnCompletion { cause ->
                    synchronized(lock) {
                        if (pending[label] === done) pending.remove(label)
                    }
                    if (cause is CancellationException) done.cancel(cause)
                    else done.complete(Unit)
                }
                job
            }
        }
        jobs.forEach { it.start() }
    }

    fun isPending(label: String): Boolean = synchronized(lock) { label in pending }

    suspend fun await(label: String) {
        val completion = synchronized(lock) {
            if (closed) throw CancellationException("Archive sidecars retired")
            pending[label]
        }
        completion?.await()
    }

    override fun close() {
        val cancelled = synchronized(lock) {
            if (closed) return
            closed = true
            pending.values.toList().also { pending.clear() }
        }
        cancelled.forEach { it.cancel() }
        owner.cancel()
    }
}
