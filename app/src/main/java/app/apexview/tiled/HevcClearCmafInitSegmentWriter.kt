package app.apexview.tiled

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a single-track, clear HEVC fragmented-MP4 initialization segment.
 *
 * hvcC is built from the exact VPS/SPS/PPS emitted for the composed picture. The matching
 * media fragments are written by HevcClearCmafFragmentWriter. This class never writes
 * encryption signalling or accepts DRM metadata.
 */
internal object HevcClearCmafInitSegmentWriter {
    data class Request(
        val trackId: Long,
        val mediaTimescale: Long,
        val parameterSets: HevcOutputParameterSetWriter.Output,
        val handlerName: String = "ApexView HEVC",
        val nalLengthSize: Int = 4
    )

    /**
     * Writes a clear source-feed init segment without changing the source VPS/SPS/PPS syntax.
     * This is used only for synthetic clear inputs and parsed clear CMAF sources. Protected
     * sample entries and encryption signalling are never accepted or generated here.
     */
    fun writeSource(
        trackId: Long,
        mediaTimescale: Long,
        vpsNal: ByteArray,
        spsNal: ByteArray,
        ppsNal: ByteArray,
        handlerName: String = "ApexView source HEVC"
    ): ByteArray {
        require(trackId in 1 until UINT32_MAX) { "Track ID must leave room for next_track_ID" }
        require(mediaTimescale in 1..UINT32_MAX) { "Media timescale is outside uint32 range" }
        require(handlerName.isNotBlank() && handlerName.all { it.code in 0x20..0x7e } &&
            handlerName.toByteArray(Charsets.US_ASCII).size <= 31) {
            "Handler name must be printable ASCII and fit the sample-entry field"
        }
        val vps = HevcParameterSets.parseVps(vpsNal)
        val sps = HevcParameterSets.parseSps(spsNal)
        val pps = HevcParameterSets.parsePps(ppsNal, sps)
        require(sps.vpsId == vps.vpsId && pps.spsId == sps.spsId) {
            "Source VPS/SPS/PPS references are inconsistent"
        }
        require(vps.maxLayers == 1 && !vps.extensionPresent &&
            !sps.spsExtensionPresent && !pps.extensionPresent) {
            "Source parameter-set extensions are not supported"
        }
        require(sps.displayWidth in 1..0xffff && sps.displayHeight in 1..0xffff) {
            "Source display dimensions exceed the visual sample-entry range"
        }
        val hvcC = writeSourceHevcConfiguration(listOf(
            32 to vpsNal, 33 to spsNal, 34 to ppsNal
        ), sps)
        val ftyp = box(
            "ftyp",
            ascii("iso6") + uint32(1) + ascii("iso6") + ascii("cmfc") + ascii("hvc1")
        )
        val moov = box(
            "moov",
            movieHeader(MOVIE_TIMESCALE, trackId + 1) +
                trackBox(trackId, mediaTimescale, sps.displayWidth, sps.displayHeight, hvcC, handlerName) +
                box("mvex", trackExtends(trackId))
        )
        return ftyp + moov
    }

