# Pitwall TV / ApexView — Build 896 remaining Architecture G execution handover

**26 November 2026 | Source checkpoint only — NOT a new APK or a sign-off**

## 1. Paste this prompt into a new chat

> Read the entire `docs/handovers/PITWALL_TV_BUILD_896_FINAL_SCOPE_CONTINUATION_2026-11-26.md` file from repository `cooldarshak/apexview`, branch `feature/architecture-g-rust-stitching`.
>
> Read both authoritative master plans in full, their latest appendices and every referenced handover. The performance plan is large: read it in line ranges if necessary, without silently skipping sections. Verify the actual branch head and compare it with the source checkpoint in this document.
>
> Immediately resume implementation, not research or planning alone. Complete 100% of the remaining unconditional Architecture G coding and regression-test scope across Rust memory and delivery ownership, graphics, TV playback, multi-feed startup, timing tower, track map, Pitwall Cast, Google Cast, and all applicable original and inherited requirements. Reconcile every requirement against actual source and test coverage; fix missing implementation and integration. Do not simply add more small optimizations without closing the agreed requirements.
>
> Work and commit directly in the existing Git branch, without cloning into a development workspace. Intermediate commits are allowed with [skip ci]. Do not change main or the parent feature/pitwall-performance-audit-plan. Do not trigger a signed build or receiver deployment while unconditional coding scope is incomplete. Once the entire unconditional coding and regression source scope is reconciled, run available checks and dispatch ONE consolidated signed CI build. Correct any actual compilation/test failures without weakening gates. The absence of local Android SDK or Rust tools is not a reason to stop implementation; GitHub Actions provides the full toolchain.
>
> Use Rust for applicable numerical and media processing, preserve Android platform integration and legitimate F1 login, entitlement, Widevine DRM, selected video quality and all existing features on phone and TV. Do not replace native functionality with Kotlin fallbacks to declare success. Keep both master plans updated with exact implementation, tests, open evidence and commit IDs.
>
> Do not ask me to continue repeatedly, request new logs before doing the remaining source work, or stop voluntarily at an intermediate checkpoint. Do not restart the architecture or invent progress percentages. If actual tool/execution limits prevent closure, record precise remaining source work and disclose it honestly; do not imply unattended background work. Keep user-facing updates concise and accessible to a business analyst. The goal is complete coding, a green consolidated build, then the existing physical acceptance tests, not another partial status table.

## 2. Authoritative documents and precedence

Read these files on the working branch. This handover is an execution index, not a new master plan. All still-applicable requirements and original acceptance criteria remain binding.

1. [Architecture G Rust master plan](PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md), including original G.1–24, G.0/G-A/G-B/G-C/G-D gates, research-derived ownership revisions, G-TV1–8 and latest implementation appendices.
2. [Consolidated performance audit and acceptance plan](../PITWALL_PERFORMANCE_AUDIT_PLAN.md), all P0–P2 workstreams, change contract, original acceptance matrix, and all dated continuation ledgers.
3. [Build 896 post-research consolidated continuation](PITWALL_TV_BUILD_896_POST_RESEARCH_CONSOLIDATED_CONTINUATION_2026-11-26.md), especially sections 4–8: historical measurements, four exact TV admission vectors, source checklist, build scripts and physical targets. It remains mandatory, but its old source head has been superseded.
4. [Build 896 memory engineering handover](PITWALL_TV_BUILD_896_MEMORY_ENGINEERING_HANDOVER_2026-11-26.md).
5. [Build 895 engineering handover](PITWALL_TV_BUILD_895_ENGINEERING_HANDOVER_2026-11-26.md), scope A–H.
6. [Build 892 physical test handover](PITWALL_TV_BUILD_892_PHYSICAL_TEST_ENGINEERING_HANDOVER_2026-11-26.md), scope P0-A–P1-F and heap capture setup.
7. [Build 880 G-C memory optimization handover](PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md); [Build 867 continuation](PITWALL_TV_BUILD_867_ARCHITECTURE_G_CONTINUATION_2026-11-25.md); [Build 857 architecture handover](PITWALL_TV_BUILD_857_ARCHITECTURE_G_HANDOVER_2026-11-25.md).
8. [Local TV receiver protocol](../LOCAL_TV_RECEIVER.md), actual workflow definitions, Cast receiver source/tests, diagnostics scripts and acceptance matrix referenced by the plans.

