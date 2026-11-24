# PITWALL TV: NEW-CHAT ENGINEERING HANDOVER, BUILD 826 → COMPLETE BATCH 2

**Prepared:** 24 November 2026  
**Purpose:** Copy this entire file into the next ChatGPT development conversation, or provide its GitHub URL. The next chat must **resume the EXISTING app/branch, immediately audit source, implement and verify EVERY Batch 2 coding task to 100%, and update the sole consolidated plan**. Do not start a new app, invent another active plan, or stop after merely making recommendations.  
**Repository:** https://github.com/cooldarshak/apexview  
**Branch:** `feature/pitwall-performance-audit-plan`  
**ONLY active 16-workstream plan:** `docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md`  
**This file:** a handover/execution brief, **NOT** a second active plan.  
**Last exact-source GREEN:** GitHub Actions **Build 826**, run https://github.com/cooldarshak/apexview/actions/runs/45243050523, production source commit `7c7b824e57458cada105c0c660b0f54e5df52bce`.  
**Docs-only consolidated-plan bookkeeping commit after 826:** `b50577194bea32b6498e53a95cc151517b556705`. Check actual branch HEAD and workflows before modifying anything. A documentation-only handover commit may advance HEAD without superseding the proven production binary.

> **TOP PRIORITY FOR THE NEXT CHAT:** Complete **all 11 Batch 2 work packages (B2.1–B2.11) to 100% from the CODING + automated-regression perspective**. Audit the real current production code, incorporate all affected call sites/tests in **one substantial, coordinated implementation**, run available preflight checks, push one carefully reviewed source/test/plan change and get **GREEN GitHub Actions against its exact SHA**. If an unexpected real CI failure occurs, read its first-failure logs, correct the actual defect (without deleting functionality or weakening the gate), and verify again. Do not silently declare completion, just change percentages, or substitute physical-device validation with CI.

## 1. Current source-of-truth and what is already completed

- Build 826 **GREEN**, complete job (39 successful steps; nine artifacts). Includes aligned **AndroidX Media3 1.11.1**, Media3 custom video renderer API overrides, reversible **clear-video SurfaceView** A/B, original **TextureView** fallback, secure Widevine SurfaceView preserved, output-stage diagnostic counters, capture-safe handoff, unit + Python + Cast/JS + synthetic HEVC/CMAF/FFmpeg + mandatory emulator TextureView/SurfaceView EGL scene tests, signed debug APK and benchmark artifacts. All initial failed Builds **822–825** were resolved by Build 826; they were not GREEN.
- **Batch 1: 100% of defined coding and automated-verification deliverables.** Physical A/B and release sign-off remain open. Do not redo the Media3 1.5.1→1.11.1 migration or introduce a competing renderer by default.
- Build 821 also fixed stale composition-attempt diagnostic ownership (e.g., misleading "Selected feeds 2" / "CANCELLED" after reducing to one native feed). Preserve its generation-safe logic.
- **Whole 16-workstream unweighted historical coding baseline remains 88.9375% complete / 11.0625% pending, and 1/16 physical/release gates CLOSED.** These figures are *not* the Batch 2 percent. Review and update only after verified code changes. Distinguish coded/automated-verified from real phone/TV/Cast acceptance.
- The newest request is to execute **Batch 2**, explicitly INCLUDING every unresolved issue from the Build 826 A/B logs/traces, in addition to the original G2/G3/G4 lifecycle/seek/buffering batch.

### Build 826 A/B evidence inherited from the immediately preceding investigation

**User's observation:** Video visually looked proper/smooth after A/B test on Samsung Galaxy S22 Ultra (SM-S908E, Android 16). Evidence comes from the previous chat's review of these exact uploads, which should be reopened if accessible in the next chat:

1. `pitwall-20261124-025501-212.log.txt`
2. `trace-taro-BP2A.250605.031.A3-2026-11-24-08-27-11.zip`
3. `trace-taro-BP2A.250605.031.A3-2026-11-24-08-28-39.zip`

**Do not pretend to have re-opened these files unless tool access actually confirms it.** Prior-chat derived numerical baseline:

