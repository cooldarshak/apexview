# Pitwall TV / ApexView — Build 892 physical-test findings and execution handover

Date: 26 November 2026, IST  
Repository: cooldarshak/apexview  
Working branch: feature/architecture-g-rust-stitching  
Last verified green code: Build 892, acf72c61dffbe31aed4e533f46482f2eefc37568  
Purpose: resume coding immediately from the Build 892 physical-test findings, without restarting the project or repeating diagnostic tests.

## 1. Copy-paste prompt for the next chat

Read this entire handover and the authoritative Architecture G and performance master plans linked below. Continue development of the existing Pitwall TV project on cooldarshak/apexview, branch feature/architecture-g-rust-stitching. Build 892 is green. The user has already performed physical Rust playback and native heap profiling on the S22 Ultra and provided the logs, trace and screenshots. Do not ask the user to repeat Kotlin 888 or run more diagnostics before starting this engineering batch.

Start with the six workstreams in Section 6: (1) manual per-feed quality selection and transition reporting; (2) correct driver retirement/recovery; (3) graphics buffer ownership and decoder reconfiguration; (4) Java heap retention; (5) rendering allocation churn; (6) proper channel and driver names in the quality UI. Implement the whole unconditional scope in consolidated work, update both existing master engineering plans, add real regression coverage, and only then trigger the next implementation build. Intermediate checkpoint commits with CI suppressed are allowed. The restriction is on incomplete-scope builds, not partial commits. GitHub Actions is our Android/Rust compile, test and signing environment; a missing local compiler is expected and not a reason to stop.

Retain a single Architecture G plan (no separate H), the original one-decoder architecture, legitimate F1 TV access, all previously approved feature paths and settings. Do not rewrite the Android application in Rust. Rust must own the agreed app-owned stitching/audio/overlay computation wherever applicable and validated; Kotlin remains responsible for Android integration, UI/graphics presentation, lifecycle and other agreed Kotlin scope. Keep existing reference paths and explicit actual-engine reporting, not silent fallback.

Verify current branch HEAD and latest CI state before editing. If the branch has moved beyond this handover, reconcile real source and later results instead of overwriting them. Begin actual code changes, not another planning-only response. No main or parent branch changes, no weakened tests, no quality reductions disguised as memory optimizations, no unnecessary new build per small change. The user is a business analyst, not a programmer; give concise factual progress, explain material decisions, and ask for a test only when it can change the engineering decision or is needed for final acceptance. Be honest about coding completion versus CI versus physical acceptance. Do not claim 100% merely because a workflow is green.

## 2. Exact starting checkpoint and CI history

- Repo: https://github.com/cooldarshak/apexview
- Branch: https://github.com/cooldarshak/apexview/tree/feature/architecture-g-rust-stitching
- Build 892 source: https://github.com/cooldarshak/apexview/commit/acf72c61dffbe31aed4e533f46482f2eefc37568
- Build 892 workflow: https://github.com/cooldarshak/apexview/actions/runs/45774853027
- Signed APK artifact: https://github.com/cooldarshak/apexview/actions/runs/45774853027/artifacts/15096183610 (apexview-custom-debug-apk).
- Exact-build native symbols: https://github.com/cooldarshak/apexview/actions/runs/45774853027/artifacts/15095557955 (architecture-g0-apk-symbols; match ELF build ID to trace before symbolizing).
- Other artifacts: host ABI 15096116234; API 29 report 15095913837; API 31 report 15096521195; API 36 report 15096063502; unit reports 15095567919; RenderNode instrumentation report 15095734608; single-decoder instrumentation 15095999106; macrobenchmark instrumentation 15095964125; full-scene HEVC 15095836347; Cast receiver 15096376351; output parameter validation 15096406257. Artifact expiration must be rechecked.
- Build 892 all five CI jobs succeeded, including native/Android compile/test and API 29/31/36 instrumentation plus signed publication. App log identifies version 1.0.1784, build Nov 26 07:24, PID 28324, Samsung SM-S908E, Android 16/API 36.
- Build 888 green baseline: source 01c0423bfe63aeeda2eddab12a4bb748cbc8e45c, workflow 45753602860; APK artifact 15086839115, symbols 15086219482.
- Build 889 candidate aa2c5a662f75a6a7a1bf4b94b1de5aa2a0da7936: 112 Python checks and 69 Rust host tests passed; strict Clippy rejected negative assignment spacing.
- Build 890 fix 6953bf90ebe3406305b8e466bae7e93f046cabb3: compile and API 31/36 succeeded; API 29 instrumentation failed on BigInteger.longValueExact().
- Build 891 e8dcf210865a7d2f196e8ac75a8fb4028f194b3f: exact signed-long compatibility helper across seven production files and instrumentation; workflow stopped at mandatory coordinated-change contract because performance plan was not updated.
- Build 892 acf72c61: both master ledgers updated, independent JVM exact-number regression tests added, all CI green. Preserve ExactNumberCompat.kt, range/fraction rejection, API 29 compatibility, all existing host/sanitizer, ABI, upgrade, signing and instrumentation gates. Do not repeat 889–891 failures.

This new handover is documentation only and must not trigger CI. A documentation-only branch HEAD may differ from the exact green APK SHA. The next implementation run number is expected to be 893 if no intervening runs exist; verify rather than assume.

## 3. Authoritative requirements and architecture

Read these documents in full or explicitly report inaccessible ranges; the current authoritative scope and mandatory build gate near the top supersede stale status text deeper in the historical ledgers.

1. docs/handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md — single unified G plan, G.1–G.24, R1–R8, G0/G-A/G-B/G-C/G-D, M1–M13, user-approved ownership and gate clarifications.
2. docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md — original consolidated P0–P2 release/acceptance ledger, previous batch work and coordinated-change contract. Keep it current alongside G plan on every applicable source commit.
3. docs/handovers/PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md — approved M1–M13 work and memory target.
4. docs/handovers/PITWALL_TV_BUILD_888_ENGINEERING_HANDOVER_2026-11-26.md — preceding physical test evidence, exact scripts, CI and technical context. Do NOT execute its old instruction to request new Kotlin/Rust data before starting: those diagnostics were supplied and the present handover supersedes that checkpoint.

