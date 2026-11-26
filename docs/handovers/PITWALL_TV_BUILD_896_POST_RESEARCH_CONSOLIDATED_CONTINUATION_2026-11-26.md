# Pitwall TV / ApexView — Build 896 post-research consolidated continuation

**26 November 2026, 21:09 IST | Source handover, NOT a new build**

## 1. Paste this prompt into the new chat

> Read this entire file in cooldarshak/apexview, branch feature/architecture-g-rust-stitching: docs/handovers/PITWALL_TV_BUILD_896_POST_RESEARCH_CONSOLIDATED_CONTINUATION_2026-11-26.md.
>
> Read both authoritative master plans in full, including their latest appendices, and the referenced Build 896, 895, 892 and 880 handovers. Verify the live branch head. Build 896 is the last confirmed green APK at this checkpoint; the later commits are uncompiled implementation candidates, not new green builds.
>
> Immediately complete **100% of the remaining unconditional Architecture G coding scope**, including G-TV1–8, G-C1–6, feasible G-C7 experiment instrumentation and all applicable inherited G.1–24, R1–8, M1–13, P0–P2 and previous follow-ups. Continue the existing implementation, not a rewrite. Do not merely produce a plan or stop at each checkpoint. Commit directly to this branch (no clone/workspace-based development requested); intermediate commits allowed with [skip ci]. Keep the master plans synchronized. Do not touch main or the parent feature/pitwall-performance-audit-plan.
>
> Begin with the outstanding graphics/codec/encoder lifecycle and native memory/copy issues, then close the remaining timing, startup and both Cast requirements, and reconcile EVERY unconditional requirement against actual source, integration and regression tests. Review and correct the uncompiled checkpoint changes too. Rust must own eligible app numerical/media processing; do not use a Kotlin composition fallback as the solution to the TV regression. Preserve legitimate F1 entitlement, DRM, exact manual quality, all features, all supported phone/TV and single/multiple decoder routes, Cast and captions. Android platform interfaces remain Android-owned.
>
> Do not ask for another log or user approval before starting the outstanding source work. Do not trigger intermediate signed builds. After all unconditional coding and regression coverage is reconciled, run available checks and trigger ONE consolidated signed CI build. Correct actual CI failures without relaxing tests. Do not call physical gates passed before device evidence or treat conditional allocator/renderer promotion as mandatory unconditional work. Do not stop voluntarily after a small commit, provide repeated partial status reports as a substitute for work, or invent a completion percentage. If execution capacity genuinely prevents completion, preserve exact progress and remaining work in the branch and honestly disclose it; do not imply background execution.
>
> User is a business analyst, not a developer. Keep visible reporting concise and factual. The desired end result is a green consolidated build with all unconditional code completed, followed by the existing physical acceptance matrix, not another list of proposals.

## 2. Source of truth and precedence

Read in order, but reconcile all applicable requirements rather than replacing the older plans:

1. [Architecture G master plan](PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md): mandatory build gate, user clarification, authoritative P0 TV/Cast recovery, research-derived G-C ownership revision, original scope, all appended 26 November checkpoints.
2. [Consolidated performance acceptance plan](../PITWALL_PERFORMANCE_AUDIT_PLAN.md): full P0–P2 history, change contract, physical acceptance and the same recent checkpoints.
3. [Build 896 memory engineering handover](PITWALL_TV_BUILD_896_MEMORY_ENGINEERING_HANDOVER_2026-11-26.md): phone evidence, exact scripts, M1–13, G/R requirements, research references and capture identities.
4. [Build 895 engineering handover](PITWALL_TV_BUILD_895_ENGINEERING_HANDOVER_2026-11-26.md): A–H fixes and earlier 22-row startup regression.
5. [Build 892 physical test handover](PITWALL_TV_BUILD_892_PHYSICAL_TEST_ENGINEERING_HANDOVER_2026-11-26.md): P0-A through P1-F, exact fixture/script inventory and capture setup.
6. [Build 880 G-C handover](PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md), [Build 867 continuation](PITWALL_TV_BUILD_867_ARCHITECTURE_G_CONTINUATION_2026-11-25.md), [Build 857 handover](PITWALL_TV_BUILD_857_ARCHITECTURE_G_HANDOVER_2026-11-25.md).
7. [Local TV receiver protocol](../LOCAL_TV_RECEIVER.md), current Android workflow, Cast receiver source and tests.