Read every further reference that contains a binding requirement. Later explicit user instructions supersede older wording prohibiting intermediate commits. Preserve prior sign-offs (historical G.0 and G-B, G-A foundation verified) without representing them as current G-C/G-D acceptance.

## 3. Verified source checkpoint

- Repository: cooldarshak/apexview
- Working branch: feature/architecture-g-rust-stitching
- Last confirmed green APK: Build 896, version 1.0.1792, commit `75577ebf25ab006871d6d034ff3eb57a0795e2d8`, Actions run 45818263209, as recorded in the previous master plans.
- **Initial verified live source head during handover preparation: `cfeb18be189ba7b9d26bf35e907b3f55f19e226b`.** Subsequent concurrent source checkpoint: `22d4f7ea9bc635c3bb48c6b2a18e780a950e5351`; see section 9.
- Previous reported checkpoint: `7fd3af4f3470b829d6c76b8fe22d4834e3a5134d`. Git comparison confirmed six additional commits, zero behind, six changed files.
- New handover is a documentation-only commit subsequent to this source head. Verify head on resumption; do not reset it to a historical SHA.
- Build gate: CLOSED. No 100% unconditional coding assertion. No new APK or physical testing requested.
- Android debug workflow at the source head ignores docs/** and **/*.md on push, supports workflow_dispatch. This file is committed with [skip ci]; do not dispatch.

### 3.1 Latest six commits not yet reflected in the previous master-plan checkpoint

| SHA | Source change |
|---|---|
| 9dab3ada85fafefbc0ccf5c751ec1cd99386846a | Always release native archive producer lock and retry disabled retirement |
| eb61543b2d13e6bc7d29ec903961f08a7134a814 | Fence worker slot even when native fragment cleanup fails |
| f614fff44ce5c80f1300e341217dde3b93497317 | Validate rolling composition generation after DASH publication |
| e466f3dd3d60df3d65df922e599622f823ad3a46 | Fence rolling archive host at both suspended ownership boundaries |
| 005cc9e15ea367cc5766833a78b47e2d8b6a4b69 | Preserve primary archive handoff failure through rollback and disposal |
| cfeb18be189ba7b9d26bf35e907b3f55f19e226b | Cover archive rollback when diagnostics and disposal fail |

Changed files: app/src/main/java/app/apexview/{AppState,FeedPlayer}.kt; app/src/main/java/app/apexview/tiled/{ArchiveCompositionStartup,NativeClearArchiveBridge,NativeClearArchiveJob}.kt; app/src/test/java/app/apexview/tiled/ArchiveCompositionStartupTest.kt. Review the real diff. The final two commits explicitly preserve the primary CancellationException and attach diagnostic, restore and dispose errors as suppressed exceptions; the test asserts rollback/disposal order. Test source is present, full execution not established. The performance plan now records these commits (see section 9). Check the architecture master ledger and reconcile only missing entries.

### 3.2 Earlier accumulated candidates

Both master plans contain detailed commit-by-commit ledgers through the previous documentation checkpoint. Read those rather than recreating them. Summary:

