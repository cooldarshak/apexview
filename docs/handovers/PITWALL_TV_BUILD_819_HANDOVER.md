# PITWALL TV: ENGINEERING HANDOVER AFTER BUILD 819

## 1. Immediate instructions for the new chat

You are continuing development of **Pitwall TV**, the existing native Android and Android TV Formula 1 multiview application.

**Do not start a new project, create another plan, reset completed work or repeat previous investigations unnecessarily.**

Resume the existing engineering work using the current consolidated plan.

**Repository:** `cooldarshak/apexview`

**Branch:** `feature/pitwall-performance-audit-plan`

**Latest branch HEAD at handover:** `1f4e9eee80765451e541a2e57dd891ed7c06c479`

**Latest GREEN production build:** Build 819

**Production commit:** `4ab0dbb573315d728aac4724350cd5bdaa4207e6`

**Build 819:** https://github.com/cooldarshak/apexview/actions/runs/45202138705

**Single authoritative plan:** https://github.com/cooldarshak/apexview/blob/feature/pitwall-performance-audit-plan/docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md

### Mandatory starting procedure

1. Read the **entire consolidated plan** from GitHub, including its latest Build 819 ledger, original acceptance criteria and relevant historical investigations.
2. Audit current production source before making changes.
3. Review the newly identified Build 819 defects and performance measurements documented in this handover.
4. Use the **existing 16-workstream plan** as the only source of truth. Add new findings beneath their relevant workstreams. Do not create a competing optimization plan.
5. Develop coordinated, properly tested changes rather than committing tiny fixes and repeatedly triggering builds.
6. Never describe code compilation or green CI as proof of physical playback performance.

The user wants Pitwall to deliver **Netflix/YouTube-level perceived smoothness**, including video playback, seeking, controls, layouts, timing tower and track map.

This must be pursued using measurements rather than speculative rewrites.

---

## 2. Non-negotiable engineering rules

- **Fix, don't delete.** Do not remove working functionality or suppress errors to obtain green tests.
- Preserve legitimate F1 TV authentication, subscriptions, entitlements and Widevine security.
- No DRM bypass, ClearVR, Tiledmedia SDK or proprietary playback SDK.
- Do not introduce OpenF1 as a paid live-timing dependency.
- Keep the native Kotlin, Media3 and GLES architecture unless profiling proves a change necessary.
- The user-selected **SINGLE** mode must preserve one composed HEVC video stream, one video decoder and one output presentation pipeline.
- Preserve the separately user-selected **MULTIPLE** decoder mode.
- Every playback change must be assessed across **clear HEVC and protected Widevine HEVC, phone and TV, single and multiple decoder modes, and Pitwall Cast**.
- Maintain synchronized audio, captions, live timing, track map, adjustable layouts, playback speed, seeking and quality selection.
- Preserve Android TV D-pad navigation and visible focus.
- On TCL 55C655, **Software DRM must remain ON** for testing. Turning it OFF has previously caused corruption/crashes.
- Preserve signed, in-place APK updates. Do not require uninstalling the application.
- Do not silently reduce manually selected video quality.
- Do not hide physical performance failures behind retries, guards or misleading diagnostic labels.
- Maintain security-sensitive log redaction and protected-video capture restrictions.
- Update the original consolidated plan with honest coding percentages, testing status and physical release gates.
- Run production compilation, tests, synthetic HEVC/FFmpeg validation and required emulator instrumentation before considering a build successful.
- **One consolidated implementation batch, not dozens of small builds.** Do not manually trigger extra CI builds without a reason.

The user works from an Android phone and can supply Pitwall logs, Perfetto traces and real-device observations.

---

## 3. Verified Build 819 status

Build 819 was GREEN on November 23, 2026.

It passed the applicable production Kotlin/JVM, Python, Cast/JavaScript, synthetic HEVC/CMAF, independent FFmpeg, APK assembly and emulator instrumentation checks.

Build 818 was RED due to one incorrect refresh-mode test expectation. The production policy correctly allowed 150 Hz as an integer multiple of 50 fps. The test was corrected, with additional regression coverage, and Build 819 passed.

Build 819 includes the completed source changes for:

- **P1.1:** HEVC coded-atlas optimization.
- **P1.2:** 1× and 1.5× display-refresh matching policy.
- **P1.3:** Per-feed quality, workload disclosure and thermal policy.
- **P2.6:** Reproducible testing and benchmark infrastructure.

