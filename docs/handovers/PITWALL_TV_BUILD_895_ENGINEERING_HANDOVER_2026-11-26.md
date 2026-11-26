# Pitwall TV / ApexView — Build 895 engineering handover
**26 November 2026 | Continue existing Architecture G | Build 895 GREEN**

## 1. Copy-paste prompt for the next chat

Read this entire file from cooldarshak/apexview, branch feature/architecture-g-rust-stitching. Read the complete current master performance plan and Architecture G master handover linked below, including their latest appended checkpoints, and the approved Build 880, 888 and 892 handovers. Verify the latest branch HEAD and exact green CI before touching code. Build 895 is green. Its physical phone test exposed a confirmed startup overlay regression and unresolved total memory consumption despite substantially faster steady-state Rust overlay and stitching processing.

Continue development immediately on this existing branch, not a fresh project. Execute all unconditional coding in the next consolidated G-C scope in section 7, resolve outstanding applicable G.21–G.24 integration gaps, retain all P0-A–P1-F implementations and M1–M13 obligations, and update BOTH master ledgers. Start with the confirmed timing publication regression, not a speculative allocator rewrite. Use existing logs, trace, source, tests and previous research. Do not request that the user repeat tests merely to begin work. Keep the Rust engine and existing approved Android platform boundaries. Intermediate source/documentation commits with CI suppressed are allowed; trigger one consolidated signed APK build only after completing the entire agreed unconditional coding scope and available regression checks. Fix any resulting failures without bypassing or weakening gates. Do not change main or the parent branch. Report exact commit, CI run, genuine unresolved blockers, and separate coding, automated verification, and physical acceptance. Do not claim G-C sign-off or achieved memory goals until the evidence supports them. No further device test is needed to begin the work.

After that consolidated build is green, the previously recommended workflow is an independent Astra Pro memory/architecture audit of the exact candidate and existing evidence, followed by implementation of approved audit recommendations and controlled physical acceptance. That audit is not permission to pause the present engineering work. Do not make a separate Architecture H or restart the project.

## 2. Starting checkpoint, branch and CI history

- Repository: https://github.com/cooldarshak/apexview
- Working branch: https://github.com/cooldarshak/apexview/tree/feature/architecture-g-rust-stitching
- Exact Build 895 source: d5fd4bc4fa5e69bd2fe9aa2de635e3d47ded66f1
- Build 895: https://github.com/cooldarshak/apexview/actions/runs/45804191998
- Verified on 26 November: all five required jobs completed successfully: build/host/unit tests 139854981470; Android API 31 139860714500; API 36 139860714595; API 29 139860714633; signed final APK publication 139862517383.
- Physical APK version 1.0.1790 (Nov 26 10:47 UTC build metadata). Earlier physical APK version 1.0.1784 corresponds to Build 892, exact green source acf72c61dffbe31aed4e533f46482f2eefc37568, run https://github.com/cooldarshak/apexview/actions/runs/45774853027.
- Build 893 (0355c8843483ed24ad183f922efb430cc8f55b22; run 45794381473): 77/77 Rust host tests and sanitizer run passed; Android compilation failed because direct receiver quality selection used the generic Media3 TrackSelectionParameters builder for a DefaultTrackSelector-only method.
- Build 894 (b78cd087dad95a439dced39b1ab95730f5ec9e17; run 45799513249): fixed using the exact load-owned DefaultTrackSelector and preserving strict video constraints, existing audio/text selection, and lifecycle. Build/unit job succeeded, but API 29/31/36 instrumentation failed the shell footer check, although all five G-B JUnit tests actually passed.
- Build 895: updated exact required G-B footer from four to five tests and added regression matching all nine mandatory class counts to JUnit annotations. Counts are 2/4/2/5/2/1/1/1/2; no test disabled. 115/115 Python checks and bash syntax check passed before branch update. All CI jobs green. No playback production source was altered by the 895 test-runner fix.
- HEAD before adding this documentation is the exact Build 895 SHA. This handover is a documentation-only continuation, not a new APK. Use exact build SHA rather than later documentation HEAD when retrieving APK, symbols and physical evidence. Confirm branch ancestry and any newer work before implementation.

## 3. Authoritative plans and reference hierarchy

All paths relative to repository root. Read full documents, not only the table of contents or stale historical statuses. The newest explicit user-approved requirements and append-only checkpoints control conflicts. This file adds the 895 physical findings and next action list; it does not replace, narrow or restart any existing plan.

1. [Full original P0–P2 performance and release ledger](../PITWALL_PERFORMANCE_AUDIT_PLAN.md)
2. [Architecture G consolidated Rust master plan](PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md) — G.0, G-A, G-B, G-C, G-D, G.1–G.24, R1–R8, M1–M13, unified ownership revision after 888, acceptance and latest build corrections.
3. [Build 892 physical-test execution handover](PITWALL_TV_BUILD_892_PHYSICAL_TEST_ENGINEERING_HANDOVER_2026-11-26.md) — complete P0-A through P1-F details, 892 native attribution, scripts, tooling and full inherited inventory.
4. [Build 888 engineering handover](PITWALL_TV_BUILD_888_ENGINEERING_HANDOVER_2026-11-26.md) — previous traces, unified native memory and overlay scope, and complete repository reference inventory.
5. [Approved Build 880 G-C memory optimization plan](PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md) — M1–M13, approved success criteria, research, controlled protocol and implementation strategy.
6. [Build 867 Architecture G continuation](PITWALL_TV_BUILD_867_ARCHITECTURE_G_CONTINUATION_2026-11-25.md) and [Build 857 Architecture G handover](PITWALL_TV_BUILD_857_ARCHITECTURE_G_HANDOVER_2026-11-25.md) — earlier foundation/port decisions and historical evidence, not permission to undo signed-off work.
7. [Phone-only aShell/Perfetto workflow](../perfetto/ASHELL.md) — use current heap-only v4 instructions in 892 handover and section 10 below, not stale baseline/version references.

### 3.1 Non-negotiable user requirements

