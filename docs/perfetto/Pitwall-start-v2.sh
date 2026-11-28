#!/system/bin/sh
# Run as Android shell, not root. One unique label per capture; stop manually.
set -eu
pkg=app.apexview_custom
label=${1:?usage: Pitwall-start-v2.sh LABEL memory|system|native|java}
mode=${2:-memory}
case "$label" in *[!a-zA-Z0-9_-]*|'') echo "Invalid label" >&2; exit 2;; esac
case "$mode" in memory|system|native|java) ;; *) exit 2;; esac
out="/sdcard/Download/Pitwall-evidence-$label"
mkdir "$out" || { echo "Use a new label" >&2; exit 2; }
pid=$(pidof "$pkg" || :)
case "$pid" in ''|*[!0-9]*) echo "Start Pitwall first; expected one app PID" >&2; exit 2;; esac
apk=$(pm path "$pkg" | sed -n '1s/^package://p')
[ -n "$apk" ] || exit 2
key="pitwall-$label-$pid"
trace="/data/misc/perfetto-traces/$key.pftrace"
cfg="/data/misc/perfetto-configs/$key.cfg"
[ ! -e "$trace" ] && [ ! -e "$cfg" ] || exit 2
printf 'label=%s\nmode=%s\npackage=%s\npid=%s\nkey=%s\nstart_epoch=%s\n' "$label" "$mode" "$pkg" "$pid" "$key" "$(date +%s)" > "$out/manifest.txt"
getprop ro.build.fingerprint > "$out/firmware.txt"
sha256sum "$apk" > "$out/apk-start.sha256" 2>"$out/apk-start.error" || :
dumpsys meminfo "$pkg" > "$out/meminfo-start.txt" 2>&1 || :
cat /proc/meminfo > "$out/system-memory-start.txt" 2>&1 || :
cat "/proc/$pid/smaps_rollup" > "$out/smaps-start.txt" 2>&1 || :
if [ "$mode" = memory ]; then
  printf 'start_status=UNPROFILED\n' >> "$out/manifest.txt"
  echo "Unprofiled capture started. Enable in-app diagnostics; export when ready."
  exit 0
fi
perfetto --query > "$out/producers.txt" 2>&1 || :
{
case "$mode" in
system)
cat <<'CFG'
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
buffers { size_kb: 16384 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" target_buffer: 0 ftrace_config {
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_waking"
  ftrace_events: "power/cpu_frequency"
  ftrace_events: "gpu_mem/gpu_mem_total"
  atrace_categories: "gfx"
  atrace_categories: "video"
  atrace_apps: "app.apexview_custom"
} } }
data_sources { config { name: "linux.process_stats" target_buffer: 1 process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 1000 } } }
data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 1 } }
CFG
;;
native)
cat <<'CFG'
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
data_sources { config { name: "android.heapprofd" heapprofd_config {
  process_cmdline: "app.apexview_custom"
  sampling_interval_bytes: 131072
  shmem_size_bytes: 33554432
  block_client: false
  continuous_dump_config { dump_interval_ms: 10000 }
} } }
CFG
;;
java)
cat <<'CFG'
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
data_sources { config { name: "android.java_hprof" java_hprof_config {
  process_cmdline: "app.apexview_custom"
  continuous_dump_config { dump_interval_ms: 30000 }
} } }
CFG
;;
esac
cat <<'CFG'
duration_ms: 7200000
write_into_file: true
file_write_period_ms: 5000
max_file_size_bytes: 503316480
CFG
} > "$out/config.txt"
if ! cp "$out/config.txt" "$cfg" || ! cmp -s "$out/config.txt" "$cfg"; then
  echo 'start_status=CONFIG_FAILED' >> "$out/manifest.txt"; exit 1
fi
if perfetto --txt -c "$cfg" -o "$trace" --detach="$key" > "$out/start.txt" 2>&1 &&
   perfetto --is_detached="$key" >> "$out/start.txt" 2>&1; then
  echo 'start_status=DETACHED' >> "$out/manifest.txt"
else
  echo 'start_status=FAILED' >> "$out/manifest.txt"; exit 1
fi
echo "Capture started. Export manually when ready. Trace caps can end capture early; unprofiled app logs remain separate."
