package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TowerEditTest {
    private val sidebar = Area(660f, 0f, 340f, 600f)

    @Test fun heightGripUsesTheWholeSidebarAndKeepsWidth() {
        val tower = Area(680f, 300f, 300f, 300f)
        val resized = tower.adjustTower(TowerEdit.HEIGHT, 120f, 800f, sidebar, 280f, 180f, 340f, 600f)
        assertEquals(Area(680f, 0f, 300f, 600f), resized)
    }

    @Test fun moveKeepsTheSizeAndClampsAtTheWorkspaceEdges() {
        val tower = Area(680f, 140f, 300f, 300f)
        val moved = tower.adjustTower(TowerEdit.MOVE, -500f, 500f, sidebar, 280f, 180f, 340f, 600f)
        assertEquals(Area(660f, 300f, 300f, 300f), moved)
    }

    @Test fun feedReservationKeepsTheTowerAtItsChosenPositionWhenThereIsRoom() {
        val chosen = Area(660f, 105f, 340f, 450f)
        val arrangement = Layouts.withOverlays(LayoutMode.FEATURED, 2, 1000f, 600f,
            chosen.w, chosen.h, preferredTower = chosen)
        assertEquals(chosen, arrangement.tower)
        arrangement.slots.forEach { assertFalse(chosen.overlaps(it)) }
    }
}
