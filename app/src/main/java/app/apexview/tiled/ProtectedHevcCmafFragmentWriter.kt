package app.apexview.tiled

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Produces protected fragmented-MP4 output for a composed sequence of HEVC access units.
 *
 * The writer keeps the original track's tenc/CBCS defaults and copies the designated master
 * sample's IV into the output senc record. It never decrypts or re-encrypts sample bytes.
 */
internal object ProtectedHevcCmafFragmentWriter {
    data class Sample(
        val sampleBytes: ByteArray,
        val subsampleRanges: List<CmafBoxInspector.SubsampleEncryptionRange>,
        val perSampleIvBytes: ByteArray?,
        /** Fingerprint captured from the selected main-feed senc record. */
        val perSampleIvIdentity: ProtectedValueFingerprint?,
        val durationTicks: Long,
        val sampleFlags: Long,
        val compositionOffsetTicks: Long
    )

    data class Request(
        val trackId: Long,
        val baseDecodeTimeTicks: Long,
        val sequenceNumber: Long,
        val perSampleIvSize: Int,
        val samples: List<Sample>,
        val stypBox: ByteArray? = null
    )

    private const val UINT32_MAX = 0xffff_ffffL
    private const val MAX_FRAGMENT_BYTES = 64 * 1024 * 1024
    private const val MAX_MDAT_BYTES = 56 * 1024 * 1024
    private const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024
    private const val MAX_SAMPLE_COUNT = 4096
    private const val MAX_SUBSAMPLES = 4096