Hard requirements:
- Core one-decoder pipeline: source HEVC CMAF feeds -> composed HEVC stream -> one authorized Widevine session when applicable -> one MediaCodec decoder -> one output surface. Preserve full source feeds, timestamps, quality and legal entitlement. No proprietary SDK, no paid OpenF1, no DRM bypass or extraction of secret material. Preserve existing clear/protected profile gates.
- Existing persistent Rust setting (Native Zero-Copy On-Device Stitching using Rust), OFF reference Kotlin, ON Rust across all applicable validated processing, independent of single/multiple decoder, single/multiple feeds, clear/authorized protected playback, overlay state, phone/TV, Cast. Actual subsystem and reason-coded fallback must be explicit. Do not pretend that merely loading the library equals native dispatch. Do not activate duplicate engines or add per-frame JNI allocation/copy churn. Dormant hidden overlays must not do needless work.
- Audio app-owned metadata/clock/selection/queue/publication hot paths in Rust where applicable; Android Media3/MediaCodec/AudioTrack/MediaExtractor/audio focus/routing/DRM integration remains platform-owned. No invented native decoder/resampler/mixer or redundant transform of passthrough samples. Native Cast audio/TS was not exercised by this local phone test.
- Map/edge geometry, interpolation, driver state and render-data processing in Rust; tower timing lookup/order/dirty fields/sector processing in Rust. Kotlin retains Android layout, DPAD, accessibility, controls, rendering API integration and existing Compose/Canvas/Skia/GLES. Further snapshot and render preparation ownership remains a review item, not a claim that all map/tower work is Rust. Optimize common drawing paths for both modes; new rendering backend/allocator only if justified by measured evidence.
- Phone S22 Ultra Android 16/API 36, 120 Hz adaptive; TCL 55C655 Android TV 12, Software DRM ON required for current device compatibility. Test across Android API 29/31/36 and four native ABIs. Do not hardcode device/vendor/race names.
- All modes/features remain: TV/single/split/grid/free/snap layouts; DPAD; saved named layouts; move/resize/lock/unlock; keep-clear-of; opacity/font/dot/abbreviation controls; combined feed telemetry; timing tower/mini-sectors; map/edge/pit views; speed/seek; captions and text size/background opacity; F1 commentary/feed switch; continue watching; login. Google Cast and Pitwall Cast, direct/composed/baked/non-baked, one composed output with baked overlays where supported; no receiver decoder proliferation. Receiver protocol compatibility retained.
- Maintain existing SurfaceView experiment as an independent A/B option. Experimental clear-video SurfaceView OFF means TextureView, not turning off SurfaceTexture. The user asked why OFF: it was solely to match prior memory capture backend. Do not force it OFF for production or conflate its effect with Rust. Both paths must work.
- No local compile requirement, no new architecture branch, no restart, no main merge. CI source and docs must be coordinated. Update both master plans for source work and verify actual CI trigger. Check scripts/pitwall_change_contract.py or current workflow contract before push. Workflow ignores docs/** and **/*.md; intermediate commits [skip ci]; only final complete-scope commit should trigger build. Do not weaken tests or turn off Clippy, API 29, fixture, sanitizer, APK verification, signing, publication checks. Fix genuine failures after complete-scope dispatch and verify rerun.

## 4. Evidence available for this checkpoint

User's latest uploads (keep private, do not commit raw logs, account data or tokens; attach to new chat if not automatically available):
- pitwall-20261126-074432-668.log.txt (13,734 physical lines, including capture metadata; Files UI reports 13,735).
- pitwall-rust-892.perfetto-trace (1,538,356 bytes).
- 1000279850.jpg, 1000279851.jpg, 1000279852.jpg: quality sheet; requested 1080p main, three 540p onboards, actual source dimensions shown; one screenshot also shows incoming call.
- 1000279854.jpg: local four-feed TV layout, captions, track map, timing tower; user reports Lawson stale OUT while racing and lap data updating. Use the user's observation as defect report, not as proof of exact raw event cause. Do not infer exact driver identities solely from screenshots or internal channel IDs.
- Prior comparator: pitwall-20261126-044730-053.log.zip (contains extended Build 888 log), pitwall-kotlin-v4.perfetto-trace, pitwall-rust-v4.perfetto-trace. Prior v3/generic system traces are not a substitute for the dedicated heap trace. Build 888 old symbols must not be used for Build 892.

Log rechecked against raw upload:
- Capture ID 28324-813697240; startup at 13:14:31 IST. Thermal actual 2, thermal adaptation disabled. Content 1000010442, clear HLS replay, TV layout, four feeds, map TRACK, tower true, no Cast. Main channel-1025, initially channel-1009/1011/1016. User later changes one onboard to channel-1039 and several quality preferences. Do not hardcode mapping of those identifiers to drivers.
- 13:15:12.113 F1_COMPOSITION_SOURCE_QUALITIES: 1080p + 3x480p. 13:15:18.639 ARCHIVE_VOD_SOURCE_RENDITIONS: main 1920x1080 visible / 1920x1088 coded / HLS 1920x1080; all three onboards 640x360 visible / 960x544 coded / HLS 640x360. 13:15:18.866 F1_SCENE_UI_BOUND: atlas 1920x2176, canvas 2316x1080, TextureView, clearSurfaceAB=false, CLEAR_ARCHIVE. Do not describe the 360p sources as actual 480p, or visible tile size as source quality. Earlier transient native fallback starts at low rung 480x270 during preparation, not proof the final composed main rendition is 270p.
- Initial Rust verification from 13:15:21.608 (VALIDATED_CLEAR); initial segment 826 includes cold startup 1442 ms composition and 231232608 process allocation bytes, exclude from steady comparison. Seek back about 5 minutes at 13:15:29. Distinguish startup, seek, steady state, pause, stop, quality transitions and browse.
- 13:15:12.294 MAP_ENGINE requested=RUST actual=RUST reason=BATCH_SAMPLED; 13:15:12.296 FLAG_ENGINE FLAGS_SAMPLED; 13:15:12.554 MAP_GEOMETRY_ENGINE GEOMETRY_PROJECTED; 13:15:12.698 TOWER_LOOKUP_ENGINE BATCH_SAMPLED; 13:15:15.409 TOWER_SECTOR_ENGINE STRIP_NORMALIZED. All logged native hot-path statuses were RUST actual RUST. Stitcher startup JVM validation is expected; do not conflate it with steady native fallback. This demonstrates actual execution in this one clear local route, not all modes or the whole overlay stack.
- 13:20:45.981 two actual 1080p sources + two 360p, atlas increases to 2880x2176. 13:21:11.699 channel-1039 actual 960x540; 13:21:16.629 channel-1011 actual 960x540. 13:21:23.537 channel-1039 restored to 1080p. 13:21:35.484 two 1080p + two 540p. 13:22:01.176 main 1080p + three 540p all actual requested resolutions. These are prepared source events, not proof of instant visual activation; correlate with scene generation, codec binding and playback readiness. Incoming call and orientation/layout changes also complicate later measurements.
- Peak sampled PSS 845.525 MiB at uptime 814137404 (log line 11522), graphics 407.5 MiB, Media3 99,024,896 bytes, native engine payload 10,957,211 bytes. This is a different/higher source-quality configuration and a transition-heavy window, not matched steady-state regression proof. Investigate excess retention within equal quality/atlas separately from legitimate geometry cost.
- Final memory samples uptime 814227553 / 814237678 PSS 437.7 / 436.4 MiB with native engine/fragment arena and Media3 in-use zero; graphics ~103.6 MiB and Java PSS ~141.2 MiB. Later samples 814247548 / 814257607 show ~323.8 / 323.6 MiB total and graphics ~0.6 MiB. Final category values need validation (native/Java categories collapse while total PSS remains ~323); do not use a single potentially inconsistent memory breakdown to claim a leak or precise ownership. Full log ends before a long matched browse window; no permanent leak established.

### 4.1 Comparable memory and throughput baseline

