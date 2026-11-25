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

/** Exact signed conversion, preserving truncation toward zero and overflow rejection
 * without BigInteger.longValueExact (not available on Android API 29).
 */
internal fun scaleHevcMicrosToTicks(micros: Long, scale: Long): Long {
    require(scale > 0L)
    val value = java.math.BigInteger.valueOf(micros)
        .multiply(java.math.BigInteger.valueOf(scale))
        .divide(java.math.BigInteger.valueOf(1_000_000L))
    if (value < java.math.BigInteger.valueOf(Long.MIN_VALUE) ||
        value > java.math.BigInteger.valueOf(Long.MAX_VALUE)) throw ArithmeticException("HEVC time conversion overflow")
    return value.toLong()
}
