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

    @Test fun droppedLeadingDriverDoesNotInvalidateUnchangedFollowingDriver() {
        // A source track that ends must not shift the identity cache for the
        // surviving driver. The former implementation indexed a compacted
        // result list using un-compacted source indices.
        val source = SessionTiming(
            type = "Race",
            drivers = emptyMap(),
            tracks = linkedMapOf(
                "1" to DriverTrack(longArrayOf(1_000, 1_200),
                    floatArrayOf(1f, 1f), floatArrayOf(2f, 2f), 2),
                "44" to DriverTrack(longArrayOf(1_000, 3_000),
                    floatArrayOf(10f, 10f), floatArrayOf(20f, 20f), 2),
            ),
            states = emptyMap(),
            outline = floatArrayOf(),
            hasStartFinish = false, corners = emptyList(), startUtcMs = 1_000L,
        )
        val sampler = OverlayPositionSampler(source)
        assertEquals(listOf("1", "44"), sampler.sample(1_100).map { it.number })
        val afterRetirement = sampler.sample(1_500)
        assertEquals(listOf("44"), afterRetirement.map { it.number })
        val unchanged = sampler.sample(1_600)
        assertSame("stationary B car must not be recreated after A disappears",
            afterRetirement.single(), unchanged.single())
        assertEquals(carsAt(source, 1_600).single().x, unchanged.single().x, 0f)
    }

    @Test fun independentUiAndDrawTimelinesMatchLegacyDuringInterleavedClockUpdates() {
        val t = timing()
        val hitTargets = OverlayPositionSampler(t)
        val motion = OverlayPositionSampler(t)
        repeat(90) { frame ->
            val drawNow = 1_050L + frame * 20L
            val hitNow = drawNow + 35L
            val hit = hitTargets.sample(hitNow)
            val drawn = motion.sample(drawNow)
            assertEquals(carsAt(t, hitNow).map { it.number to it.x },
                hit.map { it.number to it.x })
            assertEquals(carsAt(t, drawNow).map { it.number to it.y },
                drawn.map { it.number to it.y })
        }
        assertEquals("draw sampling remains cached at an unchanged clock",
            motion.sample(2_830L), motion.sample(2_830L))
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
    @Test fun drawOnlyProjectionReusesMarkersAndPreservesImmutablePublicSnapshotsOnSeeking() {
        val t = timing()
        val drawSampler = OverlayPositionSampler(t)
        val publishedSampler = OverlayPositionSampler(t)
        val first = drawSampler.sampleForDraw(1_100L)
        val marker = first.first { it.number == "1" }
        val mutableList = first
        assertEquals(carsAt(t, 1_100L).map { it.number }, first.map { it.number })
        assertSame(first, drawSampler.sampleForDraw(1_100L))
        val frozen = publishedSampler.sample(1_100L)
        val oldX = frozen.first().x
        for (at in listOf(1_120L, 1_500L, 6_600L, 7_200L, 900L, 1_300L)) {
            val drawn = drawSampler.sampleForDraw(at)
            val expected = carsAt(t, at)
            assertSame("one draw list per session", mutableList, drawn)
            assertEquals("source order at $at", expected.map { it.number }, drawn.map { it.number })
            expected.zip(drawn).forEach { (actual, reused) ->
                assertEquals(actual.x, reused.x, 0.00001f)
                assertEquals(actual.y, reused.y, 0.00001f)
                assertEquals(actual.state, reused.state)
            }
        }
        assertSame("each moving car retains its marker object", marker,
            drawSampler.sampleForDraw(1_100L).first { it.number == "1" })
        assertEquals("10Hz/receiver snapshot cannot be mutated by Canvas", oldX, frozen.first().x, 0f)
        assertSame(frozen, publishedSampler.sample(1_100L))
    }

    @Test fun twentyFourDriverDrawProjectionRequiresNoPerFrameCarCreation() {
        val tracks = linkedMapOf<String, DriverTrack>()
        repeat(24) { number ->
            tracks[number.toString()] = DriverTrack(
                longArrayOf(1_000L, 2_000L), floatArrayOf(number.toFloat(), number + 40f),
                floatArrayOf(number.toFloat(), number + 80f), 2)
        }
        val timing = SessionTiming(type = "Race", drivers = emptyMap(), tracks = tracks,
            states = emptyMap(), outline = floatArrayOf(), hasStartFinish = false,
            corners = emptyList(), startUtcMs = 1_000L)
        val sampler = OverlayPositionSampler(timing)
        val original = sampler.sampleForDraw(1_050L).toList()
        for (i in 0 until 50) {
            val frame = sampler.sampleForDraw(1_051L + 19L * i)
            assertEquals(24, frame.size)
            repeat(24) { j -> assertSame(original[j], frame[j]) }
        }
        sampler.sampleForDraw(3_000L)
        assertEquals(0, sampler.sampleForDraw(3_000L).size)
        assertEquals(24, sampler.sampleForDraw(1_500L).size)
        repeat(24) { j -> assertSame(original[j], sampler.sampleForDraw(1_500L)[j]) }
    }

    @Test fun pausedLiveClockPublishesNewPositionsWhenMetadataArrivesAtSameTime() {
        val t = SessionTiming(type = "Race", drivers = emptyMap(),
            tracks = mapOf("44" to DriverTrack(
                longArrayOf(1_000L, 2_000L),
                floatArrayOf(10f, 20f), floatArrayOf(30f, 40f), 2)),
            states = emptyMap(), outline = floatArrayOf(), hasStartFinish = false,
            corners = emptyList(), startUtcMs = 1_000L, live = true)
        val sampler = OverlayPositionSampler(t)
        // Live position history has not yet reached this paused video clock.
        t.positionsFromUtc = 2_000_000L
        assertTrue(sampler.sampleForDraw(1_100L).isEmpty())
        // A new live/archive update supplies history without advancing playback.
        t.positionsFromUtc = null
        val visible = sampler.sampleForDraw(1_100L)
        assertEquals(listOf("44"), visible.map { it.number })
        t.positionsFromUtc = 2_000_000L
        assertTrue("stale dots must disappear at the same playhead", sampler.sampleForDraw(1_100L).isEmpty())
    }

}