- Existing Android phone and Android TV app; preserve all layouts (single/split/grid/TV/free/snap), named persistent layouts, DPAD, continue watching, move/resize/opacity/font/lock/keep-clear controls, audio selections, captions and appearance, seek/speed, map/edge map, tower, driver labels and live/replay.
- One composed HEVC stream, one hardware video decoder, one output surface for single-decoder composition; one authorized Widevine session where required. Multiple-decoder option must also remain usable. Do not add hidden decoders or full-frame copies as an apparent performance fix.
- Preserve legitimate F1 TV login, entitlement, Widevine and secure output, existing local Cast mutual TLS/auth, signing/package identity and upgrade compatibility. Never bypass DRM, weaken security, or special-case one race such as Las Vegas.
- Apply across clear and authorized protected media, single and multiple feed/decoder, phone and TV, local, Google Cast, Pitwall Cast, baked and receiver-rendered map/tower/audio/caption modes. Baked routes must keep the agreed complete picture and commentary in a continuous stream where supported.
- Rust ON means actual Rust for applicable validated stitching and app-owned audio/map/tower numerical processing; Rust OFF is the existing equivalent JVM reference. Distinguish requested vs actual per subsystem, reason-coded fallback, NOT_APPLICABLE only when no work is needed. Missing implementation is not a successful native route. Keep Kotlin/Compose for Android UI, accessibility, graphics/platform binding, playback coordination, networking/auth/DRM, Media3/MediaCodec/AudioTrack. Do not move numerical hot paths back to Kotlin or rewrite the entire UI into Rust. No proprietary SDK or paid OpenF1.
- Manual per-feed quality is exact when genuinely offered/compatible; hide EVERY unavailable rendition, not merely 480p. Do not silently lower quality, frame rate, buffer safety or feature coverage for a memory win. Explicit reason when a requested incompatible selection cannot be supported. Auto alone may adapt according to approved policy; keep user's thermal adaptation option.
- Single consolidated change per agreed implementation batch; intermediate commits use [skip ci] where supported, check all workflow triggers. Complete 100% of unconditional agreed coding scope, add appropriate tests and update both ledgers before one normal CI trigger. If CI fails, correct actual defect. No tiny speculative feature builds, no weakened assertions, no unsigned artifact or bypassed publication. Do not report an unfinished checkpoint as tested.
- User is a business analyst, not a coder. Be concise and clear about actual results, exact next step, whether testing is needed, and what is not yet complete. Do not promise background work or ask to wait.

### 3.2 Phase and workstream status after 895

| Area | Coding and verification state | Remaining |
|---|---|---|
| G.0 | Signed off by user; all 895 gates green | Preserve |
| G-A | Foundation implemented and verified | Preserve |
| G-B | Signed off by user; all 5 instrumentation tests on each required API | Preserve |
| G-C | Significant implementation and CI complete, NOT signed off | Remaining unconditional source gaps, new 895 startup regression, evidence-led optimization, applicable route audit, physical checks |
| G-D | Open | Full physical A/B, feature/DRM/Cast/endurance and release acceptance |
| P0-A–P1-F | All six implementations and CI in 895 | Physical verification; overlay publication regression reopens P0-B/P0-D/P1-E and G.22–G.24 related acceptance, not a blanket rollback |
| M1–M11 | Previously implemented scope (883 ledger), subsequent fixes through 895 | Reconcile residual issues with actual owner evidence; matched profiling and acceptance |
| M12 | Conditional, system allocator retained | Only experiment if allocator fragmentation/retention is proven material |
| M13 | Instrumentation and feasible lifecycle safeguards implemented | External decoder/GPU attribution, evidence-led fixes; structural redesign conditional |
| G.21–G.24 | Substantial native audio/map/tower/render integration implemented; later checkpoints cover identified hot paths | Final complete cross-mode audit and remaining applicable coding, startup defect, matched performance and parity |

Do not mistake historical open/closed wording or a green build for universal physical support. Preserve the original full P0–P2 matrix and signed-off phase scope.

## 4. All plan mappings and scope preservation

### 4.1 Original sixteen workstreams

P0.1 segment priority and exact seek; P0.2 HEVC memory/delivery; P0.3 rendering/BufferQueue/GPU; P0.4 map/tower; P1.1 atlas/geometry and single decoder; P1.2 50/75fps cadence/refresh; P1.3 quality/thermal; P2.1 Pitwall/Google Cast; P2.2 playback/timing coordinators; P2.3 release/updates; P2.4 authorization/security/TLS; P2.5 TV; P2.6 tests/benchmarks/endurance; P2.7 core architecture; P2.8 Material 3 Expressive UI; P2.9 closed captions (rolling HLS, alternate programme, size/background opacity and route acceptance). Exact criteria and current ledgers are in the master performance plan. Preserve audio publication-copy reduction and playback/timing coordinator ownership work, including cancellation and seek generations.

### 4.2 Architecture G core and refinements

G.1 profile reference stitcher; G.2 bounded arenas; G.3 HEVC parsing/stitching; G.4 CMAF moof/mdat assembly; G.5 authorized DRM compatibility; G.6 native leases/lifecycle; G.7 bounded deadline queues; G.8 direct buffers/JNI; G.9 reduce network-to-native copies; G.10 low-copy Media3 publication; G.11 SIMD/NEON only with hotspot evidence; G.12 reusable/low-allocation hot paths; G.13 seek/cancel/generation fencing; G.14 compatible feed switch and priority prefetch; G.15 fuzz/sanitizer/leak gates; G.16 differential parity and independent decode; G.17 adaptive low-RAM budget; G.18 full profiling; G.19 all eligible local/Cast modes and actual native attribution; G.20 Android ABI/API/signing/upgrade compatibility.

R1 fused processing, R2 deadline-aware scheduling, R3 device-adaptive arenas, R4 measured bounded worker concurrency, R5 batched JNI, R6 reusable validated stream analysis, R7 layout-aware Auto quality, R8 end-to-end attribution. No arbitrary SIMD or worker pool, additional decoder or native renderer purely to tick a checklist.