All four are **100% coding-complete**.

The subsequent branch HEAD is a documentation-only reconciliation commit. **Build 820 is not required to obtain those code changes.**

### Latest overall status

- **16 workstreams**
- **88.9375% coding complete**
- **11.0625% coding pending**
- **1 of 16 strict release gates CLOSED**
- **15 of 16 strict release gates OPEN**

These are separate measures. Coding completion does not mean hardware performance or full product release readiness.

---

## 4. Complete current 16-workstream plan

This is the latest authoritative Build 819 coding ledger, verified against the original GitHub plan at handover.

| ID | Workstream | Coding done | Coding pending | Release gate |
|---|---|---:|---:|---|
| P0.1 | Foreground segment priority | 100% | 0% | OPEN |
| P0.2 | HEVC memory and allocation efficiency | 100% | 0% | OPEN |
| P0.3 | Video rendering and presentation | 100% | 0% | OPEN |
| P0.4 | Track map, timing tower and UI performance | 100% | 0% | OPEN |
| P1.1 | HEVC coded atlas optimization | 100% | 0% | OPEN |
| P1.2 | Display refresh and frame matching | 100% | 0% | OPEN |
| P1.3 | Video quality and thermal management | 100% | 0% | OPEN |
| P2.1 | Pitwall Cast | 85% | 15% | OPEN |
| P2.2 | Coordinator architecture | 25% | 75% | OPEN |
| P2.3 | Release management and updates | 88% | 12% | OPEN |
| P2.4 | Security | 70% | 30% | OPEN |
| P2.5 | Android TV | 80% | 20% | OPEN |
| P2.6 | Tests and benchmarks | 100% | 0% | OPEN |
| P2.7 | Core architecture | 100% | 0% | CLOSED |
| P2.8 | Material 3 Expressive UI | 100% | 0% | OPEN |
| P2.9 | Closed captions | 75% | 25% | OPEN |
| **Overall** | **Unweighted coding estimate** | **88.9375%** | **11.0625%** | **1/16 CLOSED** |

### Detailed workstream scope and outstanding acceptance

**P0.1: Foreground segment priority**

Per-segment single-flight composition, separate bounded network lanes, speculative prefetch preemption, seek cancellation and cache ownership are coded.

Remaining physical acceptance: foreground segment latency, seek p95, repeated seeking under load and correctness of cancellation on S22 Ultra and TCL.

**P0.2: HEVC memory**

Validated metadata reuse, sized output buffers, reduced CMAF/NAL copies, ownership rules and independent bitstream verification are coded.

Remaining acceptance: sustained seven/eight-feed allocation and garbage-collection measurements, clear/protected parity, and actual 10-minute thermal behaviour.

**P0.3: Video rendering**

Codec-to-texture-to-GLES diagnostics, Choreographer pacing, surface lifecycle handling and timestamp correlation infrastructure are coded.

**Newly confirmed problems require follow-up engineering:** unexplained BufferQueue pressure, inadequate independent final-video presentation evidence and potential surface/presentation bottlenecks.

**P0.4: Map and timing tower**

Retained map geometry, tower display caching, decoupled sampling and rendering optimizations are coded.

Remaining: improve physical app-window performance to the original **under-5% slow UI frame** target, with no steady-state freezes. Preserve real updates rather than reducing functionality.

**P1.1: HEVC atlas**

Validated bounded smallest-coded-area search, hardware decoder size/rate capability filtering, source geometry checks and diagnostic disclosure completed in Build 819.

Remaining: real mixed-quality F1 playback, codec workload comparison, decoder stability and sustained thermal validation.

**P1.2: Refresh matching**

1×/1.5× cadence policies, supported display-mode filtering, seamless frame-rate hints and tests completed.

Remaining: actual refresh selection, scanout presentation cadence, 50/75 fps handling, audio sync and real-device validation.

**P1.3: Quality and thermal**

Explicit manual rendition ceilings, actual Media3 offered source reporting, workload disclosure, thermal notices and multiple-decoder thermal policies completed.

Remaining: real source-selection and endurance testing without unintended quality downgrades.

**P2.1: Pitwall Cast, 85%**

