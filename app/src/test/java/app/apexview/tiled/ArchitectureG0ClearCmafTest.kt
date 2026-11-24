package app.apexview.tiled

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

/** Exports exact JVM clear writer bytes for independent Rust assembly comparison. */
class ArchitectureG0ClearCmafTest {
    @Test fun clearWriterFixtures() {
        val root = System.getenv("PITWALL_G0_CMAF_DIR")?.let(::File)
        val small = listOf(
            HevcClearCmafFragmentWriter.Sample(byteArrayOf(0,0,0,2,2,1),3600,-7,0x02000000),
            HevcClearCmafFragmentWriter.Sample(byteArrayOf(0,0,0,3,2,1,8),1800,19,0x01010000)
        )
        val base = HevcClearCmafFragmentWriter.write(1,1,0,small,false,false)
        assertEquals(listOf("moof","mdat"), CmafBoxInspector.inspect(base).topLevelBoxes)
        assertThrows(IllegalArgumentException::class.java) {
            HevcClearCmafFragmentWriter.write(1,1,0,small,true,false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HevcClearCmafFragmentWriter.write(1,1,0,small,false,true)
        }
        if (root == null) return
        val fixture = File(requireNotNull(System.getenv("APEXVIEW_HEVC_FIXTURE_DIR")))
        emit(root,"small",small,1,1,0)
        for ((index,name) in listOf("feed_a.hevc","feed_b.hevc").withIndex()) {
            val nals = HevcBitstream.splitAnnexB(File(fixture,name).readBytes())
            val pictures=mutableListOf<MutableList<ByteArray>>()
            for (nal in nals.filter { it.type in 0..31 }) {
                require(nal.bytes.size >= 3)
                if ((nal.bytes[2].toInt() and 0x80) != 0) pictures.add(mutableListOf())
                require(pictures.isNotEmpty())
                pictures.last().add(nal.bytes)
            }
            assertEquals(50,pictures.size)
            val samples=pictures.take(25).map { picture ->
                val out=ByteArrayOutputStream()
                picture.forEach { nal -> out.write(ByteBuffer.allocate(4).putInt(nal.size).array()); out.write(nal) }
                val type=HevcBitstream.readNalHeader(picture.first()).type
                HevcClearCmafFragmentWriter.Sample(out.toByteArray(),3600,0,if(type in 16..23) 0x02000000L else 0x01010000L)
            }
            emit(root,"encoded-$index",samples,index+1L,index+2L,0)
        }
    }
    private fun emit(root: File, name: String, samples: List<HevcClearCmafFragmentWriter.Sample>, track: Long, seq: Long, time: Long) {
        val dir=File(root,name); check(dir.mkdirs() || dir.isDirectory)
        val expected=HevcClearCmafFragmentWriter.write(track,seq,time,samples,false,false)
        File(dir,"expected.m4s").writeBytes(expected)
        File(dir,"manifest.txt").writeText(buildString {
            appendLine("$track $seq $time")
            samples.forEachIndexed { i,s ->
                val file="sample-$i.bin"
                File(dir,file).writeBytes(s.data)
                appendLine("$file ${s.durationTicks} ${s.compositionTimeOffsetTicks} ${s.sampleFlags}")
            }
        })
        repeat(16) { check(HevcClearCmafFragmentWriter.write(track,seq,time,samples,false,false).size==expected.size) }
        val times=LongArray(51) {
            val start=System.nanoTime()
            repeat(8) { check(HevcClearCmafFragmentWriter.write(track,seq,time,samples,false,false).size==expected.size) }
            (System.nanoTime()-start)/8
        }.sorted()
        println("G0 CMAF jvm case=$name samples=${samples.size} bytes=${expected.size} p50Ns=${times[25]} p95Ns=${times[48]} maxNs=${times[50]} outputReused=false")
    }
}
