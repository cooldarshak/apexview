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

    /** Direct candidate after exact parity on the current representation.
     * The caller still validates the generated complete fragment independently. */
    fun directHeader(request: ProtectedHevcCmafFragmentWriter.Request, auxiliarySizes: List<Int>,
                     generate: (ByteArray) -> ByteArray? = NativeStitchingEngine::protectedHeader): ByteArray? {
        var packet: ByteArray? = null
        var output: ByteArray? = null
        var accepted = false
        try {
            packet = encode(request, auxiliarySizes)
            output = generate(packet)
            val candidate = output ?: return null
            // Canonical box length and sequence are checked here; independent
            // CMAF inspection subsequently verifies all timing/IV/ranges.
            val n = request.samples.size
            val tfdt = if (request.baseDecodeTimeTicks <= 0xffffffffL) 16 else 20
            val expectedSize = 8 + 16 + 8 + 20 + tfdt + 20 + 16*n + 16 +
                auxiliarySizes.sum() + 17 + n + 20
            require(candidate.size == expectedSize)
            val b = ByteBuffer.wrap(candidate).order(ByteOrder.BIG_ENDIAN)
            require(b.int == expectedSize && b.int == 0x6d6f6f66 &&
                b.int == 16 && b.int == 0x6d666864 && b.int == 0 &&
                (b.int.toLong() and 0xffffffffL) == request.sequenceNumber)
            // Check the canonical data/aux offsets, in addition to the
            // independent inspector's full fragment and CBCS round trip.
            val trun = 8 + 16 + 8 + 20 + tfdt
            val senc = trun + 20 + 16*n
            val saiz = senc + 16 + auxiliarySizes.sum()
            val saio = saiz + 17 + n
            val tfhd = 32
            val tfdtStart = tfhd + 20
            require(b.getInt(24) == expectedSize - 24 && b.getInt(28) == 0x74726166 &&
                b.getInt(tfhd) == 20 && b.getInt(tfhd+4) == 0x74666864 &&
                b.getInt(tfhd+8) == 0x00020002 &&
                (b.getInt(tfhd+12).toLong() and 0xffffffffL) == request.trackId &&
                b.getInt(tfhd+16) == 1 &&
                b.getInt(tfdtStart) == tfdt && b.getInt(tfdtStart+4) == 0x74666474 &&
                b.getInt(tfdtStart+8) == (if (tfdt == 16) 0 else 0x01000000) &&
                b.getInt(trun) == 20+16*n && b.getInt(trun+4) == 0x7472756e &&
                b.getInt(trun+8) == 0x01000f01 && b.getInt(trun+12) == n &&
                b.getInt(trun + 16) == expectedSize + 8 &&
                b.getInt(senc) == 16+auxiliarySizes.sum() && b.getInt(senc+4) == 0x73656e63 &&
                b.getInt(senc+8) == 2 && b.getInt(senc+12) == n &&
                b.getInt(saiz) == 17+n && b.getInt(saiz+4) == 0x7361697a &&
                b.getInt(saiz+8) == 0 && b.get(saiz+12) == 0.toByte() &&
                b.getInt(saiz+13) == n &&
                b.getInt(saio) == 20 && b.getInt(saio+4) == 0x7361696f &&
                b.getInt(saio+8) == 0 && b.getInt(saio+12) == 1 &&
                b.getInt(saio + 16) == senc + 16)
            auxiliarySizes.forEachIndexed { i, size ->
                require((b.get(saiz+17+i).toInt() and 0xff) == size)
            }
            accepted = true
            return candidate
        } catch (_: RuntimeException) { return null }
          catch (_: LinkageError) { return null }
        finally { packet?.fill(0); if (!accepted) output?.fill(0) }
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