    fun write(request: Request): ByteArray {
        require(request.trackId in 1 until UINT32_MAX) {
            "Track ID must leave room for next_track_ID"
        }
        require(request.mediaTimescale in 1..UINT32_MAX) {
            "Media timescale is outside uint32 range"
        }
        require(request.handlerName.isNotBlank() && request.handlerName.all { it.code in 0x20..0x7e }) {
            "Handler name must contain printable ASCII characters"
        }
        require(request.handlerName.toByteArray(Charsets.US_ASCII).size <= 31) {
            "Handler name exceeds the visual sample-entry compressor-name field"
        }

        val output = request.parameterSets
        val vps = HevcParameterSets.parseVps(output.vps)
        val sps = HevcParameterSets.parseSps(output.sps)
        val pps = HevcParameterSets.parsePps(output.pps, sps)
        val vpsPtl = vps.profileTierLevel
        val spsPtl = sps.profileTierLevel

        require(sps.vpsId == vps.vpsId) { "Output SPS does not reference the output VPS" }
        require(pps.spsId == sps.spsId) { "Output PPS does not reference the output SPS" }
        require(sps.width == output.codedWidth && sps.height == output.codedHeight) {
            "Output parameter-set dimensions do not match the coded dimensions"
        }
        require(sps.displayWidth in 1..0xffff && sps.displayHeight in 1..0xffff) {
            "HEVC display dimensions exceed the visual sample-entry range"
        }
        require(vps.maxLayers == 1 && !vps.extensionPresent &&
            !sps.spsExtensionPresent && !pps.extensionPresent) {
            "Multilayer or extended HEVC parameter sets are not supported"
        }
        require(pps.tilesEnabled && !pps.entropyCodingSyncEnabled) {
            "Output PPS must declare tiles and must not use WPP"
        }
        require(pps.tileColumnWidthsInCtbs == output.tileColumnWidthsInCtbs &&
            pps.tileRowHeightsInCtbs == output.tileRowHeightsInCtbs) {
            "Output PPS tile geometry does not match the parameter-set model"
        }
        require(
            vpsPtl.profileSpace == spsPtl.profileSpace &&
            vpsPtl.tierFlag == spsPtl.tierFlag &&
            vpsPtl.profileIdc == spsPtl.profileIdc &&
            vpsPtl.compatibilityFlags == spsPtl.compatibilityFlags &&
            vpsPtl.constraintIndicatorFlags == spsPtl.constraintIndicatorFlags &&
            vpsPtl.levelIdc == output.levelIdc &&
            spsPtl.levelIdc == output.levelIdc
        ) {
            "Output VPS/SPS profile-tier-level signalling is inconsistent"
        }
        require(sps.maxSubLayers in 1..7) { "HEVC temporal layer count is outside hvcC range" }
        require(sps.chromaFormatIdc in 0..3) { "HEVC chroma format is outside hvcC range" }
        require(sps.bitDepthLuma in 8..15 && sps.bitDepthChroma in 8..15) {
            "HEVC bit depth is outside hvcC range"
        }

        val hvcC = writeHevcConfiguration(output, sps, request.nalLengthSize)
        val ftyp = box(
            "ftyp",
            ascii("iso6") + uint32(1) + ascii("iso6") + ascii("cmfc") + ascii("hvc1")
        )
        val moov = box(
            "moov",
            movieHeader(MOVIE_TIMESCALE, request.trackId + 1) +
                trackBox(
                    request.trackId,
                    request.mediaTimescale,
                    sps.displayWidth,
                    sps.displayHeight,
                    hvcC,
                    request.handlerName
                ) +
                box("mvex", trackExtends(request.trackId))
        )
        return ftyp + moov
    }

    internal fun writeHevcConfiguration(
        output: HevcOutputParameterSetWriter.Output,
        sps: HevcParameterSets.SpsMetadata,
        nalLengthSize: Int = 4
    ): ByteArray = writeSourceHevcConfiguration(
        listOf(32 to output.vps, 33 to output.sps, 34 to output.pps),
        sps,
        output.levelIdc,
        nalLengthSize
    )

    private fun writeSourceHevcConfiguration(
        parameterSets: List<Pair<Int, ByteArray>>,
        sps: HevcParameterSets.SpsMetadata,
        levelIdc: Int = sps.profileTierLevel.levelIdc,
        nalLengthSize: Int = 4
    ): ByteArray {
        require(nalLengthSize in 1..4)
        val ptl = sps.profileTierLevel
        parameterSets.forEach { (type, nal) ->
            require(HevcBitstream.parseNalHeader(nal).type == type) {
                "hvcC parameter-set array contains the wrong NAL type"
            }
            require(nal.size in 2..0xffff) {
                "hvcC parameter-set NAL length is outside uint16 range"
            }
        }

        val profileByte = (ptl.profileSpace shl 6) or
            (if (ptl.tierFlag) 0x20 else 0) or ptl.profileIdc
        val packedTemporal = (sps.maxSubLayers shl 3) or
            (if (sps.temporalIdNesting) 0x04 else 0) or (nalLengthSize - 1)

        val record = ByteArrayOutputStream()
        record.write(uint8(1))
        record.write(uint8(profileByte))
        record.write(uint32(ptl.compatibilityFlags))
        record.write(uint48(ptl.constraintIndicatorFlags))
        record.write(uint8(levelIdc))
        record.write(uint16(0xf000)) // reserved bits and min_spatial_segmentation_idc
        record.write(uint8(0xfe)) // reserved bits and parallelismType=2 (tile-based)
        record.write(uint8(0xfc or sps.chromaFormatIdc))
        record.write(uint8(0xf8 or (sps.bitDepthLuma - 8)))
        record.write(uint8(0xf8 or (sps.bitDepthChroma - 8)))
        record.write(uint16(0)) // avgFrameRate unspecified; sample durations are authoritative.
        record.write(uint8(packedTemporal))
        record.write(uint8(parameterSets.size))
        parameterSets.forEach { (type, nal) ->
            record.write(uint8(0x80 or type)) // array_completeness=1, reserved=0, NAL type
            record.write(uint16(1))
            record.write(uint16(nal.size))
            record.write(nal)
        }
        return box("hvcC", record.toByteArray())
    }

