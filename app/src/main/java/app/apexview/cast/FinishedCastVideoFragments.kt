package app.apexview.cast

import android.media.MediaFormat
import app.apexview.tiled.HevcClearCmafFragmentWriter
import app.apexview.tiled.HevcClearCmafInitSegmentWriter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Packages the clear AVC encoder output on its codec handler. Each fragment begins with an IDR. */
internal class FinishedCastVideoFragments(
    private val playlist: FinishedCastVideoPlaylist,
) {
    private val samples = ArrayList<HevcClearCmafFragmentWriter.Sample>()
    private var sequence = 1L
    private var originUs: Long? = null
    private var fragmentStartUs = 0L
    private var initialized = false
    private var previous: AccessUnit? = null
    private var lastFrameDurationUs = 0L

    private data class AccessUnit(val data: ByteArray, val timeUs: Long, val keyFrame: Boolean)

    fun format(format: MediaFormat) {
        check(!initialized) { "The Cast encoder changed format during playback" }
        fun configurationNal(key: String, nalType: Int): ByteArray {
            val buffer = checkNotNull(format.getByteBuffer(key)).duplicate()
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            return annexBNals(bytes).single { (it[0].toInt() and 31) == nalType }
        }
        val init = HevcClearCmafInitSegmentWriter.writeAvc(
            format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT),
            configurationNal("csd-0", 7), configurationNal("csd-1", 8),
        )
        initialized = true
        playlist.initialize(init)
    }

    fun sample(data: ByteArray, timeUs: Long, keyFrame: Boolean) {
        check(initialized)
        if (originUs == null) {
            if (!keyFrame) return
            originUs = timeUs
        }
        val relativeUs = timeUs - checkNotNull(originUs)
        val payload = ByteArrayOutputStream(data.size)
        val stream = DataOutputStream(payload)
        val nals = annexBNals(data).filterNot { (it[0].toInt() and 31) in 7..8 }
        require(nals.isNotEmpty()) { "Cast encoder emitted an empty access unit" }
        nals.forEach {
            stream.writeInt(it.size)
            stream.write(it)
        }
        previous?.let { last ->
            val durationUs = relativeUs - last.timeUs
            require(durationUs > 0) { "Cast encoder timestamps must increase" }
            lastFrameDurationUs = durationUs
            append(last, durationUs)
            if (keyFrame) publishThrough(relativeUs)
        }
        previous = AccessUnit(payload.toByteArray(), relativeUs, keyFrame)
    }

    fun finish(nominalFrameDurationUs: Long) {
        require(nominalFrameDurationUs > 0)
        previous?.let { last ->
            val duration = lastFrameDurationUs.takeIf { it > 0 } ?: nominalFrameDurationUs
            append(last, duration)
            publishThrough(last.timeUs + duration)
        }
        previous = null
        playlist.finish()
    }

    private fun append(unit: AccessUnit, durationUs: Long) {
        val flags = if (unit.keyFrame) 0x02000000L else 0x01010000L
        samples += HevcClearCmafFragmentWriter.Sample(unit.data, durationUs, 0, flags)
    }

    private fun publishThrough(endUs: Long) {
        val fragment = HevcClearCmafFragmentWriter.write(
            1, sequence, fragmentStartUs, samples,
            sourceTrackEncrypted = false, sourceFragmentHasEncryptionMetadata = false,
        )
        playlist.publish(sequence++, endUs - fragmentStartUs, fragment)
        fragmentStartUs = endUs
        samples.clear()
    }

    private fun annexBNals(bytes: ByteArray): List<ByteArray> {
        val nals = ArrayList<ByteArray>()
        var start = -1
        var zeros = 0
        for (index in bytes.indices) {
            when (bytes[index].toInt()) {
                0 -> zeros++
                1 -> {
                    if (zeros >= 2) {
                        val end = index - zeros
                        if (start >= 0 && end > start) nals += bytes.copyOfRange(start, end)
                        start = index + 1
                    }
                    zeros = 0
                }
                else -> zeros = 0
            }
        }
        if (start >= 0 && bytes.size - zeros > start) nals += bytes.copyOfRange(start, bytes.size - zeros)
        require(nals.isNotEmpty()) { "Expected Annex B AVC from the Cast encoder" }
        return nals
    }
}
