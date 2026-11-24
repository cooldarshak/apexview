package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class HevcArchiveAtlasPlanTest {
    @Test fun keepsExistingRowWhenItFits() {
        assertEquals(HevcArchiveAtlasPlan(4, 1), HevcArchiveAtlasPlan.choose(4, 960, 544, 4096, 2160))
    }

    @Test fun largerSelectionsCanUseRowsInsteadOfAnOverwidePicture() {
        val atlas = HevcArchiveAtlasPlan.choose(24, 480, 272, 4096, 2160)!!
        assertTrue(atlas.rows > 1)
        assertTrue(atlas.columns * atlas.rows >= 24)
        assertTrue(atlas.columns * 480 <= 4096)
        assertTrue(atlas.rows * 272 <= 2160)
        assertEquals(24, atlas.columns * atlas.rows)
    }

    @Test fun sevenAndEightSourcesRequireOneConformingCodedAtlas() {
        val seven = HevcArchiveAtlasPlan.choose(7, 480, 272, 4096, 2160)!!
        val eight = HevcArchiveAtlasPlan.choose(8, 480, 272, 4096, 2160)!!
        assertTrue(seven.columns * seven.rows >= 7)
        assertTrue(eight.columns * eight.rows >= 8)
        assertTrue(seven.columns * 480 <= 4096 && seven.rows * 272 <= 2160)
        assertTrue(eight.columns * 480 <= 4096 && eight.rows * 272 <= 2160)
        assertNull(HevcArchiveAtlasPlan.choose(8, 1920, 1088, 4096, 2160))
    }

    @Test fun rejectsASelectionThatCannotFitAnyRowsOrColumns() {
        assertNull(HevcArchiveAtlasPlan.choose(24, 1920, 1088, 4096, 2160))
    }
}