| Observation | Result / interpretation |
| --- | --- |
| Source and decoder | Four selected feeds + both tower/map, composed HEVC atlas 1920×2176, `c2.qti.hevc.decoder`, **ONE active hardware video decoder**, 50 fps nominal source; both A/B output paths reached ~48–50 GLES submissions/s in stable windows |
| A/B order | TextureView began around **08:26:09**; SurfaceView began around **08:27:50**; two separate playback sessions, not perfectly matched randomized runs |
| Whole app-log periods (not matched windows) | TextureView **2,162/4,999 = 43.2%** slow app-window frames; SurfaceView **457/2,964 = 15.4%**; different interactions/startup |
| Selected steady app-window samples | TextureView **120 Hz: 1,026/2,845 = 36.1% slow** (08:26:34–08:27:09); SurfaceView **60 Hz: 91/1,791 = 5.1% slow** (08:28:05–08:28:35) |
| Same SurfaceView after touch boosts display refresh | 120 Hz at 08:28:39–08:28:45: **239/452 = 52.9% slow app-window frames**, while GLES still ~45–50 fps. **Refresh-rate confounding is major** |
| Raw FrameTimeline | Selected window app-deadline misses approximately **32.8% → 5.0%** when switching those two configurations; still not isolated to renderer alone |
| BufferQueue | ~**601 repeated timeout occurrences** across startup/switching/playback/shutdown; many nonblocking `mDequeueTimeout: 0` messages. FrameTimeline **buffer stuffing** ~81.4% TextureView and ~96.0% SurfaceView in selected app-window windows. This remains **UNRESOLVED**, not proof of 601 perceptible stalls |
| Thermal | Status **2 → 3** during session (moderate → severe); need distinguish app contribution, environment, and Android thermal policy |
| Additional concerns | 480p quality request vs observed **640×360** for some HLS renditions, duplicate `MediaDrm: Object Already unbinded` messages, render callback gaps >45 ms, source/caption timing and Cast not physically established |
| What was **not** demonstrated | No independent physical SurfaceView scanout FPS proof; the tested composition was at **1×**, not 1.5×; Cast inactive; captured L3/no loaded DRM keys does **not** prove protected Widevine L1. Do not close DRM/Cast/TCL physical gates |

**Key interpretation:** Both renderers make good-looking video and the one-decoder pipeline appears healthy, but the huge app-window A/B advantage is largely confounded by **60 Hz versus 120 Hz** and touch refresh voting. **Never equate app-window slow/late frames with dropped HEVC pictures**, and never call BufferQueue stuffing a networking/Media3 buffer shortage without causal tracing. Do **not** force 60 Hz just to inflate benchmark success.

## 2. UPDATED ORIGINAL 16-WORKSTREAM PLAN (exact verified coding baseline)

Keep the 16 IDs and physical gate ownership exactly as the sole consolidated plan records. The Batch 2 tasks map INTO these workstreams; do not introduce an imaginary 17th or a separate active plan.

