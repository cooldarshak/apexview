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

Then, while the **four feeds are already playing**:

```sh
cat /sdcard/Download/pitwall-50fps.cfg | perfetto --txt -c - -o /sdcard/Download/pitwall-next-50fps.perfetto-trace
```

This should run for **60 seconds** and return to the aShell prompt. The actual
trace must contain `android.surfaceflinger.frametimeline`. The file could be
hundreds of MB; wait for it to finish before sharing it. If `perfetto` refuses
to write directly to Download, try the system trace directory:

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