- Native: revised checked admission arithmetic, actual capacity and workspace forecasts, bounded idle atlas backing, final-reader reuse, pressure and generation fencing, transient memory/worker rejection recovery, full-capacity buffer clearing, unified native adoption/copy admission, protected/clear lease paths, batched timing-floor JNI and direct encoder AVC framing. Preserve legitimate low-RAM limits, actual selected quality and ABI safety.
- Media3: active/parked owners and allocator accounting on phone/receiver, bounded load ledger, stale callback protection; three parked players had zero allocator bytes in historical measurements. Ledger tracks observed downloaded bytes, not retained sample-queue allocations. Do not label unavailable internal/vendor memory as zero.
- Startup: exact authorized HLS offer identity, bounded interruptible network probes, selected audio/caption validation overlapped with video proof, optional alternates in two session-owned lanes, exact initial playback handoff, catch-up request, generation and rollback fences. Selected programme must still be validated and installed; delayed alternates remain available once validated.
- Timing: independent map/tower coverage and progressive moving playhead publication; bounded previews, corrected clock handling, original rank/pit/lap predecessors, V5 startup checkpoints supporting map-only/tower-only and successor interpolation, compatible full V3 and preview V3/V4 readers; completed primary index reuse when supplemental absence known. Preserve actual ordered supplemental merge where necessary.
- Graphics: separate resource release steps; partial EGL allocation cleanup; owner-looper producer retirement and deferred overlay queue release; balanced app-owned EGL display leases; encoder/capture/texture ownership accounting; avoid unused bitmap renderer allocations. Logical ownership counts are not actual GPU allocation bytes.
- Pitwall pairing: authenticated physical TV certificate selection (six groups of four hexadecimal characters, 96-bit fingerprint), actual peer verification before control requests, single positive approval on TV, legacy mutual confirmation retained, remembered peers reconnect without prompts, manual reconnect restrictions persist, forget clears pins/restrictions. Do not trust name, IP or network alone.
- Pitwall/Google Cast: immutable heap/native segment and playlist read leases until final socket reader, atomic metadata publication, bounded timing batches, reconnect/close generation fences, correlation UUIDs, first media delivery versus first rendered frame, first-frame watchdog, stale/foreign load and clock rejection, return to local, secure relay lifecycle; web receiver frame-cadence upload and cached geometry redraw.
- Transport: Rust ADTS/PES/PCR/PTS/continuity validation; real synthetic 50-frame AVC/AAC independent Python reconstruct/FFmpeg fixture, native/reference parity and CI wiring; direct owned AVC sample framing with prefix and bounds checks. Preserve one receiver video decoder and selected commentary/CC.

Not all of these candidates have compiled. Do not confuse a source commit, isolated helper check, or documentation ledger with completion of the whole workstream.

### 3.3 Available verification already reported

Prior exact-blob isolated JVM checks: 1,000 Media3 retirement cycles; timing-tower coverage across map gaps/seeks and authoritative replacement; 100 bounded sidecar discovery batches including failure/cancellation/retired publication; authenticated fingerprint helper; 1,000 producer retire/detach races and EGL display lease cycles using minimal stubs; 1,000 AVC framing comparisons against JVM reference (native stub, not native proof); Cast recovery predicate tests; standalone scene cleanup check and JavaScript layout receiver regression. FFmpeg source-generation fixture verified 50 access-unit delimiters, IDRs at 0/25 and 95 ADTS units. Full native transport output fixture, Android/NDK/Rust tests, actual TLS/CAF/EGL integration and physical acceptance are not established. Local environment lacked Rust compiler/Android SDK in prior passes. Recheck tooling, use CI when eligible.

## 4. Remaining unconditional execution — close requirements rather than expand scope

First build a finite ledger inside the existing master plans: requirement ID, exact source paths/symbols, status (implemented/partial/missing/not applicable with justification), integration and regression test, evidence, actual residual action. Reconcile G.1–24, R1–8, M1–13, P0–P2, A–H, P0-A–P1-F, G-TV1–8 and G-C1–6 plus feasible G-C7 instrumentation. Distinguish unconditional code/test source from automated execution and physical proof. Do not repeatedly reopen completed source work absent a demonstrated issue. Audit accumulated uncompiled changes for correctness and build compatibility, then close specific gaps.

