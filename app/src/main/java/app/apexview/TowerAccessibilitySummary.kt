package app.apexview

/**
 * One useful accessibility node per timing row, independent of fast-changing gear,
 * speed, sector-strip fragments and draw frames. The visible cells remain live.
 * The row itself stays actionable for TalkBack and D-pad users.
 */
internal object TowerAccessibilitySummary {
    fun prepare(name: String, state: CardState, tyre: Tyre?): String = buildString {
        append("Position ${state.position ?: "unknown"}, $name.")
        when {
            state.retired -> append(" Retired.")
            state.stopped -> append(" Stopped.")
            state.inPit -> append(" In pit.")
        }
        state.lastLap?.time?.takeIf { it.isNotBlank() }?.let { append(" Last lap $it.") }
        state.bestLap?.time?.takeIf { it.isNotBlank() }?.let { append(" Best lap $it.") }
        state.ahead?.takeIf { it.isNotBlank() }?.let { append(" Interval $it.") }
        state.toLeader?.takeIf { it.isNotBlank() }?.let { append(" To leader $it.") }
        tyre?.let { append(" ${it.compound}, ${it.laps} laps.") }
    }
}
