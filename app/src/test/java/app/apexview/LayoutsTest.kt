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
