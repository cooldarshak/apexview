package app.apexview.tiled

import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exact GBC1 / bounded GBC2 wire contract for the native atlas worker.
 * Source ownership remains with the archive producer until submission copies
 * the packet. Only validated unencrypted windows beginning at random access enter this path.
 */
internal object NativeClearArchiveJob {
    const val MAX_PACKET = NativeStitchingEngine.MAX_ATLAS_PACKET
    class OutputCapacityException : IllegalStateException("Native clear output capacity exceeded")
    const val MAX_WINDOW = 4

    data class Geometry(val width: Int, val height: Int, val columns: List<Int>,
                        val rows: List<Int>, val cells: List<HevcTizenGridComposer.CellSelection>,
                        val loopAcrossTiles: Boolean = false)
    data class Encoded(val bytes: ByteArray)
    data class Output(val initialization: ByteArray, val media: ByteArray) {
        fun wipe() { initialization.fill(0); media.fill(0) }
    }

    data class WindowOutput(val initialization: ByteArray, val media: List<ByteArray>) {
        fun wipe() { initialization.fill(0); media.forEach { it.fill(0) } }
    }

    fun size(geometry: Geometry, inits: List<ByteArray>, media: List<ByteArray>): Int =
        windowSize(geometry, inits, media.map { listOf(it) })

    fun windowSize(geometry: Geometry, inits: List<ByteArray>, media: List<List<ByteArray>>): Int {
        require(inits.size in 2..24 && inits.size == media.size)
        val count = media.first().size
        require(count in 1..MAX_WINDOW && media.all { it.size == count })
        require(geometry.width > 0 && geometry.height > 0)
        require(geometry.columns.size in 1..20 && geometry.rows.size in 1..22)
        require(geometry.columns.all { it > 0 } && geometry.rows.all { it > 0 })
        require(geometry.cells.size == geometry.columns.size * geometry.rows.size)
        var bytes = 36L + 4L * (geometry.columns.size + geometry.rows.size + geometry.cells.size)
        inits.indices.forEach { i ->
            require(inits[i].isNotEmpty() && media[i].all { it.isNotEmpty() })
            bytes += 8L + inits[i].size + media[i].sumOf { it.size.toLong() + if (count > 1) 4L else 0L }
        }
        require(bytes in 1..MAX_PACKET.toLong()) { "Native clear packet exceeds bound" }
        return bytes.toInt()
    }

    fun encode(track: Long, sequence: Long, rateNumerator: Int, rateDenominator: Int,
               geometry: Geometry, inits: List<ByteArray>, media: List<ByteArray>): Encoded =
        encodeWindow(track, sequence, rateNumerator, rateDenominator, geometry, inits, media.map { listOf(it) })

    fun encodeWindow(track: Long, sequence: Long, rateNumerator: Int, rateDenominator: Int,
                     geometry: Geometry, inits: List<ByteArray>, media: List<List<ByteArray>>): Encoded {
        require(track in 1..0xffffffffL && sequence in 1..0xffffffffL)
        require(rateNumerator > 0 && rateDenominator > 0)
        val ordered = geometry.cells.sortedWith(compareBy<HevcTizenGridComposer.CellSelection> { it.outputRow }
            .thenBy { it.outputColumn })
        val length = windowSize(geometry, inits, media)
        val count = media.first().size
        require(sequence + count - 1L <= 0xffffffffL)
        ordered.forEachIndexed { i, cell ->
            require(cell.outputRow == i / geometry.columns.size && cell.outputColumn == i % geometry.columns.size)
            require(cell.feedIndex in inits.indices && cell.sourceTileIndex in 0..65535)
        }
        val packet = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        packet.put(byteArrayOf(0x47, 0x42, 0x43,
            (if (geometry.loopAcrossTiles) 0x33 else if (count == 1) 0x31 else 0x32).toByte()))
        packet.putInt(track.toInt()).putInt(sequence.toInt()).putInt(geometry.width).putInt(geometry.height)
        packet.putInt(rateNumerator).putInt(rateDenominator)
        packet.putShort(inits.size.toShort()).putShort(geometry.columns.size.toShort())
            .putShort(geometry.rows.size.toShort()).putShort((if (count == 1 && !geometry.loopAcrossTiles) 0 else count).toShort())
        geometry.columns.forEach { packet.putInt(it) }
        geometry.rows.forEach { packet.putInt(it) }
        ordered.forEach { packet.putShort(it.feedIndex.toShort()).putShort(it.sourceTileIndex.toShort()) }
        inits.indices.forEach { i ->
            val blockSize = media[i].sumOf { it.size.toLong() + if (count > 1) 4L else 0L }.toInt()
            packet.putInt(inits[i].size).putInt(blockSize).put(inits[i])
            media[i].forEach { bytes ->
                if (count > 1) packet.putInt(bytes.size)
                packet.put(bytes)
            }
        }
        check(!packet.hasRemaining())
        return Encoded(packet.array())
    }

