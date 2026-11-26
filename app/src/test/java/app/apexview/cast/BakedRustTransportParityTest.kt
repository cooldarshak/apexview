package app.apexview.cast

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class BakedRustTransportParityTest {
    @Test fun nativeAndReferenceMuxMatchRealEncodedSamplesAndContinuity() {
        val path = System.getenv("PITWALL_BAKED_TS_FIXTURE_DIR")
        // Other branches do not package the Architecture G host binary.
        org.junit.Assume.assumeTrue("Architecture G host fixture not supplied", !path.isNullOrBlank())
        val dir = File(requireNotNull(path))
        val reference = BakedCastTransportStream()
        repeat(2) { segment ->
            val raw = ByteBuffer.wrap(dir.resolve("segment-$segment.samples").readBytes())
            val samples = mutableListOf<BakedCastTransportStream.Sample>()
            while (raw.hasRemaining()) {
                val pts = raw.long
                val size = raw.int
                val flags = raw.get().toInt()
                require(size > 0 && size <= raw.remaining())
                val data = ByteArray(size)
                raw.get(data)
                samples += BakedCastTransportStream.Sample(pts, data, flags and 1 != 0, flags and 2 != 0)
            }
            assertArrayEquals("segment $segment", dir.resolve("segment-$segment.ts").readBytes(),
                reference.segment(samples))
        }
    }
}
