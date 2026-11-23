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

    @Test fun normalPhonePlaybackKeepsItsExistingLowerRungs() {
        assertEquals(960 to 540, HevcArchiveRenditionPlan.choose(rungs, 540, 0, 4, 4096))
    }
}
