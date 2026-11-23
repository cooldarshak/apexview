package app.apexview.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REAL-device-only UI / FrameTimeline measurement. The operator signs into
 * their authorized F1 TV session and opens active playback BEFORE running a
 * test. Repeat the same tests with overlays OFF, map, tower and both active;
 * repeat at 1x, 1.5x, on phone, TCL and the Cast receiver as applicable.
 *
 * The test deliberately fails rather than silently benchmarking the login
 * screen or substituting a synthetic feed. No credentials or media URLs are
 * embedded. Use an installed non-debuggable benchmark variant.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class PitwallFrameTimingBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    private val targetPackage = "app.apexview_custom"

    private fun measure(operation: androidx.benchmark.macro.MacrobenchmarkScope.() -> Unit) =
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Ignore(),
            iterations = 3,
            startupMode = null,
        ) {
            assertEquals(
                "Open real, authorized playback in the benchmark app first. " +
                    "Repeat separately for overlays OFF, map, tower, both.",
                targetPackage, device.currentPackageName,
            )
            operation()
        }

    /** Stable-playback frames, including a separate SurfaceFlinger video layer. */
    @Test fun playbackWithSelectedOverlayConfiguration() = measure {
        Thread.sleep(8_000L)
    }

    /** Playback controls, map/tower touch hit targets and TV DPAD navigation. */
    @Test fun controlAndRemoteNavigation() = measure {
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        device.pressDPadDown()
        device.pressDPadRight()
        device.pressDPadLeft()
        device.pressDPadUp()
        Thread.sleep(2_000L)
    }

    /** Lifecycle recreation/Compose layout response while an authorized feed runs. */
    @Test fun orientationAndLayoutChange() = measure {
        try {
            device.setOrientationLeft()
            Thread.sleep(2_000L)
            device.setOrientationNatural()
            Thread.sleep(2_000L)
        } finally {
            device.setOrientationNatural()
        }
    }

    /** Transport seek gestures / feed-strip changes on the foreground viewer. */
    @Test fun playbackScrubAndFeedInteraction() = measure {
        val centerY = device.displayHeight / 2
        val left = device.displayWidth / 4
        val right = device.displayWidth * 3 / 4
        device.click(device.displayWidth / 2, centerY)
        device.swipe(left, centerY, right, centerY, 20)
        Thread.sleep(2_000L)
        device.swipe(right, centerY, left, centerY, 20)
        Thread.sleep(2_000L)
    }
}