| ID | Workstream | Coding complete | Pending coding | Physical gate | Batch 2 relevance / outstanding release work |
| --- | --- | ---: | ---: | --- | --- |
| **P0.1** | Foreground segment priority and seeking | **100%** | 0% | OPEN | B2.5 seek coalescing/correct final frame; B2.7 evidence-led buffering; measure p95, 1×/1.5× |
| **P0.2** | Bounded HEVC memory | **100%** | 0% | OPEN | B2.6 allocations/GC/caches; B2.7 bounded startup buffer; 10-minute real heap and feed-capacity validation |
| **P0.3** | One-decoder GL video presentation | **100%** | 0% | OPEN | B2.1, B2.2, B2.3, B2.8: real compositor/surface/frame lifetime, queue roots, actual presented frames |
| **P0.4** | Retained map/tower and UI scheduling | **100%** | 0% | OPEN | B2.4 UI/overlay scheduling; controlled OFF/MAP/TOWER/BOTH and <5% target |
| **P1.1** | Smallest conforming HEVC atlas | **100%** | 0% | OPEN | Preserve bounded smallest-conforming coded atlas, prove 1/4/6/8 feeds on real hardware |
| **P1.2** | Display refresh / frame-rate matching | **100%** | 0% | OPEN | B2.1/B2.8 prove 60/120 scheduling and 50fps/1.5× effective 75fps cadence without forced modes |
| **P1.3** | Manual quality / thermal policy | **100%** | 0% | OPEN | B2.6 and B2.10: thermal 2→3, actual HLS rendition transparency, no silent downquality |
| **P2.1** | Pitwall Cast continuity and parity | **85%** | 15% | OPEN | B2 lifecycle/capture must preserve; remaining full receiver parity stays Batch 3 |
| **P2.2** | Coordinator architecture and ownership | **25%** | 75% | OPEN | **Core Batch 2 lifecycle target:** B2.2/B2.3/B2.5/B2.8/B2.9; code audit, explicit resource ownership and failure-safe coordinator; update row only when truly earned |
| **P2.3** | Signed release and in-place updates | **88%** | 12% | OPEN | Preserve signing, package identity, debug-update continuity; production verification Batch 3 |
| **P2.4** | Authorization, DRM and network security | **70%** | 30% | OPEN | B2.9 DRM cleanup without entitlement/security compromise; full security audit Batch 3 |
| **P2.5** | Android TV player, remote, map/tower | **80%** | 20% | OPEN | B2.3/B2.4/B2.8 preserve TCL Software DRM ON and D-pad; TV release validation Batch 3 |
| **P2.6** | Automated tests and physical benchmark harness | **100%** | 0% | OPEN | **All B2 packages:** extend strict regressions, true layer metrics, 10-min physical matrix |
| **P2.7** | Native Kotlin/Media3/GLES core architecture | **100%** | 0% | **CLOSED** | Preserve source-defined one-decoder invariant; NO proprietary SDK |
| **P2.8** | Material 3 Expressive/UI accessibility | **100%** | 0% | OPEN | B2.4 map/tower/UI jank; preserve approved portrait/landscape and TV accessibility |
| **P2.9** | Source-owned closed captions | **75%** | 25% | OPEN | B2 must preserve captions through lifecycle/seek/recovery; remaining full mobile/TV/Cast parity Batch 3 |

**Baseline calculation:** (sum of existing 16 row coding percentages)/16 = **88.9375%**. Physical gates **1/16 closed** (P2.7). Earlier 100%-coded milestones remain valid as historical implemented scope; new trace-confirmed defects are follow-up coding work, not grounds to retroactively claim those milestones never compiled. For Batch 2, separately track **11/11 source packages coded/automated verified** as the explicit completion target; do not invent a number for end-to-end app readiness.

## 3. ALL BATCHES: single consolidated roadmap

| Batch | Content / upstream adoption | Baseline | Required stop condition |
| --- | --- | --- | --- |
| **Batch 1: M3 + G5 + G1** | **Media3 1.11.1** aligned ExoPlayer/DASH/HLS/UI/OkHttp libraries, codec API migration, video-layer diagnostics, **reversible clear TextureView vs SurfaceView** A/B, protected SurfaceView unchanged and safe Cast/replay fallback | **100% coding + automated tests**, Build 826 GREEN | DONE for coding; on-device physical A/B/release gates remain open and feed into B2/B3 |
| **Batch 2: G4 + G2 + G3, plus trace findings B2.1–B2.11** | **Coordinator ownership and surface lifecycle, 120/60 Hz scheduling, BufferQueue causal fixes, overlay/UI scheduling, rapid seeking/1.5×, memory/thermal, measured buffering, DRM cleanup, rendition truth, strict regression+benchmarks** | **0/11 NEW Batch 2 coding packages verified** at handover | **100% of ALL B2.1–B2.11 code changes + tests, exact-source GREEN CI, no known missing production wiring**, evidence-backed status recorded in sole plan; physical device release acceptance remains separately open |
| **Batch 3: G5 extension, G6 if justified, G7 TV reference only** | Finish Cast/protected relay/TV/closed captions/release-security/signing and optional *measured* bounded player reuse strictly outside SINGLE; physical 10-min device matrices and final 16-gate acceptance | NOT STARTED as a dedicated release batch | Real S22 Ultra + TCL + applicable Cast acceptance, full clear/protected/1×/1.5×/overlays/quality/captions, signed update/security and **16/16** release gates only with proof |

### Upstream GitHub source provenance (ideas and public APIs, **never** unlicensed code copying)

