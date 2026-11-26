package app.apexview.tiled

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class ArchiveInitializationBackingTest {
    @Test fun concurrentRetirementNeverErasesBorrowedBytes() {
        val init = byteArrayOf(1, 2, 3, 4)
        val owner = ArchiveInitializationBacking(listOf(init))
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (0..1).map {
                pool.submit {
                    owner.read { arrays ->
                        assertSame(init, arrays.single())
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        assertArrayEquals(byteArrayOf(1, 2, 3, 4), arrays.single())
                    }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.close()
            owner.close()
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), init)
            assertThrows(IOException::class.java) { owner.read { fail("Retired owner admitted a new reader") } }
            release.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertArrayEquals(ByteArray(4), init)
        } finally {
            release.countDown()
            pool.shutdownNow()
            owner.close()
        }
    }

    @Test fun repeatedReadsReuseExactArraysAndExceptionRetiresReader() {
        val init = ByteArray(1024) { (it % 251).toByte() }
        val owner = ArchiveInitializationBacking(listOf(init))
        repeat(1000) {
            owner.read { arrays -> assertSame(init, arrays.single()) }
        }
        assertThrows(IllegalStateException::class.java) {
            owner.read { owner.close(); throw IllegalStateException("Synthetic producer failure") }
        }
        assertArrayEquals(ByteArray(init.size), init)
    }

    @Test fun aliasingSourceOwnershipIsRejected() {
        val bytes = byteArrayOf(1, 2)
        assertThrows(IllegalArgumentException::class.java) { ArchiveInitializationBacking(listOf(bytes, bytes)) }
        assertArrayEquals(byteArrayOf(1, 2), bytes)
    }
}