All units MiB for memory; platform categories PSS from GC_PROCESS_MEMORY (KiB / 1024). Do not mix nativeHeapAllocatedBytes, native heap PSS, sampled native live bytes, RSS, JVM used/committed, future native reservations, cumulative copy bytes, or Media3 allocator counters. PSS categories need not add exactly to the selected independently computed medians.

| Metric | Kotlin 888 | Rust 888 | Rust 892 |
|---|---:|---:|---:|
| Steady median total PSS | 588.29 | 588.38 | 655.19 |
| Sampled peak PSS (selected window) | 605.21 | 613.79 | 659.3 |
| Median RSS | 478.17 | 476.98 | 516.84 |
| Native heap PSS | 38.23 | 77.31 | 72.70 |
| Java heap PSS | 196.95 | 158.09 | 175.12 |
| Graphics PSS | 247.43 | 247.03 | 256.51 |
| Median segment compose ms (previous analysis) | 232.5 | 32 | 36 |
| Median producer CPU ms (previous analysis) | 235.5 | 25 | 31 |
| JVM allocations/segment MiB (previous analysis) | 59.75 | 10.09 | 11.3 |

892 short window: six samples uptime 813767388 through 813817376 inclusive (13:15:42.8–13:16:32.8 approximately), after seek and before quality changes. Reproduces 655.18896484375 MiB median and 72.701/175.123/256.510 category medians. Longer first-session steady selection 813767388 through 813917372 inclusive (16 samples): median total 650.20, peak 661.55, median native PSS 76.77, Java 171.23, graphics 254.25, RSS 517.01. No improvement over old total baseline in either selection. Six-sample +11.4% against 588.29; old illustrative 20% reduction threshold 470.63 MiB; gap ~184.6 MiB. Not formal acceptance.

888 comparator from matched 20–80s after first GL during dedicated heap profiles, six samples per engine, same content and actual resolution/atlas/TextureView, but same warm PID, thermal Rust2/Kotlin1, Kotlin trace began with brief Rust before actual Kotlin session. Old matched 46 segments 788–833 (source/output extents identical); Rust vs Kotlin 7.3x composition speed, ~83% JVM allocation reduction. Build 892 prior segment summary (36 ms/31 ms/11.3 MiB) is indicative from a different range, not independently rederived in this handover; recompute by stable session and exact segment numbers if claiming new speedup. Do not oversell 6.5x as a controlled new comparison.

Current user explicitly refuses repeating Kotlin now; use historic 888 comparator for this engineering pass. 892 fresh PID vs 888 warm process, different playback ranges, trace instrumentation, layout/call/quality transitions mean cross-build and imperfect comparison. Formal G-D sign-off still needs appropriately matched evidence; do not demand it before implementing the next batch.

### 4.2 Native heap trace quality and remaining attribution

The Build 892 heap-only trace was independently parsed from its protobuf packet stream in the container: 134 TracePackets, 24 ProfilePackets (field 37), 12 ProcessHeapSamples dumps (field 5), PID 28324. All dump flags 3,6,7,8,10,14 zero; sampling interval 65536 bytes. No reported overrun in these dumps. Sampled live native MiB (allocated minus freed): 13.44, 46.10, 47.48, 47.27, 14.44, 58.62, 58.62, 19.68, 55.61, 73.12, 40.17, 64.40. Last snapshot reported records sum allocated 3084.91 MiB / freed 3020.51 MiB. These sums are sampling estimates for records represented in each snapshot, not process lifetime byte totals, PSS, or proof of leaks; they need not be monotonic. Trace overlaps reconfigurations. Do not combine its allocation totals with unprofiled playback or present as a clean 888/892 comparison.

First steady log engine payload 6,277,590 bytes (~5.99 MiB), arena capacity varying ~26–28 MiB, Media3 active ~57–65 MiB. Existing memory ownership optimization has reduced materialized buffers, not solved total PSS. Download exact 892 symbols artifact, verify readelf build ID against the trace mapping, symbolize retained and high-churn stacks, align monotonic trace clock with log timestamps using clock snapshots. Group by owner (Rust fragment arena, Rust engine, JNI scratch, HWUI/Skia/text, codec, TLS/network, diagnostic redaction, Java-side owners where visible). Exact-symbolized 892 allocation-site analysis is still a task, not already completed.

Previous Rust 888 heapprofd: last eight live median 47.81 vs Kotlin 18.84 MiB; Rust live ~26.91 MiB immutable fragment capacity and ~6.80 MiB engine buffer allocation matched explicit telemetry. Existing fragment arena output backing and lazy input optimization should be retained. Old 888 HWUI/text high cumulative churn (~2079 MiB in final represented records) but ~5.44 MiB sampled live; not graphics PSS and not evidence that UI alone owns all GPU RAM. Exact 888 Rust symbol chains: engine worker -> Atlas::write_window_fragment_to -> FragmentOutput::prepare -> Arena::take_output/output_buffer; Engine::new_reserved -> Buffers::new. 892 must be symbolized separately. External vendor codec and graphics allocations remain NOT_MEASURED. Allocation-site attribution, rather than more blind Rust conversion, is the next step.

## 5. Implementation already in place — do not redo or assume physical sign-off

G0 and G-B signed off by user, G-A foundation automated verification complete. G-C expanded implementation has green CI, partial physical engine activation confirmed, overall target failed. G-D pending. M1–M13 are the actual memory workstreams; no M14/M15 defined in master plans at this checkpoint. Retain original P0–P2 ledger and 16 workstream acceptance scope.

G-C candidate changes include lazy compact Rust slots/actual input length, native immutable output adoption and bounded fragment reuse, native geometry and interpolation, batch map/tower timing and row order, lap/weather/header lookup, flags, mini-sector normalization and immutable strip cache, Cast prediction, metadata-only audio timing, transactional audio queue, exact transport extent, native baked TS writer into immutable fragment leases, fallback continuity, network exact chunk transfer, diagnostic UTF8 size allocation removal, archive pressure trim and exact foreground transport forecast. Reference validation and JVM presentation/snapshot construction remain explicit boundaries.

Relevant Rust sources under native/architecture-g/src/: hot_path.rs, overlay_geometry.rs, jni_bridge.rs, audio_metadata.rs, ts_mux.rs, engine.rs, fragment_storage.rs, clear_atlas.rs, atlas_job.rs, lib.rs. Kotlin/Java bridges and owners under app/src/main/java/app/apexview/: tiled/NativeHotPath.java, tiled/NativeStitchingEngine.java, tiled/NativeClearArchiveBridge.kt, tiled/NativeFragmentLease.kt, OverlayPositionSampler.kt, OverlayTowerSampler.kt, DriverTiming.kt, LiveTiming.kt, TrackMap.kt, TvTowerRow.kt, Laptop.kt, cast/NativeCastPrediction.kt, cast/CastTimingProtocol.kt, cast/CastTimingPublisher.kt, cast/CmafCastAudio.kt, cast/BakedCastArchiveAudio.kt, cast/BakedCastSegmentQueue.kt, cast/BakedCastTransportStream.kt, cast/BakedCastHlsSession.kt, cast/BakedCastLiveSession.kt. Read actual source before change; these are starting points, not exhaustive call graph.

