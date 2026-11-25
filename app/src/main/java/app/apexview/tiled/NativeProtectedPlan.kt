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
    val direct: Boolean = false,
    private val maxBatchSlices: Int = 440,
    private val generate: (ByteArray, Int) -> ByteArray? = NativeStitchingEngine::protectedPlan
) {
    var matchedSlices = 0
        private set
    var matchedMaps = 0
        private set
    private var failed = false
    var matchedBatches = 0
        private set

    data class BatchInput(
        val feedIndex: Int, val sample: ByteArray, val mapped: ProtectedHevcSampleLayout.NalUnit,
        val tail: Int, val destination: Int, val opaque: Boolean, val filter: Boolean? = null
    )
    private data class BatchResult(val input: BatchInput, val prefix: ByteArray)
    private var batch: ArrayDeque<BatchResult>? = null

    fun clearBatch() {
        batch?.forEach { it.prefix.fill(0) }
        batch = null
    }

    private fun fail() {
        clearBatch()
        failed = true
        reject()
    }

    /** One native command for normal pictures; split only when bounded
     * input or worst-case output would exceed a command's capacity.
     * Never retain a partially accepted picture. No opaque media enters JNI. */
    fun prepareBatch(sources: List<List<ByteArray>>, inputs: List<BatchInput>) {
        clearBatch()
        if (!direct || failed || !enabled()) return
        val prepared = ArrayDeque<BatchResult>()
        var transferred = false
        try {
            val chunks = try { batchChunks(sources, outputSets, inputs, maxBatchSlices) }
                catch (_: RuntimeException) { fail(); return }
            for (chunk in chunks) {
                val result = candidate(3, { encodeBatch(sources, outputSets, chunk) }, { output ->
                    val b = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN)
                    require(b.remaining() >= 4 && b.int == chunk.size)
                    val parsed = ArrayDeque<BatchResult>()
                    try {
                        chunk.forEach { input ->
                            require(b.remaining() >= 4)
                            val len = b.int
                            require(len in 6..b.remaining() && len <= 32*1024)
                            require(b.int == input.tail)
                            val prefix = ByteArray(len-4).also { b.get(it) }
                            parsed.addLast(BatchResult(input, prefix))
                        }
                        require(!b.hasRemaining())
                        parsed
                    } catch (failure: Throwable) {
                        parsed.forEach { it.prefix.fill(0) }
                        throw failure
                    }
                }) ?: return
                try {
                    while (result.isNotEmpty()) {
                        val entry = result.first()
                        prepared.addLast(entry)
                        result.removeFirst()
                    }
                } finally { result.forEach { it.prefix.fill(0) } }
            }
            if (!enabled()) return
            require(prepared.size == inputs.size)
            batch = prepared
            matchedBatches++
            transferred = true
        } finally {
            if (!transferred) prepared.forEach { it.prefix.fill(0) }
        }
    }

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
        fail()
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

    /**
     * After an exact published fragment has established the current
     * representation's parity, use the native rewrite without constructing
     * a duplicate JVM prefix. The independently inspected source boundary and
     * caller's output parser still validate every candidate. Null is fallback.
     */
    fun directSlice(
        sourceSets: List<ByteArray>, sample: ByteArray, sourceOffset: Int,
        clearBytes: Int, sourceLength: Int, expectedTail: Int,
        destination: Int, opaque: Boolean, filterOverride: Boolean?,
        accept: (ByteArray, Int) -> HevcSliceHeaderRewriter.Parts?
    ): HevcSliceHeaderRewriter.Parts? {
        if (!direct || failed || !enabled()) return null
        val queued = batch
        if (queued != null) {
            val entry = queued.removeFirstOrNull()
            var transferred = false
            try {
                require(entry != null)
                val i = entry.input
                require(i.sample === sample && i.mapped.nalOffset == sourceOffset &&
                    i.mapped.clearNalPrefixBytes == clearBytes && i.mapped.nalLength == sourceLength &&
                    i.tail == expectedTail && i.destination == destination && i.opaque == opaque &&
                    i.filter == filterOverride)
                val parts = accept(entry.prefix, i.tail)
                if (parts != null) {
                    transferred = true
                    matchedSlices++
                    return parts
                }
            } catch (_: RuntimeException) {
                // Same independently checked fallback as the single kernel.
            } finally { if (!transferred) entry?.prefix?.fill(0) }
            fail()
            return null
        }
        val result = candidate(1, {
            val end = Math.addExact(expectedTail, if (opaque) 0 else 1)
            require(expectedTail in 2 until sourceLength && end in 3..clearBytes &&
                end <= 16*1024 && sourceOffset >= 0 && sourceOffset <= sample.size-end)
            val clear = sample.copyOfRange(sourceOffset, sourceOffset+end)
            try { encodeSlice(sourceSets, outputSets, clear, destination, opaque, filterOverride) }
            finally { clear.fill(0) }
        }, { output ->
            require(output.size >= 6)
            val tail = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN).int
            require(tail == expectedTail)
            val prefix = output.copyOfRange(4, output.size)
            var transferred = false
            try {
                val parts = accept(prefix, tail)
                transferred = parts != null
                parts
            } finally { if (!transferred) prefix.fill(0) }
        })
        if (result != null) matchedSlices++
        return result
    }

    /** Verify native metadata against every original range in one pass.
     * No duplicate JVM range list is created in direct mode. */
    fun directMap(slices: List<ProtectedHevcSampleComposer.Slice>, width: Int, size: Int):
        List<CmafBoxInspector.SubsampleEncryptionRange>? {
        if (!direct) return null
        val result = candidate(2, { encodeMap(slices, width) }, { output ->
            val b = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN)
            require(b.remaining() >= 8 && b.int == size)
            val count = b.int
            require(count in 1..4096 && b.remaining() == 6*count)
            val entries = List(count) {
                CmafBoxInspector.SubsampleEncryptionRange(
                    b.short.toInt() and 0xffff, b.int.toLong() and 0xffffffffL)
            }
            var cursor = 0
            var pending = 0L
            fun consume(clear: Long, encrypted: Long) {
                require(cursor < entries.size)
                val entry = entries[cursor++]
                require(entry.clearBytes.toLong() == clear && entry.encryptedBytes == encrypted)
            }
            fun flush(encrypted: Long) {
                while (pending > 0xffffL) { consume(0xffffL, 0); pending -= 0xffffL }
                consume(pending, encrypted)
                pending = 0
            }
            for (slice in slices) {
                pending += width
                for (range in slice.protectionRanges) {
                    if (range.isClear) pending += range.lengthBytes
                    else flush(range.lengthBytes.toLong())
                }
            }
            if (pending > 0) flush(0)
            require(cursor == entries.size)
            entries
        })
        if (result != null) matchedMaps++
        return result
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
        private const val MAX_BATCH_PACKET = 2*1024*1024L
        // Rust's fixed header arena can emit at most 16 KiB of escaped prefix.
        // Use the larger existing verified JVM response cap conservatively.
        private const val MAX_RESULT_PER_SLICE = 4L + 32*1024L

        internal fun batchChunks(sources: List<List<ByteArray>>, output: List<ByteArray>,
                                 inputs: List<BatchInput>, maxSlices: Int = 440): List<List<BatchInput>> {
            require(sources.size in 1..8 && sources.all { it.size == 3 } && output.size == 3)
            require(inputs.size in 1..440 && maxSlices in 1..440)
            val sets = sources.flatten() + output
            require(sets.all { it.size in 3..16*1024 })
            val overhead = 12L + sets.sumOf { 4L+it.size }
            var previous = -1
            var start = 0
            var bytes = overhead
            val chunks = ArrayList<List<BatchInput>>()
            inputs.forEachIndexed { index, input ->
                val mapped = input.mapped
                val end = Math.addExact(input.tail, if (input.opaque) 0 else 1)
                require(input.feedIndex in sources.indices && input.destination > previous &&
                    input.tail in 2 until mapped.nalLength && end in 3..mapped.clearNalPrefixBytes &&
                    end <= 16*1024 && mapped.nalOffset >= 0 && mapped.nalOffset <= input.sample.size-end)
                previous = input.destination
                val cost = 20L + end
                require(overhead + cost <= MAX_BATCH_PACKET)
                val count = index - start + 1
                if (count > maxSlices || bytes + cost > MAX_BATCH_PACKET ||
                    4L + count * MAX_RESULT_PER_SLICE > MAX_BATCH_PACKET) {
                    require(index > start)
                    chunks += inputs.subList(start, index)
                    start = index
                    bytes = overhead
                }
                bytes += cost
            }
            chunks += inputs.subList(start, inputs.size)
            return chunks
        }

        fun encodeBatch(sources: List<List<ByteArray>>, output: List<ByteArray>,
                        inputs: List<BatchInput>): ByteArray {
            require(sources.size in 1..8 && sources.all { it.size == 3 } && output.size == 3)
            require(inputs.size in 1..440)
            val sets = sources.flatten() + output
            require(sets.all { it.size in 3..16*1024 })
            var previous = -1
            var size = 12L + sets.sumOf { 4L+it.size }
            for (input in inputs) {
                val mapped = input.mapped
                val end = Math.addExact(input.tail, if (input.opaque) 0 else 1)
                require(input.feedIndex in sources.indices && input.destination > previous &&
                    input.tail in 2 until mapped.nalLength && end in 3..mapped.clearNalPrefixBytes &&
                    end <= 16*1024 && mapped.nalOffset >= 0 && mapped.nalOffset <= input.sample.size-end)
                previous = input.destination
                size += 20L + end
            }
            require(size in 1..2*1024*1024L)
            val b = ByteBuffer.allocate(size.toInt()).order(ByteOrder.BIG_ENDIAN)
            b.put(byteArrayOf(0x47,0x42,0x53,0x32)).putInt(sources.size).putInt(inputs.size)
            sets.forEach { b.putInt(it.size).put(it) }
            inputs.forEach { input ->
                val end = input.tail + if (input.opaque) 0 else 1
                b.putInt(input.feedIndex).putInt(input.destination)
                    .putInt(if (input.opaque) 1 else 0)
                    .putInt(if (input.filter == null) 0 else if (input.filter) 2 else 1)
                    .putInt(end).put(input.sample, input.mapped.nalOffset, end)
            }
            check(!b.hasRemaining())
            return b.array()
        }

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
