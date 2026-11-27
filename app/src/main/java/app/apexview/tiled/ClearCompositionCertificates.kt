package app.apexview.tiled

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/** Small process-memory-only exact-input proof cache. No source media,
 * DRM data, or output fragments are retained. A key includes every request
 * field and every input byte; shape/rendition equality alone is insufficient. */
internal class ClearCompositionCertificates {
    internal class Certificate(initialization: ByteArray, val requiredBytes: Int,
                               val mediaDigests: List<String>) {
        private val init = initialization.copyOf()
        fun initialization() = init.copyOf()
        fun matchesInitialization(bytes: ByteArray) = init.contentEquals(bytes)
        fun retire() { init.fill(0) }
    }

    private val entries = LinkedHashMap<String, Certificate>(4, 0.75f, true)
    fun find(key: String): Certificate? = entries[key]
    fun remember(key: String, init: ByteArray, media: List<ByteArray>) {
        if (init.size !in 1..64*1024 || media.size !in 1..NativeClearArchiveJob.MAX_WINDOW) return
        val required = 8L + init.size + media.sumOf { it.size.toLong() + if (media.size > 1) 4L else 0L }
        if (required !in 1L..NativeClearArchiveJob.MAX_PACKET.toLong()) return
        val value = Certificate(init, required.toInt(), media.map { digest(it) })
        entries.put(key, value)?.retire()
        while (entries.size > 4) {
            val oldest = entries.entries.iterator()
            oldest.next().value.retire()
            oldest.remove()
        }
    }

    fun clear() { entries.values.forEach { it.retire() }; entries.clear() }

    companion object {
        fun digest(bytes: ByteArray): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun digest(buffer: ByteBuffer): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(buffer.asReadOnlyBuffer().apply { position(0) })
            return Base64.getEncoder().encodeToString(md.digest())
        }

        fun key(request: HevcClearCmafGridComposer.Request): String {
            val md = MessageDigest.getInstance("SHA-256")
            val field = ByteBuffer.allocate(8)
            fun number(value: Long) {
                field.clear(); field.putLong(value); md.update(field.array())
            }
            fun bytes(value: ByteArray) { number(value.size.toLong()); md.update(value) }
            bytes("pitwall-clear-exact-proof-v1".toByteArray(Charsets.UTF_8))
            number(request.outputTrackId); number(request.firstOutputSequenceNumber)
            number(request.frameRateNumerator.toLong()); number(request.frameRateDenominator.toLong())
            number(request.outputGrid.columns.toLong()); number(request.outputGrid.rows.toLong())
            number(request.cells.size.toLong())
            request.cells.forEach { cell ->
                number(cell.feedIndex.toLong()); number(cell.sourceTileIndex.toLong())
                number(cell.outputColumn.toLong()); number(cell.outputRow.toLong())
            }
            number(request.feeds.size.toLong())
            request.feeds.forEach { feed ->
                bytes(feed.label.toByteArray(Charsets.UTF_8))
                bytes(feed.initializationSegment)
                number(feed.mediaFragments.size.toLong())
                feed.mediaFragments.forEach(::bytes)
            }
            return Base64.getEncoder().encodeToString(md.digest())
        }
    }
}