G.21 Rust app-owned audio processing and low-copy publication (supported AAC/container/sample parsing, timestamps, continuity, queues, selected commentary and actual route ownership). G.22 Rust track/edge-map geometry/index, interpolation, drivers/flags/animation and reusable render descriptors; G.23 Rust timing tower snapshot normalization, ordering/rows, sectors/laps/intervals/flags/header/status/animation and stable descriptors; G.24 shared efficient retained drawing and rendering integration (map, tower, local and baked/native receiver). All are part of G-C, not separate Architecture H. Browser receiver where native is unavailable retains supported protocol/rendering; document the actual owner, never assert it runs the Android Rust engine. Android platform rendering, Media3/MediaCodec/AudioTrack, secure surfaces and UI stay with appropriate platform APIs.

### 4.3 Approved M1–M13

| ID | Requirement | Next focus |
|---|---|---|
| M1 | Heap allocation-site and process memory attribution | Exact 895 symbols, valid trace parsing; distinguish live/cumulative/PSS and external GPU |
| M2 | Unified device-headroom/byte budgeting | Check forecast vs actual reservations, overlapping owners, no double counting or foreground starvation |
| M3 | Eliminate overlapping copies | Timing archive Java ownership and native/publication boundary audit; preserve payload equivalence |
| M4 | Lazy native buffers | Retain lazy compact slots and exact counters; check residual eager materialization |
| M5 | Adaptive safe trimming | Preserve active readers, generation safety, warm reuse; measure refetch/reallocation costs |
| M6 | Compact immutable storage | Retain bounded native fragment leases and immutable timing data; avoid duplicated histories |
| M7 | Reusable parser/workspace | Preserve generation-fenced caches and right-sizing; evidence before further restructuring |
| M8 | Byte-based prefetch/queues | Foreground first, bounded pending + published bytes across all owners |
| M9 | Coordinate Media3 allocation | Measure active vs parked allocator and safe trim; keep time/byte safety and quality |
| M10 | Pressure-aware cache eviction | Fix replay preview/full cache compatibility and safe monotonic publication |
| M11 | Allocation and regression gates | Add cold/warm/upgrade timing regression and ownership/allocation tests to existing CI |
| M12 | Conditional allocator comparison | Do not swap allocator on current evidence |
| M13 | GPU/decoder memory optimization | Investigate real surface/codec/vendor memory ownership and presentation before changing backend |

### 4.4 Six Build 892 follow-up workstreams (already coded in 895)

- P0-A: advertised actual quality options, all unavailable rungs hidden, manual source heights, generation/request snapshots, composed/baked/receiver transitions, stale protection, remote media identity and actual render acknowledgement. Baked transition may briefly interrupt receiver while preserving one decoder; no simultaneous extra decoder. Keep selected commentary and clear/protected policy. Custom CAF receiver source updated/tested; separate hosted deployment is not established; do not silently deploy without appropriate approval.
- P0-B: driver status/OUT recovery including Lawson, sparse TimingData vs TimingDataF1 precedence, stale lap/sector rejection, Rust retirement/recovery with exact chronology. Exact Lawson historical incident not physically reproduced; no uncorroborated telemetry un-retirement.
- P0-C: graphics resource ownership, EGL/surface generation fencing, exact detach/release, validated geometry, diagnostic owner counts. External memory remains unmeasured.
- P0-D: native pressure watermark so old leased backing retires on final release instead of re-entering idle pool; lazy right-size input, reduce JVM copies/retention, parked player allocator diagnostics.
- P1-E: immutable timing sector list reuse, cached Cast geometry/table fingerprints, stopped flag persistence, reduced churn and existing retained layers. Cache format v3 invalidates old v2 cache; 895 startup regression must be corrected.
- P1-F: validated full driver names using session identity/number/TLA, proper UI labels, fallback without inventing data or changing stable internal IDs.
- Key current source changes are recorded at the tail of both master plans under Build 892 candidate, 893 compilation correction, 894 instrumentation correction. Do not reimplement completed steps unnecessarily.

## 5. Physical test inputs and reproducibility (latest user-provided files)

Phone: Samsung Galaxy S22 Ultra SM-S908E, Android 16 API 36, adaptive 120 Hz. TV target: TCL 55C655 Android TV 12, usual Software DRM ON to prevent previously observed crash/corruption. No TV capture on 895 yet. Replay content 1000010442 (Las Vegas race); actual four-feed Rust clear archive composition, TV layout with tower and TRACK map, 1x main playback. Later actions include quality/layout/scene transitions; do not pool them with steady comparison.

Source uploads in current conversation (raw private logs/traces must NOT be committed; new chat can request access to existing project attachments only if unavailable, and must not demand recapture before starting):
- pitwall-20261126-074432-668.log.txt — despite filename, combined capture contains earlier app 1.0.1784 PID 28324 and later 1.0.1790 PIDs 20365 and 21226. 21,350 lines by local splitlines count. SHA256 50062c1401c94902c674bc02a8d1af95cd9cb1dd3d7e0ce745633f2bed6437ed.
- pitwall-20261126-111807-938.log.txt — continuation segment 2 of PID 21226, 1.0.1790. 3,047 lines. SHA256 adaa2b7dfe4cfd8bb2f3bdf29a2bbdc1317af2ae92c4216d9fac3b8037eabfa0.
- pitwall-rust-895.perfetto-trace — native heap-only v4 capture, SHA256 6a436c5ab860d50368636bdd83d00aa30035d9669c500463746a2db129a255e9. Prior audit reported partial decode; do not fabricate complete trace parse or exact source-line attribution.
- Prior Build 888/892 log/trace analysis and matching ELF symbol evidence is in their handovers. Earlier raw captures may require user/project attachment access, but existing findings suffice for coding start.

## 6. Build 895 physical audit and conclusions

### 6.1 Confirmed functional startup regression: map and timing tower blink

User report: multiple visible blink/load cycles, then stable after five to six seconds, worse than before. Treat as P0. Verified against raw log, not an inference from generic jank.

