package app.apexview

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

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

    fun read(checkActive: () -> Unit): ReplayCore? {
        if (file.length() !in 1..MAX_BYTES || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) return null
        return try {
            DataInputStream(file.inputStream().buffered(BUFFER_SIZE)).use { input ->
                if (input.readInt() != VERSION || input.readLong() != contentId) return null
                val path = input.readUTF()
                val type = input.readUTF()
                val startUtc = input.readLong()
                val hasStartFinish = input.readBoolean()
                val outline = FloatArray(input.count(8192)) { input.readFloat() }
                val drivers = input.readByDriver {
                    DriverInfo(readUTF(), readUTF(), readInt(), readUTF(), readUTF(), readUTF())
                }
                val tracks = input.readByDriver {
                    val n = count(MAX_SAMPLES)
                    val t = LongArray(n)
                    val x = FloatArray(n)
                    val y = FloatArray(n)
                    repeat(n) {
                        if (it % 4096 == 0) checkActive()
                        t[it] = readLong()
                        x[it] = readFloat()
                        y[it] = readFloat()
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

    fun write(core: ReplayCore, checkActive: () -> Unit): Boolean {
        if (core.outline.size < 8 || core.drivers.isEmpty() || core.tracks.isEmpty() || core.laps.isEmpty()) return false
        folder.mkdirs()
        val temporary = try {
            File.createTempFile("timing-", ".tmp", folder)
        } catch (_: IOException) {
            return false
        }
        try {
            DataOutputStream(temporary.outputStream().buffered(BUFFER_SIZE)).use { output ->
                output.writeInt(VERSION)
                output.writeLong(contentId)
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
                output.writeByDriver(core.tracks) { track ->
                    writeInt(track.n)
                    repeat(track.n) {
                        if (it % 4096 == 0) checkActive()
                        writeLong(track.t[it])
                        writeFloat(track.x[it])
                        writeFloat(track.y[it])
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
            folder.listFiles { entry -> entry != file && entry.extension == "bin" }
                ?.sortedByDescending { it.lastModified() }?.drop(1)?.forEach { it.delete() }
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
        const val VERSION = 1
        const val BUFFER_SIZE = 128 * 1024
        const val MAX_BYTES = 64L * 1024 * 1024
        const val MAX_SAMPLES = 200_000
        const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    }
}
