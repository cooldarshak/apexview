package app.apexview.tiled

import androidx.media3.common.ParserException
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.util.concurrent.TimeoutException

internal class PermanentCaptureException(message: String) : IOException(message)

internal data class CaptureFailure(val reason: String, val retryable: Boolean) {
    companion object {
        fun from(error: Exception): CaptureFailure = when (error) {
            is PermanentCaptureException -> CaptureFailure(error.message.orEmpty(), false)
            is HttpDataSource.InvalidResponseCodeException -> CaptureFailure(
                "F1 media HTTP ${error.responseCode}", error.responseCode in setOf(408, 429) || error.responseCode >= 500
            )
            is ParserException -> CaptureFailure("Invalid or unsupported media manifest", false)
            // Future.get() in the per-feed archive fetch lane throws TimeoutException,
            // which is not an IOException. It is still a transient network timeout.
            is TimeoutException -> CaptureFailure("F1 media segment download timed out", true)
            is IOException -> CaptureFailure("F1 media network read failed (${error.javaClass.simpleName})", true)
            else -> CaptureFailure("Capture validation failed (${error.javaClass.simpleName})", false)
        }
    }
}