The latest authoritative revisions and explicit user clarification supersede earlier wording prohibiting checkpoint commits. Do not discard an old applicable requirement because it is not repeated here. This document is a continuation index and execution checklist, not a competing architecture.

## 3. Verified starting point and commit ledger

Repository: cooldarshak/apexview. Working branch: feature/architecture-g-rust-stitching.
Last verified green Build 896: commit 75577ebf25ab006871d6d034ff3eb57a0795e2d8, app 1.0.1792, Actions run 45818263209.
Source head before this documentation-only handover: 4ebb41ff7d6e2dff31adb33d150acf61daa9921d, tree 73d2aada2478d1cbe7f5ccfa4c2dde159dbe7a18.
Verify actual head after reading; the handover itself will be a subsequent documentation-only commit.

| Commit | Content | Verification status |
|---|---|---|
| 25e313ca192de376c191394a9bfa0c7f49774144 / bad5465ff38a8179e47d92623ac5897fbfefba8a | Industry research-derived ownership plan and acceptance revision | Documentation |
| 29dfc6d46334116089a25d8072850a2708ca055b / a8e3abec9974f0a491826b656b66c8c3195cc979 | TV/Cast regression audit, G-TV1–8 integrated into both master plans | Documentation |
| 34bd7c2fc8f8b8886f8fdf15585c1abd2d4b509c | Correct bounded clear atlas admission and full checked native workspace forecast | Source candidate |
| 10905b4a13cbcc35b454891d40de14ac33a6157e | Dedicated bounded atlas idle backing, adoption tier, counters and seek/close trim | Source candidate |
| cffe1e3f801b4f7467d67153cde8b435f4f00ce8 | Remember authenticated peers, remove redundant Allow, control close attribution, skip inactive clock sync | Source candidate |
| 6b30d23e20d1866e6cc8b71854d0121d03635b0d | Timing preview cache ownership/retry and Rust TS/ADTS integrity | Source candidate |
| a8c119fc85b7bff8f1125865011692686e284f6c | Active-engine final-reader reuse, capacity checks and granular admission/ownership diagnostics | Source candidate |
| f4f0762e588ac3d3a1758c87b552b9ac8c41fcd2 | Bounded timing batches, pinned retry, custom Google rendering fence, load watchdog | Source candidate |
| 4ebb41ff7d6e2dff31adb33d150acf61daa9921d | Archive quality supersession/debounce, startup stage attribution, Media3 load ledger | Source candidate |

All source candidates above were committed directly on the branch, CI-suppressed; no new signed build was triggered or verified in this execution. G.0 and G-B historical user sign-offs remain, G-A foundation remains CI verified. Do not reinterpret those as current G-C/G-D sign-off. Prior chat gave approximate 60% complete/40% pending with per-workstream estimates; these were not audited counts and are superseded by the requirement-level review. No trustworthy current percentage exists.

## 4. Physical evidence to preserve

### Phone / memory

S22 Ultra, Android API36 adaptive 120 Hz, Build 896, Las Vegas clear replay, four feeds 1080+3x540, one Qualcomm HEVC hardware decoder, TextureView, atlas 1920x2176, scene 2316x1080, map/tower on, 1x. Matched-ish 895/896 Rust windows: PSS 652/637 MiB; sampled peaks 678.5/654.1; RSS 533.8/540.9; graphics PSS 247.1/252.1; Java heap PSS 189.1/182.7; native heap PSS 73.5/76.4; Media3 active bytes 76.8/77.5 MiB; native arena 24.9/24.3; input workspace 7.81/7.87; composition approximately 42ms both; slow frames 30.4/30.2%; zero decoder drops. One onboard differs, so not a controlled engine comparison. Longer 896 median 645.5 MiB, sampled peak 676, stop recovers ~191 MiB; three parked players zero allocator bytes. Heapprofd sampled peak 50.04 MiB with approximately 25 MiB unresolved and 7.87 MiB input workspace. 54 output allocations/adoptions, zero reuse; 41 exceed prior 8 MiB generic pool cap. Sizes 7.64–9.39 MiB, median 8.18. Neither a proven leak nor proof Rust is already lower PSS than Kotlin. Do not add overlapping counters.

