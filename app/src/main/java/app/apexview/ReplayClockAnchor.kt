package app.apexview

/** A program clock observed on a previous playback of the same archive feed and delivery route. */
internal data class ReplayClockAnchor(val baseMs: Long, val observedAtMs: Long) {
    fun encode() = "$baseMs/$observedAtMs"

    companion object {
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        fun read(value: String, nowMs: Long = System.currentTimeMillis()): ReplayClockAnchor? {
            val separator = value.indexOf('/')
            if (separator < 0) return null
            val base = value.substring(0, separator).toLongOrNull() ?: return null
            val observed = value.substring(separator + 1).toLongOrNull() ?: return null
            if (base <= 0 || observed !in (nowMs - MAX_AGE_MS)..nowMs) return null
            return ReplayClockAnchor(base, observed)
        }
    }
}
