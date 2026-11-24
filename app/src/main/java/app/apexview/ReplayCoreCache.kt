package app.apexview

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Indexed data needed to draw both replay overlays and seek without parsing the source again. */
internal class ReplayCore(
    val path: String,
    val type: String,
    val drivers: Map<String, DriverInfo>,
    val tracks: Map<String, DriverTrack>,
    val states: Map<String, StateLog>,
    val outline: FloatArray,
    val hasStartFinish: Boolean,
    val startUtc: Long,
    val laps: Map<String, DriverLaps>,
    val parts: List<Pair<Long, Int>>,
)

/** Small, disposable cache of completed archives. The original topics remain the source of truth. */
internal class ReplayCoreCache(directory: File, private val contentId: Long) {
    private val folder = File(directory, "replay-timing")
    private val file = File(folder, "$contentId.bin")
    private val preview = File(File(directory, "replay-previews"), "$contentId.bin")

    fun read(checkActive: () -> Unit): ReplayCore? = readFile(file, null, checkActive)

    fun deletePreview() {
        preview.delete()
    }

    fun readPreview(positionMs: Long, checkActive: () -> Unit): ReplayCore? =
        readFile(preview, positionMs, checkActive)

    private fun readFile(file: File, previewPositionMs: Long?, checkActive: () -> Unit): ReplayCore? {
        if (file.length() !in 1..MAX_BYTES || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) return null
        return try {
            DataInputStream(file.inputStream().buffered(BUFFER_SIZE)).use { input ->
                if (input.readInt() != VERSION || input.readLong() != contentId) return null
                if (previewPositionMs != null &&
                    kotlin.math.abs(input.readLong() - previewPositionMs) > PREVIEW_REUSE_MS
                ) return null
                val path = input.readUTF()
                val type = input.readUTF()
                val startUtc = input.readLong()
                val hasStartFinish = input.readBoolean()
                val outline = FloatArray(input.count(8192)) { input.readFloat() }
                val drivers = input.readByDriver {
                    DriverInfo(readUTF(), readUTF(), readInt(), readUTF(), readUTF(), readUTF())
                }
                val samples = ByteBuffer.allocate(TRACK_BLOCK_SAMPLES * TRACK_SAMPLE_BYTES)
                val tracks = input.readByDriver {
                    val n = count(MAX_SAMPLES)
                    val t = LongArray(n)
                    val x = FloatArray(n)
                    val y = FloatArray(n)
                    for (first in 0 until n step TRACK_BLOCK_SAMPLES) {
                        checkActive()
                        val end = minOf(first + TRACK_BLOCK_SAMPLES, n)
                        readFully(samples.array(), 0, (end - first) * TRACK_SAMPLE_BYTES)
                        samples.clear()
                        for (i in first until end) {
                            t[i] = samples.long
                            x[i] = samples.float
                            y[i] = samples.float
                        }
                    }
                    DriverTrack(t, x, y, n)
                }
                val states = input.readByDriver {
                    val times = readTimes()
                    StateLog(times, Array(times.size) { LapState.entries[readUnsignedByte()] })
                }
                val laps = input.readByDriver {
                    val times = readTimes()
                    val sectors = List(count(MAX_SAMPLES)) { readSector() }
                    fun cell(): SectorCell? {
                        val index = readInt()
                        return if (index == -1) null else sectors[index]
                    }
                    val snapshots = List(times.size) { index ->
                        if (index % 1024 == 0) checkActive()
                        DriverLaps.Snapshot(
                            position = readInt().takeIf { it >= 0 },
                            sectors = List(3) { cell() },
                            lapSectors = List(3) { cell() },
                            lastLap = cell(),
                            gap = readNullableString(),
                            inPit = readBoolean(),
                            toLeader = readNullableString(),
                            ahead = readNullableString(),
                            retired = readBoolean(),
                            bestLap = cell(),
                        )
                    }
                    val crossings = readTimes()
                    val lapTimes = readTimes()
                    val lapCounts = IntArray(lapTimes.size) { readInt() }
                    DriverLaps(times, snapshots, crossings, lapTimes, lapCounts)
                }
                val parts = List(input.count(MAX_SAMPLES)) { input.readLong() to input.readInt() }
                if (outline.size < 8 || outline.size % 2 != 0 || drivers.isEmpty() ||
                    tracks.isEmpty() || laps.isEmpty() || input.read() != -1
                ) return null
                ReplayCore(path, type, drivers, tracks, states, outline, hasStartFinish, startUtc, laps, parts)
            }
        } catch (_: IOException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    fun write(core: ReplayCore, checkActive: () -> Unit): Boolean = writeFile(file, core, null, checkActive)

    fun writePreview(session: SessionTiming, positionMs: Long, utcMs: Long, checkActive: () -> Unit): Boolean {
        val path = session.radioBase.removePrefix(LiveTimingArchive.BASE)
        // A progressive archive snapshot is safe for a bounded restart preview only
        // when the tracks and tower contain actual data at the target UTC.
        // The checks below enforce that, including for archivePartial snapshots.
        if (path == session.radioBase || path.isBlank() || session.live) return false
        if (session.tracks.values.none { it.at(utcMs) != null } ||
            session.laps.values.none { it.at(utcMs)?.position != null }
        ) return false
        val from = utcMs - 90_000L
        val until = utcMs + 120_000L
        val tracks = session.tracks.mapNotNull { (number, track) ->
            checkActive()
            val range = previewRange(track.t, track.n, from, until) ?: return@mapNotNull null
            val end = range.last + 1
            number to DriverTrack(track.t.copyOfRange(range.first, end), track.x.copyOfRange(range.first, end),
                track.y.copyOfRange(range.first, end), range.count())
        }.toMap()
        val states = session.states.mapNotNull { (number, state) ->
            val range = previewRange(state.t, state.t.size, from, until) ?: return@mapNotNull null
            number to StateLog(state.t.copyOfRange(range.first, range.last + 1), state.s.copyOfRange(range.first, range.last + 1))
        }.toMap()
        val laps = session.laps.mapNotNull { (number, history) ->
            checkActive()
            val range = previewRange(history.times, history.times.size, from, until) ?: return@mapNotNull null
            val end = range.last + 1
            number to DriverLaps(history.times.copyOfRange(range.first, end), history.states.subList(range.first, end),
                history.crossings, history.lapTimes, history.lapCounts)
        }.toMap()
        val core = ReplayCore(path, session.type, session.drivers, tracks, states, session.outline,
            session.hasStartFinish, session.startUtcMs, laps, emptyList())
        return writeFile(preview, core, positionMs, checkActive)
    }

    private fun previewRange(times: LongArray, size: Int, from: Long, until: Long): IntRange? {
        if (size == 0) return null
        val left = java.util.Arrays.binarySearch(times, 0, size, from)
        val right = java.util.Arrays.binarySearch(times, 0, size, until)
        val first = (if (left < 0) -left - 2 else left).coerceIn(0, size - 1)
        val last = (if (right < 0) -right - 1 else right).coerceIn(first, size - 1)
        return first..last
    }

    private fun writeFile(file: File, core: ReplayCore, previewPositionMs: Long?, checkActive: () -> Unit): Boolean {
        if (core.outline.size < 8 || core.drivers.isEmpty() || core.tracks.isEmpty() || core.laps.isEmpty()) return false
        val destination = file.parentFile ?: return false
        destination.mkdirs()
        val temporary = try {
            File.createTempFile("timing-", ".tmp", destination)
        } catch (_: IOException) {
            return false
        }
        try {
            DataOutputStream(temporary.outputStream().buffered(BUFFER_SIZE)).use { output ->
                output.writeInt(VERSION)
                output.writeLong(contentId)
                if (previewPositionMs != null) output.writeLong(previewPositionMs)
                output.writeUTF(core.path)
                output.writeUTF(core.type)
                output.writeLong(core.startUtc)
                output.writeBoolean(core.hasStartFinish)
                output.writeInt(core.outline.size)
                core.outline.forEach { output.writeFloat(it) }
                output.writeByDriver(core.drivers) { driver ->
                    writeUTF(driver.number)
                    writeUTF(driver.tla)
                    writeInt(driver.color)
                    writeUTF(driver.firstName)
                    writeUTF(driver.lastName)
                    writeUTF(driver.team)
                }
                val samples = ByteBuffer.allocate(TRACK_BLOCK_SAMPLES * TRACK_SAMPLE_BYTES)
                output.writeByDriver(core.tracks) { track ->
                    writeInt(track.n)
                    for (first in 0 until track.n step TRACK_BLOCK_SAMPLES) {
                        checkActive()
                        samples.clear()
                        for (i in first until minOf(first + TRACK_BLOCK_SAMPLES, track.n)) {
                            samples.putLong(track.t[i])
                            samples.putFloat(track.x[i])
                            samples.putFloat(track.y[i])
                        }
                        write(samples.array(), 0, samples.position())
                    }
                }
                output.writeByDriver(core.states) { state ->
                    writeTimes(state.t)
                    state.s.forEach { writeByte(it.ordinal) }
                }
                output.writeByDriver(core.laps) { history ->
                    writeTimes(history.times)
                    // Snapshots share sector cells; store each object once and refer to its index.
                    val cells = LinkedHashMap<SectorCell, Int>()
                    fun remember(cell: SectorCell?) {
                        if (cell != null) cells.getOrPut(cell) { cells.size }
                    }
                    history.states.forEach { snapshot ->
                        snapshot.sectors.forEach(::remember)
                        snapshot.lapSectors.forEach(::remember)
                        remember(snapshot.lastLap)
                        remember(snapshot.bestLap)
                    }
                    writeInt(cells.size)
                    cells.keys.forEach { writeSector(it) }
                    fun cell(value: SectorCell?) = writeInt(cells[value] ?: -1)
                    history.states.forEachIndexed { index, snapshot ->
                        if (index % 1024 == 0) checkActive()
                        writeInt(snapshot.position ?: -1)
                        snapshot.sectors.forEach(::cell)
                        snapshot.lapSectors.forEach(::cell)
                        cell(snapshot.lastLap)
                        writeNullableString(snapshot.gap)
                        writeBoolean(snapshot.inPit)
                        writeNullableString(snapshot.toLeader)
                        writeNullableString(snapshot.ahead)
                        writeBoolean(snapshot.retired)
                        cell(snapshot.bestLap)
                    }
                    writeTimes(history.crossings)
                    writeTimes(history.lapTimes)
                    history.lapCounts.forEach { writeInt(it) }
                }
                output.writeInt(core.parts.size)
                core.parts.forEach { (time, part) ->
                    output.writeLong(time)
                    output.writeInt(part)
                }
            }
            checkActive()
            if (temporary.length() > MAX_BYTES || !temporary.renameTo(file)) return false
            val maxFiles = if (previewPositionMs == null) 6 else 12
            val budget = if (previewPositionMs == null) 192L * 1024 * 1024 else 16L * 1024 * 1024
            var bytes = file.length()
            destination.listFiles { entry -> entry != file && entry.extension == "bin" }
                ?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, entry ->
                    bytes += entry.length()
                    if (index >= maxFiles - 1 || bytes > budget) entry.delete()
                }
            return true
        } catch (_: IOException) {
            return false
        } finally {
            temporary.delete()
        }
    }

    private fun DataInputStream.count(max: Int): Int = readInt().also {
        if (it !in 0..max) throw IOException("Invalid replay cache count")
    }

    private fun DataInputStream.readTimes() = LongArray(count(MAX_SAMPLES)) { readLong() }

    private fun DataOutputStream.writeTimes(times: LongArray) {
        writeInt(times.size)
        times.forEach { writeLong(it) }
    }

    private fun DataInputStream.readSector() = SectorCell(
        time = readUTF(),
        pace = Pace.entries[readUnsignedByte()],
        previousTime = readUTF(),
        segments = List(count(128)) { readInt() },
    )

    private fun DataOutputStream.writeSector(cell: SectorCell) {
        writeUTF(cell.time)
        writeByte(cell.pace.ordinal)
        writeUTF(cell.previousTime)
        writeInt(cell.segments.size)
        cell.segments.forEach { writeInt(it) }
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun <T> DataInputStream.readByDriver(readValue: DataInputStream.() -> T): Map<String, T> {
        val size = count(64)
        val result = LinkedHashMap<String, T>(size)
        repeat(size) { result[readUTF()] = readValue() }
        return result
    }

    private fun <T> DataOutputStream.writeByDriver(
        values: Map<String, T>,
        writeValue: DataOutputStream.(T) -> Unit,
    ) {
        writeInt(values.size)
        values.forEach { (number, value) ->
            writeUTF(number)
            writeValue(value)
        }
    }

    private companion object {
        const val VERSION = 2
        const val PREVIEW_REUSE_MS = 45_000L
        const val TRACK_BLOCK_SAMPLES = 4096
        const val TRACK_SAMPLE_BYTES = Long.SIZE_BYTES + 2 * Int.SIZE_BYTES
        const val BUFFER_SIZE = 128 * 1024
        const val MAX_BYTES = 64L * 1024 * 1024
        const val MAX_SAMPLES = 200_000
        const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    }
}
