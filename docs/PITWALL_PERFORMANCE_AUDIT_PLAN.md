## Required change-scope matrix (effective after Build 692)

A green build validates compilation and mandatory tests; it never proves
physical phone/TV performance. Every change to scene activation, timing,
CMAF handling, decoding, surface lifecycle, overlays or Cast must account
for the following production paths, with a specific regression test where
behavior can be reproduced off-device:

| Path | Production renderer / transport | Acceptance |
| --- | --- | --- |
| Phone clear HEVC archive | SingleDecoderSceneView, nativeArchiveTiles | Full-canvas per-feed crops, one decoder, proper source order |
| Phone protected CBCS archive, L1 | ProtectedDecoderSceneView secure output | No raw atlas, secure path retained, correct crop geometry |
| Phone protected CBCS archive, authorized L3 | ProtectedDecoderSceneView nonsecure surface | No raw atlas, one decoder, no DRM weakening |
| Phone protected live | ProtectedDecoderSceneView | Host-first scene publication, rolling renditions/seek |
| Android TV playback | Same AppState/Viewer pipeline plus device codec | TV D-pad, focus, resizing, secure/software DRM differences |
| Native Pitwall Cast phone→TV | CastSceneProtocol + receiver GLES and authorized receiver DRM | Four distinct crops, signed scene geometry, overlays/clock |
| Clear HEVC Pitwall Cast phone→TV | CastSceneProtocol clear relay or allowed baked screen capture | Full 4-feed canvas, no raw atlas, overlays captured if baked |
| Baked Cast scene | One encoded picture of phone window | No second atlas mapping, audio+overlays synchronized |
| Receiver clear/protected EGL | PitwallReceiverActivity | Actual first GL output swap before RENDERING acknowledgement |

**Follow-up to Build 692:** The protected archive ordering correction is
now extended to clear archive and protected live composition. The same
observable host state is shared by the Android and Android TV app. The
Cast receiver already rejects PLAY without a scene, and the versioned
protocol preserves source rects; a new four-feed encode/decode test
asserts source identity and geometric parity through the receiver
protocol, including window repositioning. Runtime receiver logs now
include atlas, window count, decoder count and renderer type.

Remaining hardware acceptance: clear HEVC on S22 Ultra and TCL, protected
archive/live on both (subject to entitlement and TV capability), native
Pitwall Cast L1/L3 and clear baked, plus animation/tower freshness after
20+ minutes, seek, layout change, 1× and 1.5×. None are signed off by CI.

## Build 691 on-device single-decoder raw-atlas display failure (22 November 2026)

**Sign-off BLOCKED.** Screenshot at ~10:55:47 shows all four feeds in a tiny
top-right tile, with the rest of the intended main/right video windows black.
The accompanying phone log confirms a successfully decoded four-feed
1920x2176 HEVC atlas, one Qualcomm hardware decoder and a host player
surface of 165x188. It is not a missing camera feed or timing-map problem.

Root cause in the UI activation path: replaceHost assigned the observable
`protectedRollingScene` *before* calling `showProtectedCompositionPresentation`.
That function assigned `protectedCompositionHost` into a plain, unobservable
Kotlin field. Compose could recompose on the scene write while the host was
still null, retain `TileView(videoOnly=true)`, and then skip the canvas
subtree when the plain host field changed. The old small PlayerView rendered
the entire coded atlas until an unrelated re-layout finally created the
full-canvas `ProtectedDecoderSceneView`.

In the observed log, `F1_PROTECTED_SCENE_PREPARED` appeared at 10:55:42,
but `SURFACE_SCENE_EGL_READY` only at 10:55:50, after the screenshot.
Earlier composition attempts likewise logged active playback but lacked a
full-canvas EGL-presenter confirmation. This differentiates a renderer
lifecycle failure from valid CMAF composition.

Coordinated fix:
- Make `protectedCompositionHost` observable via `mutableStateOf`.
- Activate `protectedRollingScene` only after the host is registered and
  composed playback is installed, using explicit host identity assertion.
- Emit `F1_SCENE_UI_ACTIVATED` and `F1_SCENE_UI_BOUND` evidence with
  atlas dimensions, source count, actual output canvas and mapped windows.
- Add a source-wiring regression check; preserve existing 1920x2176
  four-camera UV/crop tests, one-decoder gate, Widevine, native layout,
  movable map and timing tower.

This code fix needs CI and a real S22 Ultra follow-up; no hardware GFX
sign-off is asserted. A successful decoder-first-frame log *by itself* is
not adequate evidence of an attached full-canvas surface.

# Pitwall TV: playback performance audit and implementation plan

### Build 686 instrumentation failure: Android binder not yet stable

The source/unit test suite, HEVC fixtures, signed debug APK and Android
instrumentation APK compiled successfully, but the emulator reported
`DeadObjectException` immediately when `am instrument` contacted the
activity service shortly after `sys.boot_completed=1`. No native test
assertion failed and this run is **not** green. The shared instrumentation
script now waits for sustained ActivityManager and PackageManager binder
readiness and allows a single repeat **only** for a binder death before
any test has reported a status code. It still requires the exact
`OK (2 tests)` RenderNode and `OK (1 test)` SurfaceView markers.
Neither test is disabled, skipped or given a synthetic result.

## Pitwall Cast receiver overlay parity: follow-up to Build 688

Real TCL receiver observation: track-map cars are visibly jerky and timing
tower values do not update despite smooth phone overlays. This is an observed
**Cast parity failure**. Do not sign off map/tower, casting, or P2 hardware
acceptance based on CI.

Source audit found that the native receiver ran a 30Hz Compose map
recomposition clock in addition to its existing draw-phase car clock.
The receiver now uses its most recently accepted packet for Compose hit
targets and drives car motion solely from the receiver's video-position
draw clock. Tower tables are also separate observable state, so a valid
timing-table packet can update the tower without waiting for the next
accepted car-motion frame. The sender samples full tower rows every 500ms,
while retaining 200ms car-motion packets and 60Hz draw-phase animation,
to reduce allocations and repeated full-row updates on the TV.

The sender/receiver now log bounded 5-second TIMING_TX_HEALTH and
TIMING_RX_HEALTH summaries with accepted frame/table counts, stale ages,
rejected frame reasons and clock epochs. The receiver also relays an optional
TIMING kind=health packet to the phone over the already-negotiated Pitwall
extension so diagnostics can be recovered from the phone alone. This is an
additive report that older senders ignore; it carries only scalar counters
and the active playback ID, not credentials or video data. The phone no
longer treats a single initial TIMING_UI_DRAWN event as proof of sustained
freshness. A same-version 5-second table heartbeat refreshes transport freshness
without reparsing or rerendering an unchanged tower. Healthy map/tower status requires continuously fresh frames and
periodic table snapshots while video is playing. This distinguishes remote transport,
timeline and renderer problems if TV still freezes. No packet schema,
Widevine, codec, Cast format or driver timing semantics are changed.

Verification: new receiver table-independence regression, existing Cast
protocol/seek tests and mandatory Android instrumentation. Real receiver
smoothness and timing freshness remain **OPEN** pending TCL observation
and measured diagnostics. The old Build 674 traces cannot validate this
new receiver-side change.

## Post-Build 674 coordinated engineering pass (22 November 2026)

**Working branch**: `feature/pitwall-performance-audit-plan` (never create a replacement).
**Real-device baseline**: Build 674 on Galaxy S22 Ultra, app log
`pitwall-20261122-031751-489.log.txt` and two separately supplied Perfetto
traces for approximately 08:49 and 08:50 local time.
**CI and actual hardware acceptance remain independent**. This checkpoint
describes source changes, NOT validated end-to-end smoothness.

### Baseline evidence and diagnosis

- The authorized Las Vegas qualifying HLS/CBCS stream passed protected tile
  alignment (288 pictures per feed, zero IV mismatches) and started **one**
  `c2.qti.hevc.decoder`, Widevine L3, with four rendered source crops. The old
  CBCS-protected stream initialization failure is no longer the first blocker.
- At `1080p + 1080p + 1080p + 540p`, the composed picture was
  `3840x2176`. Codec drops reached 857 amid several buffering intervals,
  severe app-window jank, SurfaceTexture queue timeouts and scene display
  gaps. A second `1080p + 540p + 540p + 540p` selection used
  `1920x2176` and had healthier 5-second presentation intervals, but was
  not entirely drop-free. These quality combinations are distinct workloads.
- The log records user playback speed `1.5x`; physical 50fps acceptance
  at `1x` and achievable 75fps delivery at `1.5x` must be assessed
  separately. BufferQueue timeout events alone do not prove codec failure.
- Overlay samples in the same recording show occasional maximum
  `track-car-flag-draw` duration of 35.3ms and tower sampling of 30.9ms.
  Source timestamps and actual SurfaceFlinger FrameTimeline associations
  are required to assign causality. Independent overlay sign-off is OPEN.
- Full binary Perfetto analysis was **not completed in the present tool
  environment**. Do not infer SurfaceFlinger physical presentations,
  layer-specific late flags or per-thread running time from app timer probes.

### Coordinated source changes after Build 674

1. **Onboard UI**: replace the separate top speed pill and bottom driver
   card with one position/number/driver/lap/gap/speed row. Retain the same
   real green/purple/yellow sector data immediately beneath it, with grey
   for missing sectors. Display a speed value even if lap timing has not
   arrived. Add scale, feed-local clamped X/Y offsets, top-left/right anchor,
   independently adjustable background opacity and reset; persist all
   settings in named-layout save/load/export/import and device backup.
   TV D-pad navigation continues through the existing AppleSlider controls.
   Do not alter the timing data model, sector semantics or Cast wire format.
