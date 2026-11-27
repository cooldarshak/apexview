package app.apexview.cast

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class BakedAudioRecoveryTest {
    @Test fun wrappedTransientNetworkErrorRetriesBoundedlyButMediaAndAuthorizationDoNot() {
        val dns = IOException("wrapped", UnknownHostException("temporary"))
        assertEquals(500L, BakedAudioRecovery.retryDelayMs(dns, 0))
        assertEquals(1000L, BakedAudioRecovery.retryDelayMs(dns, 1))
        assertEquals(2000L, BakedAudioRecovery.retryDelayMs(dns, 2))
        assertNull(BakedAudioRecovery.retryDelayMs(dns, 3))
        assertNull(BakedAudioRecovery.retryDelayMs(IllegalArgumentException("invalid media"), 0))
        assertNull(BakedAudioRecovery.retryDelayMs(IOException("HTTP 403"), 0))
        assertNull(BakedAudioRecovery.retryDelayMs(java.io.InterruptedIOException("cancelled"), 0))
    }

    @Test fun failedLaterSegmentWipesUnpublishedSamplesAndRollsBackBothClocks() {
        val progress = BakedAudioReadProgress(1000L)
        val bytes = byteArrayOf(9, 7, 5)
        val failure = UnknownHostException("temporary")
        val thrown = runCatching {
            progress.transaction { output ->
                output.add(BakedCastTransportStream.Sample(0L, bytes, false))
                progress.nextAudioUs = 2000L
                progress.lastLoadedSegmentStartUs = 1000L
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, thrown)
        assertEquals(1000L, progress.nextAudioUs)
        assertEquals(Long.MIN_VALUE, progress.lastLoadedSegmentStartUs)
        assertArrayEquals(ByteArray(3), bytes)
        val retry = progress.transaction { output ->
            output.add(BakedCastTransportStream.Sample(0L, byteArrayOf(9, 7, 5), false))
            progress.nextAudioUs = 2000L
            progress.lastLoadedSegmentStartUs = 1000L
        }
        assertEquals(1, retry.size)
        assertArrayEquals(byteArrayOf(9, 7, 5), retry.single().bytes)
        assertEquals(2000L, progress.nextAudioUs)
        assertEquals(1000L, progress.lastLoadedSegmentStartUs)
    }
}
