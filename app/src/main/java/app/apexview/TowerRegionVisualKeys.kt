package app.apexview

/**
 * One canonical dependency list for the three separately retained tower
 * display lists. A change in only one region must not re-record the others.
 */
internal data class TowerRegionVisualKeys(
    val base: Base,
    val sectors: Sector,
    val tyre: TyreRegion,
) {
    internal data class Base(
        val name: String, val position: String, val positionChange: Int?,
        val retired: Boolean, val inPit: Boolean, val selected: Boolean,
        val teamColor: Int?, val gear: String, val speed: String,
        val lastLap: String, val bestLap: String, val interval: String,
        val leader: String, val lastPace: Pace?, val bestPace: Pace?,
        val density: Float, val fontScale: Float,
    )
    internal data class Sector(
        val cells: List<SectorCell?>, val density: Float, val fontScale: Float,
    )
    internal data class TyreRegion(
        val compound: String?, val laps: Int?, val density: Float, val fontScale: Float,
    )
}

internal fun towerRegionVisualKeys(
    driver: DriverInfo?, state: CardState, prepared: TowerRowText,
    tyre: Tyre?, highlight: Boolean, density: Float, fontScale: Float,
): TowerRegionVisualKeys = TowerRegionVisualKeys(
    base = TowerRegionVisualKeys.Base(
        name = prepared.name, position = prepared.position,
        positionChange = state.positionChange,
        retired = state.retired, inPit = state.inPit, selected = highlight,
        teamColor = driver?.color, gear = prepared.gearText, speed = prepared.speed,
        lastLap = prepared.lastLap, bestLap = prepared.bestLap,
        interval = prepared.interval, leader = prepared.leader,
        lastPace = state.lastLap?.pace, bestPace = state.bestLap?.pace,
        density = density, fontScale = fontScale,
    ),
    sectors = TowerRegionVisualKeys.Sector(
        prepared.sectors, density, fontScale,
    ),
    tyre = TowerRegionVisualKeys.TyreRegion(
        tyre?.compound, tyre?.laps, density, fontScale,
    ),
)
