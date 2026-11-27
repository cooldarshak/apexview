"""Exercise the actual mandatory runner against deterministic fake adb results."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "scripts/run_rendernode_instrumentation.sh"

class InstrumentationGateCollectionTest(unittest.TestCase):
    def run_runner(self, failures, tls_only=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for path in ("app/build/outputs/apk/debug/app-debug.apk",
                         "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
                         "baseline.apk"):
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b"fixture")
            adb = root / "adb"
            adb.write_text("""#!/usr/bin/env bash
case "$*" in
  'shell getprop sys.boot_completed') echo 1 ;;
  'shell getprop ro.build.version.sdk') echo 29 ;;
  'shell run-as app.apexview_custom cat files/g0-upgrade-marker') echo g0-baseline ;;
  'shell am instrument '*)
    while [[ "$1" != "-e" ]]; do shift; done
    name="${3##*.}"
    echo "$name" >> "$CALLS"
    case ",$FAILURES," in
      *",$name,"*) echo 'FAILURES!!!'; exit 1 ;;
    esac
    case "$name" in
      ArchitectureG0AndroidTest) echo 'OK (4 tests)' ;;
      ArchitectureGBAndroidTest) echo 'OK (15 tests)' ;;
      ArchitectureGAAndroidTest|OverlayRetainedRenderNodeAndroidTest|ArchiveSegmentDeliveryAndroidTest|PitwallTlsAndroidTest) echo 'OK (2 tests)' ;;
      *) echo 'OK (1 test)' ;;
    esac ;;
esac
exit 0
""")
            adb.chmod(0o755)
            sleep = root / "sleep"
            sleep.write_text("#!/bin/sh\nexit 0\n")
            sleep.chmod(0o755)
            calls = root / "calls"
            env = dict(os.environ, PATH=str(root)+os.pathsep+os.environ["PATH"],
                       RUNNER_TEMP=str(root), PITWALL_G0_BASELINE_APK=str(root/"baseline.apk"),
                       CALLS=str(calls), FAILURES=",".join(failures))
            result = subprocess.run(["bash", str(RUNNER)] + (["tls-only"] if tls_only else []),
                                    cwd=root, env=env, capture_output=True, text=True, timeout=20)
            return result, calls.read_text().splitlines()

    def test_all_independent_failures_are_collected_without_publishing(self):
        failed = ["ArchitectureGBAndroidTest", "SceneAsyncTextureLatchAndroidTest"]
        result, calls = self.run_runner(failed)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(calls), 10)
        self.assertEqual(len(set(calls)), 10)
        self.assertEqual(calls[-1], "PitwallTlsAndroidTest")
        for name in failed:
            self.assertIn("Mandatory instrumentation failed: " + name, result.stderr)

    def test_all_passing_and_tls_only_failure(self):
        result, calls = self.run_runner([])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 10)
        result, calls = self.run_runner(["ArchitectureGAAndroidTest"], tls_only=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, ["ArchitectureG0AndroidTest", "ArchitectureGAAndroidTest",
                                 "ArchitectureGBAndroidTest", "PitwallTlsAndroidTest"])
        self.assertIn("Mandatory instrumentation failed: ArchitectureGAAndroidTest", result.stderr)

if __name__ == "__main__":
    unittest.main()
