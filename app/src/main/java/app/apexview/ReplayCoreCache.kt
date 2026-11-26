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
    val previewTowerFromUtc: Long? = null,
    val previewTowerThroughUtc: Long? = null,
)

/** Map and tower readiness are independent, including when one topic is absent. */
internal object ReplayPreviewCoverage {
    const val MAP = 1
    const val TOWER = 2

    fun at(session: SessionTiming, utc: Long): Int {
        val map = session.outline.size >= 8 && session.outline.size % 2 == 0 &&
            session.tracks.values.any { it.hasPositionAt(utc) }
        val tower = utc >= (session.previewTowerFromUtc ?: Long.MIN_VALUE) &&
            (!session.archivePartial || session.towerIndexed || utc <= session.towerThroughUtc) &&
            session.laps.values.any { it.hasRankAt(utc) }
        return (if (map) MAP else 0) or (if (tower) TOWER else 0)
    }
}

/** Small, disposable cache of completed archives. The original topics remain the source of truth. */
internal class ReplayCoreCache(directory: File, private val contentId: Long,
                              private val diagnostic: (String) -> Unit = {}) {
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
        val kind = if (previewPositionMs == null) "core" else "preview"
        fun miss(reason: String): ReplayCore? {
            diagnostic("Replay $kind cache content=$contentId result=$reason")
            return null
        }
        if (!file.isFile) return miss("absent")
        val limit = if (previewPositionMs == null) MAX_BYTES else MAX_PREVIEW_BYTES
        if (file.length() !in 1..limit) return miss("invalid-length")
        if (System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) return miss("expired")
        return try {
            DataInputStream(file.inputStream().buffered(BUFFER_SIZE)).use { input ->
                val version = input.readInt()
                // Version 2 omitted the stopped bit and cannot represent the original history.
                // V4 adds independent tower coverage; V5 preserves the
                // original rank baseline in compact preview histories.
                // Full V3 indices (including Stopped) are unchanged.
                if (version != VERSION && !(previewPositionMs != null && version in COVERAGE_PREVIEW_VERSION..PREVIEW_VERSION))
                    return miss("rebuild-version-$version")
                if (input.readLong() != contentId) return miss("wrong-content")
                if (previewPositionMs != null &&
                    kotlin.math.abs(input.readLong() - previewPositionMs) > PREVIEW_REUSE_MS
                ) return miss("outside-window")
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
                    val n = count(MAX_SAMPLES, TRACK_SAMPLE_BYTES)
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
                    val sectors = List(count(MAX_SAMPLES, 9)) { readSector() }
                    fun cell(): SectorCell? {
                        val index = readInt()
                        return if (index == -1) null else sectors[index]
                    }
                    fun strip(previous: List<SectorCell?>?): List<SectorCell?> {
                        val a = cell()
                        val b = cell()
                        val c = cell()
                        return if (previous != null && previous[0] === a && previous[1] === b && previous[2] === c)
                            previous else listOf(a, b, c)
                    }
                    var previous: DriverLaps.Snapshot? = null
                    val snapshots = List(times.size) { index ->
                        if (index % 1024 == 0) checkActive()
                        DriverLaps.Snapshot(
                            position = readInt().takeIf { it >= 0 },
                            sectors = strip(previous?.sectors),
                            lapSectors = strip(previous?.lapSectors),
                            lastLap = cell(),
                            gap = readNullableString(),
                            inPit = readBoolean(),
                            toLeader = readNullableString(),
                            ahead = readNullableString(),
                            retired = readBoolean(),
                            bestLap = cell(),
                            stopped = readBoolean(),
                        ).also { previous = it }
                    }
                    val crossings = readTimes()
                    val lapTimes = readTimes()
                    val lapCounts = IntArray(lapTimes.size) { readInt() }
                    DriverLaps(times, snapshots, crossings, lapTimes, lapCounts)
                }
                val parts = List(input.count(MAX_SAMPLES, 12)) { input.readLong() to input.readInt() }
                val towerFrom = if (version >= COVERAGE_PREVIEW_VERSION) input.readLong() else null
                val towerThrough = if (version >= COVERAGE_PREVIEW_VERSION) input.readLong() else null
                val withRankBaseline = if (version == PREVIEW_VERSION) {
                    val originalRanks = input.readByDriver {
                        val rank = readInt()
                        if (rank < -1) throw IOException("Invalid preview rank baseline")
                        rank.takeIf { it >= 0 }
                    }
                    if (originalRanks.keys != laps.keys) return miss("invalid-rank-baseline")
                    laps.mapValues { (number, history) -> history.withInitialPosition(originalRanks.getValue(number)) }
                } else laps
                if (towerFrom != null && (towerThrough == null || towerFrom > towerThrough)) return miss("invalid-coverage")
                val validMap = outline.size >= 8 && outline.size % 2 == 0 && tracks.isNotEmpty()
                val independent = previewPositionMs != null && version == PREVIEW_VERSION
                val validTopics = if (independent) (validMap || laps.isNotEmpty()) &&
                    (tracks.isEmpty() || validMap) else validMap && laps.isNotEmpty()
                if (outline.size % 2 != 0 || drivers.isEmpty() || !validTopics ||
                    input.read() != -1) return miss("invalid-payload")
                diagnostic("Replay $kind cache content=$contentId result=hit bytes=${file.length()}")
                ReplayCore(path, type, drivers, tracks, states, outline, hasStartFinish, startUtc, withRankBaseline, parts, towerFrom, towerThrough)
            }
        } catch (_: IOException) {
            miss("truncated-or-invalid")
        } catch (_: IndexOutOfBoundsException) {
            miss("invalid-reference")
        }
    }

    fun write(core: ReplayCore, checkActive: () -> Unit): Boolean = writeFile(file, core, null, checkActive)

    fun writePreview(session: SessionTiming, positionMs: Long, utcMs: Long, checkActive: () -> Unit): Boolean {
        val path = session.radioBase.removePrefix(LiveTimingArchive.BASE)
        if (path == session.radioBase || path.isBlank() || session.live) return false
        val coverage = ReplayPreviewCoverage.at(session, utcMs)
        if (coverage == 0) return false
        val hasMap = coverage and ReplayPreviewCoverage.MAP != 0
        val hasTower = coverage and ReplayPreviewCoverage.TOWER != 0
        val from = utcMs - 90_000L
        val until = utcMs + 120_000L
        // V5 already carries explicit tower coverage and rank baseline.
        // No tower is represented by an empty lap map and an empty interval.
        val towerFrom = if (hasTower) maxOf(from, session.previewTowerFromUtc ?: Long.MIN_VALUE) else Long.MIN_VALUE
        val towerThrough = if (hasTower) minOf(until,
            if (!session.archivePartial || session.towerIndexed) Long.MAX_VALUE else session.towerThroughUtc)
            else Long.MIN_VALUE
        val tracks = if (!hasMap) emptyMap() else session.tracks.mapNotNull { (number, track) ->
            checkActive()
            if (track.n == 0 || track.endMs < from) return@mapNotNull null
            val range = historyWindowRange(track.n, from, until) { track.t[it] } ?: return@mapNotNull null
            // Interpolation at the upper boundary requires the following sample.
            val end = (range.last + 2).coerceAtMost(track.n)
            number to DriverTrack(track.t.copyOfRange(range.first, end), track.x.copyOfRange(range.first, end),
                track.y.copyOfRange(range.first, end), end - range.first)
        }.toMap()
        val states = if (!hasTower) emptyMap() else session.states.mapNotNull { (number, state) ->
            val range = historyWindowRange(state.t.size, towerFrom, towerThrough) { state.t[it] } ?: return@mapNotNull null
            number to StateLog(state.t.copyOfRange(range.first, range.last + 1), state.s.copyOfRange(range.first, range.last + 1))
        }.toMap()
        val laps = if (!hasTower) emptyMap() else session.laps.mapNotNull { (number, history) ->
            checkActive()
            history.previewWindow(towerFrom, towerThrough)?.let { number to it }
        }.toMap()
        val core = ReplayCore(path, session.type, session.drivers, tracks, states,
            if (hasMap) session.outline else FloatArray(0),
            hasMap && session.hasStartFinish, session.startUtcMs, laps, emptyList(), towerFrom, towerThrough)
        return writeFile(preview, core, positionMs, checkActive)
    }

    private fun writeFile(file: File, core: ReplayCore, previewPositionMs: Long?, checkActive: () -> Unit): Boolean {
        val validMap = core.outline.size >= 8 && core.outline.size % 2 == 0 && core.tracks.isNotEmpty()
        val validTopics = if (previewPositionMs == null) validMap && core.laps.isNotEmpty()
            else (validMap || core.laps.isNotEmpty()) && (core.tracks.isEmpty() || validMap)
        if (core.drivers.isEmpty() || !validTopics) return false
        val destination = file.parentFile ?: return false
        destination.mkdirs()
        val temporary = try {
            File.createTempFile("timing-", ".tmp", destination)
        } catch (_: IOException) {
            return false
        }
        try {
            val limit = if (previewPositionMs == null) MAX_BYTES else MAX_PREVIEW_BYTES
            val bounded = object : java.io.FilterOutputStream(temporary.outputStream()) {
                var remaining = limit
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    if (count > remaining) throw IOException("Replay index exceeds disk budget")
                    out.write(bytes, offset, count)
                    remaining -= count
                }
                override fun write(value: Int) {
                    if (remaining == 0L) throw IOException("Replay index exceeds disk budget")
                    out.write(value)
                    remaining--
                }
            }
            DataOutputStream(bounded.buffered(BUFFER_SIZE)).use { output ->
                output.writeInt(if (previewPositionMs == null) VERSION else PREVIEW_VERSION)
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
                        writeBoolean(snapshot.stopped)
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
                if (previewPositionMs != null) {
                    output.writeLong(requireNotNull(core.previewTowerFromUtc))
                    output.writeLong(requireNotNull(core.previewTowerThroughUtc))
                    output.writeByDriver(core.laps) { writeInt(it.initialPosition ?: -1) }
                }
            }
            synchronized(commitLock) {
                checkActive()
                if (temporary.length() > limit) {
                    diagnostic("Replay cache content=$contentId write=oversize")
                    return false
                }
                if (!temporary.renameTo(file)) {
                    diagnostic("Replay cache content=$contentId write=rename-failed")
                    return false
                }
                val maxFiles = if (previewPositionMs == null) 6 else 12
                val budget = if (previewPositionMs == null) 192L * 1024 * 1024 else 16L * 1024 * 1024
                var bytes = file.length()
                destination.listFiles { entry -> entry != file && entry.extension == "bin" }
                    ?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, entry ->
                        bytes += entry.length()
                        if (index >= maxFiles - 1 || bytes > budget) entry.delete()
                    }
            }
            return true
        } catch (failure: IOException) {
            diagnostic("Replay cache content=$contentId write=io-failed type=${failure.javaClass.simpleName}")
            return false
        } finally {
            temporary.delete()
        }
    }

    // This reader is always backed by a finite regular file. Bound allocations
    // by remaining serialized bytes before trusting a length in a damaged index.
    private fun DataInputStream.count(max: Int, minBytes: Int = 1): Int = readInt().also {
        if (it !in 0..max || it > available() / minBytes) throw IOException("Invalid replay cache count")
    }

    private fun DataInputStream.readTimes() = LongArray(count(MAX_SAMPLES, Long.SIZE_BYTES)) { readLong() }

    private fun DataOutputStream.writeTimes(times: LongArray) {
        writeInt(times.size)
        times.forEach { writeLong(it) }
    }

    private fun DataInputStream.readSector() = SectorCell(
        time = readUTF(),
        pace = Pace.entries[readUnsignedByte()],
        previousTime = readUTF(),
        segments = List(count(128, Int.SIZE_BYTES)) { readInt() },
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
        repeat(size) {
            val number = readUTF()
            if (result.containsKey(number)) throw IOException("Duplicate driver in replay index")
            result[number] = readValue()
        }
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
        val commitLock = Any()
        const val MAX_PREVIEW_BYTES = 16L * 1024 * 1024
        const val VERSION = 3
        const val COVERAGE_PREVIEW_VERSION = 4
        const val PREVIEW_VERSION = 5
        const val PREVIEW_REUSE_MS = 45_000L
        const val TRACK_BLOCK_SAMPLES = 4096
        const val TRACK_SAMPLE_BYTES = Long.SIZE_BYTES + 2 * Int.SIZE_BYTES
        const val BUFFER_SIZE = 128 * 1024
        const val MAX_BYTES = 64L * 1024 * 1024
        const val MAX_SAMPLES = 200_000
        const val MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    }
}
