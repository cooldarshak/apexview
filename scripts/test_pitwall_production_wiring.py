"""Regression contracts for source actually wired into Pitwall's player and receiver.

These tests catch orphaned policy classes or removed production entry points.
They are not device playback/DRM acceptance; those remain separate gates.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


def contents(path):
    return (ROOT / path).read_text(encoding="utf-8")


class ProductionWiringTests(unittest.TestCase):
    def test_main_and_cast_receiver_use_real_expressive_theme(self):
        activity = contents("app/src/main/java/app/apexview/MainActivity.kt")
        receiver = contents("app/src/main/java/app/apexview/cast/local/PitwallReceiverActivity.kt")
        design = contents("app/src/main/java/app/apexview/Design.kt")
        dependencies = contents("app/build.gradle.kts")
        self.assertIn("setContent { AppTheme { Root(app) } }", activity)
        self.assertIn("setContent { AppTheme { ReceiverScreen() } }", receiver)
        self.assertIn("MaterialExpressiveTheme(", design)
        self.assertIn("LocalReduceMotion provides reducedMotion", design)
        self.assertIn('implementation("androidx.compose.material3:material3:1.5.0")', dependencies)

    def test_player_browser_and_remote_path_apply_reduce_motion(self):
        activity = contents("app/src/main/java/app/apexview/MainActivity.kt")
        viewer = contents("app/src/main/java/app/apexview/Viewer.kt")
        motion = contents("app/src/main/java/app/apexview/Motion.kt")
        chrome = contents("app/src/main/java/app/apexview/ChromeLayoutPolicy.kt")
        self.assertGreaterEqual(activity.count("val reducedMotion = LocalReduceMotion.current"), 2)
        self.assertIn("delayMillis = if (reducedMotion) 0 else 60", activity)
        self.assertIn("if (reducedMotion) 0 else Motion.SCREEN_MS", activity)
        self.assertIn("if (reducedMotion) ReducedArtBounds else ArtBounds", motion)
        self.assertIn("ChromeLayoutPolicy.compact(", viewer)
        self.assertIn("TopChromeExtraActions(app, compact = true)", viewer)
        self.assertIn("widthDp < 1200f", chrome)
        self.assertIn("ChromeLayoutPolicy.compactWithClearance(", viewer)
        self.assertIn("cutout.start", viewer)
        self.assertIn("cutout.end", viewer)
        for name in ("GoogleCastPlayerButton(app)", "TowerPanel(app", "MapPanelView(app"):
            self.assertIn(name, viewer)

    def test_portrait_browse_is_wired_into_real_shelf_and_hero(self):
        browse = contents("app/src/main/java/app/apexview/BrowseScreens.kt")
        policy = contents("app/src/main/java/app/apexview/BrowseLayoutPolicy.kt")
        unit = contents("app/src/test/java/app/apexview/BrowseLayoutPolicyTest.kt")
        self.assertIn("portrait = compactPortrait", browse)
        self.assertIn("Type.title1.copy(fontSize = 30.sp, lineHeight = 36.sp) else Type.largeTitle", browse)
        self.assertIn("BrowseSectionGap()", browse)
        self.assertIn("HOME_GEOMETRY portrait=", browse)
        self.assertIn("fun compactPortrait(isPortrait: Boolean, widthDp: Float)", policy)
        self.assertIn("fun portraitPhoneBalancesReadableCardsAndNextPreview()", unit)
        self.assertIn("cardTitleMaxLines(portrait, config.screenWidthDp.toFloat())", browse)
        self.assertIn("cardSubtitleMaxLines(portrait, config.screenWidthDp.toFloat(), fontScale)", browse)
        self.assertIn("available * (if (posters) 0.55f else 0.60f)", policy)
        self.assertIn("val reducedMotion = LocalReduceMotion.current", browse)
        self.assertIn(".crossfade(if (reducedMotion) 0 else Motion.BROWSE_FADE_MS)", browse)

    def test_source_archive_cleanup_and_error_classification_are_wired(self):
        session = contents("app/src/main/java/app/apexview/tiled/HevcArchiveVodSession.kt")
        ownership = contents("app/src/main/java/app/apexview/tiled/ArchiveBufferPolicy.kt")
        delivery = contents("app/src/main/java/app/apexview/tiled/ArchiveSegmentDelivery.kt")
        self.assertIn("OwnedArchiveInitializations.snapshot(inputs.map { it.initialization })", session)
        self.assertIn("} finally { ownedInits.close() }", session)
        self.assertIn("internal class OwnedArchiveInitializations", ownership)
        self.assertIn("root !is java.net.SocketTimeoutException", session)
        self.assertIn("it is java.net.SocketTimeoutException", delivery)
        self.assertIn("fun foregroundJoinerRecoversFromCancelledPrefetchWithoutDuplicatingProduction()",
                      contents("app/src/test/java/app/apexview/tiled/ArchiveSegmentDeliveryTest.kt"))

    def test_real_window_metrics_include_frame_stage_p95_and_heap_snapshots(self):
        diagnostics = contents("app/src/main/java/app/apexview/WindowFrameDiagnostics.kt")
        self.assertIn("jvmHeapUsedMiB=$heapUsedMiB", diagnostics)
        self.assertIn("nativeHeapAllocatedMiB=$nativeHeapMiB", diagnostics)
        for stage in ("layout", "draw", "sync", "command", "swap", "gpu"):
            self.assertIn(stage + "P95UpperMs=", diagnostics)
        self.assertIn("window.addOnFrameMetricsAvailableListener(listener", diagnostics)

    def test_archive_policy_reaches_real_datasource_without_extra_warm_copy(self):
        session = contents("app/src/main/java/app/apexview/tiled/HevcArchiveVodSession.kt")
        policy = contents("app/src/main/java/app/apexview/tiled/ArchiveBufferPolicy.kt")
        delivery = contents("app/src/main/java/app/apexview/tiled/ArchiveSegmentDelivery.kt")
        self.assertIn("private val cache = BoundedArchiveFragmentCache()", session)
        self.assertIn("private val production = ArchiveSegmentSingleFlight()", session)
        self.assertIn("production.run(number)", delivery)
        self.assertIn("private val delivery = ArchiveSegmentDelivery(", session)
        self.assertIn("delivery.obtain(probe, copyForCaller = false)", session)
        self.assertIn("delivery.obtain(candidate, copyForCaller = false)", session)
        self.assertIn("ArchiveWarmupFailurePolicy.classify(", session)
        self.assertIn("ARCHIVE_VOD_PREFETCH_STOP", session)
        self.assertIn("ARCHIVE_VOD_DELIVERY_CACHE_RACE", session)
        self.assertNotIn("runCatching { ensureCached(", session)
        self.assertIn("MAX_EVICTION_RETRIES = 2", delivery)
        self.assertIn("production.close()", session)
        self.assertIn("ArchiveFetchDeadline.remainingNs(", session)
        self.assertIn("onEvictionRace = { number, attempt ->", session)
        self.assertNotIn("composeOrRead(candidate).fill(0)", session)
        self.assertIn("cache.readCopy(number)", delivery)
        self.assertIn("cache.contains(number)", delivery)
        self.assertIn("cache.put(number, output)", session)
        self.assertIn("MAX_RETAINED_MEDIA_BYTES", policy)
        self.assertIn("pending.forEach { it.finished.completeExceptionally", policy)

    def test_clear_and_protected_writers_remain_real_player_sources(self):
        session = contents("app/src/main/java/app/apexview/tiled/HevcArchiveVodSession.kt")
        clear = contents("app/src/main/java/app/apexview/tiled/HevcClearCmafGridComposer.kt")
        protected = contents("app/src/main/java/app/apexview/tiled/ProtectedHevcLiveComposition.kt")
        source = contents("app/src/main/java/app/apexview/tiled/ProtectedHevcLiveDashSource.kt")
        viewer = contents("app/src/main/java/app/apexview/Viewer.kt")
        self.assertIn("HevcClearCmafGridComposer.compose(", session)
        self.assertIn("HevcClearCmafFragmentWriter.writeNalSamples(", clear)
        self.assertIn("ProtectedOutputHistoryPolicy.evictions(", protected)
        self.assertIn("takeOwnership = true", source)
        self.assertIn("ProtectedDecoderSceneView(", viewer)
        self.assertIn("SingleDecoderSceneView(", viewer)

    def test_accessible_pickers_and_settings_share_live_callbacks(self):
        sheets = contents("app/src/main/java/app/apexview/Sheets.kt")
        self.assertIn("app.togglePick(channel)", sheets)
        self.assertIn("role = Role.Checkbox", sheets)
        self.assertIn("Role.RadioButton", sheets)
        self.assertIn(".toggleable(value = checked, role = Role.Switch", sheets)
        self.assertIn("onClick = { app.watchPicked() }", sheets)
        self.assertIn("internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit)", sheets)
        self.assertIn("private fun SettingRow(", sheets)

    def test_ci_never_assembles_or_publishes_an_apk_after_failure(self):
        workflow = contents(".github/workflows/android-debug-apk.yml")
        assemble = re.search(
            r"- name: Assemble debug APK\s+id: assemble_apks\s+if: success\(\)",
            workflow,
        )
        self.assertIsNotNone(assemble)
        self.assertNotIn("if: always() && !cancelled()", workflow)
        self.assertIn("cancel-in-progress: true", workflow)
        self.assertIn("if: success() && steps.assemble_apks.outcome == 'success' && env.APK_SIGNING_READY == 'true'", workflow)
        self.assertIn("Execute native RenderNode recording and replay instrumentation", workflow)
        self.assertLess(workflow.index("- name: Execute native RenderNode recording and replay instrumentation"),
                        workflow.index("- name: Upload debug APK"))
        instrumentation = contents("scripts/run_rendernode_instrumentation.sh")
        self.assertIn("app.apexview.tiled.ArchiveSegmentDeliveryAndroidTest", instrumentation)
        self.assertIn("'OK (2 tests)'", instrumentation)


if __name__ == "__main__":
    unittest.main()
