# S22 Ultra: phone-only aShell measurement of Pitwall playback

No `adb` command, PC, uninstall or root access is required when aShell has
the device-shell permissions that previously allowed `perfetto` and `logcat`.
If the device rejects a Perfetto data source, report the error; do not turn
off Android security protections or downgrade the trace silently.

## Before the capture

1. Install the newest green **signed** Debug APK over the current build.
   Keep the app's saved login and preferences. Do not uninstall it.
2. Enable Pitwall's in-app diagnostic logging and keep Single Decoder selected.
3. Open the **same archive and playback position** as Build 573. Use four feeds:
   1080p primary + three 540p secondary renditions where available. Confirm
   the **actual** resolutions and one hardware HEVC decoder in Pitwall's log.
4. Use the **TV** player layout, timing tower **ON**, track map **ON**.
   Keep the screen at 120 Hz. Avoid switching layout or seeking during this test.
5. Play normally for 30 seconds before starting the trace. Keep the phone
   unlocked, note its temperature/charging state, and avoid screen recordings
   during the trace. A 60-second trace is the measurement interval.

## Commands to paste in aShell

Download the versioned official-schema config from the same branch as the APK:

```sh
curl -fL -o /sdcard/Download/pitwall-50fps.cfg https://raw.githubusercontent.com/cooldarshak/apexview/feature/pitwall-performance-audit-plan/docs/perfetto/pitwall-50fps.cfg
```

If `curl` is not available, open that URL in your phone browser and save the
file as `/sdcard/Download/pitwall-50fps.cfg` instead. The config must be
downloaded **after** the updated branch has been pushed.

Then, while the **four feeds are already playing**, stream Perfetto's binary
output to a file opened by the shell. This avoids Android's restriction on
letting the Perfetto process itself open files in `/sdcard/Download`:

```sh
cat /sdcard/Download/pitwall-50fps.cfg | perfetto --txt -c - -o - > /sdcard/Download/pitwall-next-50fps.perfetto-trace
```

If a previously downloaded config fails with `Unexpected value 'LID_MAIN'`,
replace that invalid enum with `LID_DEFAULT` (the main log buffer) before
running the command above:

```sh
sed -i 's/LID_MAIN/LID_DEFAULT/g' /sdcard/Download/pitwall-50fps.cfg
```

Keep a space after `-o` and use the full `.perfetto-trace` filename. This
corrects the config parse error but does not by itself establish that every
Perfetto producer is supported by the device.

This should run for **60 seconds** and return to the aShell prompt. The actual
trace must contain `android.surfaceflinger.frametimeline`. The file could be
hundreds of MB; wait for it to finish before sharing it. If `perfetto` refuses
to stream to the shell's output file, try the system trace directory. Give it a
new name if a previous capture already exists:

```sh
cat /sdcard/Download/pitwall-50fps.cfg | perfetto --txt -c - -o /data/misc/perfetto-traces/pitwall-next-50fps.perfetto-trace
cp /data/misc/perfetto-traces/pitwall-next-50fps.perfetto-trace /sdcard/Download/pitwall-next-50fps.perfetto-trace
```

If shell access is denied, stop and send the exact error. Do not rerun with
an unrelated 64 MiB default trace since it loses most of the measurement.

While the same replay remains open, collect the *focused* system buffer:

```sh
logcat -d -v time -s BufferQueueProducer:E BufferQueueConsumer:E SurfaceTexture:W SurfaceFlinger:W > /sdcard/Download/pitwall-next-queue.txt
```

Also export the full rotating Pitwall app diagnostics using the in-app export
button, and share that file with the two files from Download. The app export
is preferable to unfiltered `logcat` because it redacts service credentials.

## What to test after the first 60 seconds

Repeat the same replay at the same quality and refresh mode in this order:

1. Four feeds + tower + map, TV layout: 60 seconds, **first priority**.
2. Four feeds + both overlays off: 60 seconds.
3. Four feeds + tower only: 60 seconds.
4. Four feeds + map only: 60 seconds.
5. With both overlays restored, seek forward and backward, pause/resume,
   switch audio, and confirm only **one hardware decoder** remains. Do not
   include this interaction test in a steady-state benchmark interval.
6. When phone playback passes, repeat the same playback and D-pad/back/focus
   flow on the TCL Android TV. Use shorter Perfetto captures on low-memory TVs.

