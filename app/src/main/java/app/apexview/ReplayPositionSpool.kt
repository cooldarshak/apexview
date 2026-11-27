package app.apexview

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import app.apexview.tiled.NativeHotPath

/** Single IO owner for the incoming raw position topic. Sparse offsets are
 * byte offsets (not String character counts), never retained JSON or tracks.
 * Only one bounded decode window is read at a time. The caller owns deletion.
 * Unordered sources deliberately fall back to the complete reference pass. */
internal class ReplayPositionSpool(private val file: File, nativeRequested: Boolean = false) : AutoCloseable {
    private val writer = FileOutputStream(file).buffered()
    private var nativeIndex = if (nativeRequested) NativeHotPath.replayIndexCreate() else 0L
    private var indexLost = false
    private var offsets = LongArray(if (nativeIndex == 0L) 64 else 0)
    private var times = LongArray(offsets.size)
    private var marks = 0
    private var records = 0L
    var bytesWritten = 0L
        private set
    var throughMs = Long.MIN_VALUE
        private set
    var chronological = true
        private set
    private var closed = false

    fun append(line: String) {
        check(!closed)
        val bytes = line.toByteArray(Charsets.UTF_8)
        val next = Math.addExact(bytesWritten, bytes.size.toLong() + 1L)
        if (next > MAX_BYTES) throw IOException("Position archive exceeds 64 MiB")
        val prefix = streamPrefixLength(line)
        if (prefix < line.length) {
            val time = streamOffsetMs(line, prefix)
            if (time < throughMs) chronological = false
            if (chronological && records % STRIDE == 0L) {
                if (nativeIndex != 0L) {
                    if (!NativeHotPath.replayIndexMark(nativeIndex, time, bytesWritten)) {
                        NativeHotPath.replayIndexClose(nativeIndex)
                        nativeIndex = 0L
                        indexLost = true
                    }
                } else if (!indexLost) {
                    if (marks == offsets.size) {
                        offsets = offsets.copyOf(offsets.size * 2)
                        times = times.copyOf(times.size * 2)
                    }
                    offsets[marks] = bytesWritten
                    times[marks++] = time
                }
            }
            throughMs = maxOf(throughMs, time)
            records++
        }
        writer.write(bytes)
        writer.write(10)
        bytesWritten = next
    }

    /** Include the preceding sparse block so equal timestamps at a block
     * boundary never lose a record. Exact filtering follows the indexed seek. */
    private fun startOffset(fromMs: Long): Long {
        if (nativeIndex != 0L) return NativeHotPath.replayIndexOffset(nativeIndex, fromMs).coerceAtLeast(0L)
        if (indexLost) return 0L // Exact sequential fallback, never use an incomplete index.
        var lo = 0
        var hi = marks
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] < fromMs) lo = mid + 1 else hi = mid
        }
        return if (lo <= 1) 0L else offsets[lo - 1]
    }

    fun <T> readWindow(fromMs: Long, untilMs: Long, consume: (Sequence<String>) -> T): T {
        check(!closed && chronological)
        require(fromMs >= 0L && untilMs >= fromMs)
        writer.flush()
        FileInputStream(file).use { input ->
            input.channel.position(startOffset(fromMs))
            return input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                consume(sequence {
                    for (line in lines) {
                        val prefix = streamPrefixLength(line)
                        if (prefix >= line.length) continue
                        val offset = streamOffsetMs(line, prefix)
                        if (offset > untilMs) break
                        if (offset >= fromMs) yield(line)
                    }
                })
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { writer.close() } finally {
            NativeHotPath.replayIndexClose(nativeIndex)
            nativeIndex = 0L
        }
    }

    companion object {
        private const val STRIDE = 128L
        private const val MAX_BYTES = 64L * 1024 * 1024
    }
}
