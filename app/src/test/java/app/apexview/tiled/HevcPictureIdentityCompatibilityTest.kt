package app.apexview.tiled

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcPictureIdentityCompatibilityTest {
    @Test
    fun acceptsMatchingPictureTypeTemporalLayerPocAndReferenceSet() {
        val identity = HevcPictureIdentityCompatibility.PictureIdentity(
            nalUnitType = 1,
            temporalIdPlus1 = 1,
            pictureOrderCount = 105,
            sliceType = 1,
            noOutputOfPriorPictures = null,
            references = listOf(
                HevcSliceHeaderParser.ReferencePicture(-1, null, true, false),
                HevcSliceHeaderParser.ReferencePicture(-2, null, false, false)
            )
        )
        val result = HevcPictureIdentityCompatibility.compare(
            listOf(identity, identity.copy(pictureOrderCount = 106)),
            listOf(identity.copy(), identity.copy(pictureOrderCount = 106))
        )
        assertTrue(result.reasons.toString(), result.compatible)
    }

    @Test
    fun rejectsPocReferenceSetTemporalLayerAndRandomAccessMismatch() {
        val first = HevcPictureIdentityCompatibility.PictureIdentity(
            nalUnitType = 21,
            temporalIdPlus1 = 1,
            pictureOrderCount = 240,
            sliceType = 2,
            noOutputOfPriorPictures = true,
            references = emptyList()
        )
        val second = first.copy(
            nalUnitType = 1,
            temporalIdPlus1 = 2,
            pictureOrderCount = 241,
            noOutputOfPriorPictures = false,
            references = listOf(
                HevcSliceHeaderParser.ReferencePicture(-1, null, true, false)
            )
        )
        val result = HevcPictureIdentityCompatibility.compare(listOf(first), listOf(second))
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("picture type") })
        assertTrue(result.reasons.any { it.contains("temporal_id_plus1") })
        assertTrue(result.reasons.any { it.contains("full POC") })
        assertTrue(result.reasons.any { it.contains("no-output flag") })
        assertTrue(result.reasons.any { it.contains("reference-picture set") })
    }

    @Test
    fun rejectsMissingOrDifferentIdentityCounts() {
        val identity = HevcPictureIdentityCompatibility.PictureIdentity(
            19, 1, 0, 2, true, emptyList()
        )
        assertFalse(HevcPictureIdentityCompatibility.compare(emptyList(), listOf(identity)).compatible)
        val countMismatch = HevcPictureIdentityCompatibility.compare(
            listOf(identity, identity), listOf(identity)
        )
        assertTrue(countMismatch.reasons.any { it.contains("identity counts differ") })
    }
}
