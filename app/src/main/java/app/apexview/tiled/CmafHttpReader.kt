package app.apexview.tiled

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream

/** Bounded authorised reads only. Does not request keys or interpret licence endpoints. */
internal class CmafHttpReader(private val factory: DataSource.Factory) {
    class Response(val uri: Uri, val bytes: ByteArray)

    fun read(uri: Uri, limit: Int, offset: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): Response {
        require(offset >= 0 && (length == C.LENGTH_UNSET.toLong() || length in 1..limit.toLong())) {
            "HLS byte range is invalid or exceeds its capture limit"
        }
        val source = factory.createDataSource()
        try {
            val size = source.open(DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length).build())
            if (size > limit) throw PermanentCaptureException("CMAF response exceeds its capture limit")
            // Media3 DataSource.open() reports the exact transfer length when
            // Content-Length or DataSpec range bounds are known. Avoid the
            // ByteArrayOutputStream growth + toByteArray full copy for every
            // large clear or CBCS CMAF segment. This buffer owns the response
            // until it passes to the authorized composition/cast consumer.
            if (size >= 0L) {
                val exact = ByteArray(size.toInt())
                try {
                    var readAt = 0
                    while (readAt < exact.size) {
                        if (Thread.currentThread().isInterrupted) {
                            throw java.io.InterruptedIOException("CMAF read cancelled")
                        }
                        val count = source.read(exact, readAt, exact.size - readAt)
                        if (count <= 0) {
                            throw PermanentCaptureException(
                                "Known-length CMAF response was truncated or made no progress"
                            )
                        }
                        readAt += count
                    }
                    if (length != C.LENGTH_UNSET.toLong() && exact.size.toLong() != length) {
                        throw PermanentCaptureException("HLS byte-range response was truncated")
                    }
                    return Response(source.uri ?: uri, exact)
                } catch (error: Exception) {
                    // Zero partly received CBCS ciphertext on I/O cancellation,
                    // transport failure, or invalid byte-range metadata.
                    exact.fill(0)
                    throw error
                }
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("CMAF read cancelled")
                val count = source.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                if (count == C.RESULT_END_OF_INPUT) break
                // DataSource.read(nonzero length) must return bytes or EOF.
                // A broken transport that repeatedly returns zero otherwise
                // spins the IO thread forever and starves playback workers.
                if (count == 0) throw PermanentCaptureException("CMAF data source made no progress")
                if (output.size().toLong() + count > limit) throw PermanentCaptureException("CMAF response exceeds its capture limit")
                output.write(buffer, 0, count)
            }
            if (length != C.LENGTH_UNSET.toLong() && output.size().toLong() != length) {
                throw PermanentCaptureException("HLS byte-range response was truncated")
            }
            return Response(source.uri ?: uri, output.toByteArray())
        } finally { runCatching { source.close() } }
    }
}