For each test report how it looks to you: smooth / occasional judder /
continuous jitter / freezes, along with the actual feed resolutions. Do not
assume scene texture callback counts equal physically presented video frames.

## Reproducible comparison, performed after receiving the traces

Use `scripts/analyze_pitwall_overlay_ab.py` on the analysis computer (`pip
install perfetto`). It reads real Perfetto scheduler slices, named overlay
traces and **app** FrameTimeline data. Windows use seconds relative to the
first scheduler event in the trace, **not** the video time or wall-clock time.
Only select steady intervals, excluding toggle and startup transitions.

```sh
python scripts/analyze_pitwall_overlay_ab.py pitwall-overlay-ab.perfetto-trace \
  --window OFF:5:19 --window MAP:21:35 \
  --window BOTH:37:53 --window TOWER:55:59
```

The intervals above are only an example. Select actual on/off boundaries in
the captured trace; never use those example numbers blindly for a new capture.
For separate 60-second traces, run the tool against each trace separately and
compare like-for-like steady windows. Verify equal source resolutions, decoded
video FPS, 120 Hz mode, single decoder and similar device temperature first.

To track CMAF GC without confusing initial warmup with steady playback:

```sh
python scripts/analyze_pitwall_runtime.py pitwall-app-export.log.txt --build 1.0.583
```

Replace `1.0.583` with the **installed** version. The analyzer ignores the
first three segment compositions when reporting steady median allocations,
GC counts, compose and rewrite time. App deadline misses, codec throughput,
GL submission and physical video presentation are different metrics. A green
CI build or fewer app deadline misses alone is not a video smoothness PASS.


## Controlled release-quality endurance matrix (P2.6)

Run a full **10-minute authorized playback session** for each of OFF, MAP,
TOWER and BOTH, first at **1.0x**, then at **1.5x**. Keep the same F1 event,
source renditions, feed count, decoder preference, device display refresh mode,
DRM entitlement and layout across cases. Do not attribute thermal drift or
feed changes to an overlay. Start with the S22 Ultra, repeat on the TCL 55C655
with Software DRM **ON**, and separately exercise clear/protected and Pitwall Cast
where permitted. Never turn off security, recording or overlays to pass a gate.

For each ten-minute interval, export the app diagnostic log and take a 60-second
Perfetto trace **during steady playback**. Record temperature/charging conditions,
actual mode shown by `SCENE_DISPLAY_MODE` / `SURFACE_SCENE_DISPLAY_MODE`,
decoder type, real source dimensions and effective playback rate. Benchmark
APKs produced by green CI include a physical-only
`sustainedAuthorizedPlaybackTenMinutes` instrumentation test. It deliberately
requires the already-authorized viewer on screen and does not insert credentials.

Export the Perfetto `actual_frame_timeline_slice` CSV with
`docs/perfetto/frame_timeline_export.sql`, then create a local JSON manifest
containing **eight runs** per device and playback configuration. A sample
single-run entry (repeat for the other seven overlays/speeds):

```json
{
  "runs": [{
    "label": "S22-4feed-1x-off",
    "overlay": "OFF",
    "speed": 1.0,
    "device": "SM-S908E",
    "build": "1.0.2000",
    "content_id": "same-authorized-session",
    "source_profile": "verified-source-dimensions-and-fps",
    "decoder_mode": "SINGLE",
    "protection": "CLEAR_HEVC",
    "delivery": "PHONE",
    "feed_count": 4,
    "display_hz": 120.0,
    "thermal_start": 0,
    "thermal_end": 1,
    "app_log": "s22-off-1x.log.txt",
    "timeline_csv": "s22-off-1x.csv"
  }]
}
```

Then run `python scripts/pitwall_acceptance_matrix.py captures.json --json`.
Its validator refuses an incomplete or mixed-quality matrix, missing measured
app frames, less than 600 seconds of real app diagnostics, or less than 45 seconds
of FrameTimeline capture. Reports include app slow-frame percentage,
SurfaceFlinger app/video layer lateness, frozen frames, HEVC process-wide
allocations, GC, foreground request p95, queue timeout rate and thermal statuses.
It reports `NO_INDEPENDENT_VIDEO_LAYER` for clear TextureView when there is no
separate SurfaceView layer; **that is not a video presentation PASS**.

The comparison program cannot independently prove source-to-scanout picture
identity, protected Widevine functionality, true 50fps video presentation,
A/V sync, or TCL/Cast parity. All of these still require on-device validation.
A script exit status or successful CI build never closes a physical release gate.