Earlier 1.0.1784: trace 813736119 initial partial at 90 ms with 22 laps, 23 cars, 362 outline points; 22 rows/cars ready around 1.10s, replay index hit, three partials, complete at 4,139 ms. Second trace 814023279: index hit, three partials, complete at 1,562 ms, stable 362-point outline. These were warm-cache cases.

895 first interrupted fresh run trace 9292348 PID 20365: index miss, no usable data at 1s/5s; 22 tower rows at 7,153 ms, first 22 positioned cars at 9,889 ms, outline 591 then 365 points. Do not call this a completed archive load.

895 second run trace 9386484 PID 21226, timing start 16:44:57.498 IST:
| elapsed ms | actual observed data |
|---:|---|
| 87 | Saved preview: drivers=22 laps=22 cars=22 outline=365 |
| 90 | Full replay index miss |
| 100 | Season index hit (separate cache) |
| 1089–1091 | 22 ranked rows and 22 positioned cars; both ready; recorded readiness 1088ms |
| 3727 | New partial: laps=0, cars=22, outline=366; previously ready tower data discarded |
| 4604 | Another partial still laps=0 |
| 5127 | 5-second checkpoint: rankedRows=0, positionedCars=22, although previously marked ready |
| 6985 | 22 lap histories restored; 22 cars, outline 366 |
| 8160, 8898, 14095, 15794 | Additional partials |
| 20349, 20719 | 23 cars, outline changes to 591; track coverage changes |
| 22928, 23253, 23562 | Outline changes to 362; further partials |
| 24344 | Replay index saved (1035ms save) |
| 24897 | Archive complete: 22 laps, 23 cars, outline 362 |

There are 13 partial publications in this specific completed load (earlier response initially said 14, then corrected to 13). Tower disappearance is confirmed; map geometry churn correlates with observed blinking but exact rendered visibility/frame sequence is not directly measured. The 5–6s user observation aligns with valid tower data being lost/restored. The readiness diagnostic records first readiness, not continuous availability; enhance it to record regressions and stable readiness. Compare warm-to-warm and cold-to-cold before claiming 6x slowdown.

Source audit at exact 895:
- app/src/main/java/app/apexview/AppState.kt loadTiming (around 4195) clears timing on session start; followReplayTiming (4200–4350) invokes showTiming(partial) for every owned onPartial; showTiming (4472) assigns timing=loaded unconditionally. saveReplayPreview called for usable map/laps, mapStatus may change with each partial, layout reservation occurs on first timing object. Do not bypass session ownership or keep previous race data.
- LiveTiming.kt LiveTimingArchive.load (782 onward), loadCore, publishTopics (906), publishTracks (944): partialTracks/partialOutline/partialHistory start empty; TimingPreviewEpochs and positionPreviewEpochs reject older in-flight updates but do not prevent newer incomplete snapshots from replacing prior usable cached preview. TimingPreviewSnapshot taken on Main then SessionTiming materialized on Default; verify exact preview seeding and coordination across saved preview, topic assembly, positions, full result, actual playhead and seek. Race between independent topics is not solved merely by an epoch check.
- ReplayCoreCache.kt VERSION 2 at 892 (line 317); VERSION 3 at 895 (line 327); strict VERSION equality on read. v3 persists stopped flag previously omitted and reuses unchanged three-sector lists. Upgrade invalidation plausibly causes the first cold rebuild. The second run is after interrupted initial load, so it can have a saved preview but no completed core index even with v3. Do not assert format migration alone caused every index miss. Preserve stopped semantics; never silently interpret v2 bytes as v3.

### 6.2 Memory: exact selected window, independently recalculated

Combined log lines 18867–19838 (inclusive, 1-based), PID 21226, boot uptime 9419858–9529849ms, 12 samples over 110s, same active engine ID 1 and steady four-feed playback before later changes. PSS in KiB/1024, owner byte counts /1048576. Do not compare aggregate whole-log mixed sessions.

| Measurement | Median MiB | Peak sampled MiB |
|---|---:|---:|
| Process PSS | 649.585 | 678.486 |
| Platform graphics PSS | 247.088 | 247.777 |
| Platform Java heap PSS | 189.076 | 204.492 |
| Platform native heap PSS | 73.541 | 81.246 |
| Native fragment arena capacity | 24.790 | 25.787 |
| Native engine materialized payload | 7.814 | 7.814 |
| Media3 in-use allocator bytes | 76.969 | 81.188 |

Three parked players report zero media items, allocator bytes and video/audio decoders. Do not equate zero Media3 bytes with zero total vendor/ART ownership; inspect residual objects if evidence warrants. Do not add owner capacity on top of PSS (overlap). OS has headroom; reserved/future commitments are not live memory; externalCodecGpu and sceneGraphicsBytes explicitly NOT_MEASURED. Peak sampled != continuous maximum.

Historical prior audit medians: 888 Kotlin 588.29 MiB; 892 Rust 655.19 MiB; 895 Rust 649.58 MiB. Latest is about 5.6 MiB lower than 892, still about 10.4% above historical Kotlin, NOT a matched clean-process A/B. Illustrative 80% target from Kotlin=470.63 MiB. Must recalculate with same signed build, settings, playhead and test environment. 888 earlier profiled comparison also had almost equal Kotlin/Rust PSS ~588.29/588.38; do not confuse that specific older A/B with 892 comparison. 20% lower total RAM not demonstrated.

Continuation log: later quality/scene changes replace native engine IDs 2/3/4. Example uptime 9579860: PSS 714212 KiB, graphics 302280 KiB, Java 177992 KiB, native 78560 KiB, three parked owners zero, native fragments 25,691,512 bytes, engine materialized 7,526,995 bytes, Media3 71,565,312 bytes, one TextureView EGL context/window/decoder texture, atlas 1920x2176, output 2316x1080. Transition peaks and multiple owners must be analyzed separately. At uptime 9649979 native fragment capacity/engine payload/Media3 bytes and scene graphics owners zero. Later PSS ~193–195 MiB; graphics 580 KiB; process remains alive. These observations support release, not proof of no leak across full race/endurance or all routes.

