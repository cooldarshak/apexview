package app.apexview.tiled

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

class CaptureFailureTest {
    @Test fun futureTimeoutRetriesInsteadOfStrandingTheSingleDecoderPreview() {
        val failure = CaptureFailure.from(TimeoutException())
        assertTrue(failure.retryable)
        assertTrue(failure.reason.contains("timed out"))
    }

    @Test fun socketTimeoutRemainsRetryable() {
        assertTrue(CaptureFailure.from(SocketTimeoutException("timeout")).retryable)
    }

    @Test fun invalidCompositionIsNotTreatedAsAConnectivityFailure() {
        assertFalse(CaptureFailure.from(IllegalArgumentException("incompatible HEVC tiles")).retryable)
    }
}
