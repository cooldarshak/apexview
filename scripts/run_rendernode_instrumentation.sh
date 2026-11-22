#!/usr/bin/env bash
# Mandatory Android RenderNode and independent SurfaceView instrumentation.
# Never classify a failed test assertion as an emulator-startup retry.
set -euo pipefail

report="${RUNNER_TEMP:?}/pitwall-rendernode-instrumentation.txt"
apk="app/build/outputs/apk/debug/app-debug.apk"
tests="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
test -s "$apk"
test -s "$tests"
adb wait-for-device

# sys.boot_completed can become 1 before system_server's activity/package
# binders have stabilized. The Build 686 runner hit DeadObjectException
# immediately after an otherwise successful emulator boot. Require
# consecutive healthy activity and package-manager service probes.
wait_for_android_services() {
  local attempt healthy=0
  for attempt in $(seq 1 45); do
    if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == "1" ]] &&
       adb shell am get-current-user >/dev/null 2>&1 &&
       adb shell pm path android >/dev/null 2>&1; then
      healthy=$((healthy + 1))
      if [[ "$healthy" -ge 3 ]]; then
        echo "Android services stable after $attempt checks."
        return 0
      fi
    else
      healthy=0
    fi
    sleep 2
  done
  echo "Android activity/package services did not become stable." >&2
  return 1
}

# Only an Android binder dying *before instrumentation reports any test*
# permits a single retry. Assertion failures, timeouts after tests start,
# missing footers and real crashes remain hard CI failures.
run_required_instrumentation() {
  local class_name="$1"
  local expected_footer="$2"
  local output="$3"
  local attempt
  for attempt in 1 2; do
    wait_for_android_services
    if adb shell am instrument -w \
        -e class "$class_name" \
        app.apexview_custom.test/androidx.test.runner.AndroidJUnitRunner \
        > "$output" 2>&1; then
      if grep -Fq "$expected_footer" "$output"; then
        cat "$output"
        return 0
      fi
    fi
    cat "$output"
    if [[ "$attempt" == "1" ]] &&
       grep -Eq 'DeadObjectException|DeadSystemException|Can.t find service: activity' "$output" &&
       ! grep -q '^INSTRUMENTATION_STATUS_CODE:' "$output"; then
      echo "Activity service died before test startup; retrying once after rechecking boot."
      sleep 3
      continue
    fi
    echo "Mandatory instrumentation $class_name did not pass." >&2
    return 1
  done
  return 1
}

wait_for_android_services
adb install -r "$apk"
adb install -r "$tests"

run_required_instrumentation \
  app.apexview.OverlayRetainedRenderNodeAndroidTest \
  'OK (2 tests)' "$report"

surface_report="${RUNNER_TEMP:?}/pitwall-surface-scene-instrumentation.txt"
run_required_instrumentation \
  app.apexview.scene.SurfaceDecoderSceneInstrumentationTest \
  'OK (1 test)' "$surface_report"
cat "$surface_report" >> "$report"
