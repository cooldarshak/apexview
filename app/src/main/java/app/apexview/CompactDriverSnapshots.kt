package app.apexview

import app.apexview.tiled.NativeHotPath
import java.util.RandomAccess

/**
 * Archive-only exact snapshot columns: nine unsigned dictionary/flag fields
 * per row in the existing bounded Rust-owned compact telemetry registry.
 * Android objects remain only once per dictionary value; the full original
 * snapshot list is not retained. A single immutable decoded row is cached.
 * Overflow, unsupported position or unavailable native storage falls back
 * to the original list; no field is silently truncated.
 */
internal class CompactDriverSnapshots private constructor(
    private val rows: NativeHotPath.CompactTelemetry?,
    private val deltas: NativeHotPath.CompactSnapshotDeltas?,
    override val size: Int,
    private val sectorLists: List<List<SectorCell?>?>,
    private val cells: List<SectorCell?>,
    private val strings: List<String?>
) : AbstractList<DriverLaps.Snapshot>(), RandomAccess {
    private data class Decoded(val index: Int, val snapshot: DriverLaps.Snapshot)
    @Volatile private var latest: Decoded? = null

    override fun get(index: Int): DriverLaps.Snapshot {
        if (index !in indices) throw IndexOutOfBoundsException("Snapshot $index")
        latest?.let { if (it.index == index) return it.snapshot }
        val start = index * FIELDS
        val decoded = deltas?.let { owner -> scratch.get()!!.also { owner.read(index, it) } }
        fun field(column: Int): Int = decoded?.get(column) ?: requireNotNull(rows).at(start + column)
        val flags = field(5)
        val value = DriverLaps.Snapshot(
            field(0).let { if (it == 0) null else it - 1 },
            requireNotNull(sectorLists[field(1)]),
            requireNotNull(sectorLists[field(2)]),
            cells[field(3)],
            strings[field(4)],
            flags and 1 != 0,
            strings[field(6)],
            strings[field(7)],
            flags and 2 != 0,
            cells[field(8)],
            flags and 4 != 0
        )
        latest = Decoded(index, value)
        return value
    }

    fun retiredAt(index: Int): Boolean {
        if (index !in indices) return false
        latest?.let { if (it.index == index) return it.snapshot.retired }
        val flags = deltas?.let { owner -> scratch.get()!!.also { owner.read(index, it) }[5] }
            ?: requireNotNull(rows).at(index * FIELDS + 5)
        return flags and 2 != 0
    }

    private class DictionaryLimit : RuntimeException()
    private class Dictionary<T : Any> {
        private val ids = HashMap<T, Int>()
        val values = arrayListOf<T?>(null)
        fun id(value: T?): Short {
            if (value == null) return 0
            val existing = ids[value]
            if (existing != null) return existing.toShort()
            if (values.size > Short.MAX_VALUE.toInt()) throw DictionaryLimit()
            val index = values.size
            values.add(value)
            ids[value] = index
            return index.toShort()
        }
    }

    companion object {
        private const val FIELDS = 9
        // One tiny reusable buffer per reading thread, never a full snapshot array.
        private val scratch = ThreadLocal.withInitial { IntArray(FIELDS) }
        private const val MAX_TEMP_FIELDS = 2_000_000
        fun freeze(source: List<DriverLaps.Snapshot>): CompactDriverSnapshots? {
            if (source is CompactDriverSnapshots) return source
            if (source.size < 64 || source.size > MAX_TEMP_FIELDS / FIELDS || !NativeHotPath.available()) return null
            val sectors = Dictionary<List<SectorCell?>>()
            val cells = Dictionary<SectorCell>()
            val strings = Dictionary<String>()
            val data = ShortArray(source.size * FIELDS)
            try {
                source.forEachIndexed { index, state ->
                    val pos = state.position
                    if (pos != null && pos !in 0..32766) return null
                    val start = index * FIELDS
                    data[start] = (pos?.plus(1) ?: 0).toShort()
                    data[start + 1] = sectors.id(state.sectors)
                    data[start + 2] = sectors.id(state.lapSectors)
                    data[start + 3] = cells.id(state.lastLap)
                    data[start + 4] = strings.id(state.gap)
                    data[start + 5] = ((if (state.inPit) 1 else 0) or
                        (if (state.retired) 2 else 0) or (if (state.stopped) 4 else 0)).toShort()
                    data[start + 6] = strings.id(state.toLeader)
                    data[start + 7] = strings.id(state.ahead)
                    data[start + 8] = cells.id(state.bestLap)
                }
                val delta = NativeHotPath.compactSnapshotDeltas(data, source.size)
                val packed = if (delta == null) NativeHotPath.compactTelemetry(data, data.size, 16) ?: return null else null
                return CompactDriverSnapshots(packed, delta, source.size, sectors.values, cells.values, strings.values)
            } catch (_: DictionaryLimit) {
                return null
            } finally {
                data.fill(0)
            }
        }
    }
}