| Workstream | Required closure focus |
|---|---|
| G-TV1 / G-C1–2 / memory | Verify NativeStitchingEngine, NativeStitchingDeviceBudget, NativeClearArchiveBridge/Job, NativeFragmentLease and Rust engine/jni_bridge/fragment_storage/atlas_job. Four exact historical TV denial vectors; 32-bit arithmetic and ARMv7 ABI; actual admission/worker retry; pressure, stale release, final-reader lease, no overflow or premature reuse. Audit protected, clear, rolling, finite, native and necessary platform boundary copy owners. Retain hard memory bounds and no quality downgrade. |
| G-TV2 / startup / G.21 | Close actual archive preparation, metadata discovery, selected programme/caption merge, quality supersession, handoff and rollback integration. Validate cancellation at all suspended boundaries, worker lock/slot retirement, generation after DASH publish, same-session reuse, exact rung and actual channel labels, 2/3/4+ feeds, no fake readiness or unnecessary refetch. Cover start, seek, ordinary drift, quality/audio changes, stop/restart, failure while old host is valid. |
| G-TV3 / G-C5 / timing | Reconcile LiveTiming, ReplayStartupPublication, ReplayCoreCache, DriverTiming and AppState for warm/cold/cache upgrade/restart, moving clock and backward/forward seek, independent topic availability, original ranks, stopped/out recovery, map interpolation and smooth dots, mini-sector progression, no flicker or stale rank. Ensure bounded snapshots/queue, retired roots and no duplicate full primary histories where avoidable. Rust owns applicable numerical processing; platform IO stays platform. |
| G-C4 / Media3 | Inspect allocator ownership and source/renderer/codec lifecycle across active and parked instances, all receiver routes, callback fencing, audio and caption followers. Measure real owner types without conflating downloaded bytes with queue residency. Remove avoidable retained resources without lowering buffer safety or quality. |
| G-C6–7 / G-TV8 / graphics | Verify actual SurfaceTexture/Surface/EGL/MediaCodec ownership in single/multiple decoder, direct/TextureView/protected and baked capture paths. Retire only after producer detaches, preserve shared EGL display leases and owner-loop release, failure cleanup and cancellation. Complete feasible renderer parity instrumentation and accounting. Audit BufferQueue pacing, updateTexImage, 50fps and adaptive 120Hz, frame attribution; no speculative renderer default change. |
| G-TV4 / pairing | Review fingerprint selection and actual TLS identity pinning, mutual legacy confirmation, remembered pair/revocation/restart, spoofed name/IP, cert renewal, reject/replay, multiple senders, one-sided forget, disconnect restrictions, compatibility. Current first-use UI requires entering a long physical TV fingerprint as selection; one TV approval is not zero-entry onboarding. Preserve security and disclose this tradeoff. |
| G-TV5 / Pitwall Cast | Reconcile direct, atlas, baked and receiver-rendered modes, native selection, first-frame/ack/timeout, reconnect exact remembered peer only, manual disconnect and forget, no stale timing/control, bounded batch/full refresh, relay lease retirement and socket shutdown, selected audio/CC/quality and local restoration. Test generation replacement and active-reader retirement. |
| G-TV6 / transport | Review ts_mux.rs, JNI, BakedCastTransportStream/LiveSession/SegmentQueue/ArchiveAudio, CmafCastAudio, NativeComposedCastSession and transport fixtures. Exact PAT/PMT CRC, ADTS/PES length, PTS/PCR marker and wrap, adaptation and continuity across segments, keyframes; independent reconstruction and decode. Do not assume historical TV PES warnings proven fixed or lower 12s baked readiness safety to hit startup targets. |
| G-TV7 / Google Cast | Review standard/custom/baked compatibility, CAF load adoption and correlated render events, stale ACK, same URL reused, foreign status, failed load/disconnect, first delivery/first visible distinction, relay and receiver lifecycle, playback handback, authorized URLs, audio/CC and overlays. Test receiver model and sender integration; standard CAF status is a protocol proxy, not physical first frame. Do not deploy receiver prematurely. |

All applicable route matrix combinations: phone and TV; clear/protected; live/archive; single/multiple feeds and decoders; native selected versus existing Kotlin reference; Software DRM on/off only where supported; direct, composed, baked and protocol-rendered Cast, with/without map/tower/CC; selected audio, seek, play speed, feed/quality/layout change, restart and retirement. No blanket claim that every theoretical combination is supported by F1 or device codecs; document genuine unsupported capability explicitly. No intentional downgrade or DRM bypass.

### Conditional work, not an unconditional coding blocker