### 6.3 Heap trace and historical allocation evidence

Previous 895 audit reported recovery of 11 heap snapshots, 64 KiB sampling, no reported buffer overrun in recovered records, peak recovered live 60.21 MiB, last 59.70 MiB, largest retained group 24.90 MiB, input workspace ~7.81 MiB. Trace framing was malformed/incomplete in that analysis; these are provisional recovered-profile estimates, not a fully symbolized heap report. Independently validate packet continuity, dump completeness, PID, sampling and clock correlation using official Perfetto tooling/protobuf. Preserve original trace, hash, and distinguish recovered vs missing samples. Retrieve exact 895 architecture-g0-apk-symbols artifact from the successful run; verify arm64 ELF GNU build ID against trace mappings; use exact source before symbolizing. Do not use 892 symbols or infer exact function from an approximate number. Do not conflate sampled live heap with process PSS or cumulative alloc churn.

Historical 892 exact-build attributed native sampled peak ~73.12 MiB: composed output 45.68, input slots 9.82, HWUI/Skia/text 8.38, media/codec 6.31, ART/JNI 2.80, parser .06; final ~64.40 total (output 37.71, input 10.45, HWUI 7.62, media 6.06, ART 2.37, parser .06). During earlier 892 transition PSS ~845.5, graphics 407.5, Java 186.2, Media3 94.4 MiB. Retained output/input and graphics remain meaningful; not proof of fragmentation. Native 895 storage/release looks encouraging. No OOM/fatal/JNI linkage error identified in latest playback process by previous audit. Do not treat absence of an error in this run as proof of universal safety.

### 6.4 Overlay and playback performance (prior audit, validate same windows before using as regression baseline)

Median of reported per-window mean processing time (ms), selected 892 vs 895 playback windows, not frame-level p95 or controlled allocation reduction:
| Owner | 892 | 895 |
|---|---:|---:|
| Timing tower sample | 5.298 | 1.530 |
| Map driver position | .852 | .394 |
| Map motion clock | .567 | .289 |
| Tower row draw | .653 | .310 |
| Map car/flag draw | 2.747 | 1.683 |

Actual Rust execution reported for map positions/geometry, tower lookup, sectors, flags and driver retirement reconciliation. Preserve those improvements. Reported elapsed costs are not evidence of 90% avoidable allocation reduction, actual display FPS or zero GPU churn. Audit all matching owner names and sample windows.

Steady four feeds main 1080p + three 540p, one actual hardware HEVC decoder, 1x, actual Rust; median stitching 39ms, median segment prep 887.5ms; previous audit counted 5,581 rendered frames and zero dropped during selected interval, 50fps decoder rate; panel 120Hz. Approx. 32% UI frames classified slow in prior selected windows, residual BufferQueue timeouts. Initial JVM probe ~2.65s and first native composition ~3s while timing archive loads concurrently. These prior playback metrics should be independently recomputed from ARCHIVE_VOD_SEGMENT, F1_COMPOSITION_PLAYBACK, PitwallFrames, SCENE_CODEC_RELEASE and overlay diagnostics; don't equate a decoder count with physical presentation. No system scheduling/frame timeline trace was collected for 895 heap-only test. Zero decoder drops does not mean zero UI jank. Investigate startup contention vs steady-state performance, not simply Rust vs Kotlin.

## 7. Immediate next consolidated engineering scope (all unconditional work before build)

Execute the following in one batch, mapped to existing master plans. The user reported the regression; previous recommendation was to correct it before independent Astra audit. No arbitrary speculative structural redesign. The implementation details below are engineering proposals requiring source audit and parity tests, not a claim they already exist.

### A. P0 — eliminate overlay startup blinking (G.22, G.23, P0.4, P1-E)
1. Trace saved preview, cached full index, independently completed lap/state/position topics, geometry and final archive publication. Establish per-session, per-seek/playhead generation ownership and exact data-quality/coverage semantics. Preserve authoritative data from a valid preview across new incomplete results when still valid for the same session and requested clock.
2. Use coherent immutable published snapshot or independently versioned map/tower components, merging only compatible same-session, same-clock data; do not union huge histories repeatedly or maintain duplicate long-lived engines. Reject regressions such as 22 valid rows -> 0 from partial missing lap topic when previous data still covers current clock. Do not retain data from another session or old seek; a real empty/invalid/discontinuous target must be displayed honestly. Avoid a blanket monotonic row-count rule: legitimate retirements/flags/track changes and playhead seeking need correct updates.
3. Separate readiness of usable rows, positioned cars, stable geometry, and complete archive. Record first AND stable readiness, loss/recovery reason, preview/core source, generation, accepted/rejected update, coverage, layout/geometry version and number of native sampler rebuilds. Keep logs bounded; no per-frame strings.
4. Avoid replacing usable map outline for marginal partial improvements. Use stable validated circuit/known complete outline when compatible; adopt improved geometry atomically with corresponding position/projection state. Preserve accurate track shape and offsets, start/finish, corner labels, map/edge controls; no hiding legitimate missing data or freeze of moving dots.
5. Keep Compose/render owner stable when data upgrades; no needless teardown, visible flash, layout request, placeholder swap, or resetting both overlay engines on one topic's revision. Retain dynamic interpolation and timing freshness, respect actual playback clock. Preserve local/baked/receiver publication behavior and Rust numerical ownership. Add regressions reproducing trace 9386484 sequence plus cold/warm index, cancellation, seek across coverage, session switch, legitimate empty states/retirement, late topic and overlapping epochs.
6. Acceptance: under same replay/playhead with valid cached preview, zero visible data disappearance caused by incomplete background publication. Once usable map/tower ready, stay usable unless actual playhead/session requires a new coverage window. No stale previous-race data. Measure startup first/stable ready and verify five-second checkpoint; complete archive latency separate. Cold first load is honestly reported, not required to fabricate missing data. User should see overlay settle once, not blink repeatedly.