| ID | GitHub project | Real reference | Batch use / caveat |
| --- | --- | --- | --- |
| **M3** | https://github.com/androidx/media | `release/RELEASENOTES.md`; published 1.11.1 artifacts | **B1 DONE**; do not re-migrate. Keep compatible Media3 player/surface API and codec override behavior |
| **G1** | https://github.com/androidx/media/tree/release/demos/surface | SurfaceControl surface demo | **B1 A/B coded**. Decoder reparenting in demo is **not** directly equivalent to custom EGL composed atlas; B2 only improves measured surface-lifetime defects |
| **G2** | https://github.com/moneytoo/Player | `app/src/main/java/com/brouken/player/PlayerActivity.java` | **B2 seeking:** validated sync-point scrub/jump and exact final user-requested position |
| **G3** | https://github.com/jellyfin/jellyfin-androidtv | `playback/media3/exoplayer/src/main/kotlin/ExoPlayerBackend.kt`, `ExoPlayerOptions.kt` | **B2 lifecycle and measured DefaultLoadControl**; do not claim GL BufferQueue errors are network rebuffering |
| **G4** | https://github.com/anilbeesetti/nextplayer | `feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/PlayerContentFrame.kt`, `state/SeekGestureState.kt`, `service/DecoderRecoveryManager.kt` | **B2 surface lifetime / player/recovery / seeking:** independent design, **GPL-3.0: no source copying without a licensing decision** |
| **G5** | https://github.com/Endijuan33/motionflow-android | `performance/FramePerformanceAccumulator.kt`, `performance/android/AndroidPerformanceProbe.kt`, `pacing/FramePacingPolicy.kt` (under `app/src/main/java/com/motionflow/player/core/media/`) | **B1 diagnostics coded, B2 extends** to accurate independent video-vs-UI cadence, thermal and buffer attribution |
| **G6** | https://github.com/nikitachicherindev/compose-video-player-pooling | `ExoPlayerPool.kt`, `VideoContainer.kt` | **B3 only if profiler proves useful**, exclusively previews/user-selected MULTIPLE; NEVER active extra decoder in SINGLE |
| **G7** | https://github.com/theothernt/AerialViews and https://github.com/npanu420/F1OpenViewer | Optional UI/TV references only | **B3 optional**, not an established BufferQueue solution, do not treat uninspected F1OpenViewer code as verified |

## 4. BATCH 2 EXECUTION MATRIX: IMPLEMENT EVERY ROW TO 100% CODING

**B2 coding percentage starts 0/11 verified**. Each row means production wiring, all affected call sites, executable regression tests and actual integration into the app. A research-only task, change in comments, fake test, unconnected helper, or unverified CI does **not** equal 100%.