### TCL local TV and Cast

Both exports were previously read in full: pitwall-20261124-132908-594_2.log.txt (31,578 lines, mixed old builds and 896 PID11329 segment1); pitwall-20261126-141351-371.log.txt (11,322 lines, 896 PID11329 segment2 plus PIDs13096/14865). TCL 55C655/G09, Android12/API31, 32-bit armeabi-v7a. Local overlay sample softwareDrm=false, despite user's usual Software DRM ON requirement. Separate actual route/setting in new tests.

- All 12 recorded composed segments JVM/HEAP despite Rust requested; MAP/FLAG actual Rust is not proof the stitcher ran Rust. Four explicit headroom planner denials, lowRam=false/lowMemory=false. Source arithmetic proved prior 1/8 allowance insufficient. Four vectors (input, output, OS available, OS threshold, JVM headroom): [5474084,7002945,621977600,150994944,303428224], [5226177,6258463,651563008,150994944,321931440], [5591105,6324612,619515904,150994944,268680776], [5221449,6295460,672854016,150994944,323391488]. Old arithmetic 0/4, revised arithmetic 4/4; not physical results. First exact forecast 90,700,353 bytes vs prior allowance58,872,832; revised allowance117,745,664. Native ceiling actual reserve/workspace, not full allowance. Recovery fraction /4 and modest growth12.5% are provisional engineering choices, original low-RAM tier retained.
- 12 segments of 5,760ms duration: median total7,453.5ms, range4,887–23,200; median compose6,155ms, 10/12 negative 1x slack; first two-feed probe total23,200ms, compose21,950, process allocations188,690,712 bytes and 8GCs. No recorded three-feed ACTIVE in 896; two-feed briefly active. Older builds did play three feeds but are not matched or flawless. User reports >2min startup; not a measured individual stitch.
- Timing trace40069614 V2 rebuild: map24.855s then lost61.796s; tower209.248s then lost212.121s, recovered259.768s, history stage236.283s, overall323.403s. Next trace40450646 map16.815s, tower47.656s, rebuild again; no archive-complete marker. Older warm/complete sessions first readiness0.475–2.941s; cold older baseline also slow. Do not infer failed cache write without evidence.
- TLS identity renewed in PID13096; PID14865 had three successful TLS1.3 connections then closed ~21–23s later. Last two loads one AVC1918x894, close1.78/2.78s after init. 26 PesReader unexpected start code prefix warnings. No real first-frame acknowledgment; sender route and close initiator unknown. Zero timing RX may be valid baked mode. Google Cast user report is real, but no sender/Google receiver log establishes exact cause.
- Vendor drop frame logs 11,190 after dedup are NOT measured visible drops; 146 reason1 discontinuities many disabled feeds ~2s; one READY main rendered0 skipped6181. Segment2 11 rebuffer, 5 after-release messages, 6 dead-thread messages, 4 freeAllBuffers, 28 skip-frame, app-window slow39.6%, max2,835ms (segment1 55.7%, max3,837ms). No fatal OOM evidenced.
- PID11329 mixed-phase PSS226.7–300.8 MiB median259.4, graphics17.4 external unknown; active Media3 ~589824 bytes, parked zero, one graphics owner, native zero. Not a valid TV Rust baseline. Do not transplant phone graphics budget to TV. SELinux dmabuf access unavailable.

## 5. Mandatory remaining execution checklist

Paths below are relative to app/src/main/java/app/apexview unless specified. Treat existing code as a candidate to review, not instructions to blindly append duplicate functionality.

### G-TV1 / G-C1–2 — Rust admission and ownership

