package app.apexview.cast

import app.apexview.Settings
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CastTimingSeekGenerationTest {
    private val playback = "seek-generation-test"

    private fun table(id: Long, epoch: Long?, driver: String = "1", rank: Int = 1): JSONObject {
        val row = JSONArray().put(driver).put(rank).put(false).put(false)
            .put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL)
            .put(JSONArray().put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL))
            .put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL)
            .put(JSONObject.NULL).put(0).put(JSONObject.NULL).put(JSONObject.NULL)
        return JSONObject().put("version", 1).put("kind", "table")
            .put("playbackId", playback).put("id", id)
            .put("rows", JSONArray().put(row)).put("stoppedDrivers", JSONArray())
            .apply { if (epoch != null) put("clockEpoch", epoch) }
    }

    private fun frame(sequence: Long, id: Long, epoch: Long?, position: Long): JSONObject =
        JSONObject("""{
            "version":1,"kind":"frame","geometry":"","utc":1000,
            "density":1,"fontScale":1,"mapMode":"OFF","map":null,"tower":null,
            "rotation":0,"mapOpacity":1,"dot":3,"label":8,
            "towerOpacity":1,"towerFont":1,"towerHeader":0,
            "tv":true,"onTop":null,"leader":null,"cars":[],
            "condition":0,"yellow":[],"resumption":null,"stop":null,"circle":null,
            "header":["Race","", -1, "", []],"message":null
        }""").put("playbackId", playback).put("sequence", sequence)
            .put("tableId", id).put("position", position).put("speedUnit", Settings.KMH)
            .apply { if (epoch != null) put("clockEpoch", epoch) }

    @Test fun lateTableCannotRestoreOldRanksAndMotionCanContinueBeforeFreshTable() = runBlocking {
        var time = 1000L
        val receiver = CastTimingReceiver { time }
        receiver.acceptAsync(table(10, 0), playback)
        receiver.acceptAsync(frame(1, 10, 0, 1000), playback)
        assertEquals("1", receiver.currentTable!!.rows.single().first)
        receiver.onSeek(1000, 50000)
        assertNull(receiver.frame)
        assertNull(receiver.currentTable)
        assertEquals(-1L, receiver.health().lastTableAgeMs)

        time += 200
        receiver.acceptAsync(table(11, 0, "44"), playback)
        receiver.acceptAsync(frame(2, 10, 0, 1200), playback)
        assertNull(receiver.currentTable)
        assertNull(receiver.frame)
        assertEquals(1L, receiver.health().tablesAccepted)

        receiver.acceptAsync(frame(3, 11, 1, 50000), playback)
        assertEquals(50000L, receiver.frame!!.positionMs)
        assertTrue(receiver.frame!!.table.rows.isEmpty())
        assertNull(receiver.currentTable)
        receiver.acceptAsync(table(11, 1, "63", 2), playback)
        assertEquals("63", receiver.currentTable!!.rows.single().first)
        receiver.acceptAsync(table(12, 0, "44"), playback)
        assertEquals("63", receiver.currentTable!!.rows.single().first)
        receiver.acceptAsync(frame(4, 11, 1, 50200), playback)
        assertEquals("63", receiver.frame!!.table.rows.single().first)

        receiver.onSeek(50200, 2000)
        receiver.acceptAsync(table(11, 2, "63", 2), playback) // unchanged row version, new epoch
        receiver.acceptAsync(frame(5, 11, 2, 2000), playback)
        assertEquals(2L, receiver.clockEpoch)
        assertEquals("63", receiver.frame!!.table.rows.single().first)
        assertEquals(3L, receiver.health().tablesAccepted)
        receiver.clear()
        assertNull(receiver.currentTable)
        assertNull(receiver.frame)
    }

    @Test fun firstFrameCannotInheritTableFromDifferentEpochAndLegacyStillConnects() {
        val receiver = CastTimingReceiver { 1000L }
        receiver.accept(table(3, 4, "44"), playback)
        receiver.accept(frame(1, 3, 5, 1000), playback)
        assertEquals(5L, receiver.clockEpoch)
        assertNull(receiver.currentTable)
        assertTrue(receiver.frame!!.table.rows.isEmpty())
        receiver.accept(table(3, 5, "63"), playback)
        assertEquals("63", receiver.currentTable!!.rows.single().first)
        receiver.accept(table(4, null, "44"), playback)
        assertEquals("63", receiver.currentTable!!.rows.single().first)

        receiver.clear()
        receiver.accept(table(3, null, "44"), playback)
        receiver.accept(frame(1, 3, null, 1000), playback)
        assertEquals("44", receiver.frame!!.table.rows.single().first)
        receiver.onSeek(1000, 50000)
        receiver.accept(frame(2, 3, null, 1200), playback)
        assertNull(receiver.frame)
        receiver.accept(frame(3, 3, null, 50000), playback)
        assertEquals("44", receiver.frame!!.table.rows.single().first)
    }
}