| Task | Priority | Root issue and evidence | Required code implementation / inspected real targets | Original workstreams | Coding acceptance |
| --- | --- | --- | --- | --- | --- |
| **B2.1 Refresh voting and UI/video pacing** | **P0** | Texture 120 Hz ~36.1% slow vs Surface 60 Hz ~5.1%; Surface 120 Hz after touch ~52.9% slow | Inspect `scene/SceneFrameRatePolicy.kt`, `scene/SceneFramePacing.kt`, `FrameDeadlinePolicy.kt`, `WindowFrameDiagnostics.kt`, `Viewer.kt`, 1×/1.5× votes and display mode listener. Fix actual redundant votes/mismatch; isolate Compose vs 50fps video scheduling; never force 60Hz solely for metrics | P0.3/P1.2/P2.6 | Pure JVM policy + production source wiring; measured test modes distinguish 50/60/75/120 and touch transitions; correct refresh reported, no regressions |
| **B2.2 BufferQueue ownership/backpressure** | **P0** | ~601 repeated timeout events, buffer stuffing in both paths; no proven 601 user-visible stalls | Track actual producer, consumer, SurfaceTexture, EGL submit, app-window and SurfaceFlinger layer. Inspect `scene/SingleDecoderSceneView.kt`, `scene/ProtectedDecoderSceneView.kt`, `scene/SceneSurfaceLifetime.kt`, `scene/SceneOutputReadiness.kt`, `scene/SceneFrameDemand.kt`, `scene/SceneCodecTimestampCorrelation.kt` and trace export. Fix demonstrated queue ownership, unbounded buffering, stale swap/latch or lifecycle fault; retain nonblocking safety. Add timestamp-domain and queue attribution; **do not silence errors** | P0.3/P1.2/P2.2/P2.6 | Reproducing regression + fixed invariant, real-source compositor integration, queue-stage telemetry, no fake count/drop claims |
| **B2.3 Coordinator and surface/player lifecycle** | **P0** | Previous stale CANCELLED diagnostics and risk from renderer switch/orientation/player teardown | Incrementally extract resource ownership from `AppState.kt` into testable coordinator/state machine where justified. Audit `Viewer.kt`, `FeedPlayer.kt`, `scene/SceneSurfaceLifetime.kt`, `scene/SceneOutputReadiness.kt`, `scene/VideoScene.kt`, clear/protected streams and `cast/local/PitwallReceiverActivity.kt`. Fix generation validity, 3→2→1, layout-only recomposition, seek/stop, background/foreground, PiP, Cast transfer and duplicate release/prepare. **Fix don't delete; no second decoder in SINGLE** | P2.2/P0.3/P2.5 | Deterministic state-machine and real production wiring tests; no stale owner/scene, no redundant decoder starts; robust cancellation/recovery |
| **B2.4 Compose UI and map/tower scheduling** | **P0** | UI lateness despite smooth 50fps compositor; overlays and touch interaction amplify jank | Profile and remove unnecessary invalidations, remeasures, main-thread blockers and needless redraws in `Viewer.kt`, `TrackMap.kt`, `TvTowerRow.kt`, `OverlayTowerSampler.kt`, `TowerRegionVisualKeys.kt`, `WindowFrameDiagnostics.kt`. Preserve timing cadence and ALL map/tower interactions, TV focus, transparency, dragging, font sizes, Material 3 Expressive approved design | P0.4/P2.8/P2.5 | Regression tests for freshness, layout, controls and recomposition scope; no hiding/low-frequency fake timing updates |
| **B2.5 Seek/scrub/1.5× recovery** | **P1** | A/B tested 1×, previous 1.5× seek-window jank | Implement bounded coalescing of superseded seek requests with validated HEVC sync/IRAP preview and exact final seek. Audit `AppState.kt`, `FeedPlayer.kt`, `FrameThinning.kt`, archive CMAF/HEVC path and media clock. Preserve alignment with main audio, captions, map, timing tower and Cast. Cancel obsolete preflight/prefetch but keep latest request; no endless buffer state | P0.1/P0.3/P2.2/P2.6 | Real regression cases for rapid seeks, 1.5×, 3→2→1, latest-request wins, final PTS correctness and decoder reuse counts; define seek p95 capture |
| **B2.6 Memory/GC/thermal** | **P1** | Thermal status 2→3 and intermittent long callbacks | Profile and correct redundant frame/quad/atlas allocations, buffer copies, cache churn, GC bursts and retained media contexts. Inspect `scene/SceneQuadBufferCache.kt`, `tiled/HevcComposedPlayback.kt`, `PlaybackStrainMonitor.kt`, `VideoThermalBudget.kt`, loading/atlas paths. Preserve visible user quality choice; **no silent quality downgrade, no artificial thermal masking** | P0.2/P1.3/P2.6 | Executable allocation/retention/boundedness tests, instrumented GC/heap counters and thermal reason telemetry; real endurance gate remains open |
| **B2.7 Measured Media3 network buffering** | **P1** | Multiple HLS/CMAF loads at startup but no proof of network starvation in steady playback | Inspect `FeedPlayer.kt`, `AppState.kt`, `PlaybackOffers.kt`, archive buffer policies. Add/reuse real load/rebuffer metrics. Tune `DefaultLoadControl` or prioritization **only if real underbuffering is measured**; otherwise record evidence-based no-op and do NOT inflate buffer size. Keep bounded foreground segment priority, quality and memory | P0.1/P0.2/P1.3 | Unit/source tests for measured selection policy or evidence-backed no-op, precise startup/rebuffer/segment metrics; no BufferQueue/network confusion |
| **B2.8 A/B output switching and controlled acceptance tooling** | **P1** | SurfaceView visually promising but 60/120 Hz confounded; capture needs TextureView | Retain `ClearSceneOutputPolicy.kt` default-OFF reversible experiment, `Viewer.kt` and both scene views. Make actual output-ready/capture handoff reliable and instrument SurfaceFlinger independent video layer vs HWUI. Extend `docs/perfetto/frame_timeline_export.sql` and `scripts/pitwall_acceptance_matrix.py` if useful; explicitly label 60/120, 1×/1.5×, OFF/MAP/TOWER/BOTH | P0.3/P2.1/P2.2/P2.6 | Tests cover in-flight A/B transition, first-frame epoch, Cast/replay/external capture fallback, clear/secure z-order, exact decoder count; do not promote ON without real physical evidence |
| **B2.9 DRM session cleanup and entitlement-safe release** | **P1** | Duplicate `MediaDrm: Object Already unbinded` warnings | Trace ownership of ExoPlayer/DRM sessions and secure output in `FeedPlayer.kt`, `AppState.kt`, `scene/ProtectedDecoderSceneView.kt`, Cast receiver. Fix demonstrated duplicate release/order defects with idempotent cleanup. Do **not** disable DRM logs, leak keys, bypass license or weaken secure output | P2.2/P2.4/P2.5 | Test repeated stop/seek/switch/rotation/TV Cast with valid DRM ownership, clear-vs-protected isolation and no repeated release of owned session |
| **B2.10 Quality/rendition truth** | **P2** | A 480p request was observed with actual 640×360 HLS output for some secondary feeds | Audit `PerFeedQualityPolicy.kt`, `PlaybackOffers.kt`, `FeedPlayer.kt`, current rendition/atlas reporting. Distinguish requested cap, manifest label, selected track, decoded pixels, composed atlas and displayed tile. Enforce accurate UI, no unsupported resolution assumptions or unwanted downgrade | P1.3/P2.6 | Unit tests with nonstandard HLS sizes, AUTO/manual caps, clear/protected, user facing actual-quality disclosure |
| **B2.11 Full consolidated tests / quality barrier** | **P0** | Historical Builds 757–800 had excessive failed tiny commits; B1 needed four precise corrections before green | Expand existing `scripts/test_pitwall_production_wiring.py`, `scripts/pitwall_change_contract.py` (keep strict), relevant JVM/androidTest scene + codec/seek/DRM tests, `scripts/pitwall_acceptance_matrix.py` and benchmark gates. Cover phone+TV+Cast, SINGLE/MULTIPLE, clear/auth Widevine, 1×/1.5×, each overlay state, decoder count, focus, captions, rapid switching, screenshot mode, in-place installation identity | P2.6 / every affected workstream | **One consolidated substantial code/test/plan change**, real tests, all mandatory exact-source GitHub Actions steps GREEN, signed APK/benchmark and genuine synthetic HEVC/emulator output tests. Device acceptance honestly OPEN |

