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
            app.count("if (!compositionDiagnosticOwner.isOwner(diagnosticGeneration) || playbackWork.capture !== capture) return")
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
        android_renderer_test = contents(
            "app/src/androidTest/java/app/apexview/scene/SingleDecoderSceneInstrumentationTest.kt"
        )
        self.assertIn("codec!!.isHardwareAccelerated || (isEmulator && !offersHardwareHevc)",
                      android_renderer_test)
        self.assertIn("physicalHardwareGate=", android_renderer_test)
        self.assertIn("maxActive.get()", android_renderer_test)
        self.assertIn("captureUntil { moved ->", android_renderer_test)
        instrumentation = contents("scripts/run_rendernode_instrumentation.sh")
        self.assertIn("app.apexview.scene.SurfaceDecoderSceneInstrumentationTest", instrumentation)
        self.assertIn("app.apexview.scene.SingleDecoderSceneInstrumentationTest", instrumentation)
        tests = contents("app/src/test/java/app/apexview/ClearSceneOutputPolicyTest.kt")
        for test in ("productionDefaultIsTextureView", "optedInClearCompositionUsesIndependentSurfaceView",
                     "protectedVideoIsNeverChangedByClearExperiment",
                     "castAndReplayRequireOriginalCaptureCompatibleOutput",
                     "switchingCaptureStateRevertsAndRestoresIndependentLayer"):
            self.assertIn(test, tests)

    def test_batch2_output_epoch_seek_capture_and_measurement_wiring(self):
        prefix = "app/src/main/java/app/apexview/"
        for name in ("SingleDecoderSceneView", "ProtectedDecoderSceneView"):
            renderer = contents(prefix + "scene/" + name + ".kt")
            self.assertIn("val surfaceEpoch = renderEpoch", renderer)
            self.assertIn("outputReadiness.matches(surfaceEpoch)", renderer)
            self.assertIn("acknowledgeSubmittedFrame(surfaceEpoch)", renderer)
            self.assertNotIn("val surfaceEpoch = outputReadiness.generation()", renderer)
            self.assertIn("coalescedTextureLatches=", renderer)
        viewer = contents(prefix + "Viewer.kt")
        self.assertIn("key(host, app.protectedRollingSceneActive, secureRolling, independentClearSurface)", viewer)
        self.assertGreaterEqual(viewer.count("kotlinx.coroutines.withTimeoutOrNull(5_000L)"), 2)
        state = contents(prefix + "AppState.kt")
        self.assertIn("playbackWork.coalesceSeek(scope, targetMs)", state)
        self.assertIn("pendingSeek.offer(targetMs)", contents("app/src/main/java/app/apexview/PlaybackWorkCoordinator.kt"))
        self.assertIn("seekToNow(latest)", state)
        stop = state.split("fun stopAll() {", 1)[1].split("fun showChrome()", 1)[0]
        self.assertIn("cancelControlSeek()", stop)
        self.assertIn("playbackWork.cancelCastRestart()", state)
        self.assertIn("if (protectedRollingScene != null) return protectedActualRenditions[label]", state)
        player = contents(prefix + "FeedPlayer.kt")
        self.assertIn("bufferEpisodes.onState", player)
        self.assertIn("cause=UNATTRIBUTED", player)
        self.assertIn("SeekParameters.EXACT", player)
        self.assertIn("releaseGate.beginRelease()", player)
        self.assertIn("nativeSourceRendition =", player)
        self.assertIn("observedLoads.size >= 128", player)
        self.assertIn("processGcCount=", contents(prefix + "WindowFrameDiagnostics.kt"))
        self.assertIn("MAX_RETAINED_WINDOWS = 24", contents(prefix + "scene/SceneQuadBufferCache.kt"))

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
        composer = contents("app/src/main/java/app/apexview/tiled/HevcClearCmafGridComposer.kt")
        grid = contents("app/src/main/java/app/apexview/tiled/HevcTizenGridComposer.kt")
        rewriter = contents("app/src/main/java/app/apexview/tiled/HevcSliceHeaderRewriter.kt")
        self.assertIn("HevcTizenGridComposer.composeNalSpans(", composer)
        self.assertIn("HevcBitstream.NalSpan(bytes, offset, nalLength)", composer)
        self.assertIn("HevcSliceHeaderRewriter.rewriteClearParts(", grid)
        self.assertIn("nal = located.nal", grid)
        self.assertIn("false, nal.offset, nal.length", rewriter)
        self.assertIn("tailStart = sourceOffset + sourceTailOffset", rewriter)
        self.assertIn("output.put(backing, tailStart, copiedTailBytes)", rewriter)
        self.assertIn("rewritten.verifyHeader(outputSps, outputPps)", grid)
        self.assertIn("(!preserveOpaqueCabacTail && clearNalBytes == sourceLength)", rewriter)
        self.assertIn("cache.readLease(number)", delivery)
        self.assertIn("session.segmentLease(number)", session)
        self.assertIn("body.readInto(buffer, offset, cursor, count)", session)
        self.assertIn("resource?.close()", session)
        self.assertIn("ArrayBlockingQueue<Runnable>", session)
        self.assertIn("admission.run(ArchiveFetchDeadline.remainingNs(", session)
        self.assertIn("composeAndPublishAdmitted(number, requestedAt)", session)
        self.assertIn("foregroundAdmission.close(); prefetchAdmission.close()", session)
        self.assertIn("lane.purge()", session)
        self.assertIn("busy = false; changed.signalAll()", policy)
        self.assertIn("cache.contains(number)", delivery)
        self.assertIn("cache.put(number, output)", session)
        self.assertIn("MAX_RETAINED_MEDIA_BYTES", policy)
        self.assertIn("pending.forEach { it.finished.completeExceptionally", policy)

    def test_protected_resource_retirement_and_idempotent_archive_audio_are_wired(self):
        source = contents("app/src/main/java/app/apexview/tiled/ProtectedHevcLiveDashSource.kt")
        self.assertIn("previous.values.forEach(::retire)", source)
        self.assertIn("retired.remove(resource)", source)
        self.assertIn("PROTECTED_DASH_RESOURCE_BYTES", source)
        self.assertIn("resources.replace(next)", source)
        host = contents("app/src/main/java/app/apexview/FeedPlayer.kt")
        audio = host.split("internal fun selectArchiveAudio(", 1)[1].split("internal fun releaseArchiveOwnership", 1)[0]
        self.assertLess(audio.index("archiveProgrammeLabel == label"), audio.index("startArchiveVodPlayback("))
        self.assertIn("archiveSelectedAudioUri = previousAudio", host)
        self.assertIn("archiveSelectedAudioUri = audioUri", host)
        self.assertIn("archiveSelectedAudioUri = null", host)

    def test_production_cadence_audio_seek_thermal_and_async_log_wiring(self):
        prefix = "app/src/main/java/app/apexview/"
        player = contents(prefix + "FeedPlayer.kt")
        receiver = contents(prefix + "cast/local/PitwallReceiverActivity.kt")
        self.assertIn("AdaptivePlaybackCadenceObserver(context, sceneCodecCorrelation)", player)
        self.assertIn("AdaptivePlaybackCadenceObserver(this, correlation)", receiver)
        self.assertIn("setReceiverOutputDisplay(displayOwner, displayId)", receiver)
        for name in ("SingleDecoderSceneView", "ProtectedDecoderSceneView"):
            renderer = contents(prefix + "scene/" + name + ".kt")
            self.assertIn("recordPipelineWindow(input, now, now - lastLogNs", renderer)
            self.assertIn("pipelineHealthy()", renderer)
        self.assertIn("ArchiveAudioSelectionPlan.sameTimeline(", contents(prefix + "tiled/F1HlsFeedCapture.kt"))
        self.assertIn("ArchiveAudioSelectionPlan.children(audioUri, session.stableAudioUris)", player)
        self.assertIn("setOverrideForType(override)", player)
        selection = player.split("internal fun selectArchiveAudio(", 1)[1].split("internal fun releaseArchiveOwnership", 1)[0]
        self.assertLess(selection.index("applyArchiveAudioTrack(audio, player.currentTracks)"),
                        selection.index("startArchiveVodPlayback("))
        state = contents(prefix + "AppState.kt")
        self.assertIn("singleDecoderScenePlayer?.isArchiveVodPlayback == true", state)
        self.assertIn("singleDecoderScenePlayer?.prioritizeArchiveSeek(targetMs)", state)
        self.assertIn("protectedCompositionHost?.feed?.updateArchiveThermalStatus(thermal)", state)
        session = contents(prefix + "tiled/HevcArchiveVodSession.kt")
        self.assertIn("ArchiveTimelineSearch.floorIndex(", session)
        # Preserve thermal gating and require memory gating before scheduling
        # and before each speculative fetch.
        self.assertIn("warmLimit = next", session)
        self.assertIn("ArchiveBufferPolicy.speculativeWarmLimit(status)", session)
        self.assertIn("val memoryWarmLimit = ArchiveBufferPolicy.memoryWarmLimit(warmLimit, headroom, observed.input, observed.output)", session)
        self.assertIn("!closed && memoryWarmLimit > 0", session)
        self.assertIn("ArchiveBufferPolicy.warmFollowing(number, groups.lastIndex, memoryWarmLimit)", session)
        self.assertIn("candidate - number > warmLimit", session)
        self.assertRegex(session, r"if \(candidate - number > ArchiveBufferPolicy\.memoryWarmLimit\(warmLimit, currentMemory,\s*currentSizes\.input, currentSizes\.output\)\) break")
        policy = contents(prefix + "tiled/ArchiveBufferPolicy.kt")
        self.assertIn("if (headroom == null) return thermalLimit", policy)
        self.assertIn("if (headroom.lowMemory || headroom.available <= headroom.threshold) return 0", policy)
        self.assertIn("return minOf(thermalLimit, (spare / charge)", policy)
        logs = contents(prefix + "DiagnosticLogs.kt")
        self.assertIn("appendQueue.offer(text)", logs)
        self.assertIn("appendQueue.drain(token, close = closeQueue)", logs)
        self.assertIn("DIAGNOSTIC_DIRECT_QUEUE_OVERFLOW", logs)
        self.assertIn('text.startsWith("FATAL EXCEPTION")', logs)
        self.assertIn("if (token == generation) status = value", logs)

    def test_clear_and_protected_writers_remain_real_player_sources(self):
        session = contents("app/src/main/java/app/apexview/tiled/HevcArchiveVodSession.kt")
        clear = contents("app/src/main/java/app/apexview/tiled/HevcClearCmafGridComposer.kt")
        protected = contents("app/src/main/java/app/apexview/tiled/ProtectedHevcLiveComposition.kt")
        source = contents("app/src/main/java/app/apexview/tiled/ProtectedHevcLiveDashSource.kt")
        viewer = contents("app/src/main/java/app/apexview/Viewer.kt")
        self.assertIn("HevcClearCmafGridComposer.compose(", session)
        self.assertIn("HevcClearCmafFragmentWriter.writeNalParts(", clear)
        self.assertIn("emitNalParts = true", clear)
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
        build, rest = workflow.split("\n  g0_emulator:\n", 1)
        matrix, publish = rest.split("\n  g0_publish:\n", 1)
        self.assertRegex(build,
            r"- name: Assemble debug APK\s+id: assemble_apks\s+if: success\(\)")
        self.assertNotIn("if: always() && !cancelled()", workflow)
        self.assertIn("cancel-in-progress: true", workflow)
        legacy = "github.ref != 'refs/heads/feature/architecture-g-rust-stitching'"
        self.assertIn("if: " + legacy + " && success() && steps.assemble_apks.outcome == 'success' && env.APK_SIGNING_READY == 'true'", build)
        self.assertLess(build.index("- name: Execute native RenderNode recording and replay instrumentation"),
                        build.index("- name: Upload debug APK"))
        self.assertIn("needs: build", matrix)
        self.assertIn("needs.build.result == 'success'", matrix)
        self.assertIn("fail-fast: false", matrix)
        self.assertIn("max-parallel: 3", matrix)
        for api in (29, 31, 36):
            self.assertIn(f"- api: {api}", matrix)
        self.assertIn("scripts/run_rendernode_instrumentation.sh", matrix)
        self.assertIn("needs: [build, g0_emulator]", publish)
        self.assertIn("needs.build.result == 'success'", publish)
        self.assertIn('test "$MATRIX_RESULT" = success', publish)
        gate = "success() && needs.g0_emulator.result == 'success'"
        restore = publish.split("- name: Restore verified APKs only after all gates", 1)[1].split("- name:", 1)[0]
        self.assertIn(gate, restore)
        self.assertIn("architecture_g0_stage.py unpack", restore)
        names = ("apexview-custom-debug-apk", "pitwall-benchmark-target-apk",
                 "pitwall-macrobenchmark-instrumentation", "apexview-hevc-single-decoder-instrumentation")
        def uploads(job, artifact):
            return [step for step in job.split("      - name: ")
                    if "uses: actions/upload-artifact@" in step
                    and "name: " + artifact + "\n" in step]
        for name in names:
            legacy_steps = uploads(build, name)
            self.assertEqual(len(legacy_steps), 1)
            self.assertIn(legacy + " && success()", legacy_steps[0])
            steps = uploads(publish, name)
            self.assertEqual(len(steps), 1)
            self.assertIn(gate, steps[0])
            self.assertFalse(uploads(matrix, name))
        stage = build.split("- name: Upload encrypted internal test bundle", 1)[1].split("- name:", 1)[0]
        self.assertIn("g0-stage.gpg", stage)
        self.assertNotIn(".apk", stage)
        instrumentation = contents("scripts/run_rendernode_instrumentation.sh")
        self.assertIn("app.apexview.tiled.ArchiveSegmentDeliveryAndroidTest", instrumentation)
        self.assertIn("'OK (2 tests)'", instrumentation)
        self.assertIn("ArchitectureG0AndroidTest", instrumentation)
        self.assertIn("'OK (4 tests)'", instrumentation)


    def test_batch2_decoder_producer_detaches_before_texture_consumer_replacement(self):
        # Real production callsites, not standalone sample/test-only policy.
        for path, created, destroyed in (
            ("app/src/main/java/app/apexview/scene/SingleDecoderSceneView.kt",
             "override fun onSurfaceTextureAvailable", "override fun onSurfaceTextureDestroyed"),
            ("app/src/main/java/app/apexview/scene/ProtectedDecoderSceneView.kt",
             "override fun surfaceCreated", "override fun surfaceDestroyed"),
        ):
            source = contents(path)
            self.assertIn("owner.clearVideoSurface(old)", source)
            self.assertIn("SCENE_CODEC_INPUT_ATTACH", source)
            self.assertIn("SCENE_CODEC_INPUT_DETACH", source)
            self.assertIn("SCENE_CODEC_INPUT_RELEASE", source)
            self.assertIn("bufferQueueStage=", source)
            self.assertIn("physicalQueueDepth=NOT_MEASURED", source)
            creation = source.split(created, 1)[1].split("override fun", 1)[0]
            self.assertLess(creation.index('detachDecoderInput("output-replaced")'),
                            creation.index("val lifetime = surfaceLifetime.advance()"))
            self.assertIn("detachDecoderInput(", source.split(destroyed, 1)[1])
            self.assertIn("SceneFrameRatePolicy.composedOutputHint(", source)
        stage = contents("app/src/main/java/app/apexview/scene/SceneBufferQueueEvidence.kt")
        self.assertIn("mDequeueTimeout=0", stage)
        self.assertNotIn("NETWORK_STARVATION", stage)
        cadence = contents("app/src/main/java/app/apexview/scene/SceneFrameCadenceGate.kt")
        self.assertIn("SceneFrameRatePolicy.composedOutputHint", cadence)



    def test_batch2_strict_codec_surface_lease_and_adaptive_output_are_production_wired(self):
        for path in (
            "app/src/main/java/app/apexview/scene/SingleDecoderSceneView.kt",
            "app/src/main/java/app/apexview/scene/ProtectedDecoderSceneView.kt",
        ):
            source = contents(path)
            self.assertIn("attachedCodecPlayer: ExoPlayer?", source)
            self.assertIn("attachedCodecSurface: Surface?", source)
            self.assertIn("inputGeneration = lifetime", source)
            self.assertIn("attachDecoderInput(it, inputGeneration)", source)
            self.assertIn("!surfaceLifetime.matches(epoch)", source)
            self.assertIn("!surface.isValid", source)
            self.assertIn("thermalForOutput()", source)
            self.assertIn("outputPower?.currentThermalStatus", source)
            self.assertIn("SceneFrameRatePolicy.composedOutputHint(", source)
        video = contents("app/src/main/java/app/apexview/FrameThinning.kt")
        player = contents("app/src/main/java/app/apexview/FeedPlayer.kt")
        policy = contents("app/src/main/java/app/apexview/AdaptivePlaybackCadence.kt")
        self.assertIn("outputCadence.outputFps(thinner.estimatedSourceFps(), playbackSpeed)", video)
        self.assertIn("AdaptivePlaybackCadenceObserver(context)", video)
        self.assertIn("ThinningRenderersFactory(context, outputCadence)", player)
        self.assertIn("outputCadence.composedSingle = true", player)
        self.assertIn("outputCadence.composedSingle = false", player)
        self.assertIn("composedSingle && pipelineHealthy && canDisplayAll && thermalStatus in 0..1", policy)
        self.assertIn("if (speed <= 1.15f) return media.toFloat()", policy)
        # Codec/DRM producer must be released before wiping the CMAF source it
        # might still be reading; no DRM bypass or per-tile extra player.
        release = player.split("    fun release() {", 1)[1].split("    private fun explain(", 1)[0]
        self.assertLess(release.index("player.release()"), release.index("archive?.close()"))
        archive = contents("app/src/main/java/app/apexview/tiled/HevcArchiveVodSession.kt")
        self.assertIn("private val uniformAtlasPlan:", archive)
        self.assertIn("private val uniformCells:", archive)
        self.assertIn("val mixedInputs = sourceTiles", archive)
        self.assertIn("val cells = mixedAtlas?.cells ?: uniformCells", archive)


    def test_batch12_hardening_media3_source_ownership_is_transactional(self):
        player = contents("app/src/main/java/app/apexview/FeedPlayer.kt")
        protected = player.split("    internal suspend fun startProtectedComposedPlayback(", 1)[1].split(
            "    /** One static composed video track", 1)[0]
        self.assertIn("val previous = protectedLiveDashSource", protected)
        self.assertIn("if (protectedLiveDashSource === nextSource) protectedLiveDashSource = previous", protected)
        self.assertIn("if (!handoffInstalled)", protected)
        self.assertLess(protected.index("player.prepare()"), protected.index("previous?.release()"))
        clear = player.split("    internal fun startArchiveVodPlayback(", 1)[1].split(
            "    internal fun selectArchiveAudio", 1)[0]
        self.assertIn("val previousArchive = archiveVodSession", clear)
        self.assertIn("if (archiveVodSession === session) {", clear)
        self.assertIn("archiveVodSession = previousArchive", clear)
        self.assertLess(clear.index("player.prepare()"), clear.index("previousArchive?.close()"))
        restore = player.split("    fun stopProtectedComposedPlayback(", 1)[1].split(
            "    /** Clears only the source-tile video lock", 1)[0]
        self.assertIn("val priorProtected = protectedLiveDashSource", restore)
        self.assertIn("val priorArchive = archiveVodSession", restore)
        self.assertLess(restore.index("player.setMediaItem("), restore.index("priorProtected?.release()"))
        self.assertLess(restore.index("player.prepare()"), restore.index("priorArchive?.close()"))
        release = player.split("    fun release() {", 1)[1].split(
            "    private fun explain(", 1)[0]
        self.assertLess(release.index("player.release()"), release.index("archive?.close()"))
        self.assertLess(release.index("player.release()"), release.index("protectedSource?.release()"))

    def test_batch12_hardening_decoder_follows_real_scene_display_not_default_display(self):
        player = contents("app/src/main/java/app/apexview/FeedPlayer.kt")
        observer = contents("app/src/main/java/app/apexview/AdaptivePlaybackCadence.kt")
        viewer = contents("app/src/main/java/app/apexview/Viewer.kt")
        self.assertIn("@Volatile var activeDisplayId: Int = -1", observer)
        self.assertIn("displayManager.getDisplay(requestedDisplay)?.refreshRate", observer)
        self.assertNotIn("Display.DEFAULT_DISPLAY", observer)
        self.assertIn("setComposedOutputDisplay(owner: Any, displayId: Int?)", player)
        self.assertIn("if (outputDisplayOwner !== owner) return", player)
        self.assertIn("outputCadence.activeDisplayId = -1", player)
        self.assertGreaterEqual(viewer.count(
            "onDisplaySelected = { displayId -> host.setComposedOutputDisplay(this, displayId) }"), 2)
        for scene in (
            "app/src/main/java/app/apexview/scene/SingleDecoderSceneView.kt",
            "app/src/main/java/app/apexview/scene/ProtectedDecoderSceneView.kt",
        ):
            source = contents(scene)
            self.assertIn("var onDisplaySelected: (Int?) -> Unit", source)
            self.assertIn("onDisplaySelected(null)", source)
            self.assertIn("onDisplaySelected(", source)

    def test_batch12_archive_datasource_rejects_malformed_ranges_without_overflow(self):
        source = contents("app/src/main/java/app/apexview/tiled/HevcArchiveVodSession.kt")
        self.assertIn("spec.length < 0L && spec.length != C.LENGTH_UNSET.toLong()", source)
        self.assertIn("val available = body.size.toLong() - spec.position", source)
        self.assertIn("offset > buffer.size - length", source)
        self.assertIn("if (cursor >= end) return C.RESULT_END_OF_INPUT", source)

    def test_batch12_hardening_all_error_surface_messages_redact_tokens(self):
        feed = contents("app/src/main/java/app/apexview/FeedPlayer.kt")
        app = contents("app/src/main/java/app/apexview/AppState.kt")
        self.assertIn("return DiagnosticRedaction.redact(", feed)
        self.assertIn("Composed source initialization failed: ${DiagnosticRedaction.redact(", app)
        self.assertIn("terminalReason = DiagnosticRedaction.redact(", app)
        self.assertIn("castStatus = DiagnosticRedaction.redact(", app)

    def test_post833_cast_leases_caption_source_and_control_ownership(self):
        base = "app/src/main/java/app/apexview/"
        cast = contents(base + "cast/ComposedCmafRelay.kt")
        archive = contents(base + "cast/SceneCastRelay.kt")
        native = contents(base + "cast/NativeComposedCastSession.kt")
        receiver = contents(base + "cast/local/PitwallReceiverActivity.kt")
        sender = contents(base + "AppState.kt")
        capture = contents(base + "tiled/F1HlsFeedCapture.kt")
        player = contents(base + "FeedPlayer.kt")
        self.assertIn("RelayResourceStore(", cast)
        self.assertIn("RelayHttpRange.parse(", cast)
        self.assertIn("session.segmentLease(", archive)
        self.assertIn("lease.writeTo(output", archive)
        self.assertIn("takeOwnership = true", native)
        self.assertIn("if (!windowTransferred) window.zero()", native)
        self.assertIn("ReceiverMediaPolicy.validateResource(spec.uri.toString()", receiver)
        self.assertIn("CaptionControlProtocol.matchesLoad(", receiver)
        self.assertIn("CastSessionCoordinator()", sender)
        self.assertIn("archiveCaptionSource(", capture)
        self.assertIn("setOf(C.TRACK_TYPE_TEXT)", player)
        self.assertIn("ClearCaptionPlaylist.rewrite(", archive)
        self.assertIn("ClearCaptionPlaylist.isWebVtt(bytes)", archive)

    def test_post834_protected_payload_written_directly_to_final_fragment(self):
        base = "app/src/main/java/app/apexview/tiled/"
        live = contents(base + "ProtectedHevcLiveComposition.kt")
        picture = contents(base + "ProtectedHevcPictureComposer.kt")
        rewriter = contents(base + "ProtectedHevcSliceHeaderRewriter.kt")
        writer = contents(base + "ProtectedHevcCmafFragmentWriter.kt")
        self.assertIn("ProtectedHevcPictureComposer.plan(", live)
        self.assertIn("plannedPayload = composed.payload", live)
        self.assertIn("plannedPictures.forEach { it.close() }", live)
        self.assertIn("ProtectedHevcSampleComposer.planBorrowed(", picture)
        self.assertIn("rewriteInspectedProtectedParts(", rewriter)
        self.assertIn("parts.verifyCopiedTail(output, offset)", rewriter)
        self.assertIn("sample.copyInto(result, cursor)", writer)
        self.assertIn("else sample.writeTo(output)", writer)
        self.assertIn("NativeFragmentLease.build(totalFragmentBytes.toInt(), nativeContext)", writer)
        self.assertIn("writeBytes(output, null)", writer)
        self.assertIn("validate(readable)", writer)
        self.assertIn("plannedPayload", writer)
        self.assertIn("encryption.subsampleRanges == sample.subsampleRanges", writer)
        self.assertNotIn("sample.copyOfRange(mapped.nalOffset, sourceNalEnd)", rewriter)

    def test_gb_rolling_clear_dispatch_uses_existing_validated_native_engine(self):
        base = "app/src/main/java/app/apexview/tiled/"
        rolling = contents(base + "ClearCmafArchiveComposition.kt")
        capture = contents(base + "F1HlsFeedCapture.kt")
        archive = contents(base + "F1ArchiveFeedCapture.kt")
        app = contents("app/src/main/java/app/apexview/AppState.kt")
        self.assertIn("nativeClear.compose(validated", rolling)
        self.assertIn("nativeClear.publish(produced.nativeEpoch)", rolling)
        self.assertIn("independentlyDecodable && previous == 0", rolling)
        self.assertIn("previous > 0", rolling)
        self.assertIn("windowCount in 1..NativeClearArchiveJob.MAX_WINDOW", rolling)
        self.assertIn("nativeClear.invalidateForSeek()", rolling)
        self.assertIn("nativeClear.close()", rolling)
        self.assertIn("CLEAR_WINDOW_UNANCHORED_OR_OUTSIDE_NATIVE_BOUND", rolling)
        bridge = contents(base + "NativeClearArchiveBridge.kt")
        worker = contents("native/architecture-g/src/atlas_job.rs")
        atlas = contents("native/architecture-g/src/clear_atlas.rs")
        self.assertIn("NativeClearArchiveJob.encodeParts", bridge)
        self.assertIn("val parts = encodeParts(", contents(base + "NativeClearArchiveJob.kt"))
        self.assertIn("NativeClearArchiveJob.executeWindow", bridge)
        self.assertIn('b"GBC2"', worker)
        self.assertIn("previous_end = Some(end)", worker)
        self.assertIn("previous_end.is_none() && index == 0", atlas)
        self.assertIn("clearComposition.compose(", capture)
        self.assertIn("nativeRequested = nativeRequested", capture)
        self.assertIn("nativeRequested = nativeRequested", archive)
        self.assertIn("nativeContext = context, nativeRequested = { settings.nativeRustStitching", app)

    def test_gc_compact_atlas_and_cast_native_ownership(self):
        base = "app/src/main/java/app/apexview/tiled/"
        cast = "app/src/main/java/app/apexview/cast/"
        java = contents(base + "NativeStitchingEngine.java")
        jni = contents("native/architecture-g/src/jni_bridge.rs")
        engine = contents("native/architecture-g/src/engine.rs")
        job = contents(base + "NativeClearArchiveJob.kt")
        for name in ("nativeOpenAtlas", "nativeSubmitAtlasParts", "nativeReadAtlasArray", "nativeFragmentAllocateBounded"):
            self.assertIn(name, java)
            self.assertIn(name, jni)
            self.assertIn(name, contents("scripts/verify_architecture_g0_apk.sh"))
        self.assertIn("reserved_atlas", engine)
        self.assertIn("get_byte_array_region", jni)
        self.assertIn("set_byte_array_region", jni)
        self.assertIn("parts.add(inits[i])", job)
        self.assertIn("submitClearAtlasParts", job)
        live = contents(base + "ProtectedHevcLiveComposition.kt")
        self.assertIn("updateCastNativeHeaderPreference", live)
        self.assertIn("session.lastNativePublication", live)
        self.assertIn("shareNative = true", contents(cast + "NativeComposedCastSession.kt"))
        self.assertIn("video.nativeResources", contents(cast + "CastDashPublication.kt"))
        self.assertIn("lease.storageIdentity", contents(cast + "RelayResourceStore.kt"))
        self.assertIn("32 * 1024", contents(base + "NativeFragmentLease.kt"))
        session = contents(base + "HevcArchiveVodSession.kt")
        self.assertIn("nativeJobTimeoutMs", session)
        self.assertIn("memoryWarmLimit", session)

    def test_gc_dashed_capture_slab_and_measured_bandwidth_are_wired(self):
        base = "app/src/main/java/app/apexview/"
        tiled = base + "tiled/"
        rust = "native/architecture-g/src/"
        dash = contents(tiled + "F1DashFeedCapture.kt")
        reader = contents(tiled + "CmafHttpReader.kt")
        app = contents(base + "AppState.kt")
        player = contents(base + "FeedPlayer.kt")
        archive = contents(tiled + "F1ArchiveFeedCapture.kt")
        hls = contents(tiled + "F1HlsFeedCapture.kt")
        planner = contents(tiled + "HevcArchiveRenditionPlan.kt")
        self.assertIn("reader.read(dataSpec, maximumBytes, verifyEnd = true)", dash)
        self.assertNotIn("ByteArrayOutputStream(", dash)
        self.assertIn("val size = source.open(spec)", reader)
        self.assertIn("source.read(probe, 0, 1)", reader)
        self.assertIn("exact.fill(0)", reader)
        self.assertIn("compositionBandwidthMeter.addEventListener(", app)
        self.assertIn("compositionBandwidthMeter.removeEventListener(compositionBandwidthListener)", app)
        self.assertIn("network != compositionBandwidthNetwork", app)
        self.assertIn("bytes >= 64L * 1024", app)
        self.assertIn("compositionBandwidthBudget(castTiles.map(::qualityFor))", app)
        self.assertIn("DefaultBandwidthMeter.getSingletonInstance(context)", player)
        self.assertIn("autoBandwidthBps = autoBandwidthBps", archive)
        self.assertIn("aggregateBitrates", dash)
        self.assertIn("aggregateBitrates", hls)
        self.assertIn("sizes.all { (bandwidthCosts[it] ?: 0L) > 0L }", planner)
        atlas = contents(rust + "clear_atlas.rs")
        dispatch = contents(rust + "atlas_job.rs")
        self.assertIn("rewrite_tile_into_slab", atlas)
        self.assertIn("write_slab_nal_parts", atlas)
        self.assertIn("header_slab.clear()", atlas)
        self.assertIn("&mut self.header_slab", dispatch)
        self.assertIn("self.header_slab = Vec::new()", dispatch)
        self.assertIn('NativeTrace::begin(c"PitwallNativeAtlas")', contents(rust + "engine.rs"))
        self.assertIn('NativeTrace::begin(c"PitwallNativePictures")', atlas)

    def test_gc_reuse_and_auto_preparation_are_wired(self):
        base = "app/src/main/java/app/apexview/"
        tiled = base + "tiled/"
        app = contents(base + "AppState.kt")
        quality = contents(base + "PerFeedQualityPolicy.kt")
        archive = contents(tiled + "F1ArchiveFeedCapture.kt")
        dash = contents(tiled + "F1DashFeedCapture.kt")
        hls = contents(tiled + "F1HlsFeedCapture.kt")
        atlas = contents("native/architecture-g/src/clear_atlas.rs")
        self.assertIn("qualities.any { it != VideoQuality.AUTO }", quality)
        self.assertIn('autoCompositionHeight("FINITE_CLEAR", selected, autoBaseline)', app)
        self.assertIn('autoCompositionHeight("ROLLING_LOCAL", selectedTiles, maxHeight)', app)
        self.assertIn('autoCompositionHeight("LIVE_LOCAL",', app)
        self.assertIn("autoPreferredHeight = autoPreferredHeight", archive)
        self.assertIn("preferred ?: maximumVideoHeight", dash)
        self.assertIn("autoPreferredHeight?.coerceIn(1, maximumHeight)", hls)
        self.assertIn("source.slices_into(incoming, slices)", atlas)
        self.assertIn("slices.clear();", atlas)
        self.assertIn("picture_parts.chunks_exact(self.placements.len())", atlas)
        self.assertIn("pictures.checked_mul(self.placements.len())", atlas)
        self.assertNotIn("picture_parts.push(nals)", atlas)
        bridge = contents(tiled + "NativeClearArchiveBridge.kt")
        job = contents(tiled + "NativeClearArchiveJob.kt")
        for metric in ("submitUs=", "waitUs=", "readUs=", "validateUs="):
            self.assertIn(metric, bridge)
        self.assertIn("readyAt - started", job)

    def test_gb_native_profile_and_protected_chunk_admission_remain_wired(self):
        base = "app/src/main/java/app/apexview/tiled/"
        bridge = contents(base + "NativeClearArchiveBridge.kt")
        job = contents(base + "NativeClearArchiveJob.kt")
        native = contents("native/architecture-g/src/clear_atlas.rs")
        worker = contents("native/architecture-g/src/atlas_job.rs")
        parser = contents("native/architecture-g/src/cmaf_input.rs")
        protected = contents(base + "NativeProtectedPlan.kt")
        self.assertIn("loopAcrossTiles = pps.loopFilterAcrossTiles == true", bridge)
        self.assertIn("if (geometry.loopAcrossTiles) 0x33", job)
        self.assertIn('b"GBC3"', worker)
        self.assertIn("filter != can_gate_filter", worker)
        self.assertIn("loop_across_tiles && !own_seams", native)
        self.assertIn("coding_key()? != key", native)
        self.assertIn("slice.prediction != first_slice.1.prediction", native)
        self.assertIn("mdat.payload.get(start..).ok_or(Error::Extent)?", parser)
        self.assertIn("payload.finish()?", parser)
        self.assertIn("batchChunks(sources, outputSets, inputs, maxBatchSlices)", protected)
        self.assertIn("candidate(3, { encodeBatch(sources, outputSets, chunk) }", protected)
        self.assertIn("require(prepared.size == inputs.size)", protected)
        self.assertIn("if (!transferred) prepared.forEach { it.prefix.fill(0) }", protected)

    def test_gb_atlas_admission_uses_headroom_and_bounded_capacity_retry(self):
        base = "app/src/main/java/app/apexview/tiled/"
        rust = "native/architecture-g/src/"
        java = contents(base + "NativeStitchingEngine.java")
        bridge = contents(base + "NativeClearArchiveBridge.kt")
        session = contents(base + "HevcArchiveVodSession.kt")
        job = contents(base + "NativeClearArchiveJob.kt")
        engine = contents(rust + "engine.rs")
        jni = contents(rust + "jni_bridge.rs")
        self.assertIn("long ceiling=surplus/8", java)
        self.assertIn("Math.min(MAX_ATLAS_BUDGET,ceiling-javaTransient)", java)
        self.assertIn("javaTransient>jvmHeadroom", java)
        self.assertIn("b.reservedBytes+atlasWorkspaceBytes(input,output)<=b.ceilingBytes", java)
        self.assertIn("if (h.lowMemory) null", bridge)
        self.assertIn("GB_CLEAR_MEMORY_ADMISSION", bridge)
        self.assertIn("catch (_: NativeClearArchiveJob.OutputCapacityException)", bridge)
        self.assertIn("require(required > priorCapacity)", bridge)
        self.assertIn("val remaining = timeoutMs -", bridge)
        self.assertIn("actualStitcher=", session)
        self.assertIn("stitcherReason=", session)
        self.assertIn("NativeStitchingEngine.OUTPUT_CAPACITY", job)
        self.assertIn("crate::bmff::Error::Capacity { OUTPUT_CAPACITY }", engine)
        self.assertIn("length, crate::bmff::MAX_ATLAS_BYTES", jni)

    def test_gb_semantic_parameter_equivalence_and_protected_output_setup(self):
        base = "app/src/main/java/app/apexview/tiled/"
        rust = "native/architecture-g/src/"
        atlas = contents(rust + "clear_atlas.rs")
        keys = contents(rust + "hevc_output.rs")
        params = contents(rust + "hevc_parameters.rs")
        cmaf = contents(rust + "cmaf_input.rs")
        poc = contents(rust + "hevc_poc.rs")
        plan = contents(rust + "protected_plan.rs")
        jni = contents(rust + "jni_bridge.rs")
        adapter = contents(base + "NativeProtectedPlan.kt")
        production = contents(base + "ProtectedHevcLiveComposition.kt")
        self.assertIn("parameter_identity(sets, &candidate, false)? != self.parameter_identity", atlas)
        self.assertIn("short: metadata.short.clone()", keys)
        self.assertIn("ordering: prefix.ordering", keys)
        self.assertIn("sps.finish_rbsp()?", params)
        self.assertIn('optional(b"mfhd")?', cmaf)
        self.assertIn("take_end_of_sequence", atlas)
        self.assertIn("take_end_of_sequence", poc)
        self.assertIn('b"GBO1"', plan)
        self.assertIn("4 => crate::protected_plan::output_parameters(&input)", jni)
        self.assertIn("response = generate(packet, 4)", adapter)
        self.assertIn("require(nal.contentEquals(ref))", adapter)
        self.assertIn("NativeProtectedPlan.matchingOutputParameters(", production)
        self.assertIn("session.nativeParameterEpoch == nativeEpoch", production)
        self.assertIn("listOf(nativeParameters.vps, nativeParameters.sps, nativeParameters.pps)", production)

    def test_gb_frame_rate_reduction_is_api29_compatible_and_checked(self):
        base = "app/src/main/java/app/apexview/tiled/"
        rolling = contents(base + "ClearCmafArchiveComposition.kt")
        protected = contents(base + "ProtectedHevcLiveComposition.kt")
        helper = contents(base + "HevcFrameRate.kt")
        self.assertIn("reducePositiveHevcFrameRate(timescale, sampleDuration)", rolling)
        self.assertIn("reducePositiveHevcFrameRate(numerator, denominator)", protected)
        self.assertNotIn(".longValueExact()", rolling)
        self.assertNotIn(".intValueExact()", protected)
        self.assertIn("Math.toIntExact(numerator / a)", helper)
        self.assertIn("Math.toIntExact(denominator / a)", helper)
        self.assertIn("require(numerator > 0L && denominator > 0L)", helper)
        archive = contents(base + "HevcArchiveVodSession.kt")
        self.assertIn("reducePositiveHevcFrameRate(trackScale, step)", archive)
        self.assertNotIn(".longValueExact()", archive)
        self.assertIn("scaleHevcMicrosToTicks(us, scale)", archive)
        self.assertIn("java.math.BigInteger.valueOf(Long.MIN_VALUE)", helper)
        self.assertIn("java.math.BigInteger.valueOf(Long.MAX_VALUE)", helper)
        composer = contents(base + "HevcClearCmafGridComposer.kt")
        init = contents(base + "HevcClearCmafInitSegmentWriter.kt")
        self.assertIn("nalLengthSize = first.config.nalLengthSize", composer)
        self.assertIn("writeHevcConfiguration(output, sps, request.nalLengthSize)", init)

    def test_post834_pitwall_control_and_media_use_paired_tls(self):
        base = "app/src/main/java/app/apexview/"
        sender = contents(base + "AppState.kt")
        receiver = contents(base + "cast/local/PitwallReceiverActivity.kt")
        tls = contents(base + "cast/local/PitwallTls.kt")
        relay = contents(base + "cast/ComposedCmafRelay.kt")
        archive = contents(base + "cast/SceneCastRelay.kt")
        self.assertIn("PitwallTls.connect(", sender)
        self.assertIn("decision.await()", sender)
        self.assertIn("PitwallTls.controlServer(", receiver)
        self.assertIn("PitwallTls.accept(identity, socket)", receiver)
        self.assertIn("sslSocketFactory(tls.socketFactory, tls.trustManager)", receiver)
        self.assertIn('origin.scheme == "https"', receiver)
        self.assertIn("needClientAuth = true", tls)
        self.assertIn("MessageDigest.isEqual(pinned.encoded, cert.encoded)", tls)
        self.assertIn("tls?.mediaServer(", relay)
        self.assertIn("tls?.mediaServer(", archive)
        self.assertIn("castResources.releaseMedia()", sender)
        self.assertIn("castResources.retainedCompositionGroups()", sender)

    def test_post834_native_audio_and_declared_text_use_actual_receiver_tracks(self):
        base = "app/src/main/java/app/apexview/"
        native = contents(base + "cast/NativeComposedCastSession.kt")
        audio = contents(base + "cast/CmafCastAudio.kt")
        text = contents(base + "cast/CmafCastText.kt")
        receiver = contents(base + "cast/local/PitwallReceiverActivity.kt")
        self.assertIn("alternate.compatibleWith(audio)", native)
        self.assertIn("track.appendTo(publication", native)
        self.assertIn("textTracks.values.forEach { it.appendTo(publication) }", native)
        self.assertIn("acceptPssh(", audio)
        self.assertIn("it.type == C.TRACK_TYPE_TEXT", text)
        self.assertIn("!f.sampleEncryptionBoxPresent", text)
        self.assertIn("group.isTrackSelected(index)", receiver)
        self.assertIn("NativeAudioControl.acknowledgment(playbackId(), requested, selected)", receiver)

    def test_post835_timing_capture_and_tls_regressions(self):
        base = "app/src/main/java/app/apexview/"
        state = contents(base + "AppState.kt")
        worker = contents(base + "scene/BakedCastCaptureWorker.kt")
        timing = contents(base + "TimingWorkCoordinator.kt")
        audio = contents(base + "cast/CmafCastAudio.kt")
        self.assertIn("timingWork.load(", state)
        self.assertIn("val owner = begin(contentId)", timing)
        self.assertIn("timingWork.owns(timingOwner)", state)
        self.assertIn("timingWork.loadSpeeds(", state)
        self.assertIn("if (completeSpeed(request, loaded)) publish(loaded)", timing)
        self.assertIn("playbackWork.cancelControlSeek()", state)
        self.assertIn("current === owner", timing)
        self.assertIn("!captureLifetime.stop()", worker)
        self.assertIn("captureLifetime.complete()", worker)
        self.assertIn("init = budget.own(bytes)", audio)
        self.assertIn("report = inspected; initKey = key", audio)
        self.assertNotIn("FLAG_SECURE", contents(base + "MainActivity.kt"))
        self.assertIn("if (protectedOutput) setSecure(true)", contents(base + "scene/ProtectedDecoderSceneView.kt"))
        self.assertIn('jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")', contents("app/build.gradle.kts"))

    def test_programme_captions_and_buffer_ownership_reach_actual_playback(self):
        base = "app/src/main/java/app/apexview/"
        sender = contents(base + "AppState.kt")
        receiver = contents(base + "cast/local/PitwallReceiverActivity.kt")
        capture = contents(base + "tiled/F1HlsFeedCapture.kt")
        player = contents(base + "FeedPlayer.kt")
        native = contents(base + "cast/NativeComposedCastSession.kt")
        relay = contents(base + "cast/RelayResourceStore.kt")
        viewer = contents(base + "Viewer.kt")
        self.assertIn("captionSources = captionSources", capture)
        self.assertIn("archiveCaptionChildren[archiveProgrammeLabel]", player)
        self.assertIn("MergingMediaSource(true, video, *children.toTypedArray())", player)
        self.assertIn("CaptionPolicy.castProgramme(group.getTrackFormat(index).id) == programme", receiver)
        self.assertIn("archiveProgrammeLabel = label", player)
        self.assertIn("captionOutputReady", viewer)
        self.assertIn("CaptionAppearanceControls(", viewer)
        self.assertIn("CaptionAppearanceControls(", receiver)
        self.assertIn("settings.captionAppearance).apply", sender)
        self.assertIn("capture.hlsProgrammes", native)
        self.assertIn("RollingWebVtt.cues(", contents(base + "cast/HlsCastText.kt"))
        self.assertIn("acquired.forEach { it.release() }", relay)
        self.assertIn("owner.previousCleanup?.await()", contents(base + "PlaybackWorkCoordinator.kt"))
        self.assertNotIn("playbackWork.capture?.reset()", sender)

    def test_baked_cast_encodes_the_live_clear_viewer_layers_on_the_media_clock(self):
        base = "app/src/main/java/app/apexview/"
        viewer = contents(base + "Viewer.kt")
        scene = contents(base + "scene/SingleDecoderSceneView.kt")
        worker = contents(base + "scene/BakedCastCaptureWorker.kt")
        gpu = contents(base + "scene/BakedCastGpuCapture.kt")
        self.assertIn(".recordBakedCastOverlay(captureOverlay)", viewer)
        self.assertIn("if (!captionsInViewerOverlay) ViewerComposedCaptions(app)", viewer)
        self.assertIn("host.archiveSceneSession === archive && app.bakedCastSession === bakedSession", viewer)
        self.assertIn("::drawBakedCastVideo, ::detachBakedCastVideo", scene)
        self.assertIn("direct.draw(placement)", worker)
        self.assertIn("mediaClock.timestampNs(position)", worker)
        self.assertIn("WINDOW_PIXEL_COPY", worker)
        self.assertIn("check(!closed && pending == null)", gpu)
        overlay = contents(base + "scene/BakedCastOverlay.kt")
        self.assertIn("scope.drawContent()", overlay)
        self.assertIn("display.nativeCanvas.drawRenderNode(content)", overlay)
        self.assertIn("surface.lockHardwareCanvas()", overlay)
        self.assertIn("canvas.drawRenderNode(content)", overlay)
        self.assertIn("surface.unlockCanvasAndPost(canvas)", overlay)
        self.assertIn("overlay.submit(targetSurface, width, height)", gpu)
        self.assertNotIn("BakedCastGpuCapture", contents(base + "scene/ProtectedDecoderSceneView.kt"))
        self.assertIn("BakedCastCaptureAndroidTest", contents("scripts/run_rendernode_instrumentation.sh"))

if __name__ == "__main__":
    unittest.main()
