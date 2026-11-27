package app.apexview

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReplayPositionSpoolTest {
    private fun line(ms: Int, payload: String = "test"): String =
        String.format(java.util.Locale.US, "00:%02d:%02d.%03d%s", ms/60000, ms/1000%60, ms%1000, payload)

    @Test fun seeksBackwardsWithinDownloadedPrefixWithoutRetainingPayloads() {
        val file = File.createTempFile("pitwall-position-test", ".stream")
        val expected = (0..1000).map { line(it * 200, "é$it") }
        try {
            ReplayPositionSpool(file).use { spool ->
                expected.take(800).forEach(spool::append)
                assertEquals(expected.subList(510, 521), spool.readWindow(102000,104000) { it.toList() })
                assertEquals(expected.subList(80, 91), spool.readWindow(16000,18000) { it.toList() })
                assertEquals(expected.subList(127,130), spool.readWindow(25400,25800) { it.toList() })
                expected.drop(800).forEach(spool::append)
                assertEquals(expected.subList(990,1001),spool.readWindow(198000,200000) { it.toList() })
                assertEquals(200000L,spool.throughMs)
            }
            assertEquals(expected,file.readLines(Charsets.UTF_8))
        } finally { file.delete() }
    }

    @Test fun equalTimestampsAndUnorderedInputAreNotSilentlySkipped() {
        val file = File.createTempFile("pitwall-position-test", ".stream")
        try {
            ReplayPositionSpool(file).use { spool ->
                repeat(257) { spool.append(line(1000,"$it")) }
                assertEquals(257,spool.readWindow(1000,1000) { it.count() })
                spool.append(line(999))
                assertFalse(spool.chronological)
                assertThrows(IllegalStateException::class.java) {
                    spool.readWindow(0,2000) { it.toList() }
                }
            }
            assertEquals(258,file.readLines().size)
        } finally { file.delete() }
    }
}