2. **50fps pacing**: a user-selected playback rate now participates in the
   rolling SurfaceView cadence gate and Android's frame-rate hint. A 50fps
   source played at 1.5x should not be force-padded to a 50fps display gate.
   Never throttle the SurfaceTexture latch on behalf of the display gate.
3. **Protected composition polling**: make immutable session-index selection
   and volatile status/readiness diagnostics visible without entering the
   global long-running CBCS composition critical section. Media sample
   ownership, encrypted zeroing and fragment copies remain synchronized.
4. **Startup/rebuffer lead**: protected rolling source transfer now requires
   at least 6s at 1x, 9s at 1.5x and 12s at 2x (limited by archive EOF)
   rather than a nearly expired first fragment. Keep source selections
   unchanged, and do not silently lower quality to avoid overload.
5. **Main-thread isolation**: run CMAF output window copies, preflight DASH
   package validation and the rolling protected DASH resource publication
   on IO dispatchers; leave all ExoPlayer operations on their established
   main/application thread. Immutable package generations and source-side
   lease retirement continue securely zeroing encrypted bytes.
6. **Profiling**: add trace marks around CBCS per-picture composition, 1Hz
   GL-looper scheduling delay probes and additional EGL/frame-latch
   diagnostics without expensive logging on each frame.
7. **Protected TV live**: reuse the same independently cropped four-window
   SurfaceView scene as archive playback, with validated per-rendition
   geometry, and do not copy/republish protected CMAF output during polls
   that have not advanced the actual composed-media version.

### Regression and sign-off gates

- JVM/native CI must pass exact compile, source HEVC/DRM composition tests,
  screen rendering instrumentation, Cast protocol tests and APK signing.
  New tests target feed-local badge geometry, named-layout persisted fields,
  accelerated display pacing/hints and speed-relative startup lead.
- **Device playback**: same 10-minute authorized session and feed qualities
  on Galaxy S22 Ultra at 1x then 1.5x; compare both existing quality mixes.
  Require one hardware decoder, correct independent four-feed windows,
  no repeated buffering, and real physical presentation stability.
- **Overlay-only contribution**: same stream/clock, identical playback speed,
  compare OFF, map, tower, map+tower; inspect RenderNode recording,
  static geometry invalidation, GPU/RenderThread and physical FrameTimeline.
  UI slow frames target <5% in stable conditions with no sustained freeze.
- **TV**: repeat on TCL 55C655 with correct Widevine protection,
  remote focus/controls and no decoder over-allocation. Independently
  verify native Pitwall Cast, clear baked Cast and receiver-side timing.
- **Non-negotiable**: no proprietary HEVC SDK, no OpenF1 paid dependency,
  no multi-decoder fallback, no DRM bypass or silent source-quality changes.
  No code-only sign-off is possible; the physical acceptance statuses
  **remain OPEN** until the supplied traces are decoded and post-change
  device measurements are available.

## Coordinated clear/Widevine optimization and named layout library

The current branch shares bounded parallel source acquisition, low-priority
HEVC composition, cached HEVC metadata/atlas geometry, rolling CMAF media
ownership, 50fps frame demand/pacing, and CPU/memory/GC diagnostics across
clear and protected playback. The encrypted CBCS/Widevine composer and
finite-clear seek scheduler remain intentionally distinct to preserve
security and valid fragment continuity.

The driver timing card now uses a compact version on narrow onboard
windows in every layout. Named layouts save video/map/tower geometry,
support load/rename/delete/default and JSON import/export, and migrate
old arrangements. Android Auto Backup restore depends on the system
backup transport and is not guaranteed after uninstall.

Full side-by-side implementation audit:
docs/CLEAR_WIDEVINE_PARITY_AND_NAMED_LAYOUTS.md.
Real licensed F1 playback and physical 50fps/overlay sign-off remain open.

## Build 643 severe lag: independent video surface and bounded CMAF read memory

Real device log: one hardware HEVC decoder and four correct source crops,
but a TextureView ties the rolling L3 composed scene to a severely
janky app UI. During active playback the GL scene latches ~240–250 frames
per five seconds, while its output presents only ~200–230. At the same
time the main UI shows 300–994ms frames, massive GC activity and
frequent BufferQueue timeout logs. Capture workers take ~1.9–3.3s
composing a new fragment. Sliding-window player-position discontinuities
can be legitimate relative-timeline rebases, and are **not** treated
as proof of a seek bug.

Correction: reuse the established SurfaceView scene path for authorized
Widevine L3, with an independent SurfaceFlinger output layer.
L1 retains strict protected EGL/secure SurfaceView. No second player,
codec or DRM workaround. Reduce in-flight media buffer allocations using
zeroizing lease-backed CMAF resource generations; retain old generations
until their last Media3 reader closes. Add resource lifetime tests,
clear synthetic SurfaceView PixelCopy instrumentation and video pacing
logs. This is stream-agnostic, not keyed to Vegas/Sao Paulo IDs.

Full evidence and limitations:
docs/BUILD_643_LAG_AND_SURFACE_RENDERING_DIAGNOSIS.md.
Phone/TV graphics sign-off remains OPEN.

## Build 637: real one-decoder protected playback achieved; scene presentation was broken

Device log and screenshot show actual four-source CBCS media decoded into
one 1920x2176 hardware HEVC picture at 50fps, 975 rendered frames by
23:05:55, no reported decoder drops. The app incorrectly treated the
tall tile atlas as one ordinary video, hid other visual windows and
forced the diagnostic layout. Layout changes then deliberately cancelled
the protected rolling capture and reverted to source streams, matching
the 23:05:56 codec/timeline discontinuity. Repeated BufferQueue
timeouts also block rendering sign-off.

The existing one-decoder GPU source-cropped scene renderer is now
wired into the protected rolling path with independently validated
atlas rectangles and frame rate, secure EGL for L1, unchanged authorized
L3 handling, stable original TV layout and rolling rendition updates.
No HEVC/DRM bypass or extra video decoder was introduced. A full
analysis is in docs/BUILD_637_PROTECTED_SCENE_DIAGNOSIS.md.
Hardware graphics sign-off and Cast parity remain OPEN.

## Build 632 protected DASH handoff failure and coordinated correction (21 November 2026)

Build 632's Las Vegas qualifying protected four-feed capture has now been inspected
alongside the fresh 22:39:55 Perfetto trace. This is a new confirmed defect beyond the
earlier encrypted-slice header work. At 22:39:15.717 all 288 pictures pass CBCS
key/IV/shape identity and encrypted-byte preservation checks. The stream is
successfully composed, but at 22:39:16.855 the composed DASH source rejects
the rewritten init: "Protected init tenc defaults are invalid", repeated three times.

Source audit found the cause. The CMAF inspector correctly parses version-1 tenc:
FullBox version/flags, reserved, CBCS pattern, default_isProtected, IV size,
default_KID. The separate DASH default-KID reader omitted the reserved field,
so a common CBCS 0x19 pattern was treated as isProtected and failed validation.
Both version 0 and version 1 need the reserved field. The MPD builder now checks
the raw defaults against the independently validated CMAF track and verifies
the extracted KID fingerprint before emitting cenc:default_KID.

Regressions now cover real synthetic HEVC init rewrite through version 0 and 1
tenc, protected media fragments, Widevine PSSH, full DASH manifest generation
and the actual Media3 DASH manifest parser. Cast's encrypted audio path shares
the same helper, so the parser retains support for its single encrypted audio
track and cenc scheme, covered by a separate synthetic audio init regression.

The trace records 1,175 actual Pitwall app surface frames with PresentType
PRESENT_LATE and 1,001 with BufferQueue stuffing. The app's own 27 frame windows
record 2,009 frames, 757 slow (37.7%), three frozen and max 1,143 ms at 120 Hz.
The capture includes startup and one-feed fallback, not successful four-feed
protected playback. Map and timing tower graphics acceptance remains OPEN.

CBCS independent-sub-sample cipher chains are required to preserve the same
sample IV and crypto pattern with each source encrypted range; those checks
remain mandatory, and ciphertext is not decrypted or re-encrypted.
Real hardware Widevine + one-decoder first frame, four-feed playback stability,
Cast parity and overlay smoothness remain UNVERIFIED by CI.

## Build 629 protected CABAC splice follow-up (21 November 2026)

Build 629's S22 Ultra log confirms that the Build 627 protected init signalling failure
is cleared, but protected Las Vegas qualification still fails before composed decoding:
`REAL_PROTECTED_SINGLE_SENC_PREFLIGHT` reports 288 aligned pictures with matching
CBCS metadata, followed by `F1_COMPOSITION_FAILED` at POC 0 because feed 0
`channel-1033` does not expose three clear CABAC bytes. The active protected tile
rewriter expected three clear CABAC bytes even when the encrypted tail begins
immediately after the complete clear aligned slice header.

The coordinated follow-up introduces a guarded header-only splice for these CBCS
samples. It copies the entire opaque CABAC EBSP suffix unchanged, retains its
CENC/CBCS protection ranges and validates the nonzero HEVC byte-alignment terminator
on both source and rewritten headers. Existing three-clear-byte reconstruction
remains unchanged for its eligible inputs. Synthetic tile-feed tests cover 0/1/2
clear CABAC bytes, unchanged suffix bytes, output tile identity and comparison to
a fully clear rewrite. Source DRM metadata and all pre-existing fail-closed encryption
checks remain mandatory. This does not prove that a protected composite can be
decoded by the real hardware, and does not establish map/tower graphics sign-off.

