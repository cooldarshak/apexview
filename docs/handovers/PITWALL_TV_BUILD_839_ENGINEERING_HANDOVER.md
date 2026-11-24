# PITWALL TV — BUILD 839 ENGINEERING HANDOVER FOR A NEW CHAT

Prepared: 24 November 2026

Copy this entire handover into a new development chat, or provide its repository URL. Continue the EXISTING Apexview / Pitwall TV Android and Android TV application. Do not start again, replace the project or create a competing engineering plan. Your task is to audit, implement, test and complete the 14-item consolidated follow-up batch below, not simply make recommendations.

## 1. Repository and starting checkpoint

- Repository: https://github.com/cooldarshak/apexview
- Work exclusively on `feature/pitwall-performance-audit-plan`.
- Keep `main` as the stable baseline; do not merge, release or change main without the user's instruction.
- Only these two branches remain. Five obsolete feature branches were deleted.
- Before this handover-only commit both branches pointed to documentation cleanup commit `7d42ae9b3c9178f838717700586bfb7fb50f4ec7`. Their complete file trees were identical. The new handover is intentionally committed only to the feature branch; the production source remains identical to main at this checkpoint.
- Last verified green production binary: **Build 839**, version **1.0.1678**, source `5090df55d3fbef02703dacb66c514a3a185f8db5`.
- Build 839 workflow: https://github.com/cooldarshak/apexview/actions/runs/45391665766
- Build 839 APK artifact: https://github.com/cooldarshak/apexview/actions/runs/45391665766/artifacts/14918684752
- Build 838 failed the mandatory Android overlay pixel assertion; Build 839 fixed it and passed the mandatory CI stages. Build 837 was also green.
- The Build 839 merge into main is PR #5: https://github.com/cooldarshak/apexview/pull/5.
- **Only active master plan:** [PITWALL_PERFORMANCE_AUDIT_PLAN.md](../PITWALL_PERFORMANCE_AUDIT_PLAN.md). Read the current top section AND relevant original detailed workstreams/history. This handover is a snapshot, not a second active plan. Update the master plan, not multiple competing roadmaps.
- Seven obsolete phase plans/handovers were removed and seven relevant documentation files updated. Do not recreate the deleted plans. Historical documents remain available in Git history.

Immediately verify the actual remote HEAD, branch differences, latest exact-source workflow results and master plan before editing. Documentation-only commits do not change the tested binary or imply a new green build.

## 2. Hard rules and success criteria

1. Keep the existing architecture and F1 TV login, legitimate subscription entitlement, authorized Widevine license flow and protected-output rules. No DRM bypass, insecure TLS fallback, copied credentials, proprietary Tiledmedia/TME/ClearVR SDK or paid OpenF1.
2. Preserve all applicable phone and Android TV paths, clear and authorized protected HEVC, Single and Multiple decoder modes, Google Cast and native Pitwall Cast. Single-decoder mode must maintain one composed video track, one active video decoder and one decoder output surface, with permitted scene rendering; never silently fall back to independent video decoders.
3. Deliver one substantial coordinated production/test/plan change, not many small commits and repeated speculative builds. Run all available local/source/fixture checks before publishing. If a real CI failure occurs, inspect the first failing stage and correct the actual defect without weakening tests. Do not merge into main until instructed.
4. Complete every identified coding requirement and regression test that can genuinely be completed. Continue through the batch rather than stopping after an isolated fix, a progress message or a plan update. Separate coding, automated verification and physical/release acceptance. Do not claim 100% coding for a partially implemented item, invent percentages, claim device testing you did not perform or promise background work. If blocked by a genuinely unavailable device/tool, finish independent work and state the specific blocker and next evidence needed.
5. Universal fixes only: do not hardcode Las Vegas, Lawson, device-specific race IDs or particular content timestamps. Device-aware capability policies must be based on real capability checks and measurements, not unsupported assumptions.
6. Keep the new native multifeed compatibility, smooth map, timing tower and subtitle customization intact. Preserve layout library, user preferences, playback/seek, audio programmes, caption language/appearance, backup, rotation and D-pad accessibility. Do not remove working features to improve benchmarks.
7. The user's objective is smooth 50fps playback at 1×, full 75fps at 1.5× where device capabilities allow, responsive seeking, stable casting and sustained multifeed playback. Judge performance by physical presentation and controlled tests, not CI or decoder callbacks alone.