- Audit NativeStitchingEngine.java, tiled/NativeClearArchiveBridge.kt, tiled/NativeFragmentLease.kt, NativeStitchingDeviceBudget and native/architecture-g/src/{engine,jni_bridge,fragment_storage,atlas_job}.rs. Confirm all four denied vectors, low memory/JVM/packet/overflow/32-bit bounds, actual ARMv7 ABI and JNI signatures, real eligible native route, no forced admission.
- Existing planner recovery, bounded one-idle adopted backing, actual capacity guard, final-reader reuse without sentinel, pressure watermark, snapshot lengths 5/9/15/21 and compatibility tests are in candidate commits. Verify all callers, stale release, worker-owned in-flight generation and new engine/seek/close interleavings. Do not reintroduce no-live-reader rule for active atlas permit; explicit close/seek/pressure revoke it. Retain generic final-reader retirement.
- Audit remaining admission duplication: adopted Vec already resident, future commitment must only reflect truly incremental replacement/worker workspace. Current JNI subtracts future_commitment(bridge) and requires capacity<=spare; NativeFragmentLease uses recovery /4 minus staging, ordinary /8; copy path may still fall back to heap. Verify against real simultaneous ownership and do not drop safety reserves without proof. Track actual scratch where feasible, distinguish lower bounds, allocation rejection vs cache-disabled, fit/ceiling/pin/pressure/stale misses. Review atomic generation and lease lifetime, security wipe actual capacity, no writable alias after publication. Do not use Vec.clear as wipe.
- Tests: real sizes 7.64/8.18/9.39MiB with framing, boundary/too small/too large, 1,000+ stable cycles including final reader without sentinel, every eligible reuse, parallel consumers, low RAM, stop/seek/cancel, stale generation, bounded peak/idle, byte parity. Audit tests inherited from 10905 for changed final-reader rule and exact arithmetic. Candidate tests are unrun.

### G-C3 — Native-to-delivery copy audit

- Review tiled/HevcArchiveVodSession.kt, ArchiveSegmentDelivery.kt, NativeClearArchiveJob, NativeClearArchiveBridge, NativeFragmentLease, BoundedArchiveFragmentCache, CmafHttpReader and all Cast HTTP paths.
- Existing immutable lease path already supports Media3 DataSource reads, retry/range and cache eviction without wiping pinned bytes. Identify remaining actual copies (source input arrays, output baseline, header/initialization copies, segment(number) mutable contract, relay range writes, native admission fallback). Remove only unnecessary copies; preserve exact same-input cold JVM validation and independent output validation, legitimate Media3 buffers, reader lifetime, content security and lease close. Do not claim literal end-to-end zero-copy when platform requires a transfer.
- Existing NativeClearArchiveBridge fallback can disable context for rest of session after runtime/admission failure. Distinguish recoverable transient resource denial from unsupported/malformed/parity failures; bounded retry when proven headroom changes, no spin or unsafe fallback accepted as successful Rust ON. Review all clear local and Cast routes and protected header/container ownership.
- Add byte-exact, offset/range/retry, cancelled readers, parallel Cast/local lease and final-reader tests.

### G-C4 — Media3 ownership

- Existing 4ebb41f adds bounded load ledger, forward buffered duration, remote/parked role, pending observation cleanup. It measures cumulative downloaded bytes by explicit track type, NOT live sample bytes. Default/muxed classified OTHER. Back buffer/free allocator/per-track sample queue are explicitly NOT_EXPOSED.
- Review FeedPlayer.kt, Media3MemoryOwners.kt, MediaMemoryAccounting, ArchiveBufferPolicy, receiver allocator integration. Confirm correct application-looper snapshots, exact item and track state, actual active vs parked and remote owners, load cancellation/clear/reset/late events. Add receiver equivalent where applicable, ensure observation counters don't retain player/loader resources. Retire demonstrated stale allocations and loading work only; don't shrink active buffer durations or share allocators merely because parked players have zero bytes. Distinguish forward buffered duration on empty tracks from actual media. Record unknown internals honestly. Add regression for repeated lifecycle and no diagnostics retention.

### G-C6 / G-C7 — Graphics, codec and capture (start here as biggest unimplemented investigation)