### B. P0 — replay cache migration and loading (M10, M3/M6, G.22/23)
- Audit cache v2->v3 version change, preview/core distinction, atomic saves, incomplete initial run, exact content identity, file lifetime, concurrency and disk/heap limits. Consider safe backwards-compatible migration only if v2 meaning is provable; missing stopped flag must be unknown/reconstructed under verified semantics, not inferred as false without justification. Otherwise explicitly rebuild using validated old preview only where safe. Retain v3 stopped-state persistence and list interning.
- Load small usable window first at actual playhead; avoid duplicate complete parsing or unnecessary full snapshot allocations during progressive work. Use coalesced bounded publications and safe cancellation. Reuse existing authorized F1 data, not new APIs. Keep time-zone/UTC/offset semantics and seek coverage. Avoid moving JSON/auth/platform IO just for language share; keep applicable app-owned normalized processing in Rust.
- Improve traceability for warm hits, cold miss, upgrade migration, interrupted rebuild, corrupt/truncated cache and version rejection. Add fixture tests and enforce disk/heap bounds, integrity, and atomic replacement. Never weaken existing parsing validation or cache compatibility tests.

### C. P0 — real graphics and decoder memory ownership (M1/M13, P0.3, G.18/24)
- Reconcile PSS categories, active codec, BufferPoolAccessor, EGL contexts/windows/decoder textures, SurfaceTexture queues, UI RenderNodes and old/new scene transitions. Inspect SingleDecoderSceneView, protected/surface variants, lifecycle, renderer detach, generation guards, capture resources and actual sender/receiver ownership. Current diagnostics show one scene owner; does not explain all ~247MiB graphics PSS. External codec/GPU explicitly unmeasured. Do not count vendor pools twice or misattribute all graphics memory to overlays.
- Verify exact ELF heap trace where possible and compare against existing 892 profile, using allocation-site grouping and live vs cumulative metrics. Evaluate graphics/decoder/dmabuf counters supported by device and OS; document missing permissions/attribution. Use existing samples and existing system traces first. Do not promise full vendor memory attribution from heap-only data.
- Identify and fix demonstrably unnecessary retained surfaces, GL textures, offscreen layers, reader lifetimes or duplicate old/new resource ownership. Preserve secure output, texture compatibility and current experimental clear-video SurfaceView option. Structural renderer change, secure surface changes, native renderer replacement, and allocator substitution are evidence-gated. No new decoder, output size downgrade, quality change, or offscreen full-frame copy to meet RAM target.

### D. P0 — reduce Java timing archive retention (M2/M3/M6/M7/M10, G.22/23)
- Analyze lifecycle of full/partial SessionTiming, track histories, lap/state/sector lists, speed/cache/preview holders, temporary collections, Rust sampling buffers, closures, map index and rendering keys. Find actual retained references and overlapping old/new histories rather than optimizing every allocation blindly. Avoid duplicate full snapshots, boxed per-sample structures and repeatedly copying full histories; share immutable backing where safe, coalesce publication, release cancelled and superseded owners after last reader. Retain seek history, parity and immutable read leases.
- Verify total and unique backing bytes, Java/native retention, max simultaneously live generations and startup allocations; do not sum overlapping PSS categories. Ensure Rust ON uses bounded batched/direct processing and avoids duplicate numerical state. Do not port Android UI/JSON authorization or platform decoding for its own sake. Preserve M2 actual headroom admission and pressure trimming without starving foreground delivery.

### E. P0 — initial composition startup and JVM probe (G.3/4/9/10/12/14/18/19)
- Trace initial JVM composition probe (~2.65s) and first Rust work (~3s), source validation, compatibility checks, network fetch, JNI, CPU and timing-archive overlap. Determine whether probe duplicates already validated supported profile work. Do not delete mandatory independent validation or weaken clear/protected parity merely to remove a probe.
- Where provable equivalent and compatible, reuse validated stream/configuration, immutable source and native job result rather than materializing identical JVM fragment. Keep reason-coded fallback, independent CI/reference differential tests, generation fencing, and mandatory protected checks. Prioritize earliest needed foreground and exact seek over optional startup/overlay prefetch; no forced buffering or lowering requested quality. Measure startup, first frame and p95/p99 preparation separately from steady-state stitching.

### F. P1 — eliminate unnecessary overlay recreation and geometry invalidation (G.24, P1-E)
- Audit existing RenderNode/cache/Compose/Canvas/Skia and baked GLES pipeline. Reuse valid geometry/path/text/style caches and immutable row descriptors, field/row-level changes, dirty region/version tracking. Keep static/dynamic drawing isolated, frame-clock-aligned and bounded. Avoid per-frame whole snapshot, per-driver JNI objects, needless serialization or full-size offscreen buffers; never reduce visible animation cadence as shortcut.
- Use actual selected engine per subsystem and supported local/Cast mode. Preserve stopped/retired states, sector colors, speed/gear/position, driver full names, intervals, weather, captions/controls. Retain 895 steady improvements and verify allocation counts (not just elapsed draw times). Add tests for preview->full transition, independent row/geometry changes, map disabled/tower disabled/hidden dormancy, correct seek and cast generation.

### G. P1 — residual BufferQueue backpressure and 120Hz UI jank (P0.3/P1.2, G.7/13/18/24)
- Align actual codec releases, SurfaceTexture acquire/update, GL submit, display-frame deadlines and UI/overlay render. Use existing BufferQueue and frame analyzers. Timeouts may be startup/transitions or steady; count separately, don't infer exact stall duration from warning count. Diagnose main-thread work, GC, pacing, producer/consumer ownership and display cadence. 120Hz panel with 50fps video is not a guarantee of smoothness; preserve actual user settings and correct cadence policy.
- Fix observed ownership/backpressure and avoidable UI work across supported renderers. No busy-spin, artificial delays, extra decoder/surface, frame-quality reduction or forced 60Hz default without evidence. Maintain valid high-refresh operation and thermal policy on phone and TV. Run targeted regression tests for lifecycle transitions, pause/seek/speed, cast and renderer generation; physical frame timeline/thermal measurements later if necessary.

