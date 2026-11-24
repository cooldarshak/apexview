# Reproducible 50 fps playback and BufferQueue profiling

**Current checkpoint (24 November 2026):** [Build 839 GREEN](https://github.com/cooldarshak/apexview/actions/runs/45391665766), source `5090df55`. Native multifeed, map/tower and caption appearance have been observed on phone and TV; TV three-feed stability, Pitwall TLS and baked Google Cast buffering remain open. Physical/release acceptance is not implied by CI. The [current master ledger](PITWALL_PERFORMANCE_AUDIT_PLAN.md) supersedes historical coding percentages and pending instructions below.

This guide complements `PITWALL_PERFORMANCE_AUDIT_PLAN.md`. Its measurements must come
from the **same device**, actual source resolutions and chosen display mode. Green CI
checks bitstream correctness; it does **not** establish on-screen smoothness.

## Capture with Perfetto on the S22 Ultra or TCL TV

1. Install the newest signed CI APK over the installed version. Keep **Single Decoder**
   selected. Open the F1 replay, choose one primary 1080p plus three secondary feeds
   capped at 540p where available, and enable the timing tower and tracker map. Confirm the *actual*
   HLS/coded source sizes in the normal Pitwall diagnostics.
2. Leave the replay running for 30 seconds so the decoder and buffers stabilize.
3. Connect ADB, then run from the repository root:

   ```sh
   adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/pitwall-50fps.perfetto-trace < docs/perfetto/pitwall-50fps.cfg
   adb pull /data/misc/perfetto-traces/pitwall-50fps.perfetto-trace .
   ```

   The first command runs for 60 seconds. Build 573's 64MiB on-device capture
   retained only 17.7s of a 60s S22 trace; the checked-in configuration now
   reserves a 256MiB ftrace buffer and up to 480MiB for the file. On
   lower-memory TVs reduce the capture duration first if collection fails.
   If your build does not support the
   SurfaceFlinger layers/FrameTimeline data source, query supported sources with
   `adb shell perfetto --query` and remove only unavailable data sources.
4. Immediately export the app's Pitwall diagnostics log and capture `adb logcat -d`
   if BufferQueue messages were not part of the export. Record device model,
   Android version, replay/session, chosen feeds and actual dimensions, caps,
   display's **active** Hz, battery temperature and whether the phone was charging.
   Do not upload F1 tokens, HLS URLs, cookies, or entitlement material.
   If testing entirely on your phone, see `docs/perfetto/ASHELL.md` for
   `perfetto` and `logcat` commands with **no `adb` executable required**.
5. Open the trace in [Perfetto UI](https://ui.perfetto.dev). Inspect the app's main
   thread, RenderThread, ApexviewSingleScene, MediaCodec threads, compositor and
   SurfaceFlinger; correlate `PitwallCodecPtsMs` and `PitwallCodecPlannedReleaseMs`
   counters with `PitwallTextureLatch`, `PitwallGlDraw`, `PitwallEglSwap`,
   `PitwallPacingDefer`, `PitwallTowerRowDraw`, `PitwallTowerTextPrepare`, and
   `PitwallMapCars`/`PitwallEdgeCars` slices.
6. In Perfetto UI, paste `docs/perfetto/frame_timeline_export.sql` in the
   Query tab and export as `baseline-frame-timeline.csv`. Repeat on the new
   build as `candidate-frame-timeline.csv` with the same feeds/refresh mode.
   Run:

   ```sh
   python3 scripts/analyze_pitwall_frames.py \
     --baseline baseline-frame-timeline.csv \
     --candidate candidate-frame-timeline.csv
   ```

   For cumulative app-log counts and to avoid mixing two builds appended to the
   same file, run `python3 scripts/analyze_pitwall_runtime.py your-log.txt
   --build 1.0.573`. A valid A/B test must still isolate the same steady-state
   layout interval rather than compare an entire mixed-layout session.

   Record each result alongside matching `SINGLE_DECODER_SCENE`,
   `SCENE_CODEC_RELEASE`, `PitwallFrames`, composition, allocation and GC logs.
   The report explicitly separates SurfaceFlinger display slots from app
   surfaces and **cannot prove unique video-picture presentation at 50fps**.

The regular log's `SCENE_CODEC_RELEASE` histogram describes **planned codec-buffer
release**, `sourceTimestampBins...` describes the decoder texture timestamps,
`videoVsyncBins...` describes Choreographer scene draws, and `PitwallFrames` describes
HWUI window frames. None alone measures **physical presentation**. FrameTimeline's
actual SurfaceFlinger display frames are the physical-presentation evidence. The
`videoDrawPacingDeferrals` counter reports when an 8ms scene draw was delayed to
the next vsync; repeated very long intervals after deferral would be a regression.
`glQueueP95UpperMs`, `glQueueMaxMs` and `glQueueProbeSamples` report the
enqueue-to-execution delay of a low-rate main-to-GL handler probe. A high
delay suggests GL handler congestion; a low delay does not rule out codec
producer stalls, GPU fence waits or UI accessibility work. Probe posting may
also be late if the main thread is blocked, which is measured independently.

## Attribute an actual 50 fps stutter

At a genuine 50 fps source the *source timestamps* should advance about 20ms.
At 120 Hz, *physical display frames* occupy 8.33ms refresh slots. A new 50 fps
picture cannot occupy a uniform number of those slots, so a healthy 120 Hz display
usually follows 16.67/25ms picture presentation intervals, not constant 20ms.
This is expected 3:2 pulldown, distinct from extra 8.33ms or 33.33ms jitter.

Compare these time-aligned stages in Perfetto:

| If the gap begins at... | Investigate first | Do not assume |
| --- | --- | --- |
| CMAF/codec input | Segment delivery, cache misses, playback buffer, parser/GC and codec scheduling | Every stall is the compositor |
| Codec output / texture latch | MediaCodec output timing, blocked producer, dequeue/acquire ownership, release behavior and scheduler | `dequeuedCount=21` proves the consumer caused the timeout |
| Latch -> Choreographer/GL | GL looper occupancy, `eglMakeCurrent`, callback lateness, burst pacing | A quick EGL swap means GPU is never busy |
| EGL swap -> SurfaceFlinger | BufferQueue producer/consumer, fences, FrameTimeline, composition and display | A scene draw is a physical present |
| UI frames only | Main thread, RenderThread command issue, text measure, map/tower slices and GC | UI slowness necessarily implies Media3 dropped frames |

Run controlled A/B trials with the **same** feeds and playback position:

1. Four feeds, overlays both on, 5-10 minutes after warmup.
2. Same feeds, tower/map both off, 5-10 minutes.
3. Map on/tower off, then tower on/map off (attribute UI contention).
4. 1080p + 480p + 480p, then 1080p + 540p + 540p, only if offered by F1.
5. Change one feed's cap, seek forward and backward, pause/resume, audio switch,
   rotate the phone; verify decoder count returns to one.
6. Re-run the four-feed and remote-control tests on the TCL TV. Check D-pad,
   focus recovery, Back, overlays, video corruption and A/V synchronization.

For each trial report the same elapsed playback interval, input/atlas coded
pixels, `activeVideoDecoders=1`, `selectedVideoTracks=1`, `hardware=hardware YES`,
`firstFrame=true`, physical frame interval histogram, BufferQueue timeouts per
minute, slow/frozen HWUI frames, GCs/allocated bytes, composition p50/p95,
audio drift/buffered duration and active refresh mode. An ordinary Pitwall log
cannot substitute for Perfetto SurfaceFlinger data. The audio hardware clock
also requires an external sync test or platform audio trace.

## Performance reference projects

- [Android Performance Samples](https://github.com/android/performance-samples):
  Macrobenchmark `FrameTimingMetric`, composable trace slices, Baseline Profiles and
  real-device repeatability. A separate benchmark app/device runner is follow-up;
  the normal CI workflow currently assembles instrumentation APKs but cannot
  measure playback hardware in GitHub Actions.
- [Now in Android](https://github.com/android/nowinandroid): incremental,
  state-hoisted Compose architecture and startup profiling. Avoid a whole-app
  redesign while the decoder pipeline remains under test.
- [Android TV Samples](https://github.com/android/tv-samples): D-pad focus and
  back-navigation tests for retained TV functions.
- [Androidify](https://github.com/android/androidify),
  [Breezy Weather](https://github.com/breezy-weather/breezy-weather),
  [Roborazzi](https://github.com/takahirom/roborazzi): useful for future UI
  component consistency and visual regression tests. Material 3 Expressive work is tracked separately in P2.8; it is not evidence of playback performance.

No trace or smoothness result is implied by shipping this configuration.