M1 process/native allocation attribution partial (external codec/GPU and exact 892 symbols open); M2 shared memory coordination exists, broader foreground/native/Media3/relay ownership review remains; M3 copy reductions implemented, inspect remaining copies; M4 compact slots implemented, audit retained owners; M5 pressure trimming implemented, verify safe release and hysteresis; M6 native lease publication and reuse implemented, verify actual ownership; M7 parser caches/descriptor reuse implemented, optimize only measured extra churn; M8 speculative and foreground forecasting partly coordinated, audit actual simultaneous allocations; M9 Media3 owner/allocator diagnostics exist, parked players need resource attribution; M10 pressure-aware unpinned cache eviction exists, protect demand/seek; M11 regressions and CI green, physical/endurance open; M12 allocator replacement conditional, no proven fragmentation reason; M13 decoder/GPU structural redesign evidence-dependent, attribution/open. Do not falsely mark these all accepted or invent additional workstream numbers. Current G.21 audio, G.22 map, G.23 tower, G.24 rendering all require remaining cross-route verification and measured benefit.

## 6. Immediate next engineering scope — execute all unconditional items in one consolidated batch

### P0-A. Manual video quality must follow user's selection

User report verbatim: "Why doesn't the feed obey what user has selected? For example, if I have selected 540p, why does it still play at 360? Same for the main feed, if I have selected 1080p, it doesn't play at that resolution."

Source-verified root mechanisms:
- Viewer.kt VideoQualitySheet (around lines 2034–2134) calls app.settings.videoQualityForFeed(label), displays requested ceiling and actualSourceRendition(label); labels use internal profileLabel. QualityDisclosurePolicy in ComposedSourceRenditions.kt explicitly treats requested quality as a maximum. F1HlsFeedCapture.kt chooseIndividual (around 700–708) filters declared formats to <= requested height and chooses highest height/bitrate. A requested 480p can therefore play 360p. No raw full rendition ladder was recorded; do not claim F1 never offers 480p. Direct media playlists have no format until inspected.
- F1HlsFeedCapture.loadFittingVod (around 205–260): mixed case selects independently, validates actual SPS/geometry, rejects incompatible atlas; common path may remove a failed size and retry smaller. Check exact mixed and common manual semantics. F1DashFeedCapture.kt has separate common/mixed selection. PerFeedQualityPolicy.kt preserves strict protected CBCS mixed-resolution gates and makes auto bandwidth/viewport policy conditional on all AUTO. AppState.kt startArchiveSingleDecoderComposition preflight around 2987–3005, chooseFeedVideoQuality around 6715, chooseVideoQuality around 6728; selected preferences persist by profileLabel. Rebuilding the session is asynchronous and old current stream may remain until ready; repeated changes can generate churn. actualSourceRendition around 6650 returns active archive source dimensions, protected source map, or actual native rendition when not composed; inspect its generation correctness.
- Build 892 logs demonstrate eventual exact 540p and 1080p playback in the same content, not persistent failure of those offered qualities. The defect includes unclear semantics and transition reporting. Do not merely rename the label or claim a CDN outage.

Deliverables:
1. Make Auto truly adaptive and manual selection prioritize/pin the exact offered compatible rendition, independently of viewport size/auto bandwidth policy. Distinguish Default inheritance from Auto. Preserve explicit user preference across feed swaps, layout edits, seeks, restart and local/Cast routing. Audit regular Media3 track selector, HLS/DASH archive and live, direct/multiple-decoder, composed clear/authorized protected, Google Cast/Pitwall Cast. Do not modify DRM or force unsafe unsupported atlas/representation.
2. Use actual manifest/track group and validated SPS evidence. Exact offered and safe rung must be honored; no silent quality reduction. If no exact rung (e.g. current 480p selection), explicitly show available alternatives and actual source; preserve preference, make downgrade reason/fallback explicit and user-visible. If exact rung cannot form supported single-decoder atlas, show specific incompatibility and recovery choices rather than silently enabling extra decoders or downscaling. No fabricated source resolution or upscaling labeled actual F1 pixels. Decide exact unavailable behavior consistently with existing preservation requirements, document any design choice. Preserve manual choice when temporarily constrained by safe device/DRM conditions; do not remove safeguards.
3. Distinguish desired quality, preparing/applying, active actual source, failed/unavailable with reason, and last successful generation. Never show a new request as already playing based on pending metadata. Correlate commit of replacement stream with first ready frame/generation and avoid stale updates after cancellation. Keep working old scene where feasible; coalesce rapid changes and cancel superseded preparation, ensure only latest request wins and old owners retire correctly. Do not repeatedly rebuild unchanged quality, leak old sessions or open unnecessary decoder(s). Avoid extra latency or blank screen.
4. Record requested resolution, offered dimensions (safe metadata only), selected declared rendition, SPS validated actual, reason code, scene/request generation, transition milestones and decoder count. No URLs, tokens, keys or user identity. UI must explain source vs visible tile vs atlas clearly, not expose internal IDs as primary names.
5. Add tests for exact rung, unavailable 480p between 360/540, manual 1080p available vs incompatible/unavailable, mixed 1080+3x540, common same-quality, auto/default, fixed playlist unknown dimensions, protected gate, change in flight/reversal, stale generation/cancellation/failure and all applicable local/Cast dispatch routes. Verify phone and TV DPAD behavior and existing caption/audio/state continuity. Inspect existing quality tests first and extend rather than weaken.

### P0-B. Correct Lawson incorrectly showing OUT and all driver status reconciliation

User: "Also, in the timing tower, I still see Lawson showing as out, but he is already racing. We are already updating the lap times, but then why do you show him out in the timing tower?"

Exact known source behavior: DriverTiming.kt apply() around lines 413–438 sets Stopped on explicit field; sets Retired only when true; clears retired/stopped on positive NumberOfLaps crossing or PitOut unless same delta asserts true. Explicit Retired=false alone is intentionally ignored. Existing DriverTimingTest.retiredStatusStaysLatchedWhenLaterDeltasClearTheFlag and stopIsTemporaryAndRetirementClearsOnNewLap cover prior defensive policy. DriverLaps.retiredAt()/atSnapshot and CardState carry latched flags. TvTowerRow.kt and Laptop.kt render OUT first when state.retired; map samplers and Cast use same historical state, including native lookup and receiver metadata. Actual Lawson raw deltas are absent from log, so precise event ordering/cause is unproven. Do not claim screenshot alone proves that specific flag was received or modify historical results for one named driver.

