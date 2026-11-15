package app.apexview.tiled

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.Arrays

/**
 * Pass-through observer for selected F1 tile-test metadata.
 *
 * Only ISO-BMFF init responses are inspected for registered F1 feeds. Media fragments are inspected
 * only for the two explicitly selected tile-test feeds. A bounded transient copy is passed to the
 * parser synchronously and zeroed afterwards. Bytes returned to ExoPlayer are never changed.
 */
internal class CmafMetadataInspectingDataSource(
    private val upstream: DataSource,
    private val shouldCaptureInit: () -> Boolean,
    private val shouldCaptureMedia: () -> Boolean,
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
    private var captureAccepted = false
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
        if (captureDisabled || captureOverflowed || captureFinished) return

        if (!captureAccepted) {
            // Hold only the first eight bytes until the top-level box type can classify the response.
            val prefixBytes = minOf(length, 8 - captureSize)
            if (!append(source, offset, prefixBytes)) return
            if (captureSize < 8) return

            val boxType = String(captureBuffer, 4, 4, Charsets.US_ASCII)
            captureAccepted = when (boxType) {
                in INIT_BOX_TYPES -> safeShouldCaptureInit()
                in MEDIA_BOX_TYPES -> safeShouldCaptureMedia()
                else -> false
            }
            if (!captureAccepted) {
                captureDisabled = true
                wipeBuffer()
                return
            }

            val remaining = length - prefixBytes
            if (remaining > 0) append(source, offset + prefixBytes, remaining)
        } else {
            append(source, offset, length)
        }
    }

    /** Appends bytes while keeping the response strictly below the configured memory bound. */
    private fun append(source: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0) return true
        if (captureSize.toLong() + length.toLong() > maxCaptureBytes.toLong()) {
            captureOverflowed = true
            wipeBuffer()
            return false
        }
        ensureCapacity(captureSize + length)
        source.copyInto(captureBuffer, captureSize, offset, offset + length)
        captureSize += length
        return true
    }

    private fun finishCapture() {
        if (captureFinished) return
        captureFinished = true
        if (!captureAccepted || captureDisabled || !safeShouldContinue()) {
            wipeBuffer()
            return
        }
        if (captureOverflowed) {
            runCatching { onCaptureSkipped() }
            wipeBuffer()
            return
        }
        if (captureSize < 8) {
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

    private fun safeShouldContinue(): Boolean = runCatching {
        val type = String(captureBuffer, 4, 4, Charsets.US_ASCII)
        when (type) {
            in INIT_BOX_TYPES -> shouldCaptureInit()
            in MEDIA_BOX_TYPES -> shouldCaptureMedia()
            else -> false
        }
    }.getOrDefault(false)

    private fun safeShouldCaptureInit(): Boolean = runCatching { shouldCaptureInit() }.getOrDefault(false)

    private fun safeShouldCaptureMedia(): Boolean = runCatching { shouldCaptureMedia() }.getOrDefault(false)

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
        captureAccepted = false
        captureOverflowed = false
        captureFinished = false
    }

    private fun wipeBuffer() {
        if (captureBuffer.isNotEmpty()) Arrays.fill(captureBuffer, 0.toByte())
        captureBuffer = ByteArray(0)
        captureSize = 0
    }

    private companion object {
        const val INITIAL_BUFFER_BYTES = 16 * 1024
        const val DEFAULT_MAX_CAPTURE_BYTES = 8 * 1024 * 1024
        val INIT_BOX_TYPES = setOf("ftyp", "moov")
        val MEDIA_BOX_TYPES = setOf("styp", "moof")
    }
}
