package app.apexview

/** Independent sizing for the small speed badge over an onboard feed. */
internal object SpeedReadoutScale {
    const val MIN = 0.45f
    const val MAX = 1.25f
    const val DEFAULT = 0.70f

    fun clamp(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN, MAX) else DEFAULT
}
