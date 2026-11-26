package app.apexview

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import app.apexview.tiled.NativeStitchingDeviceBudget
import app.apexview.tiled.NativeStitchingEngine
import app.apexview.tiled.SpeculativeMemoryAdmission

/** Opt-in diagnostic only. Platform PSS categories can overlap other metrics. */
internal object ProcessMemoryDiagnostic {
    fun sample(context: Context): String {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val runtime = Runtime.getRuntime()
        val headroom = NativeStitchingDeviceBudget.sample(context)
        val fragments = NativeStitchingEngine.fragmentMemorySnapshot()
        val rss = runCatching {
            java.io.File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
            }
        }.getOrNull()
        val thermal = context.getSystemService(PowerManager::class.java)?.currentThermalStatus
        val refresh = runCatching { context.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate }.getOrNull()
        return "GC_PROCESS_MEMORY uptimeMs=${SystemClock.elapsedRealtime()} pid=${android.os.Process.myPid()} " +
            "pssKiB=${memory.totalPss} rssKiB=${rss ?: -1} " +
            "jvmUsedBytes=${runtime.totalMemory() - runtime.freeMemory()} jvmCommittedBytes=${runtime.totalMemory()} " +
            "nativeHeapAllocatedBytes=${Debug.getNativeHeapAllocatedSize()} nativeHeapSizeBytes=${Debug.getNativeHeapSize()} " +
            "platformGraphicsKiB=${memory.getMemoryStat("summary.graphics") ?: "unavailable"} externalCodecGpu=NOT_MEASURED defaultDisplayHz=${refresh ?: -1f} " +
            "platformNativeHeapKiB=${memory.getMemoryStat("summary.native-heap") ?: "unavailable"} " +
            "platformJavaHeapKiB=${memory.getMemoryStat("summary.java-heap") ?: "unavailable"} " +
            "platformCodeKiB=${memory.getMemoryStat("summary.code") ?: "unavailable"} " +
            "platformStackKiB=${memory.getMemoryStat("summary.stack") ?: "unavailable"} " +
            "platformPrivateOtherKiB=${memory.getMemoryStat("summary.private-other") ?: "unavailable"} " +
            "platformSystemKiB=${memory.getMemoryStat("summary.system") ?: "unavailable"} " +
            "process64Bit=${android.os.Process.is64Bit()} nativeLibraryReady=${NativeStitchingEngine.isLibraryReady()} " +
            "osAvailableBytes=${headroom?.available ?: -1} osThresholdBytes=${headroom?.threshold ?: -1} " +
            "lowRam=${headroom?.lowRam} lowMemory=${headroom?.lowMemory} thermal=${thermal ?: -1} effectivePlaybackThermal=${VideoThermalBudget.effectiveStatus(thermal ?: -1)} " +
            fragmentFields(fragments) + " " + engineFields(NativeStitchingEngine.engineMemorySnapshot()) +
            SpeculativeMemoryAdmission.process.snapshot().let { (reserved, peak) ->
                " speculativeForecastBytes=$reserved speculativePeakForecastBytes=$peak"
            } + SpeculativeMemoryAdmission.process.foregroundSnapshot().let { (current, peak) ->
                " foregroundForecastBytes=$current foregroundPeakForecastBytes=$peak"
            } + Media3MemoryOwners.snapshot().let { (owners, bytes) ->
                " media3AllocatorOwners=$owners media3InUseBytes=$bytes"
            } + Media3MemoryOwners.ownerFields() + app.apexview.scene.SceneGraphicsOwners.process.fields() + MediaMemoryAccounting.process.snapshot().entries.joinToString("") { (kind, usage) ->
                " ${kind.name}OwnedBytes=${usage.owned} ${kind.name}PinnedBytes=${usage.pinned}"
            }
    }

    fun engineFields(values: LongArray?): String {
        if (values == null || (values.size != 9 && values.size != 10) || values.any { it < 0L } ||
            values[2] > values[1] || values[3] > values[1] - values[2] || values[5] < values[2] || values[5] > values[1]) return "nativeEngineArena=UNAVAILABLE"
        return "nativeEngineId=${values[0]} nativeEngineReservedBytes=${values[1]} " +
            "nativeEnginePayloadBytes=${values[2]} nativeEngineBridgeBytes=${values[3]} " +
            "nativeEnginePayloadAllocations=${values[4]} nativeEnginePeakPayloadBytes=${values[5]} " +
            "nativeEngineInputCopyBytes=${values[6]} nativeEngineOutputCopyBytes=${values[7]} nativeEngineFutureCommitmentBytes=${values[8]}" +
            (if (values.size == 10) " nativeEngineOwnedRunning=${values[9]} nativeEnginePayloadLowerBound=${values[9] > 0}" else "")
    }

    // A null snapshot means the library was not loaded or the query failed;
    // zero is reserved for a successfully measured empty native arena.
    fun fragmentFields(values: LongArray?): String {
        if (values == null || (values.size != 5 && values.size != 9) || values.any { it < 0L } ||
            values[2] > values[1] || values[1] > values[0]) return "nativeFragmentArena=UNAVAILABLE"
        return "nativeFragmentCapacityBytes=${values[0]} nativeFragmentLiveCapacityBytes=${values[1]} " +
            "nativeFragmentLivePayloadBytes=${values[2]} nativeFragmentFreeCapacityBytes=${values[0] - values[1]} " +
            "nativeFragmentBackings=${values[3]} nativeFragmentAllocations=${values[4]}" +
            (if (values.size == 9) " nativeOwnedOutputAllocations=${values[5]} nativeOwnedOutputReuses=${values[6]} " +
                "nativeAdoptedFragments=${values[7]} nativeAdoptedBytes=${values[8]}" else "")
    }
}
