package app.apexview.tiled

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** Bounded authorised reads only. Does not request keys or interpret licence endpoints. */
internal class CmafHttpReader(private val factory: DataSource.Factory) {
    class Response(val uri: Uri, val bytes: ByteArray, val coalescedCopyBytes: Long = 0)

    fun read(uri: Uri, limit: Int, offset: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): Response {
        require(offset >= 0 && (length == C.LENGTH_UNSET.toLong() || length in 1..limit.toLong())) {
            "HLS byte range is invalid or exceeds its capture limit"
        }
        require(limit > 0) { "CMAF capture budget must be positive" }
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
            // Unknown-length source: collect bounded chunks, then copy each
            // byte once into the final response. No repeated full-buffer growth
            // or duplicate ByteArrayOutputStream.toByteArray clone.
            val chunks = ArrayList<ByteArray>()
            var active: ByteArray? = null
            var total = 0
            var eof = false
            try {
                while (!eof) {
                    // One-byte overflow probe even at an exact chunk/budget boundary.
                    val capacity = minOf(32 * 1024L, limit.toLong() - total + 1L).toInt()
                    val block = ByteArray(capacity)
                    active = block
                    var used = 0
                    while (used < block.size) {
                        if (Thread.currentThread().isInterrupted) {
                            throw java.io.InterruptedIOException("CMAF read cancelled")
                        }
                        val count = source.read(block, used, block.size - used)
                        if (count == C.RESULT_END_OF_INPUT) {
                            eof = true
                            break
                        }
                        if (count <= 0) {
                            throw PermanentCaptureException("CMAF data source made no progress")
                        }
                        if (count > limit - total) {
                            throw PermanentCaptureException("CMAF response exceeds its capture limit")
                        }
                        used += count
                        total += count
                    }
                    if (used > 0) chunks.add(block) else block.fill(0)
                    active = null
                }
                if (length != C.LENGTH_UNSET.toLong() && total.toLong() != length) {
                    throw PermanentCaptureException("HLS byte-range response was truncated")
                }
                val complete = ByteArray(total)
                var copied = 0
                for (chunk in chunks) {
                    val amount = minOf(chunk.size, total - copied)
                    chunk.copyInto(complete, destinationOffset = copied, endIndex = amount)
                    copied += amount
                }
                return Response(source.uri ?: uri, complete, copied.toLong())
            } finally {
                // Clear transient ciphertext after both successful handoff and
                // exceptions. Never clear the final caller-owned response.
                active?.fill(0)
                chunks.forEach { it.fill(0) }
            }
        } finally { runCatching { source.close() } }
    }
}