    fun write(
        outputInitializationSegment: ByteArray,
        request: Request,
        context: String = "Protected HEVC CMAF fragment writer"
    ): ByteArray {
        require(request.trackId in 1..UINT32_MAX &&
            request.baseDecodeTimeTicks >= 0L &&
            request.sequenceNumber in 1..UINT32_MAX) {
            "$context: track, decode-time or sequence number is outside supported range"
        }
        require(request.perSampleIvSize in setOf(0, 8, 16)) {
            "$context: unsupported effective per-sample IV size"
        }
        require(request.samples.isNotEmpty() && request.samples.size <= MAX_SAMPLE_COUNT) {
            "$context: output sample count is outside the supported range"
        }

        val initReport = CmafBoxInspector.inspect(outputInitializationSegment)
        require(initReport.errors.isEmpty() && initReport.initializationSegmentPresent) {
            "$context: output init is invalid: " + initReport.errors.joinToString("; ")
        }
        val track = initReport.tracks.singleOrNull {
            it.handler == "vide" && it.trackId == request.trackId
        } ?: throw IllegalArgumentException("$context: output video track was not found")
        val tenc = requireNotNull(track.trackEncryption) {
            "$context: output init has no tenc metadata"
        }
        require(track.encrypted && track.sampleEntry == "encv" &&
            track.originalFormat in setOf("hvc1", "hev1") &&
            track.protectionScheme == "cbcs" && tenc.defaultIsProtected &&
            tenc.defaultKidIdentity != null && tenc.perSampleIvSize == request.perSampleIvSize) {
            "$context: output init does not match the requested protected CBCS configuration"
        }
        if (request.perSampleIvSize == 0) {
            require(tenc.constantIvSize in setOf(8, 16) && tenc.constantIvIdentity != null) {
                "$context: constant-IV tenc configuration is incomplete"
            }
        }

        val styp = request.stypBox?.also {
            val parsed = parseBoxes(it, 0, it.size)
            require(parsed.size == 1 && parsed.single().type == "styp") {
                "$context: optional styp input must contain exactly one styp box"
            }
        } ?: ByteArray(0)

        var mdatBytes = 0L
        val auxiliarySizes = ArrayList<Int>(request.samples.size)
        request.samples.forEachIndexed { index, sample ->
            require(sample.sampleBytes.isNotEmpty() && sample.sampleBytes.size <= MAX_SAMPLE_BYTES) {
                "$context: output sample " + index + " is empty or too large"
            }
            require(sample.subsampleRanges.isNotEmpty() &&
                sample.subsampleRanges.size <= MAX_SUBSAMPLES) {
                "$context: output sample " + index + " has no supported subsample map"
            }
            if (request.perSampleIvSize == 0) {
                require(sample.perSampleIvBytes == null && sample.perSampleIvIdentity == null) {
                    "$context: constant-IV output sample unexpectedly contains a per-sample IV identity"
                }
            } else {
                val iv = sample.perSampleIvBytes
                require(iv != null && iv.size == request.perSampleIvSize) {
                    "$context: output sample " + index + " has no matching per-sample IV"
                }
                val expectedIv = sample.perSampleIvIdentity
                require(expectedIv != null && ProtectedValueFingerprint.fromBytes("iv", iv) == expectedIv) {
                    "$context: output sample " + index + " IV differs from the requested master-feed sample identity"
                }
            }
            validateSubsamples(sample, index, context)
            val auxSize = Math.addExact(
                request.perSampleIvSize,
                Math.addExact(2, Math.multiplyExact(6, sample.subsampleRanges.size))
            )
            require(auxSize in 1..255) {
                "$context: output sample " + index + " senc record exceeds the saiz uint8 limit"
            }
            auxiliarySizes += auxSize
            require(sample.durationTicks in 1..UINT32_MAX &&
                sample.sampleFlags in 0..UINT32_MAX &&
                sample.compositionOffsetTicks in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "$context: output sample " + index + " timing or flags exceed ISO-BMFF field limits"
            }
            mdatBytes = Math.addExact(mdatBytes, sample.sampleBytes.size.toLong())
        }
        require(mdatBytes in 1..MAX_MDAT_BYTES) {
            "$context: output mdat exceeds the bounded media size"
        }

        val tfhd = fullBox("tfhd", 0, 0x020002,
            uint32(request.trackId) + uint32(1))
        val tfdt = if (request.baseDecodeTimeTicks <= UINT32_MAX) {
            fullBox("tfdt", 0, 0, uint32(request.baseDecodeTimeTicks))
        } else {
            fullBox("tfdt", 1, 0, uint64(request.baseDecodeTimeTicks))
        }
        val senc = buildSenc(request.samples, request.perSampleIvSize)
        val saiz = fullBox("saiz", 0, 0, byteArrayOf(0) +
            uint32(request.samples.size.toLong()) + auxiliarySizes.map { it.toByte() }.toByteArray())
        val mfhd = fullBox("mfhd", 0, 0, uint32(request.sequenceNumber))

        fun trun(dataOffset: Long): ByteArray {
            require(dataOffset in 0..Int.MAX_VALUE.toLong()) {
                "$context: trun data_offset exceeds signed int32"
            }
            val body = ByteArrayOutputStream(8 + request.samples.size * 16)
            body.write(uint32(request.samples.size.toLong()))
            body.write(uint32(dataOffset))
            request.samples.forEach { sample ->
                body.write(uint32(sample.durationTicks))
                body.write(uint32(sample.sampleBytes.size.toLong()))
                body.write(uint32(sample.sampleFlags))
                body.write(uint32(sample.compositionOffsetTicks.toLong() and UINT32_MAX))
            }
            return fullBox("trun", 1, 0x000f01, body.toByteArray())
        }

        val placeholderTrun = trun(0)
        // Offset is measured from moof start to the first IV/subsample record in senc.
        val sencAuxOffset = 8L + mfhd.size.toLong() + 8L + tfhd.size.toLong() +
            tfdt.size.toLong() + placeholderTrun.size.toLong() + 16L
        require(sencAuxOffset <= UINT32_MAX) { "$context: saio offset exceeds version-0 uint32" }
        val saio = fullBox("saio", 0, 0, uint32(1) + uint32(sencAuxOffset))
        val trafPlaceholder = box("traf", tfhd + tfdt + placeholderTrun + senc + saiz + saio)
        val moofPlaceholder = box("moof", mfhd + trafPlaceholder)

        val finalTrun = trun(moofPlaceholder.size.toLong() + 8L)
        require(finalTrun.size == placeholderTrun.size) {
            "$context: trun size changed while calculating media data offset"
        }
        val traf = box("traf", tfhd + tfdt + finalTrun + senc + saiz + saio)
        val moof = box("moof", mfhd + traf)
        require(moof.size == moofPlaceholder.size) {
            "$context: moof size changed while calculating media data offset"
        }

        // Mirror the clear CMAF writer: the output is an exact-sized, owned
        // fragment buffer. Previously the 5-10 MB mdat payload was copied
        // into a temporary array, then copied again by box()/byte-array
        // concatenation. On 50fps four-feed CBCS this repeatedly pressured GC.
        val totalFragmentBytes = Math.addExact(
            Math.addExact(styp.size.toLong(), moof.size.toLong()),
            Math.addExact(mdatBytes, 8L)
        )
        require(totalFragmentBytes <= MAX_FRAGMENT_BYTES) {
            "$context: generated fragment exceeds the output size bound"
        }
        val result = ByteArray(totalFragmentBytes.toInt())
        var cursor = 0
        styp.copyInto(result, destinationOffset = cursor)
        cursor = Math.addExact(cursor, styp.size)
        moof.copyInto(result, destinationOffset = cursor)
        cursor = Math.addExact(cursor, moof.size)
        ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN).apply {
            position(cursor)
            putInt(Math.addExact(mdatBytes, 8L).toInt())
            put('m'.code.toByte())
            put('d'.code.toByte())
            put('a'.code.toByte())
            put('t'.code.toByte())
        }
        cursor = Math.addExact(cursor, 8)
        request.samples.forEach { sample ->
            sample.sampleBytes.copyInto(result, destinationOffset = cursor)
            cursor = Math.addExact(cursor, sample.sampleBytes.size)
        }
        check(cursor == result.size) { "$context: validated mdat samples did not fill the output fragment" }

