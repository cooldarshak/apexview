package app.apexview.tiled

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/** Samples actual OS/JVM headroom at admission. No device-model exceptions. */
internal object NativeStitchingDeviceBudget {
    private val admissionLock = Any()
    fun <T> admit(action: () -> T): T = synchronized(admissionLock) { action() }
    data class Headroom(val lowRam: Boolean, val lowMemory: Boolean, val available: Long,
                        val threshold: Long, val jvmHeadroom: Long)
    fun plan(headroom: Headroom, input: Long, output: Long): NativeStitchingEngine.Budget? {
        if (headroom.lowMemory || input !in 1..Int.MAX_VALUE.toLong() ||
            output !in 1..Int.MAX_VALUE.toLong()) return null
        return NativeStitchingEngine.plan(headroom.lowRam,headroom.available,headroom.threshold,
            headroom.jvmHeadroom,input.toInt(),output.toInt())
    }
    fun sample(context: Context): Headroom? {
        val manager=context.getSystemService(ActivityManager::class.java) ?: return null
        val info=ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val runtime=Runtime.getRuntime()
        val used=runtime.totalMemory()-runtime.freeMemory()
        return Headroom(manager.isLowRamDevice,info.lowMemory,info.availMem,info.threshold,
            (runtime.maxMemory()-used).coerceAtLeast(0))
    }
    /** Only called from existing media workers or an explicitly dispatched
     * lifecycle callback; never block the UI on allocator/native locks. */
    fun observePressure(h: Headroom?): Boolean {
        if (h == null) return false
        val decision = MediaPressurePolicy.process.observe(h, android.os.SystemClock.elapsedRealtime())
        if (decision.trimUnused) {
            app.apexview.Media3MemoryOwners.trimUnused()
            NativeFragmentLease.trim()
        }
        return decision.constrained
    }
    /** G-B calls this on its owner worker before creating the native pipeline.
     * Re-sample at each new admission; never allocate a second session to grow. */
    fun open(context: Context,input: Long,output: Long): NativeStitchingEngine = admit {
        val headroom=sample(context) ?: throw IllegalStateException("Device memory unavailable")
        val budget=plan(headroom,input,output) ?: throw IllegalStateException("Native memory budget unavailable")
        NativeStitchingEngine.open(budget)
    }
}

/** Per archive session high-water marks, not guessed frame sizes. Initial JVM
 * composition records maxima even before the UI owner attaches its context. */
internal class NativeStitchingBudgetObservation {
    data class Sizes(val input: Long,val output: Long)
    private var input=0L
    private var output=0L
    @Synchronized fun observe(inputBytes: Long,outputBytes: Long): Boolean {
        if(inputBytes<=0 || outputBytes<=0)return false
        val changed=inputBytes>input || outputBytes>output
        input=maxOf(input,inputBytes);output=maxOf(output,outputBytes)
        return changed
    }
    @Synchronized fun sizes()=Sizes(input,output)
    fun log(context: Context) {
        val size=sizes()
        // A diagnostic/admission projection must never interrupt JVM playback.
        try {
            val headroom=NativeStitchingDeviceBudget.sample(context)
            val b=headroom?.takeIf { !it.lowMemory && size.input in 1..Int.MAX_VALUE.toLong() &&
                size.output in 1..Int.MAX_VALUE.toLong() }?.let {
                NativeStitchingEngine.planClearAtlas(it.lowRam,it.available,it.threshold,it.jvmHeadroom,
                    size.input.toInt(),size.output.toInt())
            }
            Log.i("ApexviewSingleDecoder","GA_DEVICE_BUDGET planningOnly=true inputPeak=${size.input} " +
                "outputPeak=${size.output} available=${headroom?.available ?: -1} " +
                "threshold=${headroom?.threshold ?: -1} jvmHeadroom=${headroom?.jvmHeadroom ?: -1} " +
                "lowRam=${headroom?.lowRam} lowMemory=${headroom?.lowMemory} " +
                "slots=${b?.slots ?: 0} reservedBytes=${b?.reservedBytes ?: 0} " +
                "ceilingBytes=${b?.ceilingBytes ?: 0} admitted=${b != null}")
        } catch (_: RuntimeException) {
            Log.w("ApexviewSingleDecoder","GA_DEVICE_BUDGET unavailable; JVM unchanged")
        }
    }
}
