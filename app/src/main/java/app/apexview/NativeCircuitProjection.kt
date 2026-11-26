package app.apexview

import app.apexview.tiled.NativeHotPath
import java.nio.ByteBuffer

/** Private, bounded, immutable native circuit index. Owns only compact
 * circuit vertices (not race histories); reused across every draw query.
 */
internal class NativeCircuitProjection(geometry: TrackGeometry) {
    private val packet: ByteBuffer? = if (geometry.outline.size % 2 == 0 &&
        geometry.outline.size / 2 <= 16_384 && NativeHotPath.available()) {
        try {
            val storage = ByteBuffer.allocateDirect(8 + 8 * geometry.outline.size)
            if (NativeHotPath.prepareGeometry(geometry.outline, storage)) storage.asReadOnlyBuffer() else null
        } catch (_: OutOfMemoryError) { null }
    } else null
    private val xy = FloatArray(128)
    val fractions = FloatArray(64)
    val order = IntArray(64)
    private val point = FloatArray(4)
    private var failed = packet == null
    private var reported: Boolean? = null

    private fun result(success: Boolean): Boolean {
        if (!success) failed = true
        if (reported != success) {
            reported = success
            android.util.Log.i("PitwallNativeHotPath", "MAP_GEOMETRY_ENGINE requested=RUST actual=" +
                if (success) "RUST reason=GEOMETRY_PROJECTED" else "JVM reason=NATIVE_GEOMETRY_UNAVAILABLE")
        }
        return success
    }
    fun project(cars: List<OverlayMotionCar>): Boolean {
        if (failed || cars.size > 64) return result(false)
        for (i in cars.indices) {
            xy[2*i] = cars[i].x
            xy[2*i+1] = cars[i].y
        }
        return result(NativeHotPath.projectGeometry(requireNotNull(packet), xy, cars.size, fractions, order))
    }
    fun progress(x: Float, y: Float): Float? {
        if (failed) { result(false); return null }
        xy[0] = x; xy[1] = y
        return if (result(NativeHotPath.projectGeometry(requireNotNull(packet), xy, 1, fractions, order)))
            fractions[0] else null
    }
    /** Scratch valid only until the next call, consumed synchronously by draw. */
    fun pointAt(fraction: Float): FloatArray? {
        if (failed) { result(false); return null }
        return if (result(NativeHotPath.pointAt(requireNotNull(packet), fraction, point))) point else null
    }
}