        val validation = CmafBoxInspector.inspect(
            result, priorInitialization = initReport
        )
        require(validation.errors.isEmpty() && validation.fragments.size == 1) {
            "$context: generated protected fragment failed independent CMAF inspection: " +
                validation.errors.joinToString("; ")
        }
        val fragment = validation.fragments.single()
        require(fragment.trackId == request.trackId &&
            fragment.baseDecodeTimeTicks == request.baseDecodeTimeTicks &&
            fragment.sampleCount == request.samples.size &&
            fragment.sampleEncryptionBoxPresent &&
            fragment.sampleEncryptionStructureComplete &&
            fragment.sampleEncryptionCount == request.samples.size.toLong() &&
            fragment.subsampleEncryptionSignalled == true &&
            fragment.sampleEncryptionRangesCoverSamples) {
            "$context: generated fragment lost protected sample timing or encryption metadata"
        }
        require(fragment.sampleTimings.size == request.samples.size &&
            fragment.sampleEncryptionMetadata.size == request.samples.size) {
            "$context: generated fragment sample tables are incomplete"
        }
        request.samples.forEachIndexed { index, sample ->
            val timing = fragment.sampleTimings[index]
            require(timing.durationTicks == sample.durationTicks &&
                timing.compositionOffsetTicks == sample.compositionOffsetTicks &&
                timing.sampleFlags == sample.sampleFlags &&
                timing.sampleSizeBytes == sample.sampleBytes.size.toLong()) {
                "$context: generated sample " + index + " timing differs from the main-feed source"
            }
            val encryption = fragment.sampleEncryptionMetadata[index]
            require(encryption.ivSize == request.perSampleIvSize &&
                (request.perSampleIvSize == 0 ||
                    encryption.ivIdentity == ProtectedValueFingerprint.fromBytes(
                        "iv", requireNotNull(sample.perSampleIvBytes)
                    )) &&
                encryption.subsampleRanges == sample.subsampleRanges) {
                "$context: generated sample " + index + " CBCS IV/subsample map failed round-trip validation"
            }
        }
        return result
    }

    private fun validateSubsamples(sample: Sample, index: Int, context: String) {
        var covered = 0L
        var encrypted = 0L
        sample.subsampleRanges.forEachIndexed { rangeIndex, range ->
            require(range.clearBytes in 0..0xffff &&
                range.encryptedBytes in 0..UINT32_MAX) {
                "$context: sample " + index + " subsample " + rangeIndex + " exceeds CENC field limits"
            }
            covered = Math.addExact(
                covered,
                Math.addExact(range.clearBytes.toLong(), range.encryptedBytes)
            )
            encrypted = Math.addExact(encrypted, range.encryptedBytes)
        }
        require(covered == sample.sampleBytes.size.toLong() && encrypted > 0L) {
            "$context: sample " + index + " CENC map does not exactly cover protected sample bytes"
        }
    }

    private fun buildSenc(samples: List<Sample>, ivSize: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(uint32(samples.size.toLong()))
        samples.forEach { sample ->
            if (ivSize > 0) body.write(requireNotNull(sample.perSampleIvBytes))
            body.write(uint16(sample.subsampleRanges.size))
            sample.subsampleRanges.forEach { range ->
                body.write(uint16(range.clearBytes))
                body.write(uint32(range.encryptedBytes))
            }
        }
        return fullBox("senc", 0, 2, body.toByteArray())
    }

    private fun fullBox(type: String, version: Int, flags: Int, body: ByteArray) =
        box(type, byteArrayOf(
            version.toByte(),
            (flags ushr 16).toByte(),
            (flags ushr 8).toByte(),
            flags.toByte()
        ) + body)

    private fun box(type: String, body: ByteArray): ByteArray {
        val size = body.size.toLong() + 8L
        require(size <= UINT32_MAX)
        return uint32(size) + type.toByteArray(Charsets.US_ASCII) + body
    }

    private fun parseBoxes(data: ByteArray, start: Int, end: Int): List<Box> {
        val result = mutableListOf<Box>()
        var offset = start
        while (offset < end) {
            require(end - offset >= 8 && result.size < MAX_BOXES) {
                "Truncated or excessive ISO-BMFF box sequence"
            }
            val size = readU32(data, offset)
            val type = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            require(size >= 8 && size <= (end - offset).toLong()) {
                "Invalid ISO-BMFF box bounds"
            }
            result += Box(type, offset, size.toInt())
            offset = Math.addExact(offset, size.toInt())
        }
        require(offset == end)
        return result
    }

    private data class Box(val type: String, val start: Int, val size: Int)

    private fun readU32(data: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset <= data.size - 4)
        return ((data[offset].toLong() and 0xffL) shl 24) or
            ((data[offset + 1].toLong() and 0xffL) shl 16) or
            ((data[offset + 2].toLong() and 0xffL) shl 8) or
            (data[offset + 3].toLong() and 0xffL)
    }

    private fun uint16(value: Int): ByteArray {
        require(value in 0..0xffff)
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()
    }

    private fun uint32(value: Long): ByteArray {
        require(value in 0..UINT32_MAX)
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
    }

    private fun uint64(value: Long): ByteArray {
        require(value >= 0L)
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
    }

    private const val MAX_BOXES = 20_000
}
