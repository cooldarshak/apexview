# Pitwall TV: playback performance audit and implementation plan

Status: implementation backlog, not a claim of completed fixes or smooth playback.

## Implementation progress (20 November 2026)

The first P0 implementation batch is being delivered on this branch. These are code changes,
**not device-validated performance wins**. Do not close any P0 issue until its exit criteria
and both devices have been checked with fresh playback logs.

| Priority | First-batch changes | Verification and remaining work |
| --- | --- | --- |
| P0.1 | Replaced the segment-wide fetch/composition lock with per-segment single-flight production, independent bounded foreground/prefetch network lanes, preemptible warmup and bounded cache publication. Added deterministic priority, sharing, cancellation and retry tests. | Await CI; explicitly test seek/close races and compare foreground latency against Build 562 on-device. |
| P0.2 | Reuse session-owned validated initialization metadata, inspect bounded fragment sample ranges without a per-sample copy, use sized Annex-B buffers and direct sized CMAF sample output, log separate parsing/rewrite/packaging/verification times. Added byte-for-byte fixture comparison. | Await synthetic HEVC/FFmpeg CI; obtain per-segment allocated-byte and GC measurements on-device, profile remaining Annex-B and NAL allocations. |
| P0.3 | Corrected GL draw versus actual EGL swap timing; separately measured latch time, late Choreographer callbacks and SurfaceTexture timestamp regressions; added trace sections and frame-accounting tests. | BufferQueue producer/consumer cause and physical SurfaceFlinger presentation still require Perfetto/FrameTimeline captures. Do not claim timeouts fixed. |
| P0.4 | Memoized immutable Android TV tower row formatting/sector work; added explicit frozen-window-frame and display-refresh logs with unit coverage. | Capture a real main-thread/RenderThread trace; compare slow/frozen percentages. Macrobenchmark and remaining UI hotspots are still open. |

Testing note: the handover environment could not download Gradle 8.11.1 because outbound
access to the distribution host was unavailable. GitHub Actions must run compilation,
synthetic composition checks and tests before this batch is considered build-verified.

Baseline: `feature/per-feed-adaptive-hevc-quality` at
`574ccb587a50f174748d1b08ed2283e4b8ed1ecc` (Build 562).

Primary evidence: Samsung Galaxy S22 Ultra playback log
`pitwall-20261119-175803-913.log.txt` (19 November 2026).

## Goals and invariants

1. Achieve consistently smooth playback of F1's 50 fps feeds on supported hardware,
   with explicit measurement of actual on-screen presentation and A/V sync.
2. Preserve one composed HEVC bitstream, one ExoPlayer, one active hardware video
   decoder, one selected video track, and one video output for local multiview.
3. Preserve independently selectable feeds and per-feed quality caps; report the
   actual HLS rendition, visible picture dimensions, coded picture dimensions,
   and resulting HEVC atlas dimensions.
4. Preserve legitimate F1 TV login, entitlement, Widevine and protected-content
   rules. Never add DRM bypass or an undisclosed multiple-decoder fallback.
5. Keep existing phone, foldable, and Android/Google TV layouts, remote/D-pad
   navigation, resizable timing tower and map, playback/audio controls, and
   top-left speed readouts with persistent independent size selection.
6. Keep APK signing compatible with in-place updates and retain existing CI
   HEVC correctness and synthetic-decoding checks.
7. Do not introduce Tiledmedia, ClearVR, paid OpenF1, or a recording feature
   in place of the separately required continuous composed Cast stream.

## Evidence-based baseline

Build 562's recorded test used four feeds: the primary feed's actual picture
was 1920x1080, and three secondary feeds capped at 480p each actually
resolved to 640x360. Their coded pictures were 1920x1088 and three times
960x544. The composed coded picture was 1920x2176 (4,177,920 pixels),
including 522,240 extra atlas pixels beyond the coded source pictures.
The scene rendered at 2316x1080. This particular atlas is approximately
twice the decoded pixel workload of a 1920x1080 picture.

The playback diagnostic confirmed `c2.qti.hevc.decoder`,
`activeVideoDecoders=1`, `selectedVideoTracks=1`, `hardware=hardware YES`,
and `firstFrame=true`; Media3 reported zero dropped frames. **These facts do
not establish smooth physical display presentation.**