- Last chat inspected but DID NOT modify scene/SingleDecoderSceneView.kt, ProtectedDecoderSceneView.kt, ReplayClipEncoder.kt, SceneGraphicsOwners.kt and Viewer.kt. Both scene views have scalar owner registry and attach/detach/release logs already; do not duplicate. Existing protected SurfaceView and optional clear SurfaceView retain GLES atlas crops. Review SceneSurfaceLifetime/SceneOutputReadiness/SceneCodecTimestampCorrelation, cadence gate, queue diagnostics, Cast receiver and GPU capture.
- Actual source risks for review (not proven device leaks): SingleDecoderSceneView.destroy invokes stopBakedCastOnGl, captureFailed and saveClip before remaining input/EGL cleanup; exceptions could prevent final retirement; release queue uses destroy then output release and quit in same lambda without finally; SurfaceTexture lifecycle ownership needs exact single release on destroyed/replaced/released races. ProtectedDecoderSceneView.detachOutput posts destroy and quitSafely; failed post does not prove successful release. Never call GL deletion on wrong thread or forcibly free a codec-owned surface. Keep original producer detach on Media3 app looper before consumer destruction, no main-thread blocking. Review safe per-step finally chains, idempotent per-generation retirement, failure attribution and EGL release result, stale callbacks. Preserve SurfaceHolder ownership (do not release framework-owned Surface).
- ReplayClipEncoder.close currently destroys EGL surface, deletes GL objects, releases codec/input/muxer; review which context is current, exception-safe release, clear references/headers and CPU bitmap ownership. PixelCopy may still own bitmap: do not recycle while in flight. ReplayClipEncoder construction/copy failure and cancellation, recording finalization and baked GPU target surface ownership need exact tests. See BakedCastGpuCapture and existing worker lifetime. Maintain recording functionality and legitimate clear-only capture policy.
- Correlate scalar graphics owner ID, backend, surface/readiness generation, codec owner, EGL windows/contexts, decoder texture, capture encoder surface, physical queue/layer and timestamps; retain actual counts, no speculative vendor byte estimates. Add create/attach/resize/replace/detach/retire traces and deterministic tests, only remove proven redundancy. No second decoder.
- Existing clear SurfaceView experiment in Viewer.kt/settings is opt-in; instrument comparable route, layout, quality, overlay/caption, output and physical cadence. M13 structural promotion remains conditional on same-quality physical proof, no raw atlas or DRM bypass. S22/TV route differences are expected. No global renderer default change yet.

### G-TV2 — Multifeed startup and producer

- 4ebb41f now fences actual selected feed identity and quality before decoder release and host install, adds startup stage timing and coalesces repeated quality changes, elapsed/speed-aware rolling clock invalidation. Review coroutine cancellation and ownership closely: qualityRestartPending, compositionHandoffOwner, stale capture checks, rollback, old-scene preservation. Ensure restart callback survives cancellation and clear/rolling/baked replacements all work. Test superseded quality during network and during release, explicit seek, ordinary clock movement, stop/restart, feed replacement, captured host still valid.
- F1ArchiveFeedCapture/F1HlsFeedCapture: finite prep currently creates worker, fetches master/media/init, audio/caption offers, then fixed-position probe; worker.reset clears cache. Audit reusable exact offers and probe/geometry/fragment across compatible selection/generation (do not cache stale signed URLs/entitlement across sessions). Mandatory audio validation preserved; optional alternate audio/caption discovery cannot indefinitely block first visible video but must remain selectable and exact. Consider appropriate bounded parallel I/O, preflight timings and priority. Existing source players can advance during clear preflight and handoff position may now require different fragment; avoid throwing away valid proof or unnecessarily chasing clock, use validated current fragment, keep single decoder and correct time. Bound real retries, preserve existing unsupported route safety. No quality downgrade.
- HevcArchiveVodSession.prepare and requestSegment: inspect warmup single flight, producer preemption, native budget availability, foreground priority, actual fetch/compose/verify/slack. No repeated network refetch or decode handoff for ordinary playback drift. Measure intent→offers→manifest→probe→fragment→decoder→visible scene. Keep exact rungs, real source names, 2/3/4+ feeds; no fake READY.

### G-TV3 / G-C5 — Timing/cache and managed ownership

