package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtectedHevcSampleComposerTest {
    @Test fun keepsAdjacentEncryptedSubsampleBoundariesInOutputMap() {
        val nal = byteArrayOf(0x02, 0x01, 0x7e, 0x55)
        val rewritten = ProtectedHevcSliceHeaderRewriter.Result(
            nal = nal,
            protectionRanges = listOf(
                ProtectedHevcSampleLayout.ProtectionRange(0, 2, true),
                ProtectedHevcSampleLayout.ProtectionRange(2, 1, false),
                ProtectedHevcSampleLayout.ProtectionRange(3, 1, false)
            ),
            destinationAddress = 0,
            sourceTailOffset = 2,
            outputTailOffset = 2,
            copiedTailBytes = 2,
            encryptedByteCount = 2L
        )

        val output = ProtectedHevcSampleComposer.compose(listOf(rewritten), nalLengthSize = 4)

        assertEquals(
            listOf(
                CmafBoxInspector.SubsampleEncryptionRange(6, 1),
                CmafBoxInspector.SubsampleEncryptionRange(0, 1)
            ),
            output.subsampleRanges
        )
        val layout = ProtectedHevcSampleLayout.inspect(
            output.sampleBytes, output.nalLengthSize, output.subsampleRanges
        )
        assertEquals(
            listOf(
                ProtectedHevcSampleLayout.ProtectionRange(0, 2, true),
                ProtectedHevcSampleLayout.ProtectionRange(2, 1, false),
                ProtectedHevcSampleLayout.ProtectionRange(3, 1, false)
            ),
            layout.nals.single().protectionRanges
        )
    }

    @Test fun rejectsNalWithIncompleteProtectionMap() {
        val rewritten = ProtectedHevcSliceHeaderRewriter.Result(
            nal = byteArrayOf(0x02, 0x01, 0x7e),
            protectionRanges = listOf(
                ProtectedHevcSampleLayout.ProtectionRange(0, 2, true)
            ),
            destinationAddress = 0,
            sourceTailOffset = 2,
            outputTailOffset = 2,
            copiedTailBytes = 1,
            encryptedByteCount = 0L
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProtectedHevcSampleComposer.compose(listOf(rewritten), 4)
        }
    }
}
