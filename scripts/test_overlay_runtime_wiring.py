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
        self.assertIn("val sampler = OverlayTowerSampler(timing", laptop)
        self.assertIn("sampler.sample(now, speeds)", laptop)
        self.assertRegex(laptop, r"TimingTowerContents\(timing, visible\.rows")
        self.assertIn("TvTowerRow(timing.drivers[number]", laptop)
        row = source("TvTowerRow.kt")
        self.assertIn("remember(number, driver?.tla, state, kmh, gear, speedUnit)", row)
        self.assertNotIn("remember(number, driver?.tla, state, tyre, kmh, gear, speedUnit)", row)

    def test_map_animation_is_draw_phase_and_flags_are_not_recorded_every_frame(self):
        track = source("TrackMap.kt")
        self.assertEqual(track.count("rememberOverlayFlagPulse(flags.track.ending)"), 2)
        self.assertEqual(track.count("val alpha = pulse.value"), 2)
        self.assertNotIn("rememberInfiniteTransition(label = \"flags\")", track)
        self.assertNotIn("pulse, caption, stop?.number", track)
        self.assertIn('OverlayPerformanceProbe("track-flag-pulse-draw")', track)
        self.assertIn('OverlayPerformanceProbe("edge-flag-pulse-draw")', track)
        self.assertIn("OverlayMotionSource(drawClock, drawSampler::sample)", track)

    def test_map_and_cast_sender_are_wired_to_same_sampling_contract(self):
        track = source("TrackMap.kt")
        sender = source("cast/CastTimingPublisher.kt")
        geometry = source("LiveTiming.kt")
        self.assertIn("OverlayPositionSampler(timing", track)
        self.assertIn("OverlayPositionSampler(it, OverlayPerformanceProbe", sender)
        self.assertIn("OverlayVertexIndex(outline)", geometry)
        self.assertIn("OverlayEdgeProjection()", track)

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
        self.assertIn("grep -q 'OK (2 tests)'", script)
        self.assertIn("test -s", script)

if __name__ == "__main__":
    unittest.main()