**Crucial:** The fact that a particular B2 task is already partly or wholly addressed by existing code means **audit and verify it**, do not duplicate it. If no safe coding change is justified, document tested evidence and close that B2 item as an accurately verified no-op, never claim a hypothetical optimization was implemented.

## 5. Source paths, tests and tools to inspect FIRST

**Verify every path against the actual branch, not memory.**

- Playback/lifecycle: `app/src/main/java/app/apexview/AppState.kt`, `FeedPlayer.kt`, `FrameThinning.kt`, `Viewer.kt`, `Settings.kt`, `ClearSceneOutputPolicy.kt`.
- GLES: `app/src/main/java/app/apexview/scene/SingleDecoderSceneView.kt`, `ProtectedDecoderSceneView.kt`, `SceneSurfaceLifetime.kt`, `SceneOutputReadiness.kt`, `SceneFramePacing.kt`, `SceneCodecTimestampCorrelation.kt`, `SceneFrameDemand.kt`, `SceneFrameRatePolicy.kt`, `SceneQuadBufferCache.kt`.
- HEVC/quality/thermal: `app/src/main/java/app/apexview/tiled/HevcComposedPlayback.kt`, `PerFeedQualityPolicy.kt`, `PlaybackOffers.kt`, `PlaybackStrainMonitor.kt`, `VideoThermalBudget.kt`, `FrameDeadlinePolicy.kt`.
- Overlays: `TrackMap.kt`, `TvTowerRow.kt`, `OverlayTowerSampler.kt`, `TowerRegionVisualKeys.kt`, `WindowFrameDiagnostics.kt`.
- Cast and receiver: verify `app/src/main/java/app/apexview/cast/local/PitwallReceiverActivity.kt`, `cast/NativeComposedCastSession.kt`, `cast/SceneCastRelay.kt`, and `cast/` senders.
- Automated gates: `.github/workflows/`, `scripts/run_rendernode_instrumentation.sh`, `scripts/test_pitwall_production_wiring.py`, `scripts/test_pitwall_change_contract.py`, `scripts/test_pitwall_acceptance_matrix.py`, `scripts/pitwall_acceptance_matrix.py`, `docs/perfetto/frame_timeline_export.sql`, `docs/perfetto/ASHELL.md`, `macrobenchmark/` and project tests.
- Exact core checks: Media3 remains **1.11.1** everywhere; minSdk/target and signed package stable; actual HEVC decoder count one for SINGLE; custom HEVC CMAF/tiled composer intact; authorized encrypted content secure; G1 clear-output experiment remains opt-in and capture-safe.