## 3. Latest observations and scope

Devices: Samsung Galaxy S22 Ultra SM-S908E (latest log reports Android 16, adaptive 60/120 Hz); TCL 55C655 / Smart TV Pro (Android TV 12, Amlogic HEVC hardware decoder, 60 Hz in recorded sessions). User can provide Pitwall logs, phone-only aShell/Perfetto traces and TV diagnostics.

User confirmed:
- Native TV multifeed now starts in all tested combinations of Software DRM and experimental clear SurfaceView ON/OFF. Major milestone; three feeds with map and tower still interrupt repeatedly and start slowly.
- Native TV timing tower and map work; driver map motion is smooth.
- Caption text size and text-background opacity work on both phone and TV.
- Pitwall Cast fails with SSLHandshakeException; Google Cast baked scene actually works for tested clear content including overlays, but keeps buffering and phone continues playing. Preserve the baked approach; optimize it.
- Locked map button remains visible in TV layout. Main + strip shows secondary videos below instead of the required main-left 2/3 and right-side 1/3 stacked secondary feeds.
- Las Vegas race: gear missing in tower; Lawson resumed participation but tower still says OUT.

User-reported successes are real functional evidence, but do not establish all clear/protected variants, all quality settings or endurance acceptance. Prior audit reported three TLS handshake rejections, direct EGL baked output with zero reported copy failures, approximately 20–21fps against 25fps capture target, peak layer acquisition 84ms and peak encode 41ms. Reopen exact logs if available; do not treat previous audit figures as independently reverified here. Some attachments contain Build 827/831 rather than 839; filter by version/process and avoid double-counting log rotations. In particular older TCL segment production data must not be mislabeled as Build 839.

## 4. Current master ledger and exact 14-item plan

The following is reproduced from the current master plan at the cleanup commit. The master plan remains authoritative if it is subsequently updated.

### Current 16-workstream ledger

| ID | Workstream | Coding status (Build 839 + newly identified scope) | Physical/release acceptance |
| --- | --- | --- | --- |
| P0.1 | Segment priority and exact seek | Reopened: delivery tail latency and seek attribution | Open; phone seek improvement observed, TV/Cast pending |
| P0.2 | HEVC memory and delivery | Reopened: TV composition throughput, allocation and native arena feasibility | Open |
| P0.3 | Video rendering | Reopened: BufferQueue ownership, GPU capture throughput | Open |
| P0.4 | Map and timing tower | Reopened: map-lock visibility, driver OUT recovery, gear telemetry | Open; native map/tower working |
| P1.1 | HEVC atlas | Reopened: device-aware throughput/geometry validation | Open; single hardware decoder multifeed demonstrated |
| P1.2 | Refresh matching | Reopened: full 50/75fps scheduling and output-mode selection | Open |
| P1.3 | Quality and thermal | Reopened: device defaults, thermal/quality policy | Open |
| P2.1 | Pitwall Cast | Reopened: TLS, baked Google Cast buffering, capture and local presentation | Open |
| P2.2 | Coordinator architecture | Previous scope implemented; audit ownership in new Cast/rendering changes | Open |
| P2.3 | Release and updates | Previous coding scope implemented; no new specific coding defect identified | Open; signed upgrade/release acceptance |
| P2.4 | Authorization and security | Reopened: Android 12/16 mutual TLS interoperability | Open |
| P2.5 | Android TV | Reopened: landscape strip, map lock, telemetry, multifeed throughput | Open |
| P2.6 | Testing and benchmarks | Reopened: regression and matched physical evidence matrix | Open |
| P2.7 | Core architecture | Previously accepted scope implemented; preserve single-decoder invariant | Closed for previously accepted core scope only |
| P2.8 | Material 3 Expressive | Reopened: TV layout and lock-control accessibility | Open |
| P2.9 | Closed captions | Previous coding scope implemented; no new caption-appearance defect reported | Open; appearance confirmed by user on phone/TV; Cast, track/programme and full lifecycle acceptance pending |

Strict release acceptance remains **1/16 closed**; no release approval. Coding percentages for the reopened scope are not estimated. A workstream can return to 100% coding only after all its identified requirements and regression tests are implemented, with hardware acceptance separately tracked.

### Build 839 physical observations and evidence boundaries