| Metric | Observed in latest log | Interpretation |
| --- | ---: | --- |
| Slow Android window frames during active playback | 1,601 / 2,112 (75.8%) | Serious UI/render scheduling problem, not necessarily Compose alone |
| Worst active-playback window frame | 904 ms | A frozen window frame |
| Worst window frame during startup | 2,410 ms | Startup contention also matters |
| Decoder texture frames latched | 4,857 | GL-side metric, not SurfaceFlinger presentation |
| Renderer draws containing new video | 4,668 | Coalesced callbacks; do not interpret the ratio as dropped frames |
| Texture callback gaps over 45 ms | 33 | Irregular decoder-to-renderer cadence |
| Renderer display gaps over 45 ms | 36 | Does not cover short 50/60/120 Hz cadence jitter |
| Largest callback/display gap | 166 ms | Visible judder is plausible |
| BufferQueue timeouts, including grouped repeats | About 380 | Requires codec/producer/consumer timeline investigation |
| Choreographer skipped-frame warnings | 38 | Main-thread/UI scheduling pressure |
| Garbage-collection events | About 281 | Allocation churn requires profiling |
| Cumulative memory freed by GC | About 9 GB | Cumulative reclaimed allocation, **not simultaneous heap size** |
| Median composition time per 5.76-second segment | About 2.3 seconds | Throughput often sufficient at 1x, but little thermal/seek margin |
| Longest observed foreground request/lock wait | About 3.9 seconds | Foreground requests contend with warmup composition |

The previous Build 561 log was not available for a fresh, configuration-matched
comparison. Do not treat differences from its earlier reported summary as a
controlled regression; the latest recorded session used four feeds.

## P0: playback stability fixes

### P0.1: isolate and prioritize segment production

Files: `tiled/HevcArchiveVodSession.kt`, `tiled/ArchiveBufferPolicy.kt`.

`composeOrReadSerialized()` currently holds `composeLock` during network fetch,
random-access checks, atlas planning, HEVC composition, output verification,
and cache publication. Foreground playback has waited behind
`ApexviewVodWarm` for 3.908 seconds in the device log.

Implementation:

- Separate bounded network fetch from the serialized mutation of shared
  output/session state. Protect each shared field at its actual ownership
  boundary; do not simply remove synchronization.
- Introduce per-segment single-flight results so a foreground request for a
  segment already being composed can share it without duplicating work.
- Prioritize urgent playback requests over unrelated speculative prefetch.
- Cancel stale fetch and composition work cooperatively on seek, including
  checks inside expensive loops, while safely releasing all owned buffers.
- Preserve the current bounded retained cache, seek validity, output
  verification, zeroing of temporary buffers, and audio/video timeline.
- Add deterministic tests for same-segment sharing, foreground priority,
  unrelated prefetch cancellation, seek races, close races, and failures.

Acceptance: under a deterministic slow-prefetch fixture, an unrelated
foreground segment must not wait for that speculative segment's entire
network/composition operation. The same segment must not be composed twice.

### P0.2: reduce HEVC parsing and allocation churn

Files: `tiled/HevcClearCmafGridComposer.kt`,
`tiled/HevcArchiveVodSession.kt`, `tiled/CmafBoxInspector.kt`,
`tiled/HevcTizenGridComposer.kt`.

- Cache immutable, validated initialization metadata per exact rendition and
  configuration fingerprint; retain fragment-by-fragment validation and all
  encryption/protection checks.
- Replace avoidable `copyOfRange()` sample allocations with bounded byte
  slices or offsets while retaining correct lifetime and zeroing contracts.
- Avoid temporary full-stream Annex-B copies where a streaming or sized
  writer can emit the same output without weakening NAL/slice validation.
- Eliminate redundant copies between composed output, cache, and returned
  playback bytes only where ownership remains unambiguous.
- Measure fetch, CMAF parse, sample/NAL inspection, slice rewrite, output
  writing, output validation, bytes allocated, GC count, and CPU time per
  segment separately. Compare warmed and cold configurations.
- Add identical-output, property, cancellation and malformed-input tests,
  plus independent FFmpeg decoding and source-pixel comparison fixtures.

Acceptance: no regression in output parameter sets, independently decoded
pixels, fragment timeline, IRAP/seek correctness, protected-content checks,
or cache/ownership tests. Establish measured allocation and p95 composition
reductions before making a performance claim.

### P0.3: diagnose codec/SurfaceTexture/GL presentation

Files: `scene/SingleDecoderSceneView.kt`, `scene/SceneFramePacing.kt`,
`scene/SceneFrameDemand.kt`, `WindowFrameDiagnostics.kt`.

- Correlate monotonic timestamps for codec output, frame-available callback,
  `updateTexImage()`, `SurfaceTexture.getTimestamp()`, GL draw, actual
  `eglSwapBuffers()`, Choreographer callback, and player/audio clock.
