package app.apexview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSurfacePolicyTest {
    @Test
    fun singleL3FeedUsesSurfaceView() {
        assertFalse(useTextureViewForFeed(usesSoftwareDrm = true, visibleFeedCount = 1))
    }

    @Test
    fun multipleL3FeedsKeepTextureViewForTileOrdering() {
        assertTrue(useTextureViewForFeed(usesSoftwareDrm = true, visibleFeedCount = 2))
        assertTrue(useTextureViewForFeed(usesSoftwareDrm = true, visibleFeedCount = 4))
    }

    @Test
    fun L1AlwaysUsesSurfaceView() {
        assertFalse(useTextureViewForFeed(usesSoftwareDrm = false, visibleFeedCount = 1))
        assertFalse(useTextureViewForFeed(usesSoftwareDrm = false, visibleFeedCount = 4))
    }
}