### H. Explicit remaining G-C coverage audit in same batch
Reconcile all G.21–G.24 route inventory from both masters. Review actual source entry points for audio, local map/tower (single/multiple decoder, direct/composed/live/replay), native receiver, Google Cast direct/custom/receiver layout and baked, Pitwall native layout and baked, protected equivalents where supported, disabled overlays, alternate commentary/captions. Inspect actual selected/actual engine telemetry and fallback reasons. Complete all feasible unconditional gaps identified by audit; retain documented platform boundaries and evidence-conditional M12/M13/G.11/R4. Record each concrete requirement, source/test/route and verified result. Do not silently count a JVM-only eligible route as native or claim 100% based on a subset. Preserve P0-A–P1-F quality/status/lifecycle/full-name fixes. Reopen any specific failed acceptance gate, not signed-off core design.

## 8. User-approved success and closure criteria

Use master plans for complete numeric gates and mode matrices. Key requirements:
- Rust steady process PSS <=80% of matched Kotlin, lower sampled peak, no sustained growth. Earlier illustrative 470.63 MiB is not absolute cap independent of reference. Actual TV OS/headroom and vendor allocation visibility matter. Retain valid buffers and video quality; memory reduction from disabling features is invalid.
- At least 30% stitching CPU reduction vs matched reference, retain exact media parity, valid supported profiles, no regressed throughput/copy/GC/deadline tails. Track p50/p95/p99 and startup separately.
- At least 90% reduction in measured avoidable map/tower allocation churn vs matched pre-optimization baseline; near-zero app-owned steady drawing allocations where achievable. Define attributable sites and measurement before comparison; timing improvements alone are not allocation improvement. No claim that 90% of graphics PSS is removable.
- Zero regression in audio gaps/drift, commentary/captions, actual source quality, secure playback/DRM, UI interaction, map/tower correctness/continuity, Cast, seek, speed and all existing settings. No unnecessary extra full-screen buffer, readback, decoder or surface. Native ON actual processing verified across applicable paths.
- Startup regression acceptance in A/B/C above; ensure warm/upgrade/cold/interrupted scenarios and stable ready vs first ready. Re-run exact user reproduction on phone after consolidated build when requested. Measure stable readiness, no transient loss caused by background publication, correct actual playhead and geometry.
- Full race duration (at least two hours) on phone and low-RAM TCL TV with map+tower, thermal/power, seek/quality changes, on/off combinations, Cast, protected and clear. Preserve release matrix, G-D physical gate, user sign-off and Rust default OFF until user approves.
- G-C coding sign-off is NOT a green-build claim or matched performance acceptance. Separate 100% unconditional implementation, CI, conditional research/blocked scope, physical evidence and formal user sign-off.

## 9. Research already approved — reuse, do not restart

Full research, case studies and precise links are in Build 880 handover section 5 and master plan. Lessons: Cloudflare compact cache allocations and shared workspace (reported p99 RSS 9.3→5.3 GB, per-entry footprint 953→420 bytes in their context); Datadog Monocle shared radix aggregation, bounded duplicate temporary state; Zerodha Blobasaur Rust large buffer lifecycle and reduced GC pressure (no comparable whole-app RAM number); Discord Rust read-state service avoids GC latency spikes but caching trade-offs remain; Agentgateway queue blocks amplify inline payload memory, send handles not buffers; ChainSafe Forest distinguish live heap, RSS and allocator retention. These results are design references, not transferable Pitwall performance guarantees.

- GStreamer buffer pools https://gstreamer.freedesktop.org/documentation/additional/design/bufferpool.html and https://github.com/GStreamer/gst-plugins-rs — negotiate reusable, bounded, downstream-release ownership; do not replace Media3.
- Tokio Bytes https://github.com/tokio-rs/bytes — immutable sharing and reclaim, watch small views retaining oversized backing.
- Perfetto https://github.com/google/perfetto and https://perfetto.dev/docs/data-sources/native-heap-profiler — exact symbols, complete snapshots, isolate profile overhead.
- AndroidX Media3 https://github.com/androidx/media — use existing 1.11.1 allocator and platform integration; no proprietary SDK or speculative fork.
- Rust allocation regression https://github.com/nnethercote/dhat-rs and https://nnethercote.github.io/perf-book/heap-allocations.html — fixture-level allocation counts and peak; no global allocator change by default.
- ChainSafe https://blog.chainsafe.io/memory-analysis-in-rust-2/ and https://github.com/ChainSafe/forest/issues/7393 — controlled allocator evidence only; Android Scudo https://source.android.com/docs/security/test/scudo.
- Parallax https://github.com/p13marc/parallax, Lumina https://github.com/lumina-video/lumina-video, Firecracker https://github.com/firecracker-microvm/firecracker — study ownership/resources, not proof of protected TV support.
- Existing project references (Android performance/TV samples, Androidify, Now in Android, F1RacingCompanion, Multiviewer, F1OpenViewer, f1-dash, bacinger/f1-circuits, Fast-F1, FCast) are historical learning, not permission to add proprietary/paid services or change architecture. Consult authoritative source and current implementation when needed.

## 10. Existing tools, capture commands and verification

Do not create replacement scripts without first inspecting the existing tools. Full inherited inventory, fixture/native entry points and usage map are in Build 892 handover sections 8–9 (including 10.1–10.6 subsection labels) and Build 888 handover. Read them in full. Actual 895 tree includes:

Analysis: scripts/analyze_pitwall_runtime.py, analyze_pitwall_bufferqueues.py, analyze_pitwall_frames.py, analyze_pitwall_overlay_ab.py, check_pitwall_memory_trace.py. Read --help/source before invoking; use capture/version/PID/uptime filters, preserve raw input and exact units. Acceptance: scripts/pitwall_acceptance_matrix.py, validate_pitwall_acceptance.py, pitwall_change_contract.py. Build/security: scripts/architecture_g0_build_id.py, architecture_g0_stage.py, verify_architecture_g0_apk.sh, run_rendernode_instrumentation.sh.
Tests: scripts/test_analyze_pitwall_bufferqueues.py, test_analyze_pitwall_frames.py, test_analyze_pitwall_overlay_ab.py, test_analyze_pitwall_runtime.py, test_architecture_g0_fixtures.py, test_architecture_g0_kernel_vectors.py, test_architecture_g0_packaging_contract.py, test_architecture_g0_stage.py, test_check_pitwall_memory_trace.py, test_official_release_workflow.py, test_overlay_runtime_wiring.py, test_pitwall_acceptance_matrix.py, test_pitwall_change_contract.py, test_pitwall_production_wiring.py, test_release_acceptance_gate.py, test_release_gate.py.
Tools: tools/generate_hevc_fixtures.py, generate_hevc_tile_fixtures.py, generate_hevc_tile_2x2_fixtures.py, validate_hevc_clear_cmaf.py, validate_hevc_cmaf_grid.py, validate_hevc_full_scene.py, validate_hevc_output_parameters.py, validate_hevc_rust_gb_grid.py, validate_hevc_tizen_grid_composer.py, package_cast_receiver.py, test_cast_receiver.mjs, test_cast_layout_receiver.mjs.
Workflows: .github/workflows/android-debug-apk.yml (mandatory signed APK, Rust/JVM/Android checks), cast-receiver-pages.yml (hosted receiver deployment separate), official-release.yml, pitwall-approved-release.yml. Do not weaken exact-source native symbol, four ABI, instrumentation, signed upgrade or artifact gate. Preserve current workflow triggers; no docs-only APK build.
Perfetto assets: docs/perfetto/pitwall-memory.cfg, pitwall-50fps.cfg, frame_timeline_export.sql, capture-memory.sh, ASHELL.md. Heap-only v4 config is on user device, not listed as repository file; known config and install instructions in 892 handover. Do not confuse with generic system trace.

Available precommit verification:
~~~bash
python3 -m unittest discover -s scripts -p 'test_*.py'
bash -n scripts/run_rendernode_instrumentation.sh
node tools/test_cast_receiver.mjs
node tools/test_cast_layout_receiver.mjs
node --check web/cast/receiver.mjs
~~~
Run relevant Rust cargo host tests, fmt/clippy, sanitizer, fixtures and Gradle unit/instrumentation checks as available; use commands/versions already in workflow rather than guessing target paths. Exact Android toolchain CI is mandatory where staging environment lacks compiler/SDK. Build 895 had 115 Python tests; add meaningful tests rather than changing counts to mask removal. Nine exact class footers must track actual JUnit tests. Keep native ABI build IDs and signed APK upgrade retention. Do not ask user to run tests that automated CI can perform.

Phone-only capture when next green build needs physical verification, not a prerequisite for coding:
~~~bash
perfetto --background-wait --txt -c /data/misc/perfetto-configs/pitwall-heap-only-v4.cfg -o /data/misc/perfetto-traces/pitwall-rust-<build>.perfetto-trace
# Immediately play agreed replay at matched position/quality/layout for 120s.
# After capture finishes:
cp /data/misc/perfetto-traces/pitwall-rust-<build>.perfetto-trace /sdcard/Download/
~~~
Substitute actual build number; ensure no literal shell angle brackets in command. Keep correct writable paths, existing 120s/64KiB heap-only config and check complete trace before analysis. Export Pitwall diagnostic log. For matched acceptance, clean independent process launches alternating Rust OFF/ON on same build with identical fixed offered qualities, replay position, layout, renderer, overlays, audio, speed, refresh, network, thermal and Software DRM. Separate unprofiled PSS/performance from heap-only profiling and system/frame-timeline captures. Collect TV only when needed. Do not repeat Kotlin test now merely to start coding. Do not promise future work asynchronously.

Additional technical docs to preserve: docs/HEVC_CLEAR_CMAF_PACKAGING.md, HEVC_CMAF_RUNTIME_INSPECTOR.md, HEVC_STITCHING_REFERENCE_REVIEW.md, SINGLE_DECODER_LAYOUTS_AND_CAST.md, CLEAR_WIDEVINE_PARITY_AND_NAMED_LAYOUTS.md, LOCAL_TV_RECEIVER.md, PITWALL_50FPS_DEVICE_PROFILE.md, BUILD_627_VEGAS_AND_OVERLAY_DIAGNOSIS.md, BUILD_629_VEGAS_PROTECTED_CABAC_DIAGNOSIS.md, BUILD_637_PROTECTED_SCENE_DIAGNOSIS.md, BUILD_643_LAG_AND_SURFACE_RENDERING_DIAGNOSIS.md. Existing fixtures/native benchmark entry points are enumerated in the 892 reference inventory.

## 11. Execution and reporting checklist

1. Verify branch HEAD, exact 895 green ancestry, latest source and BOTH master plans. Read linked handovers, preserve prior decisions and tests. Identify source/test locations for every A–H item.
2. Implement confirmed overlay defect first; complete all unconditional scope A–H, with source/fixture evidence. Reuse prior research and concrete native/graphics findings. Keep Rust hot paths Rust, platform lifecycle Kotlin. Do not introduce a second plan, arbitrary optimization, quality reduction or new architecture.
3. Use intermediate CI-suppressed commits if needed. Maintain a coverage matrix recording actual owner, source, tests, conditional evidence or blocker. Update both master plans with 895 findings, regression tests, explicit unresolved attribution/conditional gates, and exact candidate status.
4. Run available tests and audit all changes. Only when 100% unconditional coding scope is complete trigger one consolidated normal build on existing branch. If CI fails inspect exact job and fix, retaining gates. Report run link and exact SHA. No production change/main/parent branch for documentation-only work.
5. Green is CI, not physical sign-off. Then independent Astra Pro audit as recommended, evidence-based consolidated implementation if approved, and matched device tests; keep G-C open until actual requirements met, G-D full physical acceptance after. Do not imply that latest 649.6 MiB equals success or that the startup blink was fixed by a green build.

**Start with the existing source immediately. Build 895 is the verified baseline, not a new project. The confirmed defect is newer incomplete timing publications overwriting valid overlay state. Preserve the faster Rust steady-state engine while fixing startup and total memory ownership.**