Complete protected and baked receiver overlay parity, continuous multiview, map/tower synchronization, commentary, playback controls and receiver-side performance. Test actual S22-to-TCL transmission.

**P2.2: Coordinator architecture, 25%**

The large `AppState.kt` remains responsible for tightly coupled playback, composition, overlays and Cast lifecycles.

Incrementally extract ownership into testable coordinators, especially cancellation and surface lifetime management. Avoid a destructive rewrite.

**P2.3: Release and updates, 88%**

Complete production release signing, ownership checks, update repository correctness, signed upgrade verification and actual in-place installation tests on phone and TV.

**P2.4: Security, 70%**

Complete LAN transport scoping, credential protection, secure backup exclusions, logging redaction, relay token ownership and entitlement checks.

**P2.5: Android TV, 80%**

Complete native TCL playback, one-feed plus map/tower recovery, multifeed stability, D-pad controls, sliders, layout transitions and protected output acceptance.

**P2.6: Testing and benchmarks**

Executable test infrastructure and 10-minute Macrobenchmark definition are coded.

Run genuine physical matrices at 1×/1.5×, OFF/MAP/TOWER/BOTH, phone/TV and applicable Cast paths. These have not been signed off by CI.

**P2.7: Core architecture**

CLOSED. Preserve Kotlin, Media3 and GLES, the user-selected decoder modes and legitimate DRM architecture.

**P2.8: Material 3 Expressive**

Coding complete. Portrait layout was accepted by the user. Finish physical UI, TV focus, TalkBack, large-text and accessibility validation.

**P2.9: Captions, 75%**

Complete and validate actual F1-provided text tracks across local playback, one/multiple decoder, clear/protected sessions, TV and supported Pitwall Cast receiver modes.

**Important:** Newly discovered source defects in otherwise 100%-coded workstreams must be reopened honestly for follow-up. Do not erase the Build 819 historical coding achievement, but never use it to dismiss verified runtime defects.

---

## 5. Build 819 real-device investigation: stale selected feeds and CANCELLED status

The user supplied a screenshot showing the Layout diagnostic:

- `Selected feeds: 2`
- `Playback: CANCELLED`
- `First frame: NO`
- `Rendered frames: 0`
- One active hardware HEVC decoder.

However, only the International feed was then visible, together with map and timing tower.

### Confirmed root cause

The prior composition genuinely involved two feeds:

- International, `channel-1025`
- Another feed, `channel-1006`

When the additional feed was removed, Pitwall cancelled the old composed session and resumed the remaining single-feed player.

The diagnostic text continued to reflect the old composition's captured `selected.size` and terminal `CANCELLED` state.

Source involved:

- `AppState.kt`: `startArchiveSingleDecoderComposition()`
- Its nested `diagnostic()` and cancellation handler.
- `stopProtectedCompositionPresentation()`
- `remove(tile)`
- `Viewer.kt`: `LayoutSheet()`

### Actual playback evidence

In the previous diagnostic log:

- At approximately 21:39:25 the scene reported one feed.
- At approximately 21:39:29 both map and timing tower were enabled.
- Remaining International feed reported READY and playing.
- Hardware decoder was `c2.qti.hevc.decoder`.
- Rendered frames increased from 1,081 to 2,596 over approximately 30 seconds.
- Dropped-frame count remained 2 in that interval.

**Conclusion:** The `CANCELLED` label was stale. It did not establish that the currently playing single feed had failed.

### Required fix

Implement proper composition-generation ownership for diagnostics and terminal states. An obsolete job must not publish diagnostic information after current playback ownership has changed.

Display current feed count separately from historical composition metadata.

Clear outdated diagnostics on:

- Last extra feed removed.
- Transition from composition to one native player.
- Decoder mode changes.
- Composition replacement.
- Session closure.
- Seek cancellation and restart, where appropriate.

Add regression tests for 3 → 2 → 1 feeds, rapid selection, cancellation races, seeking and decoder switching.

Do not hardcode the displayed count to 1 or merely hide the `CANCELLED` string.

---

## 6. Performance analysis from Build 815 and Build 819

### Build 815 baseline

Samsung S22 Ultra logs and Perfetto indicated:

- Visually smooth composed playback in several scenarios.
- Six, eight and ten-feed compositions demonstrated in clear HEVC.
- One Qualcomm hardware HEVC decoder.
- Around 50 decoder texture frames per second in active steady windows.
- **46.1% slow app-window UI frames**, against a target below 5%.
- No confirmed exact codec PTS-to-SurfaceTexture timestamp matches.
- Repeated BufferQueue timeouts.
- High process-wide allocation during composition.
- Thermal status reached SEVERE.

These do not establish that the HEVC decoder lost 46.1% of pictures.

### Build 819 initial test

The new diagnostic log showed:

- Approximately **47.8% slow app-window UI frames**.
- Repeated composition restarts as feeds were removed.
- Approximately **3,800 BufferQueue timeout occurrences**, including folded repetitions.
- Eight-feed composition reached ACTIVE.
- Map and timing tower initialized successfully, around 546 ms.
- No identified crash or ANR.

### First three Build 819 Perfetto traces

| Capture | App-window frames | Late | Buffer-stuffing jank |
|---|---:|---:|---:|
| 21:36:56 | 5,270 | 97.6% | 3,996 |
| 21:38:51 | 4,768 | 97.0% | 3,366 |
| 21:40:24 | 3,504 | 98.3% | 2,150 |

### Additional tests at 1.5× with multiple jumps/seeks

User supplied:

- `trace-taro-BP2A.250605.031.A3-2026-11-23-22-00-34.zip`
- `trace-taro-BP2A.250605.031.A3-2026-11-23-22-01-41.zip`

Previous analysis found:

| Metric | 22:00:34 | 22:01:41 |
|---|---:|---:|
| Analysed duration | 132.5 s | 31.6 s |
| App-window frames | 6,820 | 1,551 |
| Frames marked late | 99.3% | 99.9% |
| Buffer-stuffing events | 5,519 | 1,406 |
| App deadline missed | 46.7% | 49.5% |
| App-window frames marked dropped | 9 | 0 |
| Largest app-window frame gap | 628 ms | 452 ms |

The traces include seek-related Media3 activity.

### Critical interpretation

These are **Android app-window FrameTimeline measurements**, not independent decoded-video-layer frame loss.

Do not state that 99% of the F1 video frames were lost.

The traces indicate persistent app-window presentation lateness and buffer stuffing. They do not independently prove what happened to each decoded HEVC frame on the physical screen.

The tests also differ by workload and thermal conditions, so 1.5× cannot automatically be blamed for the apparent change.

### Current hypotheses to investigate

1. Video final-output and UI rendering contention.
2. BufferQueue backpressure and surface ownership.
3. `MediaCodec.release`, surface teardown or lifecycle churn.
4. GLES latch, draw, swap and SurfaceFlinger scheduling.
5. Excessive Compose invalidation, RenderThread operations or overlay updates.
6. Allocation/GC pressure from composition and player lifecycle transitions.
7. Seek-related network fetch, CMAF random-access handling or decoder rebuffering.
8. Thermal throttling.

None should be treated as proven root cause without corresponding stage-level evidence.

---

## 7. GitHub research: other streaming applications

The previous chat researched and inspected source in these repositories.

### A. AndroidX Media3

https://github.com/androidx/media

**Most important reference:** https://github.com/androidx/media/tree/release/demos/surface

The official SurfaceControl demo demonstrates retaining the same decoder-attached surface while reparenting visible output between SurfaceViews.

**Potential Pitwall benefit:**

- Less surface-recreation churn.
- Fewer interruptions during layout and fullscreen changes.
- Clear separation between decoder ownership and UI view lifetime.

Check Android API and protected-surface compatibility before applying it. Do not assume this demo automatically supports Pitwall's custom protected GLES path.

### B. Just (Video) Player

https://github.com/moneytoo/Player

Relevant source: `app/src/main/java/com/brouken/player/PlayerActivity.java`

Uses Media3 and supports fast scrubbing, including `SeekParameters.CLOSEST_SYNC` during scrubbing.

**Potential Pitwall benefit:**

- Responsive seeking.
- Distinguishing rapid seek preview from final accurate seek.
- Better controls during repositioning.

Do not discard HEVC IRAP/keyframe alignment or assume closest-sync seeking is always acceptable for the final requested frame.

### C. Jellyfin Android TV

https://github.com/jellyfin/jellyfin-androidtv

Relevant: `playback/media3/exoplayer/src/main/kotlin/ExoPlayerBackend.kt`

