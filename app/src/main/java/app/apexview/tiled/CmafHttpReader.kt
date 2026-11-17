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
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("CMAF read cancelled")
                val count = source.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                if (count == C.RESULT_END_OF_INPUT) break
                if (count <= 0) continue
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