- User confirms native TV multifeed playback now starts with Software DRM ON/OFF and experimental clear SurfaceView ON/OFF; native map movement is smooth, tower updates, caption text size and background opacity work on both devices. Three feeds with map/tower still interrupt frequently on TV. This is a compatibility milestone, not endurance acceptance or proof of protected playback for every toggle.
- Pitwall Cast fails with SSLHandshakeException before playback/pairing completes. Baked Google Cast works for tested clear content with overlays but repeatedly buffers; phone continues producing/displaying the source. TV map lock persists. Main + strip can choose a lower strip instead of main-left/right-strip. Gear is missing in Las Vegas tower and a resumed driver can remain OUT.
- Earlier and new logs contain mixed APK versions; classify by capture header and process, not filename or test date. S22 Build 839 header is 1.0.1678; older TCL 1.0.1662 is Build 831. The prior audit's TV segment throughput examples include older-build evidence (5.76 s media segments, slow composition/rewrite); remeasure exact Build 839/new candidate on TCL before attributing a quantitative regression to it.
- Prior phone audit reported median composition 307 → 264 ms, median process allocation 77.6 → 65.1 MB, seek p95 2210 → 1303 ms (831 → 839); uncached request p95 1841 → 2402 ms and slow UI frame share 33.79 → 38.35%. These are observational, not matched A/B results. FrameTimeline primary-window misses reported SurfaceView 24.94%, TextureView 36.50% for 839; these are not video scanout/drop rates. Full scheduler/GPU attribution still pending.
- Provisional everyday settings from the latest review: single decoder, 1×, normal DRM negotiation (Software DRM OFF), experimental clear SurfaceView ON where supported. If authorized playback fails, diagnose device/licence behavior; use only permitted fallback. On TV begin with single feed or reduced secondary quality. Actual source quality, DRM and renderer must be recorded; the clear experiment does not alter protected output policy.

### Consolidated next implementation batch (14 items; no separate competing plan)

| Item | Priority | Workstreams | Required engineering and acceptance | Status |
| --- | --- | --- | --- | --- |
| 1 | P0 | P2.1/P2.4 | Diagnose underlying TLS alert/certificate/key negotiation; test Android 12↔16 TLS 1.2/1.3, pairing/reconnect and pinning; no insecure fallback | Open; failure confirmed, cause unproven |
| 2 | P0 | P2.1/P0.1 | Baked HLS startup buffer, segment readiness, bounded retention and receiver buffering diagnostics; sustained playback/seek tests | Open |
| 3 | P0 | P0.3/P2.1 | Independent GPU overlay acquisition, adaptive encoder pacing/backpressure, measured encode and layer latency; keep actual map/tower/CC geometry | Open |
| 4 | P1 | P2.1/P2.2 | Suppress unnecessary local presentation/audio during baked Cast while retaining required source clock, decoding and composition; lifecycle/recovery tests | Open |
| 5 | P0 | P0.2/P1.1/P2.5 | Profile TV HEVC parsing/rewrite, GC and scheduling; sustain producer throughput ahead of real segment deadlines | Open |
| 6 | P0 | P0.2/P1.1 | Architecture G feasibility: benchmark native C++/Rust bounded arena/JNI against existing Kotlin range writer before migration; preserve byte-exact clear/protected invariants, ownership and licensed Widevine | Open; candidate, not proven |
| 7 | P0 | P0.3 | BufferQueue surface/codec ownership, exact release correlation, latch and physical presentation; no arbitrary pool tuning or warning suppression | Open |
| 8 | P1 | P1.2 | Measured 50/75fps pacing, actual display mode, A/V synchronization and thermal health; verify 50/100 Hz availability and mode behavior | Open |
| 9 | P1 | P0.4/P2.5/P2.8 | Locked TV map auto-hide and reveal on chrome/edit/focus navigation without trapping D-pad focus | Open; source cause confirmed |
| 10 | P1 | P2.5/P2.8 | Main + strip landscape main-left 2/3, right vertical strip 1/3; portrait bottom strip; consistent local/saved/Cast geometry | Open; source cause confirmed |
| 11 | P1 | P0.4 | Separate temporary stopped, retired and resumed race status; replay/live/Cast tests for sparse deltas and rejoining | Open; permanent latch confirmed |
| 12 | P1 | P0.4/P2.2 | Tower requests and publishes gear telemetry independently, handles partial/failed/empty archive loads and stale request ownership; validate actual F1 data | Open; likely source lead |
| 13 | P1 | P1.3/P2.5 | Device-aware safe quality/thermal defaults and renderer/DRM recommendations, verified on phone and TV | Open |
| 14 | P0 | P2.6 | Coordinated source + regression tests for every confirmed defect, exact SHA CI and matched hardware measurements | Open |

