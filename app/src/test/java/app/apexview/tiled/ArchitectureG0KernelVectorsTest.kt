package app.apexview.tiled

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Differential against the actual current JVM primitives using shared Rust vectors. */
class ArchitectureG0KernelVectorsTest {
    @Test fun vectorsMatchCurrentJvm() {
        val path = System.getenv("PITWALL_G0_KERNEL_VECTORS")
        if (path.isNullOrBlank()) {
            assertEquals(5L, HevcBitReader(byteArrayOf(0x30)).readUnsignedExpGolomb())
            return
        }
        val rows = File(path).readLines()
        assertEquals(10, rows.size)
        for (row in rows) {
            val f = row.split(" ")
            fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val work: () -> Unit = when (f[0]) {
                "bits" -> {
                    val data = hex(f[2]); val count = f[3].toInt()
                    val expected = f[4].toLong(); val pos = f[5].toInt()
                    val action: () -> Unit = {
                        val b = HevcBitReader(data)
                        val value = if (count == 0) b.readUnsignedExpGolomb() else b.readBits(count).toLong() and 0xffff_ffffL
                        check(value == expected && b.positionBits == pos)
                    }
                    action
                }
                "boxes" -> {
                    val data = hex(f[2]); val expected = f[3].toInt()
                    val action: () -> Unit = {
                        val report = CmafBoxInspector.inspect(data)
                        check(report.errors.isEmpty() && report.topLevelBoxes.size == expected)
                    }
                    action
                }
                "meta" -> {
                    val prefix = f[2].toInt(); val clear = f[3].toInt()
                    val encrypted = f[4].split(",").map(String::toInt)
                    val expected = f[5].split(",").map {
                        val (c,e) = it.split(":")
                        CmafBoxInspector.SubsampleEncryptionRange(c.toInt(), e.toLong())
                    }
                    val bytes = ByteArray(clear + encrypted.sum()) { 0x55 }
                    bytes[0] = 2; bytes[1] = 1
                    val ranges = mutableListOf(ProtectedHevcSampleLayout.ProtectionRange(0,clear,true))
                    var cursor = clear
                    for (n in encrypted) {
                        ranges += ProtectedHevcSampleLayout.ProtectionRange(cursor,n,false)
                        cursor += n
                    }
                    val slice = ProtectedHevcSliceHeaderRewriter.Result(
                        nal = bytes, protectionRanges = ranges, destinationAddress = 0,
                        sourceTailOffset = 2, outputTailOffset = 2, copiedTailBytes = bytes.size - 2,
                        encryptedByteCount = encrypted.sum().toLong())
                    val action: () -> Unit = {
                        val result = ProtectedHevcSampleComposer.compose(listOf(slice),prefix)
                        check(result.subsampleRanges == expected)
                        check(result.encryptedByteCount == encrypted.sum().toLong())
                        check(result.sampleBytes.size == bytes.size + prefix)
                    }
                    action
                }
                else -> error("Unknown fixture ${f[0]}")
            }
            repeat(32) { work() }
            val times = LongArray(101) {
                val start = System.nanoTime()
                repeat(128) { work() }
                (System.nanoTime()-start)/128
            }.sorted()
            assertTrue(times[100] >= times[50])
            println("G0 kernel jvm case=${f[1]} p50Ns=${times[50]} p95Ns=${times[95]} maxNs=${times[100]}")
        }
    }
}
