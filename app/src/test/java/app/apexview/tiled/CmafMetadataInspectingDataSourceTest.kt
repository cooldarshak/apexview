package app.apexview.tiled

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class CmafMetadataInspectingDataSourceTest {

    @Test
    fun passesThroughBytesAndInspectsInitResponse() {
        val input = ftypBox()
        var observedBrand: String? = null
        var captures = 0
        val source = source(
            input,
            shouldCaptureInit = { true },
            shouldCaptureMedia = { false },
            onCmafResponse = { bytes ->
                captures++
                observedBrand = CmafBoxInspector.inspect(bytes).majorBrand
            }
        )

        assertArrayEquals(input, readAll(source, input.size))
        assertEquals(1, captures)
        assertEquals("isom", observedBrand)
    }

    @Test
    fun ignoresNonIsoBmffResponses() {
        val input = "#EXTM3U\n#EXT-X-VERSION:7\n".toByteArray()
        var captures = 0
        val source = source(
            input,
            shouldCaptureInit = { true },
            shouldCaptureMedia = { true },
            onCmafResponse = { captures++ }
        )

        assertArrayEquals(input, readAll(source, input.size))
        assertEquals(0, captures)
    }

    @Test
    fun capturesMediaOnlyWhenExplicitlySelected() {
        val input = stypBox()
        var captures = 0
        val source = source(
            input,
            shouldCaptureInit = { true },
            shouldCaptureMedia = { true },
            onCmafResponse = { captures++ }
        )

        assertArrayEquals(input, readAll(source, input.size))
        assertEquals(1, captures)
    }

    @Test
    fun ignoresMediaWhenTileTestIsNotSelected() {
        val input = stypBox()
        var captures = 0
        val source = source(
            input,
            shouldCaptureInit = { true },
            shouldCaptureMedia = { false },
            onCmafResponse = { captures++ }
        )

        assertArrayEquals(input, readAll(source, input.size))
        assertEquals(0, captures)
    }

    @Test
    fun oversizedInitResponseIsSkippedWithoutChangingPlaybackBytes() {
        val input = ftypBox() + ByteArray(64)
        var captures = 0
        var skipped = 0
        val source = source(
            input,
            shouldCaptureInit = { true },
            shouldCaptureMedia = { false },
            onCmafResponse = { captures++ },
            onCaptureSkipped = { skipped++ },
            maxCaptureBytes = 16
        )

        assertArrayEquals(input, readAll(source, input.size))
        assertEquals(0, captures)
        assertEquals(1, skipped)
    }

    @Test
    fun disabledInitCaptureDoesNotObserveResponse() {
        val input = ftypBox()
        var captures = 0
        val source = source(
            input,
            shouldCaptureInit = { false },
            shouldCaptureMedia = { false },
            onCmafResponse = { captures++ }
        )

        assertArrayEquals(input, readAll(source, input.size))
        assertEquals(0, captures)
    }

    private fun source(
        bytes: ByteArray,
        shouldCaptureInit: () -> Boolean,
        shouldCaptureMedia: () -> Boolean,
        onCmafResponse: (ByteArray) -> Unit,
        onCaptureSkipped: () -> Unit = {},
        maxCaptureBytes: Int = 8 * 1024 * 1024
    ) = CmafMetadataInspectingDataSource(
        upstream = ByteArrayDataSource(bytes),
        shouldCaptureInit = shouldCaptureInit,
        shouldCaptureMedia = shouldCaptureMedia,
        onCmafResponse = onCmafResponse,
        onCaptureSkipped = onCaptureSkipped,
        maxCaptureBytes = maxCaptureBytes
    )

    private fun readAll(source: DataSource, expectedSize: Int): ByteArray {
        source.open(DataSpec.Builder().setUri(Uri.parse("https://example.invalid/segment.m4s")).build())
        val output = ByteArray(expectedSize)
        var offset = 0
        val chunk = ByteArray(7)
        while (true) {
            val read = source.read(chunk, 0, chunk.size)
            if (read == C.RESULT_END_OF_INPUT) break
            if (read == 0) continue
            chunk.copyInto(output, offset, 0, read)
            offset += read
        }
        source.close()
        assertEquals(expectedSize, offset)
        return output
    }

    private fun ftypBox(): ByteArray = box("ftyp")
    private fun stypBox(): ByteArray = box("styp")

    private fun box(type: String): ByteArray = byteArrayOf(
        0, 0, 0, 16,
        type[0].code.toByte(), type[1].code.toByte(), type[2].code.toByte(), type[3].code.toByte(),
        'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0, 0, 0, 0
    )

    private class ByteArrayDataSource(private val bytes: ByteArray) : DataSource {
        private var position = 0
        private var opened = false

        override fun addTransferListener(transferListener: TransferListener) = Unit

        @Throws(IOException::class)
        override fun open(dataSpec: DataSpec): Long {
            position = 0
            opened = true
            return bytes.size.toLong()
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(opened)
            if (position >= bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }

        override fun getUri(): Uri? = null
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun close() { opened = false }
    }
}
