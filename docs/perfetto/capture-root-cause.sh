#!/system/bin/sh
# Run on the playback device with aShell/adb shell, not inside the app.
# Native heap, graphics and ART graph deliberately run in separate phases.
set -eu
pkg=app.apexview_custom
action=${1:-}
label=${2:-}
case "$label" in ""|*[!A-Za-z0-9_-]*) echo "Usage: sh capture-root-cause.sh start|graphics|java|collect|idle RUN_LABEL"; exit 2;; esac
dir="/sdcard/Download/Pitwall-evidence-$label"
traceDir="/data/misc/perfetto-traces"
manifest="$dir/manifest.txt"
uptime() { awk '{print int($1)}' /proc/uptime; }
pid_now() { pidof "$pkg" | awk 'NF == 1 { print $1 }'; }
mark() {
  printf 'phase=%s uptimeSec=%s utc=%s pid=%s\n' "$1" "$(uptime)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(pid_now || true)" >> "$manifest"
}
snapshot() {
  mark "$1"
  dumpsys meminfo -a "$pkg" > "$dir/$1-meminfo.txt" 2>&1 || echo "meminfo unavailable" >> "$manifest"
  dumpsys SurfaceFlinger --list > "$dir/.layers" 2>&1 || true
  grep -F "$pkg" "$dir/.layers" > "$dir/$1-layers.txt" || true
  rm -f "$dir/.layers"
  if [ ! -s "$dir/$1-layers.txt" ]; then echo "$1 package layers not enumerated" >> "$manifest"; fi
  # Exact process mappings when shell permissions allow, never treat denial as zero.
  p=$(pid_now || true)
  if [ -n "$p" ]; then
    cat "/proc/$p/smaps_rollup" > "$dir/$1-smaps-rollup.txt" 2>&1 || echo "$1 smaps_rollup unavailable" >> "$manifest"
  fi
}
begin() {
  name="$1"; seconds="$2"
  out="$traceDir/pitwall-$label-$name.perfetto-trace"
  [ ! -e "$out" ] || { echo "Trace exists: $out. Use a fresh run label."; exit 1; }
  mark "$name-start"
  if perfetto --background-wait --txt -c - -o "$out" > "$dir/$name-perfetto.txt" 2>&1; then
    echo "$(( $(uptime) + seconds + 5 ))" > "$dir/$name-end"
    echo "$name capture accepted; return to the app now. Wait at least $((seconds + 5)) seconds."
  else
    echo "$name START_FAILED" >> "$manifest"
    cat "$dir/$name-perfetto.txt"
    exit 1
  fi
}
finish_trace() {
  name="$1"
  [ -f "$dir/$name-end" ] || { echo "No $name capture started."; exit 1; }
  [ "$(uptime)" -ge "$(cat "$dir/$name-end")" ] || { echo "$name capture still running."; exit 1; }
  src="$traceDir/pitwall-$label-$name.perfetto-trace"
  if [ -s "$src" ]; then
    cp "$src" "$dir/$name.perfetto-trace"
    mark "$name-copied"
    wc -c < "$dir/$name.perfetto-trace" | awk -v n="$name" '{print n "_bytes=" $1}' >> "$manifest"
  else
    echo "$name TRACE_MISSING" >> "$manifest"
  fi
}
check_pid() {
  p=$(pid_now || true)
  [ -n "$p" ] && [ "$p" = "$(cat "$dir/pid")" ] || { echo "Playback process changed; capture is not comparable." | tee -a "$manifest"; exit 1; }
}
case "$action" in
start)
  [ ! -e "$dir" ] || { echo "Run label already exists."; exit 1; }
  p=$(pid_now || true)
  [ -n "$p" ] || { echo "Open Pitwall browse screen first (one app PID required)."; exit 1; }
  mkdir -p "$dir"
  echo "$p" > "$dir/pid"
  touch "$dir/start-marker"
  printf 'run=%s package=%s diagnosticOnly=true\n' "$label" "$pkg" > "$manifest"
  dumpsys package "$pkg" | grep -E 'versionName=|versionCode=|pkgFlags=' > "$dir/build.txt" || true
  perfetto --query > "$dir/producers.txt" 2>&1 || true
  snapshot browse
  begin native 90 <<'CFG'
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
data_sources { config { name: "android.heapprofd" target_buffer: 0 heapprofd_config {
  process_cmdline: "app.apexview_custom" sampling_interval_bytes: 131072
  shmem_size_bytes: 33554432 block_client: false
  continuous_dump_config { dump_interval_ms: 10000 }
} } }
duration_ms: 90000
write_into_file: true
file_write_period_ms: 5000
max_file_size_bytes: 134217728
CFG
  echo "Enable a fresh diagnostic log now, then open the replay. After 95s run graphics $label."
  ;;
graphics)
  check_pid
  finish_trace native
  snapshot playing
  begin graphics 60 <<'CFG'
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" target_buffer: 0 ftrace_config {
  ftrace_events: "gpu_mem/gpu_mem_total"
  ftrace_events: "kmem/rss_stat"
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_wakeup"
  atrace_categories: "gfx" atrace_categories: "video" atrace_categories: "dalvik"
  atrace_categories: "view" atrace_apps: "app.apexview_custom"
} } }
data_sources { config { name: "linux.process_stats" target_buffer: 0 process_stats_config {
  scan_all_processes_on_start: true proc_stats_poll_ms: 1000
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 0 } }
duration_ms: 60000
write_into_file: true
file_write_period_ms: 2500
max_file_size_bytes: 268435456
CFG
  ;;
java)
  check_pid
  finish_trace graphics
  snapshot before-java
  # ART's Perfetto graph records reference structure, not raw field contents.
  # It can pause ART, so do not include this phase in frame-time comparisons.
  begin java 25 <<'CFG'
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "android.java_hprof" target_buffer: 0 java_hprof_config {
  process_cmdline: "app.apexview_custom" dump_smaps: true
  continuous_dump_config { dump_phase_ms: 10000 dump_interval_ms: 10000 }
} } }
duration_ms: 25000
write_into_file: true
file_write_period_ms: 2500
max_file_size_bytes: 268435456
CFG
  ;;
collect)
  check_pid
  finish_trace java
  snapshot after-java
  echo "Return to app, stop playback, wait 20 seconds, switch diagnostic logging OFF to flush, then run idle $label."
  ;;
idle)
  check_pid
  snapshot idle
  mkdir -p "$dir/logs"
  find /sdcard/Download/Pitwall -type f -name 'pitwall-*.log*' -newer "$dir/start-marker" -exec cp {} "$dir/logs/" \; 2>> "$manifest" || true
  count=$(find "$dir/logs" -type f | wc -l)
  echo "log_files=$count" >> "$manifest"
  if [ "$count" -eq 0 ]; then echo "LOGS_MISSING: export every segment manually" >> "$manifest"; fi
  mark collected
  tar -czf "$dir.tar.gz" -C "/sdcard/Download" "Pitwall-evidence-$label"
  echo "Upload $dir.tar.gz. Presence is not proof of complete traces; analyzer validates coverage."
  ;;
*) echo "Unknown phase: $action"; exit 2;;
esac
