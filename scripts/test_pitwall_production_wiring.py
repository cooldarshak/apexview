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
    def test_active_composition_generation_owns_diagnostics_and_remove_revokes_old_state(self):
        app = contents("app/src/main/java/app/apexview/AppState.kt")
        viewer = contents("app/src/main/java/app/apexview/Viewer.kt")
        owner = contents("app/src/main/java/app/apexview/CompositionDiagnosticOwner.kt")
        self.assertIn("private val compositionDiagnosticOwner = CompositionDiagnosticOwner()", app)
        self.assertEqual(
            2,
            app.count("if (!compositionDiagnosticOwner.isOwner(diagnosticGeneration) || f1DashCapture !== capture) return")
        )
        self.assertIn("compositionDiagnosticOwner.invalidate()", app)
        remove = app.split("    fun remove(tile: Tile) {", 1)[1].split("\n    fun stopAll()", 1)[0]
        self.assertIn("invalidateCompositionDiagnostic()", remove)
        stop = app.split("    fun stopProtectedCompositionPresentation(", 1)[1].split("\n    /** Single-decoder selections", 1)[0]
        self.assertIn("invalidateCompositionDiagnostic()", stop)
        self.assertIn("Current feeds: ${app.tiles.size}", viewer)
        self.assertIn("Composition feeds: ${selected.size}", app)
        self.assertIn("Composition feeds: ${selectedTiles.size}", app)
        self.assertNotIn('singleDecoderDiagnostic = singleDecoderDiagnostic?.replace(', app)
        self.assertIn("fun isOwner(generation: Long)", owner)

    def test_batch1_media3_upgrade_and_clear_ab_are_production_wired(self):
        gradle = contents("app/build.gradle.kts")
        viewer = contents("app/src/main/java/app/apexview/Viewer.kt")
        settings = contents("app/src/main/java/app/apexview/Settings.kt")
        sheets = contents("app/src/main/java/app/apexview/Sheets.kt")
        policy = contents("app/src/main/java/app/apexview/ClearSceneOutputPolicy.kt")
        scene = contents("app/src/main/java/app/apexview/scene/ProtectedDecoderSceneView.kt")
        texture = contents("app/src/main/java/app/apexview/scene/SingleDecoderSceneView.kt")
        self.assertIn('val media3 = "1.11.1"', gradle)
        self.assertEqual(5, gradle.count('"androidx.media3:media3-'))
        self.assertNotIn('val media3 = "1.5.1"', gradle)
        self.assertIn('prefs.getBoolean("clearSceneSurfaceExperiment", false)', settings)
        self.assertIn("s::updateClearSceneSurfaceExperiment", sheets)
        self.assertIn("app.bakedCastSession != null || app.castingActive", viewer)
        self.assertIn("app.replayClipRequested || app.replayClipRunning", viewer)
        self.assertIn("app.externalDisplayActive", viewer)
        self.assertIn("selectClearSceneOutput(", viewer)
        self.assertIn("if (app.protectedRollingSceneActive || independentClearSurface)", viewer)
        self.assertIn("ProtectedDecoderSceneView(context, protectedOutput = secureRolling)", viewer)
        self.assertIn("SingleDecoderSceneView(context)", viewer)
        self.assertIn("CLEAR_SURFACE_AB_REVERT", viewer)
        self.assertIn("kotlinx.coroutines.withTimeoutOrNull(5_000L)", viewer)
        self.assertIn("app.settings.updateClearSceneSurfaceExperiment(false)", viewer)
        self.assertIn("backend=", viewer)
        self.assertIn("CLEAR_ARCHIVE", viewer)
        self.assertIn("!protectedRollingScene && !windowCaptureRequired", policy)
        self.assertIn("if (protectedOutput) setSecure(true)", scene)
        self.assertIn('"PitwallClearSurfaceScene"', scene)
        self.assertIn('Trace.setCounter("PitwallSurfaceSceneLatched5s"', scene)
        self.assertIn('Trace.setCounter("PitwallTextureSceneLatched5s"', texture)
        frame_thinning = contents("app/src/main/java/app/apexview/FrameThinning.kt")
        self.assertIn("isAdaptiveFormatChange: Boolean", frame_thinning)
        self.assertIn("super.canReuseCodec(codecInfo, oldFormat, newFormat, isAdaptiveFormatChange)",
                      frame_thinning)
        self.assertIn("sampleStreamIsResetToKeyFrame: Boolean", frame_thinning)
        self.assertIn("super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)",
                      frame_thinning)
        self.assertIn("metrics.latched.coerceAtLeast(0L)", scene)
        self.assertIn("metrics.latched.coerceAtLeast(0L)", texture)
        instrumentation = contents("scripts/run_rendernode_instrumentation.sh")
        self.assertIn("app.apexview.scene.SurfaceDecoderSceneInstrumentationTest", instrumentation)
        self.assertIn("app.apexview.scene.SingleDecoderSceneInstrumentationTest", instrumentation)
        tests = contents("app/src/test/java/app/apexview/ClearSceneOutputPolicyTest.kt")
        for test in ("productionDefaultIsTextureView", "optedInClearCompositionUsesIndependentSurfaceView",
                     "protectedVideoIsNeverChangedByClearExperiment",
                     "castAndReplayRequireOriginalCaptureCompatibleOutput",
                     "switchingCaptureStateRevertsAndRestoresIndependentLayer"):
            self.assertIn(test, tests)

    def test_thermal_startup_notice_has_initialized_compose_message_state(self):
        # Kotlin executes property initializers and init blocks in source order.
        # Merely compiling a delegated property does not catch an early setter
        # dereferencing its not-yet-initialized delegate when startup is warm.
        app = contents("app/src/main/java/app/apexview/AppState.kt")
        self.assertLess(app.index("var message by mutableStateOf<String?>(null)"),
                        app.index("power?.addThermalStatusListener(context.mainExecutor, thermalListener)"))
        self.assertLess(app.index("var message by mutableStateOf<String?>(null)"),
                        app.index("VideoThermalBudget.startupNotice(thermal)?.let { message = it }"))
        self.assertIn("VideoThermalBudget.transitionNotice(status, before)?.let { message = it }", app)
        self.assertEqual(app.count("var message by mutableStateOf<String?>(null)"), 1)

    def test_mixed_atlas_checks_real_decoder_support_before_publishing(self):
        session = contents("app/src/main/java/app/apexview/tiled/HevcArchiveVodSession.kt")
        atlas = contents("app/src/main/java/app/apexview/tiled/HevcMixedArchiveAtlasPlan.kt")
        probe = contents("app/src/main/java/app/apexview/tiled/HevcArchiveDecoderCapability.kt")
        self.assertIn("outputEligible = { width, height ->", session)
        self.assertIn("HevcArchiveDecoderCapability.supportsClearAtlas(width, height, sourceRate)", session)
        self.assertIn("atlasDecoderCandidatesRejected=", session)
        self.assertIn("smallestAreaProven = !searchBudgetLimited || bestArea == sourceAreaLowerBound", atlas)
        self.assertIn("capabilities.videoCapabilities?.areSizeAndRateSupported(", probe)
        self.assertIn("FEATURE_SecurePlayback", probe)
        self.assertIn("allDeviceRejectedCandidatesFailClosedRatherThanSwitchingDecoders",
                      contents("app/src/test/java/app/apexview/tiled/HevcMixedArchiveAtlasPlanTest.kt"))

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

    def test_real_frame_benchmark_api_compiles_before_apk_and_has_installable_target(self):
        # A source contract that complements, never replaces, the mandatory
        # executable Kotlin compilation of BOTH benchmark variants in CI.
        # Build 811 failed late because the JUnit4 Rule was imported from the
        # wrong AndroidX package; Build 812 lacked an updated regression test.
        benchmark = contents(
            "macrobenchmark/src/main/java/app/apexview/macrobenchmark/"
            "PitwallFrameTimingBenchmark.kt"
        )
        module = contents("macrobenchmark/build.gradle.kts")
        application = contents("app/build.gradle.kts")
        workflow = contents(".github/workflows/android-debug-apk.yml")
        self.assertIn(
            "import androidx.benchmark.macro.junit4.MacrobenchmarkRule", benchmark
        )
        self.assertNotIn(
            "import androidx.benchmark.macro.MacrobenchmarkRule", benchmark
        )
        self.assertIn("@OptIn(ExperimentalMacrobenchmarkApi::class)", benchmark)
        self.assertIn("compilationMode = CompilationMode.Ignore()", benchmark)
        self.assertIn("metrics = listOf(FrameTimingMetric())", benchmark)
        self.assertIn('id("com.android.test")', module)
        self.assertIn('targetProjectPath = ":app"', module)
        self.assertIn("isDebuggable = false", application)
        self.assertIn(
            "testDebugUnitTest :macrobenchmark:compileDebugKotlin "
            ":macrobenchmark:compileBenchmarkKotlin", workflow
        )
        self.assertIn(":app:assembleBenchmark :macrobenchmark:assembleBenchmark", workflow)
        self.assertIn("name: pitwall-benchmark-target-apk", workflow)
        self.assertIn("path: app/build/outputs/apk/benchmark/*.apk", workflow)
        self.assertIn("name: pitwall-macrobenchmark-instrumentation", workflow)

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