Deliverables:
- Audit sparse TimingData + TimingF1 merge, raw event timestamps, source provenance, cache materialization and replay partial/full replacement. Define status transitions with explicit corrections plus reliable subsequent active racing evidence: new valid sector/mini-sector progression, lap completion, pit exit, and appropriately fresh corroborated position/telemetry. Mere position ranking, changing gap, repeated last-lap values, stale mini-sector updates, speed zero or replay cache restoration are insufficient. Temporary stop, pit, retirement and recovery are distinct. Explicit authoritative status and chronology take precedence; do not un-retire genuine DNF due to stale/duplicate/out-of-order packets, and do not permanently latch an erroneous retirement.
- Preserve exact seek/reverse-time semantics; no future information leaking into historical display. Handle same-timestamp topic precedence, stale lower NumberOfLaps, late prior-lap final split, sparse missing fields, repeated corrections, first join mid-race, reconnect/full snapshot, red flag and pit lane. Check archive/startup timing snapshot version/cache invalidation so corrected parser is actually used; don't clear login or saved layouts.
- Keep shared status truth consistent across Kotlin reference and Rust selected sampling, map active/retired dots, timing tower labels, driver telemetry and Cast publisher/receiver/baked overlays. Any agreed app-owned status hot-path logic in Rust mode must be covered by actual native integration/parity, without gratuitous JNI copies or dual competing state. Do not hardcode Lawson, a year or circuit.
- Add targeted regression fixtures reproducing a false retired report followed by genuine active sector/lap progression and explicit false correction, with and without count delta; genuine retired with stale updates remains retired; temporary stop clears properly; later re-retirement; seeks on either side of recovery; local/cast parity; map visibility and OUT text match. Preserve existing defenses with meaningful tests, amend old latching expectations only with justified new chronology semantics. Add bounded diagnostic status-transition reasons; no raw full timing dump in production.
- Confirm exact status cause if raw authorized archive timing data can be safely available in existing sources; otherwise deliver generic fix and tests, label Lawson root cause as candidate until physical reproduction. No new user data collection before coding.

### P0-C. Graphics memory and decoder reconfiguration

Current first steady graphics ~254–263 MiB, old ~247 MiB; later ~400 MiB under 2x1080 + onboard quality changes. Larger atlas legitimately increases buffers; separate this from overlapping or retained owners. External codec and GPU not measured; no proof of allocator fragmentation. M13 must become an attribution-led engineering pass, not a blind graphics rewrite.

- Inspect complete playback/scene generation lifecycle: existing AppState composition startup, archive/native fallback, pending preparation and active decoder handoff, GL/TextureView/SurfaceView output, Media3 allocator ownership, parked source players, video/audio/DRM ownership, on stop/seek/layout/rotation/background/call and Cast transition. Locate exact classes with source search for F1_SCENE_UI_BOUND, BufferQueue, updateTexImage, codec release, surface lifecycle and Media3MemoryOwners. Verify only one actual video decoder in steady composed playback, transition decoder counts and why any coexistence is necessary. Maintain platform-required secure surfaces and Software DRM route.
- Prevent stale surface, EGL/GL, SurfaceTexture, RenderNode, graphics layer, display list and decoder references after generation changes. Generation-fence async callbacks and retire/reuse only once consumers/readers finish; never release active protected playback or change decoder caps/DPB/codec flags blindly. Coalesce quality rebuilds and release superseded preparation promptly. Reuse compatible existing resources when safe; separate necessary geometry changes from needless rebind. Preserve full quality, 50 fps cadence, buffer duration/seek reliability and real output.
- Distinguish BufferQueue timeout warnings during steady playback vs teardown/reconfigure, latched vs submitted vs actual displayed frames. Examine deadline misses, dropped/coalesced frames, refresh changes, queue backpressure and swap blocking. Existing log tags PitwallFrames, F1_SCENE_UI_BOUND, scene renderer interval stats, BUFFER_QUEUE_TIMEOUT, event=sample. No claims of physical scanout cadence from GL submission alone. Investigate residual 120 Hz adaptive problems without locking refresh arbitrarily.
- Add concrete owner/generation diagnostics for decoder/surface/graphics allocations where obtainable; distinguish process PSS and vendor-owned/external resources. Include count/size estimates vs measured bytes. Consider native memory/graphics capture only if the existing data cannot establish an important structural decision after coding. No allocator swap or additional GPU surface/full-frame copy by default.
- Regression coverage: rapid quality changes, cancel/replacement failure, old active scene recovery, resize/rotation, stop/reopen, seek, single vs multi decoder, secure/clear, SurfaceView/TextureView, cast handoff, TV. Actual hardware ownership acceptance deferred to targeted device evidence.

### P0-D. Java heap retention and total memory ownership

Java PSS rose from 158.09 to 175.12 MiB (short 892 window). Arena/Media3 counters zero after stop but Java and graphics remain for a period; later drop. No permanent leak proved. Native engine reduction is insufficient because total process memory is the objective.

- Reconcile per-owner unique bytes and lifecycle in MediaMemoryAccounting.kt, Media3MemoryOwners.kt, tiled/SpeculativeMemoryAdmission.kt, tiled/NativeStitchingDeviceBudget.kt, tiled/MediaPressurePolicy.kt, tiled/ArchiveBufferPolicy.kt, tiled/HevcArchiveVodSession.kt, tiled/CmafHttpReader.kt, relay/cache owners and process memory diagnostics. Audit temporary allocations and retained references in timing parse/index/startup snapshot, driver histories, map/tower cached state, Coil/image/browser owners, parked player source preparation, composition generation callbacks, network/caption/audio queues, metadata parsing and log redaction.
- Inspect exact Build 892 allocation profile and log; native trace is not Java heap dump. Use existing JVM allocated/used/committed and GC telemetry with ownership diagnostics; avoid attributing Java heap to Rust/graphics without evidence. Differentiate resident/live, reclaimable cache, capacity, future reservation, cumulative allocation/copy, sampled PSS/RSS. No summing overlapping native lease/relay/cache references. Review whether a new immutable cache replaces or duplicates prior data and whether repeated JNI calls retain duplicated arrays/strings/objects. Preserve one shared clock/session data where possible.
- Reduce avoidable copies, repeated parse/state allocations, unused parked owners, redundant retained snapshots and retired generations. Prefer bounded reusable storage, precise lifetime, cheap dirty checks, lazy initialization and safe pressure-aware eviction. Keep exact source payload and existing validated caches/leases, safe reader lifetime, prefetch/foreground priority, low-RAM TV protection. No artificial GC loops, arbitrary cache/buffer reduction, lowering video quality, or emergency eviction of pinned data to hit target. Verify idle trim and admission with actual device headroom; no speculative future reserve reported as resident.
- Keep common optimization in Kotlin where agreed; strengthen native hot paths in Rust where agreed, not arbitrary full rewrite. Extend unit/host/instrumentation tests for unique ownership, close/error/cancel, seek, retirement/pin/lease, overlapping publication, pressure, cache bounds, low-memory behavior and no regression to 887–892 copy improvements.

### P1-E. Rendering allocations and continuous overlay efficiency (still part of same consolidated batch)