The supplied 22:14 trace spans device-uptime 414066.196–414100.147 s,
and includes the protected failure at device-uptime ~414076.895 s. There is no
successful protected composed playback in that interval. The app log also reports
496 UI frames, 239 slow frames, three frozen >700ms, and max 1507ms frame.
These are startup/failure-window measurements rather than steady-playback figures.

## Build 627 protected-init correction and graphics acceptance

See [Build 627 engineering diagnosis](BUILD_627_VEGAS_AND_OVERLAY_DIAGNOSIS.md).
The Vegas protected composition refusal was traced to invalid `List<ByteArray> == List<ByteArray>` identity comparison of unchanged CBCS protection boxes. Preserve bytewise protection checks and add real writer regression coverage. Hardware acceptance remains **PENDING**, independently of CI. Raw Build 627 Perfetto protobuf evidence indicates widespread app-surface late-presentation and BufferQueue stuffing flags; see the linked analysis for exact trace windows and counts.

---

Status: active implementation and hardware validation; progress audited through Build 598 (21 November 2026). Original requirements below remain open until their stated exit criteria pass.


## Build 621–624 GitHub Actions failure root cause and corrective CI gate (21 November 2026)

**Build 626 correction:** the emulator reached boot-completed, so KVM selection
is no longer the blocker on that runner. The runner executes its custom
`script:` value with `/bin/sh -c` and did not preserve the multi-line
`if/then/fi` block; it failed with an unexpected end-of-file before running
instrumentation. The native command sequence is now a separately checked-in
Bash script invoked by **one** action script command. It captures the Android
test report, prints failures and checks the explicit `OK (2 tests)` footer.
Source-wiring tests assert the script is called and still runs both tests.

Four failing runs had different causes, all before native overlay acceptance: 621
could not boot an emulator without KVM; 622 failed unit-test Kotlin compilation
(subsequent runs passed the unchanged suite); 623 booted the emulator but
invoked POSIX /bin/sh with unsupported `set -o pipefail`; 624 aborted at a
mandatory KVM preflight despite passing JVM, Cast, HEVC, APK and AndroidTest APK
assembly. The Android graphics code was **not** established as failing.

**Integrated correction:** Keep the instrumentation test mandatory and its
report uploaded. Detect an accessible /dev/kvm and use hardware acceleration
when available; when the GitHub-hosted runner has none, opt in to Android
emulator software TCG and allow the runner's automatic acceleration selection.
Use API 29, the minimum RenderNode API, with a lightweight Pixel 2 profile,
900-second boot cap, and a POSIX-safe instrumentation script that prints
test output before returning a nonzero status on failure. The application and
instrumentation APKs are built regardless of an emulator-host failure.
No CI test is skipped, marked advisory, or silently interpreted as passing.

A green emulator test verifies native **RenderNode recording/replay semantics**,
not hardware GPU speed, physical 50fps frame presentation, Widevine L1, or
TCL-specific acceleration. Those acceptance gates remain pending until tested
on the target devices.

---

## Consolidated post-620 renderer and Cast integration correction (21 November 2026)

**Current integration status:** code integrated; CI and physical-device acceptance are reported separately.
This does not constitute phone or TV performance acceptance, a Google review,
or a verified protected-video rendering benchmark.

### Findings and corrections

| Production issue | Integrated correction | How it is tested |
| --- | --- | --- |
| Ending SC/VSC flag pulse used `rememberInfiniteTransition` in the composition and put `pulse` into the track/edge event-layer key, causing display-list re-recording for animation | Replace with a 60Hz-capped Choreographer draw-only pulse state. Isolate the dynamic flag outline and caption between cached circuit and event annotations. The retained event list no longer has a pulse dependency; normal flag colour, EaseOut curve and 1100ms reverse timing are retained | `OverlayFlagPulseTest`, `scripts/test_overlay_runtime_wiring.py`, 5s `track-flag-pulse-draw` and `edge-flag-pulse-draw` |
| `TowerRowText.prepare` generated a detailed `description` string at each text refresh, although live TalkBack semantics came from `TowerAccessibilitySummary` | Keep visible speed, gear, sectors, tyre and row text unchanged. Remove unused duplicate description formatting and avoid refreshing row text when only tyre visuals change. Continue stable, focusable, clickable accessibility labels | `TowerRowTextTest`, `TowerAccessibilitySummaryTest`, `scripts/test_overlay_runtime_wiring.py` |
| Registered Google CAF WebGL receiver renders layouts, not the native timing map/tower; default Google receiver renders only encoded video grid. Previously either could silently omit overlays | Central `CastOverlayDeliveryPolicy` classifies baked clear stream, native Pitwall TV overlays, Google video layout, and Google video grid. If map/tower requested on Google video-only paths, require explicit user opt-in with an alternative to use Pitwall Cast. Preserve video-only mode as a deliberate fallback. Report selected mode in `CAST_OVERLAY_ROUTE` | `CastOverlayDeliveryPolicyTest`, source wiring checks, existing Cast protocol and receiver checks |
| Protected/live video cannot legally be PixelCopied or baked on the phone with this architecture | Preserve native single-decoder CMAF relay and legitimate TV-side Widevine. In protected/local mode, keep separately drawn synchronized map/tower on Pitwall Cast Android TV; do not mislabel this as a phone-baked picture. Refuse to imply full overlays on a receiver lacking the timing capability | Cast policy tests, receiver timing tests, existing HEVC/DRM checks; real-device acceptance remains open |
| Native Android RenderNode tests were assembled only | Run `OverlayRetainedRenderNodeAndroidTest` on an Android 35 emulator from GitHub Actions with explicit KVM hardware acceleration and upload its output. A failure makes CI red rather than claiming executed graphics tests from APK assembly | GitHub Actions KVM preflight, emulator instrument command and report, plus source wiring checks |

### Requirements for real engineering acceptance

- CI must pass the complete JVM/HEVC/Cast suite, source wiring checks, APK assembly **and emulator RenderNode instrument execution**. Emulator tests are not substitutes for actual S22 Ultra or TCL graphics performance.
- Real S22 Ultra four-feed 10-minute controlled A/B runs: overlays off, map only, tower only, both on, with the same video quality and refresh mode. Compare trace and no-trace sessions and physically presented video frames.
- During ending SC/VSC flags, event display-list `changed` must remain zero after the flag transition even while `track-flag-pulse-draw`/`edge-flag-pulse-draw` samples advance. Visual flag, caption and stop controls must match the previous build.
- Validate all three map modes; complete timing rows/mini-sectors; accessibility, focus and TV D-pad; move, resize, rotation, lock, opacity, seek, pause and rejoin.
- Cast acceptance is separate for **clear baked video+commentary** and **protected native video with receiver-rendered timing**. Google CAF's custom layout receiver is not a full timing renderer. Google default is video-only by deliberate choice. No DRM bypass, no user-visible unsupported feature claims.
- Segment-allocation pressure, SurfaceTexture buffer timeouts, video-only slow frames and protected playback remain separate, unresolved workstreams. No sign-off claim is valid until device measurements meet the acceptance gates.

---




## Post-Build 602 overlay engineering review and acceptance gate (21 November 2026)

**Baseline:** Build 602, commit `a855001247f675a49ae1471b12305a80920f9f2d`.
**Branch:** `feature/pitwall-performance-audit-plan`.
**Current status:** additional code corrections and automated regression tests implemented.
**NOT device accepted:** CI and real S22 Ultra / TCL acceptance must be reported separately.
No external company, including Google, has performed a code review or approved this app.

### Source audit findings and fixes

| Source finding in Build 602 | Corrective implementation | Regression evidence |
| --- | --- | --- |
| `Laptop.kt` live TimingTower still rebuilt cards, speeds and tyre maps every 250/500 ms; the previously implemented `OverlayTowerSampler` was **unused** | Wire one session-scoped sampler directly into TimingTower's background worker. Publish only changed immutable TowerSamples. Reset display sample on session changes; capture updated speed-map snapshots on the main thread before worker handoff | `OverlayTowerSamplerTest`, `TowerSampleVisualTest`, existing Compose accessibility tests |
| The same `OverlayPositionSampler` advanced through independently scheduled 30 Hz UI and 60 Hz map drawing clocks. Build 602 reported frequent seek resets despite normal forward playback | Give track and edge hit-target timelines independent sampler cursors from their draw-phase timelines. Preserve exact track/cast position and gap rules | `OverlayPositionSamplerTest`, `OverlayGeometryParityTest` |
| Position identity reused `previous.getOrNull(sourceIndex)`, which becomes wrong when the published list skips a leading driver | Cache the previous immutable Car **per source entry**, not by compacted display index. Unchanged following drivers retain stable identity | `OverlayPositionSamplerTest.droppedLeadingDriverDoesNotInvalidateUnchangedFollowingDriver` |
| Each of the three native RenderNodes occupied the complete row width, even when only sectors or tyres changed | Keep full-width base layer for highlight; record sectors and tyres in narrow conservative physical bounds with full-row coordinate translation. Calculate bounds only on dirty recording, not steady frame replay. Software Canvas fallback unchanged | `OverlayRetainedDrawingTest.nativeRegionBoundsCoverExistingColumnsWithoutThreeFullRowLists`, native instrumentation RenderNode tests |
| The pit circle repeatedly scanned each driver's original position history, including a second scan in Cast after the sender had already sampled the same frame | Feed the pit-circle calculation a chronologically sampled immutable car list. For local display use a session-owned `OverlayPositionSampler`; for Cast reuse the already-produced sender snapshot. Keep the legacy function default and identical pit-gap/ghost/collision models | Existing `OverlayPositionSamplerTest` and Cast protocol tests, real device map/pit-circle parity pending |
| Hardware measurements absent for the new integration | Preserve five-second `OVERLAY_5S` fixed-bin probes. New owners: `phone-tv-tower-sample`, `phone-track-draw-position`, `phone-edge-draw-position`; unchanged trace labels around tower snapshot and map composition | Build logs and Perfetto trace from device, **pending** |