G-C8/M12/M13 allocator replacement/reclaim or renderer promotion only after actual allocator identity, reproducible live-vs-resident gap and matched physical feature parity. Retain defaults until evidence and required approval. Do not count conditional promotion as mandatory incomplete code or as completed physical acceptance.

### Provisional decisions to validate, not hidden waivers

10-second native admission recheck; revised conservative TV headroom fraction and modest growth guard; bounded two-lane alternate discovery; brief initial handoff clock freeze; preview cache V5 with older compatibility; 96-bit fingerprint entry; 2-second Google Cast ownership grace and 60-second local first-frame watchdog (not performance goals); 48 MiB AVC sample upper bound consistent with baked queue budget; async remembered-peer preference persistence. Keep or adjust only with concrete correctness/acceptance evidence, not arbitrary tuning.

## 5. Baseline evidence and targets

Read prior 896 continuation sections 4 and 8 for exact evidence and capture identifiers. Do not rerun research or claim current results from older measurements.

- Phone S22 Ultra Build 896 adaptive 120 Hz, four feeds 1080+3x540, one HEVC decoder, map/tower on: matched-ish 895/896 Rust PSS 652/637 MiB, sampled peaks 678.5/654.1; graphics PSS 247.1/252.1, native arena 24.9/24.3, composition ~42 ms both. Different onboard; not controlled Rust/Kotlin comparison. Longer 896 median 645.5 MiB; stop recovers ~191 MiB; no established leak. Prior 54 atlas allocations/adoptions with zero reuse; 41 exceeded previous 8 MiB generic pool cap.
- TCL 55C655 Android TV12/API31, 32-bit ARMv7, Software DRM ON normally required; historical 896 local overlay sample was OFF. All 12 recorded composed segments JVM/HEAP despite Rust requested, four admission denials, old arithmetic 0/4 revised 4/4 (not device proof). Segment duration 5.76s, median total 7.4535s, compose 6.155s, 10/12 negative slack, 2-feed first probe 23.2s; no recorded three-feed active in that sample. Timing cold map/tower appeared/disappeared; warm older sessions could be quick. Cast logs show TLS connected then closed, PES warnings, no actual first frame established. No confirmed fatal OOM. Do not equate vendor discard messages with displayed frame drops.
- Physical gates remain: matched Rust median PSS <=80% of Kotlin, lower peak, no sustained growth, stop recovery, >=30% stitching CPU reduction, >=90% avoidable overlay allocation churn reduction, 50/75fps supported policies, no feature/quality/drop/rebuffer regression. TV first visible target <=15s warm/30s cold; producer p95 <5.76s at 1x/<3.84s at supported 1.5x; covered warm map/tower <=5s, cold aim <=10s; prepared Cast first media aim <=10s, trusted reconnect no prompts, 30min then full-race Cast. These are targets, not results.

## 6. Build and verification gate

Read .github/workflows/android-debug-apk.yml, signed upgrade and G.0/G-A/G-B/G-C instrumentation gates, scripts/pitwall_change_contract.py, receiver workflows. Review actual test annotation counts against instrumentation runner expectations; do not relax tests.

Run available checks:
```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/pitwall_change_contract.py
bash -n scripts/run_rendernode_instrumentation.sh
node tools/test_cast_receiver.mjs
node tools/test_cast_layout_receiver.mjs
node --check web/cast/receiver.mjs
cargo test --locked --manifest-path native/architecture-g/Cargo.toml
```
Use the workflow's actual Rust transport fixture and independent decode/parity commands, Android unit/instrumentation and ABI packaging checks. When local toolchain unavailable, note exactly what was not run; do not require successful local Android compile as a precondition for the consolidated CI that performs it. Finish all unconditional implementation and regression test source first, then dispatch one signed CI build on the existing branch, fix actual failures and rerun as necessary. Do not trigger unrelated release or Cast Pages deployment. Verify the exact run/head/APK and tests, not only a green icon.

Direct Git commits with [skip ci] for intermediate work; use expected head/non-force fast-forward where supported. Read exact current source/blob before edits, verify resulting branch and changes. Do not force push. No raw logs, traces, APKs, tokens, entitlement or media in Git.

## 7. Physical acceptance after green consolidated build

