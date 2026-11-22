package app.apexview

import app.apexview.cast.CastTimingProtocol
import app.apexview.cast.CastTimingReceiver
import app.apexview.cast.local.FcastProtocol
import app.apexview.cast.local.TimingMessageChunks
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CastTimingProtocolTest {
    private fun timing() = SessionTiming("Race", mapOf("1" to DriverInfo("1", "VER", 0xFF0099CC.toInt())),
        mapOf("1" to DriverTrack(longArrayOf(1000, 1200), floatArrayOf(0f, 20f), floatArrayOf(0f, 10f), 2)),
        emptyMap(), floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f), true,
        listOf(Corner(1, 100f, 0f)), 1000, marshalSectors = listOf(Corner(1, 0f, 0f)))
    private fun frame(id: String, mode: MapMode = MapMode.TRACK) = JSONObject().put("version", 1).put("kind", "frame")
        .put("geometry", id).put("position", 5000).put("utc", 1000).put("density", 3).put("fontScale", 1)
        .put("mapMode", mode.name).put("map", JSONArray(listOf(100, 50, 400, 300)))
        .put("tower", JSONArray(listOf(900, 0, 350, 700))).put("rotation", 20).put("mapOpacity", .75)
        .put("dot", 5).put("label", 8).put("towerOpacity", .9).put("towerFont", 1.2).put("towerHeader", 30)
        .put("tv", true).put("speedUnit", Settings.KMH).put("onTop", "1").put("leader", "1")
        .put("cars", JSONArray().put(JSONArray(listOf("1", 0, 0, 20, 10, LapState.PUSH.ordinal))))
        .put("condition", TrackCondition.SAFETY_CAR.ordinal).put("yellow", JSONArray().put(JSONArray(listOf(1, 2))))
        .put("stop", JSONArray(listOf("1", .5, .25, "44", 2100))).put("circle", JSONObject.NULL)
        .put("rows", JSONArray().put(JSONArray().put("1").put(1).put(false).put(false).put(2)
            .put(JSONArray(listOf("1:30.001", Pace.PURPLE.ordinal, JSONArray(), "")))
            .put(JSONArray(listOf("1:29.001", Pace.GREEN.ordinal, JSONArray(), "")))
            .put(JSONArray().put(JSONArray(listOf("28.001", Pace.GREEN.ordinal, JSONArray(listOf(2048, 2049)), "27.99"))).put(JSONObject.NULL).put(JSONObject.NULL))
            .put("+1.0").put("+2.0").put("+1.0").put("SOFT").put(7).put(300).put(8)))
        .put("header", JSONArray(listOf("RACE", "SAFETY CAR", -1, "L8/71", JSONArray(listOf("W 4.0 N", "T 36°", "A 28°", "H 70%", "P 1010", "DRY")))))
        .put("message", JSONObject.NULL)
    @Test fun sharedDisplayStatePreservesGeometryRowsFlagsAndInterpolatesAgainstTheTvClock() {
        val source = timing()
        val encoded = CastTimingProtocol.geometry(source)
        val geometry = CastTimingProtocol.decodeGeometry(encoded)
        assertArrayEquals(source.outline, geometry.timing.outline, 0f)
        assertEquals("VER", geometry.timing.drivers.getValue("1").tla)
        assertEquals(source.drivers.getValue("1").color, geometry.timing.drivers.getValue("1").color)
        assertTrue(geometry.timing.tracks.isEmpty()) // Full position/timing histories never cross the LAN.
        val json = frame(geometry.id)
        val decoded = CastTimingProtocol.decodeFrame(json)
        assertEquals(10f, decoded.carsAt(5100).single().x, 0f)
        assertEquals(0f, decoded.carsAt(5000).single().x, 0f)
        assertEquals(20f, decoded.carsAt(20000).single().x, 0f) // Delayed telemetry holds the endpoint.
        assertEquals(2, decoded.flags.yellow[1])
        assertEquals(TrackCondition.SAFETY_CAR, decoded.flags.track)
        assertEquals(2100, decoded.stop!!.outBehind!!.second.toInt())
        assertEquals("SOFT", decoded.table.tyres.getValue("1")!!.compound)
        assertEquals(listOf(2048, 2049), decoded.table.rows.single().second.sectors.first()!!.segments)
        assertSame(decoded.table, CastTimingProtocol.decodeFrame(json, decoded.table).table)
        assertEquals(.75f, decoded.mapOpacity, 0f)
        assertFalse(encoded.toString().contains("token", true))
    }
    @Test fun castMarkersUseContinuousMeasuredMotionWithoutOvershootingTurns() {
        val motion = CastTimingProtocol.CarMotion("1", 0f, 0f, 20f, 10f,
            LapState.PUSH, listOf(35f to 30f, 50f to 50f, 55f to 50f))
        assertEquals(0f, motion.at(0f).x, 0.00001f)
        assertEquals(20f, motion.at(1f).x, 0.00001f)
        assertEquals(35f, motion.at(2f).x, 0.00001f)
        assertEquals(50f, motion.at(3f).x, 0.00001f)
        assertEquals(55f, motion.at(4f).x, 0.00001f)
        assertEquals(55f, motion.at(100f).x, 0.00001f)
        val around = listOf(0f, .25f, .5f, .75f, 1f, 1.25f, 1.5f,
            1.75f, 2f, 2.5f, 3f, 3.25f, 3.5f, 3.75f, 4f)
        val xs = around.map { motion.at(it).x }
        assertTrue(xs.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(xs.all { it in 0f..55f })
        // Derivative at the 200ms interior boundary stays continuous,
        // unlike piecewise linear marker motion with changing speed.
        val before = motion.at(2f).x - motion.at(1.99f).x
        val after = motion.at(2.01f).x - motion.at(2f).x
        assertEquals(before, after, 0.005f)
        val stopped = CastTimingProtocol.CarMotion("2", 0f, 0f, 0f, 0f,
            LapState.PIT, listOf(0f to 0f, 10f to 0f))
        assertEquals(0f, stopped.at(1.5f).x, 0.00001f)
    }

    @Test fun delayedMotionUsesTheNextSamplesWithoutExtrapolatingPastThem() {
        val j = frame(CastTimingProtocol.geometry(timing()).getString("id"))
        j.getJSONArray("cars").getJSONArray(0).put(35).put(30).put(50).put(50)
        val state = CastTimingProtocol.decodeFrame(j)
        assertEquals(10f, state.carsAt(5100).single().x, 0f)
        assertEquals(27.5f, state.carsAt(5300).single().x, 0f)
        assertEquals(40f, state.carsAt(5500).single().y, 0f)
        assertEquals(50f, state.carsAt(9000).single().x, 0f)
    }

    @Test fun delayedPacketUsesSixMeasuredStepsAndRejectsUnboundedMotion() {
        val packet = frame(CastTimingProtocol.geometry(timing()).getString("id"))
        val values = packet.getJSONArray("cars").getJSONArray(0)
        // Step 0..6: exact measured endpoints, never invented extrapolation.
        for (step in 2..CastTimingProtocol.MAX_PREDICTION_STEPS) {
            values.put((step * 10).toFloat())
            values.put((step * 5).toFloat())
        }
        val decoded = CastTimingProtocol.decodeFrame(packet)
        assertEquals(35f, decoded.carsAt(5700).single().x, 0.0001f)
        assertEquals(60f, decoded.carsAt(6200).single().x, 0.0001f)
        assertEquals(60f, decoded.carsAt(7200).single().x, 0.0001f)
        assertThrows(IllegalArgumentException::class.java) {
            CastTimingProtocol.decodeFrame(JSONObject(packet.toString()).apply {
                getJSONArray("cars").getJSONArray(0).put(70).put(35)
            })
        }
        // Six additional endpoints are optional; older receivers' payloads
        // retain their exact historical interpolation semantics.
        assertEquals(10f, CastTimingProtocol.decodeFrame(
            frame(CastTimingProtocol.geometry(timing()).getString("id"))
        ).carsAt(5100).single().x, 0.0001f)
    }

    @Test fun pitwallCastSenderUsesExactNativeOverlayPositionsAfterSeekAndAtLatePackets() {
        val source = timing()
        val sampler = OverlayPositionSampler(source)
        val decoded = CastTimingProtocol.decodeFrame(frame(CastTimingProtocol.geometry(source).getString("id")))
        val sourceClock = listOf(1_000L, 1_020L, 1_100L, 1_180L, 1_200L, 1_080L)
        for (utc in sourceClock) {
            val native = sampler.sample(utc).single()
            val legacy = carsAt(source, utc).single()
            val receiver = decoded.carsAt(5_000L + utc - 1_000L).single()
            assertEquals(legacy.x, native.x, .00001f)
            assertEquals(legacy.y, native.y, .00001f)
            assertEquals(native.x, receiver.x, .00001f)
            assertEquals(native.y, receiver.y, .00001f)
            assertEquals(native.number, receiver.number)
        }
        // Real Cast packet structure is unchanged by the native sampler refactor.
        val packet = frame(CastTimingProtocol.geometry(source).getString("id"))
        assertTrue(packet.has("cars"))
        assertTrue(packet.has("rows"))
        assertTrue(packet.has("mapMode"))
        assertTrue(packet.has("tower"))
        assertTrue(packet.has("onTop"))
    }

    @Test fun independentEmptyTopicsAndHideUpdatesDoNotEraseTheOtherPanel() {
        val geo = CastTimingProtocol.geometry(timing())
        val j = frame(geo.getString("id")).put("cars", JSONArray()).put("mapMode", "OFF").put("map", JSONObject.NULL)
        val state = CastTimingProtocol.decodeFrame(j)
        assertEquals(1, state.table.rows.size)
        assertNull(state.map)
        val mapOnly = frame(geo.getString("id")).put("rows", JSONArray()).put("tower", JSONObject.NULL)
        assertEquals(1, CastTimingProtocol.decodeFrame(mapOnly).cars.size)
        assertTrue(CastTimingProtocol.decodeFrame(mapOnly).table.rows.isEmpty())
    }
    @Test fun largeCurrentTablesRoundTripInsideStandardFcastFramesAndAssemblerIsBounded() {
        var time = 100L
        val assembler = TimingMessageChunks.Assembler { time }
        val original = JSONObject().put("kind", "table").put("rows", "VER".repeat(26000))
        val packets = TimingMessageChunks.split(original)
        assertTrue(packets.size > 1)
        var complete: JSONObject? = null
        packets.forEach { j ->
            val bytes = ByteArrayOutputStream().apply { FcastProtocol.write(this, FcastProtocol.Packet(FcastProtocol.TIMING, j)) }.toByteArray()
            assertTrue(bytes.size <= 32004)
            complete = assembler.accept(FcastProtocol.read(ByteArrayInputStream(bytes))!!.body!!)
        }
        assertEquals(original.toString(), complete!!.toString())
        assembler.accept(packets.first()); time += 6000
        assertThrows(IllegalArgumentException::class.java) { assembler.accept(packets[1]) }
        assertThrows(IllegalArgumentException::class.java) { TimingMessageChunks.split(JSONObject().put("rows", "x".repeat(128001))) }
    }
    @Test fun staleMediaLoadAndOutOfOrderTimingFramesCannotOverwriteTheCurrentUi() {
        val receiver = CastTimingReceiver()
        val geo = CastTimingProtocol.geometry(timing()).put("playbackId", "current")
        receiver.accept(geo, "current")
        val j = frame(geo.getString("id")).put("playbackId", "current").put("tableId", 1).put("sequence", 2)
        receiver.accept(JSONObject().put("version", 1).put("kind", "table").put("id", 1).put("playbackId", "current").put("rows", j.getJSONArray("rows")), "current")
        receiver.accept(j, "current")
        val first = receiver.frame
        receiver.accept(frame(geo.getString("id")).put("playbackId", "old").put("tableId", 1).put("sequence", 3), "current")
        receiver.accept(frame(geo.getString("id")).put("playbackId", "current").put("tableId", 1).put("sequence", 1), "current")
        assertSame(first, receiver.frame)
        receiver.onSeek(5000, 25000)
        receiver.accept(JSONObject(j.toString()).put("sequence", 3).put("clockEpoch", 0), "current")
        assertNull(receiver.frame)
        receiver.accept(JSONObject(j.toString()).put("sequence", 4).put("position", 25000).put("clockEpoch", 1), "current")
        assertEquals(25000L, receiver.frame!!.positionMs)
        assertSame(first!!.table, receiver.frame!!.table)
        receiver.clear(); assertNull(receiver.frame); assertNull(receiver.geometry)
    }
    @Test fun refreshedTimingTableIsVisibleEvenBeforeNextMotionFrame() {
        val receiver = CastTimingReceiver()
        val geometry = CastTimingProtocol.geometry(timing()).put("playbackId", "new")
        val id = geometry.getString("id")
        receiver.accept(geometry, "new")
        val firstRows = frame(id).getJSONArray("rows")
        receiver.accept(JSONObject().put("kind", "table").put("version", 1)
            .put("playbackId", "new").put("id", 1).put("rows", firstRows), "new")
        receiver.accept(frame(id).put("playbackId", "new").put("tableId", 1)
            .put("sequence", 1).put("clockEpoch", 0), "new")
        val previousFrame = receiver.frame
        val nextRows = JSONArray(firstRows.toString())
        nextRows.getJSONArray(0).put(13, 285)
        receiver.accept(JSONObject().put("kind", "table").put("version", 1)
            .put("playbackId", "new").put("id", 2).put("rows", nextRows), "new")
        assertSame("A map packet need not arrive for a new tower table", previousFrame, receiver.frame)
        assertEquals(285, receiver.currentTable!!.speeds.getValue("1").first)
        val health = receiver.health()
        assertEquals(2L, health.tablesAccepted)
        receiver.accept(JSONObject().put("kind", "table").put("version", 1)
            .put("playbackId", "new").put("id", 2).put("rows", nextRows), "new")
        assertEquals(2L, receiver.health().tablesAccepted)
        assertTrue("Same-version tower heartbeat remains fresh",
            receiver.health().lastTableAgeMs >= 0L)
        assertEquals(1L, health.framesAccepted)
        // A frame using the old table must not overwrite a newer tower table.
        receiver.accept(frame(id).put("playbackId", "new").put("tableId", 1)
            .put("sequence", 2).put("clockEpoch", 0), "new")
        assertEquals(1L, receiver.health().tableVersionRejects)
        assertSame(previousFrame, receiver.frame)
    }

    @Test fun malformedTelemetryIsRejectedBeforeRenderAllocation() {
        val id = CastTimingProtocol.geometry(timing()).getString("id")
        assertThrows(IllegalArgumentException::class.java) { CastTimingProtocol.decodeFrame(frame(id).put("density", 0)) }
        assertThrows(IllegalArgumentException::class.java) { CastTimingProtocol.decodeFrame(frame(id).put("map", JSONArray(listOf(0, 0, -1, 600)))) }
        assertThrows(IllegalArgumentException::class.java) { CastTimingProtocol.decodeGeometry(CastTimingProtocol.geometry(timing()).put("outline", "x".repeat(40000))) }
    }
}
