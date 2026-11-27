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

# Collect independent test failures, but keep installation/boot prerequisites fatal.
# Every gate is mandatory; no failed assertion is converted to a passing build.
failed_gates=()
finish_gates() {
  if (( ${#failed_gates[@]} > 0 )); then
    printf 'Mandatory instrumentation failed: %s\n' "${failed_gates[@]}" >&2
    return 1
  fi
}

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

run_tls_gate() {
  local sdk tls_report
  sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  [[ "$sdk" =~ ^[0-9]+$ ]]
  tls_report="${RUNNER_TEMP:?}/pitwall-tls-api-$sdk-instrumentation.txt"
  if ! run_required_instrumentation \
    app.apexview.cast.local.PitwallTlsAndroidTest \
    'OK (2 tests)' "$tls_report"; then
    adb logcat -d -s PitwallTls:I '*:S' >> "$tls_report" || true
    failed_gates+=("PitwallTlsAndroidTest")
  fi
  cat "$tls_report" >> "$report"
}

run_g0_gate() {
  local sdk g0_report
  sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  [[ "$sdk" =~ ^[0-9]+$ ]]
  g0_report="${RUNNER_TEMP:?}/pitwall-g0-api-$sdk-instrumentation.txt"
  if ! run_required_instrumentation \
    app.apexview.tiled.ArchitectureG0AndroidTest \
    'OK (4 tests)' "$g0_report"; then
    adb logcat -d -s PitwallG0:I '*:S' >> "$g0_report" || true
    failed_gates+=("ArchitectureG0AndroidTest")
  fi
  adb logcat -d -s PitwallG0:I '*:S' >> "$g0_report"
  cat "$g0_report"
  local ga_report
  ga_report="${RUNNER_TEMP:?}/pitwall-ga-api-$sdk-instrumentation.txt"
  if ! run_required_instrumentation \
    app.apexview.tiled.ArchitectureGAAndroidTest \
    'OK (2 tests)' "$ga_report"; then
    adb logcat -d -s PitwallGA:I '*:S' >> "$ga_report" || true
    failed_gates+=("ArchitectureGAAndroidTest")
  fi
  adb logcat -d -s PitwallGA:I '*:S' >> "$ga_report"
  cat "$ga_report" >> "$report"
  cat "$ga_report"
  local gb_report="${RUNNER_TEMP:?}/pitwall-gb-api-$sdk-instrumentation.txt"
  if ! run_required_instrumentation \
    app.apexview.tiled.ArchitectureGBAndroidTest \
    'OK (15 tests)' "$gb_report"; then
    adb logcat -d -s PitwallGB:I '*:S' >> "$gb_report" || true
    failed_gates+=("ArchitectureGBAndroidTest")
  fi
  adb logcat -d -s PitwallGB:I '*:S' >> "$gb_report"
  cat "$gb_report" >> "$report"
  cat "$gb_report"
}

wait_for_android_services
if [[ -n "${PITWALL_G0_BASELINE_APK:-}" ]]; then
  # Fresh hosted emulator only; verify actual signed upgrade and data retention.
  adb install -r "$PITWALL_G0_BASELINE_APK"
  adb shell "run-as app.apexview_custom sh -c 'mkdir -p files && printf g0-baseline > files/g0-upgrade-marker'"
fi
adb install -r "$apk"
adb install -r "$tests"
if [[ -n "${PITWALL_G0_BASELINE_APK:-}" ]]; then
  [[ "$(adb shell run-as app.apexview_custom cat files/g0-upgrade-marker | tr -d '\r')" == "g0-baseline" ]]
  adb shell run-as app.apexview_custom rm files/g0-upgrade-marker
  echo "G0 signed upgrade/data retention PASS"
  run_g0_gate
fi

if [[ "${1:-}" == "tls-only" ]]; then
  run_tls_gate
  finish_gates
  exit 0
fi

run_required_instrumentation \
  app.apexview.OverlayRetainedRenderNodeAndroidTest \
  'OK (2 tests)' "$report" || failed_gates+=("OverlayRetainedRenderNodeAndroidTest")

surface_report="${RUNNER_TEMP:?}/pitwall-surface-scene-instrumentation.txt"
run_required_instrumentation \
  app.apexview.scene.SurfaceDecoderSceneInstrumentationTest \
  'OK (1 test)' "$surface_report" || failed_gates+=("SurfaceDecoderSceneInstrumentationTest")
cat "$surface_report" >> "$report"

# The clear TextureView is the production default and must be tested with the
# same real synthetic HEVC/MediaCodec/EGL pipeline as independent SurfaceView.
# Never mark the A/B code verified when only its optional renderer passed.
texture_report="${RUNNER_TEMP:?}/pitwall-texture-scene-instrumentation.txt"
run_required_instrumentation \
  app.apexview.scene.SingleDecoderSceneInstrumentationTest \
  'OK (1 test)' "$texture_report" || failed_gates+=("SingleDecoderSceneInstrumentationTest")
cat "$texture_report" >> "$report"

# Real clear shared-texture pixel check. An assumption skip on unsupported
# emulator EGL is capability evidence only, never a physical GPU pass.
async_report="${RUNNER_TEMP:?}/pitwall-async-latch-instrumentation.txt"
run_required_instrumentation \
  app.apexview.scene.SceneAsyncTextureLatchAndroidTest \
  'OK (1 test)' "$async_report" || failed_gates+=("SceneAsyncTextureLatchAndroidTest")
cat "$async_report" >> "$report"

cast_report="${RUNNER_TEMP:?}/pitwall-baked-cast-instrumentation.txt"
run_required_instrumentation \
  app.apexview.scene.BakedCastCaptureAndroidTest \
  'OK (1 test)' "$cast_report" || failed_gates+=("BakedCastCaptureAndroidTest")
cat "$cast_report" >> "$report"

# Independent Android-runtime coverage for archive delivery and memory ownership.
# No synthetic hardware codec can substitute for physical F1/Widevine device evidence.
delivery_report="${RUNNER_TEMP:?}/pitwall-archive-delivery-instrumentation.txt"
run_required_instrumentation \
  app.apexview.tiled.ArchiveSegmentDeliveryAndroidTest \
  'OK (2 tests)' "$delivery_report" || failed_gates+=("ArchiveSegmentDeliveryAndroidTest")
cat "$delivery_report" >> "$report"

run_tls_gate
finish_gates
