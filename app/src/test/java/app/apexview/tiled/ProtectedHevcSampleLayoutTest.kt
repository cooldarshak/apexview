package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class ProtectedHevcSampleLayoutTest {
    @Test fun mapsClearNalHeaderAndContiguousSlicePrefixWithoutCopyingEncryptedPayload() {
        val sample = byteArrayOf(0, 0, 0, 5, 0x02, 0x01, 0x7e, 0x55, 0x66)
        val report = ProtectedHevcSampleLayout.inspect(
            sample, 4, listOf(CmafBoxInspector.SubsampleEncryptionRange(7, 2))
        )
        assertEquals(sample.size, report.sampleSizeBytes)
        assertEquals(7L, report.clearByteCount)
        assertEquals(2L, report.encryptedByteCount)
        val nal = report.nals.single()
        assertEquals(4, nal.nalOffset)
        assertEquals(5, nal.nalLength)
        assertEquals(1, nal.nalType)
        assertEquals(0, nal.layerId)
        assertEquals(1, nal.temporalIdPlus1)
        assertEquals(3, nal.clearNalPrefixBytes)
        assertEquals(
            listOf(
                ProtectedHevcSampleLayout.ProtectionRange(0, 3, true),
                ProtectedHevcSampleLayout.ProtectionRange(3, 2, false)
            ),
            nal.protectionRanges
        )
    }

    @Test fun mapsMultipleNalsAcrossDistinctSubsamplePairs() {
        val sample = byteArrayOf(
            0, 0, 0, 3, 0x02, 0x01, 0x7e,
            0, 0, 0, 4, 0x04, 0x01, 0x11, 0x22
        )
        val report = ProtectedHevcSampleLayout.inspect(
            sample, 4,
            listOf(
                CmafBoxInspector.SubsampleEncryptionRange(7, 0),
                CmafBoxInspector.SubsampleEncryptionRange(0, 0),
                CmafBoxInspector.SubsampleEncryptionRange(7, 1)
            )
        )
        assertEquals(2, report.nals.size)
        assertEquals(1, report.nals[0].nalType)
        assertEquals(2, report.nals[1].nalType)
        assertEquals(3, report.nals[0].clearNalPrefixBytes)
        assertEquals(3, report.nals[1].clearNalPrefixBytes)
        assertEquals(
            listOf(ProtectedHevcSampleLayout.ProtectionRange(0, 3, true)),
            report.nals[0].protectionRanges
        )
        assertEquals(
            listOf(
                ProtectedHevcSampleLayout.ProtectionRange(0, 3, true),
                ProtectedHevcSampleLayout.ProtectionRange(3, 1, false)
            ),
            report.nals[1].protectionRanges
        )
    }

    @Test fun mapsAlternatingClearAndEncryptedSubsampleRegionsWithinOneNal() {
        val sample = byteArrayOf(
            0, 0, 0, 6, 0x02, 0x01, 0x7e, 0x55, 0x66, 0x77
        )
        val report = ProtectedHevcSampleLayout.inspect(
            sample, 4, listOf(
                CmafBoxInspector.SubsampleEncryptionRange(7, 2),
                CmafBoxInspector.SubsampleEncryptionRange(1, 0)
            )
        )
        assertEquals(
            listOf(
                ProtectedHevcSampleLayout.ProtectionRange(0, 3, true),
                ProtectedHevcSampleLayout.ProtectionRange(3, 2, false),
                ProtectedHevcSampleLayout.ProtectionRange(5, 1, true)
            ),
            report.nals.single().protectionRanges
        )
    }

    @Test fun rejectsNalLengthPrefixThatCrossesIntoEncryptedBytes() {
        val sample = byteArrayOf(0, 0, 0, 3, 0x02, 0x01, 0x7e)
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcSampleLayout.inspect(
                sample, 4, listOf(CmafBoxInspector.SubsampleEncryptionRange(2, 5))
            )
        }
        assertTrue(error.message!!.contains("NAL length prefix"))
    }

    @Test fun rejectsNalHeaderThatIsPartiallyEncrypted() {
        val sample = byteArrayOf(0, 0, 0, 3, 0x02, 0x01, 0x7e)
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcSampleLayout.inspect(
                sample, 4, listOf(CmafBoxInspector.SubsampleEncryptionRange(5, 2))
            )
        }
        assertTrue(error.message!!.contains("HEVC NAL header"))
    }

    @Test fun rejectsSubsampleMapThatDoesNotCoverTheWholeSample() {
        val sample = byteArrayOf(0, 0, 0, 3, 0x02, 0x01, 0x7e)
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcSampleLayout.inspect(
                sample, 4, listOf(CmafBoxInspector.SubsampleEncryptionRange(6, 0))
            )
        }
        assertTrue(error.message!!.contains("subsample map covers"))
    }
}
