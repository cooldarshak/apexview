"""Source binding smoke checks. They complement Kotlin tests, not device validation."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/app/apexview"

def source(relative):
    return (APP / relative).read_text(encoding="utf-8")

class LiveOverlayWiringTest(unittest.TestCase):
    def test_live_tower_uses_cached_sampler_and_native_rows(self):
        laptop = source("Laptop.kt")
        self.assertIn("var sampler = OverlayTowerSampler(timing", laptop)
        self.assertIn("rememberUpdatedState(timing)", laptop)
        self.assertIn("if (!sampler.updateSource(source))", laptop)
        self.assertIn("sampler.sample(now, speeds)", laptop)
        self.assertRegex(laptop, r"TimingTowerContents\(timing, visible\.rows")
        self.assertIn("TvTowerRow(timing.drivers[number]", laptop)
        row = source("TvTowerRow.kt")
        self.assertIn("remember(number, driver?.tla, state, kmh, gear, speedUnit, sectorsForDraw)", row)
        self.assertIn("remember(state.sectors, state.lapSectors)", row)
        self.assertNotIn("remember(number, driver?.tla, state, tyre, kmh, gear, speedUnit)", row)

    def test_map_animation_is_draw_phase_and_flags_are_not_recorded_every_frame(self):
        track = source("TrackMap.kt")
        self.assertEqual(track.count("rememberOverlayFlagPulse(flags.track.ending)"), 2)
        self.assertEqual(track.count("val alpha = pulse.value"), 2)
        self.assertNotIn("rememberInfiniteTransition(label = \"flags\")", track)
        self.assertNotIn("pulse, caption, stop?.number", track)
        self.assertIn('OverlayPerformanceProbe("track-flag-pulse-draw")', track)
        self.assertIn('OverlayPerformanceProbe("edge-flag-pulse-draw")', track)
        # Both real map variants now use the allocation-bounded draw-only cursor.
        # UI hit targets and Cast sender must continue publishing immutable snapshots.
        self.assertEqual(track.count("OverlayMotionSource(drawClock, drawSampler::sampleForDraw)"), 2)
        self.assertEqual(track.count("it.carsAt(it.positionMs.value)"), 2)
        motion = source("OverlayRetainedDrawing.kt")
        self.assertIn("val carsAt: (Long) -> List<OverlayMotionCar>", motion)
        self.assertIn("sampler::sample", source("cast/CastTimingLayer.kt"))
        receiver = source("cast/ReceiverMotionSampler.kt")
        self.assertIn("NativeHotPath.interpolateMotion", receiver)
        self.assertIn("source.writeAt(steps,target)", receiver)
        self.assertIn("return cars", receiver)
        self.assertIn("internal interface OverlayMotionCar", track)
        sampler = source("OverlayPositionSampler.kt")
        self.assertIn("fun sampleForDraw(atMs: Long)", sampler)
        self.assertIn("private val drawCars = ArrayList<OverlayDrawCar>(entries.size)", sampler)
        self.assertIn("fun sample(atMs: Long): List<Car>", sampler)
        self.assertIn("updateForDraw(movingCars, timing.geometry)", track)

    def test_real_clear_protected_and_cast_frame_attribution_with_bounded_draw_ribbon(self):
        timestamp = source("scene/SceneCodecTimestampCorrelation.kt")
        self.assertIn("earlyLatchPts = LongArray(CAPACITY)", timestamp)
        self.assertIn("lateMatchedLatches: Long", timestamp)
        self.assertIn("outstandingLatches: Int", timestamp)
        for scene in ("SingleDecoderSceneView.kt", "ProtectedDecoderSceneView.kt"):
            actual = source("scene/" + scene)
            self.assertIn("codecCorrelation?.onTextureLatched(", actual)
            self.assertIn("codecCorrelation?.onSwapSubmitted(", actual)
            self.assertIn("codecPtsMissingAtLatch=", actual)
            self.assertIn("codecPtsLateMatched=", actual)
            self.assertIn("codecPtsOutstanding=", actual)
        track = source("TrackMap.kt")
        self.assertNotIn("private fun at(f: Float): Pair<Offset, Offset>", track)
        self.assertIn("private fun cornerPoint(", track)
        self.assertIn("private fun cornerInward(", track)
        self.assertIn("movingProjection.updateForDraw(movingCars, timing.geometry)", track)

    def test_map_draw_phase_resolves_safety_leader_once_per_static_epoch(self):
        track = source("TrackMap.kt")
        self.assertEqual(track.count("val currentSafetyLeader = remember("), 2)
        self.assertEqual(track.count("leader ?: leaderAt(timing, now)"), 2)
        self.assertEqual(track.count("val safetyLeader = currentSafetyLeader"), 2)
        self.assertIn("if (first == null || position < minPosition)", track)
        tower = source("OverlayTowerSampler.kt")
        self.assertIn("previousRowsByNumber.clear()", tower)
        self.assertNotIn("previous?.rows?.any", tower)

    def test_scene_video_demand_preserves_last_frame_on_transient_surface_loss(self):
        frame = source("scene/SceneFrameDemand.kt")
        clear = source("scene/SingleDecoderSceneView.kt")
        protected = source("scene/ProtectedDecoderSceneView.kt")
        self.assertIn("fun onDrawUnavailable(hadNewVideo: Boolean)", frame)
        for target in (clear, protected):
            self.assertIn("frameDemand.onDrawUnavailable(", target)
            self.assertIn("pacing.onDrawUnavailable()", target)
            self.assertIn("unavailableSurfaceDraws=", target)
        self.assertIn("eglMakeCurrent(display, window, window, contextGl)", protected)
        self.assertIn("eglBindNs = bindNs", protected)
        self.assertIn("EGL14.eglSwapInterval(display, 0)", protected)
        self.assertIn("physicalPresentation=NOT_MEASURED", protected)

    def test_map_and_cast_sender_are_wired_to_same_sampling_contract(self):
        track = source("TrackMap.kt")
        sender = source("cast/CastTimingPublisher.kt")
        geometry = source("LiveTiming.kt")
        self.assertIn("OverlayPositionSampler(timing", track)
        self.assertIn("OverlayPositionSampler(it, OverlayPerformanceProbe", sender)
        self.assertIn("OverlayVertexIndex(outline)", geometry)
        self.assertIn("OverlayEdgeProjection(nativeRequested)", track)
        self.assertIn("NativeCircuitProjection(timing.geometry)", track)
        self.assertIn("app.settings.nativeRustStitching", sender)
        self.assertIn("towerSampler?.updateSource(activeTowerTiming)", sender)
        self.assertEqual(track.count("app.settings.mapDriverSelection || app.pitDriver != null"), 2)

    def test_gc_native_transport_sector_and_exact_forecast(self):
        self.assertIn("NativeHotPath.transport", source("cast/BakedCastTransportStream.kt"))
        self.assertIn("NativeFragmentLease.build", source("cast/BakedCastTransportStream.kt"))
        self.assertIn("native = native", source("cast/BakedCastHlsSession.kt"))
        self.assertIn("muxer.encodedSize(samples).toLong()", source("cast/BakedCastHlsSession.kt"))
        self.assertIn("NativeHotPath.normalizeSectors", source("DriverTiming.kt"))
        self.assertIn("sectorNormalizer.sample(number, row)", source("OverlayTowerSampler.kt"))
        self.assertIn("normalizedSectors = visible.normalizedSectors", source("Laptop.kt"))

    def test_gc_expanded_native_flag_audio_and_prediction_paths(self):
        self.assertIn("NativeHotPath.flags", source("LiveTiming.kt"))
        self.assertIn("timing.flags.at(now, app.settings.nativeRustStitching)", source("TrackMap.kt"))
        self.assertIn("NativeCastPrediction", source("cast/CastTimingPublisher.kt"))
        self.assertIn("includeMetadata = false", source("cast/NativeCastPrediction.kt"))
        self.assertIn("NativeHotPath.audioMetadata", source("tiled/CmafBoxInspector.kt"))
        self.assertIn("CmafBoxInspector.nativeAudioTiming", source("cast/CmafCastAudio.kt"))
        self.assertIn("NativeHotPath.audioAdmit", source("cast/BakedCastSegmentQueue.kt"))
        self.assertNotIn("ByteArrayOutputStream", source("cast/BakedCastTransportStream.kt"))
        self.assertIn("packetCount, 188L", source("cast/BakedCastTransportStream.kt"))

    def test_cast_routing_does_not_silently_claim_google_timing_overlays(self):
        app = source("AppState.kt")
        sheet = source("cast/PitwallCastSheet.kt")
        receiver = source("cast/CastTimingLayer.kt")
        self.assertIn("CastOverlayDeliveryPolicy.choose(", app)
        self.assertIn("CastOverlayDeliveryPolicy.requiresVideoOnlyConsent(", app)
        self.assertIn("confirmGoogleVideoOnlyCast()", sheet)
        self.assertIn("TimingTowerContents(timing", receiver)
        self.assertIn("TrackDrawingContent(timing", receiver)
        self.assertIn("EdgeDrawingContent(timing", receiver)
        self.assertIn("PitCircleDrawing(timing", receiver)
        self.assertIn("window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0", source("Viewer.kt"))

    def test_protected_atlas_publishes_only_after_observable_host_is_registered(self):
        # Build 691 regression, screenshot 2026-11-22 10:55:47: all four
        # cameras appeared as one raw 1920x2176 atlas in a 165x188 tile.
        # Compose may skip the canvas subtree if the host field is not State.
        app = source("AppState.kt")
        viewer = source("Viewer.kt")
        self.assertIn("private var protectedCompositionHost by mutableStateOf<Tile?>(null)", app)
        rolling = app.split("replaceHost = { window ->", 1)[1].split(
            "restoreSources = ::restore,", 1
        )[0]
        self.assertLess(
            rolling.index("host.feed.startProtectedComposedPlayback("),
            rolling.index("showProtectedCompositionPresentation(host, selectedTiles,")
        )
        self.assertLess(
            rolling.index("showProtectedCompositionPresentation(host, selectedTiles,"),
            rolling.index("protectedRollingScene = ProtectedRollingScene(labels.toList(), scene)")
        )
        self.assertIn("check(protectedCompositionHost === host)", rolling)
        self.assertIn("F1_SCENE_UI_ACTIVATED", rolling)
        self.assertIn("F1_SCENE_UI_BOUND", viewer)
        self.assertIn("AndroidView(factory = { context ->", viewer)
        self.assertIn("ProtectedDecoderSceneView(context, protectedOutput = secureRolling)", viewer)
        # Both EGL renderers must bind the registered host, using the same
        # source atlas translated into the actual output region when eligible.
        # Keep the original protection against rendering an unbound raw atlas.
        for renderer in ("ProtectedDecoderSceneView(context, protectedOutput = secureRolling)",
                         "SingleDecoderSceneView(context)"):
            block = viewer.split(renderer, 1)[1].split("onRelease = { it.release() }", 1)[0]
            self.assertIn("it.bind(host.player, outputScene, app.playbackSpeed, host.sceneCodecCorrelation)", block)
        self.assertEqual(viewer.count(
            "it.bind(host.player, outputScene, app.playbackSpeed, host.sceneCodecCorrelation)"
        ), 2)
        self.assertIn("val outputScene = remember(scene, bounds) { bounds?.let(scene::translatedTo) ?: scene }", viewer)
        self.assertEqual(viewer.count("onRelease = { it.release() }, modifier = outputModifier)"), 2)
        self.assertIn("!windowCaptureRequired && !app.arranging && !app.inPip)", viewer)
        self.assertIn("if (bounds == null) Modifier.fillMaxSize()", viewer)
        self.assertIn("Constraints.fixed(w, h)", viewer)
        self.assertIn("child.place(bounds.left, bounds.top)", viewer)
        output = source("scene/VideoScene.kt")
        self.assertIn("copy(width = bounds.width.toFloat(), height = bounds.height.toFloat(),", output)
        self.assertIn("windows = windows.map { it.copy(x = it.x - bounds.left, y = it.y - bounds.top) }", output)
        self.assertIn("if (!allowed || windows.isEmpty()", output)
        host = source("FeedPlayer.kt")
        self.assertIn("sceneCodecCorrelation.onCodecFrame(", host)
        for renderer in ("scene/SingleDecoderSceneView.kt", "scene/ProtectedDecoderSceneView.kt"):
            scene = source(renderer)
            self.assertIn("codecCorrelation?.onTextureLatched(", scene)
            self.assertIn("codecCorrelation?.onSwapSubmitted(", scene)
            self.assertIn("codecCorrelation?.snapshotAndReset(input)", scene)
            self.assertIn("codecCorrelation?.attach(surface)", scene)
            self.assertIn("codecCorrelation?.detach(old)", scene)
            self.assertIn("codecCorrelation?.onTextureLatched(input,", scene)
            self.assertIn("codecCorrelation?.onSwapSubmitted(input,", scene)
            self.assertIn("codecExactReleaseOnly=", scene)
            self.assertIn("codecMatchSemantics=EXACT_PTS_OR_RELEASE", scene)
            self.assertNotIn("codecCorrelation?.active = false", scene)

    def test_clear_live_and_archive_all_register_host_before_atlas_publication(self):
        app = source("AppState.kt")
        clear = app.split("replaceHost = { session ->", 1)[1].split(
            "restoreSources = { restore() },", 1
        )[0]
        self.assertLess(
            clear.index("showProtectedCompositionPresentation(host, selected, sceneLayout = true)"),
            clear.index("nativeArchiveTiles = selected")
        )
        self.assertIn("check(protectedCompositionHost === host)", clear)
        self.assertIn("F1_SCENE_UI_ACTIVATED path=CLEAR_ARCHIVE", clear)
        live = app.split("private fun startTvLiveCompositionIfEligible", 1)
        # Verify the live rolling capture activation, not a dormant helper.
        live = live[-1]
        self.assertLess(
            live.index("showProtectedCompositionPresentation(host, selectedTiles, audioTile,"),
            live.index("protectedRollingScene = ProtectedRollingScene(labels.toList(), layout)")
        )
        self.assertIn("F1_SCENE_UI_ACTIVATED path=PROTECTED_LIVE", live)
        self.assertIn("F1_SCENE_UI_ACTIVATED path=PROTECTED_ARCHIVE", app)

    def test_cast_receiver_waits_for_gl_draw_and_rejects_raw_atlas(self):
        app = source("AppState.kt")
        receiver = source("cast/local/PitwallReceiverActivity.kt")
        self.assertIn("castVideoScene(relay.session)", app)
        self.assertIn("nativeCastScene(session)", app)
        self.assertIn("scene.quads() // Validate coordinates", source("cast/CastSceneProtocol.kt"))
        self.assertIn("raw-atlas fallback is disabled", receiver)
        self.assertIn("onFirstFrame = { if (this@PitwallReceiverActivity.player === current", receiver)
        self.assertIn("SCENE_RENDERING_CONFIRMED", receiver)
        self.assertIn("next.setVideoFrameMetadataListener(", receiver)
        self.assertIn("correlation.onCodecFrame(", receiver)
        self.assertEqual(receiver.count("bind(current, model, desiredSpeed, sceneCodecCorrelation)"), 4)
        self.assertIn("windows=${model?.windows?.size ?: 1}", receiver)

    def test_native_rendernode_instrumentation_is_run_in_ci(self):
        workflow = (ROOT / ".github/workflows/android-debug-apk.yml").read_text(encoding="utf-8")
        self.assertIn("reactivecircus/android-emulator-runner@v2", workflow)
        # Hosted runners may omit /dev/kvm. The emulator must still execute the
        # native test with TCG, rather than fail before launching or skip tests.
        self.assertIn("Select Android emulator acceleration", workflow)
        self.assertIn("disable-linux-hw-accel: auto", workflow)
        self.assertIn("ANDROID_I_WANT_MY_TCG=yes", workflow)
        self.assertIn("if [ -c /dev/kvm ] && [ -r /dev/kvm ] && [ -w /dev/kvm ]; then", workflow)
        self.assertNotIn('echo "::error::The Android emulator requires accessible KVM', workflow)
        self.assertIn("script: bash scripts/run_rendernode_instrumentation.sh", workflow)
        script = (ROOT / "scripts/run_rendernode_instrumentation.sh").read_text(encoding="utf-8")
        self.assertIn("OverlayRetainedRenderNodeAndroidTest", script)
        self.assertIn("am instrument", script)
        # Validate the shared mandatory runner, not the old one-off grep spelling.
        # Both real device suites must execute and return their exact passed footer.
        self.assertIn("run_required_instrumentation", script)
        self.assertIn("grep -Fq \"$expected_footer\" \"$output\"", script)
        self.assertIn("'OK (2 tests)'", script)
        self.assertIn("'OK (1 test)'", script)
        self.assertIn("INSTRUMENTATION_STATUS_CODE", script)
        self.assertIn("return 1", script)
        self.assertIn("test -s", script)

    def test_independent_latch_fault_recovers_only_through_same_protection_renderer(self):
        latch = source("scene/SceneAsyncTextureLatch.kt")
        self.assertIn("throw Fault(error)", latch)
        self.assertIn("failure?.invoke(Fault(error))", latch)
        for name in ("SingleDecoderSceneView.kt", "ProtectedDecoderSceneView.kt"):
            actual = source("scene/" + name)
            self.assertIn("error is SceneAsyncTextureLatch.Fault && asyncInput != null && !asyncLatchDisabled", actual)
            self.assertIn("if (retryLatch) asyncLatchDisabled = true", actual)
            self.assertIn("val independent = if (asyncLatchDisabled) null", actual)
            self.assertIn("SCENE_ASYNC_LATCH_RECOVERY sameProtection=true", actual)
        protected = source("scene/ProtectedDecoderSceneView.kt")
        self.assertIn("texture, protectedOutput, gl)", protected)
        self.assertIn("surfaceCreated(holder)", protected)


if __name__ == "__main__":
    unittest.main()
