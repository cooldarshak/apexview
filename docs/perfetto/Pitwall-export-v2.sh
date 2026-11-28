#!/system/bin/sh
# Stop only the named capture; never kill another perfetto session.
set -eu
label=${1:?usage: Pitwall-export-v2.sh LABEL}
case "$label" in *[!a-zA-Z0-9_-]*|'') exit 2;; esac
out="/sdcard/Download/Pitwall-evidence-$label"
manifest="$out/manifest.txt"
[ -f "$manifest" ] || exit 2
field() { sed -n "s/^$1=//p" "$manifest"; }
pkg=$(field package)
pid=$(field pid)
mode=$(field mode)
key=$(field key)
[ "$pkg" = app.apexview_custom ] || exit 2
case "$pid" in ''|*[!0-9]*) exit 2;; esac
[ "$key" = "pitwall-$label-$pid" ] || exit 2
[ -z "$(field end_epoch)" ] || { echo "Capture already exported" >&2; exit 2; }
result=0
if [ "$mode" = memory ]; then
  echo 'stop_status=UNPROFILED' >> "$manifest"
else
  case "$mode" in system|native|java) ;; *) exit 2;; esac
  if perfetto --is_detached="$key" > "$out/stop.txt" 2>&1 &&
     perfetto --attach="$key" --stop >> "$out/stop.txt" 2>&1; then
    echo 'stop_status=STOPPED' >> "$manifest"
  else
    echo 'stop_status=EARLY_OR_FAILED' >> "$manifest"; result=1
  fi
  trace="/data/misc/perfetto-traces/$key.pftrace"
  if [ -s "$trace" ] && cp "$trace" "$out/trace.pftrace"; then
    (cd "$out" && sha256sum trace.pftrace > trace.sha256) || result=1
  else
    echo 'trace_status=MISSING' >> "$manifest"; result=1
  fi
fi
printf 'end_pid=%s\nend_epoch=%s\n' "$(pidof "$pkg" || :)" "$(date +%s)" >> "$manifest"
apk=$(pm path "$pkg" | sed -n '1s/^package://p')
sha256sum "$apk" > "$out/apk-end.sha256" 2>"$out/apk-end.error" || result=1
dumpsys meminfo "$pkg" > "$out/meminfo-end.txt" 2>&1 || :
cat /proc/meminfo > "$out/system-memory-end.txt" 2>&1 || :
cat "/proc/$pid/smaps_rollup" > "$out/smaps-end.txt" 2>&1 || :
dumpsys SurfaceFlinger --list > "$out/layers-end.txt" 2>&1 || :
if [ "$(field end_pid)" != "$pid" ]; then result=1; fi
if ! cmp -s "$out/apk-start.sha256" "$out/apk-end.sha256" || [ ! -s "$out/apk-end.sha256" ]; then result=1; fi
printf 'export_status=%s\n' "$result" >> "$manifest"
echo "Export in-app redacted diagnostics separately. Validate trace contents on host before interpreting any result."
echo "$out"
exit "$result"