- Start from exact native allocation-site analysis, previous HWUI/text/CMAF/diagnostic churn and retained draw implementation. TrackMap.kt has retained circuit/event layers, draw-phase sampling, label caches, ModulateAlpha; TvTowerRow.kt uses RenderNode region retention; OverlayPositionSampler/NativePositionBatch and NativeCircuitProjection, OverlayTowerSampler, TowerSectorNormalizer and Cast prediction are existing owners. Verify actual source, do not reimplement prior work.
- Audit and reduce per-frame/per-driver object allocations, repeated JNI metadata transfer, map projection/index/history duplication, repeated text/glyph/layout generation, Path/brush/paint churn, tower row/field snapshot rebuilding, unchanged sector strips, redraw invalidation, log/redaction overhead, and independent Cast sender data copies. Cache static geometry and labels, update only moving markers/dirty fields, retain reusable buffers, share one session clock/processed data where safe. Preserve correct invalidation on size/density/font/layout/opacity/theme/seek/timing-data replacement. Use native batched processing for agreed computation; Kotlin presentation and Android graphics APIs remain. Avoid allocating a second full-screen render buffer or new decoder. No arbitrary cap on overlay cadence or feature removals. Preserve map/tower smoothness, all settings and baked output. Conditional new native graphics backend only if profiling justifies it.
- Compare actual measured avoidable allocations and stable row/draw counts, not only overall cumulative bytes. Add parity and cache invalidation tests and maintain existing OverlayPositionSamplerTest, OverlayGeometryParityTest, OverlayTowerSamplerTest, TowerSampleVisualTest, OverlayPerformanceProbeTest, CastTimingProtocolTest, Android RenderNode and native parity tests. Target >=90% reduction in measured avoidable overlay churn for selected sites, near-zero app-owned steady allocations where feasible; not 90% of all graphics PSS.

### P1-F. Proper names in quality sheet and any similar user-visible IDs (same batch)

User: "instead of giving channel IDs, can you give the actual channel name instead? ... whether it is Max's driver feed or George's or or Hajar's ... This channel ID writing with the numbers against it does not make a good user experience."

Exact source: Viewer.kt VideoQualitySheet active tiles distinctBy feed.sourceStream.profileLabel then display Text((if bigTileIdForUi) "Main · " else "") + label. Channel in F1TvApi.kt already has title, channelId, isDefault, racingNumber, tla, driverName, team and teamColor. UiSessionModels.kt Tile has channel and feed as mutable state. Use these actual authorized metadata fields and session identity. Preserve stable profileLabel as internal preference/diagnostic key; never migrate quality overrides to mutable display names.

- Primary display e.g. Main · F1 Live / Main · International, Max Verstappen · Onboard, George Russell · Onboard, Isack Hadjar · Onboard; examples only, use actual metadata for selected race. Main is current prominent tile, not always default broadcast, so support a driver's feed as main. For non-driver feeds show actual channel title, for missing name use available title/TLA/racing number or neutral readable fallback without guessing. Audit duplicate feeds/different content, channel switch, restored layouts, stale metadata, direct/receiver UI and related quality messages where raw ID is unnecessarily exposed. Keep raw ID only in diagnostics/advanced details if needed. No reliance on year/driver number constants or third-party lookup.
- Keep sheet phone/TV readable, scroll and DPAD focus stable, preserve manual setting and actual source reporting. Tests for title/driver metadata, main switching, missing/duplicate names, stable keys, no regression of existing user selection.

## 7. Required execution, tests and completion gates

Work on all six items together. Order: verify exact repo/source & CI, analyze available trace and owners, define regression fixtures, implement P0-A/B and name UI plus shared transition ownership; implement evidence-backed P0-C/D and P1-E changes; review all paths/acceptance and coordinated docs; run available static/regression checks; dispatch one consolidated build. Intermediate source/doc checkpoints with [skip ci] allowed. Don't stop after only the easy UI issues or after creating a plan. Conditional structural graphics/allocator changes require actual evidence and may remain pending; clearly separate these from unconditional coding. If blocked by real source/tool restriction, preserve actual staged work and report exactly what remains. Do not claim background progress or ask to wait. No need for a user approval round to code this already requested next scope.

CI and tests:
- Python: python -m unittest discover -s scripts -p 'test_*.py' (112 before last CI corrective JVM tests; verify current count rather than assume).
- Run bash -n on modified shell scripts, static source/wiring/contract checks, check real Git diff including mode/scope, cross-route audit, native ABI symbols and exact test counts. Rust/Android compiler/strict Clippy/host/sanitizer/Gradle/instrumentation use GitHub Actions if unavailable locally. Tests must prove behavior and byte/timestamp/quality parity, not just strings in source. Inspect CI first failure root cause; don't treat missing final artifacts from canceled stages as independent issues.
- Update docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md and docs/handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md in coordinated source commits. Earlier Build 891 failed because plan update omitted. Keep one authoritative plan, not competing new plan. This handover is execution context, not a new architecture.
- Verify push/workflow exact SHA, job statuses and signed artifact; fix real failures without relaxing gates. Do not mark green until verified. Keep exact new native symbols for post-build analysis.

Final acceptance (original criteria still apply):
- >=20% lower matched steady total process PSS vs Kotlin same real sources/atlas/config, lower peak and no sustained growth. Historic illustrative target 470.63 MiB; recalibrate only with valid matched baseline, not a lower-quality run. Preserve all original G criteria incl >=30% stitching CPU improvement, correct payload/clock parity, reduced copies, no regressions in throughput and seek/buffer reliability. Near-zero avoidable app-owned overlay allocations / >=90% reduction of measured avoidable churn, smooth map/tower, no extra decoder or unnecessary full-frame readback, 50fps actual video presentation target, no audio glitches/drift/underruns or wrong selected commentary. Memory ownership/thermal/power improvements or no regressions. Physical phone and low-RAM TV >=2h full-race endurance with appropriate scenarios, Cast direct/composed/baked/non-baked, clear/authorized protected and single/multiple decoders, overlays on/off/both, captions and controls. CI green alone cannot sign off G-C/G-D.
- User does not want repeat Kotlin now. Existing data sufficient to start code. After consolidated green build, request only targeted Rust phone checks: quality selection exact/unavailable/rapid switch, proper names, Lawson/racing status including seek, stable replay memory and stop/release. Give precise settings and what files to provide. Decide whether exact-site profiling or graphics trace is required from evidence, not habit; no generic trace by default. Do not ask for TV/Cast or Kotlin formal acceptance until appropriate and explain why. Keep final acceptance separate from diagnostic comparisons; no release/merge/default switch without sign-off.

## 8. Phone-only profiling and tooling reference

Existing profile is pitwall-heap-only-v4.cfg, not a generic system trace. User has already run it for 892. Reuse ONLY when needed after next green build; don't ask for another capture now. Current known config:
```text
buffers { size_kb: 16384 fill_policy: RING_BUFFER }
data_sources {
  config {
    name: "android.heapprofd"
    target_buffer: 0
    heapprofd_config {
      process_cmdline: "app.apexview_custom"
      sampling_interval_bytes: 65536
      shmem_size_bytes: 8388608
      block_client: false
      continuous_dump_config { dump_interval_ms: 10000 }
    }
  }
}
duration_ms: 120000
flush_period_ms: 10000
write_into_file: true
file_write_period_ms: 5000
max_file_size_bytes: 134217728
```
aShell working workflow (when necessary): copy downloaded config to /data/misc/perfetto-configs/ if missing, use perfetto --background-wait --txt -c /data/misc/perfetto-configs/pitwall-heap-only-v4.cfg -o /data/misc/perfetto-traces/pitwall-rust-<build>.perfetto-trace ; immediately begin matched playback; after 120s copy trace to /sdcard/Download/. No root required in previously successful capture. Use unique actual build filename, not literal angle-bracket placeholder in executable commands. V3 combined capture hit size cap; v4 heap-only worked. Preserve old configs/scripts; don't request generic trace or rerun Kotlin unless decision requires it.