Engineering rules: one coordinated implementation pass, not a sequence of tiny speculative commits; preserve phone/TV, single/multiple decoder, clear and authorized Widevine, Google Cast and Pitwall Cast, existing login and entitlements. No proprietary SDK, paid OpenF1, session-specific patches or insecure TLS/DRM shortcuts. Keep clear capture and protected policy boundaries. Do not restart the app or publish a production release from this documentation update.



## 5. Concrete source leads and required implementation details

Paths below are relative to `app/src/main/java/app/apexview/`. Verify them against current source before editing.

### Items 1–4: Cast, security and baked stream

- `cast/local/PitwallTls.kt`, `cast/local/FcastConnection.kt`, `cast/local/PitwallReceiverActivity.kt`: the TLS failure occurs before playback; the underlying Android 12/16 incompatibility is unproven. Add bounded, redacted diagnostic cause chains; audit AndroidKeyStore EC identities, certificate validity, TLS 1.2/1.3 negotiation, peer authentication and pairing. Keep exact certificate pinning and the same legitimate media authorization. Add real Android interoperability coverage where possible; do not disable trust validation.
- `scene/BakedCastCaptureWorker.kt`, `scene/BakedCastGpuCapture.kt`, `scene/BakedCastCapturePacing.kt`, `cast/BakedCastLiveSession.kt`, `cast/BakedCastHlsSession.kt`: current source uses a fixed 25fps target, a short rolling playlist (five advertised segments), first-segment readiness and publication dependent on the next IDR and AAC availability. Verify exact current behavior. Improve startup readiness, bounded retention, backpressure and capture/overlay acquisition; measure sustained publication rate, buffering and A/V clock. Retain actual overlay geometry and captions, source media time at 1×/1.5×, independent EGL capture and supported clear fallback. Receiver feedback and publication diagnostics must distinguish producer, relay, network and receiver starvation.
- Remove only unnecessary local presentation/audio during baked casting, while the source media clock, required source decoder, composition, encoder, original commentary and relay continue. Test disconnect, seek, pause/resume, rotation/background, replacement and recovery. Native Pitwall handoff and Google baked casting are distinct routes.

### Items 5–8 and 13: throughput, memory, rendering and quality

- Profile fetch, parse, rewrite, packaging, verification, GC, worker scheduling, cache and actual buffer lead. TV must reliably produce media faster than playback consumes it. Older logs show 5.76s segments, costly composition/rewrite and buffer starvation; remeasure with exact Build 839 or the candidate on TCL. Larger buffers alone cannot solve sustained throughput deficits.
- Architecture G: assess the existing Kotlin range-writer, independent FFmpeg fixtures, direct buffers, JNI overhead, fixed native arena ownership and cancellation. Benchmark a bounded native C++ or Rust path against the same workloads if feasible. Only adopt where evidence demonstrates a meaningful benefit and byte-exact correctness, protected encryption metadata integrity and license/output policy remain intact. This is a feasibility/benchmark gate, not permission to rewrite the whole application or claim zero copy without measuring all copies.
- BufferQueue: distinguish Codec2-negotiated buffer pool occupancy from actual ownership leaks or visible frame misses. Correlate exact source/release timestamps, output surfaces, texture latch, GL submissions and SurfaceFlinger presentation; fence retired generations. Do not arbitrarily reduce buffer counts or suppress warnings. Investigate CPU scheduling, GC and GPU timing where the available traces support attribution.
- Adaptive cadence: preserve source-frame correctness, actual attached display and thermal safeguards. Establish 50fps at 1× and test 75fps at 1.5× where supported; inspect 60/120Hz on phone and possible 50/100Hz modes on TCL without assuming successful mode switching. Retain audio sync and safe fallback.
- Provisional settings: Single decoder, 1×, Software DRM OFF (normal negotiation), experimental clear SurfaceView ON where supported. For TV use lower secondary source quality when needed; this is a starting point, not a guaranteed optimal universal default. Confirm actual selected renditions, output and DRM policy. Do not treat Software DRM as a software HEVC decoder toggle.

### Items 9–12: confirmed UI/timing defects and telemetry lead

