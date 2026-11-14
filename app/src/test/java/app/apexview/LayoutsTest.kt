package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {
    // Roughly a foldable's inner screen held landscape, as in the 0.5 screenshot.
    private val w = 1890f
    private val h = 1960f

    @Test
    fun fourFeedsFitOnScreenWithoutOverlapping() {
        for (mode in LayoutMode.entries) {
            val slots = Layouts.place(mode, 4, w, h)
            assertEquals(4, slots.size)
            slots.forEach { slot ->
                assertTrue(
                    "$mode $slot off screen",
                    slot.x >= -0.5f && slot.y >= -0.5f &&
                        slot.x + slot.w <= w + 0.5f && slot.y + slot.h <= h + 0.5f,
                )
            }
            for (i in slots.indices) for (j in i + 1 until slots.size) {
                val a = slots[i]
                val b = slots[j]
                val overlap = a.x < b.x + b.w - 1 && b.x < a.x + a.w - 1 &&
                    a.y < b.y + b.h - 1 && b.y < a.y + a.h - 1
                assertTrue("$mode slots $i and $j overlap", !overlap)
            }
        }
    }

    @Test
    fun mapGetsRealSpaceNextToFourFeeds() {
        for (mode in LayoutMode.entries) {
            for ((sw, sh) in listOf(w to h, 2208f to 1840f, 1840f to 2208f)) {
                val arrangement = Layouts.withMap(mode, 4, sw, sh)
                val map = arrangement.map!!
                assertTrue("$mode $sw x $sh map too small: $map", map.w * map.h > sw * sh * 0.15f)
                assertTrue("$mode $sw x $sh map overlaps a tile", arrangement.slots.none { map.overlaps(it) })
                assertEquals(4, arrangement.slots.size)
            }
        }
    }

    @Test
    fun combinedDockedLayoutsKeepVideoTowerAndMapInSeparateRegions() {
        val screens = listOf(
            1080f to 2400f,
            720f to 1600f,
            1920f to 1080f,
            2560f to 1440f,
            1280f to 720f,
        )
        for (mode in LayoutMode.entries) {
            for ((width, height) in screens) {
                for (feedCount in 1..4) {
                    val plan = Layouts.withCombinedOverlays(
                        mode, feedCount, width, height, OverlayLayoutMode.DOCKED,
                    )
                    assertEquals(feedCount, plan.slots.size)
                    assertTrue(plan.feeds.x >= 0f && plan.feeds.y >= 0f)
                    assertTrue(plan.feeds.x + plan.feeds.w <= width + 0.5f)
                    assertTrue(plan.feeds.y + plan.feeds.h <= height + 0.5f)
                    assertTrue(plan.workspace.x >= 0f && plan.workspace.y >= 0f)
                    assertTrue(plan.workspace.x + plan.workspace.w <= width + 0.5f)
                    assertTrue(plan.workspace.y + plan.workspace.h <= height + 0.5f)
                    assertTrue("Tower outside workspace: ${plan.tower}", plan.tower.x >= plan.workspace.x && plan.tower.y >= plan.workspace.y)
                    assertTrue("Map outside workspace: ${plan.map}", plan.map.x >= plan.workspace.x && plan.map.y >= plan.workspace.y)
                    assertTrue(!plan.tower.overlaps(plan.map))
                    assertTrue(plan.slots.none { plan.workspace.overlaps(it) })
                    plan.slots.forEach { slot ->
                        assertTrue(slot.x >= plan.feeds.x - 0.5f && slot.y >= plan.feeds.y - 0.5f)
                        assertTrue(slot.x + slot.w <= plan.feeds.x + plan.feeds.w + 0.5f)
                        assertTrue(slot.y + slot.h <= plan.feeds.y + plan.feeds.h + 0.5f)
                    }
                }
            }
        }
    }

    @Test
    fun tvLandscapeLayoutKeepsVideoLeftTowerTopRightAndMapBelow() {
        for ((width, height) in listOf(1920f to 1080f, 1280f to 720f, 3840f to 2160f)) {
            for (feedCount in 1..4) {
                val plan = Layouts.withCombinedOverlays(
                    LayoutMode.TV, feedCount, width, height, OverlayLayoutMode.DOCKED,
                )
                assertEquals(feedCount, plan.slots.size)
                assertEquals(0f, plan.feeds.x, 0.01f)
                assertTrue(plan.feeds.y > 0f)
                assertEquals(plan.feeds.w, plan.workspace.x, 0.01f)
                assertEquals(0f, plan.tower.y, 0.01f)
                assertEquals(plan.workspace.x, plan.tower.x, 0.01f)
                assertEquals(plan.tower.y + plan.tower.h, plan.map.y, 0.5f)
                assertTrue(plan.map.y + plan.map.h <= height + 0.5f)
                assertTrue(plan.slots.all { slot ->
                    slot.x >= plan.feeds.x - 0.5f && slot.y >= plan.feeds.y - 0.5f &&
                        slot.x + slot.w <= plan.feeds.x + plan.feeds.w + 0.5f &&
                        slot.y + slot.h <= plan.feeds.y + plan.feeds.h + 0.5f
                })
                assertTrue(plan.slots.none { plan.workspace.overlaps(it) })
            }
        }
    }

    @Test
    fun combinedFloatingLayoutsReserveAWorkspaceForBothPanels() {
        val screens = listOf(
            1080f to 2400f,
            720f to 1600f,
            1920f to 1080f,
            2560f to 1440f,
            1280f to 720f,
        )
        for (mode in LayoutMode.entries) {
            for ((width, height) in screens) {
                for (feedCount in 1..4) {
                    val plan = Layouts.withCombinedOverlays(
                        mode, feedCount, width, height, OverlayLayoutMode.FLOATING,
                    )
                    assertTrue(plan.floating)
                    assertEquals(feedCount, plan.slots.size)
                    assertTrue(plan.tower == plan.workspace)
                    assertTrue(plan.map == plan.workspace)
                    assertTrue(plan.slots.none { plan.workspace.overlaps(it) })
                    plan.slots.forEach { slot ->
                        assertTrue(slot.x >= plan.feeds.x - 0.5f && slot.y >= plan.feeds.y - 0.5f)
                        assertTrue(slot.x + slot.w <= plan.feeds.x + plan.feeds.w + 0.5f)
                        assertTrue(slot.y + slot.h <= plan.feeds.y + plan.feeds.h + 0.5f)
                    }
                }
            }
        }
    }

    @Test
    fun combinedPortraitDockedLayoutKeepsVideoTowerAndMapInThreeSeparateRegions() {
        for ((width, height) in listOf(1080f to 2400f, 720f to 1600f)) {
            for (feedCount in 1..4) {
                val plan = Layouts.withCombinedOverlays(
                    LayoutMode.FEATURED, feedCount, width, height, OverlayLayoutMode.DOCKED,
                )
                assertEquals(feedCount, plan.slots.size)
                assertTrue(plan.feeds.y == 0f)
                assertTrue(plan.workspace.y == plan.feeds.h)
                assertTrue(plan.feeds.h < height)
                assertTrue(plan.tower.y == plan.workspace.y)
                assertTrue(plan.map.y == plan.tower.y + plan.tower.h)
                assertTrue(plan.tower.h > plan.map.h)
                assertTrue(plan.tower.y + plan.tower.h <= height + 0.5f)
                assertTrue(plan.map.y + plan.map.h <= height + 0.5f)
                assertTrue(plan.slots.all { slot ->
                    slot.x >= plan.feeds.x - 0.5f && slot.y >= plan.feeds.y - 0.5f &&
                        slot.x + slot.w <= plan.feeds.x + plan.feeds.w + 0.5f &&
                        slot.y + slot.h <= plan.feeds.y + plan.feeds.h + 0.5f
                })
                assertTrue(plan.slots.none { plan.tower.overlaps(it) || plan.map.overlaps(it) })
            }
        }
    }

    @Test
    fun combinedLandscapeDockedLayoutUsesLeftVideoAndRightStackedPanels() {
        for ((width, height) in listOf(2400f to 1080f, 1600f to 720f)) {
            for (feedCount in 1..4) {
                val plan = Layouts.withCombinedOverlays(
                    LayoutMode.FEATURED, feedCount, width, height, OverlayLayoutMode.DOCKED,
                )
                assertEquals(feedCount, plan.slots.size)
                assertTrue(plan.feeds.x == 0f)
                assertTrue(plan.workspace.x == plan.feeds.w)
                assertTrue(plan.feeds.w > width * 0.6f)
                assertTrue(plan.tower.y == 0f)
                assertTrue(plan.map.y == plan.tower.h)
                assertTrue(plan.tower.h > plan.map.h)
                assertTrue(plan.workspace.x + plan.workspace.w <= width + 0.5f)
                assertTrue(plan.map.y + plan.map.h <= height + 0.5f)
                assertTrue(plan.slots.all { slot ->
                    slot.x >= plan.feeds.x - 0.5f && slot.y >= plan.feeds.y - 0.5f &&
                        slot.x + slot.w <= plan.feeds.x + plan.feeds.w + 0.5f &&
                        slot.y + slot.h <= plan.feeds.y + plan.feeds.h + 0.5f
                })
                assertTrue(plan.slots.none { plan.tower.overlaps(it) || plan.map.overlaps(it) })
            }
        }
    }

    @Test
    fun combinedFloatingLayoutLetsMapAndTowerShareWorkspaceWithoutCoveringVideo() {
        for ((width, height) in listOf(1080f to 2400f, 2400f to 1080f, 1280f to 720f, 720f to 1280f)) {
            for (feedCount in 1..4) {
                val plan = Layouts.withCombinedOverlays(
                    LayoutMode.FEATURED, feedCount, width, height, OverlayLayoutMode.FLOATING,
                )
                assertTrue(plan.floating)
                assertEquals(feedCount, plan.slots.size)
                assertEquals(plan.workspace, plan.tower)
                assertEquals(plan.workspace, plan.map)
                assertTrue(plan.slots.all { slot ->
                    slot.x >= plan.feeds.x - 0.5f && slot.y >= plan.feeds.y - 0.5f &&
                        slot.x + slot.w <= plan.feeds.x + plan.feeds.w + 0.5f &&
                        slot.y + slot.h <= plan.feeds.y + plan.feeds.h + 0.5f
                })
                assertTrue(plan.slots.none { plan.workspace.overlaps(it) })
            }
        }
    }

    @Test
    fun tvLayoutMatchesBroadcastStyleLeftVideoAndRightTimingSidebar() {
        val width = 1536f
        val height = 768f
        val plan = Layouts.withCombinedOverlays(
            LayoutMode.TV, 1, width, height, OverlayLayoutMode.DOCKED,
        )
        assertEquals(width * 0.66f, plan.feeds.w, 1f)
        assertEquals(height * 0.09375f, plan.feeds.y, 1f)
        assertEquals(height * 0.84375f, plan.feeds.h, 1f)
        assertEquals(0f, plan.feeds.x, 0.1f)
        assertEquals(plan.feeds.w, plan.workspace.x, 0.1f)
        assertEquals(width - plan.feeds.w, plan.workspace.w, 1f)
        assertEquals(0f, plan.tower.y, 0.1f)
        assertEquals(height * 0.65625f, plan.tower.h, 1f)
        assertEquals(plan.tower.y + plan.tower.h, plan.map.y, 0.1f)
        assertEquals(height * 0.28125f, plan.map.h, 1f)
        assertEquals(height * 0.0625f, height - plan.map.y - plan.map.h, 1f)
        assertTrue(!plan.tower.overlaps(plan.map))
        assertEquals(1, plan.slots.size)
        val video = plan.slots.single()
        assertEquals(0f, video.x, 0.1f)
        assertEquals(plan.feeds.y, video.y, 0.1f)
        assertEquals(plan.feeds.w, video.w, 1f)
        assertEquals(9f / 16f, video.h / video.w, 0.001f)
        assertTrue(video.x + video.w <= plan.feeds.x + plan.feeds.w + 1f)
    }

    @Test
    fun timingTowerReservationLeavesASeparateRectangle() {
        for (mode in listOf(LayoutMode.FEATURED, LayoutMode.GRID)) {
            for ((width, height) in listOf(w to h, 1920f to 1080f, 1080f to 1920f, 1280f to 720f)) {
                for (feedCount in 1..4) {
                    val towerWidth = minOf(380f, width - 32f)
                    val towerHeight = minOf(720f, height - 40f)
                    val arrangement = Layouts.withOverlays(
                        mode, feedCount, width, height, towerWidth, towerHeight,
                    )
                    val reservedTower = arrangement.tower
                    assertNotNull("$mode $width x $height: tower reservation missing", reservedTower)
                    val tower = reservedTower ?: throw AssertionError("Tower reservation was null")
                    assertTrue(tower.x >= 0f && tower.y >= 0f)
                    assertTrue(tower.x + tower.w <= width + 0.5f)
                    assertTrue(tower.y + tower.h <= height + 0.5f)
                    assertEquals(feedCount, arrangement.slots.size)
                    assertTrue("Tower overlaps a feed: $tower", arrangement.slots.none { tower.overlaps(it) })
                }
            }
        }
    }

    @Test
    fun mapAndTowerReservationsDoNotOverlapFeedsOrEachOther() {
        for (mode in listOf(LayoutMode.FEATURED, LayoutMode.GRID)) {
            for ((width, height) in listOf(w to h, 1920f to 1080f, 1080f to 1920f, 1280f to 720f)) {
                for (feedCount in 1..4) {
                    val towerWidth = minOf(380f, width - 32f)
                    val towerHeight = minOf(720f, height - 40f)
                    val minMapSide = minOf(width, height) * 0.22f
                    val arrangement = Layouts.withOverlays(
                        mode, feedCount, width, height, towerWidth, towerHeight, minMapSide,
                    )
                    val reservedTower = arrangement.tower
                    val reservedMap = arrangement.map
                    assertNotNull("$mode $width x $height: tower reservation missing", reservedTower)
                    assertNotNull("$mode $width x $height: map reservation missing", reservedMap)
                    val tower = reservedTower ?: throw AssertionError("Tower reservation was null")
                    val map = reservedMap ?: throw AssertionError("Map reservation was null")
                    assertTrue("Tower overlaps a feed: $tower", arrangement.slots.none { tower.overlaps(it) })
                    assertTrue("Map overlaps a feed: $map", arrangement.slots.none { map.overlaps(it) })
                    assertTrue("Map overlaps timing tower: $map / $tower", !map.overlaps(tower))
                    assertEquals(feedCount, arrangement.slots.size)
                    assertTrue(map.w >= minMapSide && map.h >= minMapSide)
                }
            }
        }
    }
}
