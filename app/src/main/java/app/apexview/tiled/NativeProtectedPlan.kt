package app.apexview.tiled

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native clear-header and sample-range candidates are admitted only after
 * existing JVM checks. No opaque payload, key, license or DRM data enters JNI.
 * A disagreement disables the experiment for the current protected session.
 */
internal class NativeProtectedPlan(
    private val outputSets: List<ByteArray>,
    private val enabled: () -> Boolean,
    private val reject: () -> Unit,
    private val generate: (ByteArray, Int) -> ByteArray? = NativeStitchingEngine::protectedPlan
) {
    var matchedSlices = 0
        private set
    var matchedMaps = 0
        private set
    private var failed = false

    private fun <T> candidate(kind: Int, encode: () -> ByteArray, accept: (ByteArray) -> T?): T? {
        if (failed || !enabled()) return null
        var packet: ByteArray? = null
        var output: ByteArray? = null
        try {
            packet = encode()
            output = generate(packet, kind)
            if (output != null && enabled()) {
                val accepted = accept(output)
                if (accepted != null) return accepted
            } else if (!enabled()) return null
        } catch (_: RuntimeException) {
            // JVM reference already validated. Never turn a native failure
            // into a change of DRM policy or an exception in valid playback.
        } catch (_: LinkageError) {
            // Library unavailable: explicit session fallback.
        } finally {
            packet?.fill(0)
            output?.fill(0)
        }
        failed = true
        reject()
        return null
    }

    fun verifySlice(
        sourceSets: List<ByteArray>, sample: ByteArray,
        mapped: ProtectedHevcSampleLayout.NalUnit,
        rewritten: ProtectedHevcSliceHeaderRewriter.Borrowed,
        filterOverride: Boolean? = null,
    ) {
        val opaque = rewritten.parts.reencodedClearPayloadBytes == 0
        val end = rewritten.sourceTailOffset + if (opaque) 0 else 1
        val matched = candidate(1, {
            require(end in 3..mapped.clearNalPrefixBytes && end <= 16*1024 &&
                mapped.nalOffset >= 0 && mapped.nalOffset <= sample.size - end)
            val clear = sample.copyOfRange(mapped.nalOffset, mapped.nalOffset + end)
            try {
                encodeSlice(sourceSets, outputSets, clear, rewritten.destinationAddress, opaque, filterOverride)
            } finally { clear.fill(0) }
        }, { output ->
            if (output.size < 6) null else {
                val tail = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN).int
                val prefix = output.copyOfRange(4, output.size)
                if (rewritten.parts.adoptVerifiedNativePrefix(prefix, tail)) true
                else { prefix.fill(0); null }
            }
        })
        if (matched == true) matchedSlices++
    }

    fun matchingMap(
        slices: List<ProtectedHevcSampleComposer.Slice>, width: Int, size: Int,
        reference: List<CmafBoxInspector.SubsampleEncryptionRange>
    ): List<CmafBoxInspector.SubsampleEncryptionRange>? {
        val result = candidate(2, { encodeMap(slices, width) }, { output ->
            val b = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN)
            require(b.remaining() >= 8 && b.int == size)
            val count = b.int
            require(count in 1..4096 && b.remaining() == 6*count)
            val entries = List(count) {
                CmafBoxInspector.SubsampleEncryptionRange(
                    b.short.toInt() and 0xffff, b.int.toLong() and 0xffffffffL)
            }
            entries.takeIf { it == reference }
        })
        if (result != null) matchedMaps++
        return result
    }

    companion object {
        fun encodeSlice(source: List<ByteArray>, output: List<ByteArray>,
                        clear: ByteArray, destination: Int, opaque: Boolean,
                        filterOverride: Boolean? = null): ByteArray {
            require(source.size == 3 && output.size == 3 && destination >= 0)
            val sets = source + output + listOf(clear)
            require(sets.all { it.size in 3..16*1024 })
            val b = ByteBuffer.allocate(16 + sets.sumOf { 4 + it.size }).order(ByteOrder.BIG_ENDIAN)
            b.put(byteArrayOf(0x47, 0x42, 0x53, 0x31)).putInt(destination)
                .putInt(if (opaque) 1 else 0)
                .putInt(if (filterOverride == null) 0 else if (filterOverride) 2 else 1)
            sets.forEach { b.putInt(it.size).put(it) }
            return b.array()
        }

        fun encodeMap(slices: List<ProtectedHevcSampleComposer.Slice>, width: Int): ByteArray {
            require(width in 1..4 && slices.size in 1..4096)
            val size = 12L + slices.sumOf { 8L + 8L*it.protectionRanges.size }
            require(size in 1..2*1024*1024L)
            val b = ByteBuffer.allocate(size.toInt()).order(ByteOrder.BIG_ENDIAN)
            b.put(byteArrayOf(0x47, 0x42, 0x4d, 0x31)).putInt(width).putInt(slices.size)
            slices.forEach { slice ->
                require(slice.size > 0 && slice.protectionRanges.size in 1..4096)
                b.putInt(slice.size).putInt(slice.protectionRanges.size)
                var covered = 0
                slice.protectionRanges.forEach { range ->
                    require(range.offset == covered && range.lengthBytes > 0 &&
                        range.lengthBytes <= slice.size - covered)
                    covered += range.lengthBytes
                    b.putInt(range.lengthBytes).putInt(if (range.isClear) 1 else 0)
                }
                require(covered == slice.size)
            }
            check(!b.hasRemaining())
            return b.array()
        }
    }
}
