package app.apexview.tiled

/**
 * Reduce positive media-clock ratios exactly on all supported Android APIs.
 * Euclid uses only positive Long remainders; no multiplication can overflow.
 * Reject ratios that cannot be represented by the Int composer interface.
 */
internal fun reducePositiveHevcFrameRate(numerator: Long, denominator: Long): Pair<Int, Int> {
    require(numerator > 0L && denominator > 0L)
    var a = numerator
    var b = denominator
    while (b != 0L) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return Math.toIntExact(numerator / a) to Math.toIntExact(denominator / a)
}