- 895/896 independent map/tower startup publication and V3 stopped bit must remain. 6b30d23 avoids cancelling healthy preview writes, records true Boolean cache outcome, retries failed saves, trims preview history and prioritizes near-start map. Verify constructor/signatures, lapAt/lastCrossing/pace predecessor correctness, retry throttle (avoid endless disk writes), seek/window validity, cancellation/delete and V2→V3 complete atomic write. Do not silently discard valid cached rows when map changes.
- LiveTiming.kt, ReplayStartupPublication.kt, ReplayCoreCache.kt, DriverTiming.kt, AppState.kt: primary timing preview currently can stop parsing after target+60s/10s and spool remaining, full topic merge later. Assess moving playhead coverage on slow TV, prioritize independent map/rank windows, avoid full duplicate histories, spool/index and update covered preview with bounded memory, retain valid progress where possible. Do not parse full data twice indefinitely or claim partial history complete. Check cached geometry, corrected clock, retired publication roots, full topic strings, duplicate arrays and network response lifecycle. Rust owns eligible numerical timing/map work; Android/cache I/O stays platform. Warm ready <=5s if covered; cold aim <=10s subject to source constraints.
- Use supported ART retained-root/dominator evidence where available; do not diagnose leaks from class count alone. Preserve real driver out/stopped/recovery, tower S1→S2→S3 cadence, map motion, opacity/size/layout, captions. Add cold/upgrade/restart/seek/hidden overlay tests; source unavailable explicitly unavailable.

### G-TV4 — Trust UX

- Existing PitwallRememberedPeers, PitwallTls.Session.trustKey, receiver/sender capability negotiation, bounded 32 pins, forget all, third Allow removal. Both previously authenticated peers reconnect without redundant prompts. First-ever comparison still requires phone and TV confirmation; this is a deliberate security-preserving deviation from requested one-TV-action UX. Do NOT claim G-TV4 complete on that basis. Review authenticated out-of-band or transcript-bound single-approval design with code visible both sides; phone selection only cannot authenticate an untrusted TV by name/IP. Do not invent trust-by-LAN, weaken TLS or bypass F1 entitlement. Preserve secure legacy and clear revocation/re-pair behavior. If single-action scheme not safely realizable without additional protocol/UX decision, explicitly identify genuine blocker and present exact safe alternative; no silent waiver.
- Validate first-use, remembered, one-sided forget, cert renewal, spoofed same-name/IP, replay, rejection, multiple sender, app restart/upgrade. Check persisted local+peer hashes, explicit revocation, no trust-key logging.

### G-TV5 / G-TV8 — Pitwall Cast, lifecycle and pacing

- f4f0762 bounded optional timing batch (at most one queued/in-flight, rejected batch triggers complete refresh), one delayed retry after negotiated pinned connection, same exact peer key, restore local first, typed user-disconnect notice best-effort. Review FcastConnection, CastTimingPublisher, CastSessionCoordinator, CastResourceCoordinator, AppState, PitwallReceiverActivity and CastConnectionService. Verify full geometry/table/frame refresh semantics, chunk maximum, no partially accepted batch or stale playback ID. Avoid full-refresh storms and control starvation.
- Reconnect must not retry deliberate disconnect/forget (notice can be lost; review secure behavior), preserve user playback position, audio/CC/quality/geometry and service/relay lifetime; cancellation/generation fences for stop/new selection. Correlate sender+receiver session/load/playback ID and close initiator, media URL request/load/first frame/acks without leaking secrets. Actual error handling and source/receiver handoff, no premature local suppression. Test real sockets, handshake, queue pressure, close races and same/changed peer.
- Parked/remote/no selected track clock-sync skip already candidate. Audit remaining callbacks after release, dead handler, seek loop, visibility/clock, 120Hz and 50fps cadence, BufferQueue/GC/UI/GL contention. Distinguish vendor discard, codec frame and actual visible presentation. Preserve active audio/text followers. Keep full cast timing/map updates and one receiver video decoder.

### G-TV6 — TS/PES

- 6b30d23 native ts_mux.rs + JNI adds complete ADTS header extent/frequency validation and packet envelope/PAT/PMT CRC/PES PTS/continuity/video PCR checks; Kotlin reference also checks ADTS. Review compile compatibility of Rust helpers and tests, exact declared lengths, CRC location, continuity across segment boundaries, packet adaptation, timestamp wrap and marker, audio/video PES reconstruction, keyframe, PAT/PMT, no corrupt publication. Existing FFmpeg decode fixture only may cover JVM writer; add independent native fixtures and native/reference parity, media3 decode integration where supported. Do not claim exact source of 26 TV warnings until proven sender route.
- Review BakedCastTransportStream, BakedCastLiveSession, BakedCastSegmentQueue, BakedCastArchiveAudio, CmafCastAudio and NativeComposedCastSession. Baked queue bounded with selected commentary, monotonic PTS, keyframe boundaries, 12s ready policy; don't arbitrarily reduce active safety to hit 10s goal. Separate prepared startup from real-time accumulation. Preserve baked and receiver-rendered map/tower/CC/commentary and one receiver decoder.

