package app.apexview.tiled

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Exact signed 64-bit conversion on Android 10 and newer.
 * BigInteger.longValueExact() is not available on every supported Android API.
 * bitLength includes the signed representation rule (Long.MIN_VALUE is valid).
 */
internal fun BigInteger.longValueExactCompat(): Long {
    if (bitLength() > 63) throw ArithmeticException("BigInteger out of long range")
    return toLong()
}

/** Retain fractional-value rejection as well as the exact range check. */
internal fun BigDecimal.longValueExactCompat(): Long =
    toBigIntegerExact().longValueExactCompat()
