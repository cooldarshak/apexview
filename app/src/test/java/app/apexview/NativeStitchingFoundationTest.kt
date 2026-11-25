package app.apexview

import app.apexview.tiled.NativeStitchingEngine
import app.apexview.tiled.NativeStitchingSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NativeStitchingFoundationTest {
    @Test fun preferencePersistsWithoutChangingDecoderOrDrm() {
        val ctx=RuntimeEnvironment.getApplication()
        val prefs=ctx.getSharedPreferences("f1mv",android.content.Context.MODE_PRIVATE)
        val had=prefs.contains("nativeRustStitching")
        val old=prefs.getBoolean("nativeRustStitching",false)
        try {
            prefs.edit().remove("nativeRustStitching").commit()
            val s=Settings(ctx)
            val decoder=s.decoderMode
            val drm=s.softwareDrm
            assertFalse(s.nativeRustStitching)
            s.updateNativeRustStitching(true)
            val fresh=Settings(ctx)
            assertTrue(fresh.nativeRustStitching)
            assertEquals(decoder,fresh.decoderMode)
            assertEquals(drm,fresh.softwareDrm)
            fresh.updateNativeRustStitching(false)
            assertFalse(Settings(ctx).nativeRustStitching)
        }finally{
            if(had)prefs.edit().putBoolean("nativeRustStitching",old).commit()
            else prefs.edit().remove("nativeRustStitching").commit()
        }
    }
    @Test fun allUnsupportedRoutesExplicitlyRemainJvm() {
        NativeStitchingSelection.Path.entries.forEach { path ->
            val off=NativeStitchingSelection.resolve(false,path)
            val on=NativeStitchingSelection.resolve(true,path)
            assertEquals(NativeStitchingSelection.Route.JVM,off.actual)
            assertEquals(NativeStitchingSelection.Route.JVM,on.actual)
            assertTrue(on.requested)
            assertNotEquals(off.reason,on.reason)
            assertFalse(NativeStitchingSelection.requiresRestart(off,on))
            assertTrue(on.diagnostic(path).contains("actual=JVM"))
        }
    }
    @Test fun onlyActuallyPublishedClearCanReportRust() {
        val s = NativeStitchingSelection
        NativeStitchingSelection.Path.entries.forEach { path ->
            val selected = s.resolve(true, path, clearPublished = true)
            assertEquals(if (path == NativeStitchingSelection.Path.VERIFIED_CLEAR) NativeStitchingSelection.Route.RUST else NativeStitchingSelection.Route.JVM, selected.actual)
            assertEquals(NativeStitchingSelection.Route.JVM, s.resolve(false, path, clearPublished = true).actual)
        }
        val rejected = s.resolve(true, NativeStitchingSelection.Path.VERIFIED_CLEAR, true, "TEST_FALLBACK")
        assertEquals(NativeStitchingSelection.Route.JVM, rejected.actual)
        assertTrue(rejected.requested)
        assertEquals("TEST_FALLBACK", rejected.reason)
    }
    @Test fun clearAtlasAdmissionIncludesNativeWorkspaceReserve() {
        val mib = 1024L * 1024L
        val low = NativeStitchingEngine.planClearAtlas(true,512*mib,128*mib,128*mib,256*1024,256*1024)
        assertNotNull(low)
        assertTrue(low!!.reservedBytes + 4L*low.inputBytes + low.outputBytes + 8*mib <= low.ceilingBytes)
        // The old arena-only plan accepted this combination. The actual
        // Rust worker needs additional parser workspace and must reject it.
        assertNotNull(NativeStitchingEngine.plan(true,512*mib,128*mib,128*mib,1024*1024,1024*1024))
        assertNull(NativeStitchingEngine.planClearAtlas(true,512*mib,128*mib,128*mib,1024*1024,1024*1024))
    }
    @Test fun observedDeviceHeadroomAndHighWaterControlAdmission() {
        val mib=1024L*1024L
        val sizes=app.apexview.tiled.NativeStitchingBudgetObservation()
        assertFalse(sizes.observe(-1,1024))
        assertTrue(sizes.observe(mib,mib))
        assertFalse(sizes.observe(mib/2,mib))
        assertTrue(sizes.observe(mib/2,2*mib))
        assertEquals(mib,sizes.sizes().input)
        assertEquals(2*mib,sizes.sizes().output)
        val head=app.apexview.tiled.NativeStitchingDeviceBudget.Headroom(true,false,512*mib,128*mib,128*mib)
        val plan=app.apexview.tiled.NativeStitchingDeviceBudget.plan(head,mib,2*mib)
        assertNotNull(plan)
        assertEquals(2,plan!!.slots)
        assertNull(app.apexview.tiled.NativeStitchingDeviceBudget.plan(head.copy(lowMemory=true),mib,mib))
        assertNull(app.apexview.tiled.NativeStitchingDeviceBudget.plan(head.copy(available=128*mib),mib,mib))
        assertNull(app.apexview.tiled.NativeStitchingDeviceBudget.plan(head,Long.MAX_VALUE,mib))
    }
    @Test fun budgetsAndDeadlineSlackAreConservative() {
        val mib=1024L*1024L
        val low=NativeStitchingEngine.plan(true,512*mib,128*mib,128*mib,1024*1024,1024*1024)!!
        assertEquals(2,low.slots)
        assertTrue(low.reservedBytes<=12*mib)
        val high=NativeStitchingEngine.plan(false,1024*mib,128*mib,256*mib,1024*1024,1024*1024)!!
        assertEquals(4,high.slots)
        assertTrue(high.reservedBytes<=high.ceilingBytes)
        assertNull(NativeStitchingEngine.plan(true,128*mib,128*mib,128*mib,1,1))
        assertNull(NativeStitchingEngine.plan(false,512*mib,128*mib,128*mib,Int.MAX_VALUE,1))
        assertNull(NativeStitchingEngine.plan(true,512*mib,128*mib,128*mib,8*1024*1024,8*1024*1024))
        assertEquals(1000L,NativeStitchingEngine.slackMs(3000,1000,2.0))
        assertEquals(1L,NativeStitchingEngine.slackMs(1000,2000,1.0))
        assertEquals(60000L,NativeStitchingEngine.slackMs(Long.MAX_VALUE,0,1.0))
        assertEquals(0L,NativeStitchingEngine.slackMs(1000,0,Double.NaN))
    }
}