### G-TV7 — Google Cast

- f4f0762 custom layout waits matching CAF status plus correlated rendering acknowledgment; baked/default CAF status remains protocol proxy, not physical first frame. Callback fenced by client/load ID, 45s watchdog, synchronous baked load error handled. Audit AppState callback timing and custom receiver event/schema, retired relay and HTTP requests, first manifest/segment/byte/ready/visible metrics, diagnostics with route/session IDs, stale ACK/failed load/disconnect, correct handback and no premature stop.
- Review standard/custom/baked receiver capabilities and deployment (web/cast/receiver.mjs, tools/test_cast_receiver.mjs, tools/test_cast_layout_receiver.mjs, cast-receiver-pages.yml). Test actual compatible profile and authorized URLs, one decoder, media/relay lifetime across screen off/reconnect, quality and captions/audio overlays. User-reported failure not yet proven fixed. Do not fabricate sender/receiver evidence.

### Conditional G-C8 / M12 and M13

No blanket allocator replacement. Identify actual allocator and establish reproducible live-vs-resident gap after other owners retire, then evaluate supported reclaim outside hot path. No undocumented purge or unsafe ABI change. Renderer promotion only after measured same-feature parity, as above. These decisions remain conditional and must not be falsely counted as failed unconditional coding or completed physical work.

## 6. Review prior uncompiled changes before release

Inspect actual diff, not only commit messages. Important review candidates: Rust output reuse/peak counters and capacity guards; pressure/discard watermark across generation; stale release reinsertion; native snapshot schema consumers; ts_mux fixture compatibility with Rust compiler and real ADTS; ReplayCoreCache preview semantics and cancellation; remembered peer certificate property visibility/imports; Cast retry jobs and stop cancellation/identity; quality handoff generation rollback; Media3 listener lifecycle. These are source-review risks, not established compiler failures. Fix real errors and preserve tests. Do not relax the CI instrumentation runner expected test counts; audit footer matching actual test annotations after any new instrumentation case.

Use an explicit requirement-by-requirement ledger in the existing master plan: ID, source owner/path, code status, test coverage, verification status, remaining evidence. Reconcile all original G.1–24, R1–8, M1–13 and P0–P2, previous A–H/P0-A–P1-F. Distinguish implemented code, compiled/tested CI, physical acceptance, user sign-off and conditionally deferred. No invented percentages or claims of performance from source changes alone.

## 7. Tools, build and regression checks

Use the existing GitHub branch connector for direct commits, with expected head SHA / fast-forward lease; read exact source at head, apply coherent changes, verify returned commit/ref. Never force-push or touch main. Use [skip ci] for intermediate push-compatible workflows; verify workflow triggers and don't dispatch while incomplete. GitHub Actions provides full Gradle/Android/NDK/Rust toolchain; unavailable local compiler is not a reason to stop coding. Perform available static/host checks first. Exact commands in Build 896 §8.2 and Build 892 §9.1:

~~~bash
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/pitwall_change_contract.py
bash -n scripts/run_rendernode_instrumentation.sh
node tools/test_cast_receiver.mjs
node tools/test_cast_layout_receiver.mjs
node --check web/cast/receiver.mjs
cargo test --locked --manifest-path native/architecture-g/Cargo.toml
~~~

Read .github/workflows/android-debug-apk.yml and inherited signed upgrade, G.0/G-A/G-B/G-C instrumentation and packaging gates. Other workflows include cast-receiver-pages.yml, official-release.yml, pitwall-approved-release.yml; do not trigger unrelated deployment. One consolidated implementation build only when 100% unconditional coding and tests reconciled. Fix failed CI and rerun as appropriate.