## 6. HARD ENGINEERING RULES: NON-NEGOTIABLE

1. **FIX, DON'T DELETE.** Preserve all user-facing functions; no weakening assertions, dropping tests, blank guards, hiding errors, feature removal, or label-only/no-op patches masquerading as fixes.
2. **One comprehensive coordinated implementation, no dozens of tiny commits/builds.** Audit source and full API signatures, integration, all call sites, settings, lifecycle and regression tests; combine B2 changes in one deliberate production commit/tree push and run GitHub Actions once when ready. If a real failure requires correction, inspect first-failure logs, correct the real root and all affected paths together; avoid repeating failed push loops. **No Codemagic**.
3. **Update sole plan in SAME production commit as affected code AND meaningful regression tests.** `scripts/pitwall_change_contract.py` must remain active; do not add another active plan. A docs-only handover is NOT a production change.
4. **Do not claim tests you did not run.** If local Kotlin/Gradle is unavailable, explain that limitation, use available static tests, and treat first CI as verification rather than pretending local compilation passed. Only assert Batch 2 100% after exact-code CI success; physical release gates remain independent.
5. **All app delivery modes preserved:** standalone Samsung S22 Ultra Android 16 and TCL 55C655 Android TV (Software DRM **ON**), phone→TCL Pitwall Cast, applicable Google Cast, **clear HEVC and authorized Widevine L1/L3 HEVC**, explicit SINGLE and MULTIPLE, different qualities, protected/clear audio/captions, track map/timing tower, 1×/1.5×, remote D-pad/focus, fullscreen, PiP, screenshot mode and external/capture paths. Do not pretend successful emulator DRM is real protected content.
6. **SINGLE means one composed video stream, one MediaCodec decoder, one output scene.** NEVER fallback invisibly to MULTIPLE, no proprietary Tiledmedia/ClearVR, no DRM bypass, no extra active codec for buffering/pooling; MULTIPLE stays as an explicit user-selected option.
7. **Video metrics differ from app-window metrics.** Present GPU/SurfaceFlinger layer frames separately from codec callbacks, texture latches, EGL swaps and app-window HWUI lateness. Beware 120 Hz ↔ 60 Hz and tap boost confounding; never represent 50fps GL submitted as verified 50fps actual display.
8. **Perfetto and log integrity:** collect actual producer/consumer queue names, frame deadlines, exact layer IDs, thermal/GC and timing; count nonblocking BufferQueue timeouts without claiming each is a frozen screen. Preserve 5MB log rotation or user settings rather than creating unbounded files.
9. **No external paid OpenF1** or runtime MCP server. Use legitimate authorized F1 subscription and licensed/open-source reference ideas, not GPL code copied into proprietary app. No secret/token disclosure.
10. **Signed in-place update:** Do not change application ID, release keystore, version/update semantics, force uninstall, drop backup settings or remove the user's approved Material 3 Expressive layouts. Require real phone/TV test for physical upgrade gate.
11. **Do not inflate progress:** Existing 100%-coded workstreams remain historical coding milestones; verified B2 corrections may be added without automatically setting every remaining P2 row to 100%. Track B2 package coding separately. Strict release gates stay OPEN unless actual physical tests prove closure.
12. **Keep work moving within the next conversation** until all B2 source tasks and automated gates are verified GREEN, rather than ending after research, pseudocode or a plan. If a true external blocker prevents completion, provide precise evidence and partial status without claiming 100% or promising async work.

