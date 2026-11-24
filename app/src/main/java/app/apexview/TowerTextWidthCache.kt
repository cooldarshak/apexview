package app.apexview

/**
 * Bounded per-row cache for native Canvas text measurement. A timing tower redraw can
 * use the same labels many times per second even when its data has not changed.
 * Typeface, size and text are all part of the key; no cached width survives a style change.
 */
internal class TowerTextWidthCache(private val capacity: Int = 96) {
    private data class Key(val text: String, val size: Float, val bold: Boolean)

    init { require(capacity > 0) }

    private val widths = object : LinkedHashMap<Key, Float>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Float>): Boolean = size > capacity
    }

    fun width(text: String, size: Float, bold: Boolean, measure: () -> Float): Float =
        widths.getOrPut(Key(text, size, bold), measure)

    internal fun entries(): Int = widths.size
}