Diagnostics/reproduction scripts:
- scripts/analyze_pitwall_runtime.py --build 1.0.1792 for 896.
- scripts/analyze_pitwall_bufferqueues.py --pid, --from inclusive, --until exclusive (MM-DD HH:MM:SS.mmm).
- scripts/analyze_pitwall_frames.py; scripts/analyze_pitwall_overlay_ab.py; docs/perfetto/frame_timeline_export.sql.
- scripts/check_pitwall_memory_trace.py --require-heap; exact ELF build ID, PID, clocks and capture validity.
- scripts/pitwall_acceptance_matrix.py; scripts/validate_pitwall_acceptance.py; scripts/verify_architecture_g0_apk.sh; scripts/run_rendernode_instrumentation.sh.
- docs/perfetto/ASHELL.md, capture-memory.sh, pitwall-memory.cfg, pitwall-50fps.cfg; Build 892 includes exact heap-only v4 setup.
- Phone aShell/Bugjaeger commands when ready: dumpsys meminfo -a app.apexview_custom. Existing v4 phone-only heap capture 120s, 64KiB sampling, 10s dumps:
~~~bash
perfetto --background-wait --txt -c /data/misc/perfetto-configs/pitwall-heap-only-v4.cfg -o /data/misc/perfetto-traces/pitwall-build-896-rust-on.perfetto-trace
cp /data/misc/perfetto-traces/pitwall-build-896-rust-on.perfetto-trace /sdcard/Download/
~~~
Use actual new build number and separate ON/OFF filenames; copy only after capture ends. Export Pitwall logs. Separate heap traces from unprofiled acceptance windows and graphics/system traces. No raw logs/traces/media/credentials in git.

## 8. Physical acceptance after consolidated green build (not yet requested)

- TCL Android TV12 Software DRM ON required; separate OFF case with actual route noted. Local eligible Rust ON 2→3→4+ feeds at supported exact actual 1080/540 rungs, one decoder, correct overlays, warm/cold/upgrade, seek/speed/stop, same signed APK Rust OFF reference. No 32-bit forced admission or unsupported quality. Include direct and multiple decoder paths where supported. Separate protected/clear live/replay, DPAD, layout, map/tower and captions.
- S22 Ultra adaptive120Hz, exact same feed IDs/rungs/scene/layout/renderer, audio, captions, map/tower, playhead, speed, thermal/network/cache, fresh process and alternating ON/OFF order. At least three paired >=60s windows when practical; unprofiled PSS acceptance, separate traces for attribution. Measure idle/start/steady/postseek/stop and +30/+60/+120s; repeated cycles and >=2h full race. Absolute comparison to896 and relative same-build Kotlin. No deliberately degrading reference.
- Goals: Rust PSS median <=80% matched Kotlin, lower sampled peak, no sustained PSS/RSS/owner growth, stop recovery, >=30% stitching CPU reduction, >=90% avoidable overlay allocation churn reduction, 50/75fps supported policies, no new drops/rebuffers/feature regression. TV proposed first visible <=15s warm/30s cold, producer p95 <5.76s at1x/<3.84s at1.5x supported; cold usable map/tower aim<=10s, covered warm<=5s. Trusted reconnect no prompts, prepared Cast first media aim<=10s, 30min then full race Cast. These are targets, not measured results.
- Both Google Cast and Pitwall Cast standard/custom/direct/composed/baked/protocol-rendered, all supported clear/protected paths, selected commentary and CC appearance, one receiver decoder, actual first frame, audio/video sync, map/tower motion, quality/seek/control/reconnect, screen off, stop and return to local. Keep protected entitlement separate and existing secure output restrictions. No memory credit for failed/missing feeds.
- Measure PSS/RSS/anon+swap, Java/native PSS, native capacity/live/pinned, Media3 allocator, graphics and external system ownership separately. Unknown vendor data unknown. Research estimates nonadditive, not promised. If 20% relative target unmet, report attributable floor and remaining options; user approval required before gate/default changes.

## 9. Completion report

The next assistant should work through the code and tests, not simply repeat this handover. Final report only what actually happened: exact branch head, commits, requirement reconciliation and outstanding genuine blockers, checks run/results, consolidated workflow/run/APK if truly ready, no invented completion or physical sign-off. If still incomplete due to actual execution limit, preserve progress honestly; do not imply unattended continuation. The user explicitly wants 100% unconditional implementation before build and has repeatedly objected to voluntary stops after minor commits.