### Non-negotiable visual and feature contract

Maintain every previously supported map mode (normal, edge, pit circle), identical car positions,
retirement/gap rules, 44dp selection/focus targets, labels, sector and tyre colours, all three
mini-sector strips, remote D-pad, driver-selection actions, panel resize/move/rotation, lock,
opacity, text sizing, pause, seek and reverse. Keep original authorized subscription/DRM,
single HEVC decoder, commentary sync, Pitwall Cast timing protocol and sender/receiver
connections. No second player, additional video surface, screenshot of protected content,
proprietary SDK or alternate timing provider.

### Measurable engineering acceptance, NOT a code-completion percentage

1. **Automated green gate:** the newest full workflow on the resulting branch must pass
   Kotlin unit tests, independent synthetic HEVC validation, Cast protocol tests,
   `assembleDebug` and `assembleDebugAndroidTest`. A CI pass alone does not prove graphics.
2. **Profile the same 10-minute archive** on S22 Ultra with identical feed sizes, quality,
   layout and refresh settings in 4 conditions: overlays OFF, map-only, tower-only and both ON.
   Use non-tracing runs alongside Perfetto runs to quantify instrumentation overhead.
   Compare to Build 602 and (separately) Build 585 video-only measurements.
3. **Overlay acceptance:** position seek resets should be zero during steady forward playback
   (except genuine replay seeks/discontinuities); static circuit re-records should stay zero
   between map/layout changes; unchanged tower records should be replayed. Track p95 and max
   by `OVERLAY_5S` owner; identify and investigate every repeatable >16ms overlay spike
   rather than declaring it a GPU stall solely from CPU timers.
4. **Visual parity:** compare Build 602 and the new build in all three map modes, selected and
   retired drivers, yellow/VSC/SC/red flags, active/restarting session, lap/gear/speed/tyre
   changes, sector strips and accessibility. Verify touch and D-pad hit-target positions
   when map moves and resizes; verify opacity and text placement on both devices.
5. **UI/physical presentation:** independent `FrameTimeline`/SurfaceFlinger actual
   presented-video cadence and app UI deadline data are necessary. Target fewer than 5%
   slow app UI frames with both overlays on in a stable representative session, and
   no sustained freezes. Map/tower CPU timers do **not** establish physical 50fps.
6. **TCL 55C655 and Pitwall Cast:** ten-minute local playback and Cast receiver checks
   with map/tower, selected commentary, seek/pause, focus/resize/lock, and decoder count.
   Cast wire format remains v1. Hardware sign-off stays **PENDING** until observed.

**Scope separation:** the November 21 trace also showed segment allocation/GC pressure,
SurfaceTexture buffer timeouts, quality-change restart and UI slow frames with overlays
disabled. These broader pipeline issues remain separate P0/P1 investigations. Do not
claim that overlay corrections alone fix protected playback, buffer backpressure,
physical presentation or video-only stuttering.


## P0.4 integrated overlay-engine redesign — 21 November 2026

**Implementation branch:** feature/pitwall-performance-audit-plan. This is a
single, coordinated source commit and a single CI gate, not sequential small
APKs. **Status before device test:** implementation and regression suite
prepared; **physical smoothness and Cast parity are not yet accepted**.

### Architectural goal and compatibility contract

Retain the existing Compose layouts/controls, Canvas drawing vocabulary,
Media3/GLES video ownership and Pitwall Cast protocol. Separate relatively
slow timing-source changes from high-frequency car animation and avoid
rebuilding identical timing snapshots. All values remain driven by the
authorized F1 timing clock. Do not introduce another Surface, decoder,
PixelCopy operation, proprietary SDK or a second F1 timing provider.

- New **OverlayPositionSampler** owns one independent cursor per driver and
  advances through genuine F1 position arrays in source chronological order.
  Continuous video frames use a bounded forward cursor; large seeks and
  backward seeks use binary search. The contract exactly preserves
  DriverTrack.atOrFirst's leading position, end-of-data disappearance, >5 s
  gap handling and interpolation. Each publication is immutable and owned
  by its display or Cast sender, never shared across threads. Existing
  driver selection, PIT long-press, TV D-pad hit targets, dot sizes and lap
  colour state continue using the original Car/drawing interfaces.
- New **OverlayVertexIndex** builds a balanced, immutable nearest-circuit-
  vertex index once per session outline. TrackGeometry.nearestIndex no
  longer linearly scans every circuit vertex for each moving car. Distance
  and equal-distance tie behavior match the original exhaustive search.
  TrackGeometry.pointAt uses bounded distance binary search while
  preserving exact corner/direction semantics. Both track and edge map
  still render via the same static/dynamic Canvas layers, preserving
  marshal sectors, flags, start/finish and driver abbreviations.
- New **OverlayTowerSampler** reuses the previous immutable CardState
  when the lap snapshot index has not changed, without freezing fresh
  speed, gear, tyre, position, sector, lap, flag, countdown or weather
  data. It publishes the SAME TowerSample instance if pixels/fields
  are unchanged. Existing single-Canvas TvTowerRow, Offscreen hardware
  layers, talkback summary, focus, scrolling, text-size/opacity/resize
  controls and complete three-sector mini-sector strips remain unchanged.
- **Pitwall Cast compatibility:** CastTimingPublisher owns its own
  OverlayPositionSampler following the receiver's video clock. It keeps
  CastTimingProtocol v1 frames, 200ms position future endpoints, table
  wire format and CastTimingReceiver decoding/TrackDrawingContent/
  TimingTowerContents completely unchanged. Live geometry, maps and
  towers still draw from the same visual functions as phone. No
  downgrade to 1-feed or separate per-camera decoder fallback.
- **No per-frame logging:** OverlayPerformanceProbe reports aggregate
  fixed-bin latency histograms every five seconds. Probe owners distinguish
  phone track/edge position sampling; track/edge static and dynamic draws;
  phone/TV tower snapshots and visible tower-row draw; and the Cast
  sender's position sampler. Each OVERLAY_5S line includes sample count,
  mean entities, changed objects/rows, seek resets, mean microseconds,
  p95 bucket bound and max microseconds. No camera positions, driver IDs,
  F1 account information, URLs, encryption metadata or raw packets are
  logged. Existing Perfetto trace markers remain.

### Integration and regression checklist

| Category | Tests / evidence | Must remain true |
| --- | --- | --- |
| Position model | OverlayPositionSamplerTest | Same positions/states/order vs carsAt and DriverTrack.atOrFirst over dense 50fps samples, forward/backward seek, gaps, start/end boundary and stationary dot identity |
| Circuit geometry | OverlayGeometryParityTest | Indexed nearest vertex exactly matches exhaustive search over thousands of random locations and equal-distance ties; pointAt coordinates/directions remain equivalent |
| Tower model | OverlayTowerSamplerTest and TowerSampleVisualTest | Identical timing rows share state; speed/gear, pit status, tyre age, S1/S2/S3 mini-sector sequence and backward seek invalidate as appropriate |
| Diagnostics | OverlayPerformanceProbeTest | Bounded memory, low-rate 5-second reports, no per-frame log storm or sensitive positional values |
| Pitwall Cast | CastTimingProtocolTest with sender/receiver timeline parity, existing Cast tests | No protocol version, geometry, wire fields, source interpolation, focus, audio/clock or receiver API regression |
| Android CI | Kotlin/JVM/HEVC independent decoder and signed APK workflows | Tests pass, no hidden multi-decoder fallback, persistent signing preserved |
| Real S22 Ultra | 10 min with four actual F1 feeds; OVERLAY_5S, Perfetto FrameTimeline, GC, BufferQueue, codec/GL cadence | Each overlay incurs clearly measured incremental CPU/frame cost, correct timing/video sync, no loss of controls |
| Real TCL 55C655 | single feed + map+tower, then two/four feeds, ten-minute tests with D-pad, seek, lock/resize and Cast receiver | No laggy/frozen marker or tower updates, focus/selection intact, one video decoder |
| Cast receiver | Real Pitwall Cast from S22 to TCL with tower/map, selected commentary and seek/pause | Receiver positions, table rows and video clock agree; correct geometry and no additional receiver decoders |

### Explicit scope and next hardware gate

**Historical note (Build 600):** this initial retained-data and geometry
batch did not yet replace the full recording path. The next integrated
stage below implements explicit retained circuit/flag drawing, RenderNode
tower regions and draw-phase map motion. Neither source-code stage is
a substitute for the hardware acceptance gates.

