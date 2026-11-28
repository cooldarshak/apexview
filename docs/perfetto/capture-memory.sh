#!/system/bin/sh
# Phone shell only. Start before selecting the replay; collect after 90 seconds.
set -eu
pkg=app.apexview_custom
action=${1:-}
engine=${2:-}
case "$engine" in rust|kotlin) ;; *) echo "Usage: sh capture-memory.sh start|collect rust|kotlin"; exit 2;; esac
base="pitwall-$engine-memory"
trace="/data/misc/perfetto-traces/$base.perfetto-trace"
dest="/sdcard/Download"
mkdir -p "$dest"
case "$action" in
start)
  pid=$(pidof "$pkg") || { echo "Open Pitwall to its browse screen first"; exit 1; }
  if [ -e "$trace" ]; then
    echo "Existing capture at $trace; collect and move it before starting another"; exit 1
  fi
  printf 'engine=%s startPid=%s\n' "$engine" "$pid" > "$dest/$base-ownership.txt"
  dumpsys package "$pkg" | grep -E 'versionName=|versionCode=|pkgFlags=' >> "$dest/$base-ownership.txt" || true
  dumpsys meminfo "$pkg" >> "$dest/$base-ownership.txt"
  perfetto --query --long > "$dest/$base-producers.txt" 2>&1 || true
  if ! grep -q 'android.heapprofd' "$dest/$base-producers.txt"; then
    echo 'Producer not reported; attempting named config, verify actual heap samples after export.'
  fi
  cat > "$dest/$base.cfg" <<'PITWALL_CONFIG'
# 90-second pre-playback native allocation-site + graphics ownership capture.
# Keep separate from the higher-overhead full scheduler trace. Android may
# refuse heapprofd or GPU counters: missing producers are NOT zero usage.
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
data_sources {
  config {
    name: "android.heapprofd"
    target_buffer: 0
    heapprofd_config {
      process_cmdline: "app.apexview_custom"
      sampling_interval_bytes: 8192
      continuous_dump_config { dump_interval_ms: 10000 }
    }
  }
}
data_sources {
  config {
    name: "linux.ftrace"
    target_buffer: 1
    ftrace_config {
      ftrace_events: "gpu_mem/gpu_mem_total"
      ftrace_events: "kmem/rss_stat"
      atrace_categories: "gfx"
      atrace_categories: "video"
      atrace_categories: "dalvik"
      atrace_apps: "app.apexview_custom"
    }
  }
}
data_sources {
  config {
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 1000 }
  }
}
data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 1 } }
data_sources { config {
  name: "android.surfaceflinger.layers"
  target_buffer: 1
  surfaceflinger_layers_config { mode: MODE_DUMP }
} }
duration_ms: 90000
flush_period_ms: 10000
write_into_file: true
file_write_period_ms: 5000
max_file_size_bytes: 134217728
PITWALL_CONFIG
  cfg="/data/misc/perfetto-configs/$base.cfg"
  [ -s "$dest/$base.cfg" ] && cp "$dest/$base.cfg" "$cfg" && cmp -s "$dest/$base.cfg" "$cfg" || { echo 'Config staging failed'; exit 1; }
  perfetto --background-wait --txt -c "$cfg" -o "$trace" </dev/null > "$dest/$base-perfetto.txt" 2>&1 || { cat "$dest/$base-perfetto.txt"; exit 1; }
  echo "Capture started. Return to Pitwall, start the same replay now, and keep playing."
  echo "After 90 seconds run this script with collect $engine."
  ;;
collect)
  [ -s "$trace" ] || { echo "No trace yet: $trace"; exit 1; }
  pidof "$pkg" | sed 's/^/collectPid=/' >> "$dest/$base-ownership.txt" || true
  dumpsys meminfo "$pkg" >> "$dest/$base-ownership.txt"
  dumpsys SurfaceFlinger --list | grep -F "$pkg" >> "$dest/$base-ownership.txt" || true
  cp "$trace" "$dest/$base.perfetto-trace"
  rm -f "/data/misc/perfetto-configs/$base.cfg"
  ls -l "$dest/$base.perfetto-trace"
  echo "Share the trace, ownership and producer text files plus the in-app diagnostic log."
  ;;
*) echo "Usage: sh capture-memory.sh start|collect rust|kotlin"; exit 2;;
esac