GitHub connector workflow: fetch_commit(repo_full_name, commit_sha=branch), fetch_file(repository_full_name, ref=exact SHA, path, optional line ranges), fetch_workflow_run_jobs(repo_full_name, run_id), fetch_workflow_job_logs(repo_full_name, job_id), fetch_workflow_run_artifacts and download_workflow_artifact. Discover exact tools and schemas in current session; do not assume access. Use atomic create_tree/create_commit/update_ref with exact parent/expected SHA, no force; alternatively staged checkpoint update_file with correct file SHA and [skip ci]. Keep docs in atomic final change. Prior remote Composio workspace session "both" may be stale, never assume its HEAD/source or persisted files; rehydrate exact verified repository archive if needed. Local environment may lack compilers/network; use established GitHub Actions, not as excuse to stop coding. Use available file tools for private uploads, no web search for private logs. Never commit user logs/trace/token material. Exact-build symbolization via readelf -n and addr2line -f -C -i; compare ELF build IDs, do not apply 888 addresses to 892. Trace processor if available, otherwise validated protobuf extraction; align clocks before analysis.

## 9. Previous complete repository reference inventory (inherited from Build 888 handover)

The following inventory and script workflow are copied verbatim from the verified Build 888 handover for continuity. Paths and tool behavior must be checked against the latest branch. Historical instructions in this appendix do not supersede the Build 892 evidence, no-repeat-Kotlin direction or current execution scope above. Additional Build 892 files/tests are listed in Sections 2, 5–7.

### 9.1. Complete repository reference inventory

Exact paths from the verified Build 888 recursive tree. Read scripts before invoking; use existing tools instead of duplicating them. All paths are linked to current branch-relative sources.

### 10.1 All existing plan, handover and technical reference documents (17)

- [`docs/BUILD_627_VEGAS_AND_OVERLAY_DIAGNOSIS.md`](../BUILD_627_VEGAS_AND_OVERLAY_DIAGNOSIS.md)
- [`docs/BUILD_629_VEGAS_PROTECTED_CABAC_DIAGNOSIS.md`](../BUILD_629_VEGAS_PROTECTED_CABAC_DIAGNOSIS.md)
- [`docs/BUILD_637_PROTECTED_SCENE_DIAGNOSIS.md`](../BUILD_637_PROTECTED_SCENE_DIAGNOSIS.md)
- [`docs/BUILD_643_LAG_AND_SURFACE_RENDERING_DIAGNOSIS.md`](../BUILD_643_LAG_AND_SURFACE_RENDERING_DIAGNOSIS.md)
- [`docs/CLEAR_WIDEVINE_PARITY_AND_NAMED_LAYOUTS.md`](../CLEAR_WIDEVINE_PARITY_AND_NAMED_LAYOUTS.md)
- [`docs/HEVC_CLEAR_CMAF_PACKAGING.md`](../HEVC_CLEAR_CMAF_PACKAGING.md)
- [`docs/HEVC_CMAF_RUNTIME_INSPECTOR.md`](../HEVC_CMAF_RUNTIME_INSPECTOR.md)
- [`docs/HEVC_STITCHING_REFERENCE_REVIEW.md`](../HEVC_STITCHING_REFERENCE_REVIEW.md)
- [`docs/LOCAL_TV_RECEIVER.md`](../LOCAL_TV_RECEIVER.md)
- [`docs/PITWALL_50FPS_DEVICE_PROFILE.md`](../PITWALL_50FPS_DEVICE_PROFILE.md)
- [`docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md`](../PITWALL_PERFORMANCE_AUDIT_PLAN.md)
- [`docs/SINGLE_DECODER_LAYOUTS_AND_CAST.md`](../SINGLE_DECODER_LAYOUTS_AND_CAST.md)
- [`docs/handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md`](PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md)
- [`docs/handovers/PITWALL_TV_BUILD_839_ENGINEERING_HANDOVER.md`](PITWALL_TV_BUILD_839_ENGINEERING_HANDOVER.md)
- [`docs/handovers/PITWALL_TV_BUILD_857_ARCHITECTURE_G_HANDOVER_2026-11-25.md`](PITWALL_TV_BUILD_857_ARCHITECTURE_G_HANDOVER_2026-11-25.md)
- [`docs/handovers/PITWALL_TV_BUILD_867_ARCHITECTURE_G_CONTINUATION_2026-11-25.md`](PITWALL_TV_BUILD_867_ARCHITECTURE_G_CONTINUATION_2026-11-25.md)
- [`docs/handovers/PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md`](PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md)

### 10.2 All Perfetto / phone-only capture assets (5)

- [`docs/perfetto/ASHELL.md`](../perfetto/ASHELL.md)
- [`docs/perfetto/capture-memory.sh`](../perfetto/capture-memory.sh)
- [`docs/perfetto/frame_timeline_export.sql`](../perfetto/frame_timeline_export.sql)
- [`docs/perfetto/pitwall-50fps.cfg`](../perfetto/pitwall-50fps.cfg)
- [`docs/perfetto/pitwall-memory.cfg`](../perfetto/pitwall-memory.cfg)

### 10.3 All scripts (analysis, CI, verification, release acceptance and tests) (28)

- [`scripts/analyze_pitwall_bufferqueues.py`](../../scripts/analyze_pitwall_bufferqueues.py)
- [`scripts/analyze_pitwall_frames.py`](../../scripts/analyze_pitwall_frames.py)
- [`scripts/analyze_pitwall_overlay_ab.py`](../../scripts/analyze_pitwall_overlay_ab.py)
- [`scripts/analyze_pitwall_runtime.py`](../../scripts/analyze_pitwall_runtime.py)
- [`scripts/architecture_g0_build_id.py`](../../scripts/architecture_g0_build_id.py)
- [`scripts/architecture_g0_stage.py`](../../scripts/architecture_g0_stage.py)
- [`scripts/check_pitwall_memory_trace.py`](../../scripts/check_pitwall_memory_trace.py)
- [`scripts/pitwall_acceptance_matrix.py`](../../scripts/pitwall_acceptance_matrix.py)
- [`scripts/pitwall_change_contract.py`](../../scripts/pitwall_change_contract.py)
- [`scripts/run_rendernode_instrumentation.sh`](../../scripts/run_rendernode_instrumentation.sh)
- [`scripts/test_analyze_pitwall_bufferqueues.py`](../../scripts/test_analyze_pitwall_bufferqueues.py)
- [`scripts/test_analyze_pitwall_frames.py`](../../scripts/test_analyze_pitwall_frames.py)
- [`scripts/test_analyze_pitwall_overlay_ab.py`](../../scripts/test_analyze_pitwall_overlay_ab.py)
- [`scripts/test_analyze_pitwall_runtime.py`](../../scripts/test_analyze_pitwall_runtime.py)
- [`scripts/test_architecture_g0_fixtures.py`](../../scripts/test_architecture_g0_fixtures.py)
- [`scripts/test_architecture_g0_kernel_vectors.py`](../../scripts/test_architecture_g0_kernel_vectors.py)
- [`scripts/test_architecture_g0_packaging_contract.py`](../../scripts/test_architecture_g0_packaging_contract.py)
- [`scripts/test_architecture_g0_stage.py`](../../scripts/test_architecture_g0_stage.py)
- [`scripts/test_check_pitwall_memory_trace.py`](../../scripts/test_check_pitwall_memory_trace.py)
- [`scripts/test_official_release_workflow.py`](../../scripts/test_official_release_workflow.py)
- [`scripts/test_overlay_runtime_wiring.py`](../../scripts/test_overlay_runtime_wiring.py)
- [`scripts/test_pitwall_acceptance_matrix.py`](../../scripts/test_pitwall_acceptance_matrix.py)
- [`scripts/test_pitwall_change_contract.py`](../../scripts/test_pitwall_change_contract.py)
- [`scripts/test_pitwall_production_wiring.py`](../../scripts/test_pitwall_production_wiring.py)
- [`scripts/test_release_acceptance_gate.py`](../../scripts/test_release_acceptance_gate.py)
- [`scripts/test_release_gate.py`](../../scripts/test_release_gate.py)
- [`scripts/validate_pitwall_acceptance.py`](../../scripts/validate_pitwall_acceptance.py)
- [`scripts/verify_architecture_g0_apk.sh`](../../scripts/verify_architecture_g0_apk.sh)