**Acceptance remains unmet until real-device results:** compare the
overlay OFF, map only, tower only, both ON states on the same feed quality,
layout and device mode. Distinguish UI app-frame deadlines from actual
video pictures presented. Keep the established Build 585 video-only miss
rate as a separate baseline and do not claim that this overlay batch fixes
video-only scheduling or physical 50fps output.

---

## Integrated full retained-overlay implementation — after Build 600

**Scope:** one coordinated branch commit covering track map, edge map,
pit-circle static geometry, tower row drawing and Pitwall Cast receiver.
**Implementation status:** code and tests staged together; record final
CI/build number on successful Actions completion. **Hardware status:
not yet accepted**. This section supersedes Build 600's description as
an *initial* data-sampling-only optimization.

### Map and timing-tower rendering contract

1. **Map motion is a Draw-phase state read, never a 60/120 Hz Composition
   read.** `rememberOverlayDrawClock` samples the authorized video clock on
   Choreographer frames with a 60 Hz cap. `OverlayMotionSource` updates
   only the moving-car Canvas in the map and edge-map. Existing 30 Hz
   Compose publication is retained for hit targets, live flag captions,
   pit-stop selection and TV focus. This avoids touching/losing input
   semantics while stopping the faster motion clock from recomposing
   the map tree. Playback pause/reverse/seek follows the same clock; no
   positional guesses are invented across missing timing data.
2. **Retained map display lists** use `rememberGraphicsLayer` and immutable
   explicit invalidation tokens. Track and edge maps each have three
   separate rendering layers: **static circuit**, **flag/pit-event layer**,
   and **moving cars/driver abbreviations/safety-car**. Circuit drawing
   instructions are recorded only for changed geometry/density/panel
   dimensions. Flag/events only re-record for flag/sector/pulse/stop
   changes; identical new FlagPicture objects do not force a redraw.
   Opacity/rotation/move/resize remain controlled by the existing
   graphicsLayer and panels. No screenshots of protected content.
3. **Edge map** uses a reusable insertion-sorted primitive projection
   buffer rather than allocating Pair collections and sorted lists for
   every high-rate redraw. Tie order, collision rows and label offsets
   match the existing 44dp hit targets and label rules.
4. **Native RenderNode tower** (API 29+) records each visible tower row
   into three independently invalidated regions: (a) position, team,
   speed/gear, lap times and gaps, (b) full S1/S2/S3 mini-sector
   colours and times, and (c) tyre compound/age. Changing a sector
   does not re-record speed/gear or tyre. Recorded layers are replayed
   without re-measuring or drawing those text and bar commands. Native
   Paint/Android Canvas coordinates and existing Offscreen row isolation
   are retained. Software fallback executes those **same** commands.
   Focus ring, touch, D-pad, accessibility and scrolling stay on the
   existing clickable Compose row.
5. **Pit-circle** caches dark circuit background and turn/start-finish/
   inner-lane geometry in separately recorded display lists, with
   pit-window arc positioned below the outline as before. The complete
   retained/static/dynamic circle is composited under ONE parent opacity
   layer to preserve original overlapping colours and avoid extra
   offscreen full-panel alpha textures. Dynamic pit-lane dots, gap
   labels, car selection rings, collision placement,
   ghost and stop controls remain live. Gap text measurement and
   collision scratch lists are retained/reused on changes, not
   reallocated on every draw. Its business-driven pit-window model
   continues to use the existing 30 Hz timing cadence; no fake
   interpolation of calculated pit-stop gaps is introduced.
6. **Pitwall Cast** keeps wire protocol version 1, its multi-step
   200ms future car samples, timing table, audio/scene integration
   and existing receiver controls. Receiver map animation now reads
   the playback clock during Draw, with a **stable geometry owner**
   so new 200ms Cast packets do not restart the animation coroutine.
   Sender owns its own position sampler; receiver owns its packet
   model and retains tower/map rendering using the same functions as
   the phone. The local decoder count and DRM rules are unchanged.
7. **Diagnostics** remain low-rate, five-second fixed-memory
   `OVERLAY_5S` records for `track-static-displaylist`,
   `track-event-displaylist`, `track-car-flag-draw`,
   `edge-static-displaylist`, `edge-event-displaylist`,
   `edge-car-flag-draw`, `pit-circle-displaylist`,
   `pit-circle-vehicles`, `phone-map-motion-clock`,
   `pitwall-cast-map-motion-clock`, and
   `phone/tv-tower-row-draw`. `changed` on cached layers means
   actually re-recorded regions; zero means display-list replay.
   Metrics are CPU timing estimates, **not GPU elapsed time or
   physical SurfaceFlinger presentation evidence**. Existing
   Perfetto markers remain.

### Mandatory pre-hardware CI regression matrix

| Gate | Regression coverage | Expected result |
| --- | --- | --- |
| Re-record correctness | `OverlayRetainedDrawingTest` | Same key+dimensions replay; style, flags, geometry and resize invalidate |
| Animation scheduler | `OverlayRetainedDrawingTest` | 50/60/120 Hz clock timestamps yield valid ~50-60 Hz draw pacing; backward frame reset accepted |
| Map edge parity | `OverlayEdgeProjectionTest` | Stable sorted-by-progress order matches legacy with ties/overlaps; reusable buffer grows/shrinks correctly |
| Tower dirty fields | `TowerRegionVisualKeysTest` | Speed/gear-only, mini-sector-only, tyre-only, highlight/position-only changes invalidate their exact regions |
| Existing values | `OverlayTowerSamplerTest`, `TowerSampleVisualTest`, `OverlayGeometryParityTest`, `OverlayPositionSamplerTest` | All timing, track, target and seek/data-gap calculations equal the Build 600 source contract |
| Pitwall Cast protocol | `CastTimingProtocolTest` and all Cast receiver/sender tests | No changed protocol fields, geometry, timestamp interpolation or sender/receiver semantics |
| APK | GitHub full Android CI, signing, instrumentation APK assembly | Green before user receives a new APK |

### Remaining tests that **require devices**

The user should not be asked for an intermediate synthetic test.
A green workflow is a **code integration gate**, not the final
smoothness/visual-equivalence acceptance. After CI is green,
run one consolidated real-session validation: S22 Ultra four feeds
with map/tower ON, map/edge/pit-circle and all size/lock/rotation/
opacity controls, TV focus/D-pad/accessibility, comparison images
and 10-minute Perfetto/SurfaceFlinger and `OVERLAY_5S` counters.
Repeat single/two/four feeds on TCL 55C655, then Pitwall Cast with
tower/map and commentary, seek/pause and receiver decoder count.
The target remains fewer than 5% slow UI frames with no steady
freezes, plus independent physically smooth video. No phone-only
or CI-only evidence can mark those hardware gates complete.

---

## Current progress ledger: Build 598 (21 November 2026)

