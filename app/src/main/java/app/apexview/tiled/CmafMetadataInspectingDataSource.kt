package app.apexview.tiled

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.Arrays

/**
 * Pass-through, read-only observer for the selected F1 tile-test feeds.
 *
 * A bounded transient copy is made only for ISO-BMFF responses. The callback must inspect metadata
 * synchronously and must not retain the array. The copy is zeroed immediately after the callback.
 * Bytes delivered to ExoPlayer are never changed. This class does not decrypt or parse video samples.
 */
internal class CmafMetadataInspectingDataSource(
    private val upstream: DataSource,
    private val shouldCapture: () -> Boolean,
    private val onCmafResponse: (ByteArray) -> Unit,
    private val onCaptureSkipped: () -> Unit = {},
    private val maxCaptureBytes: Int = DEFAULT_MAX_CAPTURE_BYTES,
) : DataSource {

    init {
        require(maxCaptureBytes >= 16) { "Capture limit must be at least one small BMFF box" }
    }

    private var captureBuffer = ByteArray(0)
    private var captureSize = 0
    private var captureDisabled = false
    private var captureOverflowed = false
    private var captureFinished = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        resetCapture()
        return upstream.open(dataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = upstream.read(buffer, offset, length)
        when {
            count == C.RESULT_END_OF_INPUT -> finishCapture()
            count > 0 && !captureFinished -> observe(buffer, offset, count)
        }
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        // An early close is an incomplete response and must not be treated as a valid fragment.
        resetCapture()
        upstream.close()
    }

    private fun observe(source: ByteArray, offset: Int, length: Int) {
        if (captureDisabled || captureOverflowed || !safeShouldCapture()) return
        if (captureSize.toLong() + length.toLong() > maxCaptureBytes.toLong()) {
            captureOverflowed = true
            wipeBuffer()
            return
        }
        ensureCapacity(captureSize + length)
        source.copyInto(captureBuffer, captureSize, offset, offset + length)
        captureSize += length

        // Manifests and other text responses are rejected as soon as the first box type is known.
        if (captureSize >= 8 && !hasIsoBmffPrefix(captureBuffer, captureSize)) {
            captureDisabled = true
            wipeBuffer()
        }
    }

    private fun finishCapture() {
        if (captureFinished) return
        captureFinished = true
        if (!safeShouldCapture()) {
            wipeBuffer()
            return
        }
        if (captureOverflowed) {
            runCatching { onCaptureSkipped() }
            wipeBuffer()
            return
        }
        if (captureDisabled || captureSize < 8 || !hasIsoBmffPrefix(captureBuffer, captureSize)) {
            wipeBuffer()
            return
        }

        val response = captureBuffer.copyOf(captureSize)
        try {
            runCatching { onCmafResponse(response) }
        } finally {
            Arrays.fill(response, 0.toByte())
            wipeBuffer()
        }
    }

    private fun safeShouldCapture(): Boolean = runCatching { shouldCapture() }.getOrDefault(false)

    private fun ensureCapacity(required: Int) {
        if (captureBuffer.size >= required) return
        val nextSize = minOf(
            maxCaptureBytes,
            maxOf(required, if (captureBuffer.isEmpty()) INITIAL_BUFFER_BYTES else captureBuffer.size * 2)
        )
        val previous = captureBuffer
        val next = ByteArray(nextSize)
        if (captureSize > 0) previous.copyInto(next, 0, 0, captureSize)
        Arrays.fill(previous, 0.toByte())
        captureBuffer = next
    }

    private fun resetCapture() {
        wipeBuffer()
        captureDisabled = false
        captureOverflowed = false
        captureFinished = false
    }

    private fun wipeBuffer() {
        if (captureBuffer.isNotEmpty()) Arrays.fill(captureBuffer, 0.toByte())
        captureBuffer = ByteArray(0)
        captureSize = 0
    }

    private fun hasIsoBmffPrefix(bytes: ByteArray, size: Int): Boolean {
        if (size < 8) return false
        val type = String(bytes, 4, 4, Charsets.US_ASCII)
        return type in RECOGNISED_TOP_LEVEL_BOXES
    }

    private companion object {
        const val INITIAL_BUFFER_BYTES = 16 * 1024
        const val DEFAULT_MAX_CAPTURE_BYTES = 8 * 1024 * 1024
        val RECOGNISED_TOP_LEVEL_BOXES = setOf(
            "ftyp", "styp", "moov", "moof", "sidx", "emsg", "prft", "free", "skip"
        )
    }
}
