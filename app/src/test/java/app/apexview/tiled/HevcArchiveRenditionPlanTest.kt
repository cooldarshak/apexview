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
    @Test fun normalPhonePlaybackKeepsItsExistingLowerRungs() {
        assertEquals(960 to 540, HevcArchiveRenditionPlan.choose(rungs, 540, 0, 4, 4096))
    }
}
