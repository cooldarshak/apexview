package app.apexview.tiled

import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exact GBC1 wire contract for the already-verified native atlas worker.
 * Source ownership remains with the archive producer until submission copies
 * the packet. Only validated, unencrypted, finite clear video enters this path.
 */
internal object NativeClearArchiveJob {
    const val MAX_PACKET = 8 * 1024 * 1024

    data class Geometry(val width: Int, val height: Int, val columns: List<Int>,
                        val rows: List<Int>, val cells: List<HevcTizenGridComposer.CellSelection>)
    data class Encoded(val bytes: ByteArray)
    data class Output(val initialization: ByteArray, val media: ByteArray) {
        fun wipe() { initialization.fill(0); media.fill(0) }
    }

    fun size(geometry: Geometry, inits: List<ByteArray>, media: List<ByteArray>): Int {
        require(inits.size in 2..24 && inits.size == media.size)
        require(geometry.width > 0 && geometry.height > 0)
        require(geometry.columns.size in 1..20 && geometry.rows.size in 1..22)
        require(geometry.columns.all { it > 0 } && geometry.rows.all { it > 0 })
        require(geometry.cells.size == geometry.columns.size * geometry.rows.size)
        var bytes = 36L + 4L * (geometry.columns.size + geometry.rows.size + geometry.cells.size)
        inits.indices.forEach { i ->
            require(inits[i].isNotEmpty() && media[i].isNotEmpty())
            bytes += 8L + inits[i].size + media[i].size
        }
        require(bytes in 1..MAX_PACKET.toLong()) { "Native clear packet exceeds bound" }
        return bytes.toInt()
    }

    fun encode(track: Long, sequence: Long, rateNumerator: Int, rateDenominator: Int,
               geometry: Geometry, inits: List<ByteArray>, media: List<ByteArray>): Encoded {
        require(track in 1..0xffffffffL && sequence in 1..0xffffffffL)
        require(rateNumerator > 0 && rateDenominator > 0)
        val ordered = geometry.cells.sortedWith(compareBy<HevcTizenGridComposer.CellSelection> { it.outputRow }
            .thenBy { it.outputColumn })
        val length = size(geometry, inits, media)
        ordered.forEachIndexed { i, cell ->
            require(cell.outputRow == i / geometry.columns.size && cell.outputColumn == i % geometry.columns.size)
            require(cell.feedIndex in inits.indices && cell.sourceTileIndex in 0..65535)
        }
        val packet = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN)
        packet.put(byteArrayOf(0x47, 0x42, 0x43, 0x31))
        packet.putInt(track.toInt()).putInt(sequence.toInt()).putInt(geometry.width).putInt(geometry.height)
        packet.putInt(rateNumerator).putInt(rateDenominator)
        packet.putShort(inits.size.toShort()).putShort(geometry.columns.size.toShort())
            .putShort(geometry.rows.size.toShort()).putShort(0)
        geometry.columns.forEach { packet.putInt(it) }
        geometry.rows.forEach { packet.putInt(it) }
        ordered.forEach { packet.putShort(it.feedIndex.toShort()).putShort(it.sourceTileIndex.toShort()) }
        inits.indices.forEach { i ->
            packet.putInt(inits[i].size).putInt(media[i].size).put(inits[i]).put(media[i])
        }
        check(!packet.hasRemaining())
        return Encoded(packet.array())
    }

    /** The entire lease is acquired once, then copied directly into its two
     * final JVM-owned arrays. Never allocate another full concatenated result. */
    fun execute(engine: NativeStitchingEngine, packet: ByteArray, foreground: Boolean,
                timeoutMs: Long, checkCurrent: () -> Unit): Output {
        require(timeoutMs in 1..60_000)
        checkCurrent()
        val token = engine.submitClearAtlas(engine.generation(), packet, foreground, timeoutMs)
        check(token > 0) { "Native clear admission: $token" }
        val started = System.nanoTime()
        var terminal = false
        var init: ByteArray? = null
        var media: ByteArray? = null
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
            check(length in 10L..engine.budget.outputBytes.toLong()) { "Native clear result: $length" }
            val header = ByteArray(8)
            check(engine.read(token, 0, header, 0, 8) == 8L)
            val b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            val initSize = b.int
            val mediaSize = b.int
            check(initSize > 0 && mediaSize > 0 && 8L + initSize + mediaSize == length)
            val initBytes = ByteArray(initSize).also { init = it }
            val mediaBytes = ByteArray(mediaSize).also { media = it }
            check(engine.read(token, 8, initBytes, 0, initSize) == initSize.toLong())
            check(engine.read(token, 8 + initSize, mediaBytes, 0, mediaSize) == mediaSize.toLong())
            checkCurrent()
            check(engine.release(token) == 1L)
            terminal = false
            transferred = true
            return Output(initBytes, mediaBytes)
        } finally {
            if (!transferred) {
                init?.fill(0); media?.fill(0)
                // Failed jobs/ready leases can be released. A pending job must
                // be generation-fenced, not released or overwritten.
                if (terminal) engine.release(token) else engine.seek()
            }
        }
    }
}