Implements configurable Media3 buffering, SurfaceView attachment and TV playback management.

**Potential Pitwall benefit:**

- Structured buffering policy.
- Stable TV surface lifecycle.
- Better player-state separation.
- Consistent TV controls.

Network buffering and SurfaceFlinger buffer stuffing are different phenomena. Never conflate them.

### D. Next Player

https://github.com/anilbeesetti/nextplayer

Relevant sources:

- `PlayerContentFrame.kt`
- `SeekGestureState.kt`
- `PlayerSeekbar.kt`
- `DecoderRecoveryManager.kt`

Uses Compose, Media3 `PlayerSurface`, `SURFACE_TYPE_SURFACE_VIEW` and separate seeking/decoder recovery state.

**Potential Pitwall benefit:**

- Better player-versus-UI ownership.
- Reduced surface churn.
- Cleaner scrubbing and player controls.
- Safer recovery from decoder failures.

Its GPL-3.0 licence must be respected. Prefer adapting general architectural ideas or using appropriately licensed upstream Media3 APIs rather than copying GPL source into Pitwall without a licensing decision.

### E. MotionFlow Android

https://github.com/Endijuan33/motionflow-android

Relevant classes:

- `FramePerformanceAccumulator`
- `AndroidPerformanceProbe`
- `FramePacingPolicy`

Provides ideas for per-session decoder metrics, thermal observation and cadence classification.

**Potential Pitwall benefit:**

- Better 1× versus 1.5× measurement.
- Distinguish integer cadence from fractional display mismatch.
- Report unsupported/unmeasured metrics accurately.

This is an experimental reference, not demonstrated proof of better playback. Check its licensing before reusing code.

### F. Compose Video Player Pooling

https://github.com/nikitachicherindev/compose-video-player-pooling

Relevant:

- `ExoPlayerPool.kt`
- `VideoContainer.kt`

Demonstrates bounded player reuse and explicit surface detachment.

**Potential Pitwall benefit:** Reduced player initialization and release churn during browsing and previews.

Do **not** introduce multiple pooled decoders into Pitwall's SINGLE composition path.

### Additional references

- F1OpenViewer for F1 multiview/timing UI concepts.
- AerialViews for Android TV video-surface and overlay patterns.
- mpv-android only as an independent clear-video comparison, not as an automatic replacement for Media3.

**Important:** No reference repository was proven to solve Pitwall's exact performance defect. Treat these as implementation patterns and hypotheses to test.

---

## 8. Important source finding: Pitwall's two presentation paths differ

The current source uses:

**Clear HEVC:**

`app/src/main/java/app/apexview/scene/SingleDecoderSceneView.kt`

- Extends `TextureView`.
- Decoded HEVC atlas enters GLES through a `SurfaceTexture`.
- GLES renders the logical feed windows.
- Final output is presented through a TextureView associated with the app window.

**Protected HEVC:**

`app/src/main/java/app/apexview/scene/ProtectedDecoderSceneView.kt`

- Extends `SurfaceView`.
- Uses GLES and protected EGL where required.
- Final video has an independent SurfaceFlinger layer.

Relevant UI wiring: `app/src/main/java/app/apexview/Viewer.kt`, `PlaybackVideoCanvas()`.

The code explicitly distinguishes the clear TextureView path from the protected SurfaceView path.

### Research-derived engineering hypothesis

Clear HEVC may benefit from an experimental **SurfaceView-based GLES output**, reducing dependence on app-window HWUI presentation.

The existing protected scene already uses a SurfaceView, making its architecture a useful comparison.

This does **not** mean copying the protected renderer without checking clear-content replay recording, screenshot behaviour, PiP, layout transforms, caption overlays, casting and surface ownership.

Investigate a shared rendering abstraction only when tests demonstrate lifecycle correctness.

**Do not remove existing TextureView behaviour before proving a replacement.**

---

## 9. Media3 dependency review

Pitwall's `app/build.gradle.kts` currently specifies:

```kotlin
val media3 = "1.5.1"
```

Previous research identified Media3 **1.11.1** as a newer stable release candidate. Verify the actual current stable version, migration instructions, compatible Android/Gradle dependencies and known issues before upgrading.

A controlled Media3 migration may bring rendering, surface handling, buffering and Compose-related improvements.