**Baseline:** Build 562, commit 574ccb587a50f174748d1b08ed2283e4b8ed1ecc.
**Current functional baseline:** Build 598, commit 635d0eb7bc46aa666c8807024432231849254c16, on feature/pitwall-performance-audit-plan. GitHub compare: 37 commits ahead of Build 562, zero behind. [Build 598 CI passed](https://github.com/cooldarshak/apexview/actions/runs/44895371508).

**Overall status: active, NOT completed.** Real clear four-feed, mixed-resolution one-decoder playback has been demonstrated on a Samsung Galaxy S22 Ultra. This does not establish protected/CBCS four-feed playback, smooth physical presentation, native TCL TV playback, or multifeed casting. Do not describe a green CI run as device acceptance.

**Status vocabulary:** *Device demonstrated* means the specific behavior appears in an actual F1 session log, not that the full performance/endurance exit criterion passed. *Implemented, not accepted* means code and CI checks exist but device acceptance is outstanding. *Partial/failing* means significant gaps or observed device failures. *Pending* means no completion evidence.

### Original P0–P2 plan: achieved versus pending

| Plan item | Progress made in the branch | Status / remaining acceptance |
| --- | --- | --- |
| **P0.1 segment prioritization and single-flight** | ArchiveSegmentSingleFlight, separate bounded foreground/prefetch fetch lanes, seek/close cancellation and cache ownership code; unit tests. | **Implemented, not accepted.** Need matched foreground latency, p95 prefetch-blocking, seek-race and warm/cold tests on devices. |
| **P0.2 allocations / HEVC composition** | Direct/sized CMAF fragment writers, bounded NAL/Annex-B handling, fewer staging copies, parsed slice-header reuse and instrumentation (Builds 576–585). **Build 585 phone:** process-wide median 147.3 MB allocation, four GCs and 555.5 ms composition per 5.76 s segment. | **Partial.** No allocation-stack attribution or controlled p95/GC reduction proof; large churn remains. Independent decode/bitstream checks pass CI but do not satisfy the performance gate. |
| **P0.3 codec, BufferQueue and 50 fps presentation** | Diagnostics split callback, texture latch, GL draw, actual EGL swap, display modes and FrameTimeline; display-priority GL handling. Real clear playback retains one Qualcomm HEVC decoder. | **Partial/failing.** BufferQueue timeouts remain unexplained. Unique video-picture physical presentation, p95/p99 cadence and A/V drift are not established by decoder counters. |
| **P0.4 timing tower/map/UI jank** | Canvas/layer, accessibility, sampling, cached geometry, vsync-aligned TV marker animation, trace probes and progressive timing cache; Build 584 reversed a tower-layer regression. | **Partial/failing.** Build 585 phone app-frame deadline misses: OFF 42.7%, tower 53.4%, map 53.0%, both Featured 59.4%, both TV layout 62.6% (different trace windows/layouts, not controlled causal A/B). Goal of fewer than 5% slow UI frames / zero steady freezes is not met. |
| **P1.1 minimize coded atlas area** | Bounded search for lowest-area conforming tile arrangement, CTB geometry checks and tests; real 1920×2176 and 2880×2176 atlas configurations seen. | **Implemented, not accepted.** No matched real-device proof of minimum coded work, decoder load reduction or sustained advantage. |
| **P1.2 refresh matching** | Active and available mode logs, frame-rate hints, cadence metrics. S22 trace selected 120 Hz without exact 50/100 Hz mode. | **Partial.** No documented refresh-mode improvement or physical 50 fps acceptance; 50 fps on 120 Hz has uneven cadence even with a healthy player. |
| **P1.3 per-feed quality and thermal** | Saved per-feed caps and actual HLS rendition reporting; mixed-resolution clear atlas; protected-source rendition and tile planning. Real 480p cap can yield 640×360; 540p source can yield 960×540. | **Partial.** Clear quality selection works, but full quality-switch/endurance/thermal policy and encrypted independent-resolution playback are not accepted. Do not silently lower source quality. |
| **P2.1 continuous Google/Pitwall Cast** | Rolling CMAF/HLS relay, baked-scene/audio plumbing, local receiver, HLS discontinuity fixes, controller/overlay messages. A phone-to-TV single-feed qualifying session with map and tower **reportedly worked**. Clear-frame Cast still uses PixelCopy rather than proposed direct EGL encoder input. | **Partial/failing.** Multifeed casting with map/tower was reported failing. Four-feed receiver playback, synchronized commentary, Google Cast, pause/seek endurance and protected receivers remain unverified. |
| **P2.2 split AppState** | Source policy, capture and composition helpers extracted. | **Pending major refactor.** Playback/timing/Cast lifecycles remain strongly coupled in AppState; no completed coordinator ownership separation. |
| **P2.3 signed update and repository** | GitHub produces signed in-place-installable debug builds. | **Partial.** app/build.gradle.kts still names mikeyyy0/apexview as UPDATE_REPO rather than cooldarshak/apexview; confirm intended update source, then correct and test upgrades. |
| **P2.4 security** | Authorization, Widevine, secure surfaces and relay boundaries preserved. | **Partial.** AndroidManifest still enables global usesCleartextTraffic=true; scope LAN access and verify credential/log protections without DRM bypass. |
| **P2.5 TV, overlays, D-pad** | Layouts/focus/tower/map controls implemented, with marker-pacing and timing-cache changes. | **Partial/failing device gate.** TCL 55C655 direct native multifeed, map/tower single-feed and smooth marker/D-pad behavior not accepted on Build 598. Earlier TV captures showed major startup/composition lag. |
| **P2.6 CI, instrumentation, benchmarks** | Build 598 GitHub Actions green, Kotlin/JVM tests, synthetic HEVC and independent FFmpeg/pixel checks, signed APK plus Android-test APK assembled. | **CI achieved, device acceptance pending.** Workflow assembles but does not show executing Android instrumentation on a device; no reproducible real-phone+TV ten-minute performance/Macrobenchmark gate. |
| **P2.7 language decision** | Kotlin + Media3 + GLES retained, no speculative Rust/NDK/Vulkan replacement. | **Retained as planned.** Any native rewrite requires profiler evidence and a measured prototype first. |

### Protected F1 CMAF: additional work arising after the original plan

| Pipeline stage | Real progress | Acceptance |
| --- | --- | --- |
| Same-channel offer routing | Authorized HLS/DASH alternatives and verification; HLS is not automatically clear, a license URL alone is not proof of encrypted samples. | Implemented, source offer type varies; reason for variation unestablished. |
| Source rendition and mixed geometry | Builds 591–593 added protected mixed-source atlas and fixed the real HLS rendition selector (the DASH-only selector was insufficient). | Code/CI; actual protected composed output not verified. |
| HEV1 initialization | Build 594 recovers VPS/SPS/PPS from fully clear in-band NALs where an encrypted hev1 init lacks them. Real F1 capture moved through this stage. | Real parsing reached; does not prove protected playback. |
| CBCS sample groups and subsample records | Build 595 differentiates unrelated sample groups from encryption-changing seig; preserves KID, IV, CBCS pattern and subsample checks. | Real capture progressed to later stage; cross-feed crypto feasibility open. |
| POC and IDR epochs | Builds 597–598 make alignment AND picture-composer proof epoch/fragment/ordinal aware, avoiding POC-only duplicate-picture lookup. | Implemented/CI; successful encrypted decoder handoff still absent. |
| Cross-feed encryption compatibility | Build 598 adds production-wide coding/atlas/CBCS/key preflight and separate per-picture IV validation. | **Critical open gate.** Different actual effective KIDs/IVs may make a single protected output sample impossible without an alternative authorized architecture; never bypass protection gates. |

### What the real F1 evidence DOES and DOES NOT verify

- **Phone clear multiview:** User-provided pitwall-20261121-100011-791.log.txt confirms Build 598 with four feeds, 1920×1080 main plus three 960×540 actual visible sources in one run (other configurations also tested), atlas 1920×2176, approximately 50 fps, one c2.qti.hevc.decoder, one selected video track, one scene input texture and one output view, first frame rendered, tower and track map on. Last sampled run showed 1,527 rendered video frames and zero Media3-reported drops. **Real F1 playback demonstrated**, but captured period is about 90 seconds, not ten minutes; the log explicitly states cast=false. Media3 drop count does not show physical presentation.
- **Different F1 offers, same content:** In that log, the Build 595 attempt at about 15:30 received SDR_HD_CMAFWV (encrypted), while Build 598 at about 16:18 received SDR_HD_CMAF (clear) for content 1000010439. The clear success does **not** establish protected-path success or explain why the F1 API returned a different offer.
- **Other clear atlases:** 2880×2176 playback/preparation is recorded. A 3840×2176 source arrangement reached first frame but was not shown in a matched sustained test.
- **Phone performance baseline:** Build 585 real Perfetto traces remain the quantified OFF/map/tower/both comparison. The Build 598 playback success is **not** a new matched, physical presentation trace.
- **TCL native:** No Build 598 passing four-feed native/overlay endurance capture. Earlier native TV single feed plus overlays and protected multi-feed had stalls, corruption or excessive composition time.
- **Casting:** Single-feed plus baked overlays reportedly played through qualifying on the TV; four-feed Cast plus overlays and audio synchronization is still open.

### Revised acceptance-driven delivery sequence

1. **A. Real stream feasibility and source selection.** On one authorized session, record the actual selected per-feed renditions and offer type, effective HEVC sets, fragment/POC/IRAP epochs and source-wide CBCS/KID/IV compatibility using only safe diagnostics. Require actual composed encrypted first frame and sustained one-decoder playback before marking protected support done. If independent encrypted camera samples are unmergeable, document that architecture blocker, not another validation bypass.
2. **B. Close P0 phone smoothness in one coordinated batch.** Ten-minute four-feed run with tower+map and controlled OFF/tower/map/both isolates at fixed source/thermal conditions. Measure real SurfaceFlinger/FrameTimeline presentation, slow/frozen frames, RenderThread/Compose, allocations/GC by site, segment request p95/p99, BufferQueue ownership, audio clock and seek. Keep goal of fewer than 5% slow frames and no steady-state freezes.
3. **C. Native TCL TV end-to-end.** Test single feed with both overlays, then two and four feeds, one video decoder/secure surfaces where required, 5.76 s segment production, cold timing data, D-pad/focus/sliders, audio/seek and continuous playback. A phone success is not a TV success.
4. **D. Cast end-to-end.** Validate both Pitwall Cast and Google Cast at four feeds with the actual tower/map and commentary, correct windows, one receiver decoder, play/pause/seek and sustained A/V sync. Improve the clear PixelCopy encoding path only with captured throughput evidence and without changing protected screenshot rules.
5. **E. Product and release hardening.** Run Android instrumentation and Macrobenchmark on hardware, signed update-in-place verification, release source/cleartext review, coordinator refactor and regression matrix. Preserve all layouts/features. Publish grouped, reviewed changes rather than isolated unverified claims.

**Definition of done:** physical-phone AND TCL real F1 endurance, one composed HEVC video/Media3 player/hardware decoder, actual independent feed quality, smooth 50 fps presentation, synchronized audio, working timing/map/D-pad, separate end-to-end Cast, legitimate F1 entitlement/Widevine preserved. A green build, first frame, clear stream replacing protected stream, or short playback is not enough.

---

Status: implementation backlog, not a claim of completed fixes or smooth playback.

## Build 576 verified, next P0/P1.1 candidate (20 November 2026)

Build 576 passed GitHub Actions including Kotlin unit tests, independent HEVC
decoder/pixel checks and signed APK assembly. Its CMAF fragment writer now
constructs the final `moof`/`mdat` array directly instead of materializing
three additional fragment-sized copies. This eliminates a source of allocation
churn but has **not yet been measured on a phone**.

The S22 Ultra 92-second trace identifies substantial UI/GPU submission work:
RenderThread had 5,194 `Drawing` slices averaging 8.46ms, 21.80s nested in
`flush commands`, 236,281 rounded-rectangle operations, 294,172 text
operations, and 225,126 ellipse operations. These overlapping/nested CPU
measurements must **not** be added together. It also showed high GC activity.
The subsequent code candidate removes a duplicate owned HEVC NAL payload
allocation and adds direct bounded CMAF NAL-range inspection/writing to avoid
another full-slice copy per picture. It replaces imperceptibly rounded mini-sector bars with cheaper
rectangles, and searches bounded, conforming atlas alternatives by actual
coded pixel area rather than first valid cell count. The atlas test includes
a conforming 110-to-96 CTB-area example, not a claim that actual F1 layouts shrink.
The atlas selection logs evaluated shapes, failed grid validations and whether
its bounded search hit its work cap; an incomplete bounded search is never
reported as a proven global optimum.

**Still open:** install the new signed build on S22 Ultra, compare matched
50fps four-feed playback for GC/allocations and physical SurfaceFlinger
cadence, and isolate recurring BufferQueue timeouts before changing codec
or EGL surface ownership. Repeat on TCL TV. P0.3 frame scheduling, P0.4
rendering and P1.1 atlas **exit criteria are not passed** by CI alone.

## Build 573 device trace and next UI batch (20 November 2026)

The S22 Ultra replay log contains two appended captures: 1.0.572 (archive
startup failures) followed by **1.0.573** (successful two- and four-feed
playback). Do not attribute failures from the older appended session to 573.
The successful four-feed scene used a 1920x2176 HEVC atlas, a single hardware
`c2.qti.hevc.decoder`, first rendered picture, and roughly 50 decoder texture
frames/s. During a 160s four-feed interval its HWUI logs had 4843 slow frames
out of 8464 (57.2%), no freezes over 700ms, and 187 grouped BufferQueue
warnings (1.17/s). App-side decode/GL throughput **does not prove physical
50fps presentation**.

The supplied Build 573 Perfetto trace preserved just 17.7s and changed from
TV to Featured layout during the window. SurfaceFlinger had 627 on-time,
426 late, and 11 dropped frames; the app's main layer was late in 806 of
811 actual frames. `checkForSemanticsChanges` averaged 9.43ms over 190
calls (was 34.8ms over 133 calls in Build 569), while `Recomposer:recompose`
ran 536 times, averaging 4.32ms. Main-thread traversal occasionally exceeded
190ms, and some measure/layout intervals exceeded 150ms. Decoder callback,
texture latch, GL draw and EGL swap averaged only ~0.78/0.47/0.58/0.85ms,
so a whole decoder or HEVC rewrite is **not** supported by the current data.
The unequal and mixed-layout samples do not establish a controlled A/B win.

This follow-up change set targets **UI invalidation** first: use the already
proven single-Canvas native tower row on phone as well as TV (preserving
selection and merged TalkBack semantics); update the session countdown only
when its displayed seconds/flag state changes; supply tests for both; and add
a reproducible runtime log parser. Do not label these optimizations a smooth
playback fix until the new device logs and physical FrameTimeline confirm it.

The remaining BufferQueue warnings involve multiple SurfaceTexture queues,
including decoder and output surfaces. A 0ms timeout warning is not sufficient
to identify a blocked consumer. Preserve the one-decoder compositor and record
queue owners, timestamps, codec texture progress and SF app/display presents
before considering a buffer-policy or SurfaceView rewrite.

The next 60s S22 sample must use `docs/perfetto/pitwall-50fps.cfg` (256MiB
main buffer + 48MiB secondary), verify the actual trace buffer sizes rather
than inadvertently recording another default 64MiB trace, and keep four feeds
plus tower/map in TV layout **without switching layout**. The detailed no-PC
command sequence is in `docs/perfetto/ASHELL.md`. Follow with overlays-off,
tower-only and map-only isolates, then 1-decoder seek/audio and TCL TV tests.

## Build 569 Perfetto findings and next performance batch (20 November 2026)

This batch follows the S22 Ultra `pitwall-50fps.perfetto-trace` with four active
feeds (one 1080p plus three 540p coded renditions), one hardware HEVC decoder
and 120 Hz output. A 64MiB trace buffer preserved only the final **20.7s** of
the requested 60s, so percentages below describe the retained window, not an
entire session. The device has no selectable 50/100 Hz mode. The expected
16.7/25ms 120 Hz mapping of 50 fps sources does **not** excuse additional
blocked decoder output, repeated late frames or multi-frame UI stalls.

| S22 trace metric (retained 20.7s) | Observed | Scope and caution |
| --- | ---: | --- |
| SurfaceFlinger display frames | 930 | 663 on-time (71.3%), 253 late (27.2%), 14 dropped (1.5%); not unique video pictures |
| App surface late frames | 767 of 768 | FrameTimeline app surface; separate from physical decoder-output cadence |
| Compose semantics changes | 133 calls, ~4.63s total | Mean 34.8ms, max ~206ms; nested accessibility-event work ~4.43s |
| Recompositions | 378 calls, ~2.19s total | Significant UI work, but cannot assign every frame miss exclusively to Compose |
| Video texture callbacks | 1,013, mean ~0.97ms | GL handler, roughly 49/s; not a physical presentation count |
| Texture latch / GL draw / EGL swap | ~0.56 / 0.78 / 1.16ms mean | Fast CPU-side stages, not proof that GPU fences are always ready |
| BufferQueue producer timeouts | ~44 occurrences | ~2.1/s in the retained window; owner/cause not established |
| App garbage collections | 50 reported | 38 young + 12 other; aggregate allocation order ~500–550MB |

The same physical-format data was not recorded in older builds, so there is
**no valid percentage-point before/after claim yet**. Earlier build 567/569
log-only comparisons remained around 70–71% slow HWUI frames and significant
producer timeouts. The user's small visible improvement is plausible, but
remaining stutter is consistent with this trace.

Implementation now being tested (CI and device result pending):

1. **P0.4 accessibility:** flatten each phone timing-row subtree to one
   TalkBack-readable, clickable semantic summary; keep live rendered cells
   and TV focus behavior. TV row summaries also no longer change on every
   speed/gear/micro-sector sample. Add Compose and pure-unit regression tests.
2. **P0.2 allocation:** replace per-byte boxed lists and offsets for all-clear
   slice NALs with a bounded primitive RBSP header prefix; preserve all-clear
   emulation-prevention checks, CABAC bitstream and exact tail copying in a
   single output allocation. Add large-NAL regression coverage.
3. **P0.3 decoder output:** raise the existing one GL/latch thread to Android
   display priority, add low-rate queue-wait probes and fixed-bin p95/max
   telemetry. No new decoder, output surface or context is introduced.
   This is a scheduling hypothesis, **not proof of resolved BufferQueue waits**.
4. **50fps evidence:** enlarge the Perfetto capture buffer, check in an
   official-schema FrameTimeline query, offline baseline/candidate comparison
   script and tests. Distinguish surface present from video texture frames.
5. **Release gate:** run Kotlin/Robolectric, synthetic HEVC and independent
   FFmpeg decoding in GitHub Actions, produce a persistent-key signed APK,
   then obtain matched ten-minute phone and TCL TV logs plus physical
   SurfaceFlinger timelines. No physical 50fps success can be asserted in CI.

The five-item delivery requirement is: (a) all regression tests; (b) one
hardware HEVC decoder/selected video track; (c) updated evidence plan;
(d) green CI and matching in-place signing; (e) matched, quantified
before/after on-device frame cadence, BufferQueue rate, UI/jank and GC.
Items (b) and (e) must be **reconfirmed after installing the new build**;
maintaining the invariant in source is not device verification.

## Implementation progress (20 November 2026)

The first P0 implementation batch is being delivered on this branch. These are code changes,
**not device-validated performance wins**. Do not close any P0 issue until its exit criteria
and both devices have been checked with fresh playback logs.

| Priority | First-batch changes | Verification and remaining work |
| --- | --- | --- |
| P0.1 | Replaced the segment-wide fetch/composition lock with per-segment single-flight production, independent bounded foreground/prefetch network lanes, preemptible warmup and bounded cache publication. Added deterministic priority, sharing, cancellation and retry tests. | Await CI; explicitly test seek/close races and compare foreground latency against Build 562 on-device. |
| P0.2 | Reuse session-owned validated initialization metadata, inspect bounded fragment sample ranges without a per-sample copy, use sized Annex-B buffers and direct sized CMAF sample output, log separate parsing/rewrite/packaging/verification times. Added byte-for-byte fixture comparison. | Await synthetic HEVC/FFmpeg CI; obtain per-segment allocated-byte and GC measurements on-device, profile remaining Annex-B and NAL allocations. |
| P0.3 | Corrected GL draw versus actual EGL swap timing; independently measure decoder SurfaceTexture timestamps, callback/latch cadence, Choreographer video/all-frame cadence, EGL context bind/latch/draw/swap distributions, codec frame-processing offsets, and selected versus available display refresh modes. Fixed-bin histograms distinguish 16.7/20/25 ms cadence without per-frame logging. Added regression tests. | Physical presentation remains **unmeasured**; BufferQueue cause needs Perfetto/FrameTimeline producer-consumer investigation and on-device 50 fps cadence verification. A 50 fps source on 120 Hz cannot evenly map frames unless the display changes mode. Do not claim timeout root cause fixed. |
| P0.4 | Memoized immutable TV tower row formatting/sector work, avoided sampling the phone map on every 120 Hz display vsync (preserving approximately 30 Hz), cached source positions per map sample, and added window jank distributions and HWUI layout/draw/sync/command/swap/GPU breakdown. Map and tower trace sections expose Compose hotspots in Perfetto. | Device-test whether the changes reduce slow/frozen window frames; isolate expensive calls using trace. No Macrobenchmark or proof of solved main-thread contention yet. |

Testing note: the handover environment could not download Gradle 8.11.1 because outbound
access to the distribution host was unavailable. GitHub Actions must run compilation,
synthetic composition checks and tests before this batch is considered build-verified.

### Build 563 device report and follow-up (20 November 2026)

Build 563 passed GitHub Actions, including independently decoded CMAF fixtures and signed APK
assembly. It has **not** passed real-device playback: the S22 Ultra log
`pitwall-20261120-015021-044.log.txt` shows two `ARCHIVE_VOD_FAILED` events before any
composed segments. The failure comes from `F1HlsFeedCapture.prepareVod()` rejecting an
entire HLS replay playlist when *any* segment has a gap, discontinuity or changed `EXT-X-MAP`,
even when that segment is unrelated to the requested start position.

Follow-up implementation (awaiting CI/device retest): load/validate initialization for the
actual requested segment; validate gaps, protection and aligned discontinuities per fetched
segment; permit a rotated `EXT-X-MAP` only when its source bytes exactly match the session's
validated initialization; retain CMAF fragment, IRAP and decode-offset checks. Added tests
for unrelated future gaps/discontinuities and genuinely changed initialization bytes. A real
incompatible representation/decoder-period transition still fails closed, and seamless
cross-period playback remains open work. The device's BufferQueue/slow-frame issues still
require a successful sustained playback trace.

Build 564 caught one additional eager check: `HevcArchiveVodSession.align()` rejected any
future `EXT-X-GAP` or incompatible source duration while constructing the full timeline.
Keep those chunks addressable in the finite MPD and let the per-request fetch reject only the actual invalid chunk. The
synthetic gap regression exposed this before the device retest. Build 565 passed CI and
produced the installable APK; the S22 Ultra subsequently played composed streams, so the
previous whole-playlist start failure is no longer reproduced in this capture.

### Build 565 real-device performance report (20 November 2026)

`pitwall-20261120-031400-400.log.txt` shows successful S22 Ultra playback with one active
`c2.qti.hevc.decoder` and two test setups. Two feeds resolved to 1080p + 360p (coded
1920x1088 + 960x544), producing a 1920x1632 atlas. Four feeds resolved to **two** 1080p
sources plus **two** 360p sources (960x544 coded), producing a 2880x2176 atlas of
6,266,880 pixels, about 50% larger than Build 562's four-feed coded atlas. This is not
a matched-quality speed comparison. Four-feed playback had 780 slow window frames out of
1,150 (67.8%) in the measured active interval, two frozen frames, worst 928 ms,
63 grouped SurfaceTexture producer timeouts, and 57 GCs reclaiming about 3.1 GB
cumulatively in roughly 35 seconds. The compositor usually produced 5.76 seconds of media
within 2.6–3.3 seconds; one segment took 4.55 seconds. Renderer counters typically latched
roughly 250 frames/5 seconds after startup with rare >45ms gaps, but physical presentation
remains **unmeasured**. The phone reported both 60 and 120 Hz, neither an integer multiple
of the source's 50 fps. Media3 dropped-frame counts do not contradict the visible stutter.

Next P0.2 follow-up batch (not yet device validated): remove the oversized
`ByteArrayOutputStream` plus `toByteArray()` duplicate on every source CMAF-to-Annex-B
conversion by sizing an owned array from validated sample boundaries, retaining complete
per-sample NAL/HEVC validation and zeroing on errors. Add unit tests for short length
prefixes, bounded ranges and malformed NAL lengths. Record process-wide allocated bytes
and GC count during each segment so the next log can correlate allocation with composition.
These counters include concurrent work and cannot be attributed solely to the compositor.
P0.3/P0.4 and real-device pacing remain open; neither a green build nor this memory
change establishes smooth playback.

### Build 566 follow-up diagnostics and current P0.3/P0.4 batch

Build 566 passed CI with the signed APK, but the S22 Ultra log
`pitwall-20261120-034433-315.log.txt` still shows severe perceptible stutter.
For the sustained four-feed interval with one 1080p and three 360p sources
(1920x2176 coded atlas, 4,177,920 pixels), roughly 2,027 of 3,135
window frames were slow (64.7%). There were 54 grouped BufferQueue timeout
events and 61 GCs reclaiming roughly 3.19 GB cumulatively in about 82 seconds.
The scene latched roughly 250 frames per five seconds, and its sampled
GL draw/swap durations were usually short, but these counts are **not**
SurfaceFlinger physical presentations. This is directionally better than
Build 562's 75.8% slow UI frames but is not a matched-duration comparison.
The earlier portion used two 1080p and two 360p sources, a much larger
2880x2176 atlas, so its performance must be analyzed separately.

The next instrumentation batch adds fixed-size per-stage distributions,
Android 16 FrameTimeline vsync IDs, display-mode changes and available modes,
codec-frame processing offsets, media-position-versus-elapsed-time progression,
and Perfetto annotations for map/tower work. Media3 position progression is
**not** an independent hardware audio timestamp; GPU submission is **not** GPU
completion; scene Choreographer callbacks are **not** physical presentation.
Do not claim these instrumentation changes resolve stutter until tested.

Device acceptance: compare matched feed dimensions and duration against Build
566 for at least 5-10 minutes; record actual selected/supported refresh modes
and codec source timestamps, event and vsync histograms, window-frame stage
breakdown, BufferQueue timeout rates, allocations/GC, buffering, audio drift,
and separate SurfaceFlinger FrameTimeline/Perfetto frames. Retest with tower
and map both enabled and disabled and both phone and TCL Android TV.

### Build 567 measured follow-up and active P0.2/P0.3/P0.4 improvements

The S22 Ultra Build 567 capture `pitwall-20261120-042038-942.log.txt`
**does not establish smooth playback**. With four feeds at 1080p + three
360p actual renditions, 73.2% of HWUI window frames were slow, 21.7% took
longer than 45ms, BufferQueue timeouts still occurred at about 1.08/s and
GC ran at about 0.64/s. Median HEVC composition was about 1.34 seconds
per 5.76-second segment, with about 601 MB of process-wide allocations
observed per overlapping composition window. The same capture's 1080p +
three 540p run had about 70.4% slow frames, BufferQueue timeouts about
0.94/s, GC about 1.12/s and median composition 1.59 seconds. These
allocation values include other threads and are not composer-only bytes.
For comparable Build 566 playback the earlier log had about 1.45
BufferQueue timeout events/s and GC 0.72/s, but these rates vary with
thermal/network conditions and do not demonstrate a solved cause.

The display's selected 120 Hz mode has no corresponding 50/100 Hz mode;
its 50 fps decoder texture timestamps follow an expected mixture of 16.7
and 25ms. Scene video-display callbacks additionally had too-short ~8.3ms
and long ~33ms intervals, so the extra judder is not explained by normal
120Hz 3:2 pulldown alone. EGL submission and swap were comparatively quick;
the HWUI RenderThread's command issuance remained a significant cost.
Physical SurfaceFlinger presentation and hardware audio clock are not yet
measured, and `dequeuedCount=21 acquiredCount=1` is **not** a proven
consumer-ownership diagnosis.

Current follow-up implementation, pending GitHub Actions and device testing:

Build 568 failed at `:app:compileDebugKotlin`: Kotlin inferred a recursive
type for the new self-reposting Choreographer callback. A follow-up explicitly
declares its `Choreographer.FrameCallback` type. This is a compiler fix, not
a measured runtime improvement; the corrected build must pass CI before
installation.

- **P0.2:** Avoid full-NAL duplication in hot HEVC slice-header inspection
  and rewriting. Read NAL header fields without cloning, decode only an
  initial bounded RBSP header prefix (with a complete-prefix fallback for
  unusually long valid headers), and eliminate another stream-alignment
  header copy. Retain CMAF fragment and HEVC validation. Add malformed
  range and byte-ownership regression tests.
- **P0.3:** Add a bounded, display-only 50fps/90-165Hz draw cadence gate
  to prevent too-early 8ms Choreographer scene-draw bursts; codec
  `SurfaceTexture.updateTexImage()` remains immediate to release BufferQueue
  resources. Track explicit pacing deferrals and codec presentation-time/
  planned release-time histograms independently from latches, GL and HWUI;
  run seek/TV/anti-starvation unit tests. This is a hypothesis to test,
  not a proven fix for repeated BufferQueue timeouts.
- **P0.4:** Cache measured text widths per TV tower row (bounded by size,
  text and bold typeface), and memoize unchanged track-map projection and
  ribbon-progress calculation. Retain all overlay features, update rates,
  resizing and TV focus controls; add bounded-cache regression tests.
- **Evidence capture:** Check in `docs/perfetto/pitwall-50fps.cfg` and
  `docs/PITWALL_50FPS_DEVICE_PROFILE.md` with a reproducible 60-second
  scheduler/CPU/RenderThread/MediaCodec/BufferQueue/SurfaceFlinger
  FrameTimeline capture and controlled 5-10-minute A/B test matrix.
  A device trace is still required to resolve the buffer owner and measure
  physical presentation; a CI green check cannot substitute for this.

Reference work compared official Android Performance Samples for
Macrobenchmark/Perfetto, Now in Android for stable Compose state patterns
and Android TV Samples for remote-focus flows. Androidify and other
Material Expressive demos are design references, not evidence for a
playback-performance rewrite. No Material 3 migration is included in P0.

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