No device test requested until eligible new green APK. Use prior handover exact scripts/configs:
- scripts/analyze_pitwall_runtime.py (896 version 1.0.1792; substitute actual new version), analyze_pitwall_bufferqueues.py, analyze_pitwall_frames.py, analyze_pitwall_overlay_ab.py, check_pitwall_memory_trace.py --require-heap, docs/perfetto/frame_timeline_export.sql.
- scripts/pitwall_acceptance_matrix.py, validate_pitwall_acceptance.py, verify_architecture_g0_apk.sh, run_rendernode_instrumentation.sh.
- docs/perfetto/ASHELL.md, capture-memory.sh, pitwall-memory.cfg, pitwall-50fps.cfg; Build 892 exact heap-only v4 setup. Existing 120s / 64KiB sampled heap config and 10s dumps. Phone aShell/Bugjaeger commands:
```bash
dumpsys meminfo -a app.apexview_custom
perfetto --background-wait --txt -c /data/misc/perfetto-configs/pitwall-heap-only-v4.cfg -o /data/misc/perfetto-traces/pitwall-build-NEW-rust-on.perfetto-trace
# Copy after capture ends; substitute actual build, separate ON/OFF files
cp /data/misc/perfetto-traces/pitwall-build-NEW-rust-on.perfetto-trace /sdcard/Download/
```
Matched same signed APK ON/OFF, identical feeds/rungs/layout/overlay/clock/speed/network/cache/thermal, alternating order, >=3 paired >=60s windows when practical; separate unprofiled acceptance from heap and system/graphics traces. Idle/start/steady/seek/stop and +30/+60/+120s, repeated cycles and >=2h race. Record actual route and decoder count. TCL Software DRM ON first and separate supported OFF case. Full Cast tests and first rendered frame/AV sync/overlay updates/handback/reconnect/screen off. Unknown vendor owners remain unknown; don't sum overlapping PSS/RSS/arena counters. No credit for failed or missing feeds.

## 8. Expected final output from the next execution

Do implementation rather than generating another handover as a substitute. Report exact branch/head, closed requirement IDs with actual code/test evidence, genuine remaining blockers if any, checks executed versus pending, and consolidated run/APK link only when legitimately triggered. No invented percentages, memory savings, startup results or physical sign-off. Keep both authoritative plans synchronized; reconcile the latest source commits against their existing appendices, including section 9 below. User should receive concise plain-language results and precise test instructions only once a verified green consolidated build exists.

Current status: source candidates committed, full unconditional reconciliation and compilation still outstanding, last confirmed green Build 896. No unattended work implied.

## 9. Concurrent branch advancement observed during handover creation

The branch advanced while this document was being committed. Do not overwrite or undo that work. The verified additional source commits are:

- `d63cb2f96c04fab439952bdab21c8d16602a2a38`: Rust fragment backing wipes initialized bytes and spare capacity once; adds 1,000 actual-size synthetic cycles with 8,011,000 / 8,577,000 / 9,846,000-byte payloads plus prefix, two retained readers, pressure watermark, reuse/identity/immutability, bounded capacity and final retirement. Rust tests unrun.
- `22d4f7ea9bc635c3bb48c6b2a18e780a950e5351`: preserve existing host first-frame and error state until rolling replacement validates.
- `8252fe3e632a5660b1cf05a4f55ae1356370028b`: initial creation of this handover.
- `5b8a7a8d1ac9d68d5a93a1e1b083977af4da3632`: performance plan appendix, "native retirement and rolling handoff follow-up (build gate CLOSED)". This records the eight source commits since 7fd3af4 and reports an isolated exact-blob Kotlin coroutine/JVM test of 1,000 rollback failure sequences and a successful handoff, not a full Android/JUnit run. It also reports the shared ChatGPT link was inaccessible, so source and master plans are authoritative.

The subsequent source checkpoint is 22d4f7e; the observed branch head before this addendum was 5b8a7a8. Re-read the actual current head and both master plans before any implementation; do not replay already-applied changes. All of the above remains uncompiled as a consolidated APK. The build gate remains CLOSED.
