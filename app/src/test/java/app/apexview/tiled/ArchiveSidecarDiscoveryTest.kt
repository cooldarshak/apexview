package app.apexview.tiled

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ArchiveSidecarDiscoveryTest {
    @Test fun boundedDiscoveryDoesNotWaitForUnselectedProgramme() = runBlocking {
        withTimeout(5_000) {
            val entered = AtomicInteger()
            val peak = AtomicInteger()
            val gates = (1..5).associate { "$it" to CompletableDeferred<Unit>() }
            val published = ConcurrentHashMap<String, String>()
            ArchiveSidecarDiscovery<String>(this, concurrency = 2).use { discovery ->
                discovery.start(gates.keys.toList(), { label ->
                    val active = entered.incrementAndGet()
                    peak.updateAndGet { maxOf(it, active) }
                    try { gates.getValue(label).await(); label } finally { entered.decrementAndGet() }
                }) { label, result -> published[label] = result }
                // The primary was already prepared: no wait on any alternate.
                discovery.await("primary")
                assertTrue(discovery.isPending("5"))
                gates.values.forEach { it.complete(Unit) }
                discovery.await("3")
                gates.keys.forEach { discovery.await(it) }
                assertEquals(gates.keys, published.keys)
                assertTrue(peak.get() <= 2)
            }
        }
    }

    @Test fun failureIsIsolatedAndRetiredDiscoveryCannotPublish() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val retired = CompletableDeferred<Unit>()
            val published = ConcurrentHashMap<String, String>()
            val failures = ConcurrentHashMap.newKeySet<String>()
            val discovery = ArchiveSidecarDiscovery<String>(this, concurrency = 2) { label, _ -> failures.add(label) }
            try {
                discovery.start(listOf("failed", "late", "good"), { label ->
                    when (label) {
                        "failed" -> error("optional source unavailable")
                        "late" -> try {
                            withContext(NonCancellable) { entered.complete(Unit); release.await() }
                            label
                        } finally { retired.complete(Unit) }
                        else -> label
                    }
                }) { label, result -> published[label] = result }
                entered.await()
                discovery.await("failed")
                discovery.await("good")
                assertTrue("failed" in failures)
                assertEquals(mapOf("good" to "good"), published)
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { discovery.await("late") }
                discovery.close()
                release.complete(Unit)
                retired.await()
                assertTrue(runCatching { waiter.await() }.exceptionOrNull() is CancellationException)
                assertEquals(mapOf("good" to "good"), published)
            } finally {
                discovery.close()
                release.complete(Unit)
            }
        }
    }
}
