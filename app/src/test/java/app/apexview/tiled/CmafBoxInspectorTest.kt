package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CmafBoxInspectorTest {
    @Test fun readsBrandsTrackTimescaleAndVideoDimensions() {
        val data = box("ftyp", ascii("iso6") + int(0) + ascii("iso6cmfc"))
            .plus(box("moov", box("trak",
                box("mdia",
                    box("mdhd", byteArrayOf(0,0,0,0) + int(0) + int(0) + int(90000) + int(0) + short(0) + short(0)),
                    box("hdlr", byteArrayOf(0,0,0,0) + int(0) + ascii("vide") + ByteArray(12)),
                    box("minf", box("stbl", box("stsd",
                        byteArrayOf(0,0,0,0) + int(1) +
                            int(86) + ascii("hvc1") + ByteArray(6) + short(1) +
                            ByteArray(16) + short(1920) + short(1080) + ByteArray(50)
                    )))
                )
            )))
        val report = CmafBoxInspector.inspect(data)
        assertEquals("iso6", report.majorBrand)
        assertTrue(report.compatibleBrands.contains("cmfc"))
        assertEquals(1, report.tracks.size)
        val track = report.tracks.single()
        assertEquals("vide", track.handler)
        assertEquals(90000L, track.timescale)
        assertEquals("hvc1", track.sampleEntry)
        assertEquals(1920, track.width)
        assertEquals(1080, track.height)
        assertFalse(track.encrypted)
        assertFalse(report.fragmented)
    }

    @Test fun detectsFragmentedMp4FromMoof() {
        val report = CmafBoxInspector.inspect(box("moof", box("mfhd", ByteArray(8))))
        assertTrue(report.fragmented)
    }

    @Test fun readsFragmentDecodeAndPresentationTimesWithoutSamplePayloads() {
        val init = fragmentedInitSegment(trackId = 7, timescale = 90000, includeEncryption = true)
        val tfhd = box("tfhd", byteArrayOf(0, 0, 0, 0) + int(7))
        val tfdt = box("tfdt", byteArrayOf(0, 0, 0, 0) + int(9000))
        val trun = box("trun",
            byteArrayOf(1, 0, 11, 0) + int(2) +
                int(3000) + int(103) + int(120) +
                int(3000) + int(145) + int(-60)
        )
        val sampleOneEncryption = ByteArray(16) + short(1) + short(3) + int(100)
        val sampleTwoEncryption = ByteArray(16) + short(2) +
            short(0) + int(50) + short(5) + int(90)
        val senc = box("senc", byteArrayOf(0, 0, 0, 2) + int(2) +
            sampleOneEncryption + sampleTwoEncryption)
        val traf = box("traf", tfhd + tfdt + trun + senc +
            box("saiz", byteArrayOf(0, 0, 0, 0) + byteArrayOf(0) + int(2) + byteArrayOf(24, 30)) +
            box("saio", byteArrayOf(0, 0, 0, 0) + int(1) + int(100)) +
            box("sgpd", ByteArray(8)) + box("sbgp", ByteArray(8))
        )
        val fragment = box("moof", box("mfhd", byteArrayOf(0,0,0,0) + int(1)) + traf)
        val report = CmafBoxInspector.inspect(init + fragment)
        val track = report.tracks.single()
        assertEquals(7L, track.trackId)
        assertEquals(90000L, track.timescale)

        val timing = report.fragments.single()
        assertEquals(7L, timing.trackId)
        assertEquals(90000L, timing.timescale)
        assertEquals(9000L, timing.baseDecodeTimeTicks)
        assertEquals(2, timing.sampleCount)
        assertEquals(6000L, timing.totalDurationTicks)
        assertTrue(timing.timingComplete)
        assertEquals(listOf(9000L, 12000L), timing.sampleTimings.map { it.decodeTimeTicks })
        assertEquals(listOf(9120L, 11940L), timing.sampleTimings.map { it.presentationTimeTicks })
        assertEquals(listOf(3000L, 3000L), timing.sampleTimings.map { it.durationTicks })
        assertEquals(listOf(120L, -60L), timing.sampleTimings.map { it.compositionOffsetTicks })
        assertEquals(listOf(103L, 145L), timing.sampleTimings.map { it.sampleSizeBytes })
        assertTrue(timing.sampleEncryptionBoxPresent)
        assertTrue(timing.subsampleEncryptionSignalled!!)
        assertEquals(2L, timing.sampleEncryptionCount)
        assertTrue(timing.sampleEncryptionStructureComplete)
        assertTrue(timing.sampleEncryptionRangesCoverSamples)
        assertNull(timing.sencOverrideIvSize)
        assertEquals(listOf(16, 16), timing.sampleEncryptionMetadata.map { it.ivSize })
        assertEquals(listOf(1, 2), timing.sampleEncryptionMetadata.map { it.subsampleRanges!!.size })
        assertEquals(
            listOf(3, 0, 5),
            timing.sampleEncryptionMetadata.flatMap { it.subsampleRanges!! }.map { it.clearBytes }
        )
        assertEquals(
            listOf(100L, 50L, 90L),
            timing.sampleEncryptionMetadata.flatMap { it.subsampleRanges!! }.map { it.encryptedBytes }
        )
        assertTrue(timing.sampleAuxiliarySizesPresent)
        assertTrue(timing.sampleAuxiliaryOffsetsPresent)
        assertTrue(timing.sampleAuxiliaryInfoStructureComplete)
        assertEquals(2L, timing.sampleAuxiliaryMetadata!!.saizSampleCount)
        assertEquals(0, timing.sampleAuxiliaryMetadata.defaultSampleInfoSize)
        assertEquals(listOf(24, 30), timing.sampleAuxiliaryMetadata.sampleInfoSizes)
        assertEquals(listOf(100L), timing.sampleAuxiliaryMetadata.saioOffsets)
        assertTrue(timing.sampleGroupDescriptionPresent)
        assertTrue(timing.sampleToGroupPresent)
    }



    @Test fun resolvesFirstTrafImplicitBaseOffsetToMoofForExternalAuxiliaryRecords() {
        val init = fragmentedInitSegment(trackId = 73, timescale = 90000, includeEncryption = true)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(73))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,3,0) + int(1) + int(3000) + int(64))
        val saiz = box("saiz", byteArrayOf(0,0,0,0, 0) + int(1) + byteArrayOf(16))
        fun moof(offset: Int) = box("moof", box("mfhd", ByteArray(8)) +
            box("traf", tfhd + tfdt + trun + saiz +
                box("saio", byteArrayOf(0,0,0,0) + int(1) + int(offset))))
        val placeholder = moof(0)
        val actualMoof = moof(placeholder.size + 8)
        val report = CmafBoxInspector.inspect(
            init + actualMoof + box("mdat", ByteArray(16) { it.toByte() })
        )
        val fragment = report.fragments.single()
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertTrue(fragment.sampleAuxiliaryInfoStructureComplete)
        assertTrue(fragment.sampleAuxiliaryPayloadStructureComplete)
        assertEquals(listOf(16), fragment.sampleEncryptionMetadata.map { it.ivSize })
    }

    @Test fun verifiesExternallyAddressedAuxiliaryRecordsFromOneContiguousSaioOffset() {
        val init = fragmentedInitSegment(trackId = 70, timescale = 90000, includeEncryption = true)
        val tfhd = box("tfhd", byteArrayOf(0,2,0,0) + int(70)) // default-base-is-moof
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,3,0) + int(2) +
            int(3000) + int(103) + int(3000) + int(145))
        val saiz = box("saiz", byteArrayOf(0,0,0,0, 0) + int(2) + byteArrayOf(24,30))
        fun moof(offset: Int) = box("moof", box("mfhd", ByteArray(8)) +
            box("traf", tfhd + tfdt + trun + saiz +
                box("saio", byteArrayOf(0,0,0,0) + int(1) + int(offset))))
        val placeholder = moof(0)
        val auxOffset = placeholder.size + 8 // moof-relative, at the first byte in mdat
        val actualMoof = moof(auxOffset)
        val firstRecord = ByteArray(16) + short(1) + short(3) + int(100)
        val secondRecord = ByteArray(16) + short(2) +
            short(0) + int(50) + short(5) + int(90)
        val report = CmafBoxInspector.inspect(init + actualMoof + box("mdat", firstRecord + secondRecord))

        val fragment = report.fragments.single()
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertTrue(fragment.sampleAuxiliaryInfoStructureComplete)
        assertTrue(fragment.sampleAuxiliaryPayloadStructureComplete)
        assertEquals(2, fragment.sampleAuxiliaryPayloadSampleCount)
        assertTrue(fragment.sampleEncryptionStructureComplete)
        assertTrue(fragment.sampleEncryptionRangesCoverSamples)
        assertEquals(listOf(16, 16), fragment.sampleEncryptionMetadata.map { it.ivSize })
        assertEquals(listOf(1, 2), fragment.sampleEncryptionMetadata.map { it.subsampleRanges!!.size })
        assertEquals(listOf(3, 0, 5),
            fragment.sampleEncryptionMetadata.flatMap { it.subsampleRanges!! }.map { it.clearBytes })
        assertEquals(listOf(100L, 50L, 90L),
            fragment.sampleEncryptionMetadata.flatMap { it.subsampleRanges!! }.map { it.encryptedBytes })
    }

    @Test fun verifiesExternallyAddressedAuxiliaryRecordsWithOneSaioOffsetPerSample() {
        val init = fragmentedInitSegment(trackId = 71, timescale = 90000, includeEncryption = true)
        val tfhd = box("tfhd", byteArrayOf(0,2,0,0) + int(71))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,3,0) + int(2) +
            int(3000) + int(40) + int(3000) + int(60))
        val saiz = box("saiz", byteArrayOf(0,0,0,0, 0) + int(2) + byteArrayOf(16,16))
        fun moof(first: Int, second: Int) = box("moof", box("mfhd", ByteArray(8)) +
            box("traf", tfhd + tfdt + trun + saiz +
                box("saio", byteArrayOf(0,0,0,0) + int(2) + int(first) + int(second))))
        val placeholder = moof(0, 0)
        val firstOffset = placeholder.size + 8
        val secondOffset = firstOffset + 19 // 3-byte gap between independent records
        val actualMoof = moof(firstOffset, secondOffset)
        val auxiliaryPayload = ByteArray(16) + byteArrayOf(9,8,7) + ByteArray(16)
        val report = CmafBoxInspector.inspect(init + actualMoof + box("mdat", auxiliaryPayload))

        val fragment = report.fragments.single()
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertTrue(fragment.sampleAuxiliaryPayloadStructureComplete)
        assertEquals(2, fragment.sampleAuxiliaryPayloadSampleCount)
        assertEquals(listOf(16,16), fragment.sampleEncryptionMetadata.map { it.ivSize })
        assertTrue(fragment.sampleEncryptionRangesCoverSamples)
        assertTrue(fragment.sampleEncryptionMetadata.all { it.subsampleRanges == null })
    }

    @Test fun doesNotResolveExternalAuxiliaryOffsetsWithoutAnUnambiguousTfhdBase() {
        val init = fragmentedInitSegment(trackId = 72, timescale = 90000, includeEncryption = true)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(72))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,3,0) + int(1) + int(3000) + int(64))
        val saiz = box("saiz", byteArrayOf(0,0,0,0, 0) + int(1) + byteArrayOf(16))
        val saio = box("saio", byteArrayOf(0,0,0,0) + int(1) + int(0))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) +
                box("traf", tfhd + tfdt + trun + saiz + saio) + box("mdat", ByteArray(16)))
        )
        val fragment = report.fragments.single()
        assertTrue(fragment.sampleAuxiliaryInfoStructureComplete)
        assertFalse(fragment.sampleAuxiliaryPayloadStructureComplete)
        assertTrue(report.errors.any { it.contains("base is ambiguous") })
    }

    @Test fun parsesSencOverrideIvSizeAndSkipsKidBytes() {
        val init = fragmentedInitSegment(trackId = 22, timescale = 90000)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(22))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(1) + int(3000))
        val sencOverride = byteArrayOf(0,0,0,1) + byteArrayOf(1,2,3,8) +
            ByteArray(16) + int(1) + ByteArray(8)
        val senc = box("senc", sencOverride)
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun + senc))
        )
        val fragment = report.fragments.single()

        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertTrue(fragment.sampleEncryptionStructureComplete)
        assertEquals(8, fragment.sencOverrideIvSize)
        assertEquals(listOf(8), fragment.sampleEncryptionMetadata.map { it.ivSize })
        assertEquals(listOf(null), fragment.sampleEncryptionMetadata.map { it.subsampleRanges })
    }

    @Test fun doesNotGuessSampleIvLengthsWhenTencDefaultsAreMissing() {
        val init = fragmentedInitSegment(trackId = 21, timescale = 90000)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(21))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(1) + int(3000))
        // No senc override parameters are supplied, and the init has no tenc defaults.
        val senc = box("senc", byteArrayOf(0,0,0,2) + int(1) + ByteArray(18))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun + senc))
        )
        val fragment = report.fragments.single()
        assertTrue(fragment.sampleEncryptionBoxPresent)
        assertFalse(fragment.sampleEncryptionStructureComplete)
        assertTrue(fragment.sampleEncryptionMetadata.isEmpty())
        assertTrue(report.errors.isEmpty())
    }

    @Test fun rejectsSencSubsampleMapThatDoesNotCoverTheDeclaredSampleSize() {
        val init = fragmentedInitSegment(trackId = 31, timescale = 90000, includeEncryption = true)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(31))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,3,0) + int(1) + int(3000) + int(104))
        // One IV, followed by 3 clear + 100 encrypted bytes. Total map coverage is 103, not 104.
        val senc = box("senc", byteArrayOf(0,0,0,2) + int(1) +
            ByteArray(16) + short(1) + short(3) + int(100))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun + senc))
        )
        val fragment = report.fragments.single()
        assertTrue(fragment.sampleEncryptionStructureComplete)
        assertFalse(fragment.sampleEncryptionRangesCoverSamples)
    }

    @Test fun rejectsSaizMetadataWhenDeclaredSampleCountDoesNotMatchTrun() {
        val init = fragmentedInitSegment(trackId = 41, timescale = 90000)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(41))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(2) + int(3000) + int(3000))
        val saiz = box("saiz", byteArrayOf(0,0,0,0) + byteArrayOf(16) + int(1))
        val saio = box("saio", byteArrayOf(0,0,0,0) + int(1) + int(128))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun + saiz + saio))
        )
        val fragment = report.fragments.single()
        assertFalse(fragment.sampleAuxiliaryInfoStructureComplete)
        assertTrue(report.errors.any { it.contains("saiz/saio structure") })
    }

    @Test fun acceptsDefaultBaseIsMoofTfhdFlagAndReadsFragmentTiming() {
        val init = fragmentedInitSegment(trackId = 11, timescale = 90000)
        val tfhd = box("tfhd", byteArrayOf(0, 2, 0, 0) + int(11))
        val tfdt = box("tfdt", byteArrayOf(0, 0, 0, 0) + int(9000))
        val trun = box("trun", byteArrayOf(0, 0, 1, 0) + int(1) + int(3000))
        val traf = box("traf", tfhd + tfdt + trun)
        val fragment = box("moof", box("mfhd", byteArrayOf(0, 0, 0, 0) + int(2)) + traf)

        val report = CmafBoxInspector.inspect(init + fragment)
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        val timing = report.fragments.single()
        assertEquals(11L, timing.trackId)
        assertEquals(90000L, timing.timescale)
        assertEquals(9000L, timing.baseDecodeTimeTicks)
        assertEquals(3000L, timing.totalDurationTicks)
        assertTrue(timing.timingComplete)
        assertFalse(timing.durationIsEmpty)
    }

    @Test fun doesNotTreatZeroTrexDefaultsAsRealSampleDurationsOrSizes() {
        val init = fragmentedInitSegment(trackId = 51, timescale = 90000)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(51))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,0,0) + int(1))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun))
        )
        val timing = report.fragments.single()
        assertFalse(timing.timingComplete)
        assertEquals(listOf(null), timing.sampleTimings.map { it.durationTicks })
        assertEquals(listOf(null), timing.sampleTimings.map { it.sampleSizeBytes })
    }

    @Test fun parsesSeigSampleGroupEncryptionOverridesWithoutRetainingKidOrConstantIv() {
        val init = fragmentedInitSegment(trackId = 61, timescale = 90000, includeEncryption = true)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(61))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(2) + int(3000) + int(3000))
        val entry = byteArrayOf(0, 0x19, 1, 8) + ByteArray(16)
        val sgpd = box("sgpd", byteArrayOf(1,0,0,0) + ascii("seig") + int(0) + int(1) +
            int(entry.size) + entry)
        val sbgp = box("sbgp", byteArrayOf(0,0,0,0) + ascii("seig") + int(1) + int(2) + int(0x10001))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun + sgpd + sbgp))
        )
        val fragment = report.fragments.single()
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertTrue(fragment.sampleGroupEncryptionPresent)
        assertTrue(fragment.sampleGroupEncryptionStructureComplete)
        val group = fragment.sampleGroupEncryptionMetadata!!
        assertEquals(1, group.entries.size)
        assertTrue(group.entries.single().isProtected)
        assertEquals(8, group.entries.single().perSampleIvSize)
        assertEquals(1, group.entries.single().cryptByteBlock)
        assertEquals(9, group.entries.single().skipByteBlock)
        assertEquals(listOf(CmafBoxInspector.SampleGroupRun(2, 0x10001)), group.runs)
    }

    @Test fun appliesTrackLevelV2SeigDefaultWhenFragmentHasNoSbgp() {
        val entry = byteArrayOf(0, 0x19, 1, 8) + ByteArray(16)
        val trackSgpd = box(
            "sgpd",
            byteArrayOf(2,0,0,0) + ascii("seig") + int(0) + int(1) + int(1) +
                int(entry.size) + entry
        )
        val init = fragmentedInitSegment(
            trackId = 66, timescale = 90000, includeEncryption = true, trackSeig = trackSgpd
        )
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(66))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(2) + int(3000) + int(3000))
        // The track-level v2 default resolves both sample IV lengths to 8 bytes.
        val senc = box("senc", byteArrayOf(0,0,0,0) + int(2) + ByteArray(16))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun + senc))
        )
        val fragment = report.fragments.single()
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertTrue(fragment.sampleGroupEncryptionPresent)
        assertTrue(fragment.sampleGroupEncryptionStructureComplete)
        assertEquals(1L, fragment.sampleGroupEncryptionMetadata!!.defaultSampleDescriptionIndex)
        assertEquals(listOf(CmafBoxInspector.SampleGroupRun(2, 1)),
            fragment.sampleGroupEncryptionMetadata.runs)
        assertTrue(fragment.sampleEncryptionStructureComplete)
        assertEquals(listOf(8, 8), fragment.sampleEncryptionMetadata.map { it.ivSize })
    }

    @Test fun fragmentV2SeigDefaultOverridesTrackDefaultForUnmappedSamples() {
        val trackEntry = byteArrayOf(0, 0x19, 1, 8) + ByteArray(16)
        val localEntry = byteArrayOf(0, 0x29, 1, 16) + ByteArray(16)
        val trackSgpd = box(
            "sgpd",
            byteArrayOf(2,0,0,0) + ascii("seig") + int(0) + int(1) + int(1) +
                int(trackEntry.size) + trackEntry
        )
        val localSgpd = box(
            "sgpd",
            byteArrayOf(2,0,0,0) + ascii("seig") + int(0) + int(1) + int(1) +
                int(localEntry.size) + localEntry
        )
        val init = fragmentedInitSegment(
            trackId = 67, timescale = 90000, includeEncryption = true, trackSeig = trackSgpd
        )
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(67))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(2) + int(3000) + int(3000))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun + localSgpd))
        )
        val fragment = report.fragments.single()
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertEquals(0x10001L, fragment.sampleGroupEncryptionMetadata!!.defaultSampleDescriptionIndex)
        assertEquals(listOf(CmafBoxInspector.SampleGroupRun(2, 0x10001)),
            fragment.sampleGroupEncryptionMetadata.runs)
        assertEquals(listOf(1, 0x10001),
            fragment.sampleGroupEncryptionMetadata.entries.map { it.entryIndex })
    }

    @Test fun unmappedSbgpTailUsesEffectiveSeigDefaultInsteadOfSilentlyDroppingSamples() {
        val entry = byteArrayOf(0, 0x19, 1, 8) + ByteArray(16)
        val trackSgpd = box(
            "sgpd",
            byteArrayOf(2,0,0,0) + ascii("seig") + int(0) + int(1) + int(1) +
                int(entry.size) + entry
        )
        val init = fragmentedInitSegment(
            trackId = 68, timescale = 90000, includeEncryption = true, trackSeig = trackSgpd
        )
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(68))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(3) + int(3000) + int(3000) + int(3000))
        val sbgp = box("sbgp", byteArrayOf(0,0,0,0) + ascii("seig") + int(1) + int(2) + int(1))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun + sbgp))
        )
        val fragment = report.fragments.single()
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertEquals(
            listOf(CmafBoxInspector.SampleGroupRun(2, 1), CmafBoxInspector.SampleGroupRun(1, 1)),
            fragment.sampleGroupEncryptionMetadata!!.runs
        )
    }

    @Test fun resolvesTrackLevelSeigDescriptionFromFragmentSbgpIndex() {
        val entry = byteArrayOf(0, 0x19, 1, 8) + ByteArray(16)
        val trackSgpd = box(
            "sgpd",
            byteArrayOf(1,0,0,0) + ascii("seig") + int(0) + int(1) + int(entry.size) + entry
        )
        val init = fragmentedInitSegment(
            trackId = 64, timescale = 90000, includeEncryption = true, trackSeig = trackSgpd
        )
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(64))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(2) + int(3000) + int(3000))
        val sbgp = box("sbgp", byteArrayOf(0,0,0,0) + ascii("seig") + int(1) + int(2) + int(1))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun + sbgp))
        )
        val track = report.tracks.single()
        val fragment = report.fragments.single()
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertTrue(track.seigSampleGroupDescriptionStructureComplete)
        assertEquals(1, track.seigSampleGroupDescription!!.entries.size)
        assertTrue(fragment.sampleGroupEncryptionStructureComplete)
        assertEquals(1, fragment.sampleGroupEncryptionMetadata!!.entries.single().entryIndex)
        assertEquals(listOf(CmafBoxInspector.SampleGroupRun(2, 1)),
            fragment.sampleGroupEncryptionMetadata.runs)
    }

    @Test fun parsesSencUsingEffectiveSeigIvSizeInsteadOfTencDefault() {
        val init = fragmentedInitSegment(trackId = 65, timescale = 90000, includeEncryption = true)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(65))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(1) + int(3000))
        val entry = byteArrayOf(0, 0x19, 1, 8) + ByteArray(16) // seig IV size is 8, tenc is 16
        val sgpd = box("sgpd", byteArrayOf(1,0,0,0) + ascii("seig") + int(0) + int(1) +
            int(entry.size) + entry)
        val sbgp = box("sbgp", byteArrayOf(0,0,0,0) + ascii("seig") + int(1) + int(1) + int(0x10001))
        val senc = box("senc", byteArrayOf(0,0,0,0) + int(1) + ByteArray(8))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) +
                box("traf", tfhd + tfdt + trun + sgpd + sbgp + senc))
        )
        val fragment = report.fragments.single()
        assertTrue(fragment.sampleGroupEncryptionStructureComplete)
        assertTrue(fragment.sampleEncryptionBoxPresent)
        assertEquals(1L, fragment.sampleEncryptionCount)
        assertTrue(fragment.sampleEncryptionStructureComplete)
        assertEquals(listOf(8), fragment.sampleEncryptionMetadata.map { it.ivSize })
        assertTrue(report.errors.isEmpty())
    }

    @Test fun resolvesMixedSeigAndTrackDefaultIvSizesPerSample() {
        val init = fragmentedInitSegment(trackId = 66, timescale = 90000, includeEncryption = true)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(66))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(2) + int(3000) + int(3000))
        val entry = byteArrayOf(0, 0x19, 1, 8) + ByteArray(16)
        val sgpd = box("sgpd", byteArrayOf(1,0,0,0) + ascii("seig") + int(0) + int(1) +
            int(entry.size) + entry)
        val sbgp = box("sbgp", byteArrayOf(0,0,0,0) + ascii("seig") +
            int(2) + int(1) + int(0x10001) + int(1) + int(0))
        val senc = box("senc", byteArrayOf(0,0,0,0) + int(2) + ByteArray(8) + ByteArray(16))
        val saiz = box("saiz", byteArrayOf(0,0,0,0, 0) + int(2) + byteArrayOf(8, 16))
        val saio = box("saio", byteArrayOf(0,0,0,0) + int(1) + int(0))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) +
                box("traf", tfhd + tfdt + trun + sgpd + sbgp + senc + saiz + saio))
        )
        val fragment = report.fragments.single()
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        assertTrue(fragment.sampleEncryptionStructureComplete)
        assertEquals(listOf(8, 16), fragment.sampleEncryptionMetadata.map { it.ivSize })
        assertTrue(fragment.sampleAuxiliaryInfoStructureComplete)
    }

    @Test fun keepsSencOverrideAndActiveSeigCombinationIncomplete() {
        val init = fragmentedInitSegment(trackId = 69, timescale = 90000, includeEncryption = true)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(69))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(1) + int(3000))
        val entry = byteArrayOf(0, 0x19, 1, 8) + ByteArray(16)
        val sgpd = box("sgpd", byteArrayOf(1,0,0,0) + ascii("seig") + int(0) + int(1) +
            int(entry.size) + entry)
        val sbgp = box("sbgp", byteArrayOf(0,0,0,0) + ascii("seig") + int(1) + int(1) + int(0x10001))
        val senc = box("senc", byteArrayOf(0,0,0,1) + byteArrayOf(0,0,1,8) + ByteArray(16) + int(1) + ByteArray(8))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) +
                box("traf", tfhd + tfdt + trun + sgpd + sbgp + senc))
        )
        val fragment = report.fragments.single()
        assertTrue(fragment.sampleGroupEncryptionStructureComplete)
        assertFalse(fragment.sampleEncryptionStructureComplete)
        assertTrue(fragment.sampleEncryptionMetadata.isEmpty())
        assertTrue(report.errors.isEmpty())
    }

    @Test fun parsesSeigConstantIvSizeWithoutRetainingConstantIvBytes() {
        val init = fragmentedInitSegment(trackId = 63, timescale = 90000, includeEncryption = true)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(63))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(1) + int(3000))
        val entry = byteArrayOf(0, 0x19, 1, 0) + ByteArray(16) + byteArrayOf(16) + ByteArray(16)
        val sgpd = box("sgpd", byteArrayOf(1,0,0,0) + ascii("seig") + int(0) + int(1) +
            int(entry.size) + entry)
        val sbgp = box("sbgp", byteArrayOf(0,0,0,0) + ascii("seig") + int(1) + int(1) + int(0x10001))
        val senc = box("senc", byteArrayOf(0,0,0,0) + int(1))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) +
                box("traf", tfhd + tfdt + trun + sgpd + sbgp + senc))
        )
        val fragment = report.fragments.single()
        val entryMetadata = fragment.sampleGroupEncryptionMetadata!!.entries.single()
        assertEquals(0, entryMetadata.perSampleIvSize)
        assertEquals(16, entryMetadata.constantIvSize)
        assertTrue(fragment.sampleEncryptionStructureComplete)
        assertEquals(listOf(0), fragment.sampleEncryptionMetadata.map { it.ivSize })
    }

    @Test fun rejectsSeigMappingWithMissingDescriptionEntry() {
        val init = fragmentedInitSegment(trackId = 62, timescale = 90000)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(62))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(0))
        val trun = box("trun", byteArrayOf(0,0,1,0) + int(1) + int(3000))
        val sbgp = box("sbgp", byteArrayOf(0,0,0,0) + ascii("seig") + int(1) + int(1) + int(2))
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun + sbgp))
        )
        val fragment = report.fragments.single()
        assertTrue(fragment.sampleGroupEncryptionPresent)
        assertFalse(fragment.sampleGroupEncryptionStructureComplete)
        assertTrue(report.errors.any { it.contains("seig") })
    }

    @Test fun recognisesDurationIsEmptyWithoutInventingSampleTimings() {
        val init = fragmentedInitSegment(trackId = 12, timescale = 90000)
        val tfhd = box("tfhd", byteArrayOf(0, 1, 0, 0) + int(12))
        val tfdt = box("tfdt", byteArrayOf(0, 0, 0, 0) + int(12000))
        val traf = box("traf", tfhd + tfdt)
        val fragment = box("moof", box("mfhd", ByteArray(8)) + traf)

        val report = CmafBoxInspector.inspect(init + fragment)
        assertTrue(report.errors.toString(), report.errors.isEmpty())
        val timing = report.fragments.single()
        assertTrue(timing.durationIsEmpty)
        assertEquals(0, timing.sampleCount)
        assertTrue(timing.sampleTimings.isEmpty())
        assertFalse(timing.timingComplete)
    }

    @Test fun rejectsDurationIsEmptyTrackFragmentThatContainsTrun() {
        val init = fragmentedInitSegment(trackId = 13, timescale = 90000)
        val tfhd = box("tfhd", byteArrayOf(0, 1, 0, 0) + int(13))
        val trun = box("trun", byteArrayOf(0, 0, 0, 0) + int(1))
        val traf = box("traf", tfhd + trun)
        val report = CmafBoxInspector.inspect(
            init + box("moof", box("mfhd", ByteArray(8)) + traf)
        )

        assertTrue(report.fragments.isEmpty())
        assertTrue(report.errors.any { it.contains("duration-is-empty") })
    }

    @Test fun marksFragmentTimingIncompleteRatherThanGuessingMissingDuration() {
        val init = fragmentedInitSegment(trackId = 3, timescale = 1000, includeTrex = false)
        val tfhd = box("tfhd", byteArrayOf(0,0,0,0) + int(3))
        val tfdt = box("tfdt", byteArrayOf(0,0,0,0) + int(500))
        val trun = box("trun", byteArrayOf(0,0,0,0) + int(2))
        val fragment = box("moof", box("mfhd", ByteArray(8)) + box("traf", tfhd + tfdt + trun))
        val timing = CmafBoxInspector.inspect(init + fragment).fragments.single()

        assertEquals(2, timing.sampleCount)
        assertFalse(timing.timingComplete)
        assertEquals(listOf(500L, null), timing.sampleTimings.map { it.decodeTimeTicks })
        assertEquals(listOf(null, null), timing.sampleTimings.map { it.durationTicks })
        assertNull(timing.totalDurationTicks)
    }

    @Test fun readsHevcDecoderConfigurationRecordFieldsWithoutSamples() {
        val hvcC = byteArrayOf(
            1, 0xa1.toByte(),
            0x12, 0x34, 0x56, 0x78,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
            120, 0xf1.toByte(), 0x23, 0xfd.toByte(), 0xfd.toByte(),
            0xfa.toByte(), 0xf9.toByte(), 0x01, 0x2c, 0x9f.toByte(), 0
        )
        val entry = visualSampleEntry("hvc1", 1280, 720, box("hvcC", hvcC))
        val report = inspectVisualEntry(entry)
        val config = report.tracks.single().hevcConfiguration!!
        assertEquals(1, config.configurationVersion)
        assertEquals(2, config.profileSpace)
        assertTrue(config.tierFlag)
        assertEquals(1, config.profileIdc)
        assertEquals(120, config.levelIdc)
        assertEquals(0x123, config.minSpatialSegmentationIdc)
        assertEquals(1, config.parallelismType)
        assertEquals(1, config.chromaFormat)
        assertEquals(10, config.bitDepthLuma)
        assertEquals(9, config.bitDepthChroma)
        assertEquals(300, config.averageFrameRate)
        assertEquals(2, config.constantFrameRate)
        assertEquals(3, config.temporalLayers)
        assertTrue(config.temporalIdNested)
        assertEquals(4, config.nalLengthSize)
        assertEquals(0, config.vpsCount)
        assertEquals(0, config.spsCount)
        assertEquals(0, config.ppsCount)
    }

    @Test fun parsesVpsParameterSetArrayFromHvcC() {
        val vpsNal = byteArrayOf(0x40, 0x01) + BitWriter().apply {
            write(5, 4); write(1, 1); write(1, 1); write(0, 6)
            write(1, 3); write(1, 1); write(0xffff, 16)
        }.finish()
        val header = byteArrayOf(
            1, 0xa1.toByte(),
            0x12, 0x34, 0x56, 0x78,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
            120, 0xf1.toByte(), 0x23, 0xfd.toByte(), 0xfd.toByte(),
            0xfa.toByte(), 0xf9.toByte(), 0x01, 0x2c, 0x9f.toByte()
        )
        val array = byteArrayOf(0xa0.toByte()) + short(1) + short(vpsNal.size) + vpsNal
        val record = header + byteArrayOf(1) + array
        val config = inspectVisualEntry(
            visualSampleEntry("hvc1", 1280, 720, box("hvcC", record))
        ).tracks.single().hevcConfiguration!!

        assertEquals(1, config.vpsCount)
        assertEquals(0, config.spsCount)
        assertEquals(0, config.ppsCount)
        assertEquals(5, config.vps.single().vpsId)
        assertTrue(config.parameterSetFingerprints.single().startsWith("vps:"))
        assertEquals(64, config.parameterSetFingerprints.single().substringAfter(':').length)
        assertTrue(config.parameterSetErrors.isEmpty())
    }

    @Test fun readsCbcsTrackEncryptionShapeWithoutReturningKidOrIv() {
        val tenc = byteArrayOf(1, 0, 0, 0, 0, 0x19, 1, 16) + ByteArray(16)
        val sinf = box("sinf",
            box("frma", ascii("hvc1")) +
            box("schm", byteArrayOf(0,0,0,0) + ascii("cbcs") + int(0x00010000)) +
            box("schi", box("tenc", tenc))
        )
        val visual = visualSampleEntry("encv", 1280, 720, sinf)
        val metadata = inspectVisualEntry(visual).tracks.single()
        val protection = metadata.trackEncryption!!
        assertEquals("cbcs", metadata.protectionScheme)
        assertTrue(metadata.encrypted)
        assertTrue(protection.defaultIsProtected)
        assertEquals(16, protection.perSampleIvSize)
        assertEquals(1, protection.cryptByteBlock)
        assertEquals(9, protection.skipByteBlock)
        assertNull(protection.constantIvSize)
        assertFalse(metadata.sampleEncryptionBoxPresent)
        assertFalse(metadata.sampleAuxiliarySizesPresent)
        assertFalse(metadata.sampleAuxiliaryOffsetsPresent)
    }

    @Test fun readsVersionZeroTencWithoutVersionOnePatternByte() {
        val tenc = box("tenc", byteArrayOf(0,0,0,0, 0, 1, 8) + ByteArray(16))
        val sinf = box("sinf",
            box("frma", ascii("hvc1")) +
                box("schm", byteArrayOf(0,0,0,0) + ascii("cenc") + int(0x00010000)) +
                box("schi", tenc)
        )
        val metadata = inspectVisualEntry(visualSampleEntry("encv", 1280, 720, sinf)).tracks.single()
        val protection = metadata.trackEncryption!!
        assertEquals(0, protection.version)
        assertTrue(protection.defaultIsProtected)
        assertEquals(8, protection.perSampleIvSize)
        assertEquals(0, protection.cryptByteBlock)
        assertEquals(0, protection.skipByteBlock)
        assertNull(protection.constantIvSize)
    }

    @Test fun reportsUnsupportedOrMalformedHevcConfigurationWithoutExposingPayload() {
        val invalid = byteArrayOf(2) + ByteArray(22)
        val report = inspectVisualEntry(visualSampleEntry("hvc1", 640, 360, box("hvcC", invalid)))
        assertNull(report.tracks.single().hevcConfiguration)
        assertTrue(report.errors.any { it.contains("HEVC decoder configuration") })
    }




    @Test fun identifiesCbcsProtectionWithoutReadingProtectionPayload() {
        val sinf = box("sinf", box("frma", ascii("hvc1")) +
            box("schm", byteArrayOf(0,0,0,0) + ascii("cbcs") + int(0x00010000)))
        val visualEntry = int(8 + 78 + sinf.size) + ascii("encv") +
            ByteArray(6) + short(1) + ByteArray(16) + short(1280) + short(720) + ByteArray(50) + sinf
        val track = box("trak", box("mdia",
            box("hdlr", byteArrayOf(0,0,0,0) + int(0) + ascii("vide") + ByteArray(12)),
            box("minf", box("stbl", box("stsd", byteArrayOf(0,0,0,0) + int(1) + visualEntry)))
        ))
        val report = CmafBoxInspector.inspect(box("moov", track))
        val metadata = report.tracks.single()
        assertEquals("encv", metadata.sampleEntry)
        assertEquals("hvc1", metadata.originalFormat)
        assertEquals("cbcs", metadata.protectionScheme)
        assertTrue(metadata.encrypted)
    }

    @Test fun rejectsBoxWhoseDeclaredSizeExceedsInput() {
        val malformed = int(100) + ascii("free") + ByteArray(4)
        try {
            CmafBoxInspector.inspect(malformed)
            fail("Expected malformed box to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsInputShorterThanHeader() {
        try {
            CmafBoxInspector.inspect(ByteArray(7))
            fail("Expected short input to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    private fun fragmentedInitSegment(
        trackId: Int,
        timescale: Int,
        includeTrex: Boolean = true,
        includeEncryption: Boolean = false,
        trackSeig: ByteArray = ByteArray(0)
    ): ByteArray {
        val tkhd = box("tkhd", byteArrayOf(0,0,0,7) +
            int(0) + int(0) + int(trackId) + int(0) + ByteArray(60))
        val mdhd = box("mdhd", byteArrayOf(0,0,0,0) +
            int(0) + int(0) + int(timescale) + int(0) + short(0) + short(0))
        val hdlr = box("hdlr", byteArrayOf(0,0,0,0) + int(0) + ascii("vide") + ByteArray(12))
        val sampleEntry = if (includeEncryption) {
            val tenc = box("tenc", byteArrayOf(1, 0, 0, 0, 0, 0x19, 1, 16) + ByteArray(16))
            val protection = box("sinf",
                box("frma", ascii("hvc1")) +
                    box("schm", byteArrayOf(0,0,0,0) + ascii("cbcs") + int(0x00010000)) +
                    box("schi", tenc)
            )
            visualSampleEntry("encv", 1920, 1080, protection)
        } else {
            visualSampleEntry("hvc1", 1920, 1080)
        }
        val stsd = box("stsd", byteArrayOf(0,0,0,0) + int(1) + sampleEntry)
        val minf = box("minf", box("stbl", stsd + trackSeig))
        val trak = box("trak", tkhd + box("mdia", mdhd + hdlr + minf))
        val mvex = if (includeTrex) box("mvex", box("trex",
            byteArrayOf(0,0,0,0) + int(trackId) + int(1) + int(0) + int(0) + int(0)
        )) else ByteArray(0)
        return box("moov", trak + mvex)
    }

    private fun inspectVisualEntry(entry: ByteArray): CmafBoxInspector.Report {
        val data = box("moov", box("trak", box("mdia",
            box("hdlr", byteArrayOf(0,0,0,0) + int(0) + ascii("vide") + ByteArray(12)),
            box("minf", box("stbl", box("stsd", byteArrayOf(0,0,0,0) + int(1) + entry)))
        )))
        return CmafBoxInspector.inspect(data)
    }

    private fun visualSampleEntry(
        type: String,
        width: Int,
        height: Int,
        vararg childBoxes: ByteArray
    ): ByteArray {
        val visualFields = ByteArray(6) + short(1) + ByteArray(16) +
            short(width) + short(height) + ByteArray(50)
        val payload = ascii(type) + visualFields + childBoxes.fold(ByteArray(0)) { acc, value -> acc + value }
        return int(payload.size + 4) + payload
    }

    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += (value ushr i) and 1
        }
        fun finish(): ByteArray {
            bits += 1
            while (bits.size % 8 != 0) bits += 0
            return ByteArray(bits.size / 8) { index ->
                var value = 0
                for (bit in 0 until 8) value = (value shl 1) or bits[index * 8 + bit]
                value.toByte()
            }
        }
    }

    private fun box(type: String, vararg payloads: ByteArray): ByteArray {
        val payload = payloads.fold(ByteArray(0)) { acc, part -> acc + part }
        return int(payload.size + 8) + ascii(type) + payload
    }
    private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
    private fun short(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()
    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.ISO_8859_1)
}