## 7. EXECUTION STEPS THE NEXT CHAT MUST PERFORM (NOT JUST DESCRIBE)

**Start immediately in the existing GitHub branch:**

1. Fetch latest branch HEAD and `docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md`; reconcile if HEAD advanced beyond this handover. Inspect exact production Kotlin and all eleven B2 paths. Read existing tests and workflow. Identify already implemented vs unwired code for each row. Reference Build 826 source, full green workflow and A/B evidence above.
2. Build a **single verified root-cause coding plan** for all B2.1–B2.11, include explicit production-owner transitions, unchanged interfaces, state/decoder/DRM invariants and regression assertions. Address **120 Hz scheduling, BufferQueue producer/consumer, coordinator ownership and UI timing first**; do not prematurely tune network buffers.
3. Implement **all rows together**. Reuse current Kotlin/Compose/Media3 1.11.1 patterns, use original HEVC atlas/capture renderer rather than new decoders; ensure both clear and protected branches and Cast/TV. Introduce internal pure helpers only when actually wired into production.
4. Add meaningful JVM/Android instrumentation and Python/Cast regression coverage. Audit *all* callback/call-site API differences and compile-breaking edge cases, including 1.5×, Android 16, Android TV 12 and DRM surface lifetime.
5. Update **the sole consolidated plan** with every B2 item status, exact source files/tests, root-cause findings and coding percentages in the **same commit**. Do not edit historical paragraphs to falsely mark earlier failed CI green. Preserve all 16-workstream physical gate states until valid device evidence.
6. Use one coherent GitHub Git-data tree/commit when supported, otherwise fewest safe atomic commits, **avoid pushing partial production code** that auto-triggers wasteful failed builds. Conduct the best available preflight before pushing.
7. Run **exact-source GitHub Actions**; investigate the first failure, fix every real related source/test/plan defect rather than bypassing a gate, and verify until **GREEN**. Confirm artifacts exist and are from the exact committed source SHA. Don't launch Codemagic.
8. Report final **B2.1–B2.11 coding and CI percentages in a table**, exact GREEN build/run/SHA, changes by source project/reference, any measured improvement where evidence exists, and clearly separate remaining physical-device tests. **Do not stop while any B2 coding or automated test acceptance is known pending.**

### Explicit B2 "100%" definition (all required)

- All **11/11 B2** packages have real code integration (or an evidence-backed no-change conclusion), complete relevant test coverage and documented acceptance; **no abandoned experimental branch**.
- Every affected clear/protected, SINGLE/MULTIPLE, mobile/TV/Cast feature path verified at source/test level; no unhandled resource owner, codec leak or surface fallback that bypasses user's preference.
- Media3 **1.11.1** preserved, build source and tests compile, generated real HEVC/CMAF and independent FFmpeg checks pass, mandatory **both** EGL scene tests pass, signing/APK/benchmark artifacts pass.
- Same commit updates **sole 16-workstream plan + tests + production**, with GitHub change-contract gate unchanged.
- Latest **exact-source** Build GREEN. A docs-only bookkeeping update after that source does not need another APK build.
- Physical goals (**S22/TCL/Cast**, protected L1, controlled 60/120 refresh, 1×/1.5×, 10-minute endurance, independently presented video, D-pad, signed update) remain OPEN **until device evidence is supplied**. Don't claim Netflix/YouTube smoothness merely from CI.

## 8. What to send the user at completion

Provide a **single consolidated status table** for B2.1–B2.11 (coding %, automated test, mapping to 16 workstreams, source project, residual physical gate), updated whole-project 16-row coding ledger, GitHub exact-source commit and green CI URL, and concise real findings. Say **"Batch 2 100% coding complete" only if the actual source + tests + exact-source CI justify it**. No future-work promises or false device sign-off.

**Immediate instruction:** **Resume coding Batch 2 now. Implement all eleven work packages, in one coordinated audited change, and verify the full GitHub Actions gates. Do not simply rewrite this handover.**
