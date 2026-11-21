package app.apexview

import org.junit.Assert.*
import org.junit.Test

class OverlayPositionSamplerTest {
    private val times = longArrayOf(1_000L, 1_200L, 1_400L, 6_600L, 6_900L, 7_200L)
    private val xs = floatArrayOf(0f, 10f, 20f, 30f, 50f, 80f)
    private val ys = floatArrayOf(0f, 4f, 8f, 12f, 20f, 32f)

    @Test fun sequentialFramesAndBackwardSeekMatchLegacyInterpolationAndGapRules() {
        val source = DriverTrack(times, xs, ys, times.size)
        val cursor = OverlayPositionCursor(source)
        val moments = listOf(900L, 1_000L, 1_001L, 1_010L, 1_133L, 1_200L,
            1_299L, 1_400L, 1_500L, 2_000L, 6_599L, 6_600L, 6_750L,
            7_199L, 7_200L, 7_201L, 1_100L, 6_900L, 1_000L, 1_300L)
        moments.forEach { moment ->
            val expected = source.atOrFirst(moment)
            val available = cursor.sample(moment)
            assertEquals("availability at $moment", expected != null, available)
            if (expected != null) {
                assertEquals("x at $moment", expected.first, cursor.x, 0.00001f)
                assertEquals("y at $moment", expected.second, cursor.y, 0.00001f)
            }
        }
        assertTrue("backward seek was not indexed", cursor.seekCount >= 2)
    }

    @Test fun denseFiftyFpsFramesAgreeWithLegacyIncludingSubsecondPositions() {
        val source = DriverTrack(times, xs, ys, times.size)
        val cursor = OverlayPositionCursor(source)
        for (moment in 800L..7_300L step 20L) {
            val expected = source.atOrFirst(moment)
            val actual = cursor.sample(moment)
            assertEquals("availability $moment", expected != null, actual)
            if (expected != null) {
                assertEquals(expected.first, cursor.x, 0.00001f)
                assertEquals(expected.second, cursor.y, 0.00001f)
            }
        }
    }

    @Test fun emptyAndSingleSampleSourcesPreserveExactBoundaries() {
        val empty = OverlayPositionCursor(DriverTrack(longArrayOf(), floatArrayOf(), floatArrayOf(), 0))
        assertFalse(empty.sample(0))
        val one = OverlayPositionCursor(DriverTrack(longArrayOf(500), floatArrayOf(9f), floatArrayOf(3f), 1))
        assertTrue(one.sample(400))
        assertEquals(9f, one.x, 0f)
        assertTrue(one.sample(500))
        assertFalse(one.sample(501))
        assertTrue(one.sample(499))
    }

    private fun timing() = SessionTiming(
        type = "Qualifying",
        drivers = mapOf("1" to DriverInfo("1", "VER", 0xFF00AAFF.toInt()),
            "44" to DriverInfo("44", "HAM", 0xFF00DD99.toInt())),
        tracks = linkedMapOf(
            "1" to DriverTrack(times, xs, ys, times.size),
            "44" to DriverTrack(times, xs.map { it + 4f }.toFloatArray(),
                ys.map { it - 2f }.toFloatArray(), times.size),
        ),
        states = mapOf("1" to StateLog(longArrayOf(1_050, 6_800),
            arrayOf(LapState.PUSH, LapState.COOL_DOWN))),
        outline = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f),
        hasStartFinish = true, corners = emptyList(), startUtcMs = 1_000L,
    )

    @Test fun immutableSharedSnapshotsExactlyMatchExistingMapAndCastSenderState() {
        val t = timing()
        val probe = OverlayPositionSampler(t)
        for (now in listOf(900L, 1_000L, 1_100L, 1_400L, 6_700L,
            7_200L, 1_200L, 6_699L, 7_201L)) {
            val prior = carsAt(t, now)
            val current = probe.sample(now)
            assertEquals("driver order $now", prior.map { it.number }, current.map { it.number })
            prior.zip(current).forEach { (expected, actual) ->
                assertEquals(expected.x, actual.x, 0.00001f)
                assertEquals(expected.y, actual.y, 0.00001f)
                assertEquals(expected.state, actual.state)
            }
            assertSame("same timestamp must not rebuild frame", current, probe.sample(now))
        }
    }

    @Test fun stationaryPositionsReUseCarObjectsWithoutSharingMutableCoordinates() {
        val t = timing()
        val sampler = OverlayPositionSampler(t)
        val a = sampler.sample(800)
        val b = sampler.sample(900)
        assertNotSame(a, b)
        assertSame(a[0], b[0])
        assertSame(a[1], b[1])
        assertEquals(2, b.size)
    }
}
