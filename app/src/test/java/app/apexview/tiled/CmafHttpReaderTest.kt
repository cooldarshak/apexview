package app.apexview.tiled

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Media3 DataSource contract tests; no generated F1 media or DRM bypass.
 * The same reader feeds clear archive, CBCS capture, and authorized Cast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CmafHttpReaderTest {
    private val uri = Uri.parse("https://f1.example/segment.m4s")

    private class BytesSource(
        private val bytes: ByteArray,
        private val reported: Long,
        private val noProgress: Boolean = false,
    ) : DataSource {
        private var cursor = 0
        override fun open(dataSpec: DataSpec): Long {
            cursor = dataSpec.position.toInt()
            return reported
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (noProgress) return 0
            if (cursor >= bytes.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, 4096, bytes.size - cursor)
            bytes.copyInto(buffer, offset, cursor, cursor + n)
            cursor += n
            return n
        }
        override fun getUri(): Uri? = Uri.parse("https://f1.example/segment.m4s")
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun close() = Unit
    }

    private fun reader(bytes: ByteArray, reported: Long, noProgress: Boolean = false) =
        CmafHttpReader(DataSource.Factory { BytesSource(bytes, reported, noProgress) })

    @Test fun exactKnownLengthReadAvoidsSecondFullByteArrayCopyAndReturnsAllBytes() {
        val original = ByteArray(192 * 1024) { i -> (i * 31).toByte() }
        val response = reader(original, original.size.toLong()).read(uri, original.size)
        assertArrayEquals(original, response.bytes)
        assertEquals(uri, response.uri)
        assertEquals(0L,response.coalescedCopyBytes)
    }

    @Test fun dashDataSpecUsesExactRangeAndRejectsUnderreportedLength() {
        val original = ByteArray(48) { it.toByte() }
        val range = DataSpec.Builder().setUri(uri).setPosition(16).setLength(32).build()
        val result = reader(original, 32L).read(range, 64, verifyEnd = true)
        assertArrayEquals(original.copyOfRange(16, 48), result.bytes)
        assertEquals(0L, result.coalescedCopyBytes)
        val full = DataSpec.Builder().setUri(uri).build()
        assertThrows(PermanentCaptureException::class.java) {
            reader(original, 16L).read(full, 64, verifyEnd = true)
        }
        assertArrayEquals(original, reader(original, C.LENGTH_UNSET.toLong()).read(full, 64, verifyEnd = true).bytes)
    }

    @Test fun unknownLengthStillUsesBoundedFallbackAndReturnsAllBytes() {
        val original = ByteArray(130_099) { i -> (i * 13).toByte() }
        val response = reader(original, C.LENGTH_UNSET.toLong()).read(uri, original.size)
        assertArrayEquals(original, response.bytes)
        assertEquals(original.size.toLong(),response.coalescedCopyBytes)
    }

    @Test fun bothKnownLengthAndUnknownLengthRejectTruncationAndZeroProgress() {
        val content = byteArrayOf(1, 2, 3, 4, 5)
        assertThrows(PermanentCaptureException::class.java) {
            reader(content, 12L).read(uri, 20)
        }
        assertThrows(PermanentCaptureException::class.java) {
            reader(content, C.LENGTH_UNSET.toLong(), noProgress = true).read(uri, 20)
        }
        assertThrows(PermanentCaptureException::class.java) {
            reader(content, 5L, noProgress = true).read(uri, 20)
        }
        assertThrows(PermanentCaptureException::class.java) {
            reader(content, content.size.toLong()).read(uri, 4)
        }
    }

    @Test fun singleExactUnknownLengthChunkTransfersOwnershipWithoutCoalescing() {
        val exact = ByteArray(32 * 1024) { (it * 13).toByte() }
        val result = reader(exact, C.LENGTH_UNSET.toLong()).read(uri, exact.size)
        assertArrayEquals(exact, result.bytes)
        assertEquals(0L, result.coalescedCopyBytes)
    }

    @Test fun unknownLengthAcceptsExactChunkBoundaryAndRejectsOneExtraByte() {
        val exact = ByteArray(64 * 1024) { it.toByte() }
        assertArrayEquals(exact, reader(exact, C.LENGTH_UNSET.toLong()).read(uri, exact.size).bytes)
        assertThrows(PermanentCaptureException::class.java) {
            reader(exact, C.LENGTH_UNSET.toLong()).read(uri, exact.size - 1)
        }
    }

    @Test fun unknownLengthAllowsEmptyDataButRejectsOversizedUntrustedContent() {
        assertEquals(0, reader(byteArrayOf(), C.LENGTH_UNSET.toLong()).read(uri, 1).bytes.size)
        val tooLarge = ByteArray(96 * 1024) { (it * 3).toByte() }
        assertThrows(PermanentCaptureException::class.java) {
            reader(tooLarge, C.LENGTH_UNSET.toLong()).read(uri, 48 * 1024)
        }
    }

    @Test fun knownLengthIofailureWipesPartialCiphertextAndClosesSource() {
        var capturedBuffer: ByteArray? = null
        var closed = false
        var calls = 0
        val source = object : DataSource {
            override fun open(dataSpec: DataSpec) = 16L
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                capturedBuffer = buffer
                calls++
                if (calls == 2) throw java.io.IOException("network interrupted")
                repeat(8) { buffer[offset + it] = 0x55.toByte() }
                return 8
            }
            override fun getUri(): Uri? = uri
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun close() { closed = true }
        }
        assertThrows(java.io.IOException::class.java) {
            CmafHttpReader(DataSource.Factory { source }).read(uri, 16)
        }
        assertEquals(true, closed)
        assertArrayEquals(ByteArray(16), capturedBuffer)
    }

    @Test fun unknownLengthIofailureWipesAllTransientChunksAndClosesSource() {
        val borrowed = mutableListOf<ByteArray>()
        var closed = false
        var calls = 0
        val source = object : DataSource {
            override fun open(dataSpec: DataSpec) = C.LENGTH_UNSET.toLong()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                borrowed.add(buffer)
                calls++
                if (calls == 2) throw java.io.IOException("connection reset")
                repeat(length) { buffer[offset + it] = 0x41.toByte() }
                return length
            }
            override fun getUri(): Uri? = uri
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun close() { closed = true }
        }
        assertThrows(java.io.IOException::class.java) {
            CmafHttpReader(DataSource.Factory { source }).read(uri, 64 * 1024)
        }
        assertEquals(true, closed)
        assertEquals(2, borrowed.size)
        borrowed.forEach { assertArrayEquals(ByteArray(it.size), it) }
    }

    @Test fun explicitRangeWithUnknownOpenLengthAvoidsFullCoalescingAndChecksEof() {
        val original = ByteArray(130_099) { (it * 13).toByte() }
        val result = reader(original, C.LENGTH_UNSET.toLong()).read(uri, original.size,
            offset = 16, length = original.size.toLong() - 16)
        assertArrayEquals(original.copyOfRange(16, original.size), result.bytes)
        assertEquals(0L, result.coalescedCopyBytes)
        assertThrows(PermanentCaptureException::class.java) {
            reader(original, C.LENGTH_UNSET.toLong()).read(uri, original.size + 1,
                length = original.size.toLong() + 1)
        }
        assertThrows(PermanentCaptureException::class.java) {
            reader(original, C.LENGTH_UNSET.toLong()).read(uri, original.size,
                length = original.size.toLong() - 1)
        }
    }

    @Test fun explicitRangeMustMatchReturnedBytesInBothTransferModes() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertThrows(PermanentCaptureException::class.java) {
            reader(bytes, bytes.size.toLong()).read(uri, 20, length = 3)
        }
        assertThrows(PermanentCaptureException::class.java) {
            reader(bytes, C.LENGTH_UNSET.toLong()).read(uri, 20, length = 3)
        }
    }
}