- Fix the misleading `maxSwapMs`/`slowSwaps` measurement: it currently times
  the entire `draw()` operation, not just `eglSwapBuffers()`.
- Use Perfetto scheduler, graphics, MediaCodec, BufferQueue, SurfaceFlinger
  FrameTimeline, ART and window-frame traces to find the source of repeated
  `waitForFreeSlotThenRelock` timeouts. A timeout alone does not prove that
  SurfaceTexture is failing to release its acquired buffer.
- Evaluate whether GL latching and display drawing contend for the same
  handler/context. Change thread/context ownership only if traces establish
  a need and synchronization can be proven correct.
- Report 16/20/33/40/50 ms intervals and actual presentation time; do not
  use the Media3 dropped-frame counter as a smoothness proxy.
- Add unit tests for frame accounting and instrumentation exercising
  repeated decoder frames, output-surface recreation and playback seeks.

Acceptance: recurring BufferQueue timeouts are eliminated or causally
explained; the video is verified smooth by a real physical presentation
timeline rather than GL-side counters alone.

### P0.4: reduce UI frame misses and contention

Files: `AppState.kt`, `Viewer.kt`, `LiveTiming.kt`, `TrackMap.kt`,
`TvTowerRow.kt`, `WindowFrameDiagnostics.kt`.

- Capture main thread, RenderThread, GC, CPU frequency and scheduling traces
  while video, tower and map are all active.
- Keep timed overlay sampling decoupled from 50 fps video; preserve the
  Build 562 ~30 Hz phone update policy and smaller adjustable speed labels.
- Avoid publishing identical high-frequency Compose state, excessive
  cross-thread state updates, repeated formatting/text measurement, and
  cache-invalidating changes unrelated to the displayed content.
- Preserve cached track geometry and measure actual per-row tower work
  before optimizing it; use immutable prepared snapshots if they reduce
  main-thread work without creating synchronization bugs.
- Add Macrobenchmark `FrameTimingMetric` coverage for viewer, tower, map,
  orientation, feed changes and navigation. Track slow and frozen frames.

Acceptance: eliminate frozen (>700 ms) frames in steady playback; aim for
fewer than 5% slow UI frames on the S22 Ultra without hiding or suppressing
timing/map updates.

## P1: decoder workload and device pacing

### P1.1: choose the smallest conforming coded atlas

File: `tiled/HevcMixedArchiveAtlasPlan.kt`.

The planner considers candidate grids ordered by tile-cell count and returns
the first valid layout, rather than proving the minimum possible coded
pixel area. Explore and benchmark a bounded best-valid-candidate search
that minimizes `codedWidth * codedHeight`, subject to all HEVC tile,
CTB, crop, slice-header, compatibility and device capability constraints.
Log input coded pixels, output coded pixels, extra atlas pixels and
alternatives rejected by validation. Never substitute GPU output scaling
for a reduction in coded decoder input.

Tests: geometry-optimality fixtures, uneven source renditions, random-access
and cropping cases, synthetic composition, independent decoder verification.

### P1.2: verify display refresh matching

Files: `scene/SingleDecoderSceneView.kt`, `scene/SceneFrameRatePolicy.kt`.

- Log active and available display modes and their actual changes via
  `DisplayManager`; current diagnostics report `NOT_VERIFIED`.
- Compare 50 fps on available 50/100/60/120 Hz modes using physical
  presentation intervals and user-visible judder.
- Consider an explicitly controlled long-form refresh-matching option only
  when a real benefit outweighs black frames from non-seamless switches.
- Keep safe behavior across rotation, PiP and TV mode changes.

### P1.3: quality and thermal policy

Files: `Settings.kt`, `Viewer.kt`, `tiled/HevcArchiveRenditionPlan.kt`,
`FeedPlayer.kt`.

- Differentiate a requested maximum-height cap from actual HLS rendition
  choices per feed; do not present invented rendition availability.
- Report visible, coded and atlas dimensions before and after every switch.
- Offer device-aware suggestions based on decoder size/rate support,
  decoded atlas pixels, display tile area and sustained thermal results.
- Respect each independently selected quality cap; never silently reduce
  quality or start multiple video decoders.

## P2: casting, maintainability, and product hardening

