package app.apexview.tiled

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchitectureGAAndroidTest {
    private fun budget() = requireNotNull(NativeStitchingEngine.plan(
        true,256L*1024*1024,32L*1024*1024,32L*1024*1024,4096,8192))
    private fun wait(engine:NativeStitchingEngine, token:Long):Long {
        val end=SystemClock.elapsedRealtime()+5000
        while(SystemClock.elapsedRealtime()<end) {
            val result=engine.acquire(token)
            if(result!=0L)return result
            Thread.sleep(2)
        }
        throw AssertionError("Native worker timeout")
    }
    @Test fun boundedNativeWorkerLeasesAndGeneration() {
        val input=byteArrayOf(2,2,1,2,2,1)
        NativeStitchingEngine.open(budget()).use { engine ->
            val g=engine.generation()
            assertEquals(1L,g)
            val a=engine.submit(g,input,1,2,false,5000)
            assertTrue(a>0)
            assertEquals(NativeStitchingEngine.BACKPRESSURE,engine.submit(g,input,1,2,false,5000))
            val b=engine.submit(g,input,1,2,true,5000)
            assertTrue(b>a)
            assertEquals(NativeStitchingEngine.BACKPRESSURE,engine.submit(g,input,1,2,true,5000))
            assertEquals(12L,wait(engine,a))
            val target=ByteArray(14){0x55.toByte()}
            assertEquals(6L,engine.read(a,6,target,2,6))
            assertArrayEquals(byteArrayOf(0x55,0x55,0,0,0,1,2,1,0x55,0x55,0x55,0x55,0x55,0x55),target)
            assertEquals(NativeStitchingEngine.INVALID,engine.read(a,11,target,0,6))
            assertEquals(g+1,engine.seek())
            assertEquals(NativeStitchingEngine.STALE,engine.read(a,0,target,0,6))
            assertEquals(NativeStitchingEngine.STALE,engine.acquire(b))
            assertEquals(1L,engine.release(a))
            assertEquals(NativeStitchingEngine.STALE,engine.release(a))
        }
        Log.i("PitwallGA","GA bounded native worker/lease/generation PASS")
    }
    @Test fun malformedBatchAndExclusiveAdmission() {
        NativeStitchingEngine.open(budget()).use { engine ->
            try {
                NativeStitchingEngine.open(budget()).close()
                fail("Overlapping native arena admitted")
            } catch(expected:IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("-4"))
            }
            val token=engine.submit(engine.generation(),byteArrayOf(2,2,1,3,2,1),1,2,true,5000)
            assertTrue(token>0)
            assertEquals(NativeStitchingEngine.INVALID,wait(engine,token))
            val out=ByteArray(12){0x77.toByte()}
            assertEquals(NativeStitchingEngine.INVALID,engine.read(token,0,out,0,12))
            assertArrayEquals(ByteArray(12){0x77.toByte()},out)
            assertEquals(1L,engine.release(token))
            engine.close()
            assertEquals(NativeStitchingEngine.CLOSED,engine.generation())
        }
        repeat(8){NativeStitchingEngine.open(budget()).use { assertEquals(1L,it.generation()) }}
        Log.i("PitwallGA","GA malformed batch/exclusive admission/repeated shutdown PASS")
    }
}
