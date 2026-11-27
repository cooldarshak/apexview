package app.apexview.tiled

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.InterruptedIOException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ArchitectureGBAndroidTest {
    @Test
    fun compactArchiveTimestampsRetainExactMapAndTowerSeekSemantics() {
        assertTrue(NativeHotPath.available())
        val base = 1_800_000_000_123L
        val times = LongArray(4101) { base + it * 137L + if (it >= 2048) 6000L else 0L }
        val xs = FloatArray(times.size) { it.toFloat() }
        val ys = FloatArray(times.size) { it * 2f }
        val pool = app.apexview.SpeedTimestampPool(true)
        val compact = pool.freezeTimeline(times, times.size)
        assertTrue(compact.handle > 0L)
        assertNull(compact.array)
        assertTrue(compact === pool.freezeTimeline(times.copyOf(), times.size))
        assertEquals(times.last(), compact[times.lastIndex])
        val packedPoints = pool.freezeCoordinates(xs, ys, times.size)
        assertTrue(packedPoints.handle > 0)
        assertNull(packedPoints.xArray)
        assertNull(packedPoints.yArray)
        assertEquals(xs[256], packedPoints.xAt(256), 0f)
        assertEquals(ys.last(), packedPoints.yAt(times.lastIndex), 0f)
        val track = app.apexview.DriverTrack(compact, packedPoints, times.size)
        val reference = app.apexview.OverlayPositionCursor(app.apexview.DriverTrack(times, xs, ys, times.size))
        val packedReference = app.apexview.OverlayPositionCursor(track)
        val arrays = arrayOfNulls<LongArray>(1)
        val handles = longArrayOf(compact.handle)
        val counts = intArrayOf(times.size)
        val cursor = longArrayOf(-1L, Long.MIN_VALUE, 0L)
        val xy = FloatArray(2); val active = IntArray(1); val indices = intArrayOf(-1)
        for (now in listOf(base - 10, base, base + 400, times[2047] + 100, times[2048],
            times.last(), times.last() + 1, times[10])) {
            val expected = reference.sample(now)
            assertEquals(expected, packedReference.sample(now))
            assertTrue(NativeHotPath.samplePositionsPacked(arrays, arrayOfNulls<FloatArray>(1), arrayOfNulls<FloatArray>(1),
                counts, handles, longArrayOf(packedPoints.handle), now, cursor, xy, active))
            assertEquals(expected, active[0] == 1)
            if (expected) {
                assertEquals(reference.x, xy[0], 0.00001f)
                assertEquals(reference.y, xy[1], 0.00001f)
                assertEquals(reference.x, packedReference.x, 0.00001f)
                assertEquals(reference.y, packedReference.y, 0.00001f)
            }
            assertTrue(NativeHotPath.floorIndicesWithCompactTimes(arrays, counts, handles, now, indices))
            assertEquals(times.indexOfLast { it <= now }, indices[0])
        }
        val wide = LongArray(64) { base + it * 100_000_000L }
        assertNull(NativeHotPath.compactTimes(wide, wide.size))
        val speeds = app.apexview.SpeedBuilder().apply {
            times.forEachIndexed { i, time -> add(time, 200 + i % 80, i % 9) }
        }.build(pool)
        assertTrue(speeds.speedTimeline === compact)
        assertTrue(speeds.gearTimeline === compact)
        assertNull(speeds.speedValues.array)
        assertNull(speeds.gearValues.array)
        assertTrue(speeds.speedValues.native != null && speeds.gearValues.native != null)
        for (i in listOf(0, 1, 7, 8, 1023, 1024, times.lastIndex)) {
            assertEquals(200 + i % 80, speeds.kmhAt(times[i]))
            assertEquals(i % 9, speeds.gearAt(times[i]))
        }
        assertNull(NativeHotPath.compactTelemetry(ShortArray(64) { 501 }, 64, 9))
        assertEquals(200 + 4100 % 80, speeds.kmhAt(times.last()))
        assertEquals(4100 % 9, speeds.gearAt(times.last()))
        assertNull(speeds.kmhAt(times.last() + 5001))
        java.lang.ref.Reference.reachabilityFence(compact)
        java.lang.ref.Reference.reachabilityFence(packedPoints)
    }

    @Test
    fun nativeRetirementStatusMatchesReferenceAndHistoricalParser() {
        assertTrue(NativeHotPath.available())
        for (flags in 0..127) {
            assertEquals(app.apexview.RetirementStatusPolicy.reference(flags),
                NativeHotPath.retirementState(flags))
        }
        assertEquals(-1, NativeHotPath.retirementState(128))
        val data = listOf(
            """00:00:01.000{"Lines":{"4":{"NumberOfLaps":3}}}""",
            """00:00:02.000{"Lines":{"4":{"Retired":true}}}""",
            """00:00:03.000{"Lines":{"4":{"Retired":false}}}""",
            """00:00:04.000{"Lines":{"4":{"Sectors":{"0":{"Value":"29.100"}}}}}""",
            """00:00:05.000{"Lines":{"4":{"Retired":true}}}"""
        ).joinToString("\n")
        val reference = app.apexview.DriverTimingParser.parse(data, 0).getValue("4")
        val native = app.apexview.DriverTimingParser.parse(data, 0, nativeRequested = true).getValue("4")
        for (t in listOf(2500L, 3500L, 4500L, 5500L, 2500L)) {
            assertEquals(reference.at(t), native.at(t))
        }
        assertTrue(native.retiredAt(3500))
        assertFalse(native.retiredAt(4500))
        assertTrue(native.retiredAt(5500))
    }

    @Test
    fun nativeOverlayAndAudioKernelsMatchKotlinReferences() {
        assertTrue(NativeHotPath.available())
        val times = longArrayOf(1000, 1200, 1400, 6600, 6900, 7200)
        val xs = floatArrayOf(0f, 10f, 20f, 30f, 50f, 80f)
        val ys = floatArrayOf(0f, 4f, 8f, 12f, 20f, 32f)
        val track = app.apexview.DriverTrack(times, xs, ys, times.size)
        val reference = app.apexview.OverlayPositionCursor(track)
        val histories = arrayOf(times)
        val x = arrayOf(xs); val y = arrayOf(ys)
        val counts = intArrayOf(times.size)
        val cursor = longArrayOf(-1L, Long.MIN_VALUE, 0L)
        val xy = FloatArray(2); val active = IntArray(1)
        val moments = (800L..7400L step 20L).toList() + listOf(1000L, 7000L, 1200L, 6600L)
        for (now in moments) {
            assertTrue(NativeHotPath.samplePositions(histories,x,y,counts,now,cursor,xy,active))
            val present = reference.sample(now)
            assertEquals(present, active[0] == 1)
            if (present) {
                assertEquals(reference.x,xy[0],0.00001f)
                assertEquals(reference.y,xy[1],0.00001f)
            }
            assertEquals(reference.seekCount,cursor[2])
        }
        assertFalse(NativeHotPath.samplePositions(histories,x,y,intArrayOf(times.size+1),
            1000,cursor,xy,active))
        val out = IntArray(2)
        assertTrue(NativeHotPath.floorIndices(arrayOf(longArrayOf(10,10,30), longArrayOf()),
            intArrayOf(3,0),10,out))
        assertArrayEquals(intArrayOf(1,-1),out)
        // A full tower contributes five histories per driver plus weather.
        // Exercise beyond the previous 64-driver bound, including stale hints.
        val allTopics = Array(321) { i -> longArrayOf(i.toLong(), i + 10L, i + 10L, i + 30L) }
        val topicCounts = IntArray(allTopics.size) { 4 }
        val topicIndices = IntArray(allTopics.size) { Int.MAX_VALUE }
        for (now in longArrayOf(0, 200, 500, 15, -1)) {
            assertTrue(NativeHotPath.floorIndices(allTopics, topicCounts, now, topicIndices))
            for (i in allTopics.indices) {
                assertEquals(allTopics[i].indexOfLast { it <= now }, topicIndices[i])
            }
        }
        assertFalse(NativeHotPath.floorIndices(Array(322) { longArrayOf() },
            IntArray(322), 0, IntArray(322)))
        val order = IntArray(5)
        assertTrue(NativeHotPath.rowOrder(intArrayOf(3,1,99,1,2),5,order))
        assertArrayEquals(intArrayOf(1,3,4,0,2),order)
        val avcc = byteArrayOf(0,0,0,2,0x65,0x11,0,0,0,2,0x06,0x12)
        val prefix = byteArrayOf(0,0,0,1,0x67,0x11)
        val padded = ByteBuffer.allocateDirect(avcc.size + 9)
        padded.position(5); padded.put(avcc); padded.position(1)
        val expectedAvc = prefix + app.apexview.scene.BakedAvcAccessUnit.annexB(avcc)
        var actualNative = false
        val actualAvc = app.apexview.scene.BakedAvcAccessUnit.copyAnnexB(
            padded,5,avcc.size,prefix,true) { actualNative = it }
        assertTrue(actualNative)
        assertArrayEquals(expectedAvc, actualAvc)
        assertEquals(1,padded.position())
        assertArrayEquals(expectedAvc, app.apexview.scene.BakedAvcAccessUnit.copyAnnexB(
            padded,5,avcc.size,prefix,false))
        val badAvc = ByteBuffer.allocateDirect(5).put(byteArrayOf(0,0,0,2,0x65))
        val untouched = ByteArray(5) { 9 }
        assertFalse(NativeHotPath.avcAnnexB(badAvc,0,5,untouched,0))
        assertArrayEquals(ByteArray(5) { 9 },untouched)
        assertFalse(NativeHotPath.avcAnnexB(padded,Int.MAX_VALUE,avcc.size,actualAvc,prefix.size))
        val source = byteArrayOf(1,2,3,4,5)
        val direct = ByteBuffer.allocateDirect(source.size).put(source)
        val framed = ByteArray(source.size+7)
        assertTrue(NativeHotPath.adts(direct,source.size,48000,2,framed))
        assertArrayEquals(app.apexview.cast.BakedAacAdts.wrap(source,48000,2),framed)
        assertFalse(NativeHotPath.adts(direct,source.size,12345,2,framed))
        val scaled = LongArray(1)
        // Independent fixed reference values, not an API-31-only BigInteger method.
        val inputs = longArrayOf(-1234567L, 0L, 1234567L, Long.MAX_VALUE, Long.MIN_VALUE)
        val expectedTicks = longArrayOf(-111111L, 0L, 111111L, 830103483316929822L, -830103483316929822L)
        for (i in inputs.indices) {
            val reference = java.math.BigInteger.valueOf(inputs[i])
                .multiply(java.math.BigInteger.valueOf(90_000))
                .divide(java.math.BigInteger.valueOf(1_000_000)).longValueExactCompat()
            assertEquals(expectedTicks[i], reference)
            assertTrue(NativeHotPath.scaleUs(inputs[i],90_000,scaled))
            assertEquals(expectedTicks[i],scaled[0])
        }
        val min = java.math.BigInteger.valueOf(Long.MIN_VALUE)
        val max = java.math.BigInteger.valueOf(Long.MAX_VALUE)
        val one = java.math.BigInteger.ONE
        assertEquals(Long.MIN_VALUE, min.longValueExactCompat())
        assertEquals(Long.MAX_VALUE, max.longValueExactCompat())
        assertTrue(runCatching { min.subtract(one).longValueExactCompat() }.exceptionOrNull() is ArithmeticException)
        assertTrue(runCatching { max.add(one).longValueExactCompat() }.exceptionOrNull() is ArithmeticException)
        assertEquals(Long.MIN_VALUE, java.math.BigDecimal(min).longValueExactCompat())
        assertEquals(Long.MAX_VALUE, java.math.BigDecimal(max).longValueExactCompat())
        assertTrue(runCatching { java.math.BigDecimal("1.5").longValueExactCompat() }.exceptionOrNull() is ArithmeticException)
        assertTrue(runCatching { java.math.BigDecimal(max.add(one)).longValueExactCompat() }.exceptionOrNull() is ArithmeticException)
        assertFalse(NativeHotPath.scaleUs(Long.MAX_VALUE,Long.MAX_VALUE,scaled))
    }

    @Test
    fun nativeCircuitAndReceiverInterpolationMatchKotlin() {
        assertTrue(NativeHotPath.available())
        val outline = floatArrayOf(0f,0f,100f,0f,120f,50f,100f,100f,0f,100f)
        val geometry = app.apexview.TrackGeometry(outline)
        val shared = requireNotNull(app.apexview.NativeCircuitPackets.acquire(geometry))
        assertTrue(shared.isReadOnly)
        assertTrue(shared === app.apexview.NativeCircuitPackets.acquire(geometry))
        val replacement = app.apexview.TrackGeometry(outline.copyOf())
        assertFalse(shared === app.apexview.NativeCircuitPackets.acquire(replacement))
        val first = app.apexview.NativeCircuitProjection(geometry)
        val second = app.apexview.NativeCircuitProjection(geometry)
        assertEquals(first.progress(50f, 0f)!!, second.progress(50f, 0f)!!, 0.00001f)
        val storage = ByteBuffer.allocateDirect(8 + 8 * outline.size)
        assertTrue(NativeHotPath.prepareGeometry(outline, storage))
        val packet = storage.asReadOnlyBuffer()
        val xy = floatArrayOf(0f,0f,50f,-2f,100f,50f,0f,80f,40f,100f,50f,50f)
        val fractions = FloatArray(xy.size / 2)
        val order = IntArray(fractions.size)
        assertTrue(NativeHotPath.projectGeometry(packet,xy,fractions.size,fractions,order))
        for (i in fractions.indices)
            assertEquals(geometry.progress(xy[2*i],xy[2*i+1]),fractions[i],0.00001f)
        val referenceOrder = fractions.indices.sortedBy { geometry.progress(xy[it*2],xy[it*2+1]) }.toIntArray()
        assertArrayEquals(referenceOrder,order)
        val point = FloatArray(4)
        for (i in -100..200) {
            val f = i / 100f
            assertTrue(NativeHotPath.pointAt(packet,f,point))
            assertArrayEquals(geometry.pointAt(f),point,0.0001f)
        }
        assertFalse(NativeHotPath.prepareGeometry(outline,ByteBuffer.allocateDirect(4)))
        assertFalse(NativeHotPath.projectGeometry(packet,xy,65,fractions,order))
        val moving = app.apexview.cast.CastTimingProtocol.CarMotion("1",0f,0f,10f,20f,
            app.apexview.LapState.UNKNOWN,listOf(5f to 30f, 50f to 10f))
        val points = FloatArray(14)
        floatArrayOf(0f,0f,10f,20f,5f,30f,50f,10f).copyInto(points)
        val coords = FloatArray(2)
        for (i in -10..50) {
            val steps = i/10f
            val reference = moving.at(steps)
            assertTrue(NativeHotPath.interpolateMotion(points,intArrayOf(4),steps,coords))
            assertEquals(reference.x,coords[0],0.00001f)
            assertEquals(reference.y,coords[1],0.00001f)
        }
        assertFalse(NativeHotPath.interpolateMotion(points,intArrayOf(8),1f,coords))
    }

    @Test
    fun nativeFlagsAudioAdmissionAndMetadataMatchReference() {
        assertTrue(NativeHotPath.available())
        val flags = app.apexview.TrackFlags(
            listOf(app.apexview.SectorFlag(20,2,1), app.apexview.SectorFlag(40,3,2),
                app.apexview.SectorFlag(60,0,0)),
            listOf(10L to 4, 80L to 1), listOf(30L),
            listOf(app.apexview.Resumption(50,100,"restart")), listOf(55L to 70L))
        for (now in (0L..120L).toList() + listOf(10L,55L,20L)) {
            val expected = flags.at(now)
            val actual = flags.at(now,true)
            assertEquals(expected.track,actual.track)
            assertEquals(expected.yellow,actual.yellow)
            assertEquals(expected.resumption,actual.resumption)
        }
        assertEquals(30L,requireNotNull(NativeHotPath.audioAdmit(longArrayOf(1,10,2,20),2,0,30,2)))
        assertTrue(requireNotNull(NativeHotPath.audioAdmit(longArrayOf(1,10,1,20),2,0,30,2)) < 0)
        assertTrue(requireNotNull(NativeHotPath.audioAdmit(longArrayOf(1,10,2,20),2,0,29,2)) < 0)
        fun words(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size*4)
            .also { b -> values.forEach { b.putInt(it) } }.array()
        val parts = arrayOf(words(0x020008,7,1024),words(0x01000000,0,48000),
            words(0x01000a01,2,100,256,-1,192,0))
        val result = LongArray(4)
        assertTrue(NativeHotPath.audioMetadata(parts,longArrayOf(-1,-1,-1),result))
        assertArrayEquals(longArrayOf(7,48000,2048,2),result)
        val truncated = parts.copyOf()
        truncated[2] = truncated[2].copyOf(truncated[2].size-1)
        assertFalse(NativeHotPath.audioMetadata(truncated,longArrayOf(-1,-1,-1),result))
        val input = IntArray(192)
        input[0] = 2048; input[1] = 2049; input[64] = 2051
        val padded = IntArray(192) { -1 }
        assertTrue(NativeHotPath.normalizeSectors(input, intArrayOf(2,5,1,3,0,4), padded))
        assertArrayEquals(intArrayOf(2048,2049,0,0,0), padded.copyOfRange(0,5))
        assertArrayEquals(intArrayOf(2051,0,0), padded.copyOfRange(64,67))
        assertFalse(NativeHotPath.normalizeSectors(input,intArrayOf(2,65,1,3,0,4),padded))
        var requested = true
        val reference = app.apexview.cast.BakedCastTransportStream()
        val candidate = app.apexview.cast.BakedCastTransportStream { requested }
        repeat(20) { n ->
            requested = n % 3 != 1
            val base = n * 1_000_000L
            val video = ByteArray(160 + n * 2111) { it.toByte() }
            // One complete ADTS access unit, including a nonempty payload
            // and exact declared frame length, not only the sync marker.
            val rawAudio = ByteArray(maxOf(1, n * 17)) { it.toByte() }
            val audio = app.apexview.cast.BakedAacAdts.wrap(rawAudio, 48000, 2)
            val directAudio = ByteBuffer.allocateDirect(rawAudio.size).put(rawAudio)
            val nativeAudio = ByteArray(audio.size)
            assertTrue(NativeHotPath.adts(directAudio, rawAudio.size, 48000, 2, nativeAudio))
            assertArrayEquals(audio, nativeAudio)
            val samples = listOf(
                app.apexview.cast.BakedCastTransportStream.Sample(base,video,true,true),
                app.apexview.cast.BakedCastTransportStream.Sample(base,audio,false),
                app.apexview.cast.BakedCastTransportStream.Sample(base+20_000,audio,false),
            )
            // A rejected truncated unit must not advance either muxer's
            // continuity counters; the next valid segment must still match.
            val malformed = samples.toMutableList().apply {
                this[1] = this[1].copy(bytes = audio.copyOf(audio.size - 1))
            }
            assertTrue(runCatching { reference.segment(malformed) }.exceptionOrNull() is IllegalArgumentException)
            if (requested) {
                assertTrue(runCatching { candidate.nativeSegment(malformed, null) }.exceptionOrNull() is IllegalArgumentException)
            } else {
                assertTrue(runCatching { candidate.segment(malformed) }.exceptionOrNull() is IllegalArgumentException)
            }
            val expected = reference.segment(samples)
            if (requested) {
                requireNotNull(candidate.nativeSegment(samples,null)).use { assertArrayEquals(expected,it.copyBytes()) }
            } else {
                assertNull(candidate.nativeSegment(samples,null))
                assertArrayEquals(expected,candidate.segment(samples))
            }
        }
    }

    @Test
    fun stitchedTiledFragmentUsesNativeWorkerAndGenerationFencedLease() {
        verifyFrameRateArithmetic()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val input = assets.open("gb_clear_job.bin").use { it.readBytes() }
        val expected = assets.open("gb_clear_expected.bin").use { it.readBytes() }
        val budget = requireNotNull(NativeStitchingEngine.plan(
            true, 256L * 1024 * 1024, 32L * 1024 * 1024, 32L * 1024 * 1024,
            input.size, expected.size,
        ))
        NativeStitchingEngine.open(budget).use { engine ->
            val generation = engine.generation()
            val token = engine.submitClearAtlas(generation, input, true, 5_000)
            assertTrue("Clear atlas submission failed: $token", token > 0)
            assertEquals(expected.size.toLong(), await(engine, token))
            val actual = ByteArray(expected.size)
            assertEquals(expected.size.toLong(), engine.read(token, 0, actual, 0, actual.size))
            assertArrayEquals(expected, actual)
            assertEquals(generation + 1, engine.seek())
            assertEquals(NativeStitchingEngine.STALE, engine.read(token, 0, actual, 0, actual.size))
            assertEquals(1L, engine.release(token))

            val malformed = input.copyOf(input.size - 1)
            val rejected = engine.submitClearAtlas(engine.generation(), malformed, true, 5_000)
            assertTrue(rejected > 0)
            assertEquals(NativeStitchingEngine.INVALID, await(engine, rejected))
            assertEquals(1L, engine.release(rejected))
            Log.i("PitwallGB", "GB clear tiled worker byte parity and lease fencing PASS")
        }
        // Real encoded fixture with a deliberately undersized output slot.
        // A capacity-specific failure must not be published as valid output.
        val shortBudget = requireNotNull(NativeStitchingEngine.planClearAtlas(false,
            1024L*1024*1024, 32L*1024*1024, 128L*1024*1024, input.size, expected.size / 2))
        assertTrue(shortBudget.outputBytes < expected.size)
        NativeStitchingEngine.open(shortBudget).use { engine ->
            val failed = runCatching { NativeClearArchiveJob.execute(engine,input,true,5000) {} }.exceptionOrNull()
            assertTrue("Actual worker must report insufficient output capacity: $failed",
                failed is NativeClearArchiveJob.OutputCapacityException)
        }
        verifyBuild896TvAdmission(input, expected)
        verifyCompactTransfer(input, expected)
        verifyNativeRelayOwnership()
        verifyCastRouteIsolation()
        verifyArchiveBridge(input, expected)
    }

    /** Replay the historical planner observations through the actual Android JNI
     * worker and known-good media fixture. These are synthetic headroom inputs,
     * not proof of admission or performance on the physical TCL television. */
    private fun verifyBuild896TvAdmission(input: ByteArray, expected: ByteArray) {
        val observations = listOf(
            longArrayOf(5474084, 7002945, 621977600, 150994944, 303428224),
            longArrayOf(5226177, 6258463, 651563008, 150994944, 321931440),
            longArrayOf(5591105, 6324612, 619515904, 150994944, 268680776),
            longArrayOf(5221449, 6295460, 672854016, 150994944, 323391488),
        )
        val mib = 1024L * 1024
        for (v in observations) {
            val budget = requireNotNull(NativeStitchingEngine.planClearAtlas(false,
                v[2], v[3], v[4], v[0].toInt(), v[1].toInt()))
            assertTrue(budget.compactAtlas)
            assertTrue(budget.foregroundRecovery)
            assertTrue(input.size <= budget.inputBytes && expected.size <= budget.outputBytes)
            assertNull(NativeStitchingEngine.planClearAtlas(true,
                v[2], v[3], v[4], v[0].toInt(), v[1].toInt()))
            assertNull(NativeStitchingEngine.planClearAtlas(false,
                v[3] + 64 * mib, v[3], v[4], v[0].toInt(), v[1].toInt()))
            NativeStitchingEngine.open(budget).use { engine ->
                assertEquals(0L, engine.allocatedPayloadBytes())
                val token = engine.submitClearAtlasParts(engine.generation(),
                    arrayOf(input), input.size, true, 5000)
                assertTrue("TV admission vector failed JNI submission: $token", token > 0)
                assertEquals(expected.size.toLong(), await(engine, token))
                val actual = ByteArray(expected.size)
                assertEquals(expected.size.toLong(), engine.read(token, 0, actual, 0, actual.size))
                assertArrayEquals(expected, actual)
                assertEquals(1L, engine.release(token))
            }
        }
        Log.i("PitwallGB", "GB Build 896 four TV admission vectors and JNI media parity PASS")
    }

    private fun verifyCompactTransfer(input: ByteArray, expected: ByteArray) {
        val budget = requireNotNull(NativeStitchingEngine.planClearAtlas(false,
            1024L*1024*1024, 32L*1024*1024, 128L*1024*1024, input.size, expected.size))
        assertTrue(budget.compactAtlas)
        assertEquals(2L * (budget.inputBytes.toLong() + budget.outputBytes) + 576L * 1024,
            budget.reservedBytes)
        NativeStitchingEngine.open(budget).use { engine ->
            val generation = engine.generation()
            val parts = arrayOf(input.copyOfRange(0, 37), input.copyOfRange(37, input.size))
            assertEquals(NativeStitchingEngine.INVALID,
                engine.submitClearAtlasParts(generation, parts, input.size - 1, true, 5000))
            val token = engine.submitClearAtlasParts(generation, parts, input.size, true, 5000)
            assertTrue(token > 0)
            assertEquals(expected.size.toLong(), await(engine, token))
            val actual = ByteArray(expected.size + 2) { 91 }
            assertEquals(expected.size.toLong(), engine.read(token, 0, actual, 1, expected.size))
            assertEquals(91.toByte(), actual.first())
            assertEquals(91.toByte(), actual.last())
            assertArrayEquals(expected, actual.copyOfRange(1, actual.size - 1))
            val stats = engine.copyStats()
            assertEquals(0L, stats.javaInputBytes)
            assertEquals(input.size.toLong(), stats.nativeInputBytes)
            assertEquals(0L, stats.nativeOutputBytes)
            assertEquals(expected.size.toLong(), stats.javaOutputBytes)
            assertEquals(generation + 1, engine.seek())
            assertEquals(NativeStitchingEngine.STALE, engine.read(token, 0, actual, 1, expected.size))
            assertEquals(1L, engine.release(token))
            // Same real multi-picture fixture, after a generation change and slot reuse.
            val output = NativeClearArchiveJob.executeWindow(engine,
                NativeClearArchiveJob.Parts(parts, input.size), 1, true, 5000) {}
            try {
                val expectedSizes = java.nio.ByteBuffer.wrap(expected)
                val initSize = expectedSizes.int
                val mediaSize = expectedSizes.int
                assertEquals(initSize, output.initialization.size)
                assertEquals(mediaSize, output.media.single().size)
                assertArrayEquals(expected.copyOfRange(8, 8 + initSize), output.initialization)
                assertArrayEquals(expected.copyOfRange(8 + initSize, expected.size), output.media.single())
                assertTrue(output.submitNs >= 0 && output.waitNs >= 0 && output.readNs >= 0)
            } finally { output.wipe() }
            val directToken = engine.submitClearAtlasParts(engine.generation(), parts, input.size, true, 5000)
            assertTrue(directToken > 0)
            assertEquals(expected.size.toLong(), await(engine, directToken))
            val sizes = java.nio.ByteBuffer.wrap(expected)
            val initSize = sizes.int
            val mediaSize = sizes.int
            val expectedMedia = expected.copyOfRange(8 + initSize, expected.size)
            val before = engine.copyStats()
            val native = requireNotNull(NativeFragmentLease.fromEngine(mediaSize, null, engine, directToken, 8 + initSize))
            val cache = BoundedArchiveFragmentCache(maxEntries = 1)
            try {
                assertTrue(native.matches(expectedMedia))
                assertEquals(before.javaOutputBytes, engine.copyStats().javaOutputBytes)
                assertEquals(before.nativeOutputBytes + mediaSize, engine.copyStats().nativeOutputBytes)
                assertEquals(1L, engine.release(directToken))
                cache.putNative(1, native.retain())
                requireNotNull(cache.readLease(1)).use { reader ->
                    cache.put(2, byteArrayOf(7))
                    engine.seek()
                    cache.clear()
                    val target = ByteArray(mediaSize)
                    reader.readInto(target, 0, 0, mediaSize)
                    assertArrayEquals(expectedMedia, target)
                    val range = java.io.ByteArrayOutputStream()
                    reader.writeTo(range, 0, minOf(32, mediaSize))
                    assertArrayEquals(expectedMedia.copyOfRange(0, minOf(32, mediaSize)), range.toByteArray())
                }
            } finally { cache.clear(); native.close() }

            val ownedToken = engine.submitClearAtlasOwnedParts(engine.generation(), parts, input.size, true, 5000)
            assertTrue(ownedToken > 0)
            assertEquals(expected.size.toLong(), await(engine, ownedToken))
            val ownedBefore = engine.copyStats()
            val owned = requireNotNull(NativeFragmentLease.fromEngine(mediaSize, null, engine, ownedToken, 8 + initSize))
            try {
                assertTrue(owned.matches(expectedMedia))
                assertEquals(ownedBefore.nativeOutputBytes, engine.copyStats().nativeOutputBytes)
                assertEquals(ownedBefore.javaOutputBytes, engine.copyStats().javaOutputBytes)
                assertEquals(1L, engine.release(ownedToken))
                engine.seek()
                assertTrue(owned.matches(expectedMedia))
                engine.close()
                assertTrue(owned.matches(expectedMedia))
            } finally { owned.close() }
        }
        Log.i("PitwallGC", "Compact direct slot transfer, exact parity and stale lease rejection PASS")
    }

    @Test
    fun nativeImmutableReferenceComparisonChecksEveryChunkAndLength() {
        val bytes = ByteArray(48 * 1024 + 3) { (it * 31).toByte() }
        requireNotNull(NativeFragmentLease.build(bytes.size) { it.put(bytes) }).use { lease ->
            assertTrue(lease.matches(bytes))
            for (index in listOf(0, 16383, 16384, 32768, bytes.lastIndex)) {
                val changed = bytes.copyOf()
                changed[index] = (changed[index].toInt() xor 1).toByte()
                assertFalse(lease.matches(changed))
            }
            assertFalse(lease.matches(bytes.copyOf(bytes.size - 1)))
            lease.retain().use { retained -> assertTrue(retained.matches(bytes)) }
            assertTrue(lease.matches(bytes))
        }
    }

    private fun verifyNativeRelayOwnership() {
        val bytes = byteArrayOf(11, 22, 33, 44)
        val native = requireNotNull(NativeFragmentLease.build(bytes.size) { it.put(bytes) })
        val store = app.apexview.cast.RelayResourceStore(16L, 4, 2)
        try {
            store.publish(emptyMap(), native = mapOf("video.m4s" to native.retain()))
            requireNotNull(store.acquire("video.m4s")).use { reader ->
                store.publish(emptyMap(), native = mapOf("video.m4s" to native.retain()))
                assertEquals(4L, store.snapshot().currentBytes)
                val first = native.retain()
                val second = native.retain()
                try {
                    assertTrue(runCatching {
                        store.publish(emptyMap(), native = mapOf("a.m4s" to first, "b.m4s" to second))
                    }.exceptionOrNull() is IllegalArgumentException)
                } finally { first.close(); second.close() }
                store.publish(mapOf("new.m4s" to byteArrayOf(1)))
                native.close()
                store.close()
                val out = java.io.ByteArrayOutputStream()
                reader.writeTo(out, 1, 2)
                assertArrayEquals(byteArrayOf(22, 33), out.toByteArray())
            }
            assertEquals(0L, store.snapshot().pinnedBytes)
        } finally { store.close(); native.close() }
        Log.i("PitwallGC", "Native relay backing reuse, alias rejection and pinned reader retirement PASS")
    }

    private fun verifyCastRouteIsolation() {
        val local = "gc-test-local"
        val cast = "gc-test-cast"
        try {
            ProtectedHevcLiveComposition.selectFeeds(local, listOf("a", "b"),
                localNativeHeader = true, nativeHeaderRequested = true)
            ProtectedHevcLiveComposition.selectFeeds(cast, listOf("a", "b"),
                castNativeHeader = true, nativeHeaderRequested = true)
            assertEquals("JVM:REFERENCE_PENDING", ProtectedHevcLiveComposition.stitchingRoute(cast))
            ProtectedHevcLiveComposition.updateLocalNativeHeaderPreference(false)
            assertEquals("JVM:USER_DISABLED", ProtectedHevcLiveComposition.stitchingRoute(local))
            assertEquals("JVM:REFERENCE_PENDING", ProtectedHevcLiveComposition.stitchingRoute(cast))
            ProtectedHevcLiveComposition.updateCastNativeHeaderPreference(cast, false)
            assertEquals("JVM:USER_DISABLED", ProtectedHevcLiveComposition.stitchingRoute(cast))
        } finally {
            ProtectedHevcLiveComposition.clearSelection(local, true)
            ProtectedHevcLiveComposition.clearSelection(cast, true)
        }
    }

    /** Run on every mandatory API, including Android 10. Exact reduction
     * must not use BigInteger's newer Android exact-conversion methods. */
    private fun verifyNativeProtectedParameters(sourceInit: ByteArray, expectedInit: ByteArray,
                                                rate: Int, denominator: Int) {
        val source = requireNotNull(CmafBoxInspector.inspect(sourceInit).tracks.single().hevcConfiguration)
        val expected = requireNotNull(CmafBoxInspector.inspect(expectedInit).tracks.single().hevcConfiguration)
        val sps = expected.sps.single()
        val pps = expected.pps.single()
        val request = HevcOutputParameterSetWriter.Request(
            expected.vps.single().vpsId, sps.spsId, pps.ppsId,
            sps.width, sps.height, requireNotNull(pps.tileColumnWidthsInCtbs),
            requireNotNull(pps.tileRowHeightsInCtbs), pps.loopFilterAcrossTiles == true,
            rate, denominator)
        val sets = source.rawVpsNals + source.rawSpsNals + source.rawPpsNals
        val reference = HevcOutputParameterSetWriter.generate(sets[0], sets[1], sets[2], request)
        val native = requireNotNull(NativeProtectedPlan.matchingOutputParameters(sets, request, reference))
        assertArrayEquals(expected.rawVpsNals.single(), native.vps)
        assertArrayEquals(expected.rawSpsNals.single(), native.sps)
        assertArrayEquals(expected.rawPpsNals.single(), native.pps)
        assertNull(NativeProtectedPlan.matchingOutputParameters(sets, request, reference) { packet, kind ->
            require(kind == 4)
            packet[0] = 0
            NativeStitchingEngine.protectedPlan(packet, kind)
        })
        assertNull(NativeProtectedPlan.matchingOutputParameters(sets, request, reference) { _, _ ->
            byteArrayOf(0, 0, 0, 1, 0)
        })
        Log.i("PitwallGB", "GB protected clear output parameter native/JVM exact parity PASS")
    }

    private fun verifyFrameRateArithmetic() {
        assertEquals(50 to 1, reducePositiveHevcFrameRate(90_000L, 1_800L))
        assertEquals(30_000 to 1_001, reducePositiveHevcFrameRate(60_000L, 2_002L))
        assertEquals(1 to 1, reducePositiveHevcFrameRate(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Int.MAX_VALUE to 1, reducePositiveHevcFrameRate(Int.MAX_VALUE.toLong(), 1L))
        assertEquals(1 to Int.MAX_VALUE, reducePositiveHevcFrameRate(1L, Int.MAX_VALUE.toLong()))
        for ((n, d) in listOf(0L to 1L, 1L to 0L, -1L to 1L, 1L to -1L)) {
            assertTrue(runCatching { reducePositiveHevcFrameRate(n, d) }.exceptionOrNull() is IllegalArgumentException)
        }
        for ((n, d) in listOf(Long.MAX_VALUE to 1L, 1L to Long.MAX_VALUE)) {
            assertTrue(runCatching { reducePositiveHevcFrameRate(n, d) }.exceptionOrNull() is ArithmeticException)
        }
        assertEquals(90_000L, scaleHevcMicrosToTicks(1_000_000L, 90_000L))
        assertEquals(-1L, scaleHevcMicrosToTicks(-19L, 90_000L))
        assertEquals(Long.MAX_VALUE, scaleHevcMicrosToTicks(Long.MAX_VALUE, 1_000_000L))
        assertEquals(Long.MIN_VALUE, scaleHevcMicrosToTicks(Long.MIN_VALUE, 1_000_000L))
        assertTrue(runCatching { scaleHevcMicrosToTicks(Long.MAX_VALUE, 2_000_000L) }.exceptionOrNull() is ArithmeticException)
        Log.i("PitwallGB", "GB API-compatible exact frame-rate reduction and overflow rejection PASS")
    }

    /** Use the existing independently encoded, exact JVM/Rust fixture, not
     * a mocked native result. Keep the original worker/seek assertions above. */
    private fun verifyArchiveBridge(input: ByteArray, expected: ByteArray) {
        val packet = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4); packet.get(magic)
        assertArrayEquals(byteArrayOf(0x47, 0x42, 0x43, 0x31), magic)
        val track = packet.int.toLong() and 0xffffffffL
        val sequence = packet.int.toLong() and 0xffffffffL
        val width = packet.int; val height = packet.int
        val numerator = packet.int; val denominator = packet.int
        val feeds = packet.short.toInt() and 0xffff
        val columns = packet.short.toInt() and 0xffff
        val rows = packet.short.toInt() and 0xffff
        assertEquals(0, packet.short.toInt())
        val colWidths = List(columns) { packet.int }
        val rowHeights = List(rows) { packet.int }
        val cells = List(columns * rows) { i ->
            HevcTizenGridComposer.CellSelection(packet.short.toInt() and 0xffff,
                packet.short.toInt() and 0xffff, i % columns, i / columns)
        }
        val inits = mutableListOf<ByteArray>()
        val media = mutableListOf<ByteArray>()
        repeat(feeds) {
            val initSize = packet.int; val mediaSize = packet.int
            inits += ByteArray(initSize).also { packet.get(it) }
            media += ByteArray(mediaSize).also { packet.get(it) }
        }
        assertFalse(packet.hasRemaining())
        val geometry = NativeClearArchiveJob.Geometry(width, height, colWidths, rowHeights, cells)
        assertArrayEquals(input, NativeClearArchiveJob.encode(track, sequence, numerator, denominator,
            geometry, inits, media).bytes)
        val parts = NativeClearArchiveJob.encodeParts(track, sequence, numerator, denominator,
            geometry, inits, media.map { listOf(it) })
        assertEquals(input.size, parts.size)
        assertTrue(inits.all { init -> parts.arrays.any { it === init } })
        assertTrue(media.all { fragment -> parts.arrays.any { it === fragment } })
        val sources = inits.indices.map { i ->
            val report = CmafBoxInspector.inspect(inits[i])
            HevcClearCmafGridComposer.Feed(inits[i], listOf(media[i]), "fixture-$i", report,
                listOf(HevcClearCmafGridComposer.InspectedMedia.inspect(media[i], report)))
        }
        val request = HevcClearCmafGridComposer.Request(sources, HevcOutputGridDimensions(columns, rows),
            cells, track, sequence, numerator, denominator)
        val expectedBuffer = ByteBuffer.wrap(expected).order(ByteOrder.BIG_ENDIAN)
        val initSize = expectedBuffer.int; val mediaSize = expectedBuffer.int
        val expectedInit = ByteArray(initSize).also { expectedBuffer.get(it) }
        val expectedMedia = ByteArray(mediaSize).also { expectedBuffer.get(it) }
        assertFalse(expectedBuffer.hasRemaining())
        verifyNativeProtectedParameters(inits[0], expectedInit, numerator, denominator)
        verifyProtectedMetadata(expectedInit)
        verifyProtectedPlan(inits[0], media[0], expectedInit)
        verifyProtectedPictures(inits, media, expectedInit)
        verifyProtectedSequence(inits, media, expectedInit)
        verifyClearVariant(request, geometry, inits, media, expectedInit, expectedMedia)
        verifyLargerClearFeedCounts(request, inits, media)
        verifySelectedClearTiles(request, inits, media)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var references = 0
        fun reference(): HevcClearCmafGridComposer.Output {
            references++
            return HevcClearCmafGridComposer.compose(request)
        }
        fun verify(out: NativeClearArchiveBridge.Produced) {
            assertArrayEquals(expectedInit, out.output.initializationSegment)
            assertArrayEquals(expectedMedia, out.output.mediaFragments.single())
            out.output.initializationSegment.fill(0)
            out.output.mediaFragments.single().fill(0)
        }

        // Exercise the production bridge's one-time resize/recompare path
        // using a real encoded packet, not a mocked worker result.
        var resizeOpens = 0
        var resizeReferences = 0
        NativeClearArchiveBridge { _, i, o ->
            resizeOpens++
            val requestedOutput = if (resizeOpens == 1) o / 2 else o
            val budget = requireNotNull(NativeStitchingEngine.planClearAtlas(false,
                1024L*1024*1024, 32L*1024*1024, 128L*1024*1024, i, requestedOutput))
            NativeStitchingEngine.open(budget)
        }.use { bridge ->
            bridge.enable(context)
            repeat(2) {
                val result = bridge.compose(request, expectedInit, true, 10_000) {
                    resizeReferences++
                    HevcClearCmafGridComposer.compose(request)
                }
                assertTrue(result.nativeEpoch != null)
                bridge.publish(result.nativeEpoch) { verify(result) }
                assertEquals(NativeStitchingSelection.Route.RUST, bridge.decision.actual)
            }
            assertEquals("One bounded resize, no repeated admission", 2, resizeOpens)
            assertEquals("Reuse exact reference, then native-only", 1, resizeReferences)
            // Force the existing synchronous failure retirement path before
            // starting the independent low-RAM bridge test. Normal UI close
            // deliberately retires asynchronously and must not be raced here.
            try {
                bridge.compose(request.copy(firstOutputSequenceNumber = 0), expectedInit, true, 5_000) {
                    HevcClearCmafGridComposer.compose(request.copy(firstOutputSequenceNumber = 0))
                }
                throw AssertionError("Invalid sequence was accepted after resize")
            } catch (_: IllegalArgumentException) { }
            assertFalse(bridge.candidate)
        }

        NativeClearArchiveBridge { _, i, o ->
            val budget = requireNotNull(NativeStitchingEngine.planClearAtlas(true,
                256L * 1024 * 1024, 32L * 1024 * 1024, 32L * 1024 * 1024, i, o))
            NativeStitchingEngine.open(budget)
        }.use { bridge ->
            bridge.enable(context)
            val first = bridge.compose(request, expectedInit, true, 5_000, ::reference)
            assertTrue(first.nativeEpoch != null)
            assertEquals(NativeStitchingSelection.Route.JVM, bridge.decision.actual)
            bridge.publish(first.nativeEpoch) { verify(first) }
            assertEquals(NativeStitchingSelection.Route.RUST, bridge.decision.actual)
            assertEquals(1, references)
            val second = bridge.compose(request, expectedInit, true, 5_000, ::reference)
            assertTrue(second.nativeEpoch != null)
            assertEquals(1, references)
            bridge.invalidateForSeek()
            var stalePublished = false
            try {
                bridge.publish(second.nativeEpoch) { stalePublished = true }
                throw AssertionError("Stale native publication was accepted")
            } catch (_: InterruptedIOException) { assertFalse(stalePublished) }
            verify(second)
            assertEquals(NativeStitchingSelection.Route.JVM, bridge.decision.actual)
            val next = bridge.compose(request, expectedInit, true, 5_000, ::reference)
            assertTrue(next.nativeEpoch != null)
            bridge.publish(next.nativeEpoch) { verify(next) }
            assertEquals(2, references)
            assertEquals(NativeStitchingSelection.Route.RUST, bridge.decision.actual)
            // A later invalid native command must not hide an error in the
            // reference path or silently leave the engine marked active.
            try {
                bridge.compose(request.copy(firstOutputSequenceNumber = 0), expectedInit, true, 5_000) {
                    HevcClearCmafGridComposer.compose(request.copy(firstOutputSequenceNumber = 0))
                }
                throw AssertionError("Invalid sequence was accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(NativeStitchingSelection.Route.JVM, bridge.decision.actual)
            assertTrue(bridge.decision.requested)
            assertFalse(bridge.candidate)
            val fallback = bridge.compose(request, expectedInit, true, 5_000, ::reference)
            assertNull(fallback.nativeEpoch)
            verify(fallback)
            assertEquals(3, references)
            // The failed native worker was joined before fallback; reuse the
            // same disabled bridge for the real rolling clear capture adapter.
            verifyRollingClearBridge(context, bridge, request, inits, media)
        }
        var attempts = 0
        NativeClearArchiveBridge { _, _, _ ->
            attempts++
            throw IllegalStateException("Test bounded admission rejection")
        }.use { bridge ->
            bridge.enable(context)
            repeat(2) {
                val fallback = bridge.compose(request, expectedInit, true, 5_000, ::reference)
                assertNull(fallback.nativeEpoch)
                verify(fallback)
            }
            assertEquals(1, attempts)
            assertEquals(NativeStitchingSelection.Route.JVM, bridge.decision.actual)
            assertEquals("NATIVE_CLEAR_MEMORY_OR_ENGINE_ADMISSION", bridge.decision.reason)
            assertTrue(bridge.decision.requested)
        }
        Log.i("PitwallGB", "GB archive route exact parity, native-only next fragment, seek publication fence and explicit fallback PASS")
    }

    /** Use distinct logical source slots populated from the two independent
     * clear fixtures. Exercises complete 4/6/8-feed assembly, not merely
     * configuration cache slots. This is not physical F1 playback proof. */
    private fun verifyLargerClearFeedCounts(
        template: HevcClearCmafGridComposer.Request,
        inits: List<ByteArray>, media: List<ByteArray>
    ) {
        val sourceConfig = requireNotNull(CmafBoxInspector.inspect(inits.first()).tracks.single().hevcConfiguration)
        val sourcePps = sourceConfig.pps.single()
        val cols = sourcePps.numTileColumns
        val rows = sourcePps.numTileRows
        val mib = 1024L * 1024L
        for (count in listOf(4, 6, 8)) {
            // Two atlas rows of complete source pictures keep every output
            // tile within supported HEVC row/column limits.
            val atlasColumns = count / 2
            val outputColumns = atlasColumns * cols
            val outputRows = 2 * rows
            val feeds = List(count) { i ->
                val source = i % inits.size
                val report = CmafBoxInspector.inspect(inits[source])
                HevcClearCmafGridComposer.Feed(inits[source], listOf(media[source]), "fixture-$i", report,
                    listOf(HevcClearCmafGridComposer.InspectedMedia.inspect(media[source], report)))
            }
            val cells = (0 until outputRows).flatMap { y ->
                (0 until outputColumns).map { x ->
                    HevcTizenGridComposer.CellSelection((y / rows) * atlasColumns + x / cols,
                        (y % rows) * cols + x % cols, x, y)
                }
            }
            val request = template.copy(feeds = feeds,
                outputGrid = HevcOutputGridDimensions(outputColumns, outputRows), cells = cells)
            val expected = HevcClearCmafGridComposer.compose(request)
            try {
                val cfg = requireNotNull(CmafBoxInspector.inspect(expected.initializationSegment).tracks.single().hevcConfiguration)
                val sps = cfg.sps.single()
                val pps = cfg.pps.single()
                val geometry = NativeClearArchiveJob.Geometry(sps.width, sps.height,
                    requireNotNull(pps.tileColumnWidthsInCtbs), requireNotNull(pps.tileRowHeightsInCtbs), cells)
                val packet = NativeClearArchiveJob.encode(request.outputTrackId, request.firstOutputSequenceNumber,
                    request.frameRateNumerator, request.frameRateDenominator, geometry,
                    feeds.map { it.initializationSegment }, feeds.map { it.mediaFragments.single() }).bytes
                try {
                    val budget = requireNotNull(NativeStitchingEngine.planClearAtlas(false,
                        1024*mib, 128*mib, 256*mib, packet.size,
                        Math.addExact(8, Math.addExact(expected.initializationSegment.size, expected.mediaFragments.single().size))))
                    NativeStitchingEngine.open(budget).use { engine ->
                        val actual = NativeClearArchiveJob.execute(engine, packet, true, 10_000) {}
                        try {
                            assertArrayEquals("Native clear init for $count source slots", expected.initializationSegment, actual.initialization)
                            assertArrayEquals("Native clear media for $count source slots", expected.mediaFragments.single(), actual.media)
                        } finally { actual.wipe() }
                    }
                } finally { packet.fill(0) }
            } finally {
                expected.initializationSegment.fill(0)
                expected.mediaFragments.forEach { it.fill(0) }
            }
        }
        Log.i("PitwallGB", "GB clear 4/6/8 source-slot complete CMAF exact JVM/native parity PASS")
    }

    /** Rolling HLS composition uses the actual published path, not only the
     * finite archive bridge. Repeat the independent clear fixture with a
     * contiguous decode clock and require exact native/JVM output. */
    private fun verifyRollingClearBridge(
        context: android.content.Context, bridge: NativeClearArchiveBridge,
        request: HevcClearCmafGridComposer.Request, inits: List<ByteArray>,
        media: List<ByteArray>,
    ) {
        var requested = true
        val rolling = ClearCmafArchiveComposition(fullPictures = true,
            nativeContext = context, nativeRequested = { requested }, nativeClear = bridge)
        val reports = inits.map { CmafBoxInspector.inspect(it) }
        val firstTiming = CmafBoxInspector.inspect(media.first(), reports.first()).fragments.single()
        val duration = requireNotNull(firstTiming.totalDurationTicks)
        val dts = requireNotNull(firstTiming.baseDecodeTimeTicks)
        fun shifted(bytes: ByteArray, offset: Long): ByteArray = bytes.copyOf().apply {
            val time = findType(this, "tfdt") + 4
            val buffer = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)
            val value = Math.addExact(dts, offset)
            when (this[time].toInt()) {
                0 -> { require(value in 0..0xffffffffL); buffer.putInt(time+4, value.toInt()) }
                1 -> buffer.putLong(time+4, value)
                else -> error("Unsupported fixture tfdt")
            }
        }
        var sequence = 1L
        var offset = 0L
        try {
            repeat(4) { index ->
                if (index == 3) requested = false
                val inputs = inits.indices.map { i ->
                    HevcClearCmafGridComposer.Feed(inits[i],
                        listOf(shifted(media[i], offset)), "fixture-$i")
                }
                val expected = HevcClearCmafGridComposer.compose(request.copy(feeds = inputs,
                    outputTrackId = 1, firstOutputSequenceNumber = sequence))
                try {
                    rolling.compose(inputs)
                    val decision = rolling.lastRoute
                    val expectedRoute = if (index in 1..2) NativeStitchingSelection.Route.RUST
                        else NativeStitchingSelection.Route.JVM
                    assertEquals("Rolling route index=$index reason=${decision.reason}", expectedRoute, decision.actual)
                    assertEquals(requested, decision.requested)
                    val window = requireNotNull(rolling.outputWindow())
                    try {
                        assertArrayEquals(expected.initializationSegment, window.initializationSegment)
                        val last = window.fragments.last()
                        assertEquals(sequence, last.sequenceNumber)
                        assertArrayEquals(expected.mediaFragments.single(), last.mediaFragment)
                    } finally { window.zero() }
                } finally {
                    expected.initializationSegment.fill(0)
                    expected.mediaFragments.forEach { it.fill(0) }
                    inputs.forEach { it.mediaFragments.forEach { b -> b.fill(0) } }
                }
                sequence++
                offset = Math.addExact(offset, duration)
                if (index == 2) {
                    // Retain the original independent-fragment/OFF checks, then
                    // exercise actual dependent fragments before disabling Rust.
                    verifyRollingDependentWindow(rolling, request, inits, media, sequence, offset)
                    sequence += 4L
                    offset = Math.addExact(offset, duration)
                }
            }
        } finally { rolling.reset() }
        Log.i("PitwallGB", "GB rolling clear HLS actual Rust publication, contiguous timing, JVM reference and OFF parity PASS")
    }

    /** Split genuine encoded pictures at non-sync sample boundaries without
     * changing payloads, flags, or cadence. Publish 1, then 1, then 2 pieces
     * through the production rolling path (GBC1, GBC2 with 2 and 4 fragments). */
    private fun verifyRollingDependentWindow(
        rolling: ClearCmafArchiveComposition, request: HevcClearCmafGridComposer.Request,
        inits: List<ByteArray>, media: List<ByteArray>, sequence: Long, offset: Long,
    ) {
        val reports = inits.map { CmafBoxInspector.inspect(it) }
        val first = CmafBoxInspector.inspect(media.first(), reports.first()).fragments.single()
        val timings = first.sampleTimings
        val boundaries = mutableListOf(0)
        for (part in 1..3) {
            val target = timings.size * part / 4
            val cut = (maxOf(target, boundaries.last() + 1) until timings.size)
                .first { (requireNotNull(timings[it].sampleFlags) and 0x10000L) != 0L }
            boundaries += cut
        }
        boundaries += timings.size
        val windows = media.mapIndexed { feed, source ->
            val track = reports[feed].tracks.single()
            val fragment = CmafBoxInspector.inspect(source, reports[feed]).fragments.single()
            val data = ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN)
            data.position(data.int + 8)
            val samples = fragment.sampleTimings.map { timing ->
                val bytes = ByteArray(requireNotNull(timing.sampleSizeBytes).toInt()).also { data.get(it) }
                HevcClearCmafFragmentWriter.Sample(bytes, requireNotNull(timing.durationTicks),
                    requireNotNull(timing.compositionOffsetTicks), requireNotNull(timing.sampleFlags))
            }
            assertFalse(data.hasRemaining())
            try {
                boundaries.zipWithNext().mapIndexed { part, (start, end) ->
                    val base = Math.addExact(requireNotNull(fragment.sampleTimings[start].decodeTimeTicks), offset)
                    HevcClearCmafFragmentWriter.write(requireNotNull(track.trackId), part + 1L,
                        base, samples.subList(start, end), false, false)
                }
            } finally { samples.forEach { it.data.fill(0) } }
        }
        val fullFeeds = inits.indices.map { i ->
            HevcClearCmafGridComposer.Feed(inits[i], windows[i], "fixture-$i")
        }
        val reference = HevcClearCmafGridComposer.compose(request.copy(feeds = fullFeeds,
            outputTrackId = 1, firstOutputSequenceNumber = sequence))
        try {
            for ((start, end) in listOf(0 to 1, 1 to 2, 2 to 4)) {
                rolling.compose(fullFeeds.map { it.copy(mediaFragments = it.mediaFragments.subList(start, end)) })
                assertEquals("Dependent rolling window $start..$end: ${rolling.lastRoute.reason}",
                    NativeStitchingSelection.Route.RUST, rolling.lastRoute.actual)
                val window = requireNotNull(rolling.outputWindow())
                try {
                    assertArrayEquals(reference.initializationSegment, window.initializationSegment)
                    val actual = window.fragments.takeLast(end - start)
                    assertEquals(end - start, actual.size)
                    actual.forEachIndexed { index, fragment ->
                        val position = start + index
                        assertEquals(sequence + position, fragment.sequenceNumber)
                        assertArrayEquals(reference.mediaFragments[position], fragment.mediaFragment)
                    }
                } finally { window.zero() }
            }
        } finally {
            reference.initializationSegment.fill(0)
            reference.mediaFragments.forEach { it.fill(0) }
            windows.flatten().forEach { it.fill(0) }
        }
        Log.i("PitwallGB", "GB dependent clear HLS 2/4-fragment windows preserve POC, timing and exact JVM output PASS")
    }

    /** Existing JVM supports selecting only a subset of the available source
     * tiles. Compare actual Rust worker output, including its filter policy. */
    private fun verifySelectedClearTiles(
        template: HevcClearCmafGridComposer.Request, inits: List<ByteArray>, media: List<ByteArray>
    ) {
        val reports = inits.map { CmafBoxInspector.inspect(it) }
        val config = requireNotNull(reports[0].tracks.single().hevcConfiguration)
        val columns = config.pps.single().numTileColumns
        val rows = config.pps.single().numTileRows
        require(columns >= 2 && rows >= 2)
        val cells = List(columns) { column ->
            HevcTizenGridComposer.CellSelection(0, column, column, 0)
        }
        val feeds = inits.indices.map { i ->
            HevcClearCmafGridComposer.Feed(inits[i], listOf(media[i]), "selected-$i",
                reports[i], listOf(HevcClearCmafGridComposer.InspectedMedia.inspect(media[i], reports[i])))
        }
        val request = template.copy(feeds = feeds,
            outputGrid = HevcOutputGridDimensions(columns, 1), cells = cells)
        val expected = HevcClearCmafGridComposer.compose(request)
        try {
            val cfg = requireNotNull(CmafBoxInspector.inspect(expected.initializationSegment)
                .tracks.single().hevcConfiguration)
            val sps = cfg.sps.single()
            val pps = cfg.pps.single()
            val geometry = NativeClearArchiveJob.Geometry(sps.width, sps.height,
                requireNotNull(pps.tileColumnWidthsInCtbs), requireNotNull(pps.tileRowHeightsInCtbs),
                cells, loopAcrossTiles = pps.loopFilterAcrossTiles == true)
            val packet = NativeClearArchiveJob.encode(request.outputTrackId,
                request.firstOutputSequenceNumber, request.frameRateNumerator, request.frameRateDenominator,
                geometry, inits, media).bytes
            try {
                val budget = requireNotNull(NativeStitchingEngine.planClearAtlas(true,
                    256L*1024*1024, 32L*1024*1024, 32L*1024*1024, packet.size,
                    8 + expected.initializationSegment.size + expected.mediaFragments.single().size))
                NativeStitchingEngine.open(budget).use { engine ->
                    val result = NativeClearArchiveJob.execute(engine, packet, true, 5_000) {}
                    try {
                        assertArrayEquals(expected.initializationSegment, result.initialization)
                        assertArrayEquals(expected.mediaFragments.single(), result.media)
                    } finally { result.wipe() }
                }
            } finally { packet.fill(0) }
        } finally {
            expected.initializationSegment.fill(0)
            expected.mediaFragments.forEach { it.fill(0) }
        }
        Log.i("PitwallGB", "GB selected source-tile subset exact native/JVM profile PASS")
    }

    /** Same actual JVM composer and native worker, but the second source has
     * three-byte lengths, exact in-band sets, AUD/SEI and terminal padding. */
    /** Encode the same zero crop with the opposite presence flag.
     * The JVM metadata, source cells, timing and actual video must be equal. */
    private fun equivalentSpsEncoding(nal: ByteArray): ByteArray {
        val rbsp = HevcBitstream.ebspToRbsp(nal.copyOfRange(2, nal.size))
        val reader = HevcBitReader(rbsp)
        reader.readBits(4)
        val layers = reader.readBits(3)
        reader.readBit()
        repeat(3) { reader.readBits(32) }
        val profiles = BooleanArray(layers)
        val levels = BooleanArray(layers)
        repeat(layers) { i -> profiles[i] = reader.readBit() != 0; levels[i] = reader.readBit() != 0 }
        if (layers > 0) repeat(8-layers) { reader.readBits(2) }
        repeat(layers) { i ->
            if (profiles[i]) { reader.readBits(32); reader.readBits(32); reader.readBits(24) }
            if (levels[i]) reader.readBits(8)
        }
        reader.readUnsignedExpGolomb()
        val chroma = reader.readUnsignedExpGolomb()
        if (chroma == 3L) reader.readBit()
        reader.readUnsignedExpGolomb()
        reader.readUnsignedExpGolomb()
        val start = reader.positionBits
        val signalled = reader.readBit() != 0
        if (signalled) repeat(4) { require(reader.readUnsignedExpGolomb() == 0L) }
        val end = reader.positionBits
        val all = rbsp.flatMap { value -> (7 downTo 0).map { (value.toInt() ushr it) and 1 } }
        val stop = all.indexOfLast { it == 1 }
        val replacement = if (signalled) listOf(0) else listOf(1,1,1,1,1)
        val bits = (all.take(start) + replacement + all.subList(end, stop+1)).toMutableList()
        while (bits.size % 8 != 0) bits.add(0)
        val out = java.io.ByteArrayOutputStream()
        out.write(nal, 0, 2)
        var zeros = 0
        bits.chunked(8).forEach { chunk ->
            val value = chunk.fold(0) { a, bit -> (a shl 1) or bit }
            if (zeros == 2 && value <= 3) { out.write(3); zeros = 0 }
            out.write(value)
            zeros = if (value == 0) minOf(2, zeros+1) else 0
        }
        val variant = out.toByteArray()
        assertFalse(nal.contentEquals(variant))
        assertEquals(HevcParameterSets.parseSps(nal), HevcParameterSets.parseSps(variant))
        return variant
    }

    private fun verifyClearVariant(
        request: HevcClearCmafGridComposer.Request, geometry: NativeClearArchiveJob.Geometry,
        inits: List<ByteArray>, media: List<ByteArray>, expectedInit: ByteArray, expectedMedia: ByteArray,
    ) {
        val init = inits[1].copyOf()
        val hvcc = findType(init, "hvcC") + 4
        init[hvcc + 21] = ((init[hvcc + 21].toInt() and 0xfc) or 2).toByte()
        val report = CmafBoxInspector.inspect(init)
        assertTrue(report.errors.isEmpty())
        val track = report.tracks.single()
        val config = requireNotNull(track.hevcConfiguration)
        assertEquals(3, config.nalLengthSize)
        val source = media[1]
        val previous = request.feeds[1].validatedInitialization!!
        val fragment = CmafBoxInspector.inspect(source, previous).fragments.single()
        val data = ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN)
        val moofSize = data.int
        data.position(moofSize + 8)
        val samples = fragment.sampleTimings.mapIndexed { index, timing ->
            val end = data.position() + requireNotNull(timing.sampleSizeBytes).toInt()
            val nals = mutableListOf<ByteArray>()
            if (index == 0) {
                nals += config.rawVpsNals
                nals += config.rawSpsNals.map(::equivalentSpsEncoding)
                nals += config.rawPpsNals
                nals += byteArrayOf(0x46, 0x01, 0x50) // AUD
            }
            while (data.position() < end) {
                val length = data.int
                require(length in 2..(end - data.position()))
                val nal = ByteArray(length).also { data.get(it) }
                nals += if (index == 0 && nals.size == 4) nal + byteArrayOf(0, 0) else nal
            }
            assertEquals(end, data.position())
            if (index == 0) nals += byteArrayOf(0x50, 0x01, 0x80.toByte()) // suffix SEI
            HevcClearCmafFragmentWriter.NalSample(nals, requireNotNull(timing.durationTicks),
                requireNotNull(timing.compositionOffsetTicks), requireNotNull(timing.sampleFlags))
        }
        assertEquals(source.size, data.position())
        fun fragmentFor(samples: List<HevcClearCmafFragmentWriter.NalSample>) =
            HevcClearCmafFragmentWriter.writeNalSamples(requireNotNull(track.trackId), 1,
                requireNotNull(fragment.baseDecodeTimeTicks), samples, 3, false, false)
        val variant = fragmentFor(samples)
        val variantFeed = HevcClearCmafGridComposer.Feed(init, listOf(variant), "variant",
            report, listOf(HevcClearCmafGridComposer.InspectedMedia.inspect(variant, report)))
        val variantRequest = request.copy(feeds = request.feeds.toMutableList().apply { set(1, variantFeed) })
        val reference = HevcClearCmafGridComposer.compose(variantRequest)
        assertArrayEquals(expectedInit, reference.initializationSegment)
        assertArrayEquals(expectedMedia, reference.mediaFragments.single())
        val variantInits = inits.toMutableList().apply { set(1, init) }
        val variantMedia = media.toMutableList().apply { set(1, variant) }
        fun encode(m: List<ByteArray>) = NativeClearArchiveJob.encode(request.outputTrackId,
            request.firstOutputSequenceNumber, request.frameRateNumerator, request.frameRateDenominator,
            geometry, variantInits, m).bytes
        val packet = encode(variantMedia)
        val budget = requireNotNull(NativeStitchingEngine.planClearAtlas(true,
            256L * 1024 * 1024, 32L * 1024 * 1024, 32L * 1024 * 1024,
            packet.size, 8 + expectedInit.size + expectedMedia.size))
        NativeStitchingEngine.open(budget).use { engine ->
            val result = NativeClearArchiveJob.execute(engine, packet, true, 5_000) {}
            try {
                assertArrayEquals(reference.initializationSegment, result.initialization)
                assertArrayEquals(reference.mediaFragments.single(), result.media)
            } finally { result.wipe() }
            // Make the three-byte source the reference input, preserving the
            // tile placement. Its output hvcC and every NAL length must agree.
            val reversed = variantRequest.copy(feeds = variantRequest.feeds.reversed(),
                cells = variantRequest.cells.map { it.copy(feedIndex = 1 - it.feedIndex) })
            val shortReference = HevcClearCmafGridComposer.compose(reversed)
            try {
                val shortCfg = requireNotNull(CmafBoxInspector.inspect(shortReference.initializationSegment)
                    .tracks.single().hevcConfiguration)
                assertEquals(3, shortCfg.nalLengthSize)
                val shortPacket = NativeClearArchiveJob.encode(request.outputTrackId,
                    request.firstOutputSequenceNumber, request.frameRateNumerator, request.frameRateDenominator,
                    geometry.copy(cells = reversed.cells), variantInits.reversed(), variantMedia.reversed()).bytes
                try {
                    val actual = NativeClearArchiveJob.execute(engine, shortPacket, true, 5_000) {}
                    try {
                        assertArrayEquals(shortReference.initializationSegment, actual.initialization)
                        assertArrayEquals(shortReference.mediaFragments.single(), actual.media)
                        assertTrue(CmafBoxInspector.inspect(actual.media, CmafBoxInspector.inspect(actual.initialization)).errors.isEmpty())
                    } finally { actual.wipe() }
                } finally { shortPacket.fill(0) }
            } finally {
                shortReference.initializationSegment.fill(0)
                shortReference.mediaFragments.forEach { it.fill(0) }
            }
            // Invalid in-band VPS identity must fail, including after the
            // worker has cached this exact initialization and geometry.
            val badSets = samples.first().nals.toMutableList()
            badSets[0] = badSets[0].copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            val bad = fragmentFor(samples.toMutableList().apply {
                set(0, first().copy(nals = badSets))
            })
            val badPacket = encode(variantMedia.toMutableList().apply { set(1, bad) })
            val token = engine.submitClearAtlas(engine.generation(), badPacket, true, 5_000)
            assertTrue(token > 0)
            assertEquals(NativeStitchingEngine.INVALID, await(engine, token))
            assertEquals(1L, engine.release(token))
        }
        reference.initializationSegment.fill(0)
        reference.mediaFragments.single().fill(0)
        Log.i("PitwallGB", "GB clear mixed NAL lengths, in-band identity, non-VCL, terminal padding and cached rejection PASS")
    }

    /** Synthetic CBCS metadata and opaque dummy payload only. No real protected
     * media, key, license or CDM enters this automated differential. */
    private fun verifyProtectedMetadata(clearInit: ByteArray) {
        val trackId = requireNotNull(CmafBoxInspector.inspect(clearInit).tracks.single().trackId)
        for (ivWidth in listOf(0, 8, 16)) {
            val init = syntheticProtectedInit(clearInit, ivWidth)
            val protection = requireNotNull(CmafBoxInspector.inspect(init).tracks.single().trackEncryption)
            assertEquals(ivWidth, protection.perSampleIvSize)
            for (dts in listOf(0xffffffffL, 0x100000000L)) {
                val samples = List(2) { index ->
                    val iv = if (ivWidth == 0) null else ByteArray(ivWidth) { (it + index + 1).toByte() }
                    ProtectedHevcCmafFragmentWriter.Sample(
                        sampleBytes = ByteArray(52) { (it + index + 1).toByte() },
                        subsampleRanges = listOf(CmafBoxInspector.SubsampleEncryptionRange(4, 16),
                            CmafBoxInspector.SubsampleEncryptionRange(0, 32)),
                        perSampleIvBytes = iv,
                        perSampleIvIdentity = iv?.let { ProtectedValueFingerprint.fromBytes("iv", it) },
                        durationTicks = 3600,
                        sampleFlags = 0x02000000,
                        compositionOffsetTicks = if (index == 0) -3 else 5,
                    )
                }
                val request = ProtectedHevcCmafFragmentWriter.Request(trackId, dts, 7, ivWidth, samples)
                val baseline = ProtectedHevcCmafFragmentWriter.write(init, request)
                var matched = false
                val candidate = ProtectedHevcCmafFragmentWriter.write(init, request,
                    preferNativeHeader = true, onNativeHeaderResult = { matched = it })
                assertTrue("Native protected metadata must not silently fall back", matched)
                assertArrayEquals(baseline, candidate)
                matched = false
                val direct = ProtectedHevcCmafFragmentWriter.write(init, request,
                    preferNativeHeader = true, onNativeHeaderResult = { matched = it }, directNativeHeader = true)
                assertTrue("Direct native protected metadata must not silently fall back", matched)
                assertArrayEquals(baseline, direct)
                direct.fill(0)
                // Same writer and unchanged CBCS metadata, but the entire
                // final mdat lives in a bounded Rust allocation.
                ProtectedHevcCmafFragmentWriter.writeOwned(init, request,
                    preferNativeHeader = true, onNativeHeaderResult = { assertTrue(it) },
                    directNativeHeader = true, preferNativeStorage = true).use { owned ->
                    assertNull("Native owner must not allocate a heap fragment", owned.heap)
                    val lease = requireNotNull(owned.native)
                    assertArrayEquals(baseline, lease.copyBytes())
                    assertEquals(CmafBoxInspector.inspect(baseline, CmafBoxInspector.inspect(init)).fragments,
                        lease.inspect(CmafBoxInspector.inspect(init)).fragments)
                    val retained = lease.retain()
                    lease.close()
                    val range = ByteArray(7)
                    retained.readTo(range, 0, baseline.size-range.size, range.size)
                    assertArrayEquals(baseline.copyOfRange(baseline.size-range.size, baseline.size), range)
                    retained.close()
                }
                val moof = ByteBuffer.wrap(baseline).int
                val header = baseline.copyOfRange(0, moof)
                val auxiliarySizes = List(2) { ivWidth + 14 }
                val packet = NativeProtectedHeader.encode(request, auxiliarySizes)
                assertArrayEquals(header, requireNotNull(NativeStitchingEngine.protectedHeader(packet)))
                assertNull(NativeStitchingEngine.protectedHeader(packet.copyOf(packet.size - 1)))
                val corrupted = packet.copyOf().apply { ByteBuffer.wrap(this).putInt(32, 53) }
                assertNull(NativeStitchingEngine.protectedHeader(corrupted))
                assertNull(NativeProtectedHeader.matchingHeader(header, request, auxiliarySizes) { ByteArray(header.size) })
                assertNull(NativeProtectedHeader.matchingHeader(header, request, auxiliarySizes) { null })
                assertNull(NativeProtectedHeader.directHeader(request, auxiliarySizes) { null })
                assertNull(NativeProtectedHeader.directHeader(request, auxiliarySizes) { header.copyOf().apply {
                    ByteBuffer.wrap(this).putInt(20, 99)
                } })
                assertNull(NativeProtectedHeader.directHeader(request, auxiliarySizes) { header.copyOf().apply {
                    ByteBuffer.wrap(this).putInt(size-4, 0)
                } })
                // The dummy opaque payload remains byte-for-byte identical
                // in the real JVM writer's final mdat, with no native access.
                assertArrayEquals(samples[0].sampleBytes + samples[1].sampleBytes,
                    candidate.copyOfRange(moof + 8, candidate.size))
                packet.fill(0); corrupted.fill(0); baseline.fill(0); candidate.fill(0)
            }
            ProtectedHevcCmafInitSegmentWriter.requireProtectedSignallingPreserved(init, init.copyOf(),
                "synthetic metadata-only differential")
            init.fill(0)
        }
        Log.i("PitwallGB", "GB CBCS metadata-only native/JVM exact moof, IV modes, tfdt versions, opaque payload preservation and negative fallback PASS")
    }

    /** Exercise native syntax parsing using the independently encoded clear
     * fixture and the actual existing protected inspection/borrowed writer.
     * Synthetic range labels only: no real encrypted media or entitlement. */
    private fun verifyProtectedPlan(sourceInit: ByteArray, sourceMedia: ByteArray, outputInit: ByteArray) {
        val source = requireNotNull(CmafBoxInspector.inspect(sourceInit).tracks.single().hevcConfiguration)
        val output = requireNotNull(CmafBoxInspector.inspect(outputInit).tracks.single().hevcConfiguration)
        val srcSets = source.rawVpsNals + source.rawSpsNals + source.rawPpsNals
        val outSets = output.rawVpsNals + output.rawSpsNals + output.rawPpsNals
        val b = ByteBuffer.wrap(sourceMedia).order(ByteOrder.BIG_ENDIAN)
        val moofSize = b.int
        b.position(moofSize + 8)
        val nal = ByteArray(b.int).also { b.get(it) }
        val srcSps = source.sps.single()
        val srcPps = source.pps.single()
        val dstSps = output.sps.single()
        val dstPps = output.pps.single()
        val destination = requireNotNull(dstPps.tileColumnWidthsInCtbs).first()
        for (opaque in listOf(false, true)) {
            val reference = HevcSliceHeaderRewriter.rewrite(nal, srcSps, srcPps, dstSps, dstPps,
                destination, preserveOpaqueCabacTail = opaque)
            val end = reference.sourceTailOffset + if (opaque) 0 else 1
            require(end < nal.size)
            val clear = nal.copyOfRange(0, end)
            val packet = NativeProtectedPlan.encodeSlice(srcSets, outSets, clear, destination, opaque)
            val expectedPrefix = reference.nal.copyOfRange(0, reference.nal.size - reference.copiedTailBytes)
            val actual = requireNotNull(NativeStitchingEngine.protectedPlan(packet, 1))
            assertEquals(reference.sourceTailOffset, ByteBuffer.wrap(actual).int)
            assertArrayEquals(expectedPrefix, actual.copyOfRange(4, actual.size))
            assertNull(NativeStitchingEngine.protectedPlan(packet.copyOf(packet.size - 1), 1))
            assertNull(NativeStitchingEngine.protectedPlan(packet.copyOf().apply {
                ByteBuffer.wrap(this).putInt(4, Int.MAX_VALUE)
            }, 1))

            val sample = u32(nal.size) + nal
            val ranges = listOf(CmafBoxInspector.SubsampleEncryptionRange(4 + end, (nal.size - end).toLong()))
            val layout = ProtectedHevcSampleLayout.inspect(sample, 4, ranges)
            val access = ProtectedHevcAccessUnitInspector.inspectLengthPrefixedSample(sample, layout, srcSps, srcPps,
                source.vps.single())
            val batchInput = NativeProtectedPlan.BatchInput(0, sample, layout.nals.single(),
                reference.sourceTailOffset, destination, opaque)
            val batchPacket = NativeProtectedPlan.encodeBatch(listOf(srcSets), outSets, listOf(batchInput))
            val batchResult = requireNotNull(NativeStitchingEngine.protectedPlan(batchPacket, 3))
            val batchReader = ByteBuffer.wrap(batchResult)
            assertEquals(1, batchReader.int)
            assertEquals(actual.size, batchReader.int)
            assertArrayEquals(actual, ByteArray(batchReader.remaining()).also { batchReader.get(it) })
            assertNull(NativeStitchingEngine.protectedPlan(batchPacket.copyOf(batchPacket.size-1), 3))
            assertNull(NativeStitchingEngine.protectedPlan(batchPacket.copyOf().apply {
                ByteBuffer.wrap(this).putInt(8, 441)
            }, 3))
            batchPacket.fill(0); batchResult.fill(0)
            fun borrow(native: NativeProtectedPlan? = null) = ProtectedHevcSliceHeaderRewriter.rewriteBorrowed(sample, layout, access, 0,
                srcSps, srcPps, dstSps, dstPps, destination, nativePlan = native, sourceSets = srcSets)
            val baseline = ProtectedHevcSampleComposer.planBorrowed(listOf(borrow()), 4, "GB reference").use { it.materialize() }
            var rejected = false
            val planner = NativeProtectedPlan(outSets, { true }, { rejected = true })
            val candidate = borrow()
            planner.verifySlice(srcSets, sample, layout.nals.single(), candidate)
            assertEquals(1, planner.matchedSlices)
            val result = ProtectedHevcSampleComposer.planBorrowed(listOf(candidate), 4, "GB candidate", planner).use { it.materialize() }
            assertEquals(1, planner.matchedMaps)
            assertFalse(rejected)
            assertArrayEquals(baseline.sampleBytes, result.sampleBytes)
            assertEquals(baseline.subsampleRanges, result.subsampleRanges)
            assertEquals(baseline.encryptedByteCount, result.encryptedByteCount)
            assertArrayEquals(u32(reference.nal.size) + reference.nal, result.sampleBytes)
            val direct = NativeProtectedPlan(outSets, { true }, { rejected = true }, direct = true)
            val directResult = ProtectedHevcSampleComposer.planBorrowed(listOf(borrow(direct)), 4,
                "GB direct", direct).use { it.materialize() }
            assertEquals(1, direct.matchedSlices)
            assertEquals(1, direct.matchedMaps)
            assertFalse(rejected)
            assertArrayEquals(baseline.sampleBytes, directResult.sampleBytes)
            assertEquals(baseline.subsampleRanges, directResult.subsampleRanges)
            directResult.sampleBytes.fill(0)
            // A bad candidate disables native planning for the whole attempt;
            // the validated reference remains byte-identical.
            var attempts = 0
            val badPlanner = NativeProtectedPlan(outSets, { true }, { rejected = true }) { _, _ ->
                attempts++
                byteArrayOf(0, 0, 0, 0, 1, 1)
            }
            val fallback = borrow()
            badPlanner.verifySlice(srcSets, sample, layout.nals.single(), fallback)
            assertTrue(rejected)
            assertEquals(0, badPlanner.matchedSlices)
            val fallbackResult = ProtectedHevcSampleComposer.planBorrowed(listOf(fallback), 4, "GB fallback", badPlanner).use { it.materialize() }
            assertEquals(1, attempts)
            assertArrayEquals(baseline.sampleBytes, fallbackResult.sampleBytes)
            assertEquals(baseline.subsampleRanges, fallbackResult.subsampleRanges)
            var directAttempts = 0
            val badDirect = NativeProtectedPlan(outSets, { true }, { rejected = true }, direct = true) { input, kind ->
                directAttempts++
                requireNotNull(NativeStitchingEngine.protectedPlan(input, kind)).apply {
                    // Wrong splice offset; it cannot be adopted even in direct mode.
                    ByteBuffer.wrap(this).putInt(0, Int.MAX_VALUE)
                }
            }
            val directFallback = ProtectedHevcSampleComposer.planBorrowed(listOf(borrow(badDirect)), 4,
                "GB direct fallback", badDirect).use { it.materialize() }
            assertEquals(1, directAttempts)
            assertEquals(0, badDirect.matchedSlices)
            assertEquals(0, badDirect.matchedMaps)
            assertArrayEquals(baseline.sampleBytes, directFallback.sampleBytes)
            assertEquals(baseline.subsampleRanges, directFallback.subsampleRanges)
            directFallback.sampleBytes.fill(0)
            listOf(packet, clear, actual, expectedPrefix, reference.nal, baseline.sampleBytes,
                result.sampleBytes, fallbackResult.sampleBytes, sample).forEach { it.fill(0) }
        }

        // Independent long clear run / adjacent encrypted ranges, no payload.
        val ranges = listOf(ProtectedHevcSampleLayout.ProtectionRange(0, 65536, true),
            ProtectedHevcSampleLayout.ProtectionRange(65536, 16, false),
            ProtectedHevcSampleLayout.ProtectionRange(65552, 32, false),
            ProtectedHevcSampleLayout.ProtectionRange(65584, 7, true))
        val slice = ProtectedHevcSampleComposer.Slice(65591, ranges, 48,
            { error("Metadata-only test must never inspect media") },
            { _, _ -> error("Metadata-only test must never copy media") }, {})
        val map = listOf(CmafBoxInspector.SubsampleEncryptionRange(65535, 0),
            CmafBoxInspector.SubsampleEncryptionRange(5, 16),
            CmafBoxInspector.SubsampleEncryptionRange(0, 32),
            CmafBoxInspector.SubsampleEncryptionRange(7, 0))
        val planner = NativeProtectedPlan(outSets, { true }, { error("Valid range metadata rejected") })
        assertEquals(map, planner.matchingMap(listOf(slice), 4, 65595, map))
        assertEquals(1, planner.matchedMaps)
        val directMap = NativeProtectedPlan(outSets, { true }, { error("Valid direct range metadata rejected") }, direct = true)
        assertEquals(map, directMap.directMap(listOf(slice), 4, 65595))
        assertEquals(1, directMap.matchedMaps)
        var mapRejected = false
        val badMap = NativeProtectedPlan(outSets, { true }, { mapRejected = true }, direct = true) { input, kind ->
            requireNotNull(NativeStitchingEngine.protectedPlan(input, kind)).apply {
                // Change one protected range boundary without changing total bytes.
                ByteBuffer.wrap(this).putInt(16, 17)
            }
        }
        assertNull(badMap.directMap(listOf(slice), 4, 65595))
        assertTrue(mapRejected)
        val packet = NativeProtectedPlan.encodeMap(listOf(slice), 4)
        assertNull(NativeStitchingEngine.protectedPlan(packet.copyOf(packet.size - 1), 2))
        assertNull(NativeStitchingEngine.protectedPlan(packet.copyOf().apply {
            ByteBuffer.wrap(this).putInt(12, 65592)
        }, 2))
        packet.fill(0)
        Log.i("PitwallGB", "GB protected clear-prefix native syntax, exact borrowed output, metadata range boundaries and explicit fallback PASS")
    }

    /** Complete two independently encoded tiled pictures through the actual
     * protected picture composer. The original bytes are clear test fixtures
     * with synthetic range labels, not encrypted F1 data. */
    private fun verifyProtectedPictures(inits: List<ByteArray>, media: List<ByteArray>, outputInit: ByteArray) {
        val outputConfig = requireNotNull(CmafBoxInspector.inspect(outputInit).tracks.single().hevcConfiguration)
        val outSets = outputConfig.rawVpsNals + outputConfig.rawSpsNals + outputConfig.rawPpsNals
        val outSps = outputConfig.sps.single()
        val outPps = outputConfig.pps.single()
        val iv = ByteArray(16) { 37 }
        val sources = inits.indices.map { i ->
            val init = syntheticProtectedInit(inits[i], 16)
            val report = CmafBoxInspector.inspect(init)
            val track = report.tracks.single()
            val config = requireNotNull(track.hevcConfiguration)
            val sps = config.sps.single()
            val pps = config.pps.single()
            val clearReport = CmafBoxInspector.inspect(inits[i])
            val timing = CmafBoxInspector.inspect(media[i], clearReport).fragments.single().sampleTimings.first()
            val b = ByteBuffer.wrap(media[i])
            val start = b.int + 8
            b.position(start)
            val sample = ByteArray(requireNotNull(timing.sampleSizeBytes).toInt()).also { b.get(it) }
            val nals = ByteBuffer.wrap(sample)
            val ranges = mutableListOf<CmafBoxInspector.SubsampleEncryptionRange>()
            while (nals.hasRemaining()) {
                val nal = ByteArray(nals.int).also { nals.get(it) }
                val parsed = HevcSliceHeaderParser.parse(nal, sps, pps)
                // Only the validated aligned header is classified clear.
                val same = HevcSliceHeaderRewriter.rewrite(nal, sps, pps, sps, pps,
                    parsed.sliceSegmentAddress, preserveOpaqueCabacTail = true)
                val end = same.sourceTailOffset
                require(end < nal.size)
                ranges += CmafBoxInspector.SubsampleEncryptionRange(4 + end, (nal.size-end).toLong())
                same.nal.fill(0)
            }
            val synthetic = ProtectedHevcCmafFragmentWriter.write(init,
                ProtectedHevcCmafFragmentWriter.Request(requireNotNull(track.trackId),
                    requireNotNull(timing.decodeTimeTicks), 1, 16,
                    listOf(ProtectedHevcCmafFragmentWriter.Sample(sample, ranges, iv,
                        ProtectedValueFingerprint.fromBytes("iv", iv), requireNotNull(timing.durationTicks),
                        requireNotNull(timing.sampleFlags), requireNotNull(timing.compositionOffsetTicks)))))
            val fragment = CmafBoxInspector.inspect(synthetic, report).fragments.single()
            synthetic.fill(0)
            ProtectedHevcPictureComposer.FeedSample("fixture-$i", sample, track, fragment, 0, 0,
                i*sps.picWidthInCtbs, HevcPocTracker())
        }
        fun fresh() = sources.map { it.copy(pocTracker = HevcPocTracker()) }
        val baseline = ProtectedHevcPictureComposer.plan(fresh(), outSps, outPps,
            fullPictureColumns = 2).use { it.materialize() }
        var rejected = false
        val planner = NativeProtectedPlan(outSets, { true }, { rejected = true })
        val candidate = ProtectedHevcPictureComposer.plan(fresh(), outSps, outPps,
            fullPictureColumns = 2, nativePlan = planner).use { it.materialize() }
        assertFalse(rejected)
        assertEquals(requireNotNull(outPps.tileColumnWidthsInCtbs).size *
            requireNotNull(outPps.tileRowHeightsInCtbs).size, planner.matchedSlices)
        assertEquals(1, planner.matchedMaps)
        assertArrayEquals(baseline.sampleBytes, candidate.sampleBytes)
        assertEquals(baseline.subsampleRanges, candidate.subsampleRanges)
        assertEquals(baseline.contributions, candidate.contributions)
        val directPlanner = NativeProtectedPlan(outSets, { true }, { rejected = true }, direct = true)
        val directPicture = ProtectedHevcPictureComposer.plan(fresh(), outSps, outPps,
            fullPictureColumns = 2, nativePlan = directPlanner).use { it.materialize() }
        assertFalse(rejected)
        assertEquals(planner.matchedSlices, directPlanner.matchedSlices)
        assertEquals(1, directPlanner.matchedMaps)
        assertEquals(1, directPlanner.matchedBatches)
        assertArrayEquals(baseline.sampleBytes, directPicture.sampleBytes)
        assertEquals(baseline.subsampleRanges, directPicture.subsampleRanges)
        assertEquals(baseline.contributions, directPicture.contributions)
        val protectedInit = syntheticProtectedInit(outputInit, 16)
        val trackId = requireNotNull(CmafBoxInspector.inspect(protectedInit).tracks.single().trackId)
        fun packagePicture(picture: ProtectedHevcPictureComposer.Result, nativeHeader: Boolean,
                           direct: Boolean = false): ByteArray {
            return ProtectedHevcCmafFragmentWriter.write(protectedInit,
                ProtectedHevcCmafFragmentWriter.Request(trackId, picture.decodeTimeTicks, 1, 16,
                    listOf(ProtectedHevcCmafFragmentWriter.Sample(picture.sampleBytes, picture.subsampleRanges,
                        iv, ProtectedValueFingerprint.fromBytes("iv", iv), picture.durationTicks,
                        picture.sampleFlags, picture.compositionOffsetTicks))),
                preferNativeHeader = nativeHeader,
                onNativeHeaderResult = { assertTrue(it) }, directNativeHeader = direct)
        }
        val expected = packagePicture(baseline, false)
        val actual = packagePicture(candidate, true)
        assertArrayEquals(expected, actual)
        val directFragment = packagePicture(directPicture, true, true)
        assertArrayEquals(expected, directFragment)
        directFragment.fill(0)
        directPicture.sampleBytes.fill(0)
        // Exercise the real borrowed plan -> Rust allocation -> local DASH
        // builder -> Media3 data-source path, not a materialized test sample.
        val nativePublicationPlanner = NativeProtectedPlan(outSets, { true },
            { error("Native publication plan rejected") }, direct = true)
        ProtectedHevcPictureComposer.plan(fresh(), outSps, outPps,
            fullPictureColumns = 2, nativePlan = nativePublicationPlanner).use { picture ->
            val request = ProtectedHevcCmafFragmentWriter.Request(trackId, picture.decodeTimeTicks, 1, 16,
                listOf(ProtectedHevcCmafFragmentWriter.Sample(ByteArray(0), picture.subsampleRanges,
                    iv, ProtectedValueFingerprint.fromBytes("iv", iv), picture.durationTicks,
                    picture.sampleFlags, picture.compositionOffsetTicks, plannedPayload = picture.payload)))
            ProtectedHevcCmafFragmentWriter.writeOwned(protectedInit, request,
                preferNativeHeader = true, onNativeHeaderResult = { assertTrue(it) },
                directNativeHeader = true, preferNativeStorage = true).use { owned ->
                assertNull(owned.heap)
                val memory = requireNotNull(owned.native)
                assertEquals(1, nativePublicationPlanner.matchedBatches)
                assertArrayEquals(expected, memory.copyBytes())
                verifyNativePublication(protectedInit, picture, memory, expected)
            }
        }
        // Crypto-identity rejection precedes any native planning call.
        var nativeCalls = 0
        val forbidden = NativeProtectedPlan(outSets, { true }, { error("Unauthorized native admission") }) { _, _ ->
            nativeCalls++
            error("Incompatible protected metadata entered native planning")
        }
        val different = fresh().toMutableList()
        val last = different.last()
        val encryption = last.fragment.sampleEncryptionMetadata.single()
        different[different.lastIndex] = last.copy(fragment = last.fragment.copy(
            sampleEncryptionMetadata = listOf(encryption.copy(
                ivIdentity = ProtectedValueFingerprint.fromBytes("iv", ByteArray(16) { 91 })))))
        try {
            ProtectedHevcPictureComposer.plan(different, outSps, outPps,
                fullPictureColumns = 2, nativePlan = forbidden).close()
            throw AssertionError("Different IV identities were accepted")
        } catch (_: IllegalArgumentException) { assertEquals(0, nativeCalls) }
        listOf(expected, actual, baseline.sampleBytes, candidate.sampleBytes, protectedInit, iv).forEach { it.fill(0) }
        sources.forEach { it.sampleBytes.fill(0) }
        Log.i("PitwallGB", "GB complete two-source protected-labelled picture and CMAF differential, incompatible IV rejected before native admission PASS")
    }

    /** Native ownership is shared across the history, publication generation
     * and an active Media3 reader. A replacement/release cannot erase bytes
     * pinned by that reader; legacy and cast callers still receive heap data. */
    private fun verifyNativePublication(
        init: ByteArray, picture: ProtectedHevcPictureComposer.PlannedPicture,
        memory: NativeFragmentLease, expected: ByteArray
    ) {
        val cfg = requireNotNull(CmafBoxInspector.inspect(init).tracks.single().hevcConfiguration)
        val sps = cfg.sps.single()
        val history = ProtectedHevcLiveComposition.OutputFragment(1, ByteArray(0),
            picture.timescale, picture.decodeTimeTicks, picture.durationTicks, 1,
            sps.displayWidth, sps.displayHeight, emptyMap(), memory.retain())
        val first = ProtectedHevcLiveComposition.OutputWindow(init.copyOf(), listOf(history.detached()))
        val legacy = history.legacyDetached()
        assertNull(legacy.nativeMedia)
        assertArrayEquals(expected, legacy.mediaFragment)
        legacy.zero()
        val pssh = byteArrayOf(1, 2, 3, 4) // synthetic placeholder, never sent to a CDM
        val builder = ProtectedHevcComposedDashPackageBuilder
        builder.build(first, pssh, picture.decodeTimeTicks, 1L, validationOnly = true).useForTest()
        val source = ProtectedHevcLiveDashSource()
        val reader = source.dataSourceFactory().createDataSource()
        try {
            source.publish(first, pssh)
            first.fragments.forEach { it.nativeMedia?.close() }
            val uri = android.net.Uri.parse("https://apexview.invalid/composed/segment-1.m4s")
            val spec = androidx.media3.datasource.DataSpec.Builder().setUri(uri).build()
            assertEquals(expected.size.toLong(), reader.open(spec))
            val prefix = ByteArray(17)
            assertEquals(prefix.size, reader.read(prefix, 0, prefix.size))
            assertArrayEquals(expected.copyOfRange(0,prefix.size), prefix)
            // A new generation owns a distinct retained lease of the same
            // immutable backing, with no detached whole-fragment copy.
            val next = ProtectedHevcLiveComposition.OutputWindow(init.copyOf(), listOf(history.detached()))
            source.publish(next, pssh)
            history.zero()
            source.release()
            val tail = ByteArray(expected.size-prefix.size)
            var offset = 0
            while (offset < tail.size) {
                val count = reader.read(tail, offset, minOf(4093,tail.size-offset))
                assertTrue(count > 0)
                offset += count
            }
            assertArrayEquals(expected.copyOfRange(prefix.size, expected.size), tail)
            assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT, reader.read(tail,0,1))
            tail.fill(0); prefix.fill(0)
        } finally {
            reader.close()
            source.release()
            history.zero()
            pssh.fill(0)
        }
        // A rejected writer releases its unpublished allocation. Fill all
        // remaining admission slots, then check explicit backpressure.
        try {
            NativeFragmentLease.build(19) { error("synthetic writer rejection") }
            throw AssertionError("Rejected writer published native memory")
        } catch (_: IllegalStateException) { }
        val held = mutableListOf<NativeFragmentLease>()
        try {
            repeat(11) {
                held += requireNotNull(NativeFragmentLease.build(32) { b ->
                    repeat(32) { b.put(0x55.toByte()) }
                })
            }
            assertNull(NativeFragmentLease.build(32) { error("Arena exceeded live-slot limit") })
        } finally { held.forEach { it.close() } }
        // A closed owner cannot be resurrected or read.
        val closed = memory.retain()
        closed.close()
        try {
            closed.retain()
            throw AssertionError("Retired native lease was re-acquired")
        } catch (_: IllegalStateException) { }
        Log.i("PitwallGB", "GB Rust-owned direct CMAF publication, native history sharing, range reads and retired Media3 reader PASS")
    }

    private fun ProtectedHevcComposedDashPackageBuilder.Package.useForTest() {
        try {
            assertTrue(resources.isEmpty())
            assertTrue(nativeResources.isEmpty())
        } finally { zero() }
    }

    /** Every coded picture of the independent tiled fixture, with alternating
     * header-only/three-clear-CABAC-byte synthetic labels when the latter
     * leaves a nonempty opaque tail. Short slices use the supported header-only
     * splice instead. Track POC and require exact native/JVM output equality. */
    private fun verifyProtectedSequence(inits: List<ByteArray>, media: List<ByteArray>, outputInit: ByteArray) {
        val outConfig = requireNotNull(CmafBoxInspector.inspect(outputInit).tracks.single().hevcConfiguration)
        val outSets = outConfig.rawVpsNals + outConfig.rawSpsNals + outConfig.rawPpsNals
        val outSps = outConfig.sps.single()
        val outPps = outConfig.pps.single()
        val iv = ByteArray(16) { 47 }
        val ivIdentity = ProtectedValueFingerprint.fromBytes("iv", iv)
        var headerOnlySlices = 0
        var threeClearSlices = 0
        var shortSliceFallbacks = 0
        val sources = inits.indices.map { feed ->
            val protectedInit = syntheticProtectedInit(inits[feed], 16)
            val protectedReport = CmafBoxInspector.inspect(protectedInit)
            val track = protectedReport.tracks.single()
            val cfg = requireNotNull(track.hevcConfiguration)
            val sps = cfg.sps.single()
            val pps = cfg.pps.single()
            val clearReport = CmafBoxInspector.inspect(inits[feed])
            val timing = CmafBoxInspector.inspect(media[feed], clearReport).fragments.single()
            val data = ByteBuffer.wrap(media[feed])
            data.position(data.int + 8)
            val samples = timing.sampleTimings.mapIndexed { index, t ->
                val sample = ByteArray(requireNotNull(t.sampleSizeBytes).toInt()).also { data.get(it) }
                // This input is genuinely clear test media. Inspect it before
                // applying synthetic CBCS labels, rather than guessing that
                // every encoded slice has at least four CABAC bytes.
                val clearLayout = ProtectedHevcSampleLayout.inspect(sample, 4,
                    listOf(CmafBoxInspector.SubsampleEncryptionRange(sample.size, 0)))
                val access = ProtectedHevcAccessUnitInspector.inspectLengthPrefixedSample(
                    sample, clearLayout, sps, pps, cfg.vps.single())
                require(access.slices.size == clearLayout.nals.size) {
                    "Fixture must contain one independently coded VCL NAL per source tile"
                }
                val ranges = clearLayout.nals.map { nal ->
                    val slice = access.slices.single { it.nalIndex == nal.index }
                    val threeClearEnd = slice.thirdClearCabacByteOffset?.plus(1)
                    val wantsThree = index % 2 != 0
                    val canUseThree = threeClearEnd != null && threeClearEnd < nal.nalLength
                    val useThree = wantsThree && canUseThree
                    val clearEnd = if (useThree) requireNotNull(threeClearEnd)
                        else requireNotNull(slice.firstOpaqueCabacByteOffset)
                    require(clearEnd in 3 until nal.nalLength) {
                        "Invalid synthetic splice feed=$feed picture=$index nal=${nal.index} length=${nal.nalLength} clear=$clearEnd"
                    }
                    if (useThree) threeClearSlices++ else headerOnlySlices++
                    if (wantsThree && !canUseThree) shortSliceFallbacks++
                    CmafBoxInspector.SubsampleEncryptionRange(4 + clearEnd,
                        (nal.nalLength - clearEnd).toLong())
                }
                // Confirm the actual protected inspection agrees with the
                // synthetic labels before giving the sample to either engine.
                val labelledLayout = ProtectedHevcSampleLayout.inspect(sample, 4, ranges)
                val labelled = ProtectedHevcAccessUnitInspector.inspectLengthPrefixedSample(
                    sample, labelledLayout, sps, pps, cfg.vps.single())
                require(labelled.slices.size == access.slices.size)
                labelled.slices.forEach { slice ->
                    val nal = labelledLayout.nals[slice.nalIndex]
                    val expectedClearEnd = if (slice.clearCabacBytes >= 3)
                        requireNotNull(slice.thirdClearCabacByteOffset) + 1
                        else requireNotNull(slice.firstOpaqueCabacByteOffset)
                    require(slice.readyForCurrentHeaderRewriter &&
                        expectedClearEnd == nal.clearNalPrefixBytes &&
                        nal.clearNalPrefixBytes < nal.nalLength)
                }
                ProtectedHevcCmafFragmentWriter.Sample(sample, ranges, iv, ivIdentity,
                    requireNotNull(t.durationTicks), requireNotNull(t.sampleFlags),
                    requireNotNull(t.compositionOffsetTicks))
            }
            assertEquals(media[feed].size, data.position())
            val synthetic = ProtectedHevcCmafFragmentWriter.write(protectedInit,
                ProtectedHevcCmafFragmentWriter.Request(requireNotNull(track.trackId),
                    requireNotNull(timing.baseDecodeTimeTicks), 1, 16, samples))
            val fragment = CmafBoxInspector.inspect(synthetic, protectedReport).fragments.single()
            synthetic.fill(0); protectedInit.fill(0)
            samples.mapIndexed { index, s ->
                ProtectedHevcPictureComposer.FeedSample("sequence-$feed", s.sampleBytes, track,
                    fragment, index, 0, feed*sps.picWidthInCtbs, HevcPocTracker())
            }
        }
        val frameCount = sources.first().size
        assertTrue(frameCount >= 25)
        assertTrue(sources.all { it.size == frameCount })
        assertTrue("Full sequence must exercise the header-only splice", headerOnlySlices > 0)
        assertTrue("Full sequence must exercise the three-clear-byte splice", threeClearSlices > 0)
        assertTrue("Full sequence must exercise the short-slice regression", shortSliceFallbacks > 0)
        assertEquals(frameCount * requireNotNull(outPps.tileColumnWidthsInCtbs).size *
            requireNotNull(outPps.tileRowHeightsInCtbs).size, headerOnlySlices + threeClearSlices)
        val jvmPoc = sources.map { HevcPocTracker() }
        val rustPoc = sources.map { HevcPocTracker() }
        val expectedSamples = mutableListOf<ProtectedHevcCmafFragmentWriter.Sample>()
        val actualSamples = mutableListOf<ProtectedHevcCmafFragmentWriter.Sample>()
        val nativePlans = mutableListOf<ProtectedHevcPictureComposer.PlannedPicture>()
        // Exercise multi-command admission on genuine protected-labelled
        // clear-header fixtures. The normal one-command picture test above
        // remains mandatory; this full sequence forces the bounded split.
        val planner = NativeProtectedPlan(outSets, { true }, { error("Direct sequence rejected") },
            direct = true, maxBatchSlices = 3)
        var firstDts = 0L
        for (frame in 0 until frameCount) {
            val reference = ProtectedHevcPictureComposer.plan(sources.mapIndexed { i, s ->
                s[frame].copy(pocTracker = jvmPoc[i])
            }, outSps, outPps, fullPictureColumns = 2).use { it.materialize() }
            val actualPlan = ProtectedHevcPictureComposer.plan(sources.mapIndexed { i, s ->
                s[frame].copy(pocTracker = rustPoc[i])
            }, outSps, outPps, fullPictureColumns = 2, nativePlan = planner)
            nativePlans += actualPlan
            val actual = actualPlan.materialize()
            assertArrayEquals("picture $frame", reference.sampleBytes, actual.sampleBytes)
            assertEquals(reference.subsampleRanges, actual.subsampleRanges)
            assertEquals(reference.fullPictureOrderCount, actual.fullPictureOrderCount)
            assertEquals(reference.contributions, actual.contributions)
            if (frame == 0) firstDts = reference.decodeTimeTicks
            fun sample(p: ProtectedHevcPictureComposer.Result) = ProtectedHevcCmafFragmentWriter.Sample(
                p.sampleBytes, p.subsampleRanges, iv, ivIdentity, p.durationTicks,
                p.sampleFlags, p.compositionOffsetTicks)
            expectedSamples += sample(reference)
            actualSamples += sample(actual)
        }
        assertEquals(frameCount, planner.matchedMaps)
        assertEquals(frameCount, planner.matchedBatches)
        assertEquals(frameCount * requireNotNull(outPps.tileColumnWidthsInCtbs).size *
            requireNotNull(outPps.tileRowHeightsInCtbs).size, planner.matchedSlices)
        val protectedInit = syntheticProtectedInit(outputInit, 16)
        val trackId = requireNotNull(CmafBoxInspector.inspect(protectedInit).tracks.single().trackId)
        val reference = ProtectedHevcCmafFragmentWriter.write(protectedInit,
            ProtectedHevcCmafFragmentWriter.Request(trackId, firstDts, 1, 16, expectedSamples))
        val result = ProtectedHevcCmafFragmentWriter.write(protectedInit,
            ProtectedHevcCmafFragmentWriter.Request(trackId, firstDts, 1, 16, actualSamples),
            preferNativeHeader = true, onNativeHeaderResult = { assertTrue(it) }, directNativeHeader = true)
        assertArrayEquals(reference, result)
        // The full 50-picture sequence also uses the real borrowed-payload
        // writer directly into native-owned storage, not just sample arrays.
        val borrowed = nativePlans.map { picture ->
            ProtectedHevcCmafFragmentWriter.Sample(ByteArray(0), picture.subsampleRanges, iv, ivIdentity,
                picture.durationTicks, picture.sampleFlags, picture.compositionOffsetTicks,
                plannedPayload = picture.payload)
        }
        try {
            ProtectedHevcCmafFragmentWriter.writeOwned(protectedInit,
                ProtectedHevcCmafFragmentWriter.Request(trackId, firstDts, 1, 16, borrowed),
                preferNativeHeader = true, onNativeHeaderResult = { assertTrue(it) },
                directNativeHeader = true, preferNativeStorage = true).use { owned ->
                assertNull(owned.heap)
                val lease = requireNotNull(owned.native)
                assertArrayEquals(reference, lease.copyBytes())
            }
        } finally { nativePlans.forEach { it.close() } }
        listOf(reference, result, protectedInit, iv).forEach { it.fill(0) }
        (expectedSamples + actualSamples).forEach { it.sampleBytes.fill(0) }
        sources.flatten().forEach { it.sampleBytes.fill(0) }
        Log.i("PitwallGB", "GB direct protected-labelled sequence: frames=$frameCount slices=${planner.matchedSlices} " +
            "headerOnly=$headerOnlySlices threeClear=$threeClearSlices shortSliceFallbacks=$shortSliceFallbacks " +
            "exact POC/sample/fragment parity PASS")
    }

    private fun syntheticProtectedInit(clear: ByteArray, ivWidth: Int): ByteArray {
        val entryStart = findType(clear, "stsd") + 12
        val entrySize = ByteBuffer.wrap(clear).getInt(entryStart)
        val end = entryStart + entrySize
        val constantIv = if (ivWidth == 0) byteArrayOf(16) + ByteArray(16) { 23 } else ByteArray(0)
        val tenc = box("tenc", byteArrayOf(1, 0, 0, 0, 0, 0x19, 1, ivWidth.toByte()) +
            ByteArray(16) { 35 } + constantIv)
        val sinf = box("sinf", box("frma", "hvc1".toByteArray(Charsets.US_ASCII)) +
            box("schm", byteArrayOf(0, 0, 0, 0) + "cbcs".toByteArray(Charsets.US_ASCII) + u32(0x00010000)) +
            box("schi", tenc))
        val altered = clear.copyOfRange(0, end) + sinf + clear.copyOfRange(end, clear.size)
        "encv".toByteArray(Charsets.US_ASCII).copyInto(altered, entryStart + 4)
        ByteBuffer.wrap(altered).putInt(entryStart, entrySize + sinf.size)
        for (name in listOf("moov", "trak", "mdia", "minf", "stbl", "stsd")) {
            val start = findType(clear, name) - 4
            ByteBuffer.wrap(altered).putInt(start, ByteBuffer.wrap(clear).getInt(start) + sinf.size)
        }
        return altered + box("pssh", byteArrayOf(0, 0, 0, 0) + ByteArray(16) + u32(0))
    }

    private fun findType(data: ByteArray, name: String): Int {
        val type = name.toByteArray(Charsets.US_ASCII)
        return (4..data.size - type.size).first { i -> type.indices.all { data[i + it] == type[it] } }
    }
    private fun u32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun box(type: String, data: ByteArray) =
        u32(data.size + 8) + type.toByteArray(Charsets.US_ASCII) + data

    private fun await(engine: NativeStitchingEngine, token: Long): Long {
        val stop = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < stop) {
            val result = engine.acquire(token)
            if (result != 0L) return result
            Thread.sleep(2)
        }
        throw AssertionError("Clear native atlas worker timeout")
    }
}
