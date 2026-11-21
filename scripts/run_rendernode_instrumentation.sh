#!/usr/bin/env bash
# Execute the real Android RenderNode instrumented tests in ONE shell process.
# The emulator-runner action invokes its script input via /bin/sh -c, which
# cannot parse a multiline if/then/fi fragment as separate commands.
set -euo pipefail

report="${RUNNER_TEMP:?}/pitwall-rendernode-instrumentation.txt"
apk="app/build/outputs/apk/debug/app-debug.apk"
tests="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
test -s "$apk"
test -s "$tests"
adb wait-for-device
adb install -r "$apk"
adb install -r "$tests"

# Capture instrument output even on an Android test assertion failure.
if adb shell am instrument -w \
    -e class app.apexview.OverlayRetainedRenderNodeAndroidTest \
    app.apexview_custom.test/androidx.test.runner.AndroidJUnitRunner \
    > "$report" 2>&1; then
  cat "$report"
else
  cat "$report"
  exit 1
fi
# Some instrumentation failures return shell exit 0, so require the runner's
# explicit all-tests-passed footer rather than trusting the adb exit code alone.
grep -q 'OK (2 tests)' "$report"
