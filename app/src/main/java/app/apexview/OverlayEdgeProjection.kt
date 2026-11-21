package app.apexview

/**
 * Retained, allocation-bounded map-edge projection/sort. Reuses primitive
 * arrays rather than building Pair/Offset records and sorted lists at 60 Hz.
 * Stable insertion sort preserves the original sortedBy(progress) tie order,
 * which matters for overlapping driver labels and 44dp focus targets.
 */
internal class OverlayEdgeProjection {
    private var drivers = arrayOfNulls<Car>(24)
    private var positions = FloatArray(24)
    var size: Int = 0
        private set

    fun update(cars: List<Car>, geometry: TrackGeometry): Int {
        if (drivers.size < cars.size) {
            val capacity = maxOf(drivers.size * 2, cars.size)
            drivers = drivers.copyOf(capacity)
            positions = positions.copyOf(capacity)
        }
        size = 0
        for (car in cars) {
            val fraction = geometry.progress(car.x, car.y)
            var insertion = size
            while (insertion > 0 && fraction < positions[insertion - 1]) {
                drivers[insertion] = drivers[insertion - 1]
                positions[insertion] = positions[insertion - 1]
                insertion--
            }
            drivers[insertion] = car
            positions[insertion] = fraction
            size++
        }
        // Release references when a source shrinks/driver retires.
        for (i in size until drivers.size) drivers[i] = null
        return size
    }

    fun car(i: Int): Car = requireNotNull(drivers[i])
    fun fraction(i: Int): Float = positions[i]
}
