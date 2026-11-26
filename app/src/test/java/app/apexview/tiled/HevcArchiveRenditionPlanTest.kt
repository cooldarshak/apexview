package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class HevcArchiveRenditionPlanTest {
    private val rungs = setOf(480 to 270, 960 to 540, 1280 to 720, 1920 to 1080)
    @Test fun fourCastSourcesSelectAtLeast720RegardlessOfTheOld540Preference() {
        assertEquals(1280 to 720, HevcArchiveRenditionPlan.choose(rungs, 540, 720, 4, 4096))
    }
    @Test fun aMissing720RungCannotDowngradeTo540() {
        assertNull(HevcArchiveRenditionPlan.choose(setOf(960 to 540), 720, 720, 4, 4096))
    }
    @Test fun oversized720CompositionCannotDowngrade() {
        assertNull(HevcArchiveRenditionPlan.choose(rungs, 720, 720, 24, 4096))
    }
    @Test fun anExisting1080RequestIsHonoredWhenItFits() {
        assertEquals(1920 to 1080, HevcArchiveRenditionPlan.choose(rungs, 1080, 720, 2, 4096))
    }
    @Test fun three1080FeedsFitWithCodedPaddingAndUseTwoRows() {
        assertEquals(1920 to 1080, HevcArchiveRenditionPlan.choose(rungs, 1080, 1080, 3, 4096))
        assertEquals(HevcArchiveAtlasPlan(2, 2),
            HevcArchiveAtlasPlan.choose(3, 1920, 1088, 4096, HevcArchiveAtlasPlan.MAX_CODED_HEIGHT))
        assertNull(HevcArchiveRenditionPlan.choose(setOf(960 to 540), 1080, 1080, 3, 4096))
    }
    @Test fun unavailableOrOversizedAutoRenditionCannotEscapeDecoderBounds() {
        assertNull(HevcArchiveRenditionPlan.choose(
            setOf(7000 to 4000), null, 0, 4, 4096))
        assertEquals(1280 to 720, HevcArchiveRenditionPlan.choose(
            setOf(7000 to 4000, 1280 to 720), null, 0, 4, 4096))
        assertNull(HevcArchiveRenditionPlan.choose(
            setOf(1920 to 1080), null, 0, 24, 4096))
    }

    @Test fun commonAutoUsesAggregateOfferedBitrateAndRetainsGeometryGates() {
        val a = listOf(Triple(480,270,200_000), Triple(960,540,800_000),
            Triple(1280,720,1_500_000), Triple(1920,1080,3_000_000))
        val costs = HevcArchiveRenditionPlan.aggregateBitrates(listOf(a, a))
        assertEquals(3_000_000L, costs[1280 to 720])
        assertEquals(960 to 540, HevcArchiveRenditionPlan.choose(rungs, 1080, 0, 2, 4096, costs, 2_000_000L))
        assertEquals(1280 to 720, HevcArchiveRenditionPlan.choose(rungs, 1080, 0, 2, 4096, costs, 3_000_000L))
        assertEquals(480 to 270, HevcArchiveRenditionPlan.choose(rungs, 1080, 0, 2, 4096, costs, 1L))
        assertEquals(1280 to 720, HevcArchiveRenditionPlan.choose(rungs, 720, 720, 4, 4096, costs, 1L))
        assertEquals(1920 to 1080, HevcArchiveRenditionPlan.choose(rungs, 1080, 0, 2, 4096, costs, null))
        val unknown = HevcArchiveRenditionPlan.aggregateBitrates(listOf(a, a.map { it.copy(third = -1) }))
        assertTrue(unknown.isEmpty())
        assertEquals(1920 to 1080, HevcArchiveRenditionPlan.choose(rungs, 1080, 0, 2, 4096, unknown, 1L))
    }

    @Test fun manualDoesNotSilentlyDowngradeAnAdvertisedButIncompatibleHeight() {
        val offers = setOf(480 to 270, 7000 to 1080)
        assertNull(HevcArchiveRenditionPlan.chooseManual(offers, 1080, 0, 4, 4096))
        assertEquals(480 to 270, HevcArchiveRenditionPlan.chooseManual(offers, 720, 0, 4, 4096))
        assertNull(HevcArchiveRenditionPlan.chooseManual(setOf(960 to 540), 270, 0, 2, 4096))
        assertNull(HevcArchiveRenditionPlan.chooseManual(rungs, 540, 720, 2, 4096))
        assertEquals(1920 to 1080, HevcArchiveRenditionPlan.chooseManual(rungs, 1080, 0, 3, 4096))
    }

    @Test fun normalPhonePlaybackKeepsItsExistingLowerRungs() {
        assertEquals(960 to 540, HevcArchiveRenditionPlan.choose(rungs, 540, 0, 4, 4096))
    }
}
