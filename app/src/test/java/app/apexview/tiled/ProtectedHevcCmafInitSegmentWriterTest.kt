package app.apexview.tiled

import java.io.ByteArrayInputStream
import java.io.File
import android.net.Uri
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression for Build 627's false CBCS protection metadata mutation alarm. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProtectedHevcCmafInitSegmentWriterTest {
    @Test fun equalProtectionBoxesFromIndependentParsesAreAccepted() {
        val original = drmBoxes()
        ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(
            original, original.copyOf(), "bytewise preservation")
    }

    @Test fun alteredAndMissingTencPsshBoxesRemainRejected() {
        val original = drmBoxes()
        val modified = original.copyOf()
        val tencOffset = findType(modified, "tenc")
        modified[tencOffset + 11] = (modified[tencOffset + 11].toInt() xor 1).toByte()
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(
                original, modified, "mutated CBCS defaults")
        }.message!!.contains("sinf box 0 contents changed"))
        val modifiedPssh = original.copyOf()
        modifiedPssh[modifiedPssh.lastIndex] = 1
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(
                original, modifiedPssh, "mutated PSSH")
        }.message!!.contains("pssh box 0 contents changed"))
        val noPssh = original.copyOfRange(0, original.size - box("pssh", ByteArray(24)).size)
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(
                original, noPssh, "missing PSSH")
        }.message!!.contains("pssh box count changed"))
    }

    @Test fun protectedInitRewritePreservesCbcsHvc1AndHev1ForExpandedGrid() {
        val path = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("CI must provision generated Kvazaar tile fixtures", !path.isNullOrBlank())
        val file = File(path!!, "source_tiled_2x2.hevc")
        assumeTrue("Generated HEVC 2x2 fixture missing", file.isFile)
        val nals = HevcBitstream.splitAnnexB(file.readBytes())
        val vpsNal = nals.first { it.type == 32 }.bytes
        val spsNal = nals.first { it.type == 33 }.bytes
        val ppsNal = nals.first { it.type == 34 }.bytes
        val vps = HevcParameterSets.parseVps(vpsNal)
        val sps = HevcParameterSets.parseSps(spsNal)
        val pps = HevcParameterSets.parsePps(ppsNal, sps)
        val cols = requireNotNull(pps.tileColumnWidthsInCtbs)
        val rows = requireNotNull(pps.tileRowHeightsInCtbs)
        val filter = requireNotNull(pps.loopFilterAcrossTiles)
        fun output(width: Int, height: Int, columns: List<Int>, heights: List<Int>) =
            HevcOutputParameterSetWriter.generate(
                vpsNal, spsNal, ppsNal,
                HevcOutputParameterSetWriter.Request(
                    vps.vpsId, sps.spsId, pps.ppsId,
                    width, height, columns, heights, filter, 25,
                    uncroppedOutputPicture = true
                )
            )
        val sourceOutput = output(sps.picWidthInCtbs * sps.ctbSize,
            sps.picHeightInCtbs * sps.ctbSize, cols, rows)
        val atlas = output(sps.picWidthInCtbs * sps.ctbSize * 2,
            sps.picHeightInCtbs * sps.ctbSize * 2, cols + cols, rows + rows)
        val clear = HevcClearCmafInitSegmentWriter.write(
            HevcClearCmafInitSegmentWriter.Request(7L, 90_000L, sourceOutput))
        for (originalFormat in listOf("hvc1", "hev1")) {
            val protectedInit = protectInit(clear, originalFormat)
            val before = CmafBoxInspector.inspect(protectedInit)
            assertTrue(before.errors.toString(), before.errors.isEmpty())
            assertTrue(before.tracks.single().encrypted)
            val result = ProtectedHevcCmafInitSegmentWriter.rewrite(
                protectedInit, atlas, "generated protected 4-feed atlas")
            val after = CmafBoxInspector.inspect(result)
            assertTrue(after.errors.toString(), after.errors.isEmpty())
            val sourceTrack = before.tracks.single()
            val outputTrack = after.tracks.single()
            assertEquals("encv", outputTrack.sampleEntry)
            assertEquals(originalFormat, outputTrack.originalFormat)
            assertEquals("cbcs", outputTrack.protectionScheme)
            assertEquals(sourceTrack.trackEncryption, outputTrack.trackEncryption)
            assertEquals(sourceTrack.trackId, outputTrack.trackId)
            assertEquals(sourceTrack.timescale, outputTrack.timescale)
            assertEquals(HevcParameterSets.parseSps(atlas.sps).displayWidth, outputTrack.width)
            assertEquals(HevcParameterSets.parseSps(atlas.sps).displayHeight, outputTrack.height)
            ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(
                protectedInit, result, "independent CBCS output proof")
        }
    }


    @Test fun protectedDashDefaultKidMatchesValidatedTencVersionZeroAndOne() {
        val directory = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("CI must create HEVC synthetic tile fixtures", !directory.isNullOrBlank())
        val file = File(directory!!, "source_tiled_2x2.hevc")
        assumeTrue("HEVC fixture missing", file.isFile)
        val nals = HevcBitstream.splitAnnexB(file.readBytes())
        val vps = nals.first { it.type == 32 }.bytes
        val sps = nals.first { it.type == 33 }.bytes
        val pps = nals.first { it.type == 34 }.bytes
        val v = HevcParameterSets.parseVps(vps)
        val s = HevcParameterSets.parseSps(sps)
        val p = HevcParameterSets.parsePps(pps, s)
        val params = HevcOutputParameterSetWriter.generate(
            vps, sps, pps, HevcOutputParameterSetWriter.Request(
                v.vpsId, s.spsId, p.ppsId, s.width, s.height,
                requireNotNull(p.tileColumnWidthsInCtbs),
                requireNotNull(p.tileRowHeightsInCtbs),
                requireNotNull(p.loopFilterAcrossTiles), 25
            ))
        val clear = HevcClearCmafInitSegmentWriter.write(
            HevcClearCmafInitSegmentWriter.Request(7L, 90_000L, params))
        for (version in 0..1) {
            val init = ProtectedHevcCmafInitSegmentWriter.rewrite(
                protectInit(clear, "hev1", version), params)
            val track = CmafBoxInspector.inspect(init).tracks.single()
            assertEquals(version, track.trackEncryption?.version)
            assertEquals("23232323-2323-2323-2323-232323232323",
                ProtectedHevcComposedDashPackageBuilder.readDefaultKid(init))
        }
    }

    @Test fun protectedCmafFragmentDashPackageAndMedia3ParserAgreeOnCbcsAndPssh() {
        val directory = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("CI must create HEVC synthetic tile fixtures", !directory.isNullOrBlank())
        val file = File(directory!!, "source_tiled_2x2.hevc")
        assumeTrue("HEVC fixture missing", file.isFile)
        val nals = HevcBitstream.splitAnnexB(file.readBytes())
        val vps = nals.first { it.type == 32 }.bytes
        val sps = nals.first { it.type == 33 }.bytes
        val pps = nals.first { it.type == 34 }.bytes
        val v = HevcParameterSets.parseVps(vps)
        val s = HevcParameterSets.parseSps(sps)
        val p = HevcParameterSets.parsePps(pps, s)
        val params = HevcOutputParameterSetWriter.generate(
            vps, sps, pps, HevcOutputParameterSetWriter.Request(
                v.vpsId, s.spsId, p.ppsId, s.width, s.height,
                requireNotNull(p.tileColumnWidthsInCtbs),
                requireNotNull(p.tileRowHeightsInCtbs),
                requireNotNull(p.loopFilterAcrossTiles), 25
            ))
        val clear = HevcClearCmafInitSegmentWriter.write(
            HevcClearCmafInitSegmentWriter.Request(7L, 90_000L, params))
        val init = ProtectedHevcCmafInitSegmentWriter.rewrite(
            protectInit(clear, "hev1"), params)
        val nal = nals.first { it.type in 16..21 }.bytes
        require(nal.size > 16)
        val sample = u32(nal.size) + nal
        val iv = ByteArray(16) { 0x19.toByte() }
        val media = ProtectedHevcCmafFragmentWriter.write(
            outputInitializationSegment = init,
            request = ProtectedHevcCmafFragmentWriter.Request(
                trackId = 7L, baseDecodeTimeTicks = 900_000L,
                sequenceNumber = 1L, perSampleIvSize = 16,
                samples = listOf(ProtectedHevcCmafFragmentWriter.Sample(
                    sampleBytes = sample,
                    subsampleRanges = listOf(CmafBoxInspector.SubsampleEncryptionRange(
                        sample.size - 16, 16L)),
                    perSampleIvBytes = iv,
                    perSampleIvIdentity = ProtectedValueFingerprint.fromBytes("iv", iv),
                    durationTicks = 3_600L, sampleFlags = 0x02000000L,
                    compositionOffsetTicks = 0L
                ))
            ))
        val outputSps = HevcParameterSets.parseSps(params.sps)
        val window = ProtectedHevcLiveComposition.OutputWindow(
            initializationSegment = init,
            fragments = listOf(ProtectedHevcLiveComposition.OutputFragment(
                sequenceNumber = 1L, mediaFragment = media, timescale = 90_000L,
                baseDecodeTimeTicks = 900_000L, durationTicks = 3_600L,
                sampleCount = 1, width = outputSps.displayWidth,
                height = outputSps.displayHeight,
                alignmentOffsetsMillis = emptyMap()
            ))
        )
        val uuid = "edef8ba979d64acea3c827dcd51d21ed"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pssh = box("pssh", byteArrayOf(0, 0, 0, 0) + uuid + u32(0))
        val pkg = ProtectedHevcComposedDashPackageBuilder.build(
            window, pssh, 900_000L, 1_795_233_600_000L
        )
        try {
            // Preflight must validate identical CBCS/MPD metadata without
            // cloning or clearing any of the real protected output fragments.
            val checked = ProtectedHevcComposedDashPackageBuilder.build(
                window, pssh, 900_000L, 1_795_233_600_000L,
                validationOnly = true
            )
            assertEquals(pkg.manifest, checked.manifest)
            assertTrue(checked.resources.isEmpty())
            checked.zero() // Must not mutate the preflight caller-owned bytes.
            assertTrue(window.initializationSegment.contentEquals(init))
            assertTrue(window.fragments.single().mediaFragment.contentEquals(media))
            assertThrows(IllegalArgumentException::class.java) {
                ProtectedHevcComposedDashPackageBuilder.build(
                    window, pssh, 900_000L, 1_795_233_600_000L,
                    validationOnly = true, takeOwnership = true
                )
            }
            assertTrue(pkg.manifest.contains(
                "cenc:default_KID=\"23232323-2323-2323-2323-232323232323\""))
            assertTrue(pkg.manifest.contains("value=\"cbcs\""))
            assertTrue(pkg.manifest.contains("urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"))
            assertEquals(1, pkg.fragmentCount)
            assertEquals(1, pkg.sampleCount)
            assertTrue(pkg.resources.getValue("init.mp4").contentEquals(init))
            assertTrue(pkg.resources.getValue("segment-1.m4s").contentEquals(media))
            val parsed = DashManifestParser().parse(
                Uri.parse("https://pitwall.example/manifest.mpd"),
                ByteArrayInputStream(pkg.manifest.toByteArray(Charsets.UTF_8))
            )
            val format = parsed.getPeriod(0).adaptationSets
                .single().representations.single().format
            assertEquals("video/hevc", format.sampleMimeType)
            assertEquals("cbcs", format.drmInitData?.schemeType)
            assertTrue((format.drmInitData?.schemeDataCount ?: 0) > 0)
        } finally {
            pkg.zero()
            window.zero()
        }
    }


    @Test fun protectedFragmentCopiesThreeSamplesOnlyOnceIntoExactMdatOrder() {
        val directory = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("CI must generate real HEVC fixtures", !directory.isNullOrBlank())
        val source = File(directory!!, "source_tiled_2x2.hevc")
        assumeTrue("Generated source fixture missing", source.isFile)
        val nals = HevcBitstream.splitAnnexB(source.readBytes())
        val vps = nals.single { it.type == 32 }.bytes
        val sps = nals.single { it.type == 33 }.bytes
        val pps = nals.single { it.type == 34 }.bytes
        val v = HevcParameterSets.parseVps(vps)
        val s = HevcParameterSets.parseSps(sps)
        val p = HevcParameterSets.parsePps(pps, s)
        val params = HevcOutputParameterSetWriter.generate(vps, sps, pps,
            HevcOutputParameterSetWriter.Request(
                v.vpsId, s.spsId, p.ppsId, s.width, s.height,
                requireNotNull(p.tileColumnWidthsInCtbs),
                requireNotNull(p.tileRowHeightsInCtbs),
                requireNotNull(p.loopFilterAcrossTiles), 25
            )
        )
        val init = ProtectedHevcCmafInitSegmentWriter.rewrite(
            protectInit(HevcClearCmafInitSegmentWriter.write(
                HevcClearCmafInitSegmentWriter.Request(7L, 90_000L, params)), "hev1"), params)
        val nal = nals.first { it.type in 16..21 }.bytes
        require(nal.size > 16)
        val sampleBytes = u32(nal.size) + nal
        val samples = (1..3).map { id ->
            val iv = ByteArray(16) { id.toByte() }
            ProtectedHevcCmafFragmentWriter.Sample(
                sampleBytes = sampleBytes.copyOf(),
                subsampleRanges = listOf(
                    CmafBoxInspector.SubsampleEncryptionRange(sampleBytes.size - 16, 16L)),
                perSampleIvBytes = iv,
                perSampleIvIdentity = ProtectedValueFingerprint.fromBytes("iv", iv),
                durationTicks = 3_600L, sampleFlags = 0x02000000L,
                compositionOffsetTicks = 0L,
            )
        }
        val styp = box("styp", ascii("msdh") + u32(0))
        val fragment = ProtectedHevcCmafFragmentWriter.write(
            init, ProtectedHevcCmafFragmentWriter.Request(
                trackId = 7L, baseDecodeTimeTicks = 900_000L,
                sequenceNumber = 1L, perSampleIvSize = 16,
                samples = samples, stypBox = styp,
            )
        )
        val expectedPayload = samples.fold(ByteArray(0)) { bytes, sample ->
            bytes + sample.sampleBytes
        }
        assertArrayEquals(styp, fragment.copyOfRange(0, styp.size))
        assertArrayEquals(expectedPayload, fragment.copyOfRange(
            fragment.size - expectedPayload.size, fragment.size
        ))
        val inspected = CmafBoxInspector.inspect(fragment, CmafBoxInspector.inspect(init))
        assertTrue(inspected.errors.toString(), inspected.errors.isEmpty())
        val media = inspected.fragments.single()
        assertEquals(3, media.sampleCount)
        assertEquals(3, media.sampleEncryptionMetadata.size)
        assertEquals(samples.map { it.subsampleRanges },
            media.sampleEncryptionMetadata.map { it.subsampleRanges })
    }

    @Test fun pitwallCastEncryptedAudioCencRetainsItsOwnDefaultKid() {
        // Pitwall Cast audio reuses the same MPD protection helper. A strict video-only
        // requirement would break the encrypted soun/enca representation.
        val tenc = box("tenc", byteArrayOf(0, 0, 0, 0, 0, 1, 16) +
            ByteArray(16) { 35.toByte() })
        val sinf = box("sinf",
            box("frma", ascii("mp4a")) +
            box("schm", byteArrayOf(0, 0, 0, 0) + ascii("cenc") + u32(0x00010000)) +
            box("schi", tenc))
        val audioEntry = box("enca", ByteArray(28) + sinf)
        val stsd = box("stsd", byteArrayOf(0, 0, 0, 0) + u32(1) + audioEntry)
        val tkhd = box("tkhd",
            byteArrayOf(0, 0, 0, 7) + u32(0) + u32(0) + u32(7) + u32(0) + ByteArray(60))
        val mdhd = box("mdhd",
            byteArrayOf(0, 0, 0, 0) + u32(0) + u32(0) +
            u32(90_000) + u32(0) + ByteArray(4))
        val hdlr = box("hdlr", byteArrayOf(0, 0, 0, 0) + u32(0) +
            ascii("soun") + ByteArray(12))
        val trak = box("trak", tkhd + box("mdia", mdhd + hdlr +
            box("minf", box("stbl", stsd))))
        val trex = box("trex", byteArrayOf(0, 0, 0, 0) +
            u32(7) + u32(1) + u32(0) + u32(0) + u32(0))
        val init = box("ftyp", ascii("iso6") + u32(0) + ascii("iso6cmfc")) +
            box("moov", trak + box("mvex", trex))
        val inspected = CmafBoxInspector.inspect(init)
        assertTrue(inspected.errors.toString(), inspected.errors.isEmpty())
        assertTrue(inspected.initializationSegmentPresent)
        assertEquals("soun", inspected.tracks.single().handler)
        assertEquals("cenc", inspected.tracks.single().protectionScheme)
        val uuid = "edef8ba979d64acea3c827dcd51d21ed"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pssh = box("pssh", byteArrayOf(0, 0, 0, 0) + uuid + u32(0))
        val xml = ProtectedHevcComposedDashPackageBuilder.protectionXml(
            init, pssh, "cenc"
        )
        assertTrue(xml.contains("value=\"cenc\""))
        assertTrue(xml.contains(
            "cenc:default_KID=\"23232323-2323-2323-2323-232323232323\""))
        assertTrue(xml.contains("urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"))
    }


    @Test fun protectedRollingSceneMapsFourCameraCropsIntoWidescreenWindows() {
        val directory = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Generated HEVC fixture is required", !directory.isNullOrBlank())
        val file = File(directory!!, "source_tiled_2x2.hevc")
        assumeTrue("Generated HEVC fixture is missing", file.isFile)
        val nals = HevcBitstream.splitAnnexB(file.readBytes())
        val vps = nals.first { it.type == 32 }.bytes
        val sps = nals.first { it.type == 33 }.bytes
        val pps = nals.first { it.type == 34 }.bytes
        val v = HevcParameterSets.parseVps(vps)
        val s = HevcParameterSets.parseSps(sps)
        val p = HevcParameterSets.parsePps(pps, s)
        val config = HevcOutputParameterSetWriter.generate(
            vps, sps, pps, HevcOutputParameterSetWriter.Request(
                v.vpsId, s.spsId, p.ppsId, s.width, s.height,
                requireNotNull(p.tileColumnWidthsInCtbs),
                requireNotNull(p.tileRowHeightsInCtbs),
                requireNotNull(p.loopFilterAcrossTiles), 25
            ))
        val clear = HevcClearCmafInitSegmentWriter.write(
            HevcClearCmafInitSegmentWriter.Request(7L, 90_000L, config))
        val protected = ProtectedHevcCmafInitSegmentWriter.rewrite(
            protectInit(clear, "hev1"), config)
        val track = CmafBoxInspector.inspect(protected).tracks.single()
        val atlasWidth = requireNotNull(track.width)
        val atlasHeight = requireNotNull(track.height)
        val w = atlasWidth / 2
        val h = atlasHeight / 2
        val rects = listOf(
            HevcMixedArchiveAtlasPlan.SourceRect(0, 0, w, h),
            HevcMixedArchiveAtlasPlan.SourceRect(w, 0, w, h),
            HevcMixedArchiveAtlasPlan.SourceRect(0, h, w, h),
            HevcMixedArchiveAtlasPlan.SourceRect(w, h, w, h)
        )
        val initial = ProtectedHevcLiveComposition.OutputWindow(
            initializationSegment = protected,
            fragments = listOf(ProtectedHevcLiveComposition.OutputFragment(
                sequenceNumber = 1L, mediaFragment = byteArrayOf(),
                timescale = 90_000L, baseDecodeTimeTicks = 0L,
                durationTicks = 518_400L, sampleCount = 288,
                width = atlasWidth, height = atlasHeight,
                alignmentOffsetsMillis = emptyMap()
            )),
            sourceGeometry = ProtectedHevcLiveComposition.SourceGeometry(
                w, h, w, h, 2, 2, rects
            )
        )
        val mixed = ProtectedHevcSceneGeometry.fromWindow(initial, 4)
        assertEquals(50.0, mixed.frameRate, 0.001)
        assertEquals(rects, mixed.geometry.mixedSourceRects)
        val windows = listOf(
            app.apexview.scene.VideoWindow(1, 0, 0f, 0f, 1240f, 1080f, 1f),
            app.apexview.scene.VideoWindow(2, 1, 1240f, 0f, 300f, 360f, 2f),
            app.apexview.scene.VideoWindow(3, 2, 1240f, 360f, 300f, 360f, 3f),
            app.apexview.scene.VideoWindow(4, 3, 1240f, 720f, 300f, 360f, 4f)
        )
        val scene = app.apexview.scene.VideoScene(
            1920f, 1080f, mixed.atlasWidth, mixed.atlasHeight,
            4, windows, mixed.geometry.atlasColumns, w, h, w, h,
            rects.map { app.apexview.scene.VideoSourceRect(it.x, it.y, it.width, it.height) },
            mixed.frameRate
        )
        val quads = scene.quads()
        assertEquals(4, quads.size)
        assertEquals(windows.map { it.id }, quads.map { it.id })
        assertTrue(quads.map { it.texture.toList() }.distinct().size == 4)
        // With equal-quality source tiles, the placement is instead derived
        // from the verified output PPS and remains one-window-per-source.
        val equal = ProtectedHevcSceneGeometry.fromWindow(initial.copy(sourceGeometry = null), 4)
        assertEquals(4, equal.geometry.mixedSourceRects.size)
        assertEquals(2, equal.geometry.atlasColumns)
        assertEquals(50.0, equal.frameRate, 0.001)
        initial.zero()
    }

    @Test fun protectedSceneRejectsUnknownOrInvalidAtlasMapping() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcSceneGeometry.fromWindow(
                ProtectedHevcLiveComposition.OutputWindow(
                    ByteArray(0), emptyList()), 4)
        }
    }

    private fun drmBoxes(): ByteArray =
        box("moov", box("sinf", box("schi", box("tenc",
            byteArrayOf(1, 0, 0, 0, 0, 0x19, 1, 16) + ByteArray(16) { 17.toByte() })))) +
            box("pssh", ByteArray(24))

    private fun protectInit(clear: ByteArray, originalFormat: String, tencVersion: Int = 1): ByteArray {
        require(tencVersion in 0..1)
        val stsdType = findType(clear, "stsd")
        val entryStart = stsdType + 12
        val entrySize = readU32(clear, entryStart)
        val end = entryStart + entrySize
        require(end <= clear.size)
        val tenc = box("tenc", byteArrayOf(tencVersion.toByte(), 0, 0, 0, 0) +
            (if (tencVersion == 1) byteArrayOf(0x19) else byteArrayOf()) +
            byteArrayOf(1, 16) + ByteArray(16) { 35.toByte() })
        val sinf = box("sinf",
            box("frma", ascii(originalFormat)) +
                box("schm", byteArrayOf(0, 0, 0, 0) + ascii("cbcs") + u32(0x00010000)) +
                box("schi", tenc))
        val altered = clear.copyOfRange(0, end) + sinf + clear.copyOfRange(end, clear.size)
        ascii("encv").copyInto(altered, entryStart + 4)
        writeU32(altered, entryStart, entrySize + sinf.size)
        listOf("moov", "trak", "mdia", "minf", "stbl", "stsd").forEach { name ->
            val boxStart = findType(clear, name) - 4
            writeU32(altered, boxStart, readU32(clear, boxStart) + sinf.size)
        }
        // Synthetic DRM metadata only: no credentials, keys or protected media.
        return altered + box("pssh", byteArrayOf(0, 0, 0, 0) + ByteArray(16) + u32(0))
    }

    private fun findType(data: ByteArray, name: String): Int {
        val needle = ascii(name)
        for (i in 4..(data.size - needle.size)) {
            if (needle.indices.all { data[i + it] == needle[it] }) return i
        }
        throw IllegalArgumentException("Missing synthetic box type " + name)
    }
    private fun box(type: String, body: ByteArray) = u32(body.size + 8) + ascii(type) + body
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun u32(i: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(i).array()
    private fun readU32(bytes: ByteArray, offset: Int) =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value)
    }
}
