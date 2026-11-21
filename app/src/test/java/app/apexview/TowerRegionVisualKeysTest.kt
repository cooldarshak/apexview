package app.apexview

import org.junit.Assert.*
import org.junit.Test

class TowerRegionVisualKeysTest {
    private val driver = DriverInfo("44", "HAM", 0xFF00CCFF.toInt())
    private val state = CardState(
        position = 3,
        sectors = listOf(
            SectorCell("", Pace.NONE, listOf(2049, 0, 0)),
            SectorCell("28.035", Pace.GREEN, listOf(2048, 2049, 0)),
            null,
        ), lapSectors = emptyList(), runningMs = null,
        lastLap = SectorCell("1:23.900", Pace.GREEN),
        gap = "+1.500", inPit = false, toLeader = "+1.500",
        ahead = "+0.670", bestLap = SectorCell("1:22.100", Pace.PURPLE)
    )
    private val tyre = Tyre("SOFT", 4)
    private fun keys(
        state: CardState = this.state, tyre: Tyre? = this.tyre,
        speed: Int = 267, gear: Int = 7, highlighted: Boolean = false
    ): TowerRegionVisualKeys = towerRegionVisualKeys(
        driver, state,
        TowerRowText.prepare(driver.tla, state, tyre, speed, gear, Settings.KMH),
        tyre, highlighted, 2f, 1f
    )
    @Test fun speedAndGearChangesInvalidateOnlyTheBaseRegion() {
        val original = keys()
        val later = keys(speed = 271, gear = 8)
        assertNotEquals(original.base, later.base)
        assertEquals(original.sectors, later.sectors)
        assertEquals(original.tyre, later.tyre)
    }
    @Test fun miniSectorAndSectorColourChangesInvalidateOnlyTheSectorRegion() {
        val original = keys()
        val updated = keys(state.copy(sectors = listOf(
            SectorCell("", Pace.NONE, listOf(2049, 2048, 2048)),
            state.sectors[1], null
        )))
        assertEquals(original.base, updated.base)
        assertNotEquals(original.sectors, updated.sectors)
        assertEquals(original.tyre, updated.tyre)
    }
    @Test fun tyreAgeAndCompoundDoNotReRecordOtherColumns() {
        val original = keys()
        val later = keys(tyre = Tyre("MEDIUM", 5))
        assertEquals(original.base, later.base)
        assertEquals(original.sectors, later.sectors)
        assertNotEquals(original.tyre, later.tyre)
    }
    @Test fun selectionAndPositionChangeCannotReuseStaleBaseLayer() {
        val original = keys()
        val selected = keys(highlighted = true)
        assertNotEquals(original.base, selected.base)
        val changedPlace = keys(state.copy(position = 2, positionChange = 1))
        assertNotEquals(original.base, changedPlace.base)
        assertEquals(original.sectors, changedPlace.sectors)
        assertEquals(original.tyre, changedPlace.tyre)
    }
    @Test fun identicalNewObjectsReuseEveryRegionKey() {
        val first = keys()
        val other = keys(
            state.copy(sectors = state.sectors.toList()),
            tyre = Tyre("SOFT", 4),
        )
        assertEquals(first, other)
    }
}