1. **Continuous Cast output:** remove the per-frame
   `PixelCopy -> Bitmap -> GLUtils.texImage2D` round trip in
   `scene/SingleDecoderSceneView.kt` and `scene/ReplayClipEncoder.kt`.
   Develop a verified direct-EGL-to-`MediaCodec` encoder-surface path with
   video windows, timing tower, tracker map and synchronized commentary.
   Keep one decoded source atlas, one composed encoder, clear-content and
   DRM restrictions. Do not substitute a recording feature for live Cast.
2. **App responsibilities:** incrementally split the ~6,100-line
   `AppState.kt` into testable playback, composition, timing and casting
   coordinators while preserving behavior, layouts and state ownership.
3. **Updates:** investigate `UPDATE_REPO="mikeyyy0/apexview"` in
   `app/build.gradle.kts` against this repository's actual owner
   `cooldarshak/apexview`. Correct only after checking the intended release
   source. Continue enforcing matching APK signing and version monotonicity.
4. **Security:** review `android:usesCleartextTraffic="true"` in the
   manifest and narrow its scope where practical for LAN casting. Keep
   entitlement checks, DRM gates, log redaction, backup exclusions for
   `auth.xml`, and secure Cast relay tokens. Consider Android Keystore
   protection for locally persisted F1 tokens.
5. **UI and TV:** maintain existing speed badge location/size controls,
   overlays, accessibility and focus visibility. Add device navigation
   tests for Android/Google TV D-pad, playback sliders, focus recovery,
   tower/map loading, portrait and landscape transitions.
6. **CI:** keep synthetic HEVC assembly/decoding and unit tests. Run the
   assembled Android instrumentation tests on an emulator or device,
   rather than only assembling their APK. Add repeatable compositor
   throughput/allocation benchmarks and signed-APK update checks.
7. **Language choice:** retain Kotlin and GLES. Do not rewrite the app in
   Rust, C++, Vulkan, Flutter or another toolkit for hypothetical speed.
   Benchmark a small Rust/NDK HEVC parsing/composition prototype only if
   CPU profiling proves that binary parsing/rewrite dominates.

## Device validation matrix and exit criteria

Validate on the Samsung Galaxy S22 Ultra and TCL 55C655 Android/Google TV.
Record source qualities from real stream metadata and hardware limitations;
skip only genuinely unavailable renditions with an explicit reason.

| Test | Minimum exercise |
| --- | --- |
| 1080p + 480p + 480p caps | 10 minutes; log actual rendition dimensions and atlas |
| 1080p + 540p + 540p | 10 minutes; composition, GC, BufferQueue and presentation |
| 1080p + 720p + 720p | If streams and codec support it; 10 minutes |
| Three 1080p sources | If compatible/supported; report capability decisions |
| Four feeds | 10 minutes with timing tower and map enabled |
| Switching per-feed quality | Verify output dimensions, audio sync and one decoder |
| Forward and backward seeks | Verify prefetch cancellation and independent IRAP start |
| Pause/resume and audio switching | Verify monotonic timing and synchronized audio |
| Portrait/landscape and TV navigation | Verify surface lifecycle, focus and all controls |
| Sustained playback | Inspect thermals, frame pacing and memory trend |

For every active composed test, require:

- `activeVideoDecoders=1`, `selectedVideoTracks=1`, `hardware=hardware YES`,
  `firstFrame=true` (or an explicit unsupported/protected reason).
- Actual source rendition and coded atlas dimensions in logs.
- Direct physical display-presentation evidence, not just `dropped=0`.
- No recurring BufferQueue timeout bursts; no steady-state frozen UI frames.
- A documented slow-frame percentage, p95/p99 segment production time,
  buffer duration, GC/allocated bytes, output-frame cadence and A/V drift.
- Identical authentication/entitlement restrictions, one-decoder policy,
  existing UI behavior, and in-place APK update compatibility.

## Recommended delivery sequence

1. **Instrumentation baseline:** fix swap timing labels, add stage-level
   timing and Perfetto markers, record matching configurations on-device.
2. **Concurrency and allocations:** land small, independently tested
   segment-coordinator and buffer-processing improvements. Compare against
   the same device trace and synthetic HEVC fixtures after each change.
3. **Frame and UI scheduling:** address the causal bottlenecks found by
   Perfetto, preserving the existing working GLES/decoder architecture.
4. **Atlas and display pacing:** implement only standards-compliant atlas
   improvements and proven refresh-mode policies.
5. **Casting and general hardening:** proceed after steady local playback
   meets the acceptance criteria.

No entry in this document should be marked complete on the strength of a
green GitHub Actions run alone. Real-device visual playback, audio sync and
physical presentation timing remain the final acceptance tests.