However:

- Do not assume upgrading fixes BufferQueue backpressure.
- Do not break existing DRM callback handling.
- Preserve clear DASH/HLS and encrypted CMAF.
- Preserve F1 audio and captions.
- Preserve user quality selection.
- Preserve custom HEVC atlas MediaSource behaviour.
- Test seek, pause, speed, decoder handoff, TV and Cast.

Only retain the upgrade if full production regression checks pass and hardware performance does not regress.

---

## 10. New coordinated engineering priorities

These are **new findings and candidate changes to integrate into the original plan**, not a replacement plan.

### Priority A: Establish independent presentation evidence

Add trustworthy measurements for:

1. Media3 codec output buffer metadata.
2. Decoder `SurfaceTexture` frame-available and latch timestamps.
3. GLES draw start/end.
4. Actual `eglSwapBuffers()` duration.
5. Final video SurfaceFlinger layer presentation.
6. Main app-window FrameTimeline.
7. BufferQueue producer/consumer and lifecycle state.
8. Process allocations, GC and thermal state.
9. Seek request to first correctly presented video frame.
10. Audio clock and actual playback speed.

Use timestamp-domain-aware correlation. Never fabricate matches between incompatible clock domains.

### Priority B: Fix state ownership and stale diagnostics

Fix the confirmed two-feed/CANCELLED diagnostic bug using composition generation and ownership rules.

Also inspect cancellation races and repeated composition startup/shutdown during feed removal and seek.

Regression-test the lifecycle before modifying performance-sensitive code.

### Priority C: Clear-HEVC SurfaceView experiment

Using Media3 SurfaceControl patterns and the existing protected renderer as references:

- Keep one hardware HEVC decoder.
- Keep atlas composition and logical windows.
- Preserve GLES rendering.
- Experiment with independent SurfaceView output.
- Maintain overlay order and clipping.
- Preserve layouts, resize, fullscreen, PiP, replay recording and Cast.
- Compare identical F1 configurations against the current TextureView renderer.

Measure the actual independent video layer, not only total app-window frames.

If it performs worse or breaks functionality, retain the existing implementation and record the evidence.

### Priority D: Improve seeking at 1.5×

The existing finite clear archive usually seeks within its composed Media3 player, prioritizing the requested segment.

Rolling protected compositions may require a new capture window after a distant seek.

Investigate:

- Request-to-first-frame latency.
- Seeking into valid random-access HEVC points.
- Cancelling superseded foreground/prefetch work.
- Avoiding unnecessary complete compositor rebuilds.
- Rapid seek coalescing where appropriate.
- Maintaining playback speed and synchronized audio.
- Stale video frames and outdated map/tower positions after seek.

Study Just Player's scrubbing strategy, but do not weaken HEVC or DRM correctness.

### Priority E: Reduce overlay/UI scheduling contention

Review:

- `Viewer.kt`
- `AppState.kt`
- Track map.
- Tower rows.
- Compose state publication.
- RenderThread and Canvas allocation.
- Decoder-to-UI callback frequency.

Separate video frame presentation from map/tower updates.

Avoid rebuilding entire layout hierarchies for timing changes.

Preserve actual live timing information and visible animation quality.

Target **under 5% slow UI frames**, without steady-state freezes.

### Priority F: Tune buffering and thermal performance based on evidence

Distinguish:

- Network read-ahead.
- CMAF composition cache.
- Media3 buffer policy.
- Decoder output BufferQueue.
- GLES output buffers.
- SurfaceFlinger presentation.
- Player lifecycle release.

Do not attempt to fix graphical buffer stuffing by blindly increasing network buffers.

Do not silently lower video quality or remove feeds.

Measure before and after any proposed optimization.

---

## 11. The 1.5× frame-rate limitation

F1 video typically runs at 50 fps.

- At 1×, effective playback is 50 source frames/second.
- At 1.5×, effective playback is approximately 75 source frames/second.

50 fps is not an integer divisor of a 120 Hz display. Neither is 75 fps.

If supported, 50/100 Hz can provide even cadence for 50 fps. A 75/150 Hz mode can provide even cadence at 1.5×.

However, display modes are device-dependent.

Never force a disruptive non-seamless refresh switch or infer real display presentation from a requested `Surface.setFrameRate()` hint.