- `TrackMap.kt`: current lock predicate includes `isTv` and auto-hide explicitly excludes TV. Hide locked control after inactivity, reveal on playback chrome/edit/appropriate focus action, retain D-pad unlock access and no focus traps.
- `Layouts.kt`: `featured()` scores bottom and right strips by area. Enforce requested landscape main-left 2/3 and stacked right-strip 1/3, preserve portrait bottom strip, correct 16:9 fit and saved geometry. Share the layout contract across local and Cast paths, with tests for overlay reservations and orientation.
- `DriverTiming.kt`: the old `Retired || Stopped` condition permanently latches retired. Distinguish temporary stopped, genuine retirement and evidence of resumed participation; handle sparse timing deltas and replay seeks. Validate live/replay/tower/card/Cast consistency. Do not special-case a race or driver.
- `LiveTiming.kt`, `OverlayTowerSampler.kt`, `TvTowerRow.kt`, `TimingWorkCoordinator.kt`, `AppState.kt`, `DriverCard.kt`: gear rendering and channel-3 parsing exist. The leading hypothesis is archive telemetry ownership/loading; tower must independently request gear data, handle partial/empty/error/retry and stale session publication. Confirm real source data and runtime cause; never invent gear values. Keep independent map/tower publication and smooth interpolation.

### Item 14: regression and acceptance

Use existing tests, independent HEVC/CMAF decoding/pixel comparisons, Android rendering instrumentation, Cast protocol tests, production-wiring and release acceptance scripts. Relevant paths include `app/src/test/`, `app/src/androidTest/`, `scripts/pitwall_change_contract.py`, `scripts/validate_pitwall_acceptance.py`, `scripts/analyze_pitwall_runtime.py`, `scripts/analyze_pitwall_bufferqueues.py`, `tools/validate_hevc_full_scene.py` and `.github/workflows/android-debug-apk.yml`. Verify actual file/test names before invocation. Do not weaken existing gates.

## 6. Execution sequence

1. Read the current 16-workstream master plan and actual source; verify main/feature refs and exact green build. Gather available attached logs/traces or previously committed evidence. Record what is missing; do not stall unrelated coding on device-only evidence.
2. Reproduce/audit each of the 14 issues, identify the confirmed root cause or bounded hypothesis, and design linked regression tests. Implement the confirmed corrections in a coordinated pass. Treat native feasibility as an evidence-based go/no-go decision.
3. Run all available preflight checks, Kotlin/JVM, Python contract and source-wiring tests, HEVC/CMAF FFmpeg comparisons, Cast tests and Android instrumentation. Inspect actual failures and correct them. If the environment cannot run a required check, explicitly disclose it and use CI where available.
4. Update the sole master ledger with current status, changed paths, root-cause evidence, test results and remaining hardware acceptance. Do not replace the original plan or delete its historical requirements. Keep newly found defects in that plan.
5. Publish one coordinated source/test/plan commit to the feature branch; verify CI against that exact SHA. Correct genuine CI failures if any. Keep main unchanged. Report the green APK link only when verified.
6. Supply a precise physical validation checklist: matched phone SurfaceView/TextureView and 1×/1.5×, TCL 1/3-feed plus overlays under both permitted DRM modes, sustained 10-minute tests, Cast startup/buffering and 1×/1.5× sync, pairing/reconnect, layout/lock/gear/OUT behavior, captions and repeated seeks. Use [device profiling](../PITWALL_50FPS_DEVICE_PROFILE.md) and [aShell guide](../perfetto/ASHELL.md). Do not mark the gates closed merely because the build passed.

## 7. Required final report to user

- Exact branch HEAD, production changes, CI run number/status and APK artifact.
- All 14 items in a table: confirmed root cause or evidence status; actual implementation and coding completion; automated verification; physical acceptance/blocker. Mark 100% coding only when justified.
- Full 16-workstream updated ledger, with coding and release gates separate.
- Quantitative before/after where evidence permits, normalized for device, speed, source quality, renderer, test duration and temperature. Distinguish decoder output/application-window deadlines from physical scanout.
- What remains unresolved, why, and the exact user device tests/logs needed. No fabricated benchmark, no speculative certification, no premature release. Keep the user informed with meaningful results during the work, not repeated progress filler.

**Start actual engineering work after reading this handover. The target is completion of the known 14-item scope with reliable evidence, not another planning-only response.**