    private fun trackBox(
        trackId: Long,
        timescale: Long,
        width: Int,
        height: Int,
        hvcC: ByteArray,
        handlerName: String
    ): ByteArray {
        val sampleEntry = visualSampleEntry(width, height, hvcC, handlerName)
        val sampleTable = box(
            "stbl",
            fullBox("stsd", 0, 0, uint32(1) + sampleEntry) +
                fullBox("stts", 0, 0, uint32(0)) +
                fullBox("stsc", 0, 0, uint32(0)) +
                fullBox("stsz", 0, 0, uint32(0) + uint32(0)) +
                fullBox("stco", 0, 0, uint32(0))
        )
        val dataInformation = box(
            "dinf",
            fullBox("dref", 0, 0, uint32(1) + fullBox("url ", 0, 1, ByteArray(0)))
        )
        val mediaInformation = box(
            "minf",
            fullBox("vmhd", 0, 1, uint16(0) + uint16(0) + uint16(0) + uint16(0)) +
                dataInformation + sampleTable
        )
        val media = box(
            "mdia",
            mediaHeader(timescale) +
                handler("vide", handlerName) +
                mediaInformation
        )
        return box("trak", trackHeader(trackId, width, height) + media)
    }

    private fun visualSampleEntry(width: Int, height: Int, hvcC: ByteArray, name: String): ByteArray {
        val label = name.toByteArray(Charsets.US_ASCII)
        val compressorName = uint8(label.size) + label + ByteArray(31 - label.size)
        val fields = ByteArray(6) + uint16(1) +
            uint16(0) + uint16(0) + uint32(0) + uint32(0) + uint32(0) +
            uint16(width) + uint16(height) +
            uint32(0x00480000) + uint32(0x00480000) + uint32(0) +
            uint16(1) + compressorName + uint16(0x0018) + uint16(0xffff)
        require(fields.size == 78) { "Visual sample-entry header must be exactly 78 bytes" }
        return box("hvc1", fields + hvcC)
    }

    private fun movieHeader(timescale: Long, nextTrackId: Long): ByteArray {
        val payload = uint32(0) + uint32(0) + uint32(timescale) + uint32(0) +
            uint32(0x00010000) + uint16(0x0100) + uint16(0) + ByteArray(8) +
            transformationMatrix() + ByteArray(24) + uint32(nextTrackId)
        return fullBox("mvhd", 0, 0, payload)
    }

    private fun trackHeader(trackId: Long, width: Int, height: Int): ByteArray {
        val payload = uint32(0) + uint32(0) + uint32(trackId) + uint32(0) + uint32(0) +
            ByteArray(8) + uint16(0) + uint16(0) + uint16(0) + uint16(0) +
            transformationMatrix() + uint32(width.toLong() shl 16) + uint32(height.toLong() shl 16)
        return fullBox("tkhd", 0, 0x000007, payload)
    }

    private fun mediaHeader(timescale: Long): ByteArray = fullBox(
        "mdhd", 0, 0,
        uint32(0) + uint32(0) + uint32(timescale) + uint32(0) +
            uint16(0x55c4) + uint16(0)
    )

    private fun handler(handlerType: String, name: String): ByteArray {
        require(handlerType == "vide") { "Only video tracks are supported" }
        return fullBox(
            "hdlr", 0, 0,
            uint32(0) + ascii(handlerType) + ByteArray(12) +
                name.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        )
    }

    private fun trackExtends(trackId: Long): ByteArray = fullBox(
        "trex", 0, 0,
        uint32(trackId) + uint32(1) + uint32(0) + uint32(0) + uint32(0)
    )

    private fun transformationMatrix(): ByteArray =
        uint32(0x00010000) + uint32(0) + uint32(0) +
            uint32(0) + uint32(0x00010000) + uint32(0) +
            uint32(0) + uint32(0) + uint32(0x40000000)

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray {
        require(version in 0..0xff && flags in 0..0x00ffffff)
        val header = byteArrayOf(
            version.toByte(),
            (flags ushr 16).toByte(),
            (flags ushr 8).toByte(),
            flags.toByte()
        )
        return box(type, header + payload)
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        require(type.length == 4) { "ISO-BMFF box type must contain four characters" }
        val size = payload.size.toLong() + 8L
        require(size <= UINT32_MAX) { "ISO-BMFF box exceeds 32-bit size" }
        return uint32(size) + ascii(type) + payload
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun uint8(value: Int): ByteArray {
        require(value in 0..0xff) { "Value is outside uint8 range" }
        return byteArrayOf(value.toByte())
    }

    private fun uint16(value: Int): ByteArray {
        require(value in 0..0xffff) { "Value is outside uint16 range" }
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()
    }

    private fun uint32(value: Long): ByteArray {
        require(value in 0..UINT32_MAX) { "Value is outside uint32 range" }
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
    }

    private fun uint48(value: Long): ByteArray {
        require(value in 0..MAX_UINT48) { "Value is outside uint48 range" }
        return ByteArray(6) { index -> (value ushr ((5 - index) * 8)).toByte() }
    }

    private const val MOVIE_TIMESCALE = 1000L
    private const val UINT32_MAX = 0xffff_ffffL
    private const val MAX_UINT48 = 0xffff_ffff_ffffL
}
