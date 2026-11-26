package app.apexview.tiled

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactNumberCompatTest {
    @Test fun signedLongBoundariesAndOverflow() {
        val min = BigInteger.valueOf(Long.MIN_VALUE)
        val max = BigInteger.valueOf(Long.MAX_VALUE)
        for (value in longArrayOf(Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE)) {
            assertEquals(value, BigInteger.valueOf(value).longValueExactCompat())
            assertEquals(value, BigDecimal.valueOf(value).longValueExactCompat())
        }
        for (value in listOf(min.subtract(BigInteger.ONE), max.add(BigInteger.ONE))) {
            assertTrue(runCatching { value.longValueExactCompat() }.exceptionOrNull() is ArithmeticException)
            assertTrue(runCatching { BigDecimal(value).longValueExactCompat() }.exceptionOrNull() is ArithmeticException)
        }
    }

    @Test fun integralDecimalsWithScaleAndFractionalRejection() {
        assertEquals(1200L, BigDecimal("1.2E+3").longValueExactCompat())
        assertEquals(-42L, BigDecimal("-42.000").longValueExactCompat())
        for (text in listOf("0.1", "-0.1", "9223372036854775807.1", "-9223372036854775808.1")) {
            assertTrue(runCatching { BigDecimal(text).longValueExactCompat() }.exceptionOrNull() is ArithmeticException)
        }
    }
}