    /** The entire lease is acquired once, then copied directly into its two
     * final JVM-owned arrays. Never allocate another full concatenated result. */
    fun execute(engine: NativeStitchingEngine, packet: ByteArray, foreground: Boolean,
                timeoutMs: Long, checkCurrent: () -> Unit): Output {
        val out = executeWindow(engine, packet, 1, foreground, timeoutMs, checkCurrent)
        return Output(out.initialization, out.media.single())
    }

    fun executeWindow(engine: NativeStitchingEngine, packet: ByteArray, count: Int,
                      foreground: Boolean, timeoutMs: Long, checkCurrent: () -> Unit): WindowOutput {
        require(count in 1..MAX_WINDOW)
        require(timeoutMs in 1..60_000)
        checkCurrent()
        val token = engine.submitClearAtlas(engine.generation(), packet, foreground, timeoutMs)
        check(token > 0) { "Native clear admission: $token" }
        val started = System.nanoTime()
        var terminal = false
        var init: ByteArray? = null
        val media = ArrayList<ByteArray>(count)
        var transferred = false
        try {
            var length: Long
            while (true) {
                checkCurrent()
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Native clear cancelled")
                length = engine.acquire(token)
                if (length != 0L) { terminal = true; break }
                check(System.nanoTime() - started < timeoutMs * 1_000_000L) { "Native clear deadline" }
                Thread.sleep(2)
            }
            if (length == NativeStitchingEngine.OUTPUT_CAPACITY) throw OutputCapacityException()
            check(length in 10L..engine.budget.outputBytes.toLong()) { "Native clear result: $length" }
            val header = ByteArray(8)
            check(engine.read(token, 0, header, 0, 8) == 8L)
            val b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            val initSize = b.int
            val mediaSize = b.int
            check(initSize > 0 && mediaSize > 0 && 8L + initSize + mediaSize == length)
            val initBytes = ByteArray(initSize).also { init = it }
            check(engine.read(token, 8, initBytes, 0, initSize) == initSize.toLong())
            var cursor = 8 + initSize
            val end = length.toInt()
            val field = ByteArray(4)
            repeat(count) {
                checkCurrent()
                val size = if (count == 1) mediaSize else {
                    check(end - cursor >= 4)
                    check(engine.read(token, cursor, field, 0, 4) == 4L)
                    cursor += 4
                    ByteBuffer.wrap(field).order(ByteOrder.BIG_ENDIAN).int
                }
                check(size > 0 && size <= end - cursor)
                val bytes = ByteArray(size).also { media.add(it) }
                check(engine.read(token, cursor, bytes, 0, size) == size.toLong())
                cursor += size
            }
            check(cursor == end)
            checkCurrent()
            check(engine.release(token) == 1L)
            terminal = false
            transferred = true
            return WindowOutput(initBytes, media)
        } finally {
            if (!transferred) {
                init?.fill(0); media.forEach { it.fill(0) }
                // Failed jobs/ready leases can be released. A pending job must
                // be generation-fenced, not released or overwritten.
                if (terminal) engine.release(token) else engine.seek()
            }
        }
    }
}
