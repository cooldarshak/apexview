package app.apexview.cast

import app.apexview.OverlayDrawCar
import app.apexview.tiled.NativeHotPath

/** Receiver packet owner. Native interpolation uses the same measured endpoints
 * as the Kotlin reference and a fixed bounded batch. No new Car/list per frame.
 */
internal class ReceiverMotionSampler(private val frame: CastTimingProtocol.Frame,
    private val requested: Boolean, private val diagnostics: ReceiverMotionDiagnostics) {
    private val cars = frame.cars.map { OverlayDrawCar(it.number) }
    private val counts = if (requested) IntArray(cars.size) { frame.cars[it].later.size + 2 } else IntArray(0)
    private val points = if (requested) FloatArray(cars.size * 14).also { values ->
        frame.cars.forEachIndexed { i, car ->
            val start = i * 14
            values[start] = car.x; values[start+1] = car.y
            values[start+2] = car.nextX; values[start+3] = car.nextY
            car.later.forEachIndexed { j, pair ->
                values[start+4+2*j] = pair.first; values[start+5+2*j] = pair.second
            }
        }
    } else FloatArray(0)
    private val xy = if (requested) FloatArray(cars.size * 2) else FloatArray(0)
    private var failed = false
    fun sample(position: Long): List<OverlayDrawCar> {
        val steps = (position-frame.positionMs).toFloat()/CastTimingProtocol.POSITION_STEP_MS
        val native = requested && !failed && NativeHotPath.interpolateMotion(points,counts,steps,xy)
        diagnostics.report(requested, native)
        if (requested && !native) failed = true
        for (i in cars.indices) {
            val target = cars[i]
            val source = frame.cars[i]
            if (native) {
                target.x=xy[2*i]; target.y=xy[2*i+1]; target.state=source.state
            } else source.writeAt(steps,target)
        }
        return cars
    }
}


/** One diagnostic per engine transition, rather than one per timing packet. */
internal class ReceiverMotionDiagnostics {
    private var last: Pair<Boolean, Boolean>? = null
    fun report(requested: Boolean, actual: Boolean) {
        val old = last
        if (old != null && old.first == requested && old.second == actual) return
        last = requested to actual
        android.util.Log.i("PitwallNativeHotPath",
            "MAP_RECEIVER_ENGINE requested=" + (if (requested) "RUST" else "JVM") +
            " actual=" + (if (actual) "RUST" else "JVM") +
            " reason=" + (if (actual) "ENDPOINTS_INTERPOLATED" else if (requested) "NATIVE_BATCH_UNAVAILABLE" else "USER_DISABLED"))
    }
}