### 10.4 All fixture generation / validation and utility scripts (13)

- [`make_icon.py`](../../make_icon.py)
- [`tools/generate_hevc_fixtures.py`](../../tools/generate_hevc_fixtures.py)
- [`tools/generate_hevc_tile_2x2_fixtures.py`](../../tools/generate_hevc_tile_2x2_fixtures.py)
- [`tools/generate_hevc_tile_fixtures.py`](../../tools/generate_hevc_tile_fixtures.py)
- [`tools/package_cast_receiver.py`](../../tools/package_cast_receiver.py)
- [`tools/test_cast_layout_receiver.mjs`](../../tools/test_cast_layout_receiver.mjs)
- [`tools/test_cast_receiver.mjs`](../../tools/test_cast_receiver.mjs)
- [`tools/validate_hevc_clear_cmaf.py`](../../tools/validate_hevc_clear_cmaf.py)
- [`tools/validate_hevc_cmaf_grid.py`](../../tools/validate_hevc_cmaf_grid.py)
- [`tools/validate_hevc_full_scene.py`](../../tools/validate_hevc_full_scene.py)
- [`tools/validate_hevc_output_parameters.py`](../../tools/validate_hevc_output_parameters.py)
- [`tools/validate_hevc_rust_gb_grid.py`](../../tools/validate_hevc_rust_gb_grid.py)
- [`tools/validate_hevc_tizen_grid_composer.py`](../../tools/validate_hevc_tizen_grid_composer.py)

### 10.5 All workflow definitions (4)

- [`.github/workflows/android-debug-apk.yml`](../../.github/workflows/android-debug-apk.yml)
- [`.github/workflows/cast-receiver-pages.yml`](../../.github/workflows/cast-receiver-pages.yml)
- [`.github/workflows/official-release.yml`](../../.github/workflows/official-release.yml)
- [`.github/workflows/pitwall-approved-release.yml`](../../.github/workflows/pitwall-approved-release.yml)

### 10.6 Native benchmark and fixture entry points (8)

- [`native/architecture-g/Cargo.lock`](../../native/architecture-g/Cargo.lock)
- [`native/architecture-g/Cargo.toml`](../../native/architecture-g/Cargo.toml)
- [`native/architecture-g/src/bin/bench.rs`](../../native/architecture-g/src/bin/bench.rs)
- [`native/architecture-g/src/bin/cmaf_bench.rs`](../../native/architecture-g/src/bin/cmaf_bench.rs)
- [`native/architecture-g/src/bin/feasibility_bench.rs`](../../native/architecture-g/src/bin/feasibility_bench.rs)
- [`native/architecture-g/src/bin/gb_clear_grid.rs`](../../native/architecture-g/src/bin/gb_clear_grid.rs)
- [`native/architecture-g/src/bin/slice_bench.rs`](../../native/architecture-g/src/bin/slice_bench.rs)
- [`native/architecture-g/src/bin/support/mod.rs`](../../native/architecture-g/src/bin/support/mod.rs)


### 9.2. Script usage map and verification workflow

- Full static Python regression: from repository root run
  `python3 -m unittest discover -s scripts -p 'test_*.py' -v`.
  Build 888 pre-commit 110/110. Run `python3 scripts/pitwall_change_contract.py`
  in a real checkout with appropriate git baseline; remote staging may use
  validate_change_set on changed paths. Run available Rust/Android/fixture
  gates before functional commit; if unavailable, explicitly state and use
  unchanged CI. Do not claim Rust/Android pass based on Python.
- `analyze_pitwall_runtime.py` for matching app-version intervals/GC/CPU,
  `analyze_pitwall_bufferqueues.py` for queue identity/ownership warnings,
  `analyze_pitwall_frames.py` + `frame_timeline_export.sql` for physical
  FrameTimeline and app/SurfaceView distinctions,
  `analyze_pitwall_overlay_ab.py` for matched OFF/MAP/TOWER/BOTH windows
  (use actual timestamp boundaries).
- `pitwall_acceptance_matrix.py` and `validate_pitwall_acceptance.py`
  enforce real physical evidence and release acceptance. The original
  10-minute 1x/1.5x OFF/MAP/TOWER/BOTH matrix and TV/Cast release checks
  remain. Follow actual script CLI and schema. Do not fabricate passing
  evidence or edit acceptance manifests to bypass gate.
- `architecture_g0_build_id.py`, `architecture_g0_stage.py`,
  `verify_architecture_g0_apk.sh`, `run_rendernode_instrumentation.sh`
  protect exact-source package, ABI/symbol identity and instrumentation.
  Read .github/workflows/android-debug-apk.yml for authoritative commands.
- All fixture generation and independent validators in tools/ are retained;
  use real synthetic HEVC fixtures and independent FFmpeg decode, native/JVM
  byte differential and negative/cancellation tests. Synthetic encrypted
  labels are not real authorized F1 Widevine proof.
- Reference research: original Architecture G master section 8 and Build
  880 section 5 contain full primary links. Retain prior findings from
  GStreamer bufferpool, Tokio Bytes, Cloudflare DNS compact capacity,
  Datadog allocation/scratch, Zerodha Blobasaur bounded concurrency
  (not proof of RAM reduction; do not copy AGPL code), Discord Rust GC,
  Agentgateway bounded queue, ChainSafe allocation analysis, Perfetto,
  AndroidX Media3, DHAT, Parallax, Lumina, Firecracker. Reuse findings;
  don't rerun broad research unless a measured new problem requires it.

## 10. Start now

Begin with P0-A through P1-F, not another handover or request for old diagnostic inputs. Carry through actual coding, coordinated tests and exact-source CI. All six workstreams belong in the next consolidated engineering change, with evidence-dependent exceptions documented. G-C remains open. Rust is real and valuable for compute, but the total RAM target has failed and three user-facing defects need correction. Do not mark them resolved until implementation and applicable verification support that conclusion.
