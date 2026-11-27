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
            } + Media3MemoryOwners.ownerFields() + app.apexview.scene.SceneGraphicsOwners.process.fields() +
            MediaMemoryAccounting.process.diagnosticFields() + " " +
            compactTimingFields(app.apexview.tiled.NativeHotPath.compactTimingSnapshot())
    }

    /** Packed payload is already included in native heap/PSS. Do not add it
     * again or report it as a measured total process saving. */
    fun compactTimingFields(values: LongArray?): String {
        if (values == null || values.size !in setOf(3, 6) || values.any { it < 0 } ||
            values[1] > 64L * 1024 * 1024 || values[0] > values[2] ||
            values[0] > values[1] / 8 || values[2] > values[1] / 4 ||
            values[0] * 8 + values[2] * 4 != values[1]) return "nativeCompactTiming=UNAVAILABLE"
        val positions = if (values.size == 6) {
            if (values[4] > 64L * 1024 * 1024 - values[1] || values[3] > values[5] ||
                values[5] > values[4] / 4 || values[3] > values[4] / 8 ||
                values[4] - values[5] * 4 < values[3] * 8 ||
                (values[4] - values[5] * 4) % 8 != 0L) return "nativeCompactTiming=UNAVAILABLE"
            " nativeCompactCoordinateOwners=${values[3]} nativeCompactCoordinateBytes=${values[4]} nativeCompactCoordinateSamples=${values[5]}"
        } else " nativeCompactCoordinateBytes=UNAVAILABLE"
        return "nativeCompactTimingOwners=${values[0]} nativeCompactTimingBytes=${values[1]} " +
            "nativeCompactTimingSamples=${values[2]} nativeCompactTimingScope=REGISTERED_PAYLOAD" + positions
    }

    fun engineFields(values: LongArray?): String {
        if (values == null || values.size !in setOf(9, 10, 12) || values.any { it < 0L } ||
            values[2] > values[1] || values[3] > values[1] - values[2] || values[5] < values[2] || values[5] > values[1] ||
            (values.size == 12 && (values[11] !in 0L..1L || values[11] == 0L && values[10] != 0L)))
            return "nativeEngineArena=UNAVAILABLE"
        return "nativeEngineId=${values[0]} nativeEngineReservedBytes=${values[1]} " +
            "nativeEnginePayloadBytes=${values[2]} nativeEngineBridgeBytes=${values[3]} " +
            "nativeEnginePayloadAllocations=${values[4]} nativeEnginePeakPayloadBytes=${values[5]} " +
            "nativeEngineInputCopyBytes=${values[6]} nativeEngineOutputCopyBytes=${values[7]} nativeEngineFutureCommitmentBytes=${values[8]}" +
            (if (values.size >= 10) " nativeEngineOwnedRunning=${values[9]} nativeEnginePayloadLowerBound=${values[9] > 0}" else "") +
            (if (values.size == 12) " nativeWorkspaceKnownCapacityBytes=${if (values[11] == 1L) values[10].toString() else "UNAVAILABLE"}" +
                " nativeWorkspaceScope=PARTIAL_OWNED_CAPACITY" else " nativeWorkspaceKnownCapacityBytes=UNAVAILABLE")
    }

    // A null snapshot means the library was not loaded or the query failed;
    // zero is reserved for a successfully measured empty native arena.
    fun fragmentFields(values: LongArray?): String {
        if (values == null || (values.size !in setOf(5,9,15,21,27,28)) || values.any { it < 0L } ||
            values[2] > values[1] || values[1] > values[0] ||
            (values.size >= 15 && (values[10] > values[9] || values[10] > values[0] - values[1])) ||
            (values.size >= 21 && (values[15] < values[6] || values[20] < values[0])) ||
            // One outcome per attempt and one distinct bypass per too-small
            // observation; validate using subtraction to avoid Long overflow.
            (values.size >= 27 && (values[23] > values[21] || values[24] > values[23] ||
                values[25] > values[23] - values[24] ||
                values[26] != values[23] - values[24] - values[25])) ||
            (values.size == 28 && values[27] > values[21] - values[23]))
            return "nativeFragmentArena=UNAVAILABLE"
        return "nativeFragmentCapacityBytes=${values[0]} nativeFragmentLiveCapacityBytes=${values[1]} " +
            "nativeFragmentLivePayloadBytes=${values[2]} nativeFragmentFreeCapacityBytes=${values[0] - values[1]} " +
            "nativeFragmentBackings=${values[3]} nativeFragmentAllocations=${values[4]}" +
            (if (values.size >= 9) " nativeOwnedOutputAllocations=${values[5]} nativeOwnedOutputReuses=${values[6]} " +
                "nativeAdoptedFragments=${values[7]} nativeAdoptedBytes=${values[8]}" else "") +
            (if (values.size >= 15) " nativeAtlasIdleLimitBytes=${values[9]} nativeAtlasIdleBytes=${values[10]}" +
                " nativeOwnedMissEmpty=${values[11]} nativeOwnedMissFit=${values[12]}" +
                " nativeOwnedMissCeiling=${values[13]} nativeOwnedAllocFailures=${values[14]}" else "") +
            (if (values.size >= 21) " nativeOwnedEligible=${values[15]} nativeOwnedMissPinned=${values[16]}" +
                " nativeOwnedMissDisabled=${values[17]} nativeAdoptRejected=${values[18]}" +
                " nativeStaleReleases=${values[19]} nativeFragmentPeakCapacityBytes=${values[20]}" else "") +
            (if (values.size >= 27) " nativeAtlasFitTooSmall=${values[21]} nativeAtlasFitTooLarge=${values[22]}" +
                " nativeAtlasResizeAttempts=${values[23]} nativeAtlasResizeSameAddress=${values[24]}" +
                " nativeAtlasResizeMoved=${values[25]} nativeAtlasResizeFailed=${values[26]}" else "") +
            (if (values.size == 28) " nativeAtlasResizeBypassed=${values[27]}" else "")
    }
}
