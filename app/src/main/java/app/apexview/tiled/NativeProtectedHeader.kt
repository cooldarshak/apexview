package app.apexview.tiled

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Optional metadata-only native implementation of the already validated JVM
 * moof writer. No protected sample bytes, PSSH, license or key enter JNI.
 * The existing JVM validation is authoritative; only byte-identical native
 * metadata may be used for the final authorized output fragment.
 */
internal object NativeProtectedHeader {
    private const val MAX_PACKET = 2 * 1024 * 1024

    fun encode(request: ProtectedHevcCmafFragmentWriter.Request, auxiliarySizes: List<Int>): ByteArray {
        require(request.samples.size in 1..4096 && request.samples.size == auxiliarySizes.size)
        require(auxiliarySizes.all { it in 1..255 })
        require(request.trackId in 1..0xffffffffL && request.sequenceNumber in 1..0xffffffffL)
        require(request.baseDecodeTimeTicks >= 0 && request.perSampleIvSize in setOf(0, 8, 16))
        val size = 28L + 16L * request.samples.size + auxiliarySizes.sumOf { it.toLong() }
        require(size in 1..MAX_PACKET.toLong())
        val packet = ByteArray(size.toInt())
        try {
            val b = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
            b.put(byteArrayOf(0x47, 0x42, 0x50, 0x31))
            b.putInt(request.trackId.toInt()).putInt(request.sequenceNumber.toInt())
                .putLong(request.baseDecodeTimeTicks).putInt(request.perSampleIvSize)
                .putInt(request.samples.size)
            request.samples.forEachIndexed { i, sample ->
                require(sample.durationTicks in 1..0xffffffffL && sample.size in 1..8*1024*1024 &&
                    sample.sampleFlags in 0..0xffffffffL &&
                    sample.compositionOffsetTicks in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                b.putInt(sample.durationTicks.toInt()).putInt(sample.size).putInt(sample.sampleFlags.toInt())
                    .putInt(sample.compositionOffsetTicks.toInt())
                val start = b.position()
                if (request.perSampleIvSize > 0) {
                    val iv = requireNotNull(sample.perSampleIvBytes)
                    require(iv.size == request.perSampleIvSize)
                    b.put(iv)
                } else require(sample.perSampleIvBytes == null)
                require(sample.subsampleRanges.size in 1..4096)
                b.putShort(sample.subsampleRanges.size.toShort())
                sample.subsampleRanges.forEach { range ->
                    require(range.clearBytes in 0..0xffff && range.encryptedBytes in 0..0xffffffffL)
                    b.putShort(range.clearBytes.toShort()).putInt(range.encryptedBytes.toInt())
                }
                check(b.position() - start == auxiliarySizes[i])
            }
            check(!b.hasRemaining())
            return packet
        } catch (failure: Throwable) {
            packet.fill(0)
            throw failure
        }
    }

    /** Null means JVM fallback. Neither malformed native output nor an
     * unavailable library can change the authorized metadata or media bytes. */
    fun matchingHeader(reference: ByteArray, request: ProtectedHevcCmafFragmentWriter.Request,
                       auxiliarySizes: List<Int>,
                       generate: (ByteArray) -> ByteArray? = NativeStitchingEngine::protectedHeader): ByteArray? {
        var packet: ByteArray? = null
        var candidate: ByteArray? = null
        var transferred = false
        try {
            val encoded = encode(request, auxiliarySizes)
            packet = encoded
            candidate = generate(encoded)
            if (candidate == null || !candidate.contentEquals(reference)) return null
            transferred = true
            return candidate
        } catch (_: RuntimeException) {
            return null
        } catch (_: LinkageError) {
            return null
        } finally {
            packet?.fill(0)
            if (!transferred && candidate !== reference) candidate?.fill(0)
        }
    }
}
