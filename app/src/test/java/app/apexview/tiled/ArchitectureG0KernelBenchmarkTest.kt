package app.apexview.tiled

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureG0KernelBenchmarkTest {
    @Test
    fun sizingFixtureEquivalenceAndHostTiming() {
        val path = System.getenv("PITWALL_G0_FIXTURE_DIR")
        if (path.isNullOrBlank()) {
            assertEquals(6, HevcClearCmafGridComposer.annexBSizeForSample(
                byteArrayOf(0,0,2,9,10),0,5,3))
            return
        }
        val dir = File(path)
        val rows = File(dir,"manifest.txt").readLines()
        assertEquals(3,rows.size)
        for (row in rows) {
            val (name,prefixText,expectedText) = row.split(" ")
            val bytes = File(dir,name).readBytes()
            val prefix = prefixText.toInt()
            val expected = expectedText.toInt()
            assertEquals(expected,HevcClearCmafGridComposer.annexBSizeForSample(bytes,0,bytes.size,prefix))
            var sink = 0L
            fun work() {
                repeat(256) {
                    sink += HevcClearCmafGridComposer.annexBSizeForSample(bytes,0,bytes.size,prefix)
                }
            }
            repeat(30) { work() }
            val times = LongArray(101) {
                val start = System.nanoTime()
                work()
                (System.nanoTime()-start)/256
            }.sorted()
            assertEquals(expected.toLong()*256*131,sink)
            assertTrue(times[100]>=times[50])
            println("G0 jvm prefix=$prefix bytes=${bytes.size} result=$expected p50_ns=${times[50]} p95_ns=${times[95]} p99_ns=${times[99]} max_ns=${times[100]}")
        }
    }
}