The objective is to remove avoidable renderer delays and use the best supported cadence, not claim that every device can display every source frame perfectly.

---

## 12. Required physical acceptance matrix

Primary devices:

**Phone:** Samsung Galaxy S22 Ultra, `SM-S908E`.

**TV:** TCL QLED 55C655 Android TV, with Software DRM ON.

Test the actual authorized F1 streams.

### Decoder and protection combinations

- SINGLE, clear HEVC.
- SINGLE, authorized Widevine HEVC L1/L3 where supported.
- User-selected MULTIPLE, clear and protected streams.
- Native phone playback.
- Native Android TV playback.
- Pitwall Cast phone-to-TV.
- Clear baked and authorized protected receiver paths, as applicable.

### Workload combinations

For reproducible performance comparison:

- 1× and 1.5×.
- Overlay OFF, MAP, TOWER and BOTH.
- One, four, six and eight feeds, plus larger supported counts when appropriate.
- Source quality AUTO and explicit manual caps.
- Repeated seeking, pause/resume, feed removal and addition.
- Layout switching, orientation changes and fullscreen.
- Ten-minute sustained playback at matched device temperature and source rendition.
- A/V synchronization and caption parity.

Use the existing `scripts/pitwall_acceptance_matrix.py`, `docs/perfetto/ASHELL.md` and Macrobenchmark infrastructure.

Record genuine app diagnostics and Perfetto. Never substitute synthetic streams for protected F1 hardware acceptance.

### Acceptance criteria

- No unintended decoder-count increases.
- Actual authorized video playback.
- Continuous visible video.
- Correct atlas crops and logical feed windows.
- Verified seek recovery and audio synchronization.
- No unexplained recurring BufferQueue saturation.
- Independent physical video-layer presentation evidence.
- No steady-state UI freezes.
- Under-5% slow UI-frame target, with correct measurement context.
- Acceptable memory and thermal endurance.
- Complete map, tower, caption, layout and D-pad behaviour.
- Correct local and Pitwall Cast output.
- In-place APK update success.

If device capabilities prevent a requirement, report the exact limitation and evidence instead of declaring false success.

---

## 13. How the next chat should execute the work

**First:** Audit latest branch HEAD and confirm Build 819 is still the latest green source. Do not assume this handover remains current if GitHub has changed.

**Second:** Inspect the existing plan and code for P0.3, P0.4, P1.2, P2.2 and P2.6, particularly the confirmed stale diagnostic bug and the measured presentation pipeline.

**Third:** Map each new finding to existing workstreams, with affected production files, root-cause evidence, test coverage and applicable clear/protected/TV/Cast paths.

**Fourth:** Implement a coordinated source-and-test batch addressing proven issues. A SurfaceView or Media3 migration should be validated as an experiment, not forced into production on assumptions.

**Fifth:** Run all feasible automated checks and correct failures without deleting tests, relaxing requirements or hiding bugs.

**Sixth:** Only then allow the consolidated production change to run through GitHub Actions. Do not generate a long series of builds for two-line corrections.

**Seventh:** Update the **same original plan**, including:

- Current source SHA.
- Latest exact GREEN build.
- Actual code changes.
- Automated test outcomes.
- Updated coding percentages, where justified.
- Physical tests still pending.
- Release gates CLOSED versus OPEN.
- Known new defects and performance risks.

Do not automatically mark P0.3/P0.4 physically complete because their historical coding rows say 100%.

---

## 14. What to tell the user

Be precise and practical.

Explain:

1. The specific source and performance issue identified.
2. Which GitHub reference informed the proposed change.
3. What was actually changed in Pitwall.
4. Which test or artifact proves the change compiled and works in automation.
5. What still requires real-device testing.
6. Whether Build 819 remains the latest production build or a genuinely new GREEN build exists.

Do not give generic optimization recommendations already covered here.

Do not claim Netflix-level smoothness until it has been demonstrated on the actual S22 Ultra and TCL TV.

**The immediate objective is to resolve verified lifecycle and presentation issues, preserve the successful one-decoder HEVC architecture, and materially reduce UI/frame-pacing backpressure through measured improvements.**

**Final instruction:** Continue from the existing consolidated plan and Build 819. Preserve completed work, use the supplied evidence and researched implementations, make comprehensive verified changes, and keep coding completion separate from physical acceptance.