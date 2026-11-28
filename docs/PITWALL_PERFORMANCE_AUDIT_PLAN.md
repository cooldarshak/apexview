# PITWALL TV: SINGLE CONSOLIDATED RELEASE AND ACCEPTANCE PLAN

## Mandatory user-directed scope completion and build gate (clarified 26 November 2026)

Complete every unconditional coding requirement in the agreed scope (including the expanded G-C M1–M13 and G.21–G.24 deliverables and integration) before triggering an implementation build. The user's restriction is on premature builds, NOT on intermediate commits. Incremental source and documentation checkpoint commits are allowed on the existing working branch, with CI suppressed (use [skip ci] for push/PR workflows that support it; verify applicable triggers, and do not dispatch workflows for incomplete scope). Alternatively stage locally. Do not describe a checkpoint as 100% coding complete or as a tested APK. Review actual code, wiring and test coverage; perform available static checks before triggering one consolidated CI build at complete scope. GitHub Actions is the established compilation, Android/Rust toolchain, automated regression and signed APK environment; lack of a local compiler or Android SDK is expected and is NOT a reason to stop coding or require local compilation before dispatch. Distinguish 100% unconditional coding completion from CI verification and physical acceptance. If CI fails after scope-complete dispatch, fix the failure and rerun as appropriate without weakening tests. Conditional M12 allocator and M13 evidence-dependent graphics redesign remain conditional, not falsely marked implemented. Report genuine blockers separately. Preserve quality, features, Widevine, phone/TV, single/multiple decoder and all Cast paths. This clarification overrides earlier wording prohibiting intermediate commits or requiring local compilation, but never authorizes repeated builds for incomplete scope, a change to main or the parent branch, or an unsupported completion claim.






## User clarification and G-TV1/G-C admission implementation checkpoint (26 November 2026)

TV playback, casting, timing and memory are one interdependent Architecture G recovery scope, not competing priorities. User confirms retain Rust on Android TV and phone; do not solve regressions by moving app-owned composition/audio/timing processing back to Kotlin or another engine. Rust OFF remains the matched reference and existing safe explicit unsupported/failure behavior stays intact until independently reviewed; a JVM fallback is **not** acceptance for an eligible Rust ON route. Keep Android/Media3/MediaCodec/Widevine, UI, authenticated transport and lifecycle integrations at their approved platform boundary. No claim that fixing Rust admission alone fixes independent Cast trust, transport, timing-cache and renderer defects. G-TV1–8 and G-C1–6 plus feasible G-C7 instrumentation are one unconditional consolidated scope; M12/M13 promotion remain conditional. No implementation build until all unconditional coding and regressions are reconciled.

G-TV1 first source checkpoint: exact Build 896 TV rejection vectors reveal the existing Java-side Android headroom planner (NativeStitchingEngine.planClearAtlas) denies all four even with sufficient Java headroom and lowRam=false/lowMemory=false: 1/8 of OS surplus must cover two logical slot capacities, full native workspace and Java transient staging. For input 5,474,084 / output 7,002,945, OS surplus 470,982,656 bytes, original allowance 58,872,832 bytes, exact total forecast 90,700,353 bytes (formula 7*input + 5*output + 16 MiB + 576 KiB). This is a proven planner rejection, not a missing library or proven ABI allocation defect. Retain original tier and low-RAM behavior; normal-RAM recovery tier allows up to 1/4 measured surplus only when full native+Java forecast fits, retaining at least 3/4 plus threshold for all other owners. Use modest 12.5% observed capacity growth when fit, else exact; native ceiling only actual required slots + workspace, never whole allowance; keep Java transient allowance and native 192 MiB/16 MiB packet caps, lowMemory rejection and one engine. Rust JNI independently validates full compact atlas requirement using checked native arithmetic before opening the worker. Exact four-vector JVM planner regression and native budget boundary test added, existing parity/security checks retained. Diagnostics distinguish normal/recovery/forecast and OS-low-memory; finer rejection classification, all route coverage and physical 32-bit runtime verification remain open. No Kotlin media processing added; Java/Kotlin changes are existing platform admission bridge only. Recovery fraction is an explicit provisional engineering choice; evaluate actual TV RSS/PSS, pressure and buffer safety, not an assumed physical memory win.

Checkpoint only: no full CI or device verification, no new signed APK. Continue remaining G-TV1 details, G-TV2–8 and G-C1–6/G-C7 coding and tests without dispatching an incomplete build. No user sign-off inferred.

## P0 TV and Cast physical regression recovery — Build 896 (26 November 2026)

The latest two TCL TV diagnostic exports establish additional release blockers. The [Architecture G authoritative TV and Cast recovery revision](handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md#authoritative-p0-tv-and-cast-recovery--build-896-physical-regression-audit-26-november-2026) contains the detailed evidence, source entry points, fixes, security and regression gates. Treat it as the execution order for the next consolidated batch alongside the research-derived G-C1–8 memory plan below. Retain previous sign-offs and full scope; reopen the affected TV/Cast physical gates. No source implementation or new APK in this documentation revision.

Evidence: both attachments contain Build 896 1.0.1792 (PID 11329 across segments), and the first also has older 1.0.1662/1.0.1678. TCL Android 12/API31 process is 32-bit. All 12 recorded completed 896 clear composed segments use JVM despite Rust requested, 4 native admission records explicitly reject, no native arena active; OS available ~619–673 MB and lowMemory=false at those rejections. Median segment 7.454 s for 5.760 s media, 10/12 negative slack, first probe 23.2 s; two-feed one-decoder ACTIVE seen but unstable, three-feed ACTIVE not recorded in 896. Earlier captures demonstrate three-feed ACTIVE, but different workload and some rebuffering. Timing trace 40069614 V2 rebuild, map first 24.855 s then loses coverage, tower first 209.248 s then lost/recovered; later trace 40450646 map 16.815 s, tower 47.656 s, V2 rebuild again. Earlier five completed archive traces readiness 0.475–2.941 s (not matched cache state). Three successful Pitwall TLS1.3 handshakes followed by generic connection closes, two decoder initializations followed by close ~1.8/2.8 s later, 26 unexpected PES start-code warnings, no recorded media rendering ack. Identity renewal logged in separate process. Google Cast failure user reported; app-process TV log cannot establish Google receiver/sender root cause. Latest 34 memory samples are mixed JVM/fallback phases (median PSS 259.4 MiB, peak sampled 300.8 MiB), not valid TV Rust acceptance. Local single-overlay softwareDrm=false explicitly logged; test established TV Software DRM ON and separate OFF reproduction. Do not infer OOM or actual visible drops from vendor logs.

| P0 recovery item | Existing release gates and mandatory evidence |
|---|---|
| G-TV1 actual Rust admission/ABI | P0.1/P0.2/P1.1: reproduce rejected logged vectors, granular reason, verified safe bounded native engine admission on armeabi-v7a, actual selected Rust for eligible composed TV playback; no forced admission. |
| G-TV2 multifeed startup/producer | P0.1/P0.2/P1.1/P1.3/P2.2/P2.7: eliminate repeated warmup/refetch/obsolete work and unsafe handoff; exact quality, 2/3/4+ eligible feeds, one decoder, real first-frame and stable delivery; measure end-to-end and producer slack, no fake READY. |
| G-TV3 cold/upgrade timing | P0.4/P2.2: preserve V3 stopped bit and clock-covered independent map/tower, prioritize preview, resume/reuse valid progress where possible, retain atomic complete cache and explicit miss reasons; test cold, repeated restart, seek and warm <=5s with covered cache. |
| G-TV4 Pitwall pairing UX | P2.1/P2.4/P2.5: one first-time trust approval transaction, no repeated approval for valid remembered authenticated peer; mutually pinned TLS, revoke/re-pair on identity change, no same-LAN auto-trust or DRM entitlement bypass. |
| G-TV5 Pitwall disconnect | P2.1/P2.2/P2.4: correlate control, load/epoch, relay, first-frame ack and close initiator; secure bounded reconnect and retained media lifetime; restore phone only at correct handoff, no premature suppression. |
| G-TV6 transport integrity | P2.1/P2.2/P2.9: independently validate generated MPEG-TS/PES, continuity, timestamps, audio, segment alignment and actual receiver; identify sender path before claiming exact cause. Preserve baked and receiver-rendered overlay/caption/commentary routes. |
| G-TV7 Google Cast | P2.1/P2.2/P2.4/P2.9: separate standard/custom/baked sender/receiver diagnostics and compatible media/relay lifecycle; actual first media and sustained physical playback; user failure remains open. |
| G-TV8 hidden lifecycle and cadence | P0.1/P0.3/P0.4/P1.2/P2.2: repeated seeks on non-decoding players, callbacks after release, vendor discard vs physical presentation, GC contention and seconds-long app window stalls; generation fences, single clock, deterministic retirement and regression coverage. |

The next implementation must complete unconditional G-TV1–8 recovery scope, the previous research G-C1–6 and feasible G-C7 instrumentation, and all applicable inherited scope before one consolidated signed CI build. Evidence-gated M12 and structural M13 stay conditional. Proposed TV targets: first visible multifeed frame <=15s warm / <=30s cold under measured conditions; 5.76s segment producer p95 below media duration at 1x and 3.84s at 1.5x where supported; cold first usable overlays aim <=10s (source constraints reported); trusted Cast reconnect without prompt, first media aim <=10s when prepared, sustained 30-minute Cast check followed by full-race coverage. These are proposed targets, not measured results or permission to reduce quality/safety. Preserve the matched same-signed-build Rust PSS <=80% Kotlin, lower peak, full race >=2h, prior CPU and overlay allocation gates. Functional failure or missing features cannot count as memory savings. Start with existing logs and code; request targeted sender/device evidence only where genuinely needed. Distinguish coding, CI, physical results and user acceptance.

## Architecture G memory ownership research revision — Build 896 (26 November 2026)

The existing [Architecture G master plan, authoritative G-C memory ownership revision](handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md#authoritative-g-c-memory-ownership-revision--build-896-and-industry-research-26-november-2026) now incorporates the supplied Deep Research report and controls the next consolidated implementation batch. Use [Build 896 engineering handover](handovers/PITWALL_TV_BUILD_896_MEMORY_ENGINEERING_HANDOVER_2026-11-26.md) as the exact physical/build evidence and script reference. No separate Architecture H, native plan or reset of existing scope. G.0/G-B sign-offs and G-A foundation verification preserved; G-C memory and G-D physical acceptance open. All original P0–P2 gates, G.1–G.24, R1–R8, M1–M13 and prior follow-ups remain.

Build 896 green at 75577ebf25ab006871d6d034ff3eb57a0795e2d8, run 45818263209. Matched 895/896 Rust windows (one different onboard): 652.0/637.0 MiB median PSS, 678.5/654.1 MiB sampled peak; 896 longer 645.5 median/676.0 peak. 896 graphics 252.1 MiB, Java heap PSS 182.7, native heap PSS 76.4, active Media3 allocator 77.5, arena capacity 24.3 and workspace 7.87; these are overlapping views, not additive. 54 output allocations and zero reuse (41 payloads >8 MiB). No proven runaway leak; one stop recovered near 191 MiB. No matched Kotlin denominator or full-race acceptance yet.

| Research-derived scope | Release ledger effect |
|---|---|
| G-C1–2: unique ownership telemetry and bounded final-reader Rust output reuse | P0.1/P0.2/P2.2/P2.6: capacity-aware one-idle policy, device headroom, pressure/seek/close, representative 7.64–9.39 MiB and 1,000+ cycle tests. Churn improvement is not a PSS guarantee. |
| G-C3–5: JNI and media delivery copy audit, active Media3 attribution, managed retained-root audit | P0.1/P0.2/P0.4/P2.2/P2.9: remove demonstrated duplication; preserve retries/ranges, prefetch safety, captions, cache, playback clock and warm overlay readiness. Do not cut active buffer safety or infer parked-player allocator bytes. |
| G-C6–7: graphics/codec/EGL/BufferQueue ownership and controlled clear SurfaceView experiment | P0.3/P1.1/P1.2/P2.1/P2.5: keep exact decoder count, atlas/layout, secure paths and overlay fidelity; remove only demonstrated redundant owners; device-dependent renderer promotion requires measured parity. |
| G-C8: conditional allocator experiment | P0.2/P2.6: M12 remains closed unless actual device allocator retention is demonstrated after owner retirement; no unconditional dependency or allocator swap. |

Acceptance: same-signed-build, fresh-process matched Rust ON/OFF actual engine, identical renderer, feed IDs/rungs, settings and conditions, alternate order, at least three paired steady windows when practical. Preserve the <=80% matched Kotlin median PSS target, lower sampled peak, full-race >=2h stable memory and post-stop recovery; report absolute improvement versus Build 896 as a separate metric (shared optimizations must not artificially degrade Kotlin). Retain >=30% stitching CPU and >=90% avoidable overlay allocation reduction targets, actual physical frame cadence and complete phone/TV/Cast, clear/authorized protected, decoder modes and feature parity. No lower quality or weakened buffering, DRM or features for memory credit. If target remains unmet, document measured residual floor and seek user decision, not automatic waiver. Separate coding, signed CI, physical and user sign-off.

Implement all unconditional G-C1–6 scope and feasible G-C7 experiment instrumentation, plus applicable inherited work, in one consolidated batch, with only proven corrections and conditional experiments gated by evidence. No repeated premature builds; CI-suppressed checkpoints permitted. Use existing logs/captures to start, request only specific missing causal evidence. Planning update only, no new APK or production change.

## Build 887 CI correction (26 November 2026)

Build 887 (8f9d37798ac0bda63da54fe8d4a27f4beeae5811) failed at the first Android
Rust ABI compilation, armeabi-v7a. The host kernel checks had already run.
The new nativeAdoptAtlasFragment invalid-argument return used the JNI i32
INVALID constant in an i64 result closure (E0308). Convert explicitly to i64,
matching adjacent JNI entry points. No production behavior, ownership,
quality, DRM, test gate or scope change. Android build and physical acceptance
remain pending exact-source verification.

## Build 886 follow-up — owned-output publication and measured bottleneck investigation (26 November 2026)

Baseline: Build 886 GREEN (dd4e2b45008b6c330fb8645fa0f66a39ef30ebcf).
The latest supplied log contains Build 884 and 886 captures; use version/PID
boundaries. Build 886 PID 30798, app 1.0.1772: 34 successful native-backed
outputs, 25.3 KiB cumulative Java output copies, one hardware decoder, no
persistent media backing after shutdown. Median PSS 646.3 MiB versus prior
Kotlin 605.0 MiB (uncontrolled cross-session comparison); agreed target
remains <=484 MiB against that baseline. Native heap 84.5 MiB and graphics
267.1 MiB are not independent additive categories. Native allocation-site
stacks are missing from the supplied trace. Latch ~49.9, GL submission ~48.75
fps; not proof of scanout. No vendor buffer-count or allocator tuning based
on these aggregate metrics.

Consolidated scope:
- M4/M6/M8: best-fit reuse, cap slack at min(12.5%, 1 MiB), discard unsuitable
  idle backing before new allocation, replace stale free backing on release.
  Existing 64 MiB total, 12 live, two free/8 MiB idle caps unchanged. Replay
  the 34 measured media sizes with a simple FIFO three-entry model (no pinned
  readers, seek effects, allocator overhead or OS RSS): old 33 allocations
  / 34.46 MiB peak; revised 16 / 27.86 MiB. Regression replays sizes plus a
  small packet prefix and bounds peak and allocations.
- M3/M6: single-fragment clear compact worker calculates final size after
  validation and writes directly into exact-sized owned output (or bounded
  best-fit reused backing). JNI moves the Vec, not its contents, into immutable
  fragment arena; adoption must pass generation, exclusive reading lease,
  range, capacity and headroom checks before consuming output. Only media
  slice is visible to private JVM lease; header/init still copied separately.
  Retain old bounded native-copy/heap fallbacks, fixed multi-fragment window,
  all existing clear/protected/Cast/decoder paths. No codec/DRM or video
  quality change. Count adoption, owned-output allocations and reuses.
  During owned running jobs native payload is a known lower bound, with
  remaining commitment still charged; peak-pair counter is a conservative
  bound. Explicitly distinguish these from actual resident memory.
- M2/M3: interruptible finite HLS archive preparation; dispose cancelled
  preflight before decoder handoff, wipe unneeded input payload as soon as
  composed output ownership is resolved; explicit one-feed clear pass-through
  ownership transfer avoids redundant full-media copy. No rolling/protected
  cancellation contract or player buffering thresholds changed.
- Pacing: do not further defer an already coalesced pending picture; preserve
  its age/backlog across unavailable draw until explicit valid output.
  Both renderers use same guard; no new decoder, queue, blocking or surface.
  Log codec lifecycle, declared format sizes, input/output surface identity
  and output geometry. Declared format/graphics categories are not vendor
  buffer allocation sizes.
- M1/M12/M13 blocker: dedicated 90s pre-playback heapprofd configuration and
  phone-only start/collect script, explicit layer dump mode, producer check,
  package-scoped memory and layer snapshots, exact build symbols. Native
  heap allocation site attribution, graphics/vendor codec byte attribution,
  actual PSS target and pacing improvements still require device evidence.
  No speculative allocator replacement or arbitrary codec surface changes.
- Verification: full Python suite 110/110, shell syntax checked; host Rust
  byte parity and native transfer fixture, bounded arena replay/immutable
  ownership tests, Android ownership/seek/close regression, Kotlin
  cancellation/pacing/diagnostic tests, and expanded JNI ABI verification
  staged. Rust/Android compilers unavailable in remote staging environment:
  CI and physical acceptance remain separate and pending. All unconditional
  coding above consolidated before one commit, no interim builds.

## Build 885 CI correction (26 November 2026)

Build 885 (967297cf82c440269bb2fa1bb12cfc65cc11d8a7) failed the Python packaging contract: the Build 855 regression still required a JVM-only media array and its size expression. Native host tests passed 59/59, including sanitizer execution; Android compilation and publication were not reached. Restore the strict single heap fragment invariant and explicitly reject simultaneous native and heap media ownership. Extend the original regression to require exactly one output size derived from either backing, both publication routes preceding budget accounting, and unpublished native cleanup. Run the entire CI Python discovery suite before committing: 110/110 PASS. All Build 885 engineering changes and mandatory CI gates remain intact. Physical attribution and PSS acceptance still pending.

## 26 November 2026: Build 884 physical follow-up, consolidated implementation

Baseline: Build 884, `8793e583fff8dacc44abd3ea057c298e1c0faa46`,
all five CI jobs green. [Exact baseline run](https://github.com/cooldarshak/apexview/actions/runs/45668693169).
User approved all six follow-up actions plus a persisted manual thermal playback override.
No new architecture, allocator, decoder, DRM policy, selected quality, or stitching algorithm.

### Evidence and decision ledger

| Scope | Evidence from supplied captures | Implemented / investigated |
|---|---|---|
| Archive output ownership | 19 matching clear archive segments, median compose 304 ms Kotlin / 24 ms Rust; 134,657,697 cumulative Java output bytes. Cumulative bytes are NOT simultaneous memory. | Compact atlas output media copies directly from acquired Rust slot into a bounded unpublished Rust fragment; then validated immutable native lease transferred into archive cache. Media3 range reads and existing SceneCastRelay use the same lease; cache eviction, seek, engine retirement and concurrent readers preserve lifetime. Initial/reference metadata stays on JVM, byte parity and DRM gates unchanged. Native storage admission failure retains existing bounded heap output without forcing lower quality or changing the stitching engine. |
| Three inactive players | Thirty samples per inactive Rust feed, 07:26:22–07:27:23, STATE_IDLE, zero allocator bytes, no decoder/audio; host owns approximately 58–61 MiB. | No inactive Media3 buffer leak established. Retain lightweight parked instances for switching; stop/clear and trim unused allocator blocks. Add per-player role, item count, active video/audio decoder counts, and allocator bytes, plus receiver owner metadata. Do not destroy players on an unsupported leak hypothesis. |
| Native allocation / graphics attribution | Median process PSS 605.0 vs 623.3 MiB Kotlin/Rust; native heap allocated 40.4 vs 58.4 MiB; platform graphics 240.2 vs 249.1 MiB. Neither system trace contains uncompressed ProfilePacket (field 37) nor compressed packet (field 50): 170,208 and 169,227 packets respectively. Trace PID 21688 differs from log PID 26741. | Add bounded separate native heap + process/GPU/layer/frame-timeline config, trace coverage checker and phone-only capture/symbolization instructions. Expanded process PSS categories, actual owner identity and surface/atlas dimensions. Allocation-site stacks and external codec/GPU bytes remain UNKNOWN until matched physical evidence is supplied. No speculative allocator/surface redesign. |
| BufferQueue/pacing | Both modes have immediate dequeue refusals (mDequeueTimeout=0), one input texture/one output view, 60 Hz display and 50 fps atlas, around 250 latches/5s, 242–250 submissions, zero reported codec drops. Fractional 60/50 cadence and latch coalescing are not proof of decoder starvation or duplicate buffers. | Keep immediate texture latching, original 60 Hz presentation, platform seamless-only display negotiation. Bound high-refresh deferral by age of oldest pending picture + next vsync vs one output period. Record measured latch/submission rates, cadence compatibility, surface identity and atlas; do not claim physical scanout. |
| JNI diagnostics | Repeated unresolved native snapshot/trim calls before library load. | Explicit readiness set only after correct ABI loaded, diagnostic/trim paths do not load or invoke unresolved JNI. Preserve unknown vs measured zero. |
| Native admission | Reserved 32,753,104 B, materialized payload 16,081,640 B, reported future commitment 185,244,952 B. Previous calculation used permitted ceiling rather than planned capacity; pre-clamping fragment headroom to 64 MiB then subtracting this commitment blocked native fragment allocations. | Reserve actual planned slot/bridge/stack + bounded workspace, minus materialized bytes, constrained by existing ceiling. Apply the OS incremental allowance before the arena's independent 64 MiB / 12-live-fragment hard caps; retain existing JVM and OS admission, no quality reduction. |
| Thermal playback override | Device thermal status 3 during test; actual display 60 Hz despite advertised 120 Hz. | Advanced settings: Temperature-based playback adjustments, default ON, persisted per device. OFF bypasses ONLY Pitwall temperature policy in visible feed budgets, archive speculative warming, Auto preparation and accelerated cadence checks. Actual thermal status still logged; Android hardware thermal protection, pipeline health, memory limits, authorization and manual quality stay unchanged. Receiver uses its local setting. |

Scope gate: unconditional coding changes and investigation of supplied evidence complete
before the one functional commit. Separate physical proof remains required:
matched fresh-process Kotlin/Rust PSS, peak RSS and GC, native heapprofd allocation
call stacks with matching build-ID / ABI symbols, observed graphics/codec ownership,
clear/protected, local/remote Cast and TV lifecycle/playback regressions. The agreed
20% lower total PSS target has NOT passed. Moving cached output to native memory
does not imply an equal reduction in PSS; one native-to-native copy and Media3's
required bounded read copies remain. No native allocation callsite can be recovered
from the supplied timing-only traces. M12/M13 redesign remains evidence-gated.
See `docs/perfetto/pitwall-memory.cfg`, `docs/perfetto/ASHELL.md` and
`scripts/check_pitwall_memory_trace.py` for the exact next capture.
Pre-commit checks: full 42 production-wiring tests, 3 trace-coverage tests,
7 coordinated-change contract tests; Rust/Android compile and instrumentation
gates are CI-only in this workbench (no installed Rust/Android toolchain).
The new tests cover native transfer and pinned eviction, unpublished/exposed
storage safety, exact future reservation, inactive allocation, pending cadence
age and thermal override. No CI gate was weakened or bypassed.

### Build 883 green baseline / M1–M13 consolidated completion

Baseline: c06a6cc17eee4dccc857fff5553aa148e5390ad8, [run 45662092202](https://github.com/cooldarshak/apexview/actions/runs/45662092202), five successful jobs. This consolidated change supersedes the earlier partial memory-workstream ledger below. Implementation and pre-commit verification are separate from exact-source CI and measured device acceptance.

| Item | Completed coding scope |
|---|---|
| M1 | Process memory and unique-owner accounting, current/peak foreground and speculation forecasts, native engine reservation/materialized slots/copy/allocation counters, native fragment charged/live capacity, Media3 actual allocation, graphics attribution and explicit unknown external codec/GPU memory. |
| M2 | Shared foreground/speculative group admission, checked byte forecasts, serialized native headroom admission, incremental fragment charging including outstanding native commitments, existing per-owner hard limits retained. Mandatory foreground work never rejected by the optional-work forecast. |
| M3 | Transfer clear HLS source ownership at successful publication, retain immutable heap/native rolling output into local DASH and Cast, reuse backing identities across windows, release on final owner; legacy copy API stays compatible. |
| M4 | Lazy compact native slot allocation and retirement, exact allocation counters, free-only trim with on-demand rematerialization and read-lease regression coverage. |
| M5 | Shared pressure hysteresis, trim only idle/free or unpinned behind-playhead entries, pause/background hooks, eight-job slab/right-size hysteresis and existing generation-safe engine replacement. |
| M6 | Compact, bounded immutable backing reuse and checked ranges, no duplicate full-fragment rolling copies, final-owner zeroing, unique-byte accounting. |
| M7 | Warm native atlas workspace/header slab reuse, generation-fenced validated init cache, fixed protected descriptor array, bounded slab shrink and warm parity/allocation checks. |
| M8 | Forecast reservation across overlapping capture/composition work and publishing; baked Cast pending plus publishing bytes share the queue cap; finally release charges, preserve frame order and existing hard bounds. |
| M9 | Weak Media3 allocator registry for feed, radio and receiver, actual in-use byte totals and excess-free-block trimming with existing LoadControl duration/byte targets preserved. |
| M10 | Cache pressure trims, shared backing retirement, active reader protection, generation/close cleanup, tests for final release and slow consumers. |
| M11 | Host allocator wrapper limited to the regression fixture (System allocator unchanged), live/peak/churn/copy measurements, repeated clear/protected parity checks, CI artifact with exact SHA/host architecture, Kotlin/Rust tests and production wiring. |
| M12 | Conditional: no allocator substitution without matched physical evidence of allocator fragmentation/retention; production System allocator remains. |
| M13 | Instrumentation/attribution retained and connected; decoder/graphics ownership redesign conditional on physical evidence. Do not remove surfaces, change protection or lower quality on an assumption. |

No source-quality, frame-rate, decoder-count, DRM, Cast-protocol or minimum playback-buffer target change. The forecast is not PSS; live OS/JVM headroom already reflects resident allocations, so do not subtract all owner totals a second time. Unique shared heap backing counted at its owner; individual store pinned/retired snapshots describe reader lifetimes and are not additional process allocations. External GPU/codec allocations are not inferred from app heap. Host allocator peak tracks requested allocation bytes rather than allocator internals or Android RSS.

Engineering choices: recover optional prefetch only after 10 seconds above max(16 MiB, threshold/4) additional headroom, throttle pressure trim to 30 seconds, shrink atlas workspace after eight consecutive small jobs. Reclamation may require reallocation/backward refetch; physical latency verification is required. Existing native hard ceilings, Media3 target buffering and manual rendition selection are not weakened.

**Pre-commit gate:** all 41 production-wiring tests passed on staged source. New Kotlin and Rust regression tests are included in the unchanged mandatory CI pipeline; full Android/Rust toolchain is unavailable in the editing workbench. CI success and physical acceptance are not claimed here.

#### M1/M12/M13 physical measurement instructions (debug/profileable builds only)

1. Install the APK from the exact successful CI run. Record commit SHA, APK variant, device model, Android build/API, ABI (adb shell getprop ro.product.cpu.abi), refresh and power/thermal state. Keep its architecture-g0-apk-symbols artifact and match the ABI and native library build ID before symbolizing any stack. Never use release customer profiling or change DRM settings merely to acquire a trace.
2. Run the same race/session, segment range, 1080p + three 480p feeds at 50 fps, same layout, selected audio, speed and device conditions on Kotlin and Rust. Alternate order; repeat cold/warm runs, 1x and 1.5x, seek, pause/resume, stop and Cast. Repeat on Android TV and protected/clear paths when available. Record baseline idle, startup peak, steady-state median/peak, after-stop recovery, and a full-race sustained test. Capture at approximately 10-second intervals; report exact sampling interval.
3. Identify the actual installed debug application package via adb shell pm list packages and PID via adb shell pidof PACKAGE. Sample adb shell dumpsys meminfo PACKAGE; retain PSS/RSS and graphics categories where reported. If permitted, sample /proc/PID/status and smaps_rollup; access denials are unknown, not zero. Save application logs containing GC_PROCESS_MEMORY, GC_NATIVE_TRIM and existing codec/surface/refresh/queue/playback markers. CI gc-memory-host.txt contains GC_HOST_ALLOCATOR, GC_WARM_ATLAS and protected warm-path evidence. Do not add overlapping categories as if they were unique physical memory.
4. If the device supports heapprofd and the exact build is debuggable/profileable, capture a matched 60-second native allocation trace with Perfetto (example below). Save config, trace and matched symbols. In Perfetto UI inspect allocation sites, retained bytes and lifetime; compare the same time window. If permissions/device support prevent tracing, report this limitation, use dumpsys and existing owner counters, and leave allocator redesign conditional.
5. Correlate external codec/graphics costs using available dumpsys media.codec / SurfaceFlinger data and existing Perfetto frame/BufferQueue traces; keep protected content secure, never capture raw protected frames. M12 allocator work requires demonstrated allocator retention not explained by live/reserved backing. M13 redesign requires demonstrated avoidable duplicate decoder/surface/graphics ownership. Neither is a prerequisite to running this implementation through CI.

Example Perfetto text config (replace PACKAGE with the actual package; run using adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/pitwall-memory.perfetto-trace, piping this config to stdin, then pull the trace if permitted):

```text
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
data_sources {
  config {
    name: "android.heapprofd"
    heapprofd_config {
      process_cmdline: "PACKAGE"
      sampling_interval_bytes: 4096
      continuous_dump_config { dump_interval_ms: 10000 }
    }
  }
}
duration_ms: 60000
```

Acceptance remains: Rust steady-state PSS at most 80% of matched Kotlin, lower peak, no sustained memory growth, no regression in frame deadlines/drops, buffering, seek latency or output quality; all single/multiple decoder, phone/TV, clear/protected, local/Cast paths preserved. The numerical target is not yet measured or claimed. M12/M13 remain evidence-gated, not falsely closed as production redesigns.

### Build 882 green baseline / consolidated memory ownership and pressure batch (25 November 2026)

Verified all five jobs successful for exact source 2b05c4c320307ec43ac445f97892bf3b29420fda, [run 45657408075](https://github.com/cooldarshak/apexview/actions/runs/45657408075). Preserve all existing gates. No change to codec, Widevine, source quality, feed count, frame rate, buffer durations or Cast protocol.

| Workstream | Implemented / remaining boundary |
|---|---|
| M1 | Process PSS/RSS, heap/graphics category, fragment capacity/live bytes, engine reservation/materialized slots/bridge, speculative current/peak forecast, local and relay unique backing attribution. Existing symbol artifact and benchmark-only profileable manifest retained. Matched heapprofd and external codec/parser attribution pending. |
| M2 | Shared process speculative archive forecast prevents simultaneous warmers independently spending headroom; common estimate and live OS/JVM headroom; release on every exit. Full common admission across mandatory foreground, native, Media3 and Cast remains open. |
| M3 | Existing direct JNI transfers, exact known-length CMAF acquisition, shared native/sidecar ownership and no full-copy prefetch retained. Remove temporary flattened list of source references at cleanup. Further full-copy reduction open. |
| M4 | Compact spare payload slots lazy; retire extra free pairs at all terminal releases rather than only seek; keep one warm free pair. Other owner allocation audit open. |
| M5 | Pressure trim behind requested segment only for unpinned entries under OS lowMemory. Retain requested/forward window and active readers. Native free spare retirement at release/seek; fragment capacity bounded. Adaptive resizing/hysteresis outside these owners open. |
| M6 | Native immutable lease, capacity charging, bounded nearby-size reuse, zeroing/final retirement retained; new DASH/Cast diagnostics distinguish shared native logical bytes from arena capacity. Physical native retention verification pending. |
| M7 | Existing generation-fenced atlas validation cache/header slab and fixed descriptor arrays retained. Warm regression reuses workspace. Further parser optimization requires measured site evidence; open. |
| M8 | Speculative group reservations include observed input/output and existing allowance; peak forecast visible. Foreground queue unchanged; general source/compose/relay pending-byte coordination open. |
| M9 | Explicit existing-default Media3 allocator and diagnostics retained in player and receiver. No buffer-duration or quality changes; cross-player allocator coordination needs attribution. |
| M10 | Pressure-aware eviction of only unpinned older archive cache entries. Cache/relay/DASH ownership and retired-reader accounting. No eviction of live advertised media or pinned readers. Protected/relay adaptive policy remains open. |
| M11 | Existing generic fixture retained; additional compact fixture with 12 repeated real atlas jobs, byte parity and single materialized payload pair, unchanged stale/invalid/seek checks. Added JVM forecast, trim and accounting regressions and wiring checks. Full CI and physical paired PSS/peak/quality gates pending. |
| M12 | Conditional allocator experiment NOT triggered: no controlled heapprofd proof of fragmentation. Keep system allocator. |
| M13 | Existing platform graphics category and player diagnostics retained; no codec or surface ownership removed without proof. External codec/GPU allocations explicitly not measured; physical attribution open. |

Diagnostic owner-level projections and logical references are not additive process PSS. Count native capacity once at the arena, shared audio/text once at its budget, and reserved/forecast bytes separately from materialized allocations. Relay and DASH logical references can overlap with source history. Pressure trim may require re-fetch on backward seek under lowMemory only; measure before accepting. No physical RAM savings asserted. Six targeted production-wiring checks passed in remote checkout; full Rust/Android toolchain unavailable there, so exact-source CI mandatory. M1–M13 not represented as 100% coding complete; all physical acceptance gates remain.


### Build 881 first-failure correction (25 November 2026)

Build 881 ([run 45656595936](https://github.com/cooldarshak/apexview/actions/runs/45656595936), exact source `1e486403be8f132126e3a2d9c69f0a9249998fa7`) failed in mandatory host Rust test compilation, error E0063: missing `compact_atlas` in the existing deadline-priority test State initializer. Initialize that generic test state explicitly to false; production state already uses the supplied mode. Audited both State constructors. Keep all deadline/priority assertions, spare retirement tests, strict lint, sanitizers, Android ABI and device gates unchanged. No memory policy or playback behavior change in this correction. Exact-source CI pending; Build 880 remains last verified green. M1–M13 status and physical acceptance gates above/below remain open as recorded.


### Current approved G-C memory optimization execution (Build 880 baseline, 25 November 2026)

Build 880 exact source `4b97ef8c77ee8df047e978f39181ed6795ea0572`, run [45651321774](https://github.com/cooldarshak/apexview/actions/runs/45651321774), independently confirmed all five mandatory jobs successful (139336286363, 139342738770, 139342738882, 139342738927, 139344730867). Head before this functional candidate `e740c832d3a38e5fa2d59fbf508637aea63ba5c9` adds the approved documentation only. G.0/G-A/G-B prior sign-offs unchanged. The user explicitly approved M1–M13 and all acceptance criteria in [Build 880 execution handover](https://github.com/cooldarshak/apexview/blob/feature/architecture-g-rust-stitching/docs/handovers/PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md). The following is the active extension of the master plans, not a separate architecture. Previous checkpoints below are historical.

**Consolidated source candidate (CI and physical verification pending):** opt-in 10-second process PSS/RSS, JVM and native heap, platform graphics (not external codec/GPU), OS headroom/thermal and unique native fragment snapshot; no forced library loading on Kotlin. Clear bridge records logical arena reservation and materialized payload lengths including worker-owned slots; workspace/allocator overhead not represented as payload. Fragment storage charges full capacity and reports live logical length, live capacity, total capacity, count and cumulative new backing allocations; smallest available backing reused only within 12.5% slack capped at 64 KiB, no larger parent pinned by a small slice; every returned view retains exact logical length, free buffers are zeroed and released after final reader. Compact spare slot free pairs trimmed at seek/stale retirement, leaving one warm free pair and every active slot intact; generic eager arenas, logical admission and workspace ceiling unchanged. Clear archive reports unique cache backing (cached plus retired pinned, without double counting current readers), and only speculative warmup yields when retired pinned bytes and estimated new output exceed the existing cache allowance. Existing foreground path and playback thresholds unchanged. Media3 allocator explicitly uses its existing default trim/reset and segment size, logs in-use allocation bytes in local feed and native receiver state diagnostics; no artificial reduction of buffer targets. No third-party dependency, no DRM/media schema or quality changes. Rust unit tests, JVM accounting tests and production wiring checks added; exact-source CI still required. No physical RAM savings are claimed.

| Approved item | Coding status at candidate | Remaining requirement |
|---|---|---|
| M1 memory attribution | Partial: process/arena/cache/Media3 counters implemented | heapprofd exact ABI symbols, complete native parser/graphics, owner attribution, diagnostic mode matrix and physical PSS |
| M2 unified memory budget | Partial: unique clear cache accounting and existing admission audited | common reservation/realized-capacity policy across source/composed/relay/native/Media3 and concurrent handoff |
| M3 eliminate overlapping copies | Existing compact JNI and bounded acquisition retained | complete end-to-end clear/protected/Cast, audio/text and cache publication copy audit and further feasible removal |
| M4 lazy allocation | Build 880 lazy spare retained; materialized payload telemetry added | inventory other eager buffers and allocation counts across paths |
| M5 safe trimming | Partial: compact generation and stale lease retirement implemented | pressure/idle/peak capacity adaptation across workspace, relay and lifecycle |
| M6 compact immutable storage | Partial: bounded near-size reuse and logical/physical capacity counters in existing native store | complete per-path capacity/metadata and ownership audit |
| M7 reusable workspace | Existing header slab/generation cache retained | remaining parser/reference/job workspace attribution, bounded reuse and parity |
| M8 byte-based queues | Partial: clear speculative retired-reader gate | total in-flight reservations across foreground/prefetch/rolling/remote, no starvation |
| M9 Media3 coordination | Partial: local and native receiver in-use telemetry; existing time thresholds retained | live shared budget and safe time/byte coordination, all player paths |
| M10 pressure-aware eviction | Partial: cache unique/resident accounting, speculative gate, existing lease-aware retirement | pressure-aware eviction with safe playback minimum, all cache owners |
| M11 allocation regressions | Partial: native reuse/lease regression and JVM accounting/wiring tests | representative warm steady loops, copy count/peak bounds, ABI symbols/CI artifacts |
| M12 allocator experiments | Conditional; system allocator retained | only if attribution proves material retention/fragmentation after other fixes |
| M13 GPU/decoder memory | Partial: platform graphics attribution; no speculative rendering changes | codec/graphics/dmabuf and frame presentation attribution, measured safe optimizations |

All rows: new automated verification pending exact-source CI; new phone/TV physical acceptance pending. Prior evidence and tests remain intact. No item marked 100% or signed off by this candidate. User-approved physical acceptance: Rust steady-state process PSS <= 0.80 x matched Kotlin; lower peak memory; maintain JVM allocation improvement and Rust compose time; bounded post-warmup native churn and full-race memory growth; recovery close to idle after stop; unchanged manual quality, both hardware decoder modes, low-memory TCL with usual Software DRM, Widevine and both Cast families. Use the matched cold-start protocol in the approved handover, including repeated comparable intervals, same selected/actual qualities and decoder count, 1x plus separate seeks/1.5x, idle/startup/steady/stop/endurance, actual engine, PSS and RSS separately. RSS or reserved bytes do not substitute for PSS; no allocator replacement, extra workers or NEON without evidence. For mobile-only collection, enable existing Pitwall diagnostics and export Download/Pitwall logs containing GC_PROCESS_MEMORY; separate System Trace/heapprofd where supported. Do not request physical acceptance before green candidate. G-D remains distinct.


**Current Architecture G checkpoint (25 November 2026):** [Build 876](https://github.com/cooldarshak/apexview/actions/runs/45633549000), exact source `b57d0c370fc5cd48e4c8bb5e305c6f58bbb8f8e5`, verified GREEN in all five mandatory jobs: build/host 139272862915, API36 139282184962, API29 139282184981, API31 139282185346, final signed publication 139284550470. User G-B sign-off retained. The next consolidated G-C candidate implements further copy/allocation reduction, measured-bandwidth Auto selection and native worker trace instrumentation. Exact-source candidate CI and physical profiling are still required.

### Build 879 green: physical G-C profiling and memory correction (25 November 2026)

- Build 879, run 45642411324, source `32278a7f97410a5c1a0b33988510bfb9a06562d5`: all five mandatory jobs GREEN (host/build 139304317850; API29 139311327835; API31 139311327856; API36 139311328031; signed publication 139313613082).
- S22 Ultra SM-S908E / Android 16, app 1.0.1758; actual HLS clear replay, four feeds 1080p + three 480p, single hardware decoder, coded 1920x2176 at 50 fps; TV layout, tower and map enabled, 1x. Rust ON first, then OFF. Evidence: pitwall-20261125-155421-484.log.txt and the two 21-26-23 / 21-29-24 System Trace captures. The 17 matched media segments 800..816 have identical input/output byte totals (96,128,426 and 113,108,559). Median compose 25 vs 295 ms (Rust vs JVM); producer-thread CPU 41 vs 275 ms (excludes separate native worker); process-wide allocation during segment 18,678,304 vs 65,237,536 bytes; total segment 759 vs 926 ms; GC event count summed over matched segment intervals 19 vs 34. All 49 native operations succeeded, rejected submissions zero. Comparisons are not controlled for prefetch/foreground, network rate or thermal state. Manual quality preserved.
- First trace contains 25 native atlas spans: median 9.76 ms, max 28.22 ms; picture rewrite 8.69 ms median, parse 0.053 ms, mdat 0.867 ms. Wall time, not CPU or physical presentation. One worker is adequate for the demonstrated 5760-ms fragment cadence; no additional worker or ARM NEON justified on this phone. Reassess on low-end TV before declaring those conditional gates closed.
- Trace process_stats late snapshots: Rust RSS 598900 KiB (585 MiB), JVM 512904 KiB (501 MiB), roughly +84 MiB. Not PSS or an isolated causal comparison. JVM allocation reduction does not establish lower total memory. Expanded compact native arena reservation 35,890,878 bytes (34.2 MiB), input capacity 7,780,054 and output 9,870,473. Kotlin/JVM source caches, codec/surface allocations and process history must also be measured. Comparable steady playback logs show Rust zero dropped decoder frames and JVM 84 additional drops over nearby but not exactly equal position intervals; Rust was already thermally severe, JVM transitioned later. 60/120 Hz changes and GL coalescing still affect presentation; no physical FPS claim.
- Evidence-led correction: compact atlas engine keeps the full two-logical-slot reservation, foreground admission, stale-generation fences, read leases and workspace ceiling. Allocate one payload pair at opening, prefer an already allocated free slot, allocate another only for actual overlapping ownership. Generic conversion engines retain eager allocation. Allocation failure returns backpressure without publishing or modifying live leases. With the observed expanded capacities this avoids 17,650,527 bytes (~16.8 MiB) of eager payload allocation in the serialized path; this is an allocation estimate, not an observed RSS improvement. Retain no speculative concurrency, no quality or DRM changes. New native regression checks sequential reuse, lazy overlap, foreground reservation, backpressure and seek/close; existing parity and memory checks unchanged.
- G-C remains open for exact-source CI, matched clean-start RSS/PSS and native/Java/cache/codec allocation attribution, physical TV validation, Cast/protected handoff, and conditional mixed-Auto/remote geometry/switching. G-D remains separate; do not claim complete lower total memory until measured.

### Build 878 failure correction (G-C)

- Build 878, run 45640329497, source `c87a6f0fd6f3195cf2185961addd42fe431d5e5e`: all 52 host Rust tests passed, mandatory Clippy passed and 52 address-sanitized tests passed. Android ABI compilation rejected the new ATrace FFI in the safe engine under the existing crate-wide deny(unsafe_code) policy. APK/instrumentation/publication not completed.
- Move the trace guard and NDK FFI into the existing Android-only audited JNI/platform bridge, without expanding the crate's unsafe exemption. Safe engine and compositor use the same scope API; host guard stays no-op. The Android guard is thread-bound, does not retain any pointers/media/JNI references, and closes only an opened trace on drop. Native profiling stages and previous G-C optimizations retained.
- All 38 production wiring tests passed on the candidate with exact Build 878 dependencies verified by Git blob SHA. Strengthened assertions require the unchanged crate-wide unsafe policy, single existing exemption and safe engine trace call sites. Exact-source Android ABI, full CI and physical validation remain pending. Last verified green baseline is Build 876.

### Build 877 failure correction (G-C)

- Build 877, run 45638564822, source `409ccbd1f676054fdfe1a04fffe6020930153f3c`: all 52 Rust host unit tests passed, then mandatory Clippy rejected the eight-argument fragment writer. Android ABI/build/instrumentation and publication did not complete.
- Group previous fragment end and standalone flag into the typed FragmentBoundary value, keeping the original caller-owned trackers and reusable header slab. Same values, same validation, same lifetime and buffer bounds. No lint suppression or test relaxation.
- All 38 production wiring tests passed on the corrected candidate against exact Build 877 dependencies verified by Git blob SHA; existing parity tests and strengthened signature/temporal boundary assertions retained. Full exact-source CI still required; Build 876 remains the last verified green baseline.

### G-C continuation after verified Build 876

- G.9/G.10: route existing authorized DASH DataSpec reads through the same bounded HLS/CMAF reader. Preserve complete DataSpec including flags, position, length, request headers and resolved URI; retain DASH's EOF/size validation with a one-byte probe for known lengths, reject no-progress/truncation/oversize and wipe partial transfers. Known-length responses now use one final array instead of a scratch buffer, growing ByteArrayOutputStream and full final clone. Unknown-length response still coalesces once. No authentication, protected-sample or key handling change.
- G.12/R1/R6: replace one owned heap vector per rewritten clear NAL header with a bounded fragment header slab, retained by the worker dispatcher across jobs of the same generation. Store only checked header offsets and borrowed original VCL tails. The slab's maximum allocation is the admitted output capacity, within the existing output-sized workspace charge; no extra output-sized payload copy. New generation retires cached slab. Preserve scalar rewrite validation, exact output length/prefix widths, sample timing, and direct final mdat assembly. Protected prefix code remains unchanged. Per-slice reference parsing and per-job descriptor allocations still exist; no claim of allocation-free operation.
- G.14/R7: use fresh real Media3 bandwidth sample callbacks on the same singleton used by source players, require actual transfer (>=64 KiB), same active network, at most 30 seconds old. Reserve 30% for audio/variability and scale budget for accelerated playback. Apply only to all-Auto common selection at new local finite/rolling/live and clear/protected Cast preparation, using aggregate advertised bitrates and existing legal atlas geometry/quality floor. Unknown/stale samples, unknown offered rates, mixed/manual preferences and already-owned active scenes keep prior behavior. No sender screen dimensions substituted for receiver geometry; no automatic mid-session codec recreation. Log budget and actual chosen source separately.
- G.11/G.18/R4/R8: add Android NDK ATrace spans PitwallNativeAtlas, PitwallNativeParse, PitwallNativePictures and PitwallNativeMdat on the existing ordered native worker. Host/no-trace paths are no-op. Perfetto sched slices identify on-CPU time and contention separately from queue wait; no per-NAL trace overhead. Do not add ARM NEON or a second worker until actual target evidence identifies benefit and sufficient memory/thermal headroom. Keep one-worker admission and existing registry safety.
- Tests: all 38 production wiring tests passed on staged exact Build 876 dependencies (unchanged cached dependencies verified by Git blob SHA). New Rust tests cover slab parity, checked growth, reuse, invalid-range rejection and all NAL prefix widths; existing independently encoded fixture and decode tests retained. New JVM tests cover DASH DataSpec range/EOF and copy count, fresh/stale/manual bandwidth and aggregate offered-bitrate selection. Full Rust/Android compilation, host/sanitizers, exact differential tests, API29/31/36 and signed publication are mandatory CI, not claimed executed here.
- Remaining G-C evidence gate: run matched physical Rust OFF/ON on the same green candidate, same clear HEVC replay, four feeds 1080p + three 480p (or identical supported fixed qualities), single decoder, same playback position/layout, 1x for at least 60 seconds each, and capture Pitwall logs plus Android System Trace (CPU scheduling/frequency, app atrace, graphics, memory). Repeat on TCL with its usual Software DRM setting and verify actual Rust admission; if needed run protected replay/Cast smoke separately. Capture one all-Auto preparation after source traffic, retain actual rendition and GC_AUTO_BANDWIDTH diagnostics; do not compare Auto with fixed-quality A/B. If physical profiles establish an ARM rewrite bottleneck or worker contention opportunity, benchmark equivalent SIMD/worker alternatives and retain only measurable wins with parity and unchanged memory limits. Independent mixed-Auto tuning, remote geometry and seamless active rendition changes remain evidence-gated; do not force unsupported profile transitions. G-D full feature/endurance/thermal acceptance is separate. This is not a blanket G-C 100% or performance claim.

### G-C continuation verified by Build 876

- G.12/R1/R6: reserve a bounded flat output NAL descriptor slab per fragment; reuse one slice vector per source across pictures, clearing picture-varying state before each parse. Preserve all syntax, POC/EOS, timing, filter, size, cancellation and independent output validation. This removes the per-picture nested descriptor allocation and repeated per-feed slice vector allocations, not all per-NAL header or parser allocations. No payload copies or bitstream changes introduced.
- G.14/R7: add soft common Auto rendition preferences from actual local tile pixel heights, bounded by the existing route maximum/default, with hysteresis keyed by content and feed set and a severe-thermal/OS-low-memory pressure signal. Apply only at new finite clear, rolling HLS/DASH and live DASH preparation, only when every feed is Auto. Real authorized offered renditions and existing atlas/codec/security checks remain authoritative; a missing smaller common rendition can use the existing compatible fallback. Manual and mixed-manual choices are unchanged. No forced decoder restart on resize, no change in native/JVM selection, no speculative sender-to-receiver viewport mapping. Cast retains existing preferences because the remote visible dimensions and bandwidth are not established. Existing multiple-decoder Media3 tile adaptation remains intact.
- G.9/G.10/G.18/R8: add low-overhead submitUs (JNI admission/transfer), waitUs (queue, processing and poll wait), readUs (output lease read/release) and validateUs (independent output verification) to the existing native fragment diagnostic. Wait is wall time, not native CPU. Preserve exact copy counters. Existing G-C route and Cast lease implementation from 875 remains intact.
- Verification: all 37 production source-wiring tests passed on staged exact Build 875 sources with this candidate. Standalone Kotlin compilation of the byte-identical policy file and 16 behavioral assertions passed using the existing enum values; full Android/JVM tests, Rust host/ASan, independent decode, device instrumentation and publication remain mandatory CI gates. Added policy tests, source wiring, and real encoded compact JNI fixture repeat after generation change with exact bytes and timing fields. No weakened tests.
- Profiling-dependent gates still OPEN: G.11 NEON, R4 worker concurrency, remaining allocation/network/Media3 boundary decisions, actual bandwidth-aware and independent per-feed Auto tuning for mixed/remote layouts and any seamless live rendition switching. Do not add SIMD or workers just to close a checklist. One ordered worker remains the conservative policy. Need matched real-media CPU/alloc/copy/memory/thermal/cadence measurements and actual receiver layout/route and ownership handoffs. No 100% G-C claim, no G-D sign-off, no Rust default change.

### G-C initial integration and copy-reduction (verified in Build 875)

- G.9/G.10/R1/R5: Clear atlas production constructs small wire descriptors and borrows existing validated source arrays through synchronous JNI region transfers directly into reusable Rust worker slots. Read acquired output directly into final Java arrays. Removes the full concatenated Java packet and both separate native bridge buffers for compact atlas mode; generic G-A diagnostics remain intact. For the actual 873 first arena (7,080,580 input + 8,149,900 output bytes), the structural reserved-capacity saving is 15,230,480 bytes (~14.5 MiB), not a measured total process RAM reduction. Media3/DataSource and network/HTTP copies still exist. Explicit accounting distinguishes bridge and JNI copies.
- G.12/R3/R6: Keep the same exact first-reference comparison, generation fence, one bounded resize and reusable two-slot arena. Protected native allocations use actual device headroom with a conservative 64 MiB cap, trimming only free allocations; never evict a live reader. Reuse the private direct-buffer cursor rather than allocate a wrapper per Media3 read. Per-job parser and slice allocations remain a measured refinement, not a claim of allocation-free operation.
- G.14/R2/R4: Fresh playback position/buffer/speed snapshots set bounded native job timeout; seek invalidates stale clock. Memory headroom and observed transfer size restrict speculative prefetch only, in addition to existing thermal limits. Foreground playback and explicit quality selections unchanged. Retain one native worker; no unprofiled concurrency or ARM NEON changes.
- G.19: Enable eligible finite clear Cast production, retain baked Cast's existing composed scene, pass the Rust preference and device context into native rolling Cast capture. Protected Cast gets independent group-specific preference, first-reference validation, actual publication attribution, and bounded native fragment storage. Relay publication transfers retained immutable native leases and reuses the same backing across windows; socket reads use bounded scratch and keep leases until completion. Preserve authorized ciphertext, original Google Cast/Pitwall transports, overlay/audio and TLS policy. Direct single-feed/non-stitch routes do not manufacture an atlas. Receiver/device and simultaneous owner handoff validation remain open.
- G-C tests: mandatory ABI symbols and instrumentation for direct region transfer exact parity, copy counters, invalid size/stale generations, borrowed input identity, native relay alias rejection and reader survival, and Cast preference isolation; JVM policy tests for clock/deadline and low-memory speculation. No removal of prior gates. CI must verify the exact candidate.
- Outstanding G-C work/validation: physical matched profiling to determine SIMD, additional worker concurrency, allocation hotspots, Auto rendition/layout hysteresis and further publication/network transfer opportunities; no unsupported claim of 100% completion. Keep manual quality unchanged. G-D remains the full device acceptance matrix.

### G-B physical admission correction (Build 872 follow-up)

- Source: user capture `pitwall-20261125-120916-728.log.txt`, app 1.0.1744, S22 Ultra API36. All 31 recorded archive segments report `actualStitcher=JVM`; observed source payload maximum 6,297,176 bytes and output maximum 7,766,310 bytes (excluding packet/init overhead). Four feeds, one decoder, 1920x2176 coded, 1080p + three requested 480p; actual other source displays 640x360. The exported segment contains no explicit native admission failure, selected Rust preference or available-memory measurement: the fixed 32 MiB native ceiling is an independently confirmed source-policy defect, not proof of the exact runtime rejection reason. No Rust speedup or TV result claimed.
- Keep generic G-A conversion limits. Clear atlas jobs use two slots; calculate full native arena and checked workspace, plus JVM staging, from observed sizes and current Android memory headroom. Combined charge may use at most one eighth of available bytes above the OS threshold, with independent JVM transient headroom and a 192 MiB hard native ceiling. Low-memory OS state rejects admission; low-RAM devices use exact capacities, other devices try bounded 25% capacity growth then exact sizing. No forced allocation, per-device exception or quality change. Aggregate atlas packets and output cap 16 MiB; individual source BMFF and conversion input cap remains 8 MiB. Larger jobs remain explicit unsupported/fallback rather than silent downscale. Native workspace checked again before queueing.
- A distinct native OUTPUT_CAPACITY status fences incomplete output. One bounded resize only, close/join old arena before opening another, re-sample headroom, compare exact Kotlin output, retain deadline and generation fences; no retry for invalid syntax, DRM, metadata or unrelated worker errors. Subsequent admitted fragments use Rust directly. Persistent per-segment requested/actual/reason and admission diagnostics make physical verification possible even when log capture rotates. Retain existing authorized DRM, codec and Cast behavior; no G-C scope started.
- Tests: real four-feed size regression, lower and higher headroom low-RAM cases, insufficient JVM/OS headroom, larger packets and ceiling; Rust reserve validation; actual encoded fixture capacity error plus JNI bridge resize/parity and native-only reuse. Full CI required before install. Physical re-test: install next green build, Rust ON, single decoder, same replay, 1080p + three 480p selections, play at 1x for 60 seconds and export Pitwall logs. Require repeated actualStitcher=RUST with no unjustified fallback; TCL remains physically unverified. G-B coding and verification remain open until the defect is resolved on real media.


## Current status — 24 November 2026, Build 841 TLS failure correction

**Only active engineering plan.** Repository: `cooldarshak/apexview`. Stable branch: `main`; working branch: `feature/pitwall-performance-audit-plan`. Both contained the identical Build 839 source tree at the start of this documentation cleanup. Exact tested production commit: [5090df55](https://github.com/cooldarshak/apexview/commit/5090df55d3fbef02703dacb66c514a3a185f8db5), [Build 839 GREEN](https://github.com/cooldarshak/apexview/actions/runs/45391665766), debug app version 1.0.1678. Merged into main via [PR #5](https://github.com/cooldarshak/apexview/pull/5). Documentation-only updates do not establish new binary acceptance.

Build 839 corrected the Build 838 Android overlay capture pixel-test failure. Mandatory automated checks passed on the exact production commit. The earlier 16/16 coding-complete ledger described the then-identified scope, not a guarantee of defect-free behavior. Physical testing has reopened coding work. Do not use old percentages as current status, invent percentages for unestimated new work, or treat a green workflow as release approval.

Build 840 completed successfully at [a70fed21](https://github.com/cooldarshak/apexview/commit/a70fed21bfa5759d70d77bbf9cf323b74524de3f), [exact CI](https://github.com/cooldarshak/apexview/actions/runs/45422254393), including JVM, independent HEVC decode, APK and Android instrumentation. This continuation addresses items 1, 5, 7, 8, 13 and 14 in one batch. Architecture G remains deferred. Build 841 [exact CI](https://github.com/cooldarshak/apexview/actions/runs/45426176111) passed the full Kotlin/JVM, independent HEVC/CMAF, signed APK, RenderNode/TextureView/SurfaceView, GPU capture and archive delivery checks. Its new AndroidKeyStore mutual TLS test failed before pairing with SSLHandshakeException (connection closed by peer). Android documentation specifies DIGEST_NONE for TLS prehashed signatures: https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setDigests(java.lang.String...). The previous key generator omitted that authorization. Correct it on new and existing installation keys; require a raw ECDSA signing self-test, retain mutual TLS and exact certificate pins. No test suppression. The follow-up also separates Cast muxing from capture and starts archive CarData at partial preview availability. Candidate pending its own exact-source CI.

### Dedicated Architecture G branch checkpoint

On `feature/architecture-g-rust-stitching` only, G.0 initial feasibility work begins. See [the consolidated G ledger](handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md) for exact prototype and open CI/physical gates. The 16-workstream acceptance remains unchanged. No production engine/default changes; parent and main untouched.

G.0 second batch on the dedicated branch: diagnostic JNI-RS/DirectByteBuffer, real clear fixture differential test, debug native packaging, signed upgrade/ABI/alignment and symbolication gates added; exact CI/physical evidence pending. Build 843 remains verified green; no player/DRM/feature changes or release acceptance claims. See the G ledger for detail.

Build 844 failed at the new native crash-symbol gate: all four Rust/NDK libraries lacked GNU build IDs. Exact artifact inspected; signed APK identity, version, native library packaging and ZIP alignment passed. Android-only linker flag, early build-ID checks and regression tests added in the dedicated branch. Build 843 remains the verified green baseline; no release acceptance or player change. See G ledger.

Build 845 GREEN (717cc4c): native diagnostic JNI, signed emulator upgrade, all ABI build IDs, symbolication, clear prefix equivalence and existing checks passed. Third G.0 batch adds batched copy-inclusive benchmark and cross-arena lease rejection; candidate pending CI. Existing playback and strict release status unchanged. See dedicated G ledger.

Build 846 GREEN; fourth G.0 candidate adds shared bitreader/box/synthetic metadata vectors and conservative CI tool-cache/Gradle daemon reuse. No test suppression, no playback/DRM changes. Exact CI and physical acceptance pending. See G ledger.

Build 847 GREEN but 22m24s, slower than 846. Dedicated G branch candidate freezes Gradle inputs, reverts unproven parallel Gradle mode, runs existing Android API29/31/36 tests as independent jobs with encrypted exact-APK staging and all-gates publication, and adds Rust clear CMAF byte equivalence. No feature/test removal. Actual CI and physical acceptance pending. See G ledger.

Build 848 stopped on the existing publication contract regression before APK assembly. Updated the regression to enforce both legacy and parallel workflows, including all four artifact gates. Exact targeted preflight passed; full CI pending. No test suppression.

Build 849 GREEN in 15m15s, all five jobs successful. Sixth G.0 batch adds clear slice rewrite with JVM-validated descriptors, matched C++ CMAF control and pinned cargo-ndk tool cache. Physical baseline now requested on green 849. No production change. See G ledger.

Malformed workflow in d5eed03 independently confirmed; restored 849 structure and reapplied intended changes with strict YAML/shell and targeted regression preflight passing. Full CI pending. No test or feature removal.

Build 851 GREEN; first physical ARM diagnostic passed all 290 byte comparisons. TV validation deferred by user, not waived. Seventh G.0 batch extends real encoded slice differential and optimizes clear EBSP tail validation without removing scalar checks. Production unchanged; CI pending.

Build 852: Rust host checks passed; JVM fixture exporter selected sample-only instead of owned-NAL-only output. Corrected test configuration and explicit ownership assertions; full CI pending. All differential coverage retained.

G.0 signed off by user; Build 853 GREEN. G-A consolidated native engine foundation, bounded lease lifecycle, scheduler, JNI, persisted request/explicit JVM fallback and regression candidate; production playback unchanged. Deferred physical and further acceptance gates remain tracked in Architecture G plan.

Build 854 GREEN (all five jobs, 20 Rust tests). G-A completion candidate adds deterministic native-owned private bridge buffers, cross-session token uniqueness, device-headroom admission and measured JVM boundary counters, mandatory ASan/LSan and churn/symbol regressions. All existing playback/DRM paths unchanged. Full CI pending; physical and G-B–G-D gates remain separate.

Build 855 stable and ASan/LSan Rust tests 22/22 passed each; Kotlin compile failed from duplicate local outputBytes declaration. Corrected by reusing existing verified fragment size; no safety or test reduction. Full CI pending.

Build 856 stopped at coordinated-change contract (missing changed regression test in prior fix). Added targeted archive observer regression and reused exact verified output fragment for both size attribution and budget observation. No policy relaxation. Full CI pending.

Build 857 verified GREEN (25 Nov 2026): run 45539807100, source 9dcbef0abc3fb92c35eb6c57d0db665cdb254fb1. G.0 user sign-off and G-A foundation coding/automated gates complete; stable and ASan/LSan 22/22, exact 106 slice comparisons, four ABIs, three Android runtime APIs, signed upgrade and final publication. Item 6 Architecture G is no longer deferred; next integrated phase G-B (G.3/G.4/G.5/G.16), only on user instruction. Native selector is persisted OFF, effective JVM until actual validated pipeline integration. Physical TCL/ARM G-A, matched playback/DRM, 16KB runtime and 16-workstream release acceptance remain open. See canonical [Architecture G plan](handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md) and [Build 857 handover](handovers/PITWALL_TV_BUILD_857_ARCHITECTURE_G_HANDOVER_2026-11-25.md). Historical rows below describing Architecture G as deferred refer to the original pre-G batch, not today's status. No source/workflow changes or new APK from this handover.

G-B started after verifying Build 857 and the documentation-only branch tip. The first batch adds bounded Rust clear CMAF and early HEVC parsing, POC tracking, initialization/fragment packaging, and an exact 50-frame two-feed output check with independent FFmpeg pixels and packet timing. The fixture uses JVM-validated slice plans and output parameter sets. G.3/G.4/G.16 remain partial, with live dispatch, remaining syntax and the broader differential corpus open. G.5 retains the authorized JVM/Widevine path and adds clear-side protection rejection tests. Native protected compatibility, exact-source CI and physical gates remain open. The [Architecture G plan](handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md) records the workstream details.

Build 858 passed all five jobs, including exact-source API29/31/36 instrumentation and final signed publication. Its tested commit is `26f2721a112e94d3aaf0154b09dd6c15a79ef015`. The first two-feed Rust CMAF gate ran in that build. [Build 858](https://github.com/cooldarshak/apexview/actions/runs/45545086735).

The next G-B batch generates clear parameter sets, slice offsets and fragments in Rust from original inputs. Local comparison passed for 150 independently decoded pictures across single-tile, full 2×2 and mixed-width sources. Synthetic CBCS tests check inline subsample coverage, IV widths and auxiliary layout. G.3/G.4/G.5/G.16 remain open for production playback integration and protected parity. No change to release acceptance or the default playback engine.

Build 859 passed all five jobs at `938c29342c0e20726cd4711f1583aced0263306a`, with exact Rust-generated initialization and media, 150 decoded frame comparisons over three clear cases, API29/31/36 instrumentation and signed publication. [Build 859](https://github.com/cooldarshak/apexview/actions/runs/45546930661).

The clear atlas worker batch writes the real CMAF result into an admitted Rust output slot. It reuses the bounded G-A queue and leases, checks exact init/layout/generation identity for cached configurations, and adds mandatory Android JNI tests for byte parity, seek invalidation and malformed rejection. Synthetic CBCS checks also cover compatible track/sample-group identities and reject changed KIDs. The archive playback route, controlled switch and protected parity remain open.

Build 860 passed all five jobs at `70479b02601d7c5d4e40361704069144ef581b83`. The clear fixture gate verified exact initialization and media bytes, decoded pixels, packet timing and 150 frames across single-tile, 2×2 and mixed-width cases. Mandatory API29/31/36 instrumentation verified Rust worker output and generation-fenced leases. Production playback selection, native protected parity and physical acceptance remain open. [Exact CI](https://github.com/cooldarshak/apexview/actions/runs/45549129456).

### G-B clear archive playback integration candidate (after verified Build 860)

The local finite clear multi-feed archive producer now has an opt-in Rust path, using the existing exact JVM initialization probe. The first native fragment per generation is compared byte-for-byte with the actual JVM composer; subsequent accepted fragments use native-produced initialization/media, independently validate sample timing and flags, and publish through the existing bounded archive cache and Media3 DataSource. Requested/actual routing is explicit and RUST is reported only after a native fragment is accepted at publication. The JVM remains default and permanent fallback. Any native admission, validation, library, parity or operational failure keeps the requested preference but falls back once for that session, after native retirement; the existing JVM validations still apply. Seek changes fence publication, JNI leases are released, native retirement runs off the UI, and the existing playback coordinator handles local preference changes. Cast-specific and protected/live paths retain JVM; no Widevine, login, renderer, decoder-count, quality, audio or caption policy change is intended.

The Java admission planner now includes the existing Rust atlas workspace reserve (4× input capacity + output capacity + 8 MiB) in addition to the slot/bridge budget. It rejects inadmissible low-RAM jobs without changing quality. Exact observed packet/output sizes are used; a later larger input can require a fresh bounded reference/admission, and a larger native output can explicitly fall back. This is not a measured zero-copy or TV throughput improvement; first-generation parity and remaining packet/bridge copies are deliberate correctness costs.

Mandatory GB Android instrumentation retains the original worker/lease tests and now round-trips the real encoded GBC1 fixture through the actual JVM packet builder and local archive bridge, checks exact initialization/media and runtime-only actual routing, proves the next fragment does not invoke the JVM comparator, tests stale publication rejection on seek, later invalid-command fallback, and bounded admission fallback without a retry loop. Unit regressions cover selector proof and workspace-aware admission. Candidate Android CI, physical local playback/DRM, full protected-native parity (G.5), additional supported JVM clear profiles and the broader negative corpus remain pending; do not mark G-B complete or begin G-C/D. Default remains OFF. No main/parent merge or release.



### G-B extended clear compatibility and protected metadata-only native header candidate

Build 861 [exact run](https://github.com/cooldarshak/apexview/actions/runs/45556720979) at `baa80d6a91b38aead7e30d2773eca99304e873c4` passed all five jobs (build 139010434273; API31 139014098206; API36 139014098254; API29 139014098312; final publication 139015514814). Signed APK artifact 14997205900; matching HEVC instrumentation APK artifact 14996821521; native host/ABI 14995369430; symbols 14995879851; unit reports 14996795812; independent HEVC output validation 14996820782. The existing opt-in local finite clear archive Rust bridge and actual-publication selection, first-generation exact comparison, subsequent native-only generation, seek fencing and fallback have passed the automated gates. Physical phone/TV playback, sustained throughput, Cast and protected-native parity remain unverified.

This next consolidated candidate extends the clear atlas worker to handle exact in-band VPS/SPS/PPS identity, supported non-VCL AUD/EOS/EOB/filler/SEI NALs, terminal zero padding on borrowed ranges and legal 1–4-byte secondary source NAL lengths. First-source/output length width stays four bytes to match the existing emitted hvcC. Reserved NALs, nonzero layers, invalid temporal IDs, mismatched in-band parameter sets, incomplete/duplicate tile coverage, dependent/entry-point unsupported slices and TMVP remain rejected. No unsupported coding-tool compatibility is inferred. The normal three independent real clear output cases and FFmpeg gates remain, with a real tiled profile mutation compared byte-exactly to the original JVM fixture on the host and a second differential using the actual JVM composer and JNI on Android. Changed in-band parameter identity must fail even with a cached exact init, without output mutation.

The new Rust GBP1 metadata-only protected header writer reproduces the validated JVM moof/traf/mfhd/tfhd/tfdt/trun/senc/saiz/saio header and checks emitted inline CBCS records independently. It accepts only bounded sample sizes/timing/flags and existing validated IV/subsample records, no ciphertext or keys, no PSSH/entitlement/CDM access. Per-sample IV 0/8/16 and both tfdt versions, exact complete fragment comparison, opaque synthetic payload preservation, malformed/truncated packet and mismatch/unavailable fallback are tested by mandatory Android instrumentation; host Rust negative/extent tests and all four ABI JNI symbol checks added. JVM validates all source encryption identities and protection maps and retains its original header writer; only an exactly matching Rust header can be used. All original independent output checks and PSSH/tenc/sinf preservation remain. Explicit local protected replay/live opt-in follows the existing native preference, disables on Cast handoff, falls back for the session on a native header mismatch or unavailability. Actual protected stitcher continues to report JVM (metadata-only Rust is not a native protected HEVC composer). No default switch, no policy change, no full native protected claim. The native path deliberately still computes the reference header on each protected fragment until separately proven; no performance gain is claimed.

Candidate CI and physical tests are pending. Full native protected slice planning/rewrite/assembly, complete supported JVM-profile equivalence and authorized Widevine device parity are not closed; retain G.5/G.16 and G-B as partial. Preserve all original workstream acceptance requirements and do not start G-C/D without approval.



### G-B protected clear-prefix and sample-map differential candidate after verified Build 862

Build 862 [exact run](https://github.com/cooldarshak/apexview/actions/runs/45560653622), commit `d5222b08a909f3e19893200354e76d55fe6f40a4`, passed all five jobs: build 139023280807, API31 139027258069, API36 139027258074, API29 139027258108, publication 139028599319. Extended clear HEVC input compatibility and the protected metadata-only moof writer are automated-test verified, with the existing licensed Widevine path unchanged. No real F1 native performance or protected device acceptance is inferred.

This candidate adds GBS1 and GBM1 bounded native differential kernels. GBS1 independently parses source and output VPS/SPS/PPS and the exact already-inspected clear NAL prefix, then generates the escaped output header and source splice offset, supporting both previously supported clear CABAC and header-only splice modes. The existing clear worker uses its unchanged non-opaque mode. GBM1 independently plans CENC subsample range metadata from bounded validated NAL sizes/ranges, keeping every adjacent protected range distinct and splitting long clear ranges at uint16 limits. Both use checked bounds, reject truncated/oversized/unsupported metadata and have no access to opaque media, content keys, credentials, PSSH or licenses. No authorization gate is changed.

The opt-in local protected composer uses the original JVM compatibility, alignment, POC, source timing, protection identity and slice/layout checks first. Only exact byte-identical generated clear prefixes with the same source splice offset and exact sample-map equality are adopted. Opaque tail borrowing/copy verification, final fragment inspection and Widevine/Media3 handling remain JVM and unchanged. Native candidate failure disables protected native experiments for that session, retaining explicit JVM fallback. No native-enabled default or full native-protected engine claim; diagnostic actualStitcher remains JVM. The protected path deliberately performs differential comparison, so duplicate small-header work and JNI calls are not claimed to be optimizations. Full fused native protected production dispatch/assembly, independent complete supported-profile equivalence and real authorized Widevine parity remain open. Do not disable the JVM reference or label this partial native path as a fully active Rust stitcher.

The mandatory Android GB gate now exercises an independently encoded fixture's real clear-header syntax, both splice modes with synthetic opaque labels, exact borrowed payload/sample-map results, truncated/bad metadata, sticky fallback, long clear and adjacent range boundaries, complete two-source tiled picture through the actual protected composer and byte-exact CMAF packaging. A mismatched synthetic IV identity is rejected before any native candidate call. No real protected F1 playback is tested by these fixtures. Rust metadata negative/extent tests and four-ABI symbol checks expanded; previous clear and G.0/G-A gates unchanged. Candidate compilation, CI and physical device acceptance pending. G.3/G.4/G.5/G.16 remain partial until their complete acceptance criteria are evidenced. G-C/D not started.



### G-B protected production computation and fused picture candidate (after verified Build 863)

Build 863 [exact run](https://github.com/cooldarshak/apexview/actions/runs/45563427396), commit `e4a65c52e6d32567addf9ea4fee2195ffb0ddaaa`, passed all five jobs: build 139032365532, API31 139036370457, API29 139036370492, API36 139036370493, signed publication 139037758516. Exact protected-labelled clear-prefix and range-map differentials, both splice modes and the full two-source synthetic-labelled picture/CMAF tests are green. Not a real licensed F1 device playback result.

The opt-in local protected computation path now establishes first-published-fragment exact JVM parity per representation/preference epoch before enabling direct native header/map/moof generation. Any actual input or output representation change, session retirement or preference change invalidates the proof. Exact first-fragment and fallback JVM methods remain. In direct mode existing JVM source protection/layout, parameter-set compatibility, alignment, POC, timing and IV identity gates remain authoritative. The native GBS2 batch parses each source/output VPS/SPS/PPS triplet once per picture and rewrites bounded verified clear-prefix bytes in raster tile order, one JNI call per picture. Each result must match the independently inspected splice offset and its exact source binding. JVM independently parses the emitted clear prefix, checks all non-placement syntax fields and expected address/PPS/filter changes, validates alignment and clear CABAC bytes where applicable, and borrows the original unchanged tail. No opaque payload, keys or licenses are sent to this JNI boundary. GBM1 map output is verified against every source protection range including adjacent encrypted boundaries and uint16 clear splits without allocating a duplicate JVM range list. GBP1 direct moof is independently checked for canonical box structure, sequence and data/auxiliary offsets, then the final fragment's existing independent CMAF timing, IV identity and subsample round trip; direct rejection retries the JVM writer. Native failure is sticky per session until re-enabled, and generation-bound publication diagnostics distinguish first differential, direct native computation and fallback. OFF retains the JVM path. No default change, no Widevine or authorization change.

Mandatory Android GB tests retain the earlier direct worker, fixture and negative gates, and add exact direct prefix and map results without reference generation in the tested call, batch equivalence and truncated/bad-count rejection, changed splice/map/sequence/auxiliary offsets rejection, direct CBCS moof parity at all supported IV widths and both tfdt versions, full two-source picture parity and every coded picture of the encoded tiled fixture with alternating synthetic header-only/three-clear-CABAC labels, persistent POC tracking and complete CMAF fragment equality. Tests assert native direct and batch operations actually succeeded, not merely JVM fallback. Candidate full compilation, instrumentation and physical results pending. No independent test uses actual F1 protected content.

Boundary decision: retain existing JVM authorized source validation, protected init/DRM integration and one final exact opaque-payload copy to the owned JVM fragment. Do not transfer encrypted tails into JNI only to add another full-size copy; there is no native-owned protected mdat arena/lease in this candidate. Native protected computation is real when reported after publication, not a claim of fully native zero-copy protected production. Remaining G-B acceptance: native-owned/fused protected payload assembly and cross-picture native configuration reuse if required by the architecture's zero-copy scope, comprehensive equivalence of all JVM-admitted profiles (current conservative unsupported routes fall back explicitly), and real authorized Widevine/physical parity. G-C/D performance/production acceptance unchanged. Do not mark G.3/G.4/G.5/G.16 complete merely from the new candidate.



### Build 864 failure and full-sequence fixture correction

Build 864 [exact run](https://github.com/cooldarshak/apexview/actions/runs/45573392197), commit `9b78d451a5b5adc5a14fe8d289356bca2a80523b`: full Kotlin/Java/Rust build job 139065266323 succeeded. Android API36 139070214832, API31 139070214847 and API29 139070214913 each failed at ArchitectureGBAndroidTest.kt:611, `require(clearEnd < nal.size)`, while labelling clear fixture media before full-sequence composition. Final publication 139071685616 correctly failed. All three logs confirm earlier mandatory clear worker/lease, CBCS moof, both protected splice-mode/range-map, and complete two-source picture differentials passed, including direct native computation and batching. Do not interpret this as a green build or a full sequence result.

The test incorrectly forced a three-clear-CABAC-byte synthetic range on alternating pictures regardless of the actual remaining opaque bytes. The correction uses the existing bounded clear layout/access-unit inspector to obtain each real encoded slice's header end and third clear CABAC byte. It chooses the three-byte mode only when that leaves a nonempty tail; otherwise it uses the already supported header-only mode. It independently reinspects the labelled sample and asserts correct exact clear/opaque boundary, both splice modes, occurrence of the short-slice regression, full picture/tile counts, every direct native batch/map/slice, and original byte/POC/fragment equality. No source slice or picture is omitted; no production code, DRM, test gate, or fallback threshold is changed. The next CI run is pending; G-B full native ownership and physical acceptance remain open.



### Build 865 verified and G-B native-owned protected publication candidate

Build 865 [exact run](https://github.com/cooldarshak/apexview/actions/runs/45577690010), commit `0898df41ece25be6f51012f81325b2bee54e1bbb`, passed build 139079577499, Android API29 139084565657, API31 139084565683, API36 139084565756 and signed publication 139086113365. The complete encoded synthetic-labelled sequence reported 50 pictures, 400 native slice rewrites, 243 header-only, 157 three-clear-byte and 43 short-slice fixture fallbacks; exact JVM/native sample, POC and fragment parity. This is automated fixture evidence, not actual authorized F1 playback.

This consolidated candidate closes the previously missing native-owned output-memory integration for the admitted local protected path. A Rust generic fragment arena is bounded to 64 MiB charged capacity, 12 live allocations, and two exact-size reusable free allocations totaling at most 8 MiB; the final release trims all remaining free capacity. Handles are monotonic IDs, not raw pointers. The existing JVM validation and native clear-prefix/range/moof computation are unchanged. After first-published-fragment exact parity, direct admitted output is written once from the existing immutable borrowed ranges to a private direct view of the Rust allocation (JVM bulk ByteBuffer puts, with independent unchanged-tail comparison). It is not a port of ciphertext processing or a bypass: no protected bytes are interpreted by native code, no keys/licenses enter native processing, and the existing authorized init, CBCS/IV/POC checks and Widevine Media3 session are retained. The same CMAF inspector accepts read-only heap/direct views without a full mdat materialization. Allocation denial retains explicit heap fallback without quality or DRM changes; candidate failure retries validated JVM construction. The Rust arena is storage ownership, not a claim that every copy or every parser is implemented in Rust.

Native history fragments have explicit size/inspection/read methods, separate retained lease tokens for detached local windows and Media3 resource generations, bounded reader pinning, cancellation cleanup, and final retirement/zeroing. Local protected archive/live opt into shared native snapshots; legacy/cast diagnostics still receive the original detached heap arrays. Media3's public DataSource read contract takes a caller-owned ByteArray, so the final requested-range copy remains necessary. No raw pointer or native ByteBuffer escapes to Media3. First differential fragment, Rust-OFF, unsupported profile, budget denial and cast paths preserve existing JVM behavior. No changes to Widevine/DRM, login, decoder ownership, cast transport or UI defaults.

GBS2 now caches one immutable parsed configuration by exact ordered clear VPS/SPS/PPS byte identity (not a hash or protection identity). Every hit checks the complete key, a malformed replacement cannot reuse old analysis, and session retirement trims the cache. Host independently encoded single/tiled/mixed-resolution fixture runs add exact cached/uncached batch parity, invalid key rejection, invalidation and 2/4/6/8 configuration-slot coverage (repeated clear source triplets for 4/6/8, not independently encoded 4/6/8-feed playback). Existing independent FFmpeg decoded-pixel and CMAF comparisons are retained. Mandatory Android GB gate adds native allocation on all IV/tfdt cases, exact borrowed whole-picture and full 50-picture native-owned final output, native history sharing through the actual local DASH/Media3 DataSource, reader survival across publication/release, invalid/stale owner, admission backpressure and rejected writer cleanup. Four-ABI APK native symbol gate adds the new arena entry points. No existing tests or gates removed.

Candidate full build and all Android gates pending. The previously identified native-owned local protected output and cross-picture parsed-configuration implementation are staged; G-B sign-off remains pending complete supported-profile audit and actual authorized Widevine device parity. Native copy of borrowed bytes uses Java's existing bulk ByteBuffer API deliberately, not an extra full ciphertext JNI transfer. Protected init construction/authorization stays on the proven JVM path. Mixed/more-feed real protected profile equivalence, live discontinuities, casts and phone/TV performance remain physical/corpus validation, not proven by this fixture. No G-C/D work started.


### User-confirmed scope clarification and strengthened coverage (25 November 2026)

The objective remains smooth, stable playback on resource-constrained Android TV (especially TCL 55C655), with the same architecture on Android phones. Retain original Architecture G. Do not rewrite Pitwall entirely in Rust or change technologies solely to increase Rust code share. Keep Media3/MediaCodec, existing Widevine authorization and platform integration, OkHttp/authentication, UI/Compose, rendering, and casting services where they already work. Move additional expensive processing to Rust only if profiling identifies a material bottleneck and a tested native implementation improves it. No new proprietary SDK, cloud service, DRM relaxation, or additional decoder. Preserve user layout, manual quality, audio, captions, tower, map, remote controls, and all existing modes.

| Coverage dimension | Mandatory selection and acceptance requirement |
| --- | --- |
| Single-feed and direct passthrough, single/multiple-decoder direct paths | No unnecessary stitching or native allocation. Report stitching NOT_APPLICABLE where genuinely unused; independently profile shared processing and frame scheduling. |
| Clear multifeed composition | Rust for every compatible supported stitching entry point, finite replay and rolling/live where present; compare full frames/streams and timing, including mixed quality, supported NAL profiles, layouts, and actual feed counts. |
| Authorized Widevine multifeed composition | Same native stitching objective, retain existing compatibility/security checks, DRM and hardware playback. Distinguish native computation, JVM validation, storage and actual published result. Do not move trust boundaries or bypass DRM for language coverage. |
| Google Cast, Pitwall Cast, every existing supported cast/receiver mode | Where the existing path stitches HEVC, integrate the common Rust stitcher; where it does not, report NOT_APPLICABLE and optimize actual encoding/GPU/transport bottlenecks. Baked/non-baked, protected/clear, tower/map on/off, audio/CC must preserve feature parity. |
| Device matrix | Phone and TV, single/multi feed, single/multi decoder, all supported qualities and layouts, DRM/renderer settings, live/replay, speeds, seek, switching, rotation, cast handoff and endurance. Native OFF and ON tested with otherwise identical settings. |
| Fallback | ON requests native on applicable paths; unsupported/admission/operational failure is explicit, reason-coded, and cannot be counted as successful Rust playback. Safe existing JVM fallback only where authorized and supported, with no quality/decoder/DRM changes or silent preference reset. Direct passthrough is not a fallback. |

G-B closes the shared clear/protected stitching core, bounded native ownership and correctness regression coverage; integrate eligible local producers and inventory every remaining stitching owner. G-C owns remaining producer/receiver integration (including cast stitching paths), network/publication copies, adaptive memory/deadline/concurrency, and profiling-driven optimizations. G-D requires the complete mode matrix with real on-device A/B evidence, correct actual-engine attribution and sustained race-length results. A mode that still needs stitching but uses JVM because native integration is absent remains an open coverage requirement, not NOT_APPLICABLE. No phase completion inferred merely from green CI or use of Rust for one component. Keep all twenty G workstreams and eight refinements; no weakening of original sixteen-workstream release matrix. Do not start G-C/G-D implementation without approval.

### G-B consolidated supported-profile coding completion candidate (after Build 871 host verification)

The user explicitly requested completing the remaining G-B coding, not waiting for physical verification. Build 871 [run 45604465077](https://github.com/cooldarshak/apexview/actions/runs/45604465077) host build/test job 139171592335 passed; emulator jobs were still running during staging. Build 870 is the last fully green signed baseline at this checkpoint.

Completed the remaining identifiable semantic and protected-output setup differences in the source audit:

- Parse and compare SPS reference-picture sets by fully expanded ordered deltas and use flags, not the raw prediction-vs-explicit encoding. Normalize the inferred/explicit sublayer DPB representation and equivalent zero conformance-window signalling. Preserve other coding syntax, including fields that the JVM metadata does not model but which alter decoding. Preserve SPS sublayer levels as required by the existing JVM slice rewriter; only general level differences are ignored there. VPS comparison retains common HRD/timing constraints while removing the fully parsed per-sublayer CPB scheduling representation. VUI cross-source comparison follows the existing source compatibility rule; in-band replacement matches the existing parsed VUI metadata. Validate complete RBSP termination before deriving semantic keys. Keep actual selected template bytes and compare all expanded coding-relevant fields. In-band alternate sets must match their source identity, not a different configuration.
- Match the existing clear container policy: optional informational source mfhd, implicit single-traf moof base and bounded leading unreferenced mdat bytes. Restore rejection of size-to-end boxes (the Kotlin composition parser explicitly rejects them). Retain explicit-base prohibition, protected-box rejection, exact coverage, track/clock checks, and all memory/extent bounds.
- Preserve per-feed EOS/CRA POC reset state across the whole native window, including fragment boundaries. Non-VCL output policy is unchanged.
- Connect the existing native parameter-set writer to the protected path using a bounded clear-metadata-only GBO1 command. Check byte equality with the existing authoritative JVM output for each protected representation/preference epoch; cache the actual native-produced triplet and use it for protected native planning. Reject malformed requests and parity disagreement before native publication. No ciphertext is read in this command and no authorization, license, crypto identity or existing platform initialization rewrite is changed.
- Add Rust semantic positive and negative fixtures (equivalent explicit/predicted RPS, inferred/explicit DPB and zero crop; changed reference-use flag and invalid RBSP termination), optional-mfhd and size-zero rejection checks, and actual JNI/JVM in-band alternate SPS and protected output-parameter differential tests. Source wiring gates include the protected generation command and epoch-owned setup.

Coding checkpoint: all concrete compatible gaps identified by the current G-B source audit have an implementation and regression coverage in this candidate. Coding completion is independent of pending exact CI and physical acceptance; do not infer universal F1 stream coverage or measured performance from coding. Retain reason-coded fallback only for genuinely incompatible/invalid input, resource/unsupported-device admission or unexpected failure; if verification exposes a compatible gap or implementation defect, it remains G-B and must be fixed. No arbitrary acceptance of mismatched coding semantics, no changes to Widevine or the Kotlin/Media3 platform boundary. G-C Cast producer integration and performance tuning and G-D device matrix remain gated.

### Build 870 verified and consolidated G-B native profile and container coverage candidate

[Build 870](https://github.com/cooldarshak/apexview/actions/runs/45600577891) verified all five mandatory jobs green, exact source `3bf8d2a97035fadc8e33a50878ecad689c68e060`. Build 869's dependent 2/4-fragment native output, independent decoder, 4/6/8 slot and protected-labelled differential cases are now part of the fully green baseline. The user again authorized the remaining G-B coding in one consolidated batch.

Audit and candidate implementation (coding, not yet CI/device acceptance):

- Native source parameter compatibility now compares normalized coding syntax independently of fully parsed SPS presentation VUI and general/sub-layer level signalling, which the reference permits to differ. Actual output is the original first selected cell's rewritten parameter template, not the comparison key. Preserve other coding bits and the PPS syntax gate; explicitly reject dependent segments, weighted prediction and reference-list modification as in the existing supported source-cell profile. Compare all four conformance-window coordinates, not just resulting display width/height. In-band sets with distinct encoded headers/escaping may pass only when their complete decoded RBSP matches; genuinely changed syntax remains rejected.
- Extend native clear composition to selected source-tile subsets; retain increasing source CTB starts, unique independent slices and exact destination tile geometry. A missing selected slice fails. GBC3 enables the existing JVM loop-filter policy when the expected initialization requests it, but independently proves every output seam is from adjacent cells of the same source and every selected slice has a suitable filter field. Validate the aggregate filter decision across the whole bounded window. GBC1/2 wire compatibility retained; cross-feed filter requests rejected.
- Native picture-identity gate now includes short-term RPS selection, active reference counts, B-picture prediction flags, CABAC-init and merge-candidate fields in addition to the previous full POC, expanded reference set and timing checks. The same bounded syntax parser serves the protected clear-prefix path; existing authorized JVM picture/DRM validation remains authoritative there.
- Native CMAF parser accepts the JVM-supported single-traf implicit moof base, a bounded leading unreferenced mdat extent, and a size-zero box extending to the enclosing extent. Retain explicit-base prohibition, single-trun requirement, exact final sample end, protected-box rejection, extent, timing and memory limits. No opaque data is parsed by the clear worker.
- Protected GBS2 picture dispatch now splits only when input, conservative result bound or slice-count limit requires multiple native commands. Validate the complete ordered batch up front; keep whole-picture result ownership atomic; wipe all earlier prefixes if a later chunk rejects/cancels; matchedBatches remains a picture count. Normal small pictures continue with one bounded command. Native packet limit and 16 KiB per-prefix bound are not raised; no content keys, opaque media or DRM data are newly exposed.
- Added mandatory differential regressions: independently encoded libx265 SPS differing only in colour primaries; source general-level variation against existing exact JVM stream; single-traf implicit base, padded and final-size-zero mdat plus negative extents; selected source-tile subset against actual JVM composer; reject a filter-enabled request at real cross-feed seams; force multi-command protected picture assembly across the existing 50-picture/400-slice fixture while retaining the normal one-command case; source wiring checks. New test outcomes pending CI. Existing independent FFmpeg decode, seek/failure, protected opaque-byte, Android API29/31/36 and final publication tests retained.

Current supported-profile boundary: exact compatible source coding syntax is required. The native comparison is deliberately more conservative for differences in coding/HRD syntax that the JVM metadata model does not retain; a matching partial metadata object is not sufficient evidence of safe interchangeable coded cells. Keep explicit fallback for genuinely unproved alternate parameter encodings, out-of-bound or incompatible input, not a native success. No claim that arbitrary HEVC or every device-specific authorized protected stream is covered. Actual authorized corpus/device parity, memory and performance acceptance remain open; any additional safely admitted JVM-only profile discovered in that evidence remains G-B work rather than being silently reassigned or marked unsupported. This batch covers the identified concrete profile/container/large-batch gaps, not a blanket 100% claim. G-C Cast integration, further copy/scheduling optimization and G-D acceptance not started. No DRM or license policy change, no manual quality reduction.

### Build 869 failure: existing renderer evidence ownership and interval-counter race

[Build 869](https://github.com/cooldarshak/apexview/actions/runs/45598039229), exact source `32795925b1e8291bfaf5d718683cb07480287248`: compilation and independent decode job 139149219676 passed; API36 139154804647 and API31 139154804683 passed. API29 job [139154804677](https://github.com/cooldarshak/apexview/actions/runs/45598039229/job/139154804677) logged PASS for all G-B cases, including dependent clear HLS 2/4-fragment windows, native output and OFF parity, protected-labelled 50-picture/400-slice differential, 4/6/8 clear slots and archive/seek fencing. It subsequently failed the existing SurfaceDecoderSceneInstrumentationTest at line 189: "Codec PTS listener must execute on real video renderer". Final publication job 139156693205 correctly blocked the APK.

Source review identifies two test defects: layout-only rebind omitted its correlation argument (the actual view defaults it to null and detaches the old owner), and the final assertion sampled a destructive interval counter that the GL renderer drains at its first logging window and every five seconds thereafter. A zero counter is therefore not sufficient evidence that the real listener never ran. The candidate keeps the real Media3 callback and PixelCopy/decoder/surface tests; preserves correlation through layout updates; uses separate bounded real-callback latches for initial and recreated surface ownership, and asserts active correlation after both and inactive correlation upon retirement. No fake callbacks, test suppression, retries, production playback/DRM modifications or release-gate changes. This corrects the identified test race/ownership defects; exact CI must confirm the diagnosis. Build 868 remains the last fully verified binary. G-B residual profile scope remains open as documented; no G-C/D work.

### Build 868 verified and consolidated G-B dependent-window/profile candidate

[Build 868](https://github.com/cooldarshak/apexview/actions/runs/45592893405) completed all five jobs successfully: build 139131394462, API31 139137122592, API36 139137122999, API29 139137123072, signed publication 139138749358. Exact source `badf7d22a2d21b2bf61b027dc5598f343258d13b`. This closes the observed API29 rolling frame-rate regression. Build 867's protected storage, 4/6/8 logical slots, rolling and differential gates are retained in 868. No physical or real F1 performance proof.

The user authorized all remaining G-B coding in one consolidated batch. This candidate implements the following rather than starting G-C:

- Preserve byte-for-byte GBC1 single-fragment protocol. GBC2 adds an explicitly bounded 2–4 fragment window per feed starting at random access, with same-window native picture-order tracking, exact cross-fragment decode continuity, checked sequence arithmetic, total 8 MiB input/output admission, and per-fragment final lease reads. The worker does not retain inter-job picture state; canceled/failed jobs never publish partial output. Rolling clear HLS uses retained anchor history, including a multi-fragment incoming group. Unanchored/oversized windows explicitly fall back; finite archive seeking still requires independent segments (existing JVM contract, not weakened).
- Preserve exact JVM reference comparison on first window/profile and on changed source init bytes, geometry, frame rate, output track, count or generation. Check independently inspected output timing/flags for every fragment and wipe failed output and retired proof snapshots. Rust cross-feed timing uses exact rational i128 products for different timescales, compares the JVM dependency flag mask and rejects reserved bits. Preserve the first source clock and exact manual quality.
- Support a legal short first-source NAL prefix in native clear output and correct the JVM clear init writer to advertise the same NAL width as the produced media. Preserve existing four-byte default and existing secondary-width/in-band negative tests. Replace both remaining finite archive BigInteger exact-conversion calls: shared API29-safe checked frame-rate reduction and signed microseconds-to-ticks conversion with explicit Long-range rejection. Exact source preflight initially detected the second call; after correcting it, both targeted production wiring regressions passed without weakening assertions. The coordinated change contract passed on the candidate path set.
- Mandatory Android fixture regressions split existing independently encoded 50-picture samples at non-sync boundaries and require actual rolling Rust publication for 1/2/4-fragment windows, exact JVM output, contiguous timing, original independent and OFF parity, and short first-source prefix. Host fixture adds exact reference repackaging, generation/late malformed rejection, mixed-clock parity and an additional independently FFmpeg-decoded 50-picture history stream. These are fixture tests, not real authorized F1 playback.

Route/profile audit (candidate, no physical claims):

| Route or profile | Disposition |
| --- | --- |
| Clear finite multifeed local | Existing GBC1 bridge, corrected API29 timing; independent seek points retained |
| Clear rolling multifeed local | GBC1 plus GBC2 anchored windows; safe explicit fallback beyond existing bounded-history/admission policy |
| Protected local live/replay | Existing authorized JVM validation and native direct clear-header/metadata/output storage path, 868 automated baseline; no changes to authorization or security boundaries |
| Direct single-feed or multiple decoder | No artificial stitcher; NOT_APPLICABLE only where genuinely direct |
| Applicable Cast stitching | Still open G-C integration, not marked native or NOT_APPLICABLE |
| First/secondary NAL prefix widths; exact rational source timing | Native coverage expanded; candidate gates pending |
| Custom scaling lists, PCM, WPP, dependent slice segments, nonzero layers and unsupported extensions | Existing conservative reference/rewriter safety restrictions retained; never relax merely to increase Rust coverage |
| Semantically equal but differently encoded in-band parameter sets; source configurations accepted by JVM compatibility but not identical after native normalization; JVM-admitted container variants outside stricter native extent/base policy | Residual native equivalence coding/corpus gaps, explicit fallback; not counted as completed or universally unsupported |
| Protected profiles outside proved native syntax/metadata range | Retain authorization and opaque-byte invariant; residual supported-profile audit and real device acceptance remain open |

Candidate full compilation, host/ASan, API29/31/36 and signed publication not yet established. Remaining G-B work is not transferred to G-C: close residual supported-profile equivalence with safe implementation and differential fixtures, inspect actual device-admitted corpus and verify real authorized playback. G-C Cast and optimization, G-D physical performance matrix remain gated. No arbitrary Rust percentage, feature removal, silent quality reduction, or DRM bypass.

### Build 867 exact failure and Android compatibility correction candidate

[Build 867](https://github.com/cooldarshak/apexview/actions/runs/45588253375), source `d2731aa8bc7acb11dc6aecd2de2c7eec653c3b87`: build job 139115331718 passed all compilation, host, independent decoding, signing and source gates. API31 job 139122761439 and API36 job 139122761437 passed full mandatory instrumentation, including Rust-owned CMAF storage/range reads/retired reader, 50-picture 400-slice protected-labelled differential, 4/6/8 logical clear slots, actual rolling HLS Rust publication and OFF parity. API29 job 139122761445 failed in `ClearCmafArchiveComposition.compose` at line 40 with `NoSuchMethodError: BigInteger.longValueExact()`; final publication job 139124370549 correctly failed. Do not weaken or rerun unchanged tests to bypass this deterministic defect.

The correction replaces the unsupported exact BigInteger conversion with a shared positive-Long Euclidean reduction and checked Math.toIntExact conversions. It also removes the analogous BigInteger.intValueExact dependency from protected frame-rate derivation, preserving the original protected frame-rate bounds and avoiding unchecked clear-side integer narrowing. Mandatory GB instrumentation now tests the exact helper on every API including invalid/overflow cases; a source regression checks both production paths. Existing rolling parity, ownership, security and publication gates remain unchanged. No DRM policy, feed quality, default engine, decoder mode or Cast change. Candidate CI pending; 865 remains the last fully verified green binary.

G-B coding is not closed: supported-profile inventory and history-dependent/multi-fragment clear native coverage remain open; additional independent fixtures and actual authorized physical playback are outstanding. Cast stitching integration remains explicitly G-C; no G-C/D implementation authorized. No physical performance or real F1 protected parity claimed from synthetic evidence.

### Build 866 failure and consolidated G-B continuation

Build 865 remains verified GREEN. Build 866 [exact CI](https://github.com/cooldarshak/apexview/actions/runs/45584014962), commit `3338f12a3d1197713ff78b23f38304c05c5f1875`, build job 139100878127 failed at step 14, Validate physical FrameTimeline comparison tool. The failing test was `test_post834_protected_payload_written_directly_to_final_fragment`, a source-wiring assertion expecting the exact original direct heap-copy expression. No Android instrumentation or signed publication ran for 866; do not infer native storage runtime or full-sequence success. Restore the actual direct-copy expression without changing its semantics and keep the original assertion; add assertions for direct native destination assembly and independent validation.

This consolidated continuation also connects the existing clear native worker to independently decodable rolling HLS fragments on the local playback path. First initialization uses the existing reference. Subsequent eligible fragments use the same clear bridge, validated input reports, actual memory-budget admission, first exact output comparison, independently checked timing and generation-fenced publication. Native OFF, history-dependent fragments, missing device context, and worker/admission rejection remain explicit JVM paths; history-dependent boundaries invalidate the native proof. Rolling preferences take effect at the next fragment boundary; reset retires the engine. Added actual rolling clear fixture tests for contiguous timestamps, native output, and OFF parity, plus full 4/6/8 logical clear source-slot composition comparisons using the two independently encoded source fixtures. The larger logical feed count fixtures reuse the source media, not independently encoded F1 feeds. Retain previous independent decode, malformed input, CBCS and ownership gates. Targeted original failed regression and new rolling source-wiring test both passed on staged exact source. Full Android/Rust compilation, all API gates and signed publication pending candidate CI; actual F1/device proof remains pending.

G-B coverage and acceptance remain open until the entire candidate passes. Known current bounds: history-dependent clear profiles and other unsupported native inputs still use reason-coded fallback; cast stitching integration is still not native-enabled (G-C coverage requirement); no actual authorized protected playback or phone/TV performance proof. Build 866's fixed maximum native fragment storage is a ceiling, not an approved physical device allocation target: integrate and measure combined native/JVM/codec budget in G-C. Neither the previous hybrid implementation nor the strengthened coverage requirement justifies rewriting the whole app. No new protected-media algorithm, security rule, credential flow, or casting transport is introduced by this batch.


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
| 1 | P0 | P2.1/P2.4 | Diagnose underlying TLS alert/certificate/key negotiation; test Android 12↔16 TLS 1.2/1.3, pairing/reconnect and pinning; no insecure fallback | Corrected missing DIGEST_NONE for Conscrypt ECDSA signing; incompatible existing identity migrates once, local signing self-test, reconnect and wrong-peer tests; mandatory API 29/31/36 loopback TLS 1.2/1.3. Actual cross-device pairing remains physical acceptance. |
| 2 | P0 | P2.1/P0.1 | Baked HLS startup buffer, segment readiness, bounded retention and receiver buffering diagnostics; sustained playback/seek tests | Implementation: independent mux worker, bounded sample/byte queue, 12 s startup holdback matching three HLS target durations, mux and buffer telemetry. Physical sustained Cast/seek acceptance open. |
| 3 | P0 | P0.3/P2.1 | Independent GPU overlay acquisition, adaptive encoder pacing/backpressure, measured encode and layer latency; keep actual map/tower/CC geometry | Implementation: Build 840/841 GPU overlay instrumentation passed; move TS mux/publication off GL capture callback; adaptive pacing and layer/encode/mux timings retained. Physical throughput open. |
| 4 | P1 | P2.1/P2.2 | Suppress unnecessary local presentation/audio during baked Cast while retaining required source clock, decoding and composition; lifecycle/recovery tests | Implementation: receiver-confirmed local suppression retained; publisher and archive audio jobs retire without racing active source IO. Physical disconnect/seek/audio recovery open. |
| 5 | P0 | P0.2/P1.1/P2.5 | Profile TV HEVC parsing/rewrite, GC and scheduling; sustain producer throughput ahead of real segment deadlines | Implementation: Build 841 unit and independent decode gates passed, identity-bound CMAF inspection reuse, promotion of required warm group and protected composition deadline telemetry. Physical TCL throughput open. |
| 6 | P0 | P0.2/P1.1 | Architecture G feasibility: benchmark native C++/Rust bounded arena/JNI against existing Kotlin range writer before migration; preserve byte-exact clear/protected invariants, ownership and licensed Widevine | DEFERRED by user; no native rewrite, JNI or Architecture G benchmark in this batch |
| 7 | P0 | P0.3 | BufferQueue surface/codec ownership, exact release correlation, latch and physical presentation; no arbitrary pool tuning or warning suppression | Implementation: Build 841 TextureView and SurfaceView instrumented checks passed; atlas resize, ownership and bounded probe corrected. Physical BufferQueue attribution open. |
| 8 | P1 | P1.2 | Measured 50/75fps pacing, actual display mode, A/V synchronization and thermal health; verify 50/100 Hz availability and mode behavior | Implementation: Build 841 cadence tests passed; actual cadence diagnostics, thermal gate, fractional refresh and transition reset retained. Physical presentation/A-V open. |
| 9 | P1 | P0.4/P2.5/P2.8 | Locked TV map auto-hide and reveal on chrome/edit/focus navigation without trapping D-pad focus | Candidate: locked TV map hides after inactivity, chrome/focus reveals; D-pad device acceptance open |
| 10 | P1 | P2.5/P2.8 | Main + strip landscape main-left 2/3, right vertical strip 1/3; portrait bottom strip; consistent local/saved/Cast geometry | Candidate: landscape left two-thirds and right vertical strip; portrait bottom; orientation preserved through reserved geometry; CI and device parity open |
| 11 | P1 | P0.4 | Separate temporary stopped, retired and resumed race status; replay/live/Cast tests for sparse deltas and rejoining | Candidate: separate STOP from OUT, resume on positive lap/pit exit, native Cast optional stopped-driver table field; sparse/replay regression added; live/receiver tests open |
| 12 | P1 | P0.4/P2.2 | Tower requests and publishes gear telemetry independently, handles partial/failed/empty archive loads and stale request ownership; validate actual F1 data | Implementation: allow CarData requests on partial archive previews with resolved path, retain coordinator ownership across replacements, skip malformed entries, independent gear timestamps and bounded failure retry. Actual F1 gear coverage open. |
| 13 | P1 | P1.3/P2.5 | Device-aware safe quality/thermal defaults and renderer/DRM recommendations, verified on phone and TV | Implementation: Build 841 device-default tests passed; unset TV 540p low-RAM or 720p, phone Auto, saved caps and DRM/renderer settings preserved. Physical thermal/rendition acceptance open. |
| 14 | P0 | P2.6 | Coordinated source + regression tests for every confirmed defect, exact SHA CI and matched hardware measurements | Build 841 failed only the added real Android TLS gate; missing key authorization corrected and upgrade regression added. API 29/31/36 TLS-only/full suite on same APK, queue, HLS startup, malformed gear tests; exact new CI and physical matrix open. |

Engineering rules: one coordinated implementation pass, not a sequence of tiny speculative commits; preserve phone/TV, single/multiple decoder, clear and authorized Widevine, Google Cast and Pitwall Cast, existing login and entitlements. No proprietary SDK, paid OpenF1, session-specific patches or insecure TLS/DRM shortcuts. Keep clear capture and protected policy boundaries. Do not restart the app or publish a production release from this documentation update.

### Historical consolidated follow-up after Build 839 (Architecture G deferred)

The user selected items 1–5 and 7–14 as one coordinated batch. Item 6 remains deferred. The candidate contains production changes and regression source changes; no exact-source Android compilation or device acceptance has been established. Preserve all original tests, production wiring, entitlement, certificate pinning and protected surfaces. The working environment passed 83 Python tests and both JavaScript Cast suites on the first working-tree version, then failed its health check during local Kotlin compilation. The candidate was reconstructed against the same GitHub baseline; earlier results are not exact-commit certification. Run the full existing workflow on the recovered commit. Do not mark any reopened workstream 100% solely from this source update. Main remains unchanged.

Physical evidence needed: Android 12↔16 paired TLS logs; sustained 10-minute baked Cast with receiver buffering and GPU timings; TCL clear/protected one- and three-feed runs with producer slack; 60/120 Hz phone and actual TV mode at 1×/1.5× with presentation/A-V evidence; D-pad lock, saved layout, actual F1 gear and resumed driver, caption and seek regression. The full 16-workstream release ledger remains 1/16 closed; no release approval.

### Documentation navigation

- [50 fps device profiling](PITWALL_50FPS_DEVICE_PROFILE.md) and [phone-only aShell capture](perfetto/ASHELL.md): reproducible measurements.
- [Clear/Widevine parity and named layouts](CLEAR_WIDEVINE_PARITY_AND_NAMED_LAYOUTS.md), [single-decoder layouts and Cast](SINGLE_DECODER_LAYOUTS_AND_CAST.md), [native TV receiver](LOCAL_TV_RECEIVER.md): supporting technical status/setup notes; this master ledger takes precedence.
- Historical diagnosis, CMAF inspection and reference notes are evidence, not competing execution plans. Obsolete phase plans and Build 819/826 handovers are removed from the working tree; their complete contents remain in the [pre-cleanup Git revision](https://github.com/cooldarshak/apexview/tree/73b51df9ed9996772cf7ed499df7e01649eb9ea0/docs).

---

## Historical engineering record (preserved in full)

Everything below documents earlier implementation checkpoints, decisions, original detailed workstream requirements, research, regressions and acceptance criteria. Historical pending instructions and percentages are NOT the current execution queue. Retain technical invariants and still-applicable acceptance criteria; use the current ledger and 14-item batch above for execution status.

**Owner branch:** `feature/pitwall-performance-audit-plan`
**Baseline:** Build 699, commit `ea2cfbb345f02779f610a9d8f467426157f29ecc`, green CI.
**Historical status:** The consolidated source update was implemented. Physical and release acceptance is open. **Verified starting checkpoint: Build 837**, commit `6d20bccdcfba121f7b0998fdcb8d9dcc492ff539`. Its mandatory source, JVM, Cast, independent HEVC/CMAF, APK and Android instrumentation checks passed. This document is the active project plan. [Build 837](https://github.com/cooldarshak/apexview/actions/runs/45374278181)

## Build 838 Android rendering correction

[Build 838](https://github.com/cooldarshak/apexview/actions/runs/45388176878) passed the JVM, Cast protocol, independent HEVC/CMAF and signed package stages. Its mandatory Android pixel test verified the video layer but found the expected translucent overlay missing at the sampled position. The correction records the live viewer overlay once into an Android RenderNode, uses that display list in the viewer, and explicitly draws and posts it on the encoder's transparent hardware Canvas. Each capture still waits for both input frames. The first frame has a separate bounded acquisition timeout. The Android test retains its placement, opacity and movement assertions and reports the observed bounds if a pixel differs. The exact-source Android results belong to the next branch workflow.

## Consolidated update after Build 837

- **Playback and timing.** The playback coordinator owns feed-offer cancellation, composition replacement, delayed retry and the latest exact seek/Cast restart. Detached capture workers finish before their backend is reset. Each timing open and speed lookup has its own identity. Stale operations cannot publish over a newer request, including when the same race is reopened. Backgrounding during a local feed offer cancels the offer before it can start a player out of view.
- **Native Cast publication and captions.** The source producer and relay take leases on the same immutable audio/text bytes. Publication is atomic across the MPD, video and sidecars. Failed publication rolls back acquired leases. Retired buffers remain readable while pinned and are cleared on final release. The checked source variant determines the audio and subtitle programme. Rolling HLS WebVTT uses declared timeline boundaries, discontinuity and media sequence, timestamp maps and 33-bit wrap. The receiver selects the matching programme's clear fragmented WebVTT or declared DASH text without another decoder. Finite archive playback selects verified Media3 text children and compatible audio in the composed player.
- **Clear baked Cast.** The clear scene supplies one requested GPU picture from the existing atlas renderer. Compose records the same live overlays it draws locally, including the map, tower, driver labels and captions. The encoder composites both layers with their original geometry and alpha on its own EGL looper. A bounded clear-window capture path remains available on output acquisition failure and records the reason. Media3 source time drives the AVC timestamps and unchanged AAC commentary at both 1× and 1.5×. The paired receiver starts at the selected speed. Clear screenshots and replay capture remain available. Widevine retains its existing authorized secure output and native receiver paths.
- **TV and release.** Programme switches reject stale track acknowledgements and apply the desired audio when a load starts rendering. Caption language, text size and background opacity use the phone and TV's existing accessible controls. The release audit checked the Build 837 owned-publisher asset, checksum, signer/version, installer, paired TLS, backup and redaction paths. Their remaining gates require real installed APKs, authorized hardware sessions or release approval.
- **Automation.** The coordinated candidate includes JVM owner/clock and lease regressions, a Media3 extraction test for packaged WebVTT, and a mandatory Android EGL pixel test for the actual Compose layer and moving overlay. The branch workflow is the source of truth for exact-commit CI.

### Historical workstream ledger at Build 838 candidate

Coding percentages refer to the identified source requirements. The new commit's exact-source CI is tracked in GitHub Actions. Green CI and the physical/release evidence below are recorded independently.

| ID | Workstream | Coding | Pending coding | Physical acceptance remaining | Release gate |
| --- | --- | ---: | ---: | --- | --- |
| P0.1 | Segment priority and exact seek | 100% | 0% | S22/TCL cold, warm and lap seek at 1×/1.5×. Rolling and Cast transitions | Open |
| P0.2 | HEVC memory and delivery | 100% | 0% | Sustained heap, native allocations, GC, reader retention and supported 7/8-feed sessions | Open |
| P0.3 | Video rendering | 100% | 0% | Independent SurfaceFlinger presentation and BufferQueue attribution on S22/TCL and Cast | Open |
| P0.4 | Map and timing tower | 100% | 0% | Matched 10-minute OFF/MAP/TOWER/BOTH runs, UI deadline, timing freshness and TCL/Cast parity | Open |
| P1.1 | HEVC atlas | 100% | 0% | Real mixed renditions, coded-picture resolution, decoder workload and thermals | Open |
| P1.2 | Refresh matching | 100% | 0% | Measured 50/75fps pacing, supported refresh transitions and audio sync | Open |
| P1.3 | Quality and thermal | 100% | 0% | Real rendition caps, 10-minute power/thermal, GC and buffering | Open |
| P2.1 | Pitwall Cast | 100% | 0% | S22 → TCL overlay and caption parity, direct EGL on device, 1×/1.5× A/V, seek and reconnect | Open |
| P2.2 | Coordinator architecture | 100% | 0% | Phone/TV/Cast lifecycle, backgrounding, rotation and repeated player replacement | Open |
| P2.3 | Release and updates | 100% | 0% | Signed in-place upgrade, installer confirmation and rollback on both devices | Open |
| P2.4 | Authorization and security | 100% | 0% | Real mutual-TLS pairing/reconnect, backup and log review, authorized Widevine L1/L3 | Open |
| P2.5 | Android TV | 100% | 0% | TCL single and multifeed playback, tower/map, D-pad, sliders and focus | Open |
| P2.6 | Testing and benchmarks | 100% | 0% | Run and archive the S22/TCL/Pitwall Cast evidence matrix | Open |
| P2.7 | Core architecture | 100% | 0% | Previously accepted scope. Keep single-decoder behavior | Closed |
| P2.8 | Material 3 Expressive | 100% | 0% | TCL, TalkBack, 200% text and reduced-motion acceptance | Open |
| P2.9 | Closed captions | 100% | 0% | Real F1 caption availability, alignment, languages, appearance and phone/TV/Cast continuity | Open |

**Strict release acceptance: 1/16 closed.** Device measurements will determine physical frame pacing, BufferQueue attribution, A/V alignment, thermal limits and vendor-specific Widevine behavior. Real F1 caption availability remains part of device acceptance.



## Historical Build 836 CI correction, verified in Build 837

Build 836 stopped at the Python production-wiring suite (80 of 81 tests passed). The active composition diagnostic test counted two occurrences of the old f1DashCapture variable, renamed to playbackWork.capture in the preceding coordinator extraction. Both production identity fences are present. Update the exact two-occurrence assertion to the new owner field; do not skip or weaken the test. Kotlin compilation, TLS loopback and APK assembly did not run in 836. Exact-source CI remains pending; all earlier physical and engineering gates remain unchanged.

## Post-835 failure correction and lifecycle continuation

Build 835 compiled but failed in the JVM tests: 841/842 passed. Conscrypt on JDK 21 reflectively accesses java.net.InetAddress.holder() during the real mutual-TLS loopback. APK assembly and emulator tests were skipped. Open java.net only in the forked unit-test JVM; Android TLS, pairing, pinning and Widevine remain unchanged. Real mutual TLS and wrong-peer rejection remain mandatory. Failed accepted TLS sockets are closed.

Extract playback composition/retry/seek and timing producer ownership into production coordinators. Unique session identity fences stale callbacks even when the same content reopens. Failed speed requests can retry, old requests cannot publish over newer sessions. Clear baked Cast waits for outstanding PixelCopy before encoder/bitmap teardown. Audio initialization ownership transfers after validation; validate alternate audio shape, clear fragment encryption metadata and overflow-safe timelines.

Screenshot acceptance: preserve supported clear screenshots/replay capture, do not add window-wide screenshot bans, and retain license-required protected surfaces. Direct EGL capture with full Compose overlay parity, rolling HLS captions/alternate programme captions, broader remaining coordinator extraction and publication-copy optimisation are not complete. Exact-source CI and physical acceptance remain open; no inflated completion percentages.

## Post-834 consolidated source batch: protected streaming, paired Cast and lifecycle

One branch update from exact green Build 834; no intermediate builds. Hard rule: implement all known coding work before declaring 100%; separate source, exact-source CI and physical acceptance.

- P0.2: protected access-unit inspection parses bounded clear prefixes; validated borrowed ciphertext ranges and owned rewritten prefixes are written into the final CMAF fragment. Existing CBCS subsample boundaries, IVs, flags, timing, alignment and full encrypted-tail byte verification are retained. The writer zeroes failed output and closes plans; protected DASH publication has bounded current and pinned ownership.
- P2.1/P2.4: Pitwall-to-Pitwall local casting uses mutual TLS with non-exportable AndroidKeyStore EC identities and matching codes independently confirmed on phone and TV. Every local media server uses exact paired-certificate trust and HTTPS. No silent insecure Pitwall fallback. Both devices need this build. Google Cast clear-media compatibility remains a separate route; no protected Google Cast relay.
- P2.1: compatible native CMAF audio representations are frozen at publication, independently verified for protection, codec and alignment. Receiver changes use actual Media3 audio-group selection, load-scoped acknowledgements, and authorized reprepare for incompatible alternatives.
- P2.2: CastResourceCoordinator owns media routes, retired producers, connection and jobs. Replacement capacity is bounded; retirement is released on receiver confirmation; failure/stop cancels preparation and closes owners. Broader non-Cast AppState decomposition is not claimed complete.
- P2.9: declared clear fragmented DASH WebVTT/TTML representations can accompany the protected video manifest without another decoder, provided exact original sample-entry, timing, crypto absence and fragment validation pass. No artificial cues or key retrieval. Clear finite HLS caption paths from Build 834 remain intact. Rolling HLS text and non-primary programme caption source changes still require engineering and real manifest fixtures; unsupported text is reported, not represented as verified.
- Regression additions: protected borrowed-range exact-byte output, paired-code differentiation, loopback mutual TLS and rejection of an unpaired client, native audio command scope and production wiring. Exact-source compilation and test outcomes are pending until CI reports them.

Remaining explicit engineering: complete broader playback/timing AppState ownership extraction, direct EGL capture parity (current clear baked Cast still uses PixelCopy for Compose overlays), rolling HLS/alternate programme caption coverage, and further bounded audio publication/latency audit. Release signing, real-device install and TV performance/focus/DRM/caption acceptance are separate gates. Do not mark these items 100% or claim release readiness until evidence supports it.

## Post-833 consolidated continuation: delivery, control, captions and release integrity

**Build 834 GREEN; physical acceptance remains open.** One branch update; no intermediate build. This section supersedes earlier descriptions of Cast copy ownership.

| Workstream | Production implementation | Remaining evidence / engineering |
| --- | --- | --- |
| P0.2 / P1.2 delivery | Finite Cast reads use the archive cache lease, writing requested byte ranges directly; rolling clear/protected relay uses immutable per-resource read leases with independent 128 MiB current and pinned limits and three concurrent readers. Detached protected video windows transfer ownership into publication instead of another clone. CMAF audio retains the captured fragment directly after validation. | Measure total live working set, seek latency and GC on both devices; protected slice-rewrite allocations and additional audio publication copies remain to audit. |
| P2.1 Cast | Exact range validation including overflow, HEAD and partial responses; peer-bound local relay, scoped capability paths, separate preparation/connection/load/clock epochs, rejection of stale control and caption requests. | Real sender/TV reconnect, rolling transition and audio switch; protected Cast switching still rebuilds its source, unlike verified stable finite audio choices. |
| P2.2 coordinator | CastSessionCoordinator owns session epochs and monotonic accepted receiver clock; wired into AppState, not test-only. | Broader AppState ownership extraction remains open. |
| P2.3 release | Exact official release asset identity, bounded JSON/checksum response, cancellation ownership, temporary APK cleanup and installer rollback (details below). | Signed upgrade, real Android installation and release approval. |
| P2.4 security | Strict numeric LAN peer and resource capability directory; bounded input; robust login cookie parsing, exact publisher URL and no HTTPS downgrade. | Authenticated LAN transport, end-to-end device security review. |
| P2.5 TV | Load-scoped receiver controls, focus-aware control visibility, caption language dialog, safe lifecycle release and bounded range handling. | Physical remote/focus/reconnect, TCL playback and performance. |
| P2.9 captions | Selected archive HLS variant declares actual subtitle renditions; inspect finite clear subtitle playlist before adding a native text-only filtered child to the existing composed Media3 player. Clear Cast advertises validated independent WebVTT tracks, rewrites source URLs to opaque relay names and validates every fetched segment. Baked TV preference goes to the actual phone renderer and native TV preference returns to the sender. No extra video decoder, cue logging, key retrieval or DRM alteration. | Protected composed DASH subtitle adaptation remains open; verify real F1 rendition availability, clock alignment, language switching, Google Cast selection and device rendering. |

Regression coverage: lease retirement and reader budgets, malformed/overflow ranges, stale load and clock epochs, scoped LAN capability paths, finite clear WebVTT playlist rules, exact updater asset and size checks, malformed login cookies, and real production wiring. These are source changes and test additions, not certification of 100% engineering or device acceptance. Historical percentages are not automatically increased. The original one-decoder, authorized Widevine, clear/protected, phone/TV and Pitwall Cast requirements remain.

## Release and authentication lifecycle hardening after Build 833

**Source candidate; CI and physical acceptance remain OPEN.** P2.3/P2.4: bind official APK asset filename to the exact release tag and publisher URL, reject duplicate assets and invalid metadata length, cap decoded JSON/checksum bytes before allocation, disallow HTTP redirect downgrades, require advertised and transferred APK size agreement, retain SHA-256 and signer/version checks. Serialize update checks/install ownership, cancel on Updater close, use isolated temporary APKs, clean up files and abandon uncommitted installer sessions on failure/cancellation. Preserve explicit Android installation confirmation and committed session completion across process death. Parse malformed URL-encoded login cookies without crashing or discarding valid raw JSON; preserve F1 login/entitlement and existing backup exclusions. Automated bounded-input, exact-asset and login-cookie regression tests accompany production changes. Real signed in-place upgrade, TV confirmation, process recreation, protected playback and LAN security acceptance remain OPEN. No percentages inflated before verification.

## Consolidated production-hardening implementation: Zerodha-informed continuation

**Status: Build 831 exact-source CI GREEN; physical acceptance pending.** The research is incorporated into this single master plan, not a competing plan.

- Adopt the bounded-work, ownership and measurement principles in [Blobasaur](https://zerodha.tech/blog/high-performance-distributed-blob-storage/) and [Scaling with common sense](https://zerodha.tech/blog/scaling-with-common-sense/). Existing archive single-flight, separate foreground/prefetch fetch pools, cache publication and background thread priority are already wired; retain and benchmark rather than duplicate. Next: audit bounded queue admission, cancellation, copy-minimized clear CMAF processing and allocation stack measurements; keep protected data handling and authorization unchanged.
- Apply [Native to React Native to Flutter](https://zerodha.tech/blog/from-native-to-react-native-to-flutter/) lessons to independent scene/timing updates and real device refresh-rate measurements, not a framework migration. Retain Kotlin, Compose, Media3, GLES/EGL, existing Cast and protected playback. No Blobasaur service, Redis, Flutter, React Native or Go runtime. Rust and alternative HTTP transport require isolated benchmarks before adoption.
- Frame identity: replace PTS-only exact lookup with unique exact PTS-or-planned-release matching; track source-domain-only, release-domain-only, both-domain, ambiguous and duplicate observations separately. No inferred offsets, nearest-time guessing, false scanout claims or forced codec pool size. The real Qualcomm timestamp domain remains a hardware evidence gate.
- Frame correlation ownership: both scene renderers now attach diagnostics to the actual Media3 decoder input surface and reject reset, detach, latch, swap and snapshot events from retired surfaces. Preserve existing decoder attach/detach ordering and protected EGL policy. Regression cases cover stale owners, release timestamps, ambiguous exact matches and repeated latches. Existing seek reset remains, but Media3 callback generation and real SurfaceFlinger presentation remain open.
- Clear archive pass-through now validates the existing CMAF and HEVC range metadata without materializing temporary source NAL arrays. Actual source NAL bytes avoided are logged separately from ART allocation counts. Multi-feed owned NAL arrays, clear-only encryption rejection, IRAP validation, output copy ownership and zeroing remain intact. The independently encoded fixture tests exact unchanged source bytes and independent output ownership. Cast receiver uses the same surface-owner diagnostic shutdown. Real multi-feed copy reduction is still open.
- Maintain all original workstream percentages and acceptance gates until compilation, test and physical evidence warrant an update. HEVC copy reduction, audio switching, 120Hz performance, foreground seek, DRM lifecycle, logging, power/thermal and Cast still need their own implementation/verification. No partial workstream is declared complete based on this change.

## Blobasaur-informed bounded archive delivery continuation

**Build 832 exact-source CI GREEN; hardware acceptance OPEN.** Reviewed upstream [Blobasaur README](https://github.com/iamd3vil/blobasaur/blob/main/README.md), [app_state.rs](https://github.com/iamd3vil/blobasaur/blob/main/src/app_state.rs), [shard_manager.rs](https://github.com/iamd3vil/blobasaur/blob/main/src/shard_manager.rs) and [server.rs](https://github.com/iamd3vil/blobasaur/blob/main/src/server.rs). The upstream repository is AGPL-3.0; no source is copied, linked, or bundled. Independently implemented patterns: bounded admission, separate work lanes, read-only shared ownership, explicit release, measured queue pressure.

- Media3 clear archive DataSource now reads directly from an immutable cache lease instead of cloning an entire composed segment before the Media3 read. This applies to single- and multi-feed clear archive composition on phone and TV. Eviction/replacement/clear retire pinned data without wiping an active reader; last close zeroes retired bytes. Cache and pinned-reader budgets each bounded by 96 MiB, eight reader handles maximum; worst-case distinct backing bytes 192 MiB, excluding legacy Cast-owned copies and in-progress composition. Cast continues its existing owned-copy API. The existing clear/protected rolling DASH store now retires each immutable resource independently, so a held manifest/fragment cannot pin an entire obsolete window. Unleased ciphertext is zeroed immediately; leased ciphertext remains unchanged until its last reader closes. Publication aliasing is rejected, and current/retired-reader byte counts are logged. No DRM/key/session or protected bitstream policy changes.
- Foreground and speculative lanes use bounded executor queues and separate close-aware admission covering fetch, composition and publication, one active group per lane, four foreground waiters and one speculative waiter. The original common 25-second fetch deadline includes admission wait. Session close wakes waiting producers; canceled queued work is purged; warm queue capacity is one. Admission wait, active workers and queue depth are logged. No synchronous disk persistence, compression, Redis or backend server.
- Re-selecting an already active archive audio URI (including Cast handback) is now idempotent and does not recreate the video source. Different audio URI selection still needs the stable native-audio source design; it is not claimed complete. Archive errors use centralized credential redaction.
- New regression cases cover evicted/replaced/cleared leases, independent protected-resource retirement, alias rejection, final-reader zeroing, duplicate close, bad ranges, reader budget, queue admission/close and shared producer publication. Production-wiring assertions verify actual DataSource and executor use. Existing copy API and clear-only CMAF validation remain.
- Clear multi-feed CMAF now passes bounded borrowed NAL ranges through the grid composer, POC analyzer and slice rewriter instead of cloning all source VCL bytes. The existing source access-unit inspection, parameter-set compatibility, tile boundary, POC/reference alignment, header round-trip and unchanged CABAC-tail checks remain; the original protected whole-NAL/clear-prefix API is unchanged, and borrowed non-whole ranges require full clear input. No borrowed input is wiped by the composer; its session still owns zeroization. Existing independent FFmpeg fixtures remain the byte/pixel acceptance path; added deterministic source VCL staging-byte and input-immutability assertions. The final rewritten output NALs remain owned until packaging; further streaming rewrite is open. Physical allocation, cold seek p95, thermal and exact source-to-display metrics remain open. Retain existing historical workstream percentages until the broader engineering ledger is rebaselined.

## Post-832 consolidated source follow-up: measured scheduling, stable audio, streaming HEVC and diagnostics

**Status: Build 833 exact-source CI GREEN; physical gates remain open.** This was one production/test/plan commit based on Build 832 GREEN, not a new plan. Zerodha's bounded queues, independent worker ownership, copy minimization and measurement-first design remain the reference. AndroidX [MergingMediaSource](https://developer.android.com/reference/androidx/media3/exoplayer/source/MergingMediaSource) documents child-index-prefixed track IDs; [TrackSelectionOverride](https://developer.android.com/reference/androidx/media3/common/TrackSelectionOverride) enables native track changes. No external SDK or copied copyleft code.

| Active follow-up | Source implementation in this candidate | Remaining engineering/acceptance |
| --- | --- | --- |
| P0.2 clear HEVC output staging | Owned rewritten headers + borrowed validated CABAC ranges go straight into final CMAF mdat. Existing source alignment, range/encryption checks and original round-trip header parser with full fallback retained. Legacy Annex-B and opaque protected whole-NAL APIs remain. Partial writer output zeroed on cancel/failure. Log gross rewritten-tail staging bytes avoided separately from real ART allocations. | CI golden CMAF/FFmpeg pixels, protected fixture regression, actual allocation stacks/GC/cold latency; protected-specific payload streaming requires separate evidence and safe ownership analysis. |
| P0.3/P1.2 accelerated cadence | Both GLES scene renderers report complete, owned five-second pipeline windows. Require two consecutive healthy windows before accelerated promotion; revoke on unhealthy/stale observations, seek, decoder or surface reset. Both scene hint and decoder thinning use the same eligibility. Receiver now supplies actual display ID with owner-safe detach; no forced mode, no extra decoder. | This is an observed GL-stage eligibility trial, not physical scanout or proof of GPU headroom. App-window 120Hz jank, actual PTS/display layer, BufferQueue platform causality and controlled thermal/75fps tests remain OPEN. |
| P0.1 exact seek | Coalesce finite clear archive seeks in the existing 80ms fixed window; notify speculative priority immediately at latest target. Binary search finite media timeline, overflow-safe conversion, no preview decoder or approximate final seek. | Real cold/warm/lap seek p95, foreground network cancellation, rolling protected and Cast transition timing. |
| P0.1/P1.3 thermal work | Existing PowerManager owner updates finite archive prefetch limit: cool two, moderate/unknown one, severe no new speculative group; preserve in-flight requested production and every foreground request/quality. | Measure hot-device rebuffer/power and Cast relay behavior; rolling protected essential production is not treated as speculative. |
| Audio/CC | Only distinct finite separate audio renditions with exact known common PDT/duration, no gaps or declared encryption qualify for a single stable Media3 merge. Primary retains native text, alternate children are audio-only. Use native child-index TrackSelectionOverride instead of video source reconstruction when the requested supported group exists. Retain original native handoff for unverified/muxed/unavailable sources, with visible log reason. | Test real F1 offers, A/V sync, zero video codec reinitialization for supported alternate groups, caption/source semantics, protected and Cast handoff. Unverified-source switching is not claimed complete. Additional manifest probes must be benchmarked. |
| Diagnostics | Bounded direct-producer mailbox (128 records, 256Ki characters), generation-scoped drain, explicit overflow counts, existing asynchronous logcat capture and 5MiB rotation. Fatal path best-effort synchronous flush. No routine direct producer disk lock; no vendor warning suppression. | Test real capture lifecycle, disk pressure and runtime overhead, including explicit toggle/stop flush. |
| Other open coding | Broader AppState ownership extraction, full protected and baked Cast/audio/caption parity, release signer/update/security, Android TV navigation and recovery. | Remain OPEN; no numeric completion is invented from this commit. Network transport and Rust comparisons require measured profiling, not mandatory migrations. |

Regression tests: exact ranged vs materialized rewritten NAL/fragment bytes, source immutability and encryption rejection, independent FFmpeg fixture, accelerated health expiry and overload, archive audio timeline eligibility/identity, bounded logging, binary timeline and thermal warm-ahead, plus real production-wiring checks. Preserve prior Build 832 source ownership regressions, original 16-workstream ledger as historical baseline, and 1/16 physical acceptance gates closed. Reopen source follow-up where a demonstrated defect exists. **Do not certify all engineering work as 100% or infer hardware success from green CI.**

## Production-hardening audit of Batch 1 + Batch 2: 24 November 2026, Build 830 exact-source GREEN

**Audit posture:** targeted read of the real `cooldarshak/apexview` production scene, player, adaptive cadence, archive source, protected DASH lease, capture, timing-map, runtime analyzer, release and CI paths. This does **not** certify an exhaustive external review, a Google/YouTube/Netflix endorsement, full source-to-display scanout, or a hardware sign-off. This source correction has now passed **exact-source [Build 830 GREEN](https://github.com/cooldarshak/apexview/actions/runs/45342455841)** at SHA `404e7ddf972248852811320d802117ae43cb234d`, including unit tests, full source wiring, HEVC fixture checks, APK/benchmark assembly and Android emulator rendering. Physical acceptance and all newly discovered defects remain OPEN. Earlier Build 829 at SHA `0010f4ddf56e823f406d7ecacd82ff22027b3f90` is historical.

### Source and trace evidence (do not confuse correlation with causation)

The supplied 24-Nov S22 Ultra 120Hz log `pitwall-20261124-043242-899.log.txt` contains **1,096** expanded nonblocking BufferQueue dequeue warnings, including **461** `[MediaCodec.release]` and **635** `SurfaceTexture-...` warnings, **799** detach-not-owned and **38** cancel-not-owned warnings, plus **30** `MediaDrm Object Already unbinded` reports. The decoder announces `CCodecBufferChannel ... max output delay 22` **16** times; `[MediaCodec.release]` warnings show `dequeuedCount=23 acquiredCount=0 mDequeueTimeout=0`; SurfaceTexture queues often report `dequeuedCount=21 acquiredCount=1-2`. Android's own `CCodecBufferChannel` uses output buffer slot count **plus reorder depth plus rendering depth** to configure `Surface::setMaxDequeuedBufferCount`. Therefore 22/23 occupancy may arise from Codec2-negotiated pools; **do not force smaller buffers, assume leaked acquired frames, reduce rendition quality or increase HTTP buffer duration**. Steady intervals have ~250 decoded texture latches per five seconds; warning count is not video frame-drop count.

The app-window FrameTimeline misses ~30-40% of 120Hz deadlines in representative clear TextureView windows while independent GLES latching can still sustain ~50fps. SurfaceView results (60/96/120 actual modes) are thermally and chronologically confounded. There is not yet a physically matched video-layer scanout/PTS-to-present comparison. The HEVC clear fragment pipeline processes ~6.7MB output fragments while process-wide allocation during background composition is ~75.6MB median and 2 GC cycles, with ~298ms median composition work. Uncached foreground segments are ~925ms median / ~1,663ms p95, compared with cached requests ~4ms median. Allocation counters are process-wide, so these numbers require allocation stack samples before exact attribution.

### Confirmed, app-owned source defects corrected in this candidate

| Defect and impact | Corrective production change | Automatic regression and limits |
| --- | --- | --- |
| **S0 Protected source transition failure**: `FeedPlayer.startProtectedComposedPlayback` could publish the new source and leave `protectedLiveDashSource` pointing at a released publisher if Media3 setup failed | Capture previous publisher, install and prepare new Media3 source first, close prior only after successful installation, restore owner field on failure, release failed publisher; keep CBCS/DRM and its lease store intact | Production-wiring test verifies ordering and rollback. Real authorized Widevine L1/L3/TV required |
| **S0 Archive/source replacement order**: old clear CMAF source was closed before new `player.setMediaSource/prepare`; restoring native feed retired old sources before Media3 had switched | Preserve previous ownership during setup; retire old clear/protected backing resources after the corresponding Media3 source transition; cleanup remains exception-safe | Static path test; run rapid 3→2→1, audio changes, pause/seek, casting/rotation. No codec leak is assumed without measured evidence |
| **S1 Actual display mismatch**: decoder observer sampled `Display.DEFAULT_DISPLAY`, which can differ from the scene's attached display during external/TV output | Scene reports actual display ID to its FeedPlayer; detached/retired owner cannot revoke a newer scene; unknown output ID stays at conservative cadence | Production-wiring + existing pure adaptive cadence unit tests; verify genuine 60/96/120Hz and real display selection |
| **S1 Diagnostics credentials**: source error messages reached playback status/diagnostic strings without complete redaction | Use existing `DiagnosticRedaction` on FeedPlayer root errors and additional cast, archive and protected composition error surfaces, not only URL pattern | Regression test verifies production callsites. Test real signed URL, headers and token-bearing exceptions |
| **S2 Malformed archive DataSpec**: negative read lengths or position+length overflow could reach `ByteArray.copyInto`/cursor | Explicitly validate DataSource read bounds and use available-byte arithmetic without overflow; retain Media3 EOF semantics and byte zeroing | Production-wiring contract; emulator source DataSource fault injection remains desirable |
| **S1 Queue causality observability**: BufferQueue warning name/identity was not enough to distinguish native codec pool from GL consumer/DRM | Extend `analyze_pitwall_bufferqueues.py` with observed dequeued/acquired counts, Qualcomm codec max-output-delay correlation, exact queue identities and unbind warnings; label source-to-scanout as NOT_MEASURED | Python fixtures validate correlation without falsely claiming root cause. Never filter or suppress vendor log messages |

### OPEN engineering optimizations, not yet warranted to mark 100%

**New high-priority finding: codec PTS / SurfaceTexture timestamp mismatch.** In the user-supplied Build 829-predecessor log, 50 bounded scene intervals report **11,919 Media3 codec frame callbacks but 0 exact PTS-to-texture matches**, with **10,977 not matched at latch time**. `SceneCodecTimestampCorrelation` directly tests Media3 `presentationTimeUs * 1000 == SurfaceTexture.timestamp`. Per Android docs, Media3 also provides a `releaseTimeNs` in the system clock domain, while SurfaceTexture timestamp semantics depend on the producer. The existing zero-match metric must NEVER be interpreted as 11,919 lost pictures or trustworthy codec-to-display latency. Add a validated one-to-one, producer-specific timestamp domain matcher (source PTS vs configured release-time vs unknown) with release generation, bounded reorder window, duplicate detection, and a separate SurfaceFlinger layer/PTS-to-present correlation. The mapping is an **OPEN source defect**, not fixed by Build 830. Relevant source: `app/src/main/java/app/apexview/scene/SceneCodecTimestampCorrelation.kt` and `app/src/main/java/app/apexview/FeedPlayer.kt`.


| Priority | Domain | Exact next implementation/verification | Exit condition |
| --- | --- | --- | --- |
| **P0** | Codec2 / BufferQueue | Correlate 22 max output delay, vendor `CCodecBufferChannel` config, codec callback/frame output, SurfaceTexture release and transitions by queue ID and timestamp. Verify during startup native fallback, static-composition handoff, codec output change and repeated teardown. If the vendor pool saturates while app is still latching at 50fps, do not mislabel it as a GL stall. Fix genuine app attach/detach races only when identity proves it. | No app-owned double attach, premature Surface/DRM release, repeated stale codec generation or real video freeze across matched sessions |
| **P0** | DRM/protected scene | Exercise real F1-authorized L1 and L3 on phone/TCL, release and replacement with Media3 event ordering; group `MediaDrm Object Already unbinded` by actual session and time. Do not suppress warnings or switch off L1. | Identified owner for each repeated unbind; zero confirmed app double releases, no key/caption/audio failure |
| **P0** | Codec-to-GL timestamp identity | 0/11,919 exact source PTS matches in current real log despite ~50/s texture latches. Detect timestamp source domain, normalize only with verified MediaCodec release-time correlation and prevent stale mappings across seeks/restart/DRM changes. | One-to-one unique match across first-frame/steady/1.5x/seek; report UNKNOWN rather than fabricated latency; measure independent physical scanout |
| **P0** | 120Hz/adaptive | Correlate `FrameMetrics.DEADLINE` and FrameTimeline with main/RenderThread, GL EGL swap/latch and actual SurfaceFlinger video layer. Current 1.5x 75fps promotion is conditional on selected refresh and thermal state, **not a verified GPU/codec throughput gate**. Add observed pipeline health/capability/PTS presentation gating before treating this as optimal. | 1x 50fps media and 1.5x best-supported output without visible freezes; app steady slow rate <5% target under matched thermal load, no default rate forcing |
| **P0** | Seek/audio source lifetime | Audio track switching currently calls `startArchiveVodPlayback` and may rebuild video source/codec. Replace with a stable video media source plus selectable native audio, preserving CC, keyframes and one codec; continue checking 80ms seek coalescing vs exact-target display arrival. | Audio language switch produces zero video codec reinitializations; 10x short and distant seeks meet bounded p95 and final displayed frame correctness |
| **P1** | HEVC memory/GC | Replace per-sample `bytes.copyOfRange` and repeated rewritten `ByteArray` ownership in `HevcClearCmafGridComposer.parseFeed` and `HevcTizenGridComposer` with immutable bounded buffer slices + streaming writer, retain HEVC access-unit validation/zeroization. Never skip NAL security checks. | Synthetic golden HEVC CMAF bytes and bitstream decode unchanged; process allocation/GC and uncached segment p95 materially lower in physical test |
| **P1** | Map/tower UI | Keep RenderNode retained region keys and 10Hz metadata/60Hz draw; inspect *which* actual threads exceed 8.33ms at 120 before changing paint/layout/cars; do not simply lower map freshness. | Measured reduction in UI jank with driver positions, labels, timing correctness and TCL focus preserved |
| **P1** | Release/threading architecture | Extract AppState's multi-thousand-line composition/seek/Cast lifecycle into auditable state transition coordinator with one Media3 looper owner, immutable snapshots, explicit cancellation/lease epochs and bounded futures. | State-machine tests for start/retry/seek/remove/rotation/background/Cast, no hidden decoder fallbacks |
| **P1** | Long endurance/cross-device | Run real S22/TCL and phone→TV Cast, 1/4/6/8 feeds where supported, 1×/1.5×, fixed/adaptive 60/96/120 and clear/protected. Collect 10min app stats and FrameTimeline + video-layer PTS evidence, GC/thermal and audio sync. | Repeatable physical evidence, no severe thermal degradation, no unexplained codec failures, app crashes or unresolved release defects |

**Completion accounting:** Build 830 GREEN verifies this 11-file audit-correction source commit, but not the P0 PTS-domain, HEVC zero-copy, audio-switch or real device acceptance changes listed above. Do not relabel the initial 11/11 *checklist* as all 11 user-observed engineering problems fixed. The original 16-workstream ledger stays at its current historical ~90.1875% coding estimate and **1/16 physical gates CLOSED** until a rebaseline; source fixes should receive a verified commit/build, while hardware goals remain OPEN. A code-only CI pass cannot certify Google/Netflix/YouTube-level efficiency.

## Build 829 GREEN: consolidated observation-driven Batch 2 corrective implementation (24 November 2026)

**Status: exact-source CI GREEN; physical performance acceptance OPEN.** [Build 829 GitHub Actions SUCCESS](https://github.com/cooldarshak/apexview/actions/runs/45337200359), production SHA `0010f4ddf56e823f406d7ecacd82ff22027b3f90`. All Kotlin/JVM/Python production wiring and policy tests, HEVC/CMAF/FFmpeg fixture verification, signed debug APK and benchmark APK assembly and actual emulator RenderNode/decoder-scene rendering tests passed. Nine artifacts were published. This 20-file change is a broader *observed-problem* corrective pass over the green Build 828 scoped baseline. **Do not infer all user-observed issues are solved merely from GREEN CI.** Build 828's 11/11 meant its narrow initial deliverable checklist, not full video/BufferQueue/120Hz/DRM/seek physical acceptance. The actual cause and improvement magnitude must be verified on S22 Ultra, TCL and Cast.

This consolidated corrective implementation preserves authorized F1 TV Widevine, hardware 1-decoder SINGLE, user-selected MULTIPLE, no extra decoder/DRM bypass, clear HEVC, protected HEVC, Android TV, baked Cast, clip/caption rules, manual video quality and existing Material 3 presentation. Source code and tests, never hidden guards, own the fixes.

| ID | New/retained engineering action | Outstanding acceptance that must **not** be called fixed by source alone |
| --- | --- | --- |
| **B2.1** | Share a pure adaptive output-cadence policy across the Media3 FrameThinner and both GLES scene hint/gates; permit ~75 distinct frames/s at 1.5x *only* for composed SINGLE with actual display ≥75Hz and thermal status NONE/LIGHT; otherwise retain 50fps bounded output. Re-evaluate display modes and thermal at bounded intervals. Distinguish media clock from shown frames. | Real source-to-display cadence, 60/90/96/120Hz mode switches, 1.5x smoothness, cool/hot thermal and no extra codec; avoid unnecessary 75fps output if it harms UI |
| **B2.2** | Idempotent, generation-checked Media3 input-surface leases across clear TextureView and clear/protected SurfaceView, reject stale reattachments on rotation/output destruction, explicit detach before GL consumer teardown. New independent `analyze_pitwall_bufferqueues.py` groups warnings by exact BufferQueue producer name + queue ID, expands repeats and separates mDequeueTimeout=0 from unknown waits. | Platform `[MediaCodec.release]` internal queue root cause NOT proven app-owned; new-build producer/consumer IDs, no actual codec/GLES stall and no repeating teardown misownership |
| **B2.3** | Maintain epoch-scoped scene/player ownership, idempotent attach/detach, owner-safe terminal cleanup and previous Build 827 coordinator generation controls. | Physical 3→2→1, seeks, rotations, F1 protected output, Cast and TV handoffs |
| **B2.4** | Retain existing immutable timing samples, field-aware RenderNode overlays and map/tower dirty-key caching rather than lowering data refresh to mask 120Hz UI lateness. Independent refresh-stratified acceptance tracks real UI latency. | Main-thread/UI GPU jank target <5% in steady windows, map/tower freshness, TV focus; no speculative claim of improved 120Hz jank |
| **B2.5** | Media3 exact-target 80ms seek coalescing retained; adaptive output thinning now permits full accelerated output when device allows and automatically falls back on hot/slow displays. | Real large seek p50/p95, A/V/captions, no unnecessary restart or stale frame |
| **B2.6** | Cache immutable per-session uniform HEVC atlas geometry, tile cells and source descriptors instead of regenerating each 5.76s fragment; keep all per-fragment picture/timing/IRAP verification. Thermal-safe output adaptation and existing bounded caches retained. | Profile process-wide vs compositor-specific allocations, ART GC, 7/8 feeds and 10-min thermal endurance |
| **B2.7** | Preserve measured startup/seek/rebuffer episode classification and bounded load-control; do not increase network buffers from zero-timeout GL warnings. | Only tune real Media3 starvation when matched load/segment timeline proves it |
| **B2.8** | Reversible A/B remains SurfaceView experimental OFF by default; independent SurfaceFlinger and source-PTS evidence still required before changing default. | Actual video-layer present timeline for both surface types (TextureView lacks standalone layer) and controlled real-device A/B |
| **B2.9** | Terminal FeedPlayer releases Media3 player/codec/DRM before zeroing its backing CMAF source; even a release error still cleans owned sources. Existing release gate remains idempotent, no DRM weakening. | Verify repeated MediaDrm unbind and vendor codec teardown warnings under authorized L1/L3 + TCL |
| **B2.10** | Preserve actual-input vs user cap vs composed geometry disclosure already coded in Build 827; do not report requested 480p as delivered 480p when source is 360p. | Confirm offered F1 renditions on device and fallback guidance |
| **B2.11** | New adaptive/thermal/1.5x JVM tests, BufferQueue named-queue diagnostics tests, stale-surface ownership wiring, real source geometry reuse tests and fixed-vs-adaptive acceptance harness. | Signed exact-source green CI + full S22/TCL/Pitwall Cast physical scenarios and 10-min controlled device tests |

**Closure rule:** Build 829 has verified the new source changes and full automated test contract, but **not** all 11 observed-issue/physical criteria are resolved. Record *coded and automated-verified correction candidates*, not "all 11 hardware problems fixed." Never delete the remaining issues, claim proprietary Android MediaCodec vendor internals are app-controlled, infer physical frame rate from GL submissions, downgrade frame rate/quality silently or hide the BufferQueue/DRM warnings. The existing 16-workstream 90.1875% coding estimate is historical; revise only with reviewed, automated-verified scope. Strict release gates remain **1/16 CLOSED, 15 OPEN**.

## Historical Build 828 GREEN: initial narrow Batch 2 checklist, not complete observed-issue sign-off (24 November 2026)

**Exact verified source:** [GitHub Actions Build 828 SUCCESS](https://github.com/cooldarshak/apexview/actions/runs/45331464624), production commit `0df60abcedf418d2b0b1de847ef93a2abfeae03d`. This includes the previously green [Build 827](https://github.com/cooldarshak/apexview/actions/runs/45322677378) G2/G3/G4 source implementation, plus the 10-file coordinated 120Hz/output cadence and codec BufferQueue teardown correction. Kotlin/JVM, source wiring, HEVC/CMAF/FFmpeg, signed debug and benchmark APKs and real emulator RenderNode/decoder-scene tests passed; nine artifacts were published. No physical hardware test, DRM entitlement, display scanout or Cast acceptance follows from GREEN CI.

**Scoped Batch 2 coding acceptance is 11/11 defined packages implemented and automated-verified.** Conditional work is deliberately evidence-led: do NOT raise Media3 network buffering when video BufferQueue logs are nonblocking, silently downgrade quality or force 60/120Hz; do NOT remove the current fast-playback frame thinner until a high-rate policy is measured. These are verified deliberate policy decisions, not unfinished arbitrary toggles. The original 16-workstream release status remains 1/16 CLOSED; physical Batch 2 sign-off is OPEN.

| Batch 2 package | Scoped code / CI | Physical acceptance | What the new build must show |
| --- | ---: | --- | --- |
| B2.1 Adaptive display and pacing | **100% coding, CI GREEN** | OPEN | Adaptive 60/96/120Hz, 1x and 1.5x; independent video cadence; no forced mode |
| B2.2 BufferQueue and GL ownership | **100% coding, CI GREEN** | OPEN | Matching surface attach/detach/release IDs; queue producer/consumer attribution; no causal stall |
| B2.3 Scene/player lifecycle | **100% coding, CI GREEN** | OPEN | 3→2→1, rotation, output replacement, seek, clear/protected/Cast without codec churn |
| B2.4 Map/tower UI | **100% coding, CI GREEN** | OPEN | Steady/interacting 120Hz app windows, map/tower correctness, TCL focus and thermal |
| B2.5 Seek and speed policy | **100% coding, CI GREEN** | OPEN | 1.5x clock/captions/audio, final exact seek and request-to-render p95 |
| B2.6 Memory and thermal | **100% coding, CI GREEN** | OPEN | 10min allocation/GC/sustained thermals without user quality changes |
| B2.7 Measured network buffering | **100% coding, CI GREEN** | OPEN | Startup/seek/rebuffer attribution; tune load-control only if true starvation proven |
| B2.8 Reversible A/B and scanout observability | **100% coding, CI GREEN** | OPEN | Real SurfaceFlinger video-layer output and matched 60/120Hz comparisons |
| B2.9 DRM cleanup and release | **100% coding, CI GREEN** | OPEN | Repeated license/unbind warnings traced to actual owner, authorized L1/L3/TCL |
| B2.10 Native rendition quality | **100% coding, CI GREEN** | OPEN | Actual HLS rendition versus manual cap and composed geometry displayed correctly |
| B2.11 Regression and release evidence | **100% coding, CI GREEN** | OPEN | Full physical matrix S22/TCL/Pitwall Cast and signed in-place upgrade |

**Important closure distinction:** The bounded Batch 2 source contract can be marked coding-complete on Build 828; this is NOT a claim that a physical BufferQueue root cause has been eliminated, that 120Hz UI jank is under 5%, that large seek p95 meets target, or that protected Android TV is smooth. All physical acceptance remains OPEN until new-build measurements. Existing full P2.1/P2.2/P2.3/P2.4/P2.5/P2.9 workstreams continue in the full release plan and Batch 3. No additional production APK build was triggered by this documentation reconciliation.

## 120Hz and BufferQueue evidence rebaseline, 24 November 2026

**Grounded evidence:** The supplied S22 Ultra log `pitwall-20261124-043242-899.log.txt` covers adaptive 60/96/120Hz and a 120Hz A/B. Media3 HEVC is one active decoder for composed SINGLE and the GLES stage sustains ~250 latches per five seconds in clean 1x samples. In the latest log, **1,096** BufferQueue dequeue timeouts when logged repeat counts are expanded, **799** producer detach warnings and **38** cancel warnings. Most dequeue timeouts are explicitly **mDequeueTimeout=0**, **dequeuedCount=23** and **acquiredCount=0**: these are nonblocking producer refusals, not proof of video freezes. The detach/cancel warnings cluster on decoder release/handoffs. Independent SurfaceFlinger video scanout still is not established by these traces.

**B2 source correctness pass:** Keep the existing G4/G2/G3 improvements and tests in exact-source GREEN Build 827, SHA `518363934b43a49f39f970df7de5e70c7436ba19`. Next coordinated production candidate ensures **both** TextureView and SurfaceView/protected scene explicitly clear the codec's old input surface on the Media3 application thread before the GL SurfaceTexture is destroyed or replaced, and records matching input surface ID and output generation for attach/detach/GL release. Add a GL-stage BufferQueue classifier that never mistakes nonblocking platform queue events for network underbuffering or physically dropped video. Preserve `updateTexImage()` latching independently of Choreographer display callbacks. Correct the **specific 1.5x inconsistency**: `FrameThinner` intentionally limits output to ~source cadence (~50fps), while the old `Surface.setFrameRate` hint advertised 75fps. Report real 75fps media time and use a truthful ~50fps output hint until an independently proven, capability-aware high-FPS policy is implemented. Align paced draw scheduling and add JVM tests for 50/75 fps distinction, fixed/adaptive modes and queue evidence. **Do not force the OS to 60Hz/120Hz.**

| Batch 2 item | Build 827 existing source evidence | Additional validation required for completion |
| --- | --- | --- |
| B2.1 Adaptive refresh | Actual Display listener, per-window min/max, cadence gate | New output-hint correctness, fixed/adaptive physical matrix and real presented-layer cadence |
| B2.2 BufferQueue | Existing GL epoch/latch/submission counters | New input producer-before-consumer ownership, queue identity/log evidence, correlate events with actual stalls |
| B2.3 Lifecycle | Build 827 surface/host/backend epoch and idempotent release | New detach-before-replace for both output types, teardown/rotation/TV/Cast regression |
| B2.4 Map/tower UI | Retained hardware layer and row scan optimization | Controlled 120Hz Compose and map/tower trace, improve actual measured hotspots without reducing timing correctness |
| B2.5 Seeking/1.5x | 80ms latest-target batch, exact Media3 seek | Real large-seek p95 and final frame, speed/audio/caption/tower continuity |
| B2.6 Memory/thermal | Bounded quad cache, ART GC/thermal logging | 10-minute thermal endurance and actual allocation work by traced allocation stack |
| B2.7 Buffering | Startup/seek/rebuffer/paused episode classification | Only tune Media3 buffers if independent underbuffering is established |
| B2.8 A/B | Reversible experimental SurfaceView OFF by default, GL telemetry | Real independent video-layer presentation and controlled matched A/B |
| B2.9 DRM cleanup | Idempotent release gate | Verify actual MediaDrm unbind ownership, protected F1 L1/L3 + TCL and Cast |
| B2.10 Rendition disclosure | Input/crop/coded output separation | Actual manual-cap source validation on real offered F1 tracks |
| B2.11 Tests | Build 827 exact-source GREEN test suite | New lifecycle, pacing and queue evidence JVM checks, production wiring, full green CI + physical matrix |

**Accounting rules:** The first-pass Build 827 is *verified source GREEN*, not 11/11 accepted physical gates. The historical 16-workstream coding ledger is **88.9375%** across original defined milestones, with **1/16 strict release gates CLOSED**. Newly discovered defects or changed requirements can generate new work within a previously 100%-coded milestone. Do not inflate that percentage merely by adding test code; reconcile the ledger only after the complete revised Batch 2 code and all automated checks are verified. Physical acceptance requires the device itself. A docs entry never certifies hardware behavior.

## Batch 2 coordinated source candidate (24 November 2026; verification pending)

Preflight additions: per-window observed refresh min/max, rejection of missing or mixed refresh evidence in physical matrix, missing single-decoder evidence fails closed, seek-state expiry, old-picture demand invalidation, terminal cleanup finally blocks. These retain physical gates OPEN; app-window refresh observations are not physical video scanout.

The Build 826 A/B comparison is confounded by 60/120Hz and touch transitions. App-window lateness is not independent video scanout; ~601 queue timeouts are not 601 freezes or proof of network starvation. Do not increase buffering or force refresh modes on this evidence. This change implements a coordinated set of source corrections and regression tests; **not yet exact-source GREEN, not Batch 2 100%**, and no physical gates close.

| Package | Candidate code / test evidence | Outstanding verification |
| --- | --- | --- |
| B2.1 | Both renderers use actual Display.refreshRate from display listener; SurfaceView hint state is GL-owned and tied to its output. Existing seamless-only policy retained. | Controlled 50/60/75/120Hz and touch A/B; compile/CI |
| B2.2 | GL output epoch validated before draw; coalesced texture latch counts separate from submissions and actual scanout. | Physical producer/consumer queue names and causal attribution; CI |
| B2.3 | Epoch captured by surface creation/bind; stale in-flight swap cannot acknowledge newer generation; viewer host/backend keys own callbacks. | Full lifecycle and receiver regression, CI |
| B2.4 | Retain unchanged tower visual keys and skip diagnostic-only row scan when no probe; existing motion and timing cadence retained. | Physical map/tower profile and remaining main-thread hotspots; CI |
| B2.5 | Fixed 80ms latest-request window only for costly rebuilds, exact Media3 control seek, accumulated skips and cancellable baked Cast restart. | Exact frame/seek p95, 1.5x, no new preview decoder; CI |
| B2.6 | Retain bounded quad buffer high-water up to 24 windows; log process ART GC counts/time separately from heap and thermal. | Endurance profiling and atlas retention audit; CI |
| B2.7 | Bounded steady-state Media3 load tracking and separate startup/seek/rebuffer/paused buffer episodes, cause UNATTRIBUTED. Existing load control unchanged pending measured starvation. | Match actual loads to rebuffer/segment timeline; CI |
| B2.8 | Replay capture waits for actual new clear output submission as baked Cast does; host/backend epoch scoped to renderer owner. | Physical layer IDs, screenshot/replay/Cast and controlled matrix; CI |
| B2.9 | Terminal FeedPlayer and composed player release is idempotent, no DRM policy change. | Verify actual duplicate MediaDrm warning ownership/order, receiver and secure physical paths; CI |
| B2.10 | Native selected input format reported separately from composed source crop/atlas; stale protected rendition not reused after stop; below/above-cap disclosure. | Actual F1 rendition and selected track parity; CI |
| B2.11 | Add JVM tests for epochs, coalescing, bounded buffers, seek, release and buffering, plus production wiring assertions. Existing strict change contract and emulator/HEVC/signing workflow unchanged. | Exact-source green full CI and remaining scenario coverage |

Historical 16-workstream coding estimate remains 88.9375% and 1/16 physical/release gates CLOSED until verified progress supports an update. Do not treat this candidate as eleven closed packages.

## Build 826 GREEN: Batch 1 Media3 1.11.1 and reversible clear SurfaceView A/B source completion (23 November 2026)

**Code completion for Batch 1: 100% of defined source deliverables; no known code pending for the specified opt-in A/B and aligned dependency migration.** Exact source [Build 826 GitHub Actions SUCCESS](https://github.com/cooldarshak/apexview/actions/runs/45243050523), commit `7c7b824e57458cada105c0c660b0f54e5df52bce`. This is verified source and automated execution, **NOT** 100% physical playback smoothness. The user must run matching Build826 phone/TCL protected/clear, Cast and A/B traces before physical/release gates can close. The previous Build819 unweighted workstream coding baseline is **88.9375% / 11.0625%** and remains recorded, because the new implementation refines already coded workstreams rather than proving remaining P2.1/P2.2/P2.3/P2.4/P2.5/P2.9 are completed. Strict release gates remain **1/16 CLOSED**.

| Batch 1 deliverable | Actual verified implementation | CI evidence | Physical decision |
| --- | --- | --- | --- |
| **M3 AndroidX migration** | All existing `app/build.gradle.kts` Media3 modules `1.5.1 → 1.11.1`; existing `FrameThinning.kt` codec-reuse and position-reset overrides forward new Media3 API flags. Target/min SDK, APK signing and DRM preferences preserved. | Kotlin/JVM tests and actual Android debug/benchmark compilation; **Build826 GREEN** | Protected/clear F1 TV, Cast, audio/captions, quality 1×/1.5× on physical devices still required |
| **G1 reversible video presentation A/B** | Settings > Diagnostics > **Experimental clear-video SurfaceView (A/B)** is persisted and **OFF by default**. ON routes clear **single-decoder composed HEVC** to the existing GLES `ProtectedDecoderSceneView(protectedOutput=false)`; OFF is the original `SingleDecoderSceneView` TextureView. Secure rolling streams unchanged. Baked Cast/replay/external capture selects TextureView; experimental EGL error reverts to TextureView without altering SINGLE/MULTIPLE. | Production code wiring, 5 pure JVM policy scenarios, **both** real Android MediaCodec/EGL synthetic HEVC scene instrumentation classes PASS in Build826, plus RenderNode/segment delivery tests. | Controlled SurfaceView vs TextureView device video-layer A/B is **NOT YET MEASURED**; never silently set experiment ON for all users |
| **G5/diagnostics** | Backend/surface and capture-handoff log markers, separate clear/secure EGL thread labels, five-second GL-side `Trace.setCounter` latched/submitted counts (Long). Existing Perfetto app-window/video-layer separation and phone-only aShell instructions retained. No fabricated physical FPS. | Source and Python regression tests PASS; Perfetto export and independent video-layer analyzer existing CI PASS | Real layer IDs, FrameTimeline, BufferQueue producer/consumer and exact decode/latch/audio correlation still physical investigation |
| **Capture-safe handoff and strict CI** | Wait boundedly for new TextureView first GL output before clear baked Cast, preserve replay clip and all controls; both clear TextureView and unprotected SurfaceView synthetic source-pixel tests mandatory in emulator, not just compiled. Software-only Android emulator explicitly cannot verify hardware decoder acceleration; real devices still require hardware decoder. | 39 successful GitHub Actions job steps; signed debug + benchmark, HEVC FFmpeg fixtures, unit/Python/Cast JS tests and emulator all GREEN; nine artifacts uploaded | Physical S22/TCL L1/L3, 6/8-feed, focus, captions, overlays, signed in-place upgrade and Cast still OPEN |

**A/B acceptance to finish outside CI:** Match exact content ID, feeds, actual quality, coded atlas, refresh, speed, map/tower and temperature; record 1× and 1.5× paired OFF/ON runs with 60 s genuine Perfetto after warmup and sustained ten-minute sessions where applicable. Compare independently presented **SurfaceView layer** frames separately from Compose app-window frames; check true output, seek, sound synchrony, buffer saturation and decoder count. Promote SurfaceView only if its actual layer and UX improve with **no** Cast/replay/caption/DRM/TV regressions. F1 entitlements, mobile/TV SINGLE/MULTIPLE and DRM invariants remain hard rules. No source-stage 100% claim implies on-device or release acceptance.

**CI failure ledger (all resolved in Build826):** Build822 failed an over-strict wiring-test expectation before Android compilation; Build823 correctly rejected a separate source-only follow-up with no same-commit plan change; Build824 detected two new `MediaCodecVideoRenderer` signatures and required Long counters; Build825 passed Android/HEVC/packaging and protected SurfaceView but exposed a software-only hosted emulator conflicting with a hard physical-hardware assertion in the newly mandatory clear test. Build826 keeps physical hardware strict, explicitly recognizes software-only **emulators** without treating them as physical acceptance, and completes the clear TextureView scene tests. No failed build was retroactively marked GREEN or produced a falsely signed-off release.

## Build 819 GREEN: P1.1 / P1.2 / P1.3 / P2.6 coding 100% (23 November 2026)

**Exact-source verification:** [Build 819 GitHub Actions SUCCESS](https://github.com/cooldarshak/apexview/actions/runs/45202138705), production SHA `4ab0dbb573315d728aac4724350cd5bdaa4207e6`. The corrected one-root-cause JUnit expectation from Build818 passes; remaining Kotlin/JVM, Python scenario/wiring, JavaScript/Cast, real generated HEVC/CMAF synthetic tile fixture, independent FFmpeg decode/metadata/pixel, both Kotlin debug/benchmark compilations, signed debug and nondebuggable benchmark APK packaging, and mandatory Android emulator RenderNode/SurfaceDecoder tests completed successfully. Nine verified artifacts: `apexview-custom-unit-test-reports`, `pitwall-cast-layout-receiver`, `pitwall-macrobenchmark-instrumentation`, `apexview-custom-debug-apk`, `apexview-hevc-single-decoder-instrumentation`, `apexview-hevc-output-parameter-validation`, `pitwall-benchmark-target-apk`, `pitwall-full-scene-hevc`, `pitwall-rendernode-instrumentation-report`. The failed Build818 is not retroactively green and produced no failed debug APK.

**P1.1 HEVC atlas: 100% coding / 0% known implementation pending.** Bounded validated smallest-coded-area candidate search and explicit proof-vs-search-budget status; CTB/tile/crop/slice model, bounded 1–8 feed search and source rectangles; actual sample frame cadence and hardware HEVC size/rate capability checked before accepting a candidate; duplicate capability queries memoized per coded geometry. Actual source/coded output/extra pixels and rejected geometry/hardware candidates logged. Synthetic equal/uneven sources, containment, incompatible/unsupported fail-closed cases, NAL/slice correctness and independent FFmpeg validation passed. A budget-limited candidate is NEVER falsely labeled globally minimal; the intended bounded policy is complete, not guaranteed mathematically optimal in a truncated search. No decoder-count/DRM change. Sustained real F1 profiles and decoder workload improvements require physical acceptance.

**P1.2 refresh matching: 100% coding / 0% known implementation pending.** Pure rate/available-display-mode policy handles effective source FPS × playback speed at 1×/1.5×, integral 50/75/100/150Hz and nonintegral modes, matching physical resolution, unknown modes and active-mode preference. Both clear TextureView and protected/TV/Cast SurfaceView use real DisplayManager updates and expose safe compatible mode candidates. Existing Surface.setFrameRate remains advisory and seamless-only; app never forces non-seamless display switches or infers actual video scanout. Executable source policy tests including Build818 150Hz regression and renderer tests pass; real display cadence, audio sync and protected playback remain physical-gate work.

**P1.3 quality/thermal: 100% coding / 0% known implementation pending.** Explicit manual per-feed cap policy cannot silently choose an above-cap CDN rendition; unavailable manual sizes show corrective guidance based on actual Media3 supported video tracks. Clear HLS quality candidates must fit a conforming coded HEVC atlas even in AUTO, with bounded integer geometry. Quality sheet discloses observed actual source pixels, window pixels, composed coded atlas and effective playback FPS, active codec when available, and thermal status. Multiple-decoder 0.75× MODERATE / 0.5× SEVERE visible-tile budget and critical main-only safety remain, while single-decoder encoded-source quality is unchanged unless user selects another permitted source. User quality and Widevine entitlement, phone/TV/Cast remain preserved. JVM regressions verify limitations and disclosure; thermal benefit/actual offered sources on device remain unmeasured.

**P2.6 testing/benchmarks: 100% coding / 0% known implementation pending.** Non-debuggable physical-only 10-minute `FrameTimingMetric` Macrobenchmark compiles; original renderer/seek/layout/control and HEVC emulator tests remain intact and run as specified by CI. Operator-controlled, fail-closed eight-scenario 1×/1.5× OFF/MAP/TOWER/BOTH per-device manifest validates source/rendition/feed/DRM/decoder/refresh/thermal comparability, ≥600s real app-window diagnostics and ≥45s genuine Perfetto FrameTimeline, reporting slow/frozen UI, BufferQueue, app-layer/independent video-layer observations, request p95, process-wide GC and allocations without inventing decoded-video FPS. Python test/production wiring and phone-only aShell instructions verified. The actual 10-minute physical tests have NOT been executed by GitHub Actions and are NOT claimed to pass.

**Completion accounting:** Coding-only completion is now **P1.1 100% / 0% pending; P1.2 100% / 0%; P1.3 100% / 0%; P2.6 100% / 0%** on the exact GREEN Build819 source. All other workstream coding figures remain unchanged. Across ALL 16 workstreams the unweighted coding estimate becomes **88.9375% complete / 11.0625% pending**. Strict physical/release gates remain **1/16 CLOSED, 15/16 OPEN**; P0.3/P0.4 source coding is still closed but the S22 UI <5% slow target, 50fps physical presentation, codec-to-texture PTS mapping, BufferQueue causal attribution, TCL 55C655 (Software DRM ON), protected Widevine, in-place update and Pitwall Cast hardware parity are NOT proven. 100% coding means the previously defined coding implementation is finished, not that all optimal hardware performance or device sign-off has been achieved. This is the original sole consolidated plan; no additional optimization plan or APK build is triggered by this docs-only accounting update.

## Post-Build 819: composition diagnostic ownership fix (verified in Build 821) and open rendering follow-up

**Verified diagnostic fix:** [Build 821 GREEN](https://github.com/cooldarshak/apexview/actions/runs/45231217897), SHA 683210d0ce771430d263d8c172a5e035831be478; exact-source automated checks passed for the diagnostic code and regression tests. Physical frame-pacing improvement is still UNVERIFIED. **Historical Build 819 milestone:** Build 819 / `4ab0dbb573315d728aac4724350cd5bdaa4207e6` was the previously verified GREEN production source at preparation. Build 821 supersedes this source baseline for the lifecycle diagnostic correction, not for physical performance acceptance. No hardware performance or DRM acceptance is inferred from compilation.

**Confirmed source defect (P2.2 coordinator ownership; P0.3 diagnostics):** The clear and protected archive jobs capture a historical feed count and publish terminal `CANCELLED` diagnostics even after `stopProtectedCompositionPresentation()` nulls the active capture. Their previous publisher guard rejected only a *different non-null capture*, so cancellation after capture became null could overwrite the current native single-feed state. The source and the user-provided Build 819 log agree that the remaining International feed could continue decoding normally while the Layout sheet still said two feeds / CANCELLED.

**Coordinated implementation:** Revoke diagnostics also on feed removal even when a failed preflight has already ended, so a terminal two-feed status cannot survive the transition to a native single feed. Production-wiring regression coverage verifies the real removal, stop, source-path and Layout code, supplementing the JVM state-machine tests. Add composition-attempt generation ownership to both archive paths, revoke it before stop/cancellation and when switching to TV capture or abandoning preflight, ignore obsolete errors and retries, remove the seek-path mutation of an obsolete diagnostic, and distinguish **Current feeds** (live tile state) from **Composition feeds** (the active composition attempt). Preserve clear/protected HEVC, one decoder, audio, map/tower, captions, modes, and Cast paths. Add pure JVM regression tests for 3→2→1 removal, rapid replacement, seek and decoder changes, session shutdown, and protected-to-clear races. This removes misleading diagnostics; it does not claim to eliminate BufferQueue backpressure.

**Performance investigation still OPEN (P0.3/P0.4/P1.2/P2.6):** Handover-reported Build 819 app-window 47.8% slow frames, persistent BufferQueue timeouts, and 1.5× seek traces require stage-level correlation and genuine physical video-layer FrameTimeline evidence. Do not classify app-window lateness as a 99% HEVC picture loss. Evaluate independent clear SurfaceView presentation and Media3 migration only as controlled, reversible experiments. Physical S22/TCL (Software DRM ON), clear/Widevine, SINGLE/MULTIPLE and Cast tests remain required before closing any gate.

**Accounting:** Historical Build 819 88.9375% coding / 11.0625% pending and 1/16 strict release gates closed remain the verified baseline. This follow-up is a newly identified code defect within a previously coded feature, not retroactive proof that the earlier source lacked its documented implementation. No new physical gate closes from this change.

## GitHub streaming-project adoption and Media3 1.11.1 migration: explicit implementation contract (revised 23 November 2026)

**Status: Build 826 GREEN. Batch 1 M3, G1 and existing G5 renderer instrumentation are CODED and automated-verified; the matched physical-device A/B and DRM/Cast sign-off remain OPEN. Subsequent G2–G7 enhancements are still planned, not completed.** This replaces the earlier incomplete research table that incorrectly excluded the Media3 upgrade. This section is a prioritized implementation contract **within the existing 16 workstreams**, not a new plan, new workstream, or automatic claim of Netflix/YouTube-level playback. The latest verified production source is **Build 826 GREEN** (SHA 7c7b824e57458cada105c0c660b0f54e5df52bce, [CI](https://github.com/cooldarshak/apexview/actions/runs/45243050523)), which includes Batch 1 source changes and the earlier Build821 diagnostic fix. This docs-only acceptance accounting update does not trigger another APK build. The Build 819 baseline figures remain historical **88.9375% coding complete / 11.0625% coding pending, 1/16 physical release gates closed**; do not increase coding percentages just because an idea appears in this plan.

**Why:** S22 Ultra Build 815 had 46.1% slow *app-window* frames; Build 819 reported around 47.8%, repeated BufferQueue timeout messages, app-window FrameTimeline lateness, and significant seeking activity at 1.5x. The app can still visibly render clear single-decoder atlas video smoothly. App-window jank is not interchangeable with independently presented F1 HEVC frame loss. Attribution of actual BufferQueue producers, real composed video surface presentation and thermal impact is incomplete. Do not claim any repository's techniques are a confirmed fix until a controlled Pitwall A/B comparison passes.

### A. Required Media3 upgrade: 1.5.1 → 1.11.1 (first-class work item, M3)

**Upstream source:** [AndroidX Media3 release notes](https://github.com/androidx/media/blob/release/RELEASENOTES.md), [Google's stable 1.11.1 release](https://developer.android.com/jetpack/androidx/releases/media3), 10 September 2026; [AndroidX Media3 source](https://github.com/androidx/media). Google identifies 1.11.1 as the stable release at this plan update. This is a **version migration using published AndroidX artifacts and supported APIs**, not copying external application code.

**Historical pre-migration Pitwall source (Build821):** app/build.gradle.kts used **val media3 = "1.5.1"**; **Build826 now compiles val media3 = "1.11.1"**; this version supplies exoplayer, exoplayer-dash, exoplayer-hls, ui and datasource-okhttp. Native integration includes app/src/main/java/app/apexview/FeedPlayer.kt, AppState.kt, scene/SingleDecoderSceneView.kt, scene/ProtectedDecoderSceneView.kt, Viewer.kt and custom HEVC/CMAF playback under app/src/main/java/app/apexview/tiled/. Also check receiver/benchmark dependency graphs, Cast sender APIs and all transitive Media3 modules. All Media3 libraries must use one aligned version.

**Required changes (implement, not merely investigate):**
1. Change the shared Media3 dependency version from **1.5.1 to 1.11.1** in app/build.gradle.kts; audit all project modules and their resolved dependency trees for accidental 1.5.x/1.11.x mixing. Keep minSdk 29, targetSdk 36, user settings and signing identity unchanged.
2. Audit and adapt source/API differences affecting ExoPlayer.Builder, DefaultLoadControl, DefaultMediaSourceFactory, custom DASH/CMAF MediaSource/sample streams, OkHttp DataSource, track selection/quality caps, player listeners, DRM session callbacks, MediaCodec video metadata callbacks, video-surface attach/detach, subtitle cues and replay/cast media sources. Fix deprecated/removed APIs via their documented replacements. In particular, 1.11.1 removes MediaSource.Factory.setExperimentalEnableHagcPlayback in favour of ExtractorsFactory.setParseHagcMetadata **only if used**; do not add irrelevant HAGC code.
3. Explicitly audit upstream 1.11.0/1.11.1 fixes relevant to Pitwall: reduced unnecessary codec resets when calculating codec operating rate; prewarming/surface restoration after seek; seek scrubbing stale-frame and stall fixes. **These address similar failure classes upstream but do not prove Pitwall's custom atlas or BufferQueue defect is fixed.** Enable new experimental prewarm/scrub capabilities only after source-path compatibility and measured benefits are established.
4. Keep the one-composed-stream/one-decoder SINGLE invariant and the separately selected MULTIPLE mode. Preserve F1 login/entitlements, authorized Widevine L1/L3 behaviour, HEVC/coded atlas alignment, clear HLS and encrypted DASH/CMAF, real audio/commentary/closed captions, manual quality selection, speed and A/V clock. No proprietary SDK, DRM bypass, implicit software decoder fallback or hidden downgrade.
5. Re-run and correct the **entire production test contract** without removing tests: Kotlin/JVM, Python/JS/Cast, generated and independently FFmpeg-checked HEVC fixtures, both APK/benchmark compilation, real emulator rendering instrumentation, Android TV focus, source/DRM selection. Then compare against the exact Build 821 playback baseline on S22 Ultra and TCL. Abort and revert the migration if hardware/DRM parity regresses; log the exact API or device blocker.

**Map to existing workstreams:** P0.1 (buffer/seek), P0.3 (decoder/presentation), P1.2 (operating rate/refresh), P1.3 (quality/thermal), P2.1 (Cast), P2.2 (lifecycle), P2.5 (TV), P2.6 (test/benchmark), P2.9 (captions). **Acceptance evidence:** one aligned 1.11.1 dependency tree, green exact-source CI, unchanged decoder counts and quality rules, protected/clear F1 streams and verified phone/TV/Cast smoke + sustained-device results. No automatic frame-smoothness claim from an upgraded version string.

### B. GitHub reference projects → exact proposed Pitwall code changes

Every row below specifies **source project and actual reference file**, **Pitwall target**, **what to implement**, and **what proves it helped**. "Adopt" means an idea/pattern or documented library API, **not a claim of source code already copied or coded**.

| ID and priority | GitHub project and inspected upstream reference | Specific code change required in Pitwall | Existing workstreams and objective acceptance |
| --- | --- | --- | --- |
| G1 / highest | **[AndroidX Media3 SurfaceControl demo](https://github.com/androidx/media/tree/release/demos/surface)**, demos/surface/README.md. Its retained decoder-attached surface can be reparented across SurfaceViews, avoiding reconfiguration for supported use cases. | Implement an **experimental, reversible clear-GLES final-output SurfaceView** alongside existing clear TextureView in **scene/SingleDecoderSceneView.kt**, **scene/ProtectedDecoderSceneView.kt** (reference only, keep protected DRM secure), **Viewer.kt/PlaybackVideoCanvas** and **scene/SceneSurfaceLifetime.kt**. Preserve codec→SurfaceTexture→one GLES atlas compositor→one output layer, one hardware decoder. If Android SurfaceControl reparenting helps layout/fullscreen and is safe, test retaining output independently from Compose view lifetime. Preserve overlay z-order/clipping, resize, rotation, PiP, replay recording, screenshot-mode rules and Cast baked capture. The SurfaceControl demo handles a decoder surface, **not** Pitwall's custom EGL compositor, so direct transplantation is incorrect. | P0.3/P1.2/P2.1/P2.5/P2.6. Same-F1-source controlled TextureView vs SurfaceView A/B: actual independent video-layer FrameTimeline, EGL swap/latch, BufferQueue identities, 1x/1.5x, OFF/MAP/TOWER/BOTH, screen and Cast parity. Prefer new default only if evidence beats original without regression; otherwise revert experiment. |
| G2 / high | **[Just (Video) Player](https://github.com/moneytoo/Player)**, app/src/main/java/com/brouken/player/PlayerActivity.java (CLOSEST_SYNC scrubbing and PREVIOUS_SYNC/NEXT_SYNC jump paths). | Improve **AppState.kt seekTo()/skip()**, **FeedPlayer.kt** and **tiled/ArchiveBufferPolicy / archive composition startup**: coalesce superseded rapid scrubs, favour validated HEVC sync/IRAP boundaries for preview seeks, settle at the correct requested frame when scrub finishes, cancel obsolete preflight/foreground/prefetch requests, avoid unnecessary composition teardown, preserve speed=1.5x and map/tower/audio/caption clock position. Keep protected rolling DASH seek rebuild when genuinely necessary. | P0.1/P0.3/P2.2/P2.6. Request-to-first-correctly-presented-frame p50/p95, request count, decoder/session rebuild count, network fetch/GC and A/V alignment. Do not assume nearest-sync is exact final seek or bypass encrypted random access restrictions. |
| G3 / high | **[Jellyfin Android TV](https://github.com/jellyfin/jellyfin-androidtv)**, playback/media3/exoplayer/src/main/kotlin/**ExoPlayerBackend.kt** (DefaultLoadControl.Builder, surface attach, player state), **ExoPlayerOptions.kt** (min/max/start/rebuffer durations). | In **FeedPlayer.kt**, **AppState.kt** and **cast/local/PitwallReceiverActivity.kt**, separate player state, surface ownership and actual media buffering from UI and TV lifecycles. Expose **bounded, measured Media3 load-control tuning** for cold start, after seek and after rebuffer only where load-event metrics establish underbuffering. Preserve foreground CMAF segment priority and bounded cache; do not incorrectly treat SurfaceFlinger/GL BufferQueue stuffing as insufficient network buffers. | P0.1/P0.2/P2.2/P2.5/P2.6. Compare BUFFERING counts/durations, startup/seek p95, segment latency, bytes/memory and codec/GL queues separately, including Widevine TV playback. Revert extra buffering if it increases latency/memory or does not improve actual buffering. |
| G4 / high | **[Next Player](https://github.com/anilbeesetti/nextplayer)**, feature/player/src/main/java/dev/anilbeesetti/nextplayer/feature/player/**PlayerContentFrame.kt** (Media3 Compose PlayerSurface SURFACE_TYPE_SURFACE_VIEW and presentation state), **state/SeekGestureState.kt** and **service/DecoderRecoveryManager.kt**. | Adapt concepts in **Viewer.kt/PlaybackVideoCanvas**, **AppState.kt**, **FeedPlayer.kt**, **scene/SceneSurfaceLifetime.kt**, **scene/SceneOutputReadiness.kt**: isolate surface rendering from Compose controls and player state, preserve current surface over layout-only recompositions, distinguish intentional seek cancellation from player failure, make recovery state/generation explicit, avoid repeated release/prepare loops. For scrubbing, separate transient gesture state from final media position. Do **not** replace the existing custom GLES HEVC scene with a plain PlayerSurface. | P0.3/P0.4/P2.2/P2.5/P2.8/P2.6. Rapid 3→2→1 selection, fullscreen, orientation, PiP, D-pad, background/foreground, 1.5x seek and decoder errors; no duplicate decoders, black frames or stale UI. **Next Player is GPL-3.0:** no copying GPL source without a deliberate licensing decision; use independently written ideas and published Media3 APIs. |
| G5 / high | **[MotionFlow Android](https://github.com/Endijuan33/motionflow-android)**, app/src/main/java/com/motionflow/player/core/media/**performance/FramePerformanceAccumulator.kt**, **performance/android/AndroidPerformanceProbe.kt**, **pacing/FramePacingPolicy.kt**. | Use its session-metric separation and cadence classification as references while enhancing Pitwall's own **scene/SceneFramePacing.kt**, **scene/SceneCodecTimestampCorrelation.kt**, both scene views, **docs/perfetto/frame_timeline_export.sql**, **scripts/pitwall_acceptance_matrix.py** and Macrobenchmark. Report timestamp domains, actual refresh selection, codec callbacks, texture latches, GL draw/swap, real layer presentation, app-window jank, audio offset, queue producer/consumer, GC/memory and thermal without misleading zeros or per-video-frame assumptions. | P0.3/P1.2/P1.3/P2.6. Show stage-level evidence separately for 50fps at 1x and effective 75fps at 1.5x, where supported; 120Hz fractional cadence is a hardware/display constraint. No metric should say video frames were dropped merely because Compose frames were late. |
| G6 / conditional | **[Compose Video Player Pooling](https://github.com/nikitachicherindev/compose-video-player-pooling)**, app/src/main/java/dev/nikitachicherin/videopooling/ui/videoplayer/**ExoPlayerPool.kt**, **VideoContainer.kt** (bounded acquisition/release and visible-surface ownership). | Assess bounded player reuse **only for previews and user-selected MULTIPLE mode** in **FeedPlayer.kt/AppState.kt/Viewer.kt** after profiler proof of setup churn; release idle views promptly and preserve entitlement/DRM session ownership. **NEVER** pool extra active HEVC decoders or create a second composed decoder inside SINGLE. Avoid player warming that harms current foreground network/thermal capacity. | P0.2/P2.2/P2.5/P2.6. Implement only if measured allocation, startup and decoder initialization improve with no additional playback decoder, memory leak or protected-session retention. Lowest default priority. |
| G7 / supporting TV/UI context | **[F1OpenViewer example (public repository)](https://github.com/npanu420/F1OpenViewer)** and **[AerialViews](https://github.com/theothernt/AerialViews)** are reference-level F1/Android TV experiences, not proven source-level fixes for Pitwall's BufferQueue. | Retain the existing Pitwall map/tower and D-pad experience; evaluate applicable TV overlay focus, video-surface hierarchy and transition concepts **only after locating and licensing the actual relevant source**, rather than copying uncertain code. | P0.4/P2.5/P2.8. TV input focus, map/tower freshness, overlay placement and accessibility under real playback. Optional, evidence-led; not a prerequisite to fix the codec. |

**Project-to-change ownership:** G1 specifically owns **SurfaceView/SurfaceControl experimentation**; G2 owns **sync-point scrubbing and rapid-seek behaviour**; G3 owns **buffer thresholds and TV player-state separation**; G4 owns **Compose/player view-lifetime and decoder recovery state**; G5 owns **accurate independent metrics and cadence classification**; G6 owns **conditional bounded player reuse**, and M3 owns the **mandatory 1.5.1→1.11.1 dependency/API migration**. No project has been proven to fix the reported problem; reject unnecessary duplication of features already implemented in Pitwall.

### C. Consolidated implementation order, real acceptance and release gates

**Preparation, no code churn:** Pin Build 821 GREEN as exact source baseline. Record the current Media3 dependency graph, existing single/multiple decoder count, initial F1 startup/seek logs and independent app-vs-video layer traces. Use existing production and synthetic CI fixtures. Confirm authorized clear and protected source examples and the TCL Software DRM=ON configuration. Fix current ownership-source defects only when reproduced.

**Implementation batch 1: M3 + G5 + G1 (media foundation and measurable A/B).** In one coordinated source/test branch batch: migrate **all Media3 modules to 1.11.1**, fix actual compile/API incompatibilities, add missing independent output/BufferQueue telemetry, implement **reversible** experimental clear final-output SurfaceView while retaining TextureView control. Preserve clear/protected/MULTIPLE/Cast source selection and stable app identity. Compile, test, instrument and run controlled Android emulator work **before one useful GitHub Actions production build**. Physical same-source/same-temperature A/B is the decision gate for promoting SurfaceView, not an automatic production switch. Any unsupported protected surface behaviour must remain on the existing protected path; do not force an unsafe shared renderer.

**Implementation batch 2: G4 + G2 + G3 (lifecycle, seek, buffering).** Following measured A/B: extract coordinator ownership from AppState incrementally (no destructive rewrite), prevent decoder/surface churn when layout-only, coalesce scrub seeks while preserving exact final frame and 1.5x audio/map/tower alignment, and tune start/rebuffer memory/network thresholds only if genuine Media3 underbuffering is measured. Cover regression state machine tests and 3→2→1, rapid seeks, feed switch, surface teardown, protected license and Cast recovery. Consolidate source changes and tests into one coherent production CI run, not dozens of tiny builds.

**Implementation batch 3: G5 + G6-optional + TV/Cast/overlay acceptance.** Keep the already coded retained timing-map/tower and UI optimization. Only introduce bounded non-SINGLE player reuse if evidence supports it. Close P2.1 (Cast), P2.5 (TV), P2.9 (captions) and applicable P0.x/P1.x physical release gates through real authorized F1 feeds with TCL 55C655 Software DRM **ON**, S22 Ultra and Pitwall Cast. Preserve quality, subtitles, layouts, focus, signed in-place upgrades, secure protected content and thermal safeguards. A green CI or screenshot alone never closes physical release gates.

**Hard release acceptance, unchanged:** Use the existing 10-minute matrix for SINGLE + user-selected MULTIPLE; clear HEVC + authorized Widevine L1/L3 as actually supported; native phone, TCL and Cast; 1x/1.5x; OFF/MAP/TOWER/BOTH; 1/4/6/8 feeds as supported; auto/manual quality; repeated seeks, player removal/addition, PiP and orientation. Target **under 5% controlled slow app-window frames with no steady freezes**, independent verifiable video-layer presentation, measured (not invented) seek p95, no unexplained recurring BufferQueue saturation, expected decoder count, A/V synchrony, caption/tower/map/D-pad correctness and sustainable thermals. For 50fps source at 1.5x effective presentation ~75fps, explicitly report actual display refresh and achievable cadence rather than claiming perfect scanout at unsupported refresh modes. Preserve every user-facing function; **fix, don't delete**.

**Coding/release accounting:** Keep the **single existing 16-workstream ledger** and attach M3 and G1–G7 tasks to the workstream IDs above. Prior 100%-coded rows are preserved as historical milestones but reopened for code follow-up only when a demonstrated defect requires actual changes. Do not fabricate extra percentage completion for planning or green CI; report physically OPEN until proven. Security, F1 entitlements and DRM restrictions remain mandatory. Never bring a GPL implementation directly into Pitwall without resolving its license.

**Project source URLs and source-file mappings are part of this accepted engineering plan, not generic recommendations. No new CI run is required for this documentation-only change.**

## Batch 1 coordinated implementation: Media3 1.11.1 + clear SurfaceView A/B + presentation observability

**Build 825 CI emulator-only hardware assertion diagnosed and corrected:** All Kotlin/JVM, Media3 1.11.1 compilation, independent HEVC/CMAF FFmpeg, APK build and RenderNode + independent synthetic SurfaceView scene tests passed. Newly required *clear TextureView* synthetic HEVC scene test decoded and sampled both initial source cameras, but stopped at its pre-existing assertion that the selected decoder must be hardware accelerated. GitHub's hosted Android emulator exposes software-only HEVC for this fixture, unlike a physical Samsung/TCL device. Keep the complete pixel identity/overlap/seek/recreation/one-decoder tests mandatory, now reject a software HEVC decoder unless the device is explicitly an emulator **and advertises no hardware HEVC decoder at all**. Every real phone/TV still requires actual hardware decoder selection. The source test logs both codec name and distinct `physicalHardwareGate=NOT_MEASURED` for emulator. This is accurate test-environment accounting, never a DRM or user-playback software fallback and never physical-performance sign-off.



**Build 824 exact-source Kotlin API failure and coordinated migration correction:** The true first compile errors after Media3 1.5.1→1.11.1 are `FrameThinning.kt`'s custom `MediaCodecVideoRenderer.canReuseCodec` now requiring `isAdaptiveFormatChange: Boolean` and `onPositionReset` now requiring `sampleStreamIsResetToKeyFrame: Boolean`. Forward both flags to the superclass and preserve the existing TCL coded SPS re-creation and frame-thinning reset. Android SDK 37 `Trace.setCounter` requires a `Long`; switch both per-five-second scene instrumentation counters to nonnegative `Long` samples without reducing precision or claiming scanout. Wire real-source regression tests and this single plan into the same corrective commit. No disabling the compiler, CI, DRM, source-output validation or any renderer path.



**Build 822/823 CI correction, consolidated in the following code candidate:** Build 822 stopped on an overly strict Python source-wiring expectation before Android compilation. The correction makes both the original TextureView and the experimental SurfaceView genuine *mandatory* emulator synthetic HEVC instrumentation paths. Build 823 enforced the coordinated-change contract, which rejected the preceding three-file source correction because this single consolidated plan had not been changed in that exact commit. This pass keeps that gate fully enforced and includes **this plan, the corresponding production code, and regression coverage together**. Also distinguish clear-versus-secure SurfaceView GL threads in Perfetto (PitwallClearSurfaceScene vs PitwallSecureScene), preventing clear-video experimental queue samples from being mistaken for protected output. This is a correction to real source/test/diagnostic parity, not permission to hide failures or mark physical gates closed.



**Verified source implementation, Build 826 GREEN; pending physical A/B:** align all five existing Media3 app dependencies from 1.5.1 to **1.11.1**; add the persisted **default-OFF experimental clear SurfaceView** choice in Settings > Diagnostics, a pure-testable output policy and production wiring. Reuse the tested GLES `ProtectedDecoderSceneView(protectedOutput=false)` path to present a clear one-decoder composed HEVC atlas through an independent SurfaceView; preserve the original `SingleDecoderSceneView` TextureView for baseline/capture. **Protected Widevine, native single-feed PlayerView, MULTIPLE, and existing Cast and replay remain in place.** Force TextureView automatically whenever baked Cast/replay/external-window capture is requested, and fail back to TextureView on a clear SurfaceView EGL error without changing decoder preference. Add low-frequency trace counters for texture latch / GL swap submissions, clear renderer/backend markers, pure JVM lifecycle/output-selection tests and a production code-wiring regression. Phone-only Perfetto instructions include a matched TextureView/SurfaceView A/B.

**Verification required:** the full GitHub Actions pipeline with Android/Kotlin/Media3 API compilation, custom HEVC synthetic/FFmpeg, Kotlin/Python/JS/Cast, debug and benchmark APK, secure/nonsecure emulated scene tests; 1.0x/1.5x, captions, 1/4/6/8 feeds where supported, real authorized clear/Widevine (L1/L3) on S22 and TCL Software DRM ON, Cast and capture. No stage count is physical presentation. Promote experimental SurfaceView only on measured improvement and zero regressions. **Coding completion and physical gates must remain separate**; do not close any physical gate on an untested build or declare 100% of the physical A/B.

## Build 818 exact-source first-failure and coordinated JUnit correction (historical, corrected and verified in Build819)

**Build 818:** [GitHub Actions](https://github.com/cooldarshak/apexview/actions/runs/45200499210), production commit `14bb9179c0a25bdb330eb3e104fd29bdc190ef9f` failed at **one and only one of 772 JVM tests**. Both production `app:compileDebugKotlin` and Macrobenchmark `compileDebugKotlin`/`compileBenchmarkKotlin` passed, Python, HEVC fixture generation and JS/Cast checks passed. All later FFmpeg comparison, APK assembly and emulator instrumentation were correctly skipped; no failed APK was published. The exact failure was `SceneFrameRatePolicyTest.seamlessCandidatesNeverConsiderDifferentResolutionOrInventSupport`: expected mode IDs `[3,2]` (50 and 100Hz), actual `[3,2,6]` (50,100,150Hz).

**Root cause:** The new source policy correctly accepted 150Hz as an *integral multiple of 50fps* (150/50=3), but its test expectation omitted that mode. At 1.5×, 50fps becomes 75fps and 150Hz remains valid (150/75=2). Both are actual mathematical multiples; neither establishes physical scanout or grants an OS refresh mode switch. **No production algorithm change is justified.** Correct the test's expected 1× list to include mode 6; retain same-resolution filtering and 1.5× assertions, and add focused positive 150Hz / negative 120Hz checks at both 1× and 1.5×. This is a single root-cause regression correction with the original plan updated in the SAME commit. Do not delete tests, silence failures, change the underlying algorithm or trigger multiple builds to probe trivial edits.

**Acceptance accounting:** Build 817 remains the last exact-source GREEN until the corrected commit completes full Kotlin/JVM, HEVC/CMAF/FFmpeg, signed APK and emulator instrumentation CI. P1.1 100%, P1.2 100%, P1.3 100%, P2.6 100%, and aggregate 88.9375% remain **targets, not verified completions**, until then. Physical release gates stay **1/16 closed, 15 open**.

## Post-Build 817: one coordinated four-workstream source closeout (historical candidate, verified in Build819)

**Requested coding scope:** Close P1.1 HEVC coded atlas, P1.2 display refresh matching, P1.3 quality and thermal policy, and P2.6 reproducible testing/benchmarks **from the coding perspective**. This is a coordinated production/test/plan candidate, not a claim of physical optimization or S22/TCL release. Build 817 remains the last GREEN production checkpoint until the candidate's HEVC fixtures, Kotlin/JVM, Python, signed APK and emulator tests all pass. No new parallel plan, decoder, DRM bypass, Tiledmedia SDK, OpenF1, hidden quality reduction or non-seamless mode switch.

**P1.1 source implementation prepared:** The existing `HevcMixedArchiveAtlasPlan` already performs a bounded best-valid **coded-pixel-area** search. Its completion now includes explicit per-output-size decoder capability eligibility before selecting a mixed-quality atlas, independent of CTB/PPS cropping and slice compatibility; the first real CMAF sample cadence is known before this check. Unsupported codec size/rate candidates are counted and skipped rather than silently introducing a decoder or selecting an unplayable low-area shape. Hardware probes are memoized per candidate size. The planner records checked shapes, rejected grid geometries, rejected decoder capability candidates, whether the budget was exhausted, and whether the minimum area is proven (never fake an optimum if a bounded search is incomplete). The actual VOD `ARCHIVE_VOD_ATLAS` diagnostic records input/output coded pixels and the new proof/eligibility fields. Mixed/equal source parsed HEVC fixture regressions cover source crop, independent tile placement, exact area and device rejection; existing CMAF synthesis, HEVC slice/POC/IRAP tests and FFmpeg independently decoded pixel comparisons remain mandatory. Uniform atlas, protected Widevine and original fallback behaviour remain guarded by their existing validations. True sustained coded-pixel/GC/thermal gains require physical comparisons.

**P1.2 source implementation prepared:** Both the actual clear TextureView and protected SurfaceView/Cast-receiver renderer now log current + available display modes and list **only same-physical-resolution, integral video-frame cadence modes** at effective source FPS × playback speed. The policy is independently JVM-tested for 50/100/120 Hz at 1× and 75/100/150 Hz at 1.5×, unknown cadence and differing resolutions. The app intentionally does **not** set `preferredDisplayModeId` or demand a disruptive/non-seamless switch: its existing Android 11+/12+ `Surface.setFrameRate` request stays safe and advisory, and mode changes are observed through DisplayManager rather than guessed. PiP, rotation, seek, native Android TV and authorized Widevine continue using the existing renderer lifecycle. Perfetto still must prove physical scanout; a recommended mode is not a presented frame.

**P1.3 source implementation prepared:** Fixed/manual per-feed rendition ceilings are not replaced with an unknown above-cap video format when the CDN has no lower rung. Real Media3 VIDEO track-group offerings now publish a visible quality-sheet warning with a corrective Auto/higher-cap choice when a manual ceiling has no matching source; it never misinterprets the composed atlas track as a per-feed rendition. Real HLS rendition selection now validates atlas fit in AUTO as well as manual mode, including bounded heights and overflow. The quality sheet reports **observed** source pixels, visible tile dimensions, actual composed HEVC coded dimensions, effective source × playback FPS, active observed decoder (or not yet observed), and transparent moderate/severe/critical/recovery guidance. Guidance does not claim undocumented F1 renditions, hardware capacity beyond the validated codec, lower single-decoder quality or unmeasured temperature improvements. Existing user-selected quality, multiple-decoder visibility rules, critical thermal safety and TCL Software DRM requirement remain intact. Executable unit tests cover manual-cap handling, unknown/oversized auto sources, real workload labels, and notices; actual sustainable thermal policy still needs device evidence.

**P2.6 source implementation prepared:** Adds `sustainedAuthorizedPlaybackTenMinutes` to the non-debuggable physical Macrobenchmark runner, retaining the existing UI, controls, seek, RenderNode and decoder instrumentation. An independent `scripts/pitwall_acceptance_matrix.py` validates eight real 1×/1.5× OFF/MAP/TOWER/BOTH sessions **per identical device/build/F1 source rendition/decoder preference/protection/Cast/feed-count/display mode**, requiring 600 seconds of actual app diagnostics and ≥45 seconds of independent Perfetto FrameTimeline each, with thermal-drift checks, failed/unknown evidence and source-vs-app-window separation. Reports app jank/frozen, actual SurfaceFlinger app/video layer lateness, process-wide composition allocation/GC, queue timeout rate, and archive request p95 without claiming source picture scanout. Python tests fail duplicate/incomplete/mismatched/hot matrices; phone-only aShell instructions and export schema are documented in `docs/perfetto/ASHELL.md`. The tool NEVER signs a hardware gate or substitutes synthetic input for F1 authorization.

**Coding-only acceptance target after a green exact-source run:** P1.1 **100% / 0%** if fixture and codec validation pass, P1.2 **100% / 0%** for safe mode-selection code/instrumentation, P1.3 **100% / 0%** for source-quality/thermal disclosure policy, P2.6 **100% / 0%** for executable benchmark/reporting infrastructure. On success, the unweighted 16-workstream coding ledger would be **88.9375% complete / 11.0625% pending**. Do not record these targets as achieved until CI verifies the exact source and integration audit passes. Hardware gates remain **1/16 CLOSED**, **15/16 OPEN**, including the original <5% slow-UI target, actual 50fps on-screen pacing, BufferQueue ownership/cause, codec timestamp correlation, 10-minute S22 and TCL runs, Widevine and Pitwall Cast. Future physical evidence of a source defect must reopen coding honestly.

## Build 817 exact-source GREEN: coordinated P1.1/P1.2/P1.3/P2.6 + thermal startup correction (23 November 2026)

**Full GitHub Actions success:** [Build 817](https://github.com/cooldarshak/apexview/actions/runs/45191605071), production SHA `d3ad6c75665798b4eaa1436910f31391cb16bb50`. Workflow completed **successfully** after the mandatory change contract, new and existing Kotlin JVM regressions, Python checks, Cast/JS validation, independent synthetic tile and HEVC/CMAF decoding, signed app/debug and non-debuggable benchmark packaging, and Android emulator RenderNode/SurfaceDecoder instrumentation. Verified published artifacts: `apexview-custom-unit-test-reports`, `apexview-custom-debug-apk`, `pitwall-benchmark-target-apk`, `pitwall-macrobenchmark-instrumentation`, `apexview-hevc-single-decoder-instrumentation`, `pitwall-rendernode-instrumentation-report`, `apexview-hevc-output-parameter-validation`, `pitwall-cast-layout-receiver`, and `pitwall-full-scene-hevc`. Build 816 was auto-cancelled/superseded by this critical lifecycle correction, not credited as a green build.

**Scoped source results (coding verified, actual performance NOT measured):** P1.1 segment-invariant atlas destination address/seam computations, with byte/slice parity fixtures and no speculative tile-grid rewrite; P1.2 speed-aware 1×/1.5× frame-rate multiples and actual display-mode diagnostics in clear TextureView and authorized protected/receiver SurfaceView, still seamless-only; P1.3 preserved, tested explicit multiple-decoder thermal budget and visible moderate/severe/critical/recovery notices without changing user source caps, with `AppState.message` initialized before the startup listener; P2.6 disallows confounded overlapping/unmatched-duration overlay A/B windows. All protected content, one-decoder and user-MULTIPLE selection, phone/TV/Cast paths, captions, D-pad, seek, layout and signing behavior remain present in source. Green CI does **not** prove physical parity or performance improvements.

**Conservative coding-only rescoring from audited tasks completed:** P1.1 **86%** (+1), P1.2 **65%** (+5), P1.3 **60%** (+5), P2.6 **88%** (+1). These are engineering estimates of remaining defined source scope, not measured improvements or new hardware signoffs. All other coding rows unchanged; aggregate unweighted **82.625% coding / 17.375% pending**. Strict hardware/release gates **1/16 closed / 15 open**. Neither the 46.1% slow S22 UI-window rate, the zero exact codec-to-texture PTS matches, nor BufferQueue owner/cause have been proven fixed. Next work: real mixed atlas/decoder testing and 10-minute controlled S22 and TCL tests with Software DRM ON, 1×/1.5×, protected/clear HEVC and Pitwall Cast; then causal investigation of P0.2/P0.3/P0.4 where traces establish a source defect. This is the ONE consolidated plan; no new build should be started by this docs-only reconciliation.

## Post-816 thermal startup lifecycle correction (historical source correction, VERIFIED IN 817)

**Source-level defect caught before declaring Build 816 accepted:** Kotlin initializes delegated state properties and `init` blocks in declaration order. In the preceding candidate `AppState` thermal startup notice was called from an early `init` block but `var message by mutableStateOf(...)` was declared later, so an already-warm device could reach an uninitialized property delegate before the first screen. This is a real warm-start lifecycle risk independent of compilation or ordinary cold CI. Move the **same** Compose `message` state to the beginning of the class, before the thermal listener and startup notice; do not delete the notice or alter cooling behaviour, Widevine, decode mode, overlays, captions or Cast. Add a production-wiring regression asserting initialization order and a unique delegate declaration. The correction supersedes the exact-source acceptance of the preceding candidate; no success claim for either commit until the corrected source passes the entire normal CI. Coding percentages and physical release-gate count remain unchanged. The extra corrective CI is justified by a specific potentially startup-breaking defect rather than a cosmetic change.

## Post-Build 815 coordinated P1.1/P1.2/P1.3/P2.6 source pass (historical pre-817 CI candidate)

**Code audit and candidate, 23 November 2026:** The mixed-source atlas planner already evaluates bounded candidates by actual validated coded pixel area; no duplicate planner or speculative new tile logic is introduced. In `HevcTizenGridComposer.composeNalUnits`, per-cell destination CTB addresses and same-source seam booleans are now precomputed once after output geometry validation, instead of recalculated across every coded picture. SPS/PPS validation, exact output header round trips, per-picture identity, CABAC payload preservation and wipe-on-cancel remain unchanged. JVM fixture regression compares emitted sample bytes and slice evidence under shuffled cell selection, across all pictures. No measured decoder pixel/memory/thermal benefit is claimed; P1.1 device and mixed-quality acceptance remain OPEN.

**P1.2:** Clear and protected video renderers report active and available real display modes, with effective 50fps × playback-speed and integer refresh multiple checks. Protected SurfaceView (including receiver use) registers/unregisters a DisplayManager mode-change listener. 50fps 1× and 75fps 1.5× JUnit cadence cases distinguish 50/75/100/120/150 Hz. Existing Surface.setFrameRate hints remain seamless-only; no non-seamless mode switch or claim of physical video scanout. Android TV mode and independent physical presentation acceptance remain OPEN.

**P1.3:** Existing multiple-decoder visible-tile budget is now an explicitly testable shared thermal policy, preserving its 0.75× MODERATE, 0.5× SEVERE, critical main-video-only limits, and original single-decoder behavior. App notifications disclose the impact at MODERATE/SEVERE/CRITICAL and on recovery, also when starting warm. Explicit requested resolution caps, HEVC source quality, DRM and Cast wiring are not changed. No verified thermal improvement or automatic quality negotiation is claimed.

**P2.6:** The controlled OFF/MAP/TOWER/BOTH Perfetto comparison rejects duplicate or overlapping samples and mismatched exposure durations; Python regressions check these fail-closed rules. Equal thermal state, feed count, actual video-layer FPS, 1×/1.5× and 10-minute runs still require physical-device recordings, and app-window FrameTimeline is not independent video FPS.

**Strict accounting:** This is a SINGLE candidate commit, awaiting exact-source GitHub CI. Pre-push Android Gradle and real F1 hardware testing are unavailable in this session; Kotlin/compiler, HEVC fixtures, Python scripts, artifact/signature and emulator claims are deferred to CI. Until CI verifies and the source is reassessed, retain **P1.1 85%, P1.2 60%, P1.3 55%, P2.6 87%, aggregate 81.875% coding** and strict release gates **1/16 closed**. No claim that Build815 BufferQueue timeouts, codec PTS correlation or 46.1% slow app UI frames are fixed.

## Build 815: 23 November 2026 real S22 Ultra phone log + three Perfetto traces (physical gates NOT CLOSED)

**Grounded evidence:** User supplied `pitwall-20261123-125446-075.log.txt` (SM-S908E, Android 16, app 1.0.1630, local log 18:24:45–18:34:27), and three actual on-device Perfetto `.perfetto-trace` captures inside zipped files ending **18:26:37**, **18:31:51**, and **18:33:37**. The archives are valid native Perfetto protobuf traces; the primary `FrameTimelineEvent` actual-surface records and end cookies were decoded directly with their boottime timestamps (no fictional trace-processor SQL results). This tests the genuine Build815 phone playback, **not TCL, protected L1 or Pitwall Cast**. The user's physical visual observation is that playback, tower and map look correct and smooth.

**Functional findings:** Both map and timing tower became usable on first session in **508 ms** (22 ranked driver rows, 22 positioned cars) and on subsequent reinitialization in **881 ms**. Scene telemetry exercised **1, 2, 3, 6, 8, and 10 feeds**, with OFF/map/tower/both arrangements, including TV layout on phone, clear authorized archival CMAF/HEVC via `c2.qti.hevc.decoder`, one output texture and a single active composed decoder. No fatal exception/ANR/OOM occurred in this capture. The `drm=L3` capability field and discovery of a Widevine factory **do not establish secure/protected playback**; `keysLoaded=false` was logged in the observed composed archive examples. Cast=false throughout. Logged playback speed samples show **1.0x and 1.1x**, **not** a verified 1.5x run.

**Video stage evidence, not physical scanout:** Across 48 five-second `SINGLE_DECODER_SCENE` snapshots the logger counted **9,358 codec metadata callbacks**, **9,315 decoder texture latches** and **9,100 vsync-driven EGL draw submissions**. Active steady windows cluster around 250 latches and roughly 244–250 EGL draws per five seconds, consistent with the reported smooth-looking video. Only **7 swaps >18 ms** and **0 texture latches >18 ms** were logged, with 20 callback gaps >45 ms and 27 GL-display gaps >45 ms. Critically, **codecPtsMatched=0**, **codecPtsLateMatched=0**, **codecPtsMissingAtLatch=9,315**, and the outstanding fixed ring was repeatedly full at 128: the exact Media3 PTS-to-SurfaceTexture timestamp correlation has no usable exact-Picture evidence on this phone. Because the implementation deliberately refuses to guess across timestamp domains, these are honest unmatched values, **not proof that the user saw zero video frames or a confirmed bug in the media decoder**. Further raw PTS/timestamp-domain and SurfaceFlinger output correlation is needed to establish real video-layer scanout.

**UI window measures:** 204 `PitwallFrames role=player` windows contained **19,360 app-window frames, 8,929 classified slow (46.1%)**, **0 >700 ms frozen frames** and a **628 ms** single-window maximum; display 120 Hz. The project’s <5% slow UI-frame criterion **fails**. This is not a per-video-picture drop count. Process-wide JVM used heap peaked **292 MiB**, native allocated heap **91 MiB**; recorded `thermalStatus` reached **3 (SEVERE)** in the last three frame windows near 18:33:45–49. The 105 concurrent composition-segment measurements averaged about **102.6 MB of *process-wide* allocations per segment** and 2.58 process GCs during sampled segments; these values must not be attributed solely to the composer or overlay without allocation stacks.

**Independent SurfaceFlinger FrameTimeline (actual app-window surface):**
- Perfetto 18:26:37: 81.15 s trace, dominant Pitwall activity layer **3,891 actual surface frames**, **3,638 late (93.5%)**, **20 dropped**, actual-frame-span p95 **20.92 ms**. Single-feed map/tower transitions are included.
- Perfetto 18:31:51: 167.09 s, dominant app-window layer **7,837 frames**, **6,730 late (85.9%)**, **12 dropped**, p95 **26.29 ms**. Contains multifeed 2/3/6/8/10 ramp, primarily with overlays OFF.
- Perfetto 18:33:37: 79.93 s, dominant app-window layer **4,620 frames**, **4,491 late (97.2%)**, **8 dropped**, p95 **32.39 ms**. Includes eight-feed OFF, tower, map+ tower and TV-layout transitions.
The native jank reason bitmasks show frequent `JANK_BUFFER_STUFFING` and `JANK_APP_DEADLINE_MISSED`, not merely an app-log heuristic. **These are main app-window (including TextureView composition) surface frames**, not an independently isolated decoded-video surface; do **not** describe their p95 or present-late ratios as 50fps video-layer cadence or observable perceptual stutters. The test changes layout/feed count during recording, so these three traces are **not controlled A/B comparisons**.

**BufferQueue evidence:** The app log contains **647 actual timeout lines and folded timeout-summary lines**, approximately **5,910 occurrences including summarized repeats** across `MediaCodec.release`, BLAST `SurfaceView` and `SurfaceTexture` queue names. Typical reported `dequeuedCount` values are **20–23 with acquiredCount=0**, often `mDequeueTimeout=0` (an immediate no-free-slot failure, not necessarily a blocking delay). They appear even when map and tower are OFF. Neither the logs nor the available FrameTimeline surface attribution identifies the true producer/consumer causal chain; **do not blame the map/tower or 'fix' this by removing log lines, hiding decoder errors, adding insecure output, changing Widevine or disabling frames**. Need trace-driven buffer lifecycle attribution and comparison with EGL/codec release timestamps.

**P0.4 overlay draw-stage evidence:** The overlay instrumentation's median five-second-window mean is around **0.21 ms** for the 10Hz track-position sampler, **1.63 ms** for track car/flag drawing, **0.49 ms** for retained tower-row drawing and **1.75 ms** for tower sampling; occasional maxima include **29.11 ms** for track draw and **14.56 ms** for tower sampling. Cache/retained implementations are genuinely exercised on phone. Their individual typical timings alone do not establish low overall UI jank, maximum optimization, or TCL parity. In the sampled eight-feed FEATURED configuration **without** map or tower, app slow-frame fraction was already high; comparison with other layouts is confounded by run order, thermals, feed count and interaction.

**Acceptance verdict:** **Phone feature/visual smoke test provisionally PASS**; **P0.3 video-performance and P0.4 overlay/UI-performance release gates remain OPEN / measurable target not achieved**. Keep **100% coding / 0% previously known coding pending** *only as the exact-green Build815 source accounting*. The real-device PTS correlation blind spot and persistent UI/BufferQueue/thermal behavior are evidence-led follow-ups, and any traced production-code defect must explicitly reopen the relevant coding gate. Do not relabel P0.3/P0.4 physically accepted or call the rendering 'maximally optimized.' Other 14 workstreams and aggregate unweighted **81.875% coding** unchanged; release gates remain **1/16**.

**Next validation, in priority order:** (1) TCL 55C655 with **Software DRM ON**, first one authorized feed + tower + map, capture identical app log + Perfetto trace and DPAD behavior; (2) controlled 10-minute S22 OFF/map/tower/both steady sessions at consistent feed count and actual **1.0x/1.5x**, record standardized jank/GC/thermal/video-presentation metrics; (3) instrument actual Media3 PTS versus SurfaceTexture timestamp domains once per epoch and tie source callback, EGL and SurfaceFlinger layer/tokens to app scene on-device without content readback; (4) classify BufferQueue producer surfaces by real generation/lifetime and correlate no-free-slot status with frame latching, GL scheduling and decoder state, not merely fast EGL swap durations; (5) separately test protected authorized Widevine HEVC and genuine Pitwall Cast sender/receiver on the TCL. No new build should be triggered solely by this documentation update.

## Build 815: P0.3 video rendering + P0.4 map/tower coding completed (EXACT SOURCE GREEN)

**Verified result:** [GitHub Actions Build 815, success](https://github.com/cooldarshak/apexview/actions/runs/45160518877), production SHA `742dac94d42054a43a2ad349ab920a00d0c874fa`. The entire CI job completed successfully: change contract, production Kotlin and AndroidX macrobenchmark debug/benchmark Kotlin compilation, executed JVM and Python regression suites, JavaScript/Cast protocol checks, standalone HEVC/tile/CMAF decoder comparisons, signed app/debug/benchmark target and runner APK assembly, and mandatory emulator RenderNode/SurfaceDecoder instrumentation. Published artifact names include `apexview-custom-debug-apk`, `apexview-hevc-single-decoder-instrumentation`, `pitwall-benchmark-target-apk`, `pitwall-macrobenchmark-instrumentation` and `pitwall-rendernode-instrumentation-report`. Build 814 remains a historical failure; its source cause was corrected and its original assertion passed in this green workflow. No test was weakened, omitted or hidden to declare success.

**P0.3 coding 100% / 0% pending:** Both actual clear TextureView and secure/protected SurfaceView one-decoder scene pipelines and the Pitwall Cast receiver retain EGL lifecycle recovery, cadence, codec-frame-to-texture-to-EGL diagnostics and reusable native scene buffers. Bounded PTS correlation supports late codec metadata on a separate looper, rejects metadata with a planned release more than 40ms after a stale texture's latch, preserves valid up-to-40ms scheduling lead, and distinguishes exact matches, missing-at-latch, late matches and pending unmatched history. Surface/seek/rebind/atlas lifecycle clearing, device-runnable seek/recreate instrumentation and executable JVM tests pass. All playback authorization, Widevine protection, single-/multiple-decoder selection, mobile/TV, Cast, captions, seek and 1x/1.5x behavior remain preserved. **This is source/CI closure only, not a finding that BufferQueue stalls or real 50fps video-layer jank are cured.**

**P0.4 coding 100% / 0% pending:** The real local track and edge maps use retained mutable 60Hz draw-only driver markers, immutable 10Hz static/hit-target and Cast snapshots, stable in-place map-edge sorting, independent flag animation and native cached tower regions. Shared edge-map Ribbon corner and normal projection no longer allocates a `Pair` per car/label draw; executable parity exercises all segments, rounded corners, both travel directions, wrap, narrow portrait and wide landscape. The physical benchmark runner compiles/assembles for real `FrameTimingMetric` sampling and does not include synthetic F1 credentials/media or insecure DRM capture. Device benchmarks themselves have NOT been run.

**Precisely separated acceptance:** Both **coding workstreams CLOSED**, both **physical release gates OPEN**. Before release sign-off, run actual authorized playback for clear and protected L1/L3 HEVC, 1x/1.5x, 1/4/6 feeds, repeated seeks/surface recreations, and Pitwall Cast on S22 Ultra and TCL 55C655 (Software DRM ON); capture 10-minute UI/GC/thermal and independent SurfaceFlinger/FrameTimeline/BufferQueue/decoder traces in controlled OFF/map/tower/both scenarios. Debug/emulator green cannot establish 50fps on-screen presentation, source-to-scanout latency, license behavior, device audio sync or UI slow-frame target. New physical evidence of a code defect reopens its coding line; no speculative code or fake 100% hardware acceptance.

**Ledger after exact-green Build815:** P0.3 **100% coding / 0% pending**, P0.4 **100% / 0%**. P0.1, P0.2 and P2.8 already remain 100% source coding. Across 16 workstreams, unweighted **81.875% coded / 18.125% pending**. Strict release gates **1/16 closed, 15 open**. This plan remains the ONLY active release/workstream plan. The single documentation-only reconciliation commit does not change compiled source, and `docs/**` is explicitly ignored by the Android debug workflow to avoid an unnecessary Build816.

## Build 814: codec PTS correlation reused-timestamp regression (one root cause)

**Exact-source result:** [Build 814](https://github.com/cooldarshak/apexview/actions/runs/45159589957), source SHA `5536a44832c727d079beed75189f396dd9dce8bd`, **FAILED** at the executable JVM stage. The complete debug and macrobenchmark Kotlin variants COMPILED and **754 of 755 JVM tests passed**, including new Ribbon geometry/parity tests and the new late-metadata tests. The only failure was the *existing* `SceneCodecTimestampCorrelationTest.seekAndSurfaceRecreationDiscardOldCodecReleases`: expected 1 unmatched latch but observed 2. HEVC fixture and JS checks before Gradle passed; Build814 did NOT reach signed APK or emulator instrumentation.

**Production root cause, not a test expectation problem:** An earlier unmatched texture with PTS X at time T remained in the new bounded early-latch history; another codec frame with reused PTS X was scheduled **50ms after T**. The matching loop attached that newer metadata to the impossible older latch solely because PTS matched, leaving the legitimate later texture unmatched. Correct production correlation conservatively: late metadata may match the exact earlier PTS only if its planned release is not more than **two source frames (40ms)** after the recorded latch. A 20ms lead remains permitted for decoder scheduling variance; large positive leads stay in the normal metadata ring and pair with their actual later texture. Do NOT weaken the original test or claim that codec releaseTimeNs is an observed physical release. Extend JVM regressions for *both* incorrect reused PTS and valid modest scheduling lead, along with the original 755-test suite. This focused source/test/plan correction is the only follow-up build; preserve all other rendering/DRM/Cast, map/tower and macrobenchmark changes.

**Accounting until CI green:** Last verified green remains Build 813. P0.3 80% / 20% and P0.4 92% / 8% in the last-green ledger below. Reconcile both to 100% coding only after the corrected exact-source Kotlin, JVM, HEVC/CMAF, signed APK and emulator run passes. Device performance, physical video presentation, protected Widevine/TCL and frame/GC acceptance remain open release gates.

## Post-Build 813 coordinated P0.3 and P0.4 source closeout (exact-source CI pending)

**Verified baseline:** Build 813, commit `698d7464ce39a7af5e094d4c14ffb4425d40583d`, is GREEN. Its workflow genuinely compiled both variants of the AndroidX Macrobenchmark runner, assembled the matching nondebuggable app, ran the 750 existing JVM tests, exercised the clear composed-HEVC emulator path and published validated debug APKs. This resolves the prior Build808–812 CI blockers, not the user's S22/TCL physical-performance acceptance.

**P0.3 final code-bound defect found in full frame-path audit:** Media3 VideoFrameMetadataListener and SurfaceTexture frame-available callbacks execute on different loopers; frame metadata can arrive **after** its image has been latched. The existing fixed 128-slot codec->texture PTS correlator immediately marked that PTS unmatched and would never join a late arriving callback, giving misleading diagnostics for source/BufferQueue analysis. Preserve the original early-callback ring; add a separate **bounded** 128-slot early-texture history, reconcile *exact* PTS arriving in either order, retain pending matches across five-second logging boundaries, and wipe both histories on seek, atlas/surface epoch and release. Record metadata-missing-AT-latch and late-match counts separately; never invent a negative codec-callback-to-latch latency. Both actual clear TextureView and protected SurfaceView/Cast scene logs expose exact match, missing-at-latch, late-match and outstanding counters, while EGL submission is explicitly **not** physical SurfaceFlinger presentation. Executable JVM tests cover late metadata, callback-order inversion, no false latency, seek epoch, mismatched PTS, bounded eviction and logging windows. No player, decoder, media authorization, Widevine security, 1x/1.5x pacing or Cast wire-protocol behavior changes.

**P0.4 final retained-draw bottleneck found:** The edge-map Ribbon.point() and Ribbon.inward() previously built and discarded a Kotlin `Pair<Offset, Offset>` **every invocation**, including per-car frame draws and label avoidance. Preserve the exact rectangular/rounded segment and corner math but return packed Compose `Offset` primitives directly, without intermediate `Pair` objects, for both local edge map and receiver's shared EdgeDrawingContent. Executable pixel/normal parity against the previous algorithm covers forward/backward lap direction, wrap, zero radius, tight portrait, wide landscape and corner boundaries. Native tower/flag display-list caching, 10Hz hit targets and 60Hz mutable Canvas markers remain unchanged.

**CI/source accounting:** The old table below remains the last committed green-baseline ledger until the next exact-source workflow passes. When the coordinated implementation and all its executable regression/HEVC/benchmark packaging/emulator gates pass, both workstreams can be closed at **100% coding / 0% known code left** without inventing hardware results. Keep strict P0.3/P0.4 physical release gates OPEN for 10-minute S22+TCL+Pitwall Cast, clear and authorized Widevine, 1x/1.5x, 50fps SurfaceFlinger FrameTimeline/BufferQueue, independent video-layer cadence, quality/seek, and controlled OFF/map/tower/both frame/GC/thermal acceptance. Any new physical trace proving a code defect reopens the affected coding item. No synthetic or emulator test constitutes secure-device release acceptance.

## Build 812: change-contract gate caught missing benchmark regression test

Build 812 ([CI failure](https://github.com/cooldarshak/apexview/actions/runs/45156625259)), SHA `04adf49e5b453c5ca3d902b17a8e47f7f14fbe1b`, stopped at the existing **mandatory coordinated-change contract**, before Android SDK/Gradle. The change introduced updated benchmark API wiring and the GitHub Actions workflow but omitted a modified automated regression test *in that commit*. The gate correctly printed `PITWALL_CHANGE_CONTRACT=FAIL Production changed without a changed automated regression test`. Do not disable, weaken, or bypass the gate; Build 812 proves nothing about the compile correction.

**Coordinated correction:** Add an explicit `scripts/test_pitwall_production_wiring.py` regression covering the documented `androidx.benchmark.macro.junit4.MacrobenchmarkRule` import, `ExperimentalMacrobenchmarkApi` opt-in, preserved `CompilationMode.Ignore`, real `FrameTimingMetric`, distinct early `compileDebugKotlin` and `compileBenchmarkKotlin` CI tasks, the non-debuggable benchmark target, and publication of both physical-benchmark APKs. This source-contract regression complements, but does not replace, the **real executable benchmark Kotlin compile tasks** and physical device performance runs. Commit this test and the sole consolidated plan together. All 16 workstream percentages and 1/16 strict release-gate count remain unchanged until full source/CI and physical evidence justify a change.

## Build 811: full 750-JVM suite passed; Macrobenchmark API compilation failed

**Exact-source verification:** [GitHub Actions Build 811](https://github.com/cooldarshak/apexview/actions/runs/45154686544), production HEAD `353544d01a6e3aa7f7354e6bb9ccf82df1e6e8d8`, is **RED**. It passed Python source contracts, Cast JS/protocol, production `:app:compileDebugKotlin`, **all 750 executed `testDebugUnitTest` tests** and the independent HEVC/CMAF validations. It then stopped in `Assemble debug APK` when Gradle compiled the newly added `:macrobenchmark` module: `PitwallFrameTimingBenchmark.kt` imported `androidx.benchmark.macro.MacrobenchmarkRule`, but AndroidX puts this class in **`androidx.benchmark.macro.junit4.MacrobenchmarkRule`**. The unresolved type then caused knock-on missing `device` receiver and argument diagnostics. The module also used `CompilationMode.Ignore` without the required `@OptIn(ExperimentalMacrobenchmarkApi::class)`. **No APK or emulator completion is claimed for Build 811.** These are avoidable compile-gate omissions in a new testing module, not observed S22 or TCL playback defects.

**Coordinated API + CI correction:** Use the documented JUnit4-rule import and explicit opt-in while retaining `CompilationMode.Ignore`, which preserves the user's already-playing authorized session rather than changing compilation/profile state between playback measurements. Move **both debug and benchmark-variant Kotlin compilation into the same early unit-test Gradle gate**, before slow HEVC/APK preparation. In the existing later single APK assembly step build **both** the signed nondebuggable target `:app:assembleBenchmark` and `:macrobenchmark:assembleBenchmark` alongside the unchanged shipping debug APK and existing Android instrumented APK; publish the *target and instrumentation separately* for actual physical `FrameTimingMetric` runs. The CI benchmark module is an additional test module in the same project, not a separate branch, product or decoder. All existing app, protected output, one/multi decoder, 1x/1.5x, TV, Cast, map/tower, DRM and controls must be preserved.

**Progress accounting:** Build 807 remains the latest fully green whole-workflow baseline until a later exact-SHA run succeeds. The source changes do **not** prove P0.3 or P0.4 at 100% coding yet: re-audit production integration and actual executed gates once the new benchmark assembles, then keep device measurements and strict release acceptance separate. Strict gates remain **1/16 closed**.

## Build 810 JVM test attribution correction and historical BufferQueue audit (pre-verification)

Build 810 ([failed run](https://github.com/cooldarshak/apexview/actions/runs/45153629092)), exact SHA `100a4b3b810c026b936089cdd637954fe1e42e42`, passed Python production wiring, Cast JS/protocol, **all production Kotlin/Compose compilation** and 749 of 750 executable JVM tests. The sole failing test was the new `SceneCodecTimestampCorrelationTest.fixedRingEvictsOldFramesButPreservesAcrossLogWindows`: its intended *unmatched* final PTS `6_100_000_000ns` was in fact the **valid final stored frame** of the 256-frame fixture; one input PTS was evicted, while the other legitimately matched. The old assertion (2 unmatched) was wrong. The corrected test requires **one unmatched evicted PTS AND one exact matched retained PTS**, proving the intended ring behavior rather than modifying production matching or hiding the mismatch. No Build810 signed APK, benchmark compilation or emulator pass is claimed. This failed test was the only failed JVM test in Build810.

The same correction clarifies production metrics, **without changing frame rendering**: the Media3 `releaseTimeNs` metadata is the *planned codec release timestamp*, **not an independently measured hardware release**. Rename the clear and protected EGL logs to `plannedCodecReleaseToLatchP95UpperMs` and add the already-recorded `codecCallbackToLatchP95UpperMs` plus `codecFrameCallbacks` count. Keep exact/missing PTS match totals, EGL swap timing and `physicalPresentation=NOT_MEASURED`. This avoids falsely attributing physical presentation to planned renderer/codec timestamps.

**Historical device evidence investigated, not a claim for Build810:** The S22 log `pitwall-20261123-070839-570.log.txt` (pre-807 capture) contains **154** `waitForFreeSlotThenRelock` occurrences/repeat summaries, chiefly `dequeuedCount=21` (128 records) with `acquiredCount` 1 or 2; a later `MediaCodec.release` queue has `dequeuedCount=23` (26 records). All report `mDequeueTimeout=0`. Multiple `SurfaceTexture-1` generations appear. At 12:40:51.574 the older clear `SURFACE_DECODER_SCENE` reports 236 submitted frames in the five-second interval with max EGL swap 5ms and max draw 4ms while producer queue warnings persist. These facts **rule out a blanket inference that every timeout corresponds to a slow EGL swap**. They do not prove driver producer ownership, SurfaceTexture stuck-acquisition or SurfaceFlinger presentation as the unique root cause. The same historical session includes substantial allocations/GC and UI missed frames even with `map=OFF`, so the overlay path alone cannot explain all playback jank. The new codec/latch/GL counters, context-safe teardown and mandatory repeated surface recreation/seek checks provide stage attribution, but an exact-source **real Perfetto producer/consumer/FrameTimeline/ART trace** on S22/TCL is still required before asserting that these historical timeout patterns are gone.

**Current status:** Build 807 remains the last exact-GREEN baseline until the next coordinated exact-SHA run succeeds. P0.3/P0.4 source coverage expanded but coding completion must not be marked 100% solely because production Kotlin compiled or a historically green device path had no regression. Strict acceptance gates unchanged: **1/16 closed**.

## Build 809 Kotlin failure and consolidated renderer/Cast correction (CI acceptance pending)

Build 809 ([failed workflow](https://github.com/cooldarshak/apexview/actions/runs/45151975862)), exact branch SHA `26bf9539d4ff54799b81d13edb823ab2f41b70ea`, PASSED the corrected Python contracts, HEVC fixture preparation and Cast JS checks but **FAILED** at `:app:compileDebugKotlin`. The first Kotlin diagnostic is `cast/CastTimingLayer.kt:134:47 Inapplicable candidate: fun carsAt(position: Long): List<Car>`: the previous draw-source callback migration assumed the local mutable marker type was shared with Cast, but Cast must preserve its immutable packet car list. The initial implementation missed this production call site. No APK or performance evidence was generated by Build 809, and no historical failure is reclassified as green.

**One coordinated correction and full call-site audit:** Define `OverlayMotionCar` as a read-only geometric/state interface, implemented by both immutable `Car` and session-owned mutable `OverlayDrawCar`. `OverlayMotionSource` again accepts a covariant `List<OverlayMotionCar>` provider so the unchanged receiver `frame::carsAt` and both new allocation-bounded local `drawSampler::sampleForDraw` callbacks are compiled and rendered by the exact same track/edge Canvas routines without Cast byte/format/protocol changes. Update `OverlayEdgeProjection` stable retained sort, both map draw consumers, and all Python wiring contracts. New executable JVM regression covers immutable Cast and mutable map marker sort/state parity with stable ties.

**P0.3 cleanup and lifecycle instrumentation, both clear and protected:** In both real GL renderer destruction paths, bind the owned EGL context on its GL thread *before* deleting GL shader/texture handles; if the surface/context is gone, do not issue GLES calls against a foreign context, and rely on EGL teardown to reclaim objects, with explicit warning. This fixes a source-verified lifecycle hazard not contingent on an invented BufferQueue diagnosis. Extend the real HEVC emulator SurfaceDecoder instrumentation to release/recreate its SurfaceView and seek the **same** single Media3 decoder, checking actual pixels and concurrent decoder count with an actual `VideoFrameMetadataListener` PTS source; preserve its existing mandatory single-test CI footer. Extend the physically runnable clear TextureView scene instrumentation to reattach after a seek and prove the single-decoder invariant. Neither test bypasses Widevine, and the unprotected HEVC emulator cannot certify genuine L1/L3 DRM hardware.

**Source readiness and evidence rules:** Preserve all Build807 green workstream figures (P0.3 80%, P0.4 92%) as last *validated* baseline until the new exact-source compiler, HEVC/CMAF validation, executable JVM map/codec tests, benchmark module assembly, signed APK and emulator gates succeed. P0.3 and P0.4 are candidates for 100% **coding only** if the coordinated source tests compile and run with no remaining identifiable coding gaps. A BufferQueue scheduling causal finding, 50fps physical FrameTimeline, protected TCL/S22 playback, real Cast and 10-minute less-than-5% slow UI frames are **separate physical release acceptance** and cannot be asserted from green CI. Do not close any of the 15 open release gates.

## Build 808: first-failure diagnosis and coordinated correction (CI required)

Build 808 ([failed run](https://github.com/cooldarshak/apexview/actions/runs/45151284560)) was triggered from the single 22-file coordinated source SHA `632d3b780b5e3530b833e33d17abbbba2851a99d`. It reached Python validation but **did not** reach Kotlin/Compose compilation, unit tests, HEVC/CMAF or the signed APK/emulator gate. The first failure is **two stale `scripts/test_overlay_runtime_wiring.py` string assertions**: they still require the old `drawSampler::sample` map callback and old three-argument scene `bind`, while the real production source correctly uses `drawSampler::sampleForDraw` and the four-argument codec-correlated bind. This is a contract-test migration omission in the initial coordinated commit, not evidence of a player or device performance regression. Do not rerun unchanged Build 808 or classify it as passed.

**Correction batch:** Audited the other Python wiring tests for the changed symbols. Updated both checks to assert both production map variants, both clear/protected Viewer bind sites, the immutable map/receiver sampler split, real Cast `VideoFrameMetadataListener`, codec latch/swap accounting, and correct receiver bind calls, without weakening the assertions. Additionally repaired a live-timing edge case discovered in the full draw path re-audit: while the playback clock is paused, `SessionTiming.positionsFromUtc` may change as archived/live positions arrive at the *same timestamp*. Retained draw-only markers now invalidate on the `beforePositions` value as well as time, matching the immutable published sampler. New executable Kotlin test covers the change from empty to populated and back without advancing the clock. This source+test+plan correction is atomic and awaits a fresh exact-source CI run. All workstream source/physical/release accounting remains separate; no arbitrary green/100% claim.

## Post-807 coordinated P0.3 + P0.4 coding audit (source prepared, CI verification pending)

**Provenance and strict separation:** Base HEAD `be920a5913f1c1d1b3b3392d568c7e3b77477b8c`, last green Build 807 source `5dccdabe91b3bd0530c64176b2db57149d16eb40`. This entire source batch is prepared as ONE production / benchmark / executable-test / plan commit. It is NOT a verified build or proof of improved physical frame timing until the exact source runs through GitHub Actions. Do not close the release gates or invent a before/after S22, TCL or Cast measurement. Keep the dated Build 807 ledger below as the last GREEN baseline until a later evidence-only reconciliation.

**P0.3 production path:** Added `SceneCodecTimestampCorrelation`, a bounded fixed-size ring joining Media3 `VideoFrameMetadataListener` PTS/release callbacks to exact `SurfaceTexture.timestamp` latch events and EGL swap submission, tracking unmatched OEM timestamp domains rather than inventing matches. Ring is inactive when no scene is attached, shared by the real FeedPlayer -> Viewer clear/protected scene and directly authorized receiver ExoPlayer -> native Cast scene, not merely a test-only utility. Scene bind/seek/atlas/quality/surface/stop lifecycle resets stale PTS; a newly valid surface epoch can retry previously failed EGL initialization without bypassing security. Existing Choreographer 50fps/1.5x gating, one decoder, protected Widevine output, buffer-demand preservation, audio, captions and controls are retained. New JVM executable tests cover exact PTS match, coalesced/unmatched, bounded-history eviction, seek and recreation. The correlation ends at `eglSwapBuffers`; true FrameTimeline/SurfaceFlinger presentation and the cause of any `waitForFreeSlotThenRelock` bursts still require physical trace attribution, not an assumed fix.

**P0.4 production path:** Kept existing immutable 10Hz hit-target/tower and Cast position snapshots, but replaced the *draw-only* 60Hz map/edge-map per-frame `Car` objects and `ArrayList` allocations with session-owned mutable `OverlayDrawCar` markers and one reusable list. Each Canvas consumes markers synchronously; mutable objects NEVER escape to Compose publication or LAN/receiver protocols. Edge-ribbon stable in-place progress sorting has its own retained draw markers and primitive positions, preserving label ordering/overlap, sectors, flags, safety car, pit, selections, colours and accessibility. Real tests check 24 cars over 50 continuous frames, backward seek, missing track intervals, stable tie ordering and immutable snapshot independence. The existing single-Canvas tower RenderNodes, Offscreen mode and timing/speed/tyre/sector data are retained without a speculative performance-regressing graphicsLayer change.

**Executable physical benchmark harness:** Introduced a separate `:macrobenchmark` *test module within this existing Gradle project*, release-equivalent nondebuggable `benchmark` application variant with benchmark-only `profileable` manifest, and `FrameTimingMetric` tests for stable playback, player/DPAD navigation, orientation/layout and playback/seek gestures. The existing GitHub Actions APK compile step now also compiles and publishes the benchmark instrumentation APK; physical execution is intentionally NOT confused with emulator performance. Run tests on authorized, already-playing devices, repeating identical video-only, map-only, tower-only, both, 1x/1.5x, clear/protected, single/multiple and Cast where supported. No credentials, F1 media or DRM data embedded. Macrobenchmark measures app UI frame timing, while independent Perfetto FrameTimeline tracks video SurfaceView composition.

**Pre-CI ledger:** Last GREEN remains P0.3 80% coding/20% pending and P0.4 92%/8% (Build 807). Source-level provisional estimates after this coordinated unverified batch are P0.3 **96%** and P0.4 **98%**, *not* green, with 4%/2% pending respectively until the full code/test/benchmark module compilation and production integration audit. Do NOT mark 100% based on test filenames or source search. After exact-SHA CI, reassess actual remaining coding gaps; distinguish source closeout from S22/TCL/Cast physical data and release gate. Device-specific scheduler/BufferQueue causes must remain unverified until the user supplies comparable traces. All other 14 workstreams and **1/16 release gates** are unchanged.

**Device runbook:** Keep the release-equivalent benchmark app installed with the same signing key; open an authorized playing viewer or local Pitwall Cast receiver BEFORE each targeted benchmark class/method. Execute `./gradlew :macrobenchmark:connectedCheck -Pandroid.testInstrumentationRunnerArguments.class=app.apexview.macrobenchmark.PitwallFrameTimingBenchmark#playbackWithSelectedOverlayConfiguration` on a connected real phone or TV, never classify emulator results as hardware performance. Repeat for each overlay configuration and playback mode. Preserve each JSON trace and independent codec/EGL + BufferQueue/FrameTimeline/ART scheduling trace, collect p95/p99 UI and source cadence, slow and frozen counts, physical presentation and 10-minute GC. The target is under 5% S22 slow UI frames with no frozen steady frames; these are NOT yet measured on the new source.

## Build 807: consolidated P0.3 video / P0.4 timing-overlay engineering pass (exact-source CI GREEN)

**Verified starting point:** Build 806 green SHA `a965218c3762eb2a2bdcd87909315c7c96085ab8`, with the documentation-only HEAD `b68d6775c4c39a872e0099c50351f4f4fb00d765`. This source revision implements **both workstreams in one atomic production commit with tests**, not an independent sequence of small APK builds. Exact-source Build 807 CI is the next acceptance check.

**P0.3 video pipeline: coordinated source changes.** Audited the existing GLES/SurfaceTexture scene classes, both `TextureView` clear-archive one-decoder rendering and protected/clear `SurfaceView` one-decoder rendering (including native TV and Pitwall Cast receiver), `SceneFrameDemand`, `SceneFrameCadenceGate`, `SceneFramePacing`, and existing output-surface instrumentation. Addressed two production risks: (1) a temporary missing EGL output after `beginFrame()` previously consumed pending picture demand, so a paused/seeked frame could fail to be redrawn until another decoder callback; preserve the undrawn demand until an explicit later surface/bind/video event **without** a Choreographer retry-spin; (2) protected/clear `SurfaceView` texture callback did not explicitly `eglMakeCurrent()` before `updateTexImage()`, unlike the clear `TextureView`, relying on a context binding that output handoff could invalidate. Require and time the same existing EGL context before protected latching. Request the same nonblocking swap interval (0) on the protected surface, with the EGL default unchanged if the device refuses it; no security fallback or new decoder. Distinguish callback, EGL-bind, texture-latch, GL-submit, true `eglSwapBuffers`, Choreographer delay, and GL queue p95 in **both** renderer logs. Record unavailable output separately from codec drops, avoid misleading physical-presentation claims, retain 50fps/1.5x cadence and existing L1/L3 guards. New JVM tests prove the last picture survives a temporary surface loss, the stalled surface cannot spin, and unavailable EGL output is not counted as a decoded/presented frame.

**P0.4 map and tower: coordinated source changes.** Both track-map and edge-map moving dots remain exact 60Hz draw-only snapshots with 10Hz flag/hit-target metadata, no graphical or accessibility feature deletion. Previous safety-car drawing called `leaderAt(timing, now)` on **every draw** (60Hz, across up to 24 drivers), allocating temporary `CardState`, Pair and List objects for the entire tower. Resolve the same race leader once per 10Hz metadata state, and use the driver's indexed lap-position snapshot directly without allocating a `CardState`; preserve stable insertion-order ties, paused playhead, forward playback and backward seek. The shared `OverlayTowerSampler` replaces an O(n²) previous-row scan in diagnostics with a reusable, single-owner O(n) index; all lap/sector/tyre/speed and 24-driver order remain unchanged. JVM tests compare against the prior leader-min rule across seeks/ties and confirm 24-driver ordering, restart and immutable snapshot reuse. Python production-wiring tests explicitly cover both map styles, the real Kotlin scene paths, Cast-aware overlay entry points and EGL attribution.

**Cross-mode matrix and accepted limits:** Multiple-decoder playback still runs through its existing authorized Media3 players, and the shared map/tower and `WindowFrameDiagnostics` work applies unchanged; do NOT replace that path with the one-decoder scene. `SingleDecoderSceneView` remains clear archive only; `ProtectedDecoderSceneView` remains secure for L1/L3 as permitted and also handles already-authorized unprotected composed output/receiver. Phone/TV, clear/protected, direct and Pitwall Cast keep their lifecycle/selection/CC/focus paths. No DRM bypass, alternate media SDK, removed controls or hidden decoder fallback. Existing `TvTowerRow` Offscreen composition remains untouched because a prior ModulateAlpha experiment regressed physical UI results and no controlled device evidence supports another replacement.

**Coding-only progress (Build 807 exact-source CI GREEN):** P0.3 **80% / 20%**, P0.4 **92% / 8%**. Remaining P0.3 implementation is real per-device codec-to-FrameTimeline correlation where feasible, surface recreation/seek hardware instrumentation, and root-cause-informed BufferQueue handling once traces identify where delay originates; media-window metrics alone are not true video-layer presentation. Remaining P0.4 coding includes repeatable Macrobenchmark `FrameTimingMetric` and any profiler-confirmed draw/GC reductions, rather than pretending existing percentages prove UI smoothness. Do not conflate source completion with device acceptance or claim fewer than 5% slow UI frames without a controlled before/after.

**Hardware acceptance after one CI-green source revision:** On S22 Ultra record a comparable 10-minute sustained session at 1x and 1.5x and video only, video+map, video+tower, video+both under 1/4/6 feeds; capture app `PitwallFrames` jank/GC and independent video/SurfaceFlinger FrameTimeline, codec frame timestamp, EGL stage latency, source timestamp cadence, audio drift and queue stalls. Repeat on TCL 55C655 with Software DRM ON for clear/protected and both single/multiple decoders, then phone-to-TV Pitwall Cast, map/tower movement, focus, resize and captions. Compare controlled OFF/map/tower/both runs to the user-visible smoothness target. Existing historical Build 803 traces were not reprocessed in this commit; log symptoms are **not** proven P0.3/P0.4 root causes. **Strict release gates remain 1/16 closed.**

## Build 806 coordinated coding closeout: P0.1 / P0.2 / P2.8 (exact-source CI GREEN)

**Baseline:** [Build 805 GREEN](https://github.com/cooldarshak/apexview/actions/runs/45138949199), SHA `cd1bb093c2fa7c2fefaa3ceedf38a8de6e0509f6`; user physically confirms the revised portrait card sizing is ideal on S22 Ultra. No unverified device metrics or release sign-offs are inferred.

**P0.1 Segment priority: 100% coding / hardware gate OPEN.** Reviewed `HevcArchiveVodSession.segment/prioritizeSeek/fetch/shutdown` -> separate foreground/warm I/O lanes -> `ArchiveSegmentSingleFlight` -> `ArchiveSegmentDelivery` -> bounded byte cache -> `ResourceDataSource`, including shared producer, seek/close cancellation, bounded retries and cleanup. Found and fixed a real exception-classification bug: `SocketTimeoutException` inherits `InterruptedIOException`, but is a real failed network request rather than an expected seek cancellation. Wrapped causes, cancellations and actual timeouts are now distinguished. Added deterministic concurrent foreground-joiner recovery/ownership and timeout classification tests. **Coding acceptance PASSED: Build 806 exact-source CI.** Hardware confirmation still pending for 1x/1.5x seek p95, blocked prefetch/cold-cache and S22/TCL/Cast paths. No claim of measured latency improvement.

**P0.2 HEVC memory: 100% coding / hardware gate OPEN.** Reviewed clear archive bounded cache/read copies, 96MiB budget, cache eviction zeroing, protected output history budget and reader leases, clear composer NAL/sample cleanup and DRM isolation. Discovered a real exception-path gap: copied input initialization buffers were created outside the `try/finally` protecting composition, allowing premature feed construction or cancellation checks to skip explicit buffer wiping. New `OwnedArchiveInitializations` has exception-safe cloning and deterministic zero-on-close. Production now encloses feed setup/checkOpen/composition in its cleanup scope; JVM tests check detached ownership, exceptional cleanup and preservation of session-owned originals. **Coding acceptance PASSED: Build 806 exact-source CI.** Actual 10-minute process allocation/GC/thermal, 7/8-feed retention, S22/TCL, clear/protected and Pitwall Cast remain device validation. Build 805 heap instrumentation helps attribute jank but is not itself a memory-performance fix.

**P2.8 Material 3 Expressive: 100% coding / hardware gate OPEN.** Reviewed `BrowseScreens`, `BrowseLayoutPolicy`, `Sheets`, `Design`, `Viewer`, `Calendar`, `MainActivity` and Android TV receiver theme wiring; existing Compose semantics/role, 200% text, responsive viewport, held-key slider, reduced-motion and ChromeLayoutPolicy tests. Build 805's normal-scale portrait geometry and untouched landscape/TV sizing remain accepted. Discovered that at 175%-200% text, the card switched out of compact styling and accidentally reverted to a single-line title. Portrait title now consistently permits two lines; subtitle permits two at 175%+; narrow/large-font tests and production-wiring checks added. **Coding acceptance PASSED: Build 806 exact-source CI.** Device acceptance remains open for TCL DPAD focus and repeated keys, 200% text/TalkBack, player/overlay settings and Cast appearance.

**Completion semantics:** These three workstreams have no further known coding tasks after the coordinated fixes; Build 806 CI is GREEN, so their source coding is **closed at 100%** independently of hardware testing. Future regressions must reopen coding transparently rather than altering historical CI evidence. The release gate count stays **1/16 closed** until physical evidence is supplied. Separate P0.3/P0.4/P1.3/P2.6 issues own existing UI jank/GC/display pacing performance observations.

## November 23 Build 804 user review: portrait balance and app-window jank diagnostics

**Physical user report after Build 804:** The new portrait screenshot confirms the compact layout is too small. The request is a middle ground between Build 803's ~344dp oversized full-width cards and Build 804's ~151dp cards on a 384dp viewport. Landscape is already acceptable; do not alter it.

**Historical Build 805 source revision (CI passed):** On 320-412dp portrait viewports at default font scaling, set video card width to 60% and posters to 55% of usable shelf width (after 20dp sides), leaving approximately 1.5 visible video cards instead of one or over two. Expected at 384dp: 206.4dp video, 189.2dp poster. Raise portrait card headline from 15sp to 17sp and subtitle from 12sp to 13sp; restore 8dp card padding, 20dp radius, section heading to 19sp, 23dp section gap, 308dp hero and 30sp hero title. Keep explicit 175%-200% large-font full-card behavior and legacy landscape/TV dimensions. Respect remove-animations setting for the hero artwork crossfade. Add width, 200% font, tablet, landscape and production wiring tests.

**Previously documented Build 803 performance symptom, NOT an attributed root cause:** 2,287/9,965 slow application-window frames across 121 windows, max 623ms, 36 background concurrent mark-compact GC lines; Perfetto capture durations ~76.8s and 38.2s contain RenderThread, Choreographer, SurfaceFlinger and FrameTimeline but no reliably attributed per-app p95 reported. Original ZIP/log bytes were NOT mounted in this continuation; these figures were carried forward from the prior dated analysis, not reprocessed. They are NOT video decoder-drop measurements. No crash established.

**Coordinated observability improvement:** PitwallFrames now includes Java heap used/committed/max, native heap allocated, and layout/draw/sync/command/swap/GPU stage p95 upper-bin diagnostics every existing 2-second window. This helps determine whether GC or rendering stages correlate with jank without adding per-frame allocations or implying causation. Actual cause, video-layer FrameTimeline attribution, device comparison and before/after performance improvements remain OPEN under P0.2/P0.3/P1.3/P2.6. Do not credit a performance fix from new logging or card-size changes alone. Preserve logging privacy, video pipelines, map, tower, Cast, captions, single-decoder invariant and L1/L3 entitlement.

**Progress discipline:** Build 804 portrait was rejected, but Build 805 corrected it, passed CI and the user subsequently accepted the sizing on S22 Ultra. Material 3 coding closeout is recorded above; TCL/accessibility/release gates remain open. Strict release gate count stays 1/16.

## Samsung S22 Ultra Build 803 portrait browse regression: coordinated fix and trace review

**Observed November 23, 2026:** Real S22 Ultra on Android 16, version 1.0.1606. The uploaded screenshot shows a full-width oversized home shelf, hero/title visually colliding with words baked into the F1 artwork, and bulky section spacing. The user reports landscape is fine. This is a real P2.8 coding defect, not merely missing user acceptance.

**Root cause:** On a roughly 384dp portrait viewport, the previous BrowseLayoutPolicy selected a 250dp preferred video card, calculated one column, then expanded the card to 344dp. Hero used 320dp minimum and a 34sp title; cards used 17sp headlines and 28dp section gaps. The screenshot and actual source support this explanation.

**Coordinated implementation:** Portrait-only card width at 320/360/384/412dp now fits two complete video cards/posters and the beginning of another card. Density adapts smoothly on wider portraits. At 175%+ font scaling one larger accessible card is allowed. Only portrait uses smaller artwork cards, radius, padding, heading/title, hero height and section gap; hero artwork gets a stronger portrait-only gradient to separate pre-baked event lettering. Original landscape/TV math and focus, media, map/tower, captions, Cast and DRM are preserved. HOME_GEOMETRY gives actual phone width/fontScale/cardDp in future logs. Add policy regression tests and production-wiring check. Do not close CI acceptance before this exact SHA passes.

**App log findings:** 121 PitwallFrames windows, 9965 UI frames including 2287 marked slow, observed maximum frame interval 623ms, 36 background concurrent mark compact GC records, no FATAL EXCEPTION; no ARCHIVE_VOD_PREFETCH_STOP or ARCHIVE_VOD_DELIVERY_CACHE_RACE observed. These are not video decoder drop measurements, nor proof that the portrait sizing policy caused the GC load.

**Perfetto evidence:** Both uploaded binary traces were readable, showing Choreographer, SurfaceFlinger, RenderThread and FrameTimeline events. Primary boottime ranges approximately 76.8 and 38.2 seconds. App-specific p95 is not claimed without complete FrameTimeline attribution. Track remaining performance and thermal issues in P0.2/P0.3/P1.3 separately from this visual bug.

**Release:** P2.8 cannot be declared coding complete while a known portrait defect is present; physical visual confirmation and full device acceptance remain separate. Strict release gates remain 1 of 16 closed.

## Post-802 A–E review and coordinated P0.1 / P0.2 / P2.8 implementation (CI verification required)

**Starting point:** Build 802 green on `7b50e1ff99e6`. The GitHub source is the only implementation authority. One coordinated change addresses all five specifically audited findings; no independent micro-pushes and no artificial 100% completion.

| Previous finding | Engineering action in this change | Proof and limitation |
| --- | --- | --- |
| A. Silenced prefetch exceptions | Replace runCatching().isFailure with typed catch/diagnostics; classify routine cancellation separately, log exception **type only** without F1 URLs or secrets | Production archive path, JVM fault-recovery test, Python wiring check; device error-rate trend still needed |
| B. Unbounded cache-republish retries | Extract real ArchiveSegmentDelivery from HevcArchiveVodSession, cap cache eviction retries at 2 after initial composition, issue retryable playback failure instead of hanging; preserve single-flight and per-reader owned copies | Concurrent JVM tests and Android emulator tests confirm no starvation/zeroed returned samples; physical contention p95 remains open |
| C. Weak source-string wiring checks | Retain static contract checks but add executable shared production-delivery JVM/Android tests and assert source entry-point integration; do not misrepresent static tests as full runtime proof | Core archive delivery executed rather than only text inspected; DRM/Cast/full AppState matrix is still not instrumented |
| D. Partial instrumentation | Add mandatory archive-delivery Android instrumentation to existing RenderNode/SurfaceDecoder workflow; a failing assertion fails the whole build | 2 new Android-runtime tests run by CI. Real HEVC hardware decoder and protected F1 tests remain physical-only/unexecuted; **not** complete hardware sign-off |
| E. Stale plan checkpoints | Set Build 802 as dated verified baseline, retain all historical sections as history, add source/CI/device distinction and pending% table | Current top ledger below is the authoritative progress; historical statements do not supersede it |

**P0.1 additional source scope:** Observed cache ownership and explicit cancellation/failure classification now share an executable delivery coordinator. Background errors are visible without leaking exception messages or credentials. P0.1 remains open for real foreground 1x/1.5x seek p95 and exact-source CI.

**P0.2 additional source scope:** Archive preflight now populates the bounded cache without making a second full-sized fragment copy. Warmups never borrow a reader copy; foreground receives an independently owned buffer; Android/JVM tests verify retained original data and zeroed eviction. The bounded publication loop fails retryably rather than spinning. No claim of actual lower GC/thermal without 10-minute protected/clear/Cast physical logs.

**P2.8 additional source scope:** Player action layout accounts for pixels occupied by actual map/tower clearance when deciding whether to split into two rows. Add pure effective-width tests and a Robolectric 200%-font settings semantics/click test. Keep stable Material 3 Expressive, captions/Map/Tower/Cast accessibility and native TCL DPAD behavior. Physical focus, TalkBack and image review still outstanding.

**Progress discipline:** The last exact-source-green coded estimates are P0.1 **96% complete / 4% pending**, P0.2 **93% / 7%**, and P2.8 **92% / 8%**. These percentages stay unchanged while the coordinated source commit is unverified. If all relevant CI checks and the new real test gates pass, provisional **coding-only** estimates may rise to **98% / 96% / 95%** respectively. They must NOT be raised to 100% until the remaining source/hardware acceptance work is actually complete. Release gates remain **1/16 closed**, unchanged.

**Remaining acceptance work:** S22 Ultra and TCL 55C655 (Software DRM ON) physical 10-minute allocation/GC, 50fps and map/tower cases; one and multiple decoder paths; clear and authorized L1/L3 Widevine; phone-to-TV Pitwall Cast; 1x/1.5x seek p95, held-key focus, 200% font/TalkBack and signed update acceptance. The emulator cannot supply that evidence.

## MANDATORY HARD RULES: prevent recurrence of Builds 757–800 (effective after Build 801)

These rules apply to every developer or assistant editing Pitwall. This is the ONLY active project plan. Older notes below are historical checkpoints, not permission to ignore these rules.

1. **One coordinated change, one deliberate CI run.** Before any branch push, audit the full affected implementation and failure logs, prepare all call-site updates, real production wiring and matching tests together. Do NOT repeatedly push two- or three-line edits to discover compiler problems. Emergency hotfixes require a documented root cause and focused regression. A line-count quota is prohibited because it rewards fake code churn.
2. **Pre-push quality barrier.** Run the best available source-level preflight and relevant Kotlin/Compose/Gradle compilation, regression tests, HEVC/CMAF fixture checks, Cast tests and Python checks BEFORE pushing wherever a capable environment is available. When pre-push compilation is unavailable, explicitly report the limitation: the first GitHub run is verification, not proof of an earlier local check. Never label untested source validated.
3. **API migration is atomic.** A library, Kotlin, Android SDK, Gradle or Compose signature change must update imports, all call sites, related unit/instrumentation tests, dependency resolution and SDK provisioning in the SAME change. Check all branches of feature flags and all screen sizes, not just the edited UI element. The errors in 767–774 and 795–799 must not recur.
4. **Production integration is mandatory.** Trace entry point -> feature selection -> service/model -> consumer -> lifecycle/cleanup and recovery for actual app usage, not only for a unit helper. For affected behavior, review mobile, TCL Android TV with Software DRM ON, Pitwall Cast, SINGLE/MULTIPLE decoder, clear HEVC and authorized Widevine HEVC L1/L3, captions, map+tower, seek, 1.5x, quality and device focus. Keep single-decoder truly one decoder. FIX, DON'T DELETE. No fallback hiding an error, no bypassing entitlements/DRM.
5. **Required same-commit evidence.** Every production, build-toolchain or CI source change must update THIS plan and add/update at least one relevant automated regression test. The CI change-contract script enforces the co-change requirement, but a passing filename check does not prove the tests are meaningful. Reviewers must verify assertions cover the defect and the real call path. Only an explicit emergency exception in a PR review may waive this rule; no silent bypasses in CI.
6. **Fail-fast and no failed APK publication.** If Python, Kotlin, generated HEVC/CMAF, Cast, packaging, signature or emulator instrumentation fails, stop dependent steps and do not publish a distributable APK. Do not run a second Gradle compile after a failed test. Existing workflow success guards and branch-run concurrency must remain tested. Concurrency cancels stale runs, not defective code.
7. **No device-evidence substitution.** Synthetic HEVC, emulator RenderNode and source-string wiring tests are necessary but NOT proof of real 50fps SurfaceFlinger presentation, Widevine license/secure output, TCL playback, Samsung thermals, Cast timing, accessibility, or memory/GC p95. Keep all device/release gates open until their specific hardware logs, traces and tests pass.
8. **Accurate progress and one plan.** Reconcile ALL 16 workstream estimates against committed source and CI evidence after a verified checkpoint. Report completed% and pending%=100-completed% separately from hardware gate status. Never mark 100% to satisfy a target. No parallel active plans; update the consolidated plan and archive historical results without erasing evidence.
9. **No repeated failed-build loop.** After any CI failure, collect the full first-failure log, identify the single root cause plus other changed call sites, and build a coordinated correction before the next push. Do not rerun unchanged failing source to hide the defect. Do not dispatch Codemagic; use GitHub Actions only. Do not amend/rewrite accepted provenance or reset hardware gate counts.
10. **Release safeguards survive refactors.** Preserve signed in-place upgrades, backup/restore of named layouts, all player/map/tower/onboard/caption/DPAD features, explicit auth/security controls and official release 16-gate evidence. A green debug APK is not an approved release.

**Enforcement implemented with this review:** scripts/pitwall_change_contract.py checks a production-affecting commit for a same-commit change to this consolidated plan and an automated regression test; its own tests exercise the acceptance/rejection cases. The Android debug workflow runs that guard immediately after checkout and before Android SDK/Gradle setup. Docs-only push changes are ignored for automatic debug builds. The workflow already uses fail-fast APK assembly/publication and cancels superseded runs. The guard cannot replace a pre-push Kotlin compiler, human integration review or hardware acceptance.

## Exact GitHub Actions failure ledger: Builds 757–800

Audited historical totals: **44 runs, 10 passed, 34 failed.** These are CI snapshots, not 34 distinct playback bugs. The earliest observable failure in each run is attributed below; several runs also retried APK compilation after an earlier failure. The linked run collection is https://github.com/cooldarshak/apexview/actions .

| Build | CI | Root-cause diagnosis from workflow log |
| --- | --- | --- |
| 757 | PASS | Baseline source, tests, APK, emulator passed |
| 758 | PASS | CI passed |
| 759 | PASS | CI passed |
| 760 | FAIL | BrowseScreens.kt invalid composable call |
| 761 | PASS | CI passed |
| 762 | FAIL | ProtectedOutputHistoryPolicyTest wrong eviction count (expected 2, actual 1) |
| 763 | PASS | CI passed |
| 764 | FAIL | Python runtime p95 fixture expected 1 rather than 20 |
| 765 | FAIL | Same p95 assertion; extra APK assembly attempted despite failure |
| 766 | FAIL | Same p95 assertion and premature second assembly |
| 767 | FAIL | SDK android-37 install unavailable; Material Slider API and codec nullable receiver compile errors |
| 768 | FAIL | Same SDK, Material Slider and codec-capability compile errors |
| 769 | FAIL | Same SDK, Material Slider and codec-capability compile errors |
| 770 | FAIL | Android 37 verification; Material Slider and codec-capability compilation |
| 771 | FAIL | Android 37 verification; Material Slider and codec-capability compilation |
| 772 | FAIL | State-based Material Slider still passed obsolete valueRange arguments |
| 773 | FAIL | State-based Material Slider valueRange compile errors |
| 774 | FAIL | Kotlin Compose Gradle plugin version 2.3.0 could not be resolved |
| 775 | FAIL | ExpressiveUiAccessibilityTest invalid onAllNodes/assert imports |
| 776 | FAIL | Same Compose unit-test compilation errors |
| 777 | FAIL | Same Compose unit-test compilation errors |
| 778 | FAIL | Same Compose unit-test compilation errors |
| 779 | FAIL | Same Compose unit-test compilation errors |
| 780 | FAIL | Same Compose unit-test compilation errors |
| 781 | PASS | Material 3 migration, tests, APK and emulator passed |
| 782 | PASS | CI passed |
| 783 | PASS | CI passed |
| 784 | FAIL | ExpressiveUiAccessibilityTest invoked @Composable outside composable context |
| 785 | FAIL | Python runtime analyzer fixture miscounted steady archive segments (2 versus 0) |
| 786 | FAIL | Same invalid Compose accessibility-test invocation |
| 787 | FAIL | Same invalid Compose accessibility-test invocation |
| 788 | FAIL | Same invalid Compose accessibility-test invocation |
| 789 | FAIL | Viewer.kt val reassignment compile error |
| 790 | FAIL | Same Viewer.kt val reassignment |
| 791 | FAIL | Same Viewer.kt val reassignment |
| 792 | FAIL | Same Viewer.kt val reassignment at shifted source line |
| 793 | FAIL | Same Viewer.kt val reassignment |
| 794 | PASS | CI passed |
| 795 | FAIL | SwitchRow signature change without updating Calendar/Viewer call sites |
| 796 | FAIL | Same SwitchRow call-site mismatch |
| 797 | FAIL | SwitchRow call sites and missing Sheets.kt semantics role import |
| 798 | FAIL | Sheets.kt missing semantics role import |
| 799 | FAIL | Same Sheets.kt missing semantics role import |
| 800 | PASS | Full applicable CI, signed APK, HEVC/CMAF and 3 emulator tests passed |

### Build 801 resolution audit and remaining risk

- **CI blockers CLOSED at Build 801 source SHA 93cf7581bda337725b178a6fefd5f980bfb74699**: Kotlin/Compose source and unit-test compilation; changed API call sites; Gradle/plugin dependency resolution; Android target SDK preparation; Python runtime-analysis regressions; generated HEVC/CMAF decoding, signed debug APK assembly, emulator RenderNode (2 tests) and SurfaceDecoder (1 test). Both Build 800 and Build 801 ended GREEN. Evidence: https://github.com/cooldarshak/apexview/actions/runs/45119499727 . No failed historical run is retroactively GREEN.
- **Existing CI publication defect CLOSED in 801**: APK assembly now requires prior-step success; distributable APK upload requires overall success; branch concurrency cancels superseded runs. This stops the second compile/publication behavior seen in failed 760–799 checkpoints.
- **Production wiring PARTIALLY CHECKED in 801**: six new Python integration-presence assertions ran. They establish selected names/call paths remain in source but cannot prove runtime execution, DRM entitlement, device layout or receiver performance.
- **Unverified runtime risks remain OPEN**: Archive prefetch failure suppression within warm-ahead runCatching (poor error visibility); possible repeated cache-eviction retries under contention; actual 1x/1.5x foreground p95; real 7/8-feed allocation/GC/thermal; protected L1/L3 output; TCL one/multi decoder and map+tower stability; Cast and physical caption parity. These are NOT claimed to be regression failures proved by CI.
- **Instrumentation scope**: Build 801 ran 3 targeted emulator instrumentation tests, not the entire authored one-decoder and real device/Cast test matrix. No hardware gate may close from that result alone.
- **Code estimates after exact-green 801 (provisional)**: P0.1=96%, P0.2=93%, P2.8=92%. These modest increases recognize passed 801 fixes/tests, NOT hardware acceptance. All three remain unfinished and their release gates remain OPEN. Overall unweighted 16-workstream code estimate = 77.5625%; strict release gates 1/16 closed.
- **Required next coordinated implementation pass**: repair the still-visible recovery/observability and fairness risks if confirmed, close remaining production tests and wiring, validate full compatibility matrix and document objective evidence. No false 100% and no unsupported physical acceptance.

### Historical 16-workstream coding ledger: Build 828 exact-source GREEN

Coding percent describes implemented and automated-tested source, not hardware acceptance. Build 819 passed exact-source GitHub Actions, including JVM, Python, Cast/HEVC/CMAF, signed APK/benchmark assembly and mandatory Android emulator tests. P1.1/P1.2/P1.3/P2.6 now close at 100% coding; P0.1/P0.2/P0.3/P0.4/P2.8 remain 100% coded. ALL physical release gates except P2.7 remain OPEN.

| ID | Workstream | Coding complete | Coding pending | Automated CI | Physical testing | Release gate | Exact remaining work |
| --- | --- | ---: | ---: | --- | --- | --- | --- |
| P0.1 | Segment priority | **100%** | **0%** | 806 GREEN | S22/TCL 1x/1.5x seek p95 pending | Open | No known coding left; real contention and seek acceptance |
| P0.2 | HEVC memory | **100%** | **0%** | 806 GREEN | 10-minute GC/heap and 7/8-feed pending | Open | No known coding left; sustained clear/protected/Cast retention and allocation evidence |
| P0.3 | Video rendering | **100%** | **0%** | **815 GREEN** | S22 log + 3 Perfetto traces reviewed: visual smoke PASS, 0 exact codec PTS matches and persistent BufferQueue saturation; TCL/Widevine/Cast pending | **Open** | Physical video-layer cadence, timestamp-domain attribution, BufferQueue cause and device acceptance |
| P0.4 | Map and tower | **100%** | **0%** | **815 GREEN** | S22 visual smoke PASS, but 46.1% slow app UI frames against <5% goal; uncontrolled overlay configurations, TCL/Cast pending | **Open** | Controlled A/B, 10-minute endurance, independent SurfaceFlinger/thermal attribution and TCL testing |
| P1.1 | HEVC atlas | **100%** | **0%** | **819 GREEN** | Real mixed-rendition HEVC/decoder workload on S22/TCL pending | **Open** | Coding closed; measured smallest conforming atlas, 10-minute decoder/coded-pixel and thermal acceptance |
| P1.2 | Refresh matching | **100%** | **0%** | **819 GREEN** | S22/TCL real 50fps/75fps at 1×/1.5× and active mode transitions pending | **Open** | Coding closed; physical FrameTimeline presentation, seamless-mode and audio-sync acceptance |
| P1.3 | Quality and thermal | **100%** | **0%** | **819 GREEN** | Real offered F1 renditions, manual caps, 10-minute temperature/GC pending | **Open** | Coding closed; device thermal/quality behavior acceptance with no silent downgrade |
| P2.1 | Pitwall Cast | 85% | 15% | 806 GREEN | TV receiver timing pending | Open | Protected/baked receiver overlay parity |
| P2.2 | Coordinator architecture | **45%** | **55%** | **828 GREEN** | Phone/TCL/Cast complete coordinator ownership pending | Open | Batch 2 scene/player epoch and resource ownership verified; complete remaining broader AppState coordinator extraction |
| P2.3 | Release and updates | 88% | 12% | 806 GREEN | Signed upgrade both devices pending | Open | Production signer and upgrade verification |
| P2.4 | Security | 70% | 30% | 806 GREEN | Pending | Open | Auth, LAN transport, backup/log redaction |
| P2.5 | Android TV | 80% | 20% | 806 GREEN | TCL player/map/tower/DPAD pending | Open | Native TV playback and remote acceptance |
| P2.6 | Testing and benchmarks | **100%** | **0%** | **819 GREEN** | Run physical 10-minute 1×/1.5× OFF/MAP/TOWER/BOTH matrices on S22/TCL/Cast | **Open** | Benchmark/harness coding closed; real app/SurfaceFlinger/GC/BufferQueue evidence and release sign-off pending |
| P2.7 | Core architecture | 100% | 0% | 806 GREEN | Previously accepted scope | Closed | Preserve single decoder invariants |
| P2.8 | Material 3 Expressive | **100%** | **0%** | 806 GREEN | Portrait accepted; TCL/TalkBack/200% pending | Open | No known coding left; physical visual/accessibility acceptance |
| P2.9 | Closed captions | 75% | 25% | 806 GREEN | F1 cue rendering pending | Open | Mobile/TV/Cast clear/protected caption parity |

**Unweighted coding estimate after GREEN Build 828: 90.1875% complete / 9.8125% pending across 16 workstreams.** The 1.25-point increase is the verified P2.2 scoped-coordinator advancement from 25% to a provisional 45%; the original milestones already at 100% remain at 100%. Physical sign-off is still 1/16 CLOSED. Strict release gates: 1/16 closed, 15 open.** Four requested workstreams are now 100% source coded/automated tested, not physically accepted. All release gates except P2.7 stay open; historical status tables below remain dated evidence, not the current ledger.

## Builds 757–799 failure audit and post-800 coordinated quality checkpoint

**Historical CI ledger, verified from GitHub Actions:** Among 43 builds (757–799),
9 passed and 34 failed. Build 799 was RED, not an accepted release candidate.
This included several kinds of independently verified failures, not 34
different playback defects:

- Compose type and call-site compilation problems in the player/settings
  refactor, including `Sheets.kt` role semantics, `Viewer.kt` assignments and
  the `SwitchRow` callable signature (788/789/793/795–799).
- Material 3 1.5 / AGP 9.1 / compileSdk 37 migration and SDK provisioning
  failures (766–774), followed by corrections checked by newer green CI.
- Incorrect regression fixtures/assertions in JVM Compose and Python
  performance analysis tests (760, 762, 764–765, 779–780, 785–788).
- Builds 781, 782, 783, 794 and finally **800** prove that particular source
  snapshots compiled and passed their applicable checks. Build 800 also ran
  generated HEVC/CMAF decoder comparisons, signed APK assembly, and emulator
  RenderNode instrumentation. The earlier red builds remain red in history,
  but their compile/test blockers were absent from Build 800.

**Historical post-800 coordinated commit, verified by Build 801:**

- P0.1/P0.2: speculative archive warming now ensures a CMAF segment is cached
  without allocating another full CMAF fragment only to discard it. Foreground
  readers retain exclusive copies, per-segment single-flight, cancellation,
  bounded cache/zeroing and explicit player-priority recovery. Verify this
  production wiring and existing cancellation/ownership tests in CI.
- P2.8: root Browse/Viewer navigation and shared navigation animations now
  honor system Remove Animations; the 200%-type TV chrome uses a second row
  instead of clipping its video/Map/Tower/Cast actions. Preserve the genuine
  stable M3 1.5 Expressive theme and DPAD/accessibility semantics.
- Release engineering: fail-fast APK assembly (no second compilation after
  a failed unit test), no distributable APK after emulator or other failure,
  branch-run deduplication, and a source-wiring test to detect accidentally
  orphaned P0.1/P0.2/P2.8 production policies. This is an integrity check,
  not a replacement for device runtime proof.

**Historical Build 800 progress accounting:** P0.1 95%, P0.2 91%, P2.8 90%. Build 801 passed exact-source CI, so the current authoritative coding estimates above are now 96%, 93%, 92%. The previous percentages are retained as history.
No untested phone, TCL TV, L1/L3 Widevine or Pitwall Cast performance is signed
off by these changes. **Strict release gates remain 1/16 closed.** Hardware
seek p95, measured HEVC allocations and UI/TCL remote/Cast parity remain open.

## Historical follow-up source audit: selector hot path, CMAF wiping, settings choices (CI passed in 801)

- **P0.1**: normal continuation now chooses one chronological next segment
  in linear time without sorting every master archive candidate at each
  CMAF boundary; startup/seek retains closeness-ordered fallback. Tests
  cover out-of-order manifests and never skipping an unmatched HEVC segment.
- **P0.2**: Media3 CMAF read regression exercises I/O failure during
  known and unknown transfer-length paths, verifies that retained transient
  ciphertext buffers are wiped and that DataSource.close always runs.
  No unmeasured claim of lower physical GC is made.
- **P2.8**: settings choice rows now expose actual RadioButton semantics
  and selected state for TalkBack and DPAD, not just a coloured background.
  Compose regression checks the radio roles and one click target per row.

**At that historical pre-801 checkpoint the estimates were 95%, 91%, 90%.** Build 801 CI subsequently passed; use the updated authoritative ledger at the top (96%, 93%, 92%). Release acceptance remains 1/16; hardware p95, GC and accessibility/remote acceptance remain separate.

## Historical P0.1 single-flight observer failure isolation (CI passed in 801)

The archive shared-segment coordinator now completes and unregisters a
producer flight if its registration observer fails, rather than leaving
foreground joiners blocked forever on an unresolved future. A failing
joiner's observer does not cancel a healthy shared producer. Two JVM
regression tests cover recovery and the active-joiner exception case.

**Historical progress accounting before Build 801:** P0.1 95%, P0.2 91%, P2.8 90%. Build 801 passed; use the latest 96%, 93%, 92% provisional coding estimates at the top. This resolves a discrete concurrency failure mode but does not
replace measured physical-device seek p95 or the full coding/device
acceptance matrix. Latest branch CI must be evaluated independently.

## P0.1 / P0.2 / P2.8 coordinated work after Build 757 (coding in progress)

**Source-only checkpoint, not a release certificate.** Build 759
([green CI](https://github.com/cooldarshak/apexview/actions/runs/45111620901))
and Build 761
([green CI](https://github.com/cooldarshak/apexview/actions/runs/45111786213))
validated bounded archive fragment ownership, successful foreground recovery
from failed speculative single-flight, lazy browse sizing, single-focus
settings toggles and reduced-motion plumbing. Build 763
([green CI](https://github.com/cooldarshak/apexview/actions/runs/45112174152))
validated protected 8-feed pending-init retention, byte-aware output-history
policy, adaptive player chrome, exact eviction assertions and existing
generated clear-HEVC pixel/CMAF fixture checks.

**P0.1: now 95% coding estimate (was 92%; Build 757 baseline 87%).**
Foreground archive requests now take priority over cancelled prefetch flight
exceptions, terminated flights are released before another producer registers,
and test fixtures cover shared work, cancellation, priority, seek and close.
Every foreground request emits its actual wait time, including cache hits,
and the runtime analyzer calculates *unbiased* nearest-rank p95 over request
samples. The log-analysis regression was corrected after Builds 764/765.
The archive-cache critical section now excludes blocking process-wide ART
allocation/GC probes, CPU-time sampling, and expensive logcat formatting.
A fail-closed runtime acceptance tool evaluates real foreground seek p95
alongside required capture duration and refuses missing observations.
**Remaining 5%:** obtain actual S22/TCL repeated-seek 1x/1.5x
before/after p95 measurements and prove uninterrupted cross-path priority
under real network and foreground pressure. This is a code-oriented estimate,
not measured performance.

**P0.2: now 91% coding estimate (was 84%; Build 757 baseline 75%).**
Clear archive fragment cache owns its ByteArrays under both an entry cap and
96MiB byte budget, wiping evicted buffers; tests prove copy isolation, LRU,
oversized rejection and zeroing. Protected CBCS composer now preserves up to
eight validated feed initialization snapshots, limits rolling composed
ciphertext history to 160MiB subject to two-fragment live startup floor,
wipes evictions, and reports peak held bytes. HEVC tile rewriting now zeroes
partially written NALs on failure/seek, and avoids a flattened hot-path
selection collection. Runtime log analytics separate steady-state GC and
allocation from warmup and report clear/protected peak retained bytes.
Clear CMAF composition now wipes unpublished NALs, init and media fragments
across *all* post-rewrite failures, including early compatibility checks.
HEVC telemetry separates wall-clock fetch, CMAF inspector, NAL inspection,
rewrite, packaging, thread CPU, process-wide allocation and GC; sub-ms
per-feed inspector time is aggregated in nanoseconds to avoid false 0ms
results. The runtime report treats absent historical CPU metrics as
UNKNOWN rather than passing zeroes, and fails closed on device memory-budget
thresholds when requested.
**Remaining 9%:** verify latest post-781 telemetry changes under full CI;
capture comparable 10-minute live protected/clear/Cast sessions on real
S22/TCL to establish allocation, GC, 7/8-feed and backpressure
bounds. No measured memory/thermal win may be claimed without that evidence.

**P2.8: now 90% coding estimate (was 78%; Build 757 baseline 58%).**
Feed pickers are lazy and viewport-aware; browse hero/cards/settings
scale for narrow landscape, TV and 200% text; settings switches expose
one labeled TV-D-pad/TalkBack toggle; player chrome splits to preserve
all Cast, map, tower, layout and caption commands in small viewports;
buttons meet 48dp targets; animation honors reduced-motion. Added pure
viewport tests and Compose accessibility switch tests. **Build 781 fully
passed** the real stable Material 3 1.5 `MaterialExpressiveTheme`,
Compose BOM 2026.11.01, AGP 9.1.1, Gradle 9.3.1, compileSdk 37,
targetSdk 36, HEVC fixture tests and Android RenderNode instrumentation.
The new state-based M3 1.5 Slider preserves custom TCL D-pad key handling.
After this green build, added semantic role tests and repeat-key regression
tests, now running in newer CI jobs.
**Remaining 10%:** verify those follow-up UI regression tests, real 200%
font/TalkBack and TCL D-pad device runs, review all player/settings
navigation for visual consistency and measured smoothness with map,
tower and Cast. Full library migration is CI-accepted; physical device
acceptance is not.

**Strict release gates unchanged: 1/16 closed.** CI does not sign off
S22, TCL, native phone-to-TV Cast, 50fps physical presentation or DRM L1/L3.
The three workstreams are *not 100%* until every coding item is genuinely
finished and its objective acceptance evidence exists. Update the estimates
when verified scope changes, never merely because a build number increases.

## Build 757 CI-verified P0.1/P0.2/P2.8 source improvements (hardware evidence pending)

Build 757 passed the applicable GitHub Actions unit tests, generated HEVC/CMAF
checks, APK assembly and native RenderNode emulator instrumentation against
the exact source commit `daf4195f9dbec051fe5dc29ed68507963e8a47f3`.
That is CI verification, **not** a hardware acceptance certificate. The green
Build 756 baseline and this sole consolidated plan are preserved.

**P0.1:** Prevent `ArchiveDashSegmentSelector` from skipping the immediately
next master segment when a secondary feed lacks the corresponding timestamp.
Continuing after the next unmatched segment could omit reference-dependent
HEVC pictures. Startup/seek selection may still use an older fully aligned
window. Add both positive and negative selector regression cases. Foreground
seek p95 and cancellation-race device evidence remain outstanding.

**P0.2:** Unknown-length Media3 CMAF I/O now uses bounded 32KB owned chunks
and a single final copy, instead of geometric ByteArrayOutputStream growth plus
a further full copy. The byte-budget overflow probe rejects one extra byte;
no-progress, interruption, partial encrypted byte cleanup, authorized URL
and existing known-length exact-copy handling are retained. Add exact-boundary,
over-budget and empty-response DataSource regression tests. This reduces
avoidable temporary copies in source, NOT proven device GC/thermal pressure.

**P2.8:** The feed picker moved its eager full-driver FlowRow inside a
verticalScroll column to a lazy, keyed per-row viewport with adaptive
120dp onboard cards (1..8 columns across phone and TV), retaining broadcast,
captioned name, selected state, existing watch-live/from-start commands and
remote focus interaction. Add phone/TV width and 24-feed preservation tests.
The wider settings/viewer accessibility and physical input-latency gate remain
open. Preserve the Material 3 theme, native TV focus ring, no extra decoder
and authorized clear/Widevine and Cast ownership.

**Acceptance:** pending exact source CI, S22 Ultra/TCL 55C655 10-minute
performance traces, 50fps presentation, 1x/1.5x, seeks, overlays, Cast,
auth L1/L3, accessibility and official signer. These changes do not close
any of the strict hardware gates. Keep 1/16 closed and avoid inflating
the 72% historical estimate without full evidence.

## Build 756 coordinated Kotlin call-site correction and owned-buffer CMAF I/O

**Build 755 CI result: FAILED, not accepted.** Kotlin compilation reported
two missed `publishTracks` call sites in the archive's seek-window
streaming branch (the regular streaming path was already updated).
Both must pass a monotonic `positionPreviewEpochs.next()` value at
queue time so that every full-archive and near-seek position preview
uses the *same* freshness contract. The old branch also used
`DriverTrack.at(currentUtc)` merely to count positioned cars;
switch it to the allocation-free `hasPositionAt(currentUtc)`.
Retain all map/tower fields, clock retargeting, cached track outlines,
and the final full-archive fallback. Require Kotlin compilation and
end-to-end HEVC/Android emulated CI before marking this fixed.

**P0.2/P0.1 memory and I/O:**
`CmafHttpReader.read` supports clear HEVC, authenticated CBCS media
and the existing LAN Cast relay, with a strict caller-supplied byte
budget. When Media3 `DataSource.open` reports a known Content-Length
(or explicit byte-range length), the previous reader grew a
`ByteArrayOutputStream` and then copied its **entire contents** again
via `toByteArray`. For each concurrently read fragment, this added
a second large temporary allocation in addition to the output.
The known-length path now allocates one bounded exact-size byte array,
reads it to completion, verifies requested HLS ranges, and transfers
the same array to the existing authorized caller without an extra copy.
Unknown-length sources retain the original bounded output-stream
fallback. Fail closed on premature EOF, zero-byte reads, overflow and
invalid byte-range lengths, and zero partial encrypted buffers on
exceptions. The reader never decrypts samples, requests licenses or
changes stream authorization. New Media3 DataSource tests exercise
known/unknown transfer size, multipacket reads, premature EOF, no
progress, byte limits and mismatched HLS ranges.

This is an algorithmic reduction in transient heap allocations, not
physical proof of lower process allocation/GC, TCL map/tower playback,
true 50fps or protected eight-feed reliability. All 16 hardware
acceptance gates still use the evidence policy and are not overridden
by green CI.

## Build 755 coordinated TCL HEVC coded-picture, remote input and map-preview pass

**P0.3/P2.5, Android TV coded-picture defect (actual code bug):**
The Amlogic OMX decoder correction in `FrameThinning.kt` assumed that
any HEVC conformance crop must be at least **one whole 64-pixel CTB**.
F1 HEVC routinely uses non-CTB-aligned coded heights, including 1088
coded lines with only eight lines cropped to 1080 visible lines.
That made `croppedHevcCodedSize` return `null` on a valid 1920x1088
bitstream displayed as 1920x1080, bypassing the special TCL codec
configuration entirely. Match the **real parsed SPS conformance window
of any positive size**, and retain exact display-dimension validation.
Never call `MediaFormat.getInteger(KEY_MAX_WIDTH/HEIGHT)` when its key
is absent, which otherwise throws in OEM MediaFormats and interrupts
decoder setup. Preserve or enlarge any existing maximum coded dimensions.
Compare the **actual coded** SPS dimensions on rendition switching,
not merely the cropped display dimensions, before deciding OMX codec
reuse. Tests use real parser-produced HEVC SPS NAL-shaped fixtures with
8-line and 2-pixel crops, unchanged unpadded streams, same-display
coded-geometry switches, and missing/max-size capability paths.
Clear and Widevine still use the same authorized Media3 renderer;
no decoder bypass, removal or hidden fallback.

**P0.4, map and tower startup:** Snapshot readiness previously called
`DriverTrack.at(utc)` solely to determine whether a car has usable
coordinates, constructing a new `Pair` per car on every map/tower
readiness poll. Make the existing binary interval/gap validator reusable
as `hasPositionAt(utc)`, with **identical bounds and five-second gap
rules** but no coordinate allocations. Use it both in F1 archive
position previews and the TV/phone readiness observer, retaining the
original `at` interpolation for actual map rendering. Regression
tests check the exact equivalence across end points, gaps and empty/
single-point tracks. The position parser can queue several overlapping
asynchronous partial publications; assign them synchronized monotonic
epochs **when enqueued** and drop older work on Main instead of letting
a late map snapshot replace a newer track/tower state. Preserve
partial tower publication and later fallback to the circuit outline.

**P2.5, TV remote:** Intercept Material3 slider left/right D-pad
events in Compose's **preview** phase, before its inner slider consumes
the key; retain the custom one-percent step, held-key acceleration,
explicit key-up commit and the focus ring. Expand the existing
Robolectric Leanback remote interaction test to cover ten consecutive
right/left presses, retained focus and completion callbacks.

**Sign-off and invariants:** The patch is not considered source-verified
until all Kotlin unit tests, HEVC generated fixtures, APK assembly and
native emulator instrumentation pass. Still require real TCL 55C655
with **Software DRM ON** plus Galaxy S22 Ultra physical playback,
three combinations (video only, map only, tower only, map+tower),
SINGLE and MULTIPLE decoder, authorized clear/CBCS Widevine L1/L3,
Pitwall Cast, 1x/1.5x, measured 50fps vsync/BufferQueue and long-duration
performance. This patch **does not** close hardware gates by changing
tests or counting CI as a physical video measurement.

## Build 754 protected six/eight-feed performance log accounting and thermal correlation

The existing `scripts/analyze_pitwall_runtime.py` only summarized
`ARCHIVE_VOD_SEGMENT` (clear-archive) allocations even though the
user's confirmed real six-feed protected HEVC path logs
`PROTECTED_CMAF_SEGMENT` with ~288 samples/segment and substantial
allocation/GC. Add a separate protected CMAF steady-segment report
(excluding the first three warmup segments), medians for protected
allocation, GC, composition and encoded bytes, the actual ACTIVE
single-decoder feed counts, and app-window input/animation p95
histogram upper-bin **maxima** plus raw Android thermal categories.
Never merge clear and protected composition costs into one average.
Retain the old fields, app-window slow/frozen frame metrics and
BufferQueue timeout evidence. Add parser regression with genuine
log-shaped six-feed lines and mixed thermal statuses.

Critically: four supplied Android rotating segments overlap. The
CLI explicitly warns not to sum different file summaries, because
they may contain repeated log lines. On-device UI p95 histogram
maxima are **not** input-to-photon latency and decoder samples are
**not** SurfaceFlinger physically presented frames. Follow the
existing Perfetto tools for true 50fps and don't claim P0.2/0.3,
P0.4 or UI gate closure until matching new device captures prove it.

## Build 753 reduce FCast TCP framing syscall load without losing ordered control

The local phone-to-TV Pitwall FCast transport used
`socket.tcpNoDelay=true` and called `OutputStream.write` separately
for each of the four length bytes, opcode and body, then flushed.
On a real TCP socket those tiny header writes can generate extra
system calls and packets while frequent 200ms timing frames, video
hand-off, remote D-pad and caption controls share the connection.
Reuse one bounded **32KB BufferedOutputStream and BufferedInputStream**
on the existing IO writer/reader for the whole connection. Keep
`FcastProtocol.write`'s per-packet flush, strict 32KB length checks,
ordered backpressure and 20-second socket timeout unchanged, so
there is **no added batching delay**, lost telemetry, new thread
or change to DRM/LAN authorization.

Extend the FCast protocol test to round-trip a 16KB motion payload
between VERSION and PAUSE, and keep the real socket test proving
order, ack and close. This is a transport CPU/packet reduction by
construction, not proof the TCL's missing-map or receiver jitter
issues have disappeared; real Cast overlay and video traces must
still close those gates.

## Build 752 fix Build 748-751 CI regression, preserve strict gate integrity

Build 748 was NOT green: the real Android unit-test job completed
**691 tests with exactly one failure** at
`UpdaterTest.updateProgressOnlyReportsMeaningfulChangesToCompose:37`.
`0.06f - 0.01f` rounds slightly below `0.05f`, causing the
new 5% progress boundary to be skipped. The rule now accounts for
single-precision quantization with a narrow 0.000001f tolerance;
updates below 4.9% still fail the progress threshold. Extend the
real JVM test to cover both exact boundary and sub-threshold cases.
Keep all 16 release gates closed/open based on actual device
acceptance, not merely green CI. Builds 749-751 inherit the same
pre-fix unit test and are not release candidates. This is a
source-level correctness repair; require latest CI green end-to-end.

## Build 751 preserve protected validation with zero-copy preflight resource accounting

P0.2's rolling archive startup previously called
`ProtectedHevcComposedDashPackageBuilder.build` for preflight and
created duplicate copies of **every** output CMAF initialization and
media fragment, only to zero those buffers immediately. The real
`ProtectedHevcLiveDashSource.publish` validates the same exact
CBCS/HEVC window again and takes ownership of a separately detached
encrypted output. Keep both full inspections and the existing
copy-on-handoff source-lifetime safety, but add a `validationOnly`
mode to the preflight builder that verifies all track, IV, KID,
subsample, timing, PSSH and manifest constraints **without copying
fragment bytes into an unused package**. Explicitly reject combining
`validationOnly` and `takeOwnership`. Tests build a real generated
encrypted HEVC CMAF fixture, compare the resulting exact MPD, assert
no temporary resource buffers, verify source bytes remain unchanged
after validation teardown, and reject accidental ownership transfer.

No DRM decryption, signature change, decoder allocation, missing
validation or bypass; the successfully published package still owns
its buffers until all Media3 leases release and zero them. This is a
source-level reduction of redundant allocations, not a measured
device-memory/GC win until a matching six/eight-feed Perfetto run
proves it. All strict 16-gate hardware sign-offs remain required.

## Build 750 enforce all 16 independent hardware-evidence gates before signing/publishing

The official release workflow previously required a *closed, accepted issue*
but verified only that the issue body contained nine broad words, including
"16 gates". This could allow a production candidate to advance without
an individually traceable P0/1/2 acceptance matrix. Add one small
stdlib-only Python validator, tested in the existing
`python -m unittest discover -s scripts` debug CI. It requires:

- The accepted issue explicitly names the **same exact commit SHA**
  and **same GitHub Actions run ID** already checked by the workflow.
- All **16** named gates `P0.1..P0.4`, `P1.1..P1.3` and
  `P2.1..P2.9` each appear exactly once as
  `GATE P0.1: PASS | evidence: https://...`. A missing, duplicate,
  unpassed, malformed, non-HTTPS or placeholder evidence gate fails
  the build before release signing.
- The issue explicitly covers **Galaxy S22 Ultra**, **TCL 55C655**,
  authorized **Widevine L1 and L3**, **clear HEVC**, **SINGLE** and
  **MULTIPLE** decoder settings, **Pitwall Cast**, **closed captions**
  and **Material 3**.
- Continue requiring the existing closed owner-approved issue label,
  source SHA, successful green debug run, signer digest match and
  explicit publish permission. The validator checks structured
  evidence *references*; it cannot itself prove a phone or TV test
  took place, so human issue review remains mandatory.

At this checkpoint no physical issue satisfying this format has been
verified, hence **1/16 gates remain formally closed**. Keep release
publishing fail-closed.

## Build 749 real source-owned captions in composed clear and protected scenes

Inspection found a genuine source-owner mismatch in P2.9:
`FeedPlayer.startArchiveVodPlayback` merges the selected authorized HLS
audio **and text** tracks with clear HEVC video inside the *single*
composed ExoPlayer. `AppState.captionSourceFeed` instead allowed
`protectedCompositionAudioMaster` to win as the caption owner, so
`applyCaptionPreference` could enable text on an inactive source tile
while disabling it on the actual composed host. Update the shared pure
`CaptionPolicy.owner` contract with an explicit
`clearArchiveComposed` flag. Clear composed video captions ALWAYS belong
to the composed host player, including when a user changes the source
audio feed; protected rolling Widevine video still gets captions from
the authorized source audio master, preserving user-selected commentary.
No extra MediaCodec, DRM session, relay or text URL is introduced.

Tests cover both cases including an unavailable composed host and
the original standalone/MULTIPLE modes. Cast receiver text still uses
its own actually advertised Media3 tracks. Confirm real cues on
S22, TCL native, authorized L1/L3, SINGLE/MULTIPLE, clear/protected
and both Pitwall Cast modes before closing the captions gate.
This code-level correction does not prove captions exist in every
F1 media rendition or automatically close 16-gate hardware acceptance.

## Build 748 coordinated UI responsiveness, eight-feed layout persistence and release hardening

The new 7/8-feed HEVC path exposed an actual saved-layout interoperability bug:
the credential-free `NamedLayoutPreset` parser only accepted 1..6 feeds.
Allow all `AppState.MAX_SINGLE_DECODER_FEEDS=24` positions for clear
archive and hardware-gated protected layouts. Preserve the same versioned
storage format, every map and tower field and Android Auto Backup exclusion
of F1 login; test real Android SharedPreferences round trips with eight and
24 feed slots, refuse a 25-feed import and reject >1MB untrusted layout
JSON before expensive parsing. Restoration after uninstall depends on
Android's actual backup provider and must be physically verified.

Give the player top/bottom controls a 140ms visibility fade and 90ms
press feedback. Keep the 380ms *video geometry* transition separate
to avoid reparenting/tearing an active protected SurfaceView. This
does not constitute measured <100ms input-to-display response.

The updater previously wrote mutable Compose `progress` from
`Dispatchers.IO` on every **64KB** download read, which can
invalidate a visible settings dialog hundreds of times per second.
Throttle UI publication to 5% or 250ms intervals, always preserve
initial/final status, and publish from Main. Retain checksum, app ID,
signer identity, monotonic versionCode and Android package installer
security gates. Add a pure sampling-policy regression test.

Handle a late `TextureView` output-destroy callback after its GL
HandlerThread exits by freeing its SurfaceTexture even when the
handler refuses a posted release. Log rather than silently hiding
the lifecycle failure. Record raw Android thermal status (-1 if
unavailable) in the existing two-second player/receiver FrameMetrics
reports to correlate real 50fps, GC and UI slow frames, without
inventing thermal causes or degrading quality invisibly.

The code and tests in this pass require green GitHub Actions before
being treated as compiled. The device matrix and 16 release gates
remain unchanged until real Samsung, TCL and Cast evidence exists.


## Historical implementation and acceptance ledger (Build 801 green; post-801 guard CI pending)

This ledger supersedes the older historical 66% implementation snapshot
farther down in this same document. Numeric values are explicitly
**engineering estimates**, not code coverage or verified release scores.
No numeric percentage closes a gate without evidence.

| Gate | Coding estimate | Pending coding/acceptance |
| --- | ---: | --- |
| P0.1 Segment priority | 96% | undefined |
| P0.2 HEVC memory | 93% | undefined |
| P0.3 Video rendering | 65% | undefined |
| P0.4 Map and tower | 85% | undefined |
| P1.1 HEVC atlas | 85% | undefined |
| P1.2 Refresh matching | 60% | undefined |
| P1.3 Quality and thermal | 55% | undefined |
| P2.1 Pitwall Cast | 85% | undefined |
| P2.2 Coordinator architecture | 25% | undefined |
| P2.3 Release and updates | 88% | undefined |
| P2.4 Security | 70% | undefined |
| P2.5 Android TV | 80% | undefined |
| P2.6 Testing and benchmarks | 87% | undefined |
| P2.7 Core architecture | 100% | undefined |
| P2.8 Material 3 Expressive | 92% | undefined |
| P2.9 Closed captions | 75% | undefined |

**Current Build 801 coding estimate: 77.5625% complete, 22.4375% pending (unweighted average of the 16 entries).** P0.1 moved from 95% to 96%, P0.2 from 91% to 93%, and P2.8 from 90% to 92% solely after Build 801 passed its applicable CI tests. These are provisional engineering estimates, not line coverage, device measurements, or 100% sign-off.

**Strict full-release gates remain 1/16 closed (6.25%); 15/16 OPEN.** CI cannot prove Samsung S22/TCL hardware playback, Widevine L1/L3, Cast, physical frame presentation, timing overlays, captions or accessibility. The detailed Build 757–800 failure audit and enforceable quality contract are at the beginning of this same plan.

## Build 747 bounded-allocation exact 50fps HEVC alignment

The protected eight-source extension increases the number of per-picture
candidate comparisons. Each `HevcPictureSequenceAligner.sameTime`
comparison previously allocated multiple BigInteger values, despite
normal F1 50fps CMAF timescales safely fitting exact Long products.
Use `Math.multiplyExact` for an allocation-free exact hot path and fall
back to BigInteger only when a product would overflow. Floating-point
approximation is forbidden; 1/90000 second timing mismatches must
still reject cross-feed encrypted composition. Replace the two
`groupBy`/per-group List allocations in each 288-sample window's
duplicate POC and epoch-anchor validation with one pass over bounded
sets/maps, keeping identical fail-closed semantics. Tests cover
cross-timescale equality, exact mismatch, overflow fallback, negative
offsets and the existing multi-IDR/8-source rejection cases.

Acceptance remains contingent on the same real six-feed content
and TCL phone/TV matrix; performance savings cannot be asserted
until after-device traces, and 15 of 16 gates remain open.

## Build 746 extended protected HEVC source capacity, guarded by actual decoder capability

The previous six-source limit existed in multiple separate components:
DASH and HLS capture, IRAP epoch alignment, CBCS picture composition,
source atlas planning, protected scene geometry, and Android TV/phone
composition activation. Expand these consistently to **eight** eligible
sources without introducing additional video decoders or changing
authorized CBCS ciphertext/key/IV requirements. The <=6 mixed-picture
planner retains its original **24-cell** search space and candidate
ordering. For seven/eight sources only, allow up to **32 verified source
tiles** and do not exceed 4096 x 2176 coded pixels. Seven one-tile
sources require seven PPS tile columns when not using full pictures;
padding seven into a four-by-two grid violates the exact feed-to-tile
mapping. Eight may use four-by-two. Complete source pictures retain
the existing area-constrained atlas planner.

For >6 sources only, verify a hardware HEVC decoder advertises the
specific actual output **width, height and frame rate** *before* releasing
source decoders or activating a new protected output. Authorized L1
requires secure-capable HEVC hardware; L3 and clear HEVC require a
nonsecure hardware output. Recheck if an archive or TV live adaptive
coded picture changes. AUTO may choose an entitled 540p source for
an extended atlas; explicit quality caps are never silently lowered.
If no conforming SPS/PPS picture/compatible CBCS IV/decoder exists,
fail visibly and keep the six-feed implementation untouched. No
software decoder or MULTIPLE preference fallback is introduced.

Added tests for seven/eight-source atlas geometry and eight-feed exact
picture/clock proof, including mismatched-picture rejection. These
are algorithmic fixtures, **not** evidence of eight real F1 protected
feeds simultaneously playing on S22/TCL. 7/8 is an experimental
capability and cannot be marked physically accepted without actual
entitled F1 content, codec/thermal traces, accurate crops, clear/CBCS
and native Cast checks. Strict release acceptance remains 1/16.

## Build 745 coordinated Android TV Cast main-thread isolation, feed synchronization and expressive navigation

**Root cause addressed in code:** FcastConnection previously dispatched all
receiver packets to Main and synchronously parsed the complete timing geometry,
24-driver tower table and 200ms motion frame on Main. On the TCL that work
competes directly with Compose/D-pad and HWUI on a device already showing
overlay lag. The receiver now keeps ordered TCP delivery (each packet
awaited before the next) but parses geometry, tower table and eligible
motion payloads using Dispatchers.Default, returning to the owner/Main
scope only for existing packet generation, geometry identity, epoch,
table version and sequence validation and Compose state publication.
Clear and protected receiver playback are unaffected; DRM and media
payloads never enter this timing parser. A generation lease invalidates
a parse in flight on STOP/new PLAY or native seek, preventing old packets
from resurrecting stale map/tower state. The direct synchronous receiver
API remains usable by existing tests. New async tests exercise geometry,
tower, motion and seek epoch recovery. This is a coding performance fix,
not a hardware-confirmed TCL P0 closure.

**Coordinator extraction:** The follower clock correction algorithm moves
from the monolithic AppState into tested, pure FeedSyncPolicy with the
same 2000ms seek and 150ms nudge thresholds and 0.9/1.1 speed multipliers.
Keep the existing explicit SINGLE video-decoder invariant: this policy
does not allocate a player/decoder and must not be used as an implicit
fallback. Clamp extreme position discontinuities safely.

**Expressive interaction:** Use existing 240ms fast navigation motion in
shared browse artwork transitions (previously 380ms), without changing
video SurfaceView, GL, seek, or protected scene animation pacing.
Regression tests must pass on both phone and TV.

**Gate accounting:** This is an implementation progress checkpoint; it
does not close the 15 still-open full-release evidence gates. Close
P0.3/0.4, Cast, TV, UI and sync only after 10-minute physical TCL/S22
runs with full overlay matrix, real source ownership, frame presentation
and UI input metrics, and protected/clear SINGLE/MULTIPLE parity. Keep
the historical workstream estimates independent from CI success.

## Build 744 diagnostic capture load and UI input/animation telemetry

The user's Android 16 six-feed logs recorded thousands of identical framework
`View setRequestedFrameRate frameRate=NaN` lines (for example, over 6,800
lines within one 5MB rotating segment). Preserve a verbatim first record,
count each subsequent matching AndroidX framework line, and emit the last
verbatim instance plus total repeat count in the existing periodic log summary.
Keep BufferQueue timeout aggregation, other errors and every protected/CMAF
playback signal intact. Do not claim Android framework NaN is an app-generated
frame-rate request or suppress it on the system logger; this change only
prevents the app's opt-in *file capture* from flooding its own storage.

Add `FrameMetrics.INPUT_HANDLING_DURATION` and `ANIMATION_DURATION`
buckets to the existing per-window `PitwallFrames` 2-second report. This
separates main-thread input/animation costs from layout, draw, command queue
and GPU timing on phone and TV. These are app-window data, **not**
input-to-visible-photon latency or independent SurfaceFlinger video presents.
Compile and test aggregator reset, first-line retention and CBCS diagnostics
preservation. Validate against matching hardware traces and the accepted
`<5% slow UI frames` goal before closing the Material 3 performance gate.

## Build 743 carousel memory and immediate Home responsiveness hardening

The visible Home composable previously enqueued **every** carousel pick at
1920x1080 each time the pick list changed, even though just one or two slides
were on screen. That scales bitmap decode/memory/cache pressure with catalogue
size and competes with six-feed HEVC playback and Compose navigation. Bound
prefetching to the currently settled page and nearest two slides at 640x360,
matching the existing placeholder memory cache key. Visible carousel slides
still request 1920x1080 from the existing authorized artwork URLs. Handle
endless-pager modulo, zero/one/two picks and Int index boundaries in pure
regression tests. Preserve keyboard/TV focus, auto-scroll, image transitions,
source security and content ordering. This is an avoidable-memory coding fix,
not measured device memory savings until the post-build 6-feed UI comparison.

## Build 742 protected HEVC proof indexing, allocation and responsive browsing pass

After the Build 741 phone map-clock and browse-key pass, address an algorithmic
cost identified while reviewing the recorded six-feed protected CMAF windows:
`HevcPictureSequenceAligner.findMasterMatch` previously used `singleOrNull`
to linearly scan every proof match **for each composed picture**. With 288
pictures per CMAF segment, that repeatedly traversed the same 288-evidence
list in the tight protected CBCS composition path. Index the complete
fragment base decode time, local sample index, global ordinal, verified IRAP
epoch and POC **once per aligner-issued immutable proof**; preserve exact
cross-feed timing/KID/IV/HEVC/header validation, and fail closed on duplicate
keys. Test an authentic six-feed, 288-picture, three-epoch metadata-shaped
proof against every indexed picture and negative epoch/POC/owner cases. This
does not modify protected samples, skip inspection or add a decoder.

The six-feed logs show protected segment allocation spikes up to 312 MB and
recurrent GCs. Remove per-picture `fingerprints` lists and per-picture
`distinct` allocation during the strict CBCS IV-equality gate, preserving
identical null constant-IV handling and the exact same rejection conditions.
Report separate alignment, picture composition and CMAF writer timings
alongside existing `PROTECTED_CMAF_SEGMENT` totals and allocation/GC data.
Do not claim an FPS or allocation win until a same-session post-build
hardware log shows it.

Material 3 Expressive UX must feel immediate, not merely look modern:
use a faster 240ms browse/navigation motion and 140ms browse fades while
leaving the existing independent video geometry 380ms transition untouched.
No decoder, protected SurfaceView, Cast or player control geometry changes.

These are coding advances; all 16 physical release gates and device matrix
remain binding. Require Kotlin/JVM, actual HEVC fixtures, signed debug APK
and emulator suite green before calling the source revision CI-verified.

## Build 737 six-feed log audit and Build 740 responsiveness execution baseline (23 November 2026)

**Evidence provenance.** The four supplied rotating Samsung SM-S908E Android 16 log captures are
`pitwall-20261122-172921-705.log.txt`, `pitwall-20261122-183428-432.log.txt`,
`pitwall-20261122-183658-441.log.txt` and `pitwall-20261122-184005-006.log.txt`.
The first reports app version `1.0.729`, while the last three report
`1.0.1474` (the even-numbered debug version of Build 737), NOT Build 740.
Segments overlap and include context copied from earlier segments; counts from
different captures must not be summed as independent observations. These
diagnostics do not contain TCL TV or Pitwall Cast receiver proof.

**Confirmed advancement.** On the S22 Ultra the protected rolling archive reached
`feeds=6 stage=ACTIVE activeVideoDecoders=1 decoder=c2.qti.hevc.decoder
drm=L3 keysLoaded=true firstFrame=true codedFormat=3840x2176`.
The output decoder rendered more than eight thousand frames with zero reported
Media3 decoder drops, and the user describes six visible feeds playing smoothly.
That is a significant single-decoder success, but it does not establish correct
physical display cadence, UI responsiveness, DRM L1 support, or TCL/receiver parity.
A later six-feed composition uses a smaller `2880x1632` atlas after rebuilding.
Preserve the working one-decoder 3840x2176 six-feed path.

**Confirmed performance and lifecycle evidence (not all causal diagnoses):**
- `PitwallFrames role=player` records slow UI fractions of approximately
  **58.1%** in the six-feed-heavy `183658` segment and **62.1%** in the
  `184005` segment; these are overlapping log windows and are not physical
  video-frame statistics. The latest segment includes a reported 516 ms maximum
  player UI frame; the preceding six-feed-heavy segment reached 444 ms.
  These fail the plan's `<5% slow UI frames` goal.
- Multiple `BufferQueueProducer` groups report
  `waitForFreeSlotThenRelock: timeout`, as well as
  `MediaCodec.release` buffer cancellation warnings. The former appear against
  named `SurfaceTexture` instances during feed-count/atlas changes; investigate
  lifecycle generations, stale producer ownership and consumer latch delays.
  Do not silently suppress the warnings or assume they are steady-state crashes.
- Repeated concurrent-mark-compact GCs accompany the six-feed session
  (108 GC-summary lines in `183658`, with high overlapping capture volume);
  correlate allocations, heap footprint and UI stalls before changing buffers.
- In `184005`, a `MediaCodec$EventHandler` posted to a dead Handler thread
  after the 00:10:08 composition transition. Treat codec release/listener
  callback ordering as a lifecycle regression candidate, not a proven root
  cause of the user's UI jank.
- Source quality selection exposes mixed 1080p/540p renditions. Some playback
  telemetry reports `fps=-1.0` and `surface=-1x-1` although decoded frames
  progress: improve diagnostic separation of unknown metadata and actual video
  scene sizes. `physicalPresentation=NOT_MEASURED` is accurate and must remain.
- The log captures include repeated AndroidX `setRequestedFrameRate=NaN`
  messages. These are platform-side Compose stack messages, not sufficient
  evidence that the app requested NaN or that they explain the UI jank.
- At least one original authorized media source advertises text tracks
  `fr,en`, while others report zero; the CC UI must use actual selected
  audio-owner tracks and real cues. Track advertisement alone is not proof of
  rendered subtitles.

**Feed-count capability: do not confuse selection with valid composition.**
The SINGLE settings selection limit is 24, and clear archive layouts can
legitimately use up to 24 sources when the encoded picture, tiles and hardware
limits allow. Protected mixed-picture planning currently rejects more than
**six** in `HevcMixedArchiveAtlasPlan`; the protected picture composer also
sets `MAX_FEEDS=6`, and protected live setup limits session tiles to 2..6.
There is no verified protected seven/eight-feed implementation. Investigate a
**capability-gated 7–8-feed extension** with actual SPS/PPS geometry, CTB/tile
budgets, decoder max size/rate, memory/thermal budget and entitlement, never
an unconditional `MAX_FEEDS` increase. Retain six as the proven protected
fallback and do not sacrifice six-feed stability. Test clear/archive, protected
archive/live, explicit MULTIPLE mode, TCL standalone and Pitwall Cast separately.

**Material 3 Expressive is a performance contract, not merely a new visual skin.**
Users report sluggish navigation and controls while six-feed video succeeds.
Preserve racing identity and all functionality while profiling:
input-to-feedback p50/p95, browsing shelf scroll and card state, remote D-pad
latency/focus recovery, settings/sliders/overlay interaction, composition/layout/
draw durations and memory. Use real Perfetto UI + independent video layers;
do not infer UI performance from decoder drops or apply arbitrary animations.
For the existing Material/TV acceptance gate, target visible input feedback
without a blocking navigation wait, `<5% slow app UI frames` during stable
controlled sessions, and no repeatable >100 ms input-to-visible feedback at
p95 on the acceptance device under the measured workload. Record test conditions,
display refresh rate and raw traces, including both decoder preferences, both
DRM paths, standalone phone/TV and both native/baked Pitwall Cast. These are
acceptance targets, not measurements already achieved.

**Coordinated coding from this audit.** Align the phone's static map metadata
sampling with TV's Choreographer-free 10Hz approach. Eliminate an extra
`withFrameMillis` callback followed by a sleep while retaining the independent
60Hz draw-only driver clock, seek recovery, flags and all overlay controls.
Give browse/archive lazy shelves stable keys and shared content types so scroll
state and reuse are not needlessly invalidated by catalogue updates. Add
sampler scheduling regression checks. This is a measured-workload-oriented
coding improvement, not yet proof of fixing the 58–62% UI slow-frame rate.

**Release work still open before 100% / 16-of-16:**
1. P0: Validate six-feed 3840x2176 and adaptive 2880x1632 pipeline with
   full BufferQueue/codec-release generation tracing and physical 50fps
   timestamps, 1x/1.5x/seek, controlled decoder/quality source sets.
2. P0: Identify and eliminate remaining main/RenderThread work via matched
   overlay OFF/map/tower/both runs; verify responsive browse/player/navigation
   input latency and stable six-feed scroll/controls under sustained load.
3. P0: Fix and verify TCL single-feed+map+tower, multi-feed, D-pad controls;
   verify Cast marker and tower freshness/actual presentation independently.
4. P1: Profile allocation, GC, output-buffer ownership and thermal pressure
   without removing existing features or weakening DRM.
5. P1: Implement and prove any 7–8 feed support only behind an explicit
   capability/quality gate; preserve clear-archive larger selections and
   current protected six-feed fallback.
6. P2: Finish complete Material 3 Expressive navigation/settings/player
   consistency, large text, focus, gesture responsiveness and accessibility.
7. P2: Finish real source-owned captions across every applicable device,
   decoder preference and clear/protected native/baked receiver path.
8. P2: Publish only after signed official candidate, in-place phone/TV
   upgrade, own-repo release integrity and all 16 hardware evidence gates.
   Keep the full cross-path matrix and controlled 10-minute endurance trials.

**Plan consolidation.** This document is the *one* active engineering/release
plan. Other docs in `docs/` are retained historical diagnoses, implementation
references, fixture specs or instructions, **not** competing execution plans.
Do not delete technical evidence or regression references merely to reduce
document count. At this checkpoint the recorded strict gates remain **1/16**
closed; no Build 737 log or green Build 740 CI is a substitute for new physical
acceptance evidence. Do not inflate the historical 66% baseline on the strength
of a single UI-scheduling commit. Rescore coding progress only after verifying
each workstream's real implementation and linkable tests.

## Build 737/738 coordinated official release hardening

The published APK's monotonic version requires both release entry points to
use the SAME signed candidate and manual 16-gate verification. The older
`pitwall-approved-release.yml` remained a second independent publisher
with an obsolete debug version expectation (`1.0.run` versus the current
`1.0.(run*2)`); its source workflow must no longer attempt to publish.
Retain its user-visible manual entry point as a reusable-workflow delegate
to `official-release.yml`, with the actual accepted run, full hardware
acceptance issue, signed candidate and explicit publish boolean.

The official workflow's mandatory-secret validation previously contained
an escaped Bash indirect parameter expansion: the nonempty test always
received a literal string, so it did not detect missing signer secrets.
Correct the expansion and execute the isolated EXACT shell block from
Python CI tests under both complete and missing-secret environments.
Never log credentials, remove the signature check, or auto-publish.

Hardware evidence and the real signed release candidate are still required.
Code/CI checks do NOT close the updater/signing acceptance gate.

### Build 739 release-gate regression test reconciliation

An existing Python CI test was hardcoded to the old approved-release
workflow's direct APK-publishing implementation and legacy version scheme.
After that entry point was safely delegated to the strict shared official
workflow, those old string assertions became obsolete and failed the
pre-Gradle CI check. Replace the assertions with stronger checks of both
entry points: a manual trigger and human acceptance issue, strict exact
green commit/branch verification, signer and checksum validation, and
no independent APK publisher in the legacy path. Keep the separate
runtime Bash missing-secret test. Do not skip any Python test.

## Build 737 coordinated TV map and receiver timing recovery

Build 737 passed Kotlin, JVM, HEVC, APK assembly and emulator instrumentation,
not TCL hardware playback acceptance. This coordinated pass preserves the
single/multiple decoder setting, legitimate clear/Widevine playback and Cast.

- TV static map sampling no longer registers a main-thread Choreographer
  callback for each 60/120Hz vsync. It updates at 100ms; separate marker
  motion remains on the independent 60Hz Canvas draw clock.
- Pitwall Cast accepts a motion packet exactly one newly-versioned tower table
  ahead of the last validated table when chunk reassembly delays the tower.
  Old and multi-generation-ahead IDs still fail; geometry, epoch, sequence,
  playback ID and input validation remain unchanged. Independent tower data
  refreshes when the next validated table arrives.
- Receiver and sender logs include a count of recovered map frames. Tests
  cover table delay, hard rejection and continued map motion.

The 16 strict gates are still open except the previously closed architecture
gate. TCL single-feed video+map+tower, Cast physical car motion, subtitles,
security, in-place signed release and Material3 hardware acceptance require
the actual device and release evidence before closure.

## Build 736 acceptance checkpoint and coordinated P0 timing/overlay correction

**Build 736** passed the full debug workflow at commit
`03f2264f7aba5d5d8e7825a04f6630b458e573d1` on the existing branch.
This verifies Kotlin compilation, JVM tests, synthetic HEVC/CMAF fixture tests,
APK assembly, emulator RenderNode and receiver JavaScript checks. It does NOT
prove TCL standalone video+map+tower playback, native Cast driver-dot cadence,
or real F1 source captions. Strict acceptance stays **1/16 gates closed**.

Archived position/tower previews formerly constructed `SessionTiming` and its
`TrackGeometry` spatial vertex index on the Android **main thread**, competing
with TV map, timing-tower and player UI. Capture preview fields together on
Main, construct geometry/index on `Dispatchers.Default`, then publish on Main
only if the preview generation remains current. This preserves map/tower
visuals and prevents late preview data replacing newer source positions.
The shared path serves phone/TV, clear/Widevine and both decoder modes without
changing Media3 decoders, surfaces, license sessions or Cast wire formats.

Receiver health now distinguishes a fresh timing packet from a position frame
whose measured future samples have been exhausted. Do not claim smooth Cast
motion when the cars are waiting for new positions. Paused, buffering and
map-off modes are unaffected. The in-player CC chip now uses a semantic
Material 3 tonal button with TV focus behavior and its existing caption
selection flow.

All 16 sign-off gates retain their original hardware and release evidence
requirements. A green build is not a device acceptance report.

## Coordinated post-732 release integrity and Cast clock correction

Build 732 is green on commit 4bc244b370c248865b3091beaa9031601d536c79.
A green compile/test/emulator workflow does **not** close TCL standalone-video,
Pitwall Cast physical marker cadence, secure captions, or any unverified gate.

- Do not jump the phone's estimated receiver cursor to every ordinary 1-second
  playback heartbeat. Use bounded speed slewing, preserving monotonic cursor
  under ±90ms heartbeat jitter. Real seeks (>=750ms), pause, buffering, rate
  changes and explicit user seeks still hard reset, with regression tests.
  Receiver video remains the authority and receives no DRM/codec changes.
  Applies to phone-to-TCL Pitwall Cast in both decoder preferences and both
  supported clear/protected sender paths; Google Cast status uses the same
  controller clock. TV on-device driver dots use the receiver's actual player
  position independently. Hardware jitter proof remains OPEN.
- Preserve in-place release upgrade order by building every official release
  using the accepted green **debug workflow run number**, rather than the
  independent manual release workflow counter. Debug builds use *even*
  serials (2 × debug run number); accepted signed official APK uses the next
  *odd* serial. A future green debug run therefore still updates in place,
  and the existing updater sees the official release as numerically newer
  than the corresponding accepted debug APK. Both retain the same package
  identity and persistent signing certificate. The manually gated release
  source must still equal the current working branch HEAD. The official publisher is
  only cooldarshak/apexview. Manual candidate/publish pipeline checks exact
  commit, green CI run, acceptance issue evidence, signed certificate and
  checksum before publishing. No automatic production releases.
- Keep updater branding consistent with Pitwall TV. A manifest-declared,
  non-exported explicit PackageInstaller result receiver survives activity
  recreation and app-process death; it opens Android's user confirmation,
  records bounded failures for next launch and notifies the active UI through
  a private in-app status action. The Compose listener is disposed with
  AppState and retains application context only. Retain asset-origin,
  checksum, certificate and Android version verification.
- On actual TCL standalone single-feed/map/tower playback, log five-second
  decoder rendered/dropped buffer deltas and media-clock progression alongside
  play-state, L3 and video dimensions. These counts distinguish stalled
  Media3 output from overlay-draw stalls but never pretend to be measured
  SurfaceFlinger display presents. Do not hide the symptom.
- Universal parity requirement remains: SINGLE/MULTIPLE, clear HEVC/Widevine,
  standalone phone/TV, local Pitwall Cast and every accessible caption track.
  The TCL single feed with map+tower and Cast map motion are still BLOCKED
  until real device evidence establishes smooth independent video frames.

## Build 730 closed-caption compilation correction and backward-compatible Cast horizon

The Build 730 caption-enabled source reached Gradle but failed the Kotlin compile gate:
`CueGroup` was imported from the wrong Media3 package, an internal caption
track type was exposed by two public properties, and `FeedPlayer.setCaptions`
referenced an unretained constructor parameter. Correct all together,
retaining the player CC button, actual media text cue ownership, TV CC key,
native authorized receiver caption controls and all existing tests. No test
or caption feature is disabled to make CI green.

The earlier six-step Cast receiver decoder remained unused because the sender
correctly protected installed legacy TV APKs from oversized marker packets.
Add explicit receiver-advertised `pitwallMapPredictionSteps=6` capability to
the existing FCast INITIAL exchange, and transmit six *measured* 200ms future
positions only when the receiver confirms support. Older receiver builds
silently fall back to the original three-step 10-element per-car packet.
The current receiver decoder continues to bound incoming arrays; interpolator
never extrapolates from the last known source position. Add regression tests
for both formats, real track sampling, end holding and interoperability.
This affects map timing for native clear and authorized protected Cast while
leaving clear baked full-picture Cast, MediaCodec, Widevine, audio and the
standalone player routes unchanged. Real TCL motion cadence and receiver
drift remain strict device gates, not closed by simulated traces.


## Native Cast caption source-of-truth: receiver track metadata

After correcting the Build 730 Kotlin errors, review the CC chooser while
the phone's native players are suspended for receiver playback. Reading
their tracks is incorrect: the real TV may advertise a different set.
Send **only bounded language/label metadata** from the receiver's
authorized Media3 TRACK_TYPE_TEXT groups with its existing PLAYBACK_UPDATE.
The phone displays these options for active local Cast, resets them when
a playback ID or connection changes, and rejects malformed or oversized
metadata. No cue text, signed URLs, auth tokens, DRM keys or subtitle segments
are sent. Legacy receiver without this optional metadata remains playable;
an empty list must be visible as "not advertised", not fabricated.

The in-player CC overlay on protected composition follows the newly audible
source rather than holding a stale initial audio-master reference when audio
selection changes. The one-decoder video renderer, CBCS segment validation,
separate clear/protected Cast and authenticated media sources are unchanged.
These improvements are coding coverage, **not** TCL subtitle/video acceptance.

## Mandatory hard rule: universal parity and Closed Captions (new gate 16)

**Source-of-truth rule for every future change, not an optional regression check:**
Verify both decoder preferences (SINGLE/MULTIPLE), clear HEVC and legitimate
Widevine-protected HEVC (device L1 or supported L3), standalone Android phone
and Android TV, and phone-to-TV Pitwall Cast. This applies equally to UI,
video, map, tower, audio, seek, subtitles, player lifecycle and diagnostics.
No change may silently remove a feature or substitute multiple decoders for
the strict one-decoder composition. Keep F1 license/entitlement intact, no
unauthorized decryption, and do not use proprietary Tiledmedia/ClearVR SDKs.

**Closed Captions from the player itself** is gate **16**, separate from the
original fourteen engineering workstreams and the Material 3 Expressive gate.
It is NOT closed by successful compilation or a settings toggle.

Coordinated caption implementation:
- CC on/off and source-advertised language selector in the full player on
  phone/TV, persisted and immediately applied to the current Media3 text
  renderer without restarting video. The existing Settings switch now uses
  the same live control rather than applying only to future sessions.
- Read Media3's actual supported TRACK_TYPE_TEXT groups, not a hardcoded list
  of supposedly available F1 subtitle languages. Report when none is offered.
  The preferred text language follows commentary in Auto mode. Never invent
  captions, translate without content or assert F1 always provides English CC.
- Standard standalone one/multiple-decoder PlayerView tiles already contain
  Media3 SubtitleView. Enable only the selected programme/audio owner's text
  renderer, to avoid captions repeated over every onboard.
- For the clear archived composed one-decoder HEVC scene, preserve the original
  authorized audio source's *audio and text track types* in MergingMediaSource.
  For protected composed video, use the original audio-master's offered cues
  when available; neither path adds a video decoder or modifies the HEVC,
  DRM, license session, composition or protected output surface.
- Retain the native, font-scale-respecting Media3 SubtitleView on custom
  GLES-scene output, subscribing to the authorized player's CueGroup and
  cleaning the listener on player/generation disposal. Do not capture,
  transmit or store protected subtitle content in diagnostics.
- Pitwall TV local receiver advertises an explicit captions capability
  and accepts a bounded caption selection command. Caption-on and language
  settings are applied to the receiver's own authorized Media3 source,
  including legitimate protected direct TV playback. For a decoded scene
  without a native PlayerView, render its actual Media3 text cues over the
  independently composited HEVC surface. A hardware CC remote key works
  on both the standalone viewer and the TV receiver.
- Clear baked phone Cast includes authorized on-screen captions only if they
  are actually shown in the PixelCopy source. The receiver does not re-create
  fabricated captions. Older Pitwall TV receivers have no capability bit
  and remain playable; updating the TV is required for remote CC controls.
  Google Cast composed HEVC video and protected multi-feed receiver sources
  that offer **no subtitle track** still lack a verified source-preserving CC
  delivery path. That is an OPEN implementation/acceptance item, not success.

### Universal release CC / playback verification matrix (all rows required)

| Decoder preference | HEVC source | Playback destination | Caption acceptance |
| --- | --- | --- | --- |
| SINGLE | Clear archive | Phone | Selected real F1 captions in composed scene, on/off/lang/seek/speed |
| SINGLE | Widevine CBCS | Phone | Selected authorised cue owner above secure surface, no extra decoder |
| SINGLE | Clear/Widevine | TCL TV standalone | Exact same above, no video black frames and remote CC |
| MULTIPLE | Clear/Widevine | Phone | Media3 PlayerView CC only on selected source; all feeds survive |
| MULTIPLE | Clear/Widevine | TCL TV standalone | Same, remote focus, no video/frame loss |
| SINGLE/MULTIPLE | Clear baked full-picture | Phone-to-TV Pitwall Cast | Actual captions captured in baked scene, no second overlay |
| SINGLE/MULTIPLE | Widevine direct | Phone-to-TV Pitwall Cast | TV entitlement, receiver's native text track, CC control |
| SINGLE/MULTIPLE | Clear/Widevine native composed | Phone-to-TV Pitwall Cast | Verified text track/cue delivery on receiver; gap OPEN if source absent |
| SINGLE/MULTIPLE | Clear scene Google Cast | Phone-to-TV | Verify caption availability in receiver media; gap OPEN if absent |

For every applicable row, check captions Off/Auto/available languages,
play/pause, 10s seek, playback speed, audio switches, buffering, track
replacement and updated installed version in-place. Include video-only,
map-only, tower-only and video+map+tower on TCL 55C655. Measure video
SurfaceFlinger presentation separately from Compose-caption layout and
the native Cast driver-dot movement. Real F1 sources and logs are required.
**Do not close gate 16 until all applicable entries pass on actual hardware.**

**Progress accounting:** The fourteen-workstream engineering baseline
remains provisional at 66%. Material 3 was separately ~25% before the recent
browse/settings refinements and remains unaccepted. With captions added,
**strict full-release acceptance is 1/16 gates closed**, not 100% or 16/16.
The existing '1/14' and '1/15' figures below are historical baselines and
must not be interpreted as current project-level release sign-off.
Even a fully green CI validates implementation contracts, not F1's actual
subtitle renditions, OEM Widevine or sustained TCL compositor performance.


## Coordinated post-699 engineering corrections

Source changes following the Build 699 baseline:
- Protected SurfaceView EGL teardown no longer waits up to two seconds on
  the Compose/TV UI thread. Clear TextureView and protected SurfaceView have
  separate surface-lifetime gates so a retired surface never receives the
  late player-attach callback; a video-quality/atlas change retains the active
  output's lifetime. Tests verify both event sequences. This is a scheduling/lifecycle fix, not physical FPS proof.
- Protected, native and baked Cast HTTP relays bind only to their selected
  local interface instead of every available device interface. Peer filtering
  and random relay tokens remain intact.
- Rolling HLS publishing retains unchanged resource buffers by identity and
  zeroizes only retired buffers; this removes repeated full-segment copies
  from baked Cast publishing without storing protected samples.
- Home/shelf actions, selected settings and cards use a consistent accessible
  dark/red Material 3 expressive visual language; the video canvas is unchanged.
- Clear TextureView, Widevine L1/L3 SurfaceView and both TV Cast renderer
  types now share a reusable GLES quad-buffer cache. Dragging and resizing
  windows rewrites float buffers rather than repeatedly allocating native
  direct memory. JVM tests verify data/identity and size transitions.
- The Widevine L1 secure SurfaceView can request the same seamless-only
  display frame-rate hint as L3 without altering the protected decoder path.
  Actual OEM acceptance remains required.
- Rolling protected HLS source-quality reporting is now change-driven with
  a five-second heartbeat rather than a repeating ~250ms log storm. A real
  quality change is logged immediately; selection, validation, caps and DRM
  remain unchanged.
- Build 694 app-side diagnostics sampled the Android display at alternating
  60Hz and 120Hz during sustained 50fps playback. This is evidence of mode
  instability, NOT proof of a specific cause. Validate source frame-rate
  hints, selected mode and physical display timing together after changes.
- Test all changes in CI, and on phone, TV and both authorized Cast modes
  before marking their exit gates complete.

## Post-705 coding: parity and release integrity

- Both clear and Widevine single-decoder renderers now honor playback-speed
  changes when setting Surface frame-rate hints and in the 50fps cadence gate.
  This covers 1x, 1.5x, and slow-motion without resetting the encoded atlas.
- Owned-repository releases must include SHA256SUMS; the updater verifies exact
  asset metadata, complete bounded download, SHA-256, package ID, newer Android
  versionCode and installed signing certificate before install. No automatic
  untrusted third-party APK update is allowed.
- Physical acceptance is separate; GL EGL swaps are not SurfaceFlinger presentation.

## Responsibility split for release-quality lifecycle ownership

- The phone/TV session models, panel persistence and tile geometry are now
  isolated in `UiSessionModels.kt` rather than the 6,855-line `AppState`
  coordinator. Persistence restore validates a complete geometry snapshot
  before publishing any Compose state, with cross-canvas regression tests.
- Cast, protected and clear renderer ownership remains in their dedicated
  classes; further coordinator extraction and instrumentation are still open.

## Clear baked Cast: independent encoder scheduler

- Window PixelCopy and AVC encoder GL texture upload, codec output draining
  and transport sampling run on `PitwallBakedCastEncoder`, with an independent
  EGL context. The 50 fps HEVC scene renderer no longer executes 25 fps
  capture and codec work on its time-critical decoder-latch GL looper.
- This retains complete visible viewer capture, including Compose timing tower
  and map, and explicitly refuses protected windows. Widevine HEVC continues
  to use receiver-side entitlement and secure surfaces.
- Added measured capture/encode interval logs and deterministic 25 fps pacing
  regression. PixelCopy still incurs a window copy and CPU upload on its own
  worker, so a verified direct-GPU route may be required if actual device
  measurements exceed the performance budget.

## Platform baseline modernization for 2026 mobile and Android TV

- Coordinated AGP 8.13.2 / Gradle 8.13 / Kotlin Compose plugin 2.3.0
  toolchain upgrade with Android compile+target 36, Compose BOM 2026.11.01
  and Material 3 1.5.0 stable. This closes the stale source-level target
  configuration for Google Play mobile updates; separate Play submission,
  policy, licensing and real-device Android 16 compatibility checks remain.
- Android 36 SDK and build tools are installed in CI, and the older
  approved-release signing tool remains for signature verification.
- FrameTimeline analysis uses the official API 36 vsync identifier constant,
  without attributing MainActivity window jank to an independent video layer.
- This is a deliberate compatibility change, not a claim that an upgraded
  targetSdk makes the app smoother. CI and TV/mobile acceptance are mandatory.

## TV receiver speed and circuit parity

- The explicitly requested Cast playback speed is Compose-observable and
  supplied to both clear and secure video scene renderers on the TV. Speed-only
  updates change the decoder-surface FPS hint without allocating a new codec.
- New circuit geometry invalidates only the old car frame, never the separate
  timing tower state; no stale previous-circuit dots are shown on transition.
- MapPanel restore tests run in Robolectric with a working Android JSON runtime.

## Kotlin 2.3 compiler configuration

- The Android 36/Material 3 build upgrade first failed at Gradle script
  compilation because Kotlin 2.3 has made the legacy `kotlinOptions.jvmTarget`
  setter an error. The Android module now configures JVM 17 through the
  typed `kotlin.compilerOptions.jvmTarget` DSL; this is a build-system
  compatibility correction, not a playback change.

## Independent physical SurfaceView video-layer reporting

- Perfetto FrameTimeline analyzer now reports app UI, independent SurfaceView
  video layer, and SurfaceFlinger slots separately. Deduplicates video frames
  by surface token and layer, with video-layer p95/max presentation gaps.
- Tests prevent MainActivity jank from being misreported as video failure,
  and keep clear TextureView within the app HWUI window.
- Actual physical source-frame identity and A/V sync still need independent
  device traces; a display refresh count cannot prove 50fps HEVC pictures.

## Historical Android 36 / Material3 Expressive library compatibility and current migration

- The earlier CI AAR-metadata gate rejected Material3 1.5.0 and Compose
  1.13.0 on AGP 8.13: they require AGP 9.1 and compile API 37. The previous
  mitigation used BOM 2026.06.01 with Material3 1.4 on AGP8.13.
- The post-Build-763 coding branch deliberately upgrades to Material3 1.5.0
  and Compose BOM 2026.11.01, AGP 9.1.1 / Gradle 9.3.1, compileSdk 37,
  while retaining targetSdk 36 and minSdk 29. Material Slider now uses
  the state-based custom-track API and guarded videoCapabilities on API37.
- **Build 781 fully passed** the migration's CI (Kotlin/JVM,
  genuine HEVC decode fixtures, debug/instrumentation APK and native
  RenderNode instrumentation). The original sdkmanager index did not
  discover `platforms;android-37`; the workflow now installs/verifies
  Android 36 and signing tools early while Gradle's real compilation
  and AAR metadata enforce compileSdk 37. Later slider and telemetry
  regressions remain subject to their own CI, not to Build 781. No DRM or player feature is removed.

## Tower retained-layer key optimizations

- TV/phone timing tower RenderNodes now store their immutable base, sectors,
  and tyre visual records directly. The prior three `remember { Any() }`
  identity-token layers allocated extra keys and obscured the structural
  equality contract. Tests verify equal fresh timing snapshots replay all
  three regions unchanged, preserving display list region boundaries.

## Native TV Cast adaptive HEVC geometry acceptance

- Receiver SCENE updates no longer reject every atlas geometry change.
  Clear/secure adaptive CMAF can legitimately change coded dimensions or
  mixed source crops when the same authorized feed identities continue.
- A pure transition gate requires unchanged source count and feed ID-to-source
  mapping while accepting validated new source rectangles, video picture
  dimensions and layout. For direct single-source receiver delivery, encoded
  geometry remains pinned to the native media format.
- Media3 must still decode the chosen rendition, and the renderer invalidates
  its old atlas texture before the first new output. Never take an unrelated
  feed mapping or fall back to additional video decoders.

## Compose shared-transition API modernization

- Compose 1.11 replaced the deprecated `ResizeMode.ScaleToBounds`
  constructor with `ResizeMode.scaleToBounds`. Preserve artwork-to-hero
  shared element resize/crop transitions with the supported API rather than
  removing motion or downgrading the rendering UI.

## Multiple-decoder strain policy extraction

- The old app-state-only accelerated-playback decoder-drop heuristic now
  lives in `PlaybackStrainMonitor`, with source-count identity and stable
  10s warmup, five-second 3-of-5 drop thresholds and speed-specific
  pressure memory. New tests cover sustained drops, buffering, feed changes,
  and explicit SINGLE scenes never enabling MULTIPLE decoder fallback.
- `AppState` retains only player measurement and presentation control,
  preserving existing TV/phone behavior while isolating the policy from
  playback UI/DRM session lifecycles.

## Explicit source-rendition transparency on protected and clear HEVC

- The quality selector now displays per-feed actual source picture dimensions
  from both clear archive composition and authorized protected rolling CMAF
  geometry. A user-selected 1080p ceiling resulting in 720p/540p from F1
  renditions or a TV source cap is visibly disclosed, never silently presented
  as fulfilled.
- The same source-geometry map is updated when protected adaptive snapshots
  change and remains read-only to the playback/DRM pipeline. Map/tower and
  scene choice remain independent. Pure regression tests cover mixed-size
  and equal-size source layouts and quality-ceiling disclosure.

## Android 16 test toolchain compatibility

- CI's 638 JVM test cases compiled under the Android 36 target, but six
  Robolectric-runner cases failed because version 4.14 only understands SDK
  targets through API 35. Use Robolectric 4.16.1, which supports API 36,
  and JDK 21 for the Android 16 test SDK. Kotlin/JVM app bytecode remains 17.
- The runtime/toolchain migration gate is not closed until all 638+ tests,
  APK assembly, synthetic HEVC and receiver tests pass together.

## Build 710–720 CI regression, root cause and coordinated correction

**Not a release acceptance**. Builds 710–716 initially failed for Kotlin DSL
migration, AAR metadata or unsupported Compose symbol mismatches introduced by
the coordinated build-tool / targetSdk / Material update. Those compilation
issues were resolved by Build 719, which reached the test execution phase.

- Build 719 failed Robolectric test *initialization*: targetSdkVersion=36
  exceeded test runtime maxSdkVersion=35. This is a test SDK selection mismatch,
  not a failing assertion about playback. Robolectric 4.16.1 supports SDK 36
  with JDK 21; set the test runtime SDK to 36 explicitly.
- Build 720 compiled Kotlin and test code but Maven Central returned 429 for
  `org.robolectric:nativeruntime-dist-compat:1.0.18` while populating the JVM
  test classpath. The dependency was not downloaded, so tests never ran.
- Keep compile/target API 36. Prefer the maintained Google-hosted Maven Central
  mirror ahead of `mavenCentral()` and set the Robolectric runtime SDK to 36.
  Do NOT suppress test execution, disable flaky-test evidence, or revert the
  platform upgrade to mask a test-infrastructure issue.
- Close this gate only after all JVM and instrumentation tests and signed APK
  artifact finish on the same new branch commit.

## Build 694 trace recovery and post-721 protected allocation correction

**Physical evidence remains open.** The original Build 694 application log and
118 MB Perfetto trace were recovered from the project file library. Decoding
FrameTimeline records directly confirms substantial MainActivity UI jank in the
~25 second trace: 1,068 actual app surface frames, 304 app-deadline-miss
flags, and 879 buffer-stuffing flags. SurfaceFlinger has 1,535 actual display
slots, 251 late and 11 dropped; these are **NOT** independent HEVC picture
presentations. The trace does not identify an independently timed Pitwall
SurfaceView video layer, so 50fps physical video delivery is unmeasured here.
Do not use app frame counts or display refresh as a substitute.

The Build 694 second EGL_READY at 13:22:58.731 followed a documented window
invisibility / SurfaceView destruction at 13:22:54, rather than demonstrating
an unexplained steady-state EGL reboot. Startup's temporary black/partial
composition remains an earlier symptom awaiting Build 721 hardware replay.

The protected CMAF producer also has a concrete avoidable allocation path:
the sample composer previously used ByteArrayOutputStream + toByteArray, and
the fragment writer built a full mdat payload array before concatenating
another full final segment. Replace both with precisely sized owned output
arrays, as the clear CMAF writer already does. Preserve exact NAL lengths,
CBCS encrypted-byte ranges, IV fingerprints, validated moof/trun offsets,
bounded output checks, and independent CMAF post-write inspection. Exercise
multi-NAL prefix lengths, uint16 clear-run boundaries and multi-sample
fragments with generated real HEVC fixtures. This is an allocation reduction
by construction, **not** proof that UI jank or frame pacing was fixed.

Cross-path impact: the change is inside the shared authorized protected
archive/live fragment writer and their native protected Cast relay source;
clear HEVC uses its existing single-buffer writer and is unchanged.
Single-/multiple-decoder selection, codec ownership, Widevine, phone, TV and
receiver entitlement remain unchanged. CI regression is required; Samsung
S22 Ultra, TCL 55C655, clear baked Cast and native protected Cast need
hardware verification before any exit gate closes.

## Coordinated Build 721 evidence and Android TV / Cast / Material 3 hardening

This batch follows the confirmed successful Build 724 on the same active branch.
The new uploaded **Build 721 phone** log records approximately five Cast frame
packets per second, successful sender/receiver telemetry reports, and timing
clock-epoch changes around seeks. Receiver-health ACKs are evidence of packet
transport, **not** verified TCL SurfaceFlinger presentation. It also records
late F1 host DNS errors (a distinct network failure). No direct TCL crash/video
trace has been supplied. Native TCL single-feed plus map and tower remains a
**P0 blocked device acceptance** case; the user reports this is not playing.
Pitwall Cast single-feed/map/tower playback is reported functional, but car
motion on the receiver is visibly uneven. Neither is signed off.

Coordinated code changes for the next full workflow:
- Keep driver markers on the independent **60Hz Canvas Draw clock**, but sample
  TV/phone non-motion map content (flags, selection hit targets and other
  composition inputs) at **10Hz** instead of recomposing 30Hz. Native TV
  single-feed+map+tower now also logs decoder state, video dimensions,
  readiness, software DRM and position every five seconds when active, so
  the unresolved physical failure is identifiable from one TCL trace. The 98ms
  wake interval is checked at both 60Hz and 120Hz vsync, so it cannot
  accidentally publish only 6Hz at 120Hz. This reduces
  overlay UI pressure while a TV is decoding and drawing tower rows.
- Keep legacy Cast V1 sender frames at the existing 600ms horizon (three
  measured 200ms intervals); installed older TCL receivers reject packets
  containing more coordinate pairs. The updated receiver can accept bounded
  future 1.2s horizons, but a sender MUST negotiate that capability before
  emitting an expanded packet. Apply monotone cubic interpolation to the
  existing measured positions, preserving exact endpoints without corner
  overshoot. Add receiver clock delta/prediction-saturation diagnostics to
  the five-second health heartbeat to distinguish clock jitter from TLC
  GPU draw stalls. No unbounded extrapolation or new timing endpoints.
  Regression tests cover nonlinear coordinates and V1 compatibility.
- Replace the existing home/hero and player chrome actions with semantic
  Material 3 buttons and icon buttons with dedicated remote focus feedback;
  give browsable cards
  tonal surfaces, strengthen shape hierarchy, show the Pitwall TV identity
  consistently and update racing accent sliders. No video SurfaceView,
  codec, DRM, audio or layout persistence changes are permitted.

Cross-path source impact: map sampling applies to mobile and Android TV
overlays for both explicit decoder modes and authorized clear/Widevine
delivery, and Cast receiver uses the same shared retained map drawing.
Cast packet shape remains V1-compatible with older TV installations, for native
clear/protected receiver timing; the higher decoding bound is receiver-only
until explicit capability negotiation. Baked Cast PixelCopy is unchanged. Clear baked Cast
retains the separate full-overlay capture. Hardware video, remote TV
stability, physical Cast marker cadence and accessibility remain OPEN.

### Coordinated Material 3 browsing and TV accessibility refinement

The same release pass also updates session-schedule cards from small square
40dp rows to 68dp minimum rounded tonal cards with readable labels while
keeping start times, live states and the F1 identity. The feed selector now
has distinct dark/red selected states and spoken selection descriptions.
Grouped settings use tonal surfaces, stronger section labels and a visibly
selected row. Home pagination uses a red active pill; the TCL receiver's
idle screen presents its instructions in a centered high-contrast large TV
panel. These are observable Compose changes using stable Material 3 1.4
APIs; do not describe them as a finished Google TV/Material 3 Expressive
sign-off until accessibility, TV remote behavior and actual screenshots
have been accepted on the devices.

### Design progress as a separately enforced release gate

Material 3 Expressive coding was not included in the original fourteen
equally weighted engineering estimates. Keep their **66%** baseline, and
track **visual redesign ~25%** separately until device screenshot and TV
focus audits support re-scoring. Combining these fifteen equally weighted
workstreams yields a provisional **~63%** implementation estimate, but is
neither code coverage nor release readiness. Strict acceptance is now
**1 of 15 gates closed**, with the dedicated visual gate still OPEN.
The visual gate requires expressive home/browse, player, settings, casting,
TV focus, large-text/accessibility, login branding and clean screenshot
comparison on both devices, without independent-video regression.
Do not mark 100% for any one workstream without hardware evidence where needed.

## Completion score, 22 November 2026

**Engineering implementation estimate: 66% complete; 34% remaining.**
This is an explicitly subjective, equally weighted estimate across the fourteen
tracked workstreams below. It is not measured source-code coverage or CI results.
**Strict exit-gate acceptance: 1 of 14 workstreams fully closed (7%).**
A passing debug APK and instrumentation suite is NOT a hardware release sign-off.
No Build 699 real-device 10-minute playback, TCL or Cast evidence has been received.
Do not increase the acceptance score without specific linked device evidence.

| Item | Estimated implemented | Engineering status | To close the acceptance gate |
| --- | ---: | --- | --- |
| P0.1 foreground segment priority | 85% | Implemented, verify | Foreground p95, seek races, cold/warm sessions |
| P0.2 bounded HEVC memory | 60% | Incomplete | 10-minute allocations/GC and independent verified composition |
| P0.3 one-decoder GL presentation | 60% | P0 blocker | Physical video-layer cadence, no unexplained queue stalls |
| P0.4 retained map/tower/UI scheduling | 70% | P0 blocker | Controlled overlay OFF/map/tower/both, <5% slow UI frames |
| P1.1 smallest valid atlas | 85% | Implemented, verify | Real selected/candidate atlas and supported decoder load |
| P1.2 refresh matching | 60% | Incomplete | Physical 50 fps mode and 1.5x pacing evidence |
| P1.3 quality/thermal policy | 55% | Incomplete | Mixed-res adaptive quality without silent downshift, thermals |
| P2.1 Cast continuity/parity | 65% | P0 blocker | Clear baked and native protected receiver, audio, timing |
| P2.2 coordinator ownership split | 20% | Incomplete | Incremental lifecycle extraction and concurrency regression |
| P2.3 release updates/signing | 65% | Incomplete | Owned release repo, signed release publishing and in-place upgrade |
| P2.4 authorization/network security | 65% | Incomplete | LAN HTTP exposure review and token/backup/log tests |
| P2.5 TV D-pad and overlays | 65% | Incomplete | TCL 55C655 playback/focus/resize/map/tower acceptance |
| P2.6 repeatable CI/physical benchmarks | 75% | CI built | Independent hardware metrics and crash-free endurance |
| P2.7 Kotlin/Media3/GLES architecture | 100% | Closed | Preserve single-decoder invariants; no proprietary SDK |

The numeric row estimates are heuristic and need re-scoring after implementation
and device profiling; do NOT average them into a guarantee of product readiness.

## Required functional and device matrix

Every change to playback, UI/timing, protected media, app lifecycle, casting
or security must have a behavior check across all applicable columns:

| Decoder preference | Source format | Device | Delivery/receiver | Hard invariant |
| --- | --- | --- | --- | --- |
| User-selected SINGLE | Clear HEVC archive | Phone and Android TV | Local | One video decoder, independently cropped feed windows |
| User-selected SINGLE | Widevine CBCS L1/L3 archive and live | Phone and Android TV | Local | Authorized stream, appropriate secure SurfaceView, no DRM bypass |
| Explicit MULTIPLE preference | Authorized clear/Widevine streams | Phone and Android TV | Local | Preserve existing multiple-player behavior; never silently select it for SINGLE |
| SINGLE archive scenes | Clear HEVC and selected commentary | Phone to TCL Pitwall Cast | Baked where allowed; native relay | One receiver picture/decoder; full overlays and audio parity |
| SINGLE protected scenes | Protected HEVC + receiver-side Widevine | Phone to TCL Pitwall Cast | Native protected | Receiver authorization and secure EGL, no phone PixelCopy of protected video |
| Supported single/multifeed | Clear/protected as entitled | Phone to Chromecast / Google Cast | Standard/custom receiver capability | Never claim overlays where a receiver cannot render them |
| Either explicit preference | Source changes, 1x/1.5x, seek, pause | Phone and TV | Local and applicable Cast | No stale atlas, no lifetime leak, no silent quality mode switch |

**Note:** SINGLE/MULTIPLE is an existing explicit Settings selector.
The hard single-decoder rule applies to SINGLE composition, including
multifeed. MULTIPLE remains a separate user choice and must not be removed.

## Unified execution plan and definition of done

### A. Finish video pipeline (P0, release blocker)

- Correlate source segment fetch/parse/HEVC output, MediaCodec, SurfaceTexture
  latch, GL draw/swap, app frame and SurfaceFlinger physical presentation. A
  submitted EGL frame is NOT a presented display frame.
- Explain or remove BufferQueue `waitForFreeSlotThenRelock` timeout bursts by
  producer/consumer ownership and scheduler evidence. Do not hide timeouts.
- Measure p95/p99 composition duration, allocated bytes/GC, app window
  scheduling, thermals, and video frame timestamps. Prefer sampled counters,
  not per-frame logging or main-thread instrumentation.
- Verify clear TextureView and protected SurfaceView host/surface recreation,
  startup GL readiness, 50/100/60/120 Hz pacing, quality switches and seeks.
- No change to authorized CBCS samples, KIDs/IVs or Widevine security modes.
- **Done:** ten-minute real sessions on both devices, two documented supported
  quality sets, no sustained stall/AV drift, physical 50fps acceptance and
  one decoder in SINGLE, including at 1x and 1.5x where hardware supports it.

### B. Retained map and timing tower (P0, release blocker)

- Use immutable, structurally equal visual keys for retained RenderNode layers:
  rebuilding an equal visual key must not re-record a static circuit or tower
  region. Preserve all race data, geometry, flags, colour, selection and focus.
- Preserve 60Hz capped draw-phase motion, with tower model sampling independent
  of car-motion frames and no 60Hz full-tree Compose invalidation.
- Identify and eliminate repeatable >16ms overlay work via `OVERLAY_5S`,
  Perfetto app main/RenderThread and frame metrics. Preserve existing
  track, edge, pit circle and timing table features.
- **Done:** controlled OFF, map only, tower only and both runs; <5% slow app
  frames in stable playback, no frozen steady frames, full feature and TV parity.

### C. Android TV and Pitwall/Google Cast (P0, release blocker)

- Preserve native protected and clear receiver and selected commentary.
  Receiver reports RENDERING only after first actual renderer GL output.
- Maintain independent table state, 200ms position packets, bounded 5-second
  timing health, playback ID and epoch checks; resume from a nonzero sender
  epoch only at authorized new-session initialization.
- Audit sender table snapshot ownership on replacement `SessionTiming`;
  reconnection and seek must not strand the receiver on a stale table.
- Clear baked Cast currently uses PixelCopy and a CPU upload; direct-EGL
  encoder surface retaining *every Compose overlay* still requires measured,
  protected-safe design and independent regression validation. Do not claim
  this path is complete before replaced or proved adequate.
- **Done:** TCL 55C655 native single and four feeds, map/tower and flags at
  1x/1.5x, seek/pause/reconnect, focus/resize/lock, clear baked audio and
  authorized protected native Cast, all sustained without stale timing.

### D. Release security and update flow (P2)

- Use this repository's own GitHub release feed; never offer third-party
  mikeyyy0/apexview APKs as updates to Pitwall. This repository currently
  has no published releases, so an installed app cannot yet self-update
  from its own release feed. Creating signed releases remains an open gate.
- Maintain pinned package ID, monotonic versionCode, persistent signing key,
  backing up layouts but never F1 login tokens or WebView auth state.
- A manual GitHub Actions release gate must require the latest green source
  build on this branch, explicit physical acceptance and an expected signing
  certificate SHA-256 before publishing any downloadable APK. No unverified
  push automatically publishes a release.
- LAN receiver transports are currently HTTP to dynamically discovered IPs.
  Android network-security-config domain allowlists cannot express arbitrary
  local IPs without breaking Cast. Until an authenticated local TLS design
  exists, keep required LAN cleartext compatibility and test token scope,
  request validation and log redaction instead of disabling functionality.
- Scope Android permissions to actual features; review Play distribution and
  platform target-level requirements independently from sideload/debug builds.
- **Done:** release SHA/signature verification, clean install and upgrade in
  place on both devices, no wrong-owner update, and security checklist signed.

### E. Material 3 Expressive product refinement (P2)

- Keep race footage distraction-free, while using Pitwall dark racing colour
  tokens, consistent elevated tonal surfaces, larger grouped settings rows,
  distinct selectable states, shapes, typography and accessible TV focus.
- Scope animations to browser/sheets/navigation. Never animate/proxy the
  independent video SurfaceView or inflate high-rate map/tower Compose work.
- The current Kotlin/AGP/SDK/BOM dependency stack must be upgraded and tested
  as a coordinated compatibility change before adopting newly released
  Material3 1.5 expressive components. Theme/shapes/colors can be modernized
  with existing stable Material3 APIs without forcing an untested build-system
  upgrade or removing controls.
- **Done:** screenshot parity, navigation/gesture/D-pad/accessibility testing,
  large text, phone landscape/portrait, TV focus, and no playback regression.

### F. One consolidated release acceptance, no tiny test loops

1. CI: synthetic clear/CBCS HEVC composition, real decoder fixture
   validation, Kotlin + native RenderNode tests, Cast protocol tests,
   debug and instrumentation APK and signing checks all pass.
2. S22 Ultra: same authorized ten-minute content at controlled 1080+3,
   clear/protected where available, 1x/1.5x, all four overlays conditions,
   both decoder choices, quality switch and seek/pause.
3. TCL 55C655: single and multifeed, software/secure DRM according to device
   capability, TV remote focus/sliders, map/tower, thermal stability.
4. Pitwall Cast S22 -> TCL: clear baked and protected native receiver,
   four-source identity and layout, remote timing freshness, commentary,
   pause/seek/reconnect, handoff back to phone, one TV decoder.
5. Release: automatic owned-repo release, signed app upgrade in-place and
   device back-up/restore. Reject a source-offer or DRM security level that
   cannot support the requested single-decoder assembly; report it.
6. Publish comparative before/after Perfetto metrics and evidence links.
   Mark a cell PASS only with logs/traces, not subjective inference.

## Trace-grounded baseline (Build 694)

- L3 CBCS atlas 1920x2176, four crops, one Qualcomm HEVC decoder, initial
  one-feed `COMPOSITION_NOT_READY` phase preceding full scene by ~9.3s.
- FrameTimeline: 990 late and 77 on-time MainActivity window frames from
  1,068 measured (separate from independently rendered SurfaceView video).
- SurfaceView EGL swaps are submissions, NOT proof of physical 50fps display.
- BufferQueue timeouts, UI pressure and TCL Cast timing remain open until
  after-change device data. No Build 699 physical exit evidence exists.

---

## Historical performance and diagnosis ledger (retained verbatim below)

The following older milestones are preserved for traceability, but any
older "current" status is superseded by the release ledger above.

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

## Build 694 raw trace and coordinated scene-readiness follow-up (22 November 2026)

**Hardware acceptance still OPEN.** The S22 Ultra log and the raw Perfetto
protobuf were inspected. This is not a claim that every P0–P2 item is complete.

- Protected L3 archive: four 1920x2176 source-cropped windows, one Qualcomm
  c2.qti.hevc.decoder, Media3 decoded about 50fps with zero decoder drops in
  the sampled interval. This only demonstrates decoded throughput, not exact
  physical video presentation.
- Preflight: the app deliberately activated a one-camera native fallback with
  COMPOSITION_NOT_READY. There is an approximately 9.3-second gap between the
  logged fallback and the four-window F1_SCENE_UI_ACTIVATED event.
- Independent video SurfaceView: SURFACE_DECODER_SCENE reports many steady
  five-second intervals of approximately 247-250 EGL output swaps, but these
  are **submitted frames**, not externally verified display presentations.
- FrameTimeline: 1068 actual MainActivity surface frames included 990 late,
  77 on-time, one unspecified; 628 specifically carried buffer-stuffing-only
  jank. Distinct global SF display frames numbered 1535, including 1273 on-time,
  251 late and 11 dropped. The trace has **no separately attributable child
  SurfaceView video FrameTimeline record**. Do not attribute MainActivity
  jank directly to the independent video frame cadence.
- Trace scheduler spans approximately 25 seconds: PID 24958 MainActivity
  approximately 16.3 CPU-seconds, RenderThread TID 24982 approximately
  7.7 CPU-seconds and video GL TID 25379 approximately 4.2 CPU-seconds.
  These are scheduling totals, not method-level exclusive CPU attribution.
- The 13:22:58 surface reinitialization followed Android window stop and
  return-to-foreground around 13:22:54-13:22:58; it is consistent with
  normal surface recreation, not proven spontaneous renderer restart.
- Main-thread/UI frame jank, allocation/GC during protected composition,
  periodic SurfaceTexture timeouts and incomplete Cast receiver telemetry
  remain open investigation items. No trace from the TCL or receiver is present.

**Coordinated source change:** Both Android phone/TV clear HEVC
TextureView scene and authorized Widevine L1/L3 SurfaceView scene now
identify output readiness only after the first successfully submitted GL
frame. Epoch-based readiness rejects stale callbacks after
surface recreation and rendition/atlas changes; releasing a renderer
invalidates prior epochs. The viewer displays an explicit, state-driven
preflight/readiness indicator instead of showing a partial multiview
picture. Clear baked capture and replay clip setup require an activated
GL scene. These are additive visual readiness checks, never decoding,
decryption or stream fallback changes.

**Cross-path impact (sign-off not implied):**
- Phone clear archive: shared TextureView event and preflight state,
  with output generation tests. Hardware playback still open.
- Phone Widevine CBCS archive L3 and supported L1: shared SurfaceView
  event and preflight state, no DRM-level changes. L1 hardware still open.
- Protected live/TV: same SurfaceView readiness callback and scene
  switching, TV focus untouched. TCL hardware still open.
- Baked clear Pitwall Cast: no capture from an unready viewer; audio and
  timing transport unchanged. TCL capture verification still open.
- Native protected Pitwall Cast: receiver and authorized receiver-side DRM
  unchanged. Existing independent GL-ready acknowledgement remains;
  receiver freshness/jank hardware validation still open.

**Further required acceptance:** controlled 3840x2176 vs 1920x2176,
1x/1.5x/seek, physical video layer presentation, overlay OFF/map/tower/both
comparisons, timing and badge visual parity, named-layout persistence and
both Cast modes against actual TCL receiver telemetry. No CI-only
sign-off is allowed.

**Layout-only drag/resize safeguard:** VideoScene.sameEncodedAtlasAs compares
coded input dimensions and per-source rects separately from logical output
windows. A layout transition requests redraw without discarding a current
decoded atlas or resetting readiness; actual rendition/atlas or surface
changes do invalidate it. JVM unit tests cover both conditions.


---

## Historical HEVC grid-composer plan, superseded as a standalone document

> Archived from `docs/F1_HEVC_GRID_COMPOSER_PLAN.md` to keep exactly one active plan. Historical claims are not current acceptance.

# F1 HEVC Grid Composer: implementation plan

Current evidence (build 473): the user's phone log records four real, clear F1 archive
feeds composed into one 3840×540 video track, four source decoder release callbacks,
then one Qualcomm HEVC video decoder with one visible 2316×1080 scene output. The
decoder and scene callbacks report approximately 50 frames per second for over a
minute. This is real clear-archive evidence, not proof of Widevine-protected
composition, OS-wide decoder counts, visible pixels in every layout, audio sync or
Android TV playback. The original milestones below are retained as project history.

The latest phone log also records six clear archive feeds composed as one
2400×814 HEVC output. That confirms the clear archive path is no longer capped at
four feeds on that phone. The largest selectable count is 24, but actual playback
still depends on a compatible common rendition and an output picture that fits the
codec's size, tile and level limits. The protected composition path remains capped
at six feeds. Device validation of protected playback, TV output, every layout,
free overlap, seeking and sustained playback remains open. Google Cast now has a
standard player route picker and a relay for compatible clear archive composition;
the Cast receiver shows video only, while timing and map overlays stay on the
phone. A custom CAF layout receiver and sender scene updates are implemented but require Google app registration and TV validation. Cast now enforces at least 720p per source. Protected/live casting and separate UI overlay capture remain open.

The native GLES scene uses a codec-to-texture Surface and a separate visible
output Surface; it is not a claim that only one Surface object exists in the
process.

## Objective

Compose two or more authorised F1 TV HEVC feeds into one newly constructed HEVC picture/stream, then decode that composed stream with exactly one Android video decoder and one output surface.

The source feeds do not need to arrive with a 2x1 tile grid. The composer creates a new output grid by selecting compatible source picture regions and rewriting output parameter sets and slice headers.

Keep the existing multi-player implementation as a fallback until the composed path is proven on real devices.

## Non-negotiable constraints

- Do not use Tiledmedia, TME, ClearVR, or any Tiledmedia-owned SDK, service, code, or binary.
- Do not bypass, weaken, or remove F1 TV DRM. Use the user's authorised F1 TV subscription and Widevine licence flow.
- Never decrypt protected video payloads, expose keys/KIDs/IVs, or persist/redistribute protected F1 media.
- Preserve encrypted coded payload bytes. Parse only syntax legitimately available in clear sample ranges, and validate the encryption subsample map before touching a slice header.
- Keep exactly one video decoder and one output surface for the composed video path.
- Fail closed on unsupported or ambiguous bitstream/container syntax.
- Do not change the production playback path until the isolated composer and its tests pass.
- Do not claim success from a green CI build alone. Require runtime evidence from a physical device.

## Reference implementation and what it demonstrates

Primary independent F1-specific reference:

- Repository: https://github.com/nicbra/f1tv-tizen
- Implementation: https://github.com/nicbra/f1tv-tizen/blob/master/app/service/stitch-hevc.js
- Licence: MIT. Retain required notices if code is ever reused; prefer an independent Kotlin implementation informed by the algorithm.

The reference implementation does not merely concatenate streams. Its high-level process is:

1. Parse each input init segment and locate the HEVC sample entry and hvcC configuration.
2. Parse each source VPS/SPS/PPS and in-band parameter sets.
3. Inspect each sample's NAL units and parse only the clear slice-header window. Use the subsample encryption map to identify protected ranges.
4. Check that feeds represent the same picture moment: matching sample counts, decode-time anchors, per-sample durations and composition offsets, and compatible picture/slice reference syntax.
5. Determine which source slice/tile supplies each output cell.
6. Construct new output geometry and rewrite output VPS/SPS/PPS, including the output tile grid.
7. Rewrite each selected slice header for its destination CTB/tile address and output parameter-set configuration.
8. Copy coded slice data without decrypting or modifying protected payload bytes.
9. Rebuild output samples and corresponding encryption subsample metadata.
10. Package the composed samples as one output track for one decoder.

This reference is an algorithmic guide, not proof that every current F1 feed pair has the required structure or that its Tizen-specific packaging can be transplanted unchanged to Android/Media3.

## Important correction to the existing compatibility gate

The current HevcParameterSetCompatibility gate expects both source PPS records to declare a 2-column by 1-row tile grid. That is the wrong prerequisite for the F1 grid-composition model.

The source PPS describes each incoming feed. The output PPS describes the newly composed picture. Therefore:

- A source 1x1 picture may be a valid single output cell.
- A source with multiple tiles may contribute one or more selected cells, if its tile geometry and slice structure are supported.
- Source tile declarations must be parsed and validated, but must not be confused with the output grid declaration.
- The composer must derive output CTB geometry from selected source cells and write a new output SPS/PPS.
- Source feeds with different resolutions or cell dimensions are not automatically compatible. Only cells that can be mapped to the output CTB grid without resampling or altering coded payload are eligible.

Do not simply remove the current checks. Replace them with separate source-cell validation and output-grid validation.

## Evidence currently available

The branch contains a synthetic clear HEVC reconstruction proof and a physical Android hardware-decoder test. The synthetic fixture proves a narrow tile-slice assembly case. The device test proved that generated synthetic 2x1 and 2x2 streams rendered 50 frames using one hardware decoder instance and one output surface on the tested Samsung S22 Ultra.

The user's recent authorised F1 observations include HEVC feeds at different resolutions, including a 1920x1080/50 main feed and 960x540/50 onboard feeds. Some selected 960x540 feeds were reported with matching format/parameter-set fingerprints and matching observed fragment timing anchors. These are promising observations, but they are not proof of matching per-sample CMAF timing, POC/reference syntax, compatible cell geometry, or successful composition.

The current read-only CMAF inspector is diagnostic only. It does not compose samples or change the production player graph. Reports showing tilesEnabled=false mean the source PPS does not declare a tile grid; this alone must not reject a source as a candidate output cell.

Encryption observations must be treated separately. A reported Widevine L1 security level does not prove that the particular video samples inspected are encrypted. Before protected composition, establish the actual sample encryption scheme and metadata from authorised playback. Do not infer encryption from DRM security level.

## Implementation work packages

### WP0: preserve baseline and establish fixtures

- Keep the current synthetic tile-slice assembler and tests as a regression path.
- Add independent synthetic source-picture fixtures representing two separately encoded, same-geometry feeds, not just two slices split from one original picture.
- Include fixtures with different source PPS IDs, source 1x1 PPS, and supported deblocking override syntax.
- Retain deterministic expected output and FFmpeg decode/hash validation.
- Record fixture encoder settings, parameter sets, frame rate, GOP, POC and reference-picture behaviour.

Acceptance: fixtures are reproducible and the existing synthetic proof remains green.

### WP1: source-cell and output-grid model

Create explicit models for source track/sample-entry configuration, source VPS/SPS/PPS and CTB geometry, source picture and slice layout, candidate source cell selection, output grid rows/columns, CTB dimensions, coded dimensions and display crop, and source-to-output CTB address mapping.

Do not represent source PPS tile count as output-grid tile count.

Acceptance: unit tests prove valid 1x1 source cells can be mapped into a new 2x1 output grid, and reject mismatched CTB cell sizes, unsupported crops, invalid tile geometry and overflow.

### WP2: parameter-set rewriting

Implement bounded bit-level rewriting for output VPS/SPS/PPS:
- Generate valid output picture dimensions and CTB grid.
- Generate output tile column/row declarations and spacing.
- Reconcile parameter-set IDs and references.
- Preserve or correctly regenerate profile-tier-level, chroma format, bit depth, VUI, DPB/reorder limits and conformance-window semantics.
- Explicitly reject unparsed extensions or syntax whose semantics cannot be preserved.

Acceptance: parse rewritten parameter sets back and compare their declared geometry/configuration with the output-grid model. Validate with an independent HEVC parser/decoder.

### WP3: slice-header rewriting

Implement a dedicated slice-header parser/rewriter, separate from payload copying:
- Parse NAL header and slice-segment header only within the verified clear range.
- Map source slice/CTB addresses to output addresses.
- Rewrite PPS references and all dependent syntax whose bit positions/values change.
- Support the exact deblocking override and loop-filter syntax needed by observed feeds.
- Validate picture type, POC, temporal ID, reference-picture-list syntax and other picture-level invariants across selected inputs.
- Preserve CABAC-coded slice data byte-for-byte.
- Correctly handle emulation-prevention bytes and NAL length prefixes.

Do not claim support for a syntax element until tests cover both its present and absent forms. Unsupported weighted prediction, entry-point offsets, dependent slices, header extensions, or other syntax must be explicitly rejected until implemented.

Acceptance: rewritten clear synthetic output decodes and matches expected picture geometry/content. Byte-level tests prove coded payload regions are unchanged.

### WP4: frame/sample synchronisation

For every composed output sample, compare all selected feeds:
- Track timescale and decode-time anchor.
- Sample count and sample ordering.
- Per-sample duration and composition-time offset.
- Picture order count and reference-picture compatibility.
- IDR/CRA and random-access boundaries.
- GOP structure and discontinuities.
- Required parameter-set changes.

Use exact per-sample timestamps, not chunk-duration approximations. Do not compose samples from different moments merely because chunk start times look close.

Acceptance: deterministic positive tests and negative tests for one-tick offsets, missing samples, different durations, POC mismatch, reference-list mismatch and discontinuity.

### WP5: CMAF output and encryption metadata

Initially support a clear synthetic CMAF output path. Then implement protected sample metadata only after the clear composer is proven.

For protected samples:
- Verify compatible scheme, pattern, IV-size configuration and per-sample encryption layout.
- Parse clear/encrypted subsample ranges without retaining KIDs or IV values.
- Ensure every rewritten header byte remains in a clear range.
- Copy encrypted payload bytes unchanged.
- Rebuild senc/saiz/saio and related auxiliary metadata with exact sample-size/range coverage.
- Reject unsupported auxiliary-info layouts and incompatible encryption configurations.
- Confirm that the Android Widevine/Media3 path can consume the resulting packaging without exposing keys or decrypting payloads in application code.

Do not assume separate source DRM sessions can simply be merged. Determine the supported authorised packaging/session model before integrating protected output.

Acceptance: container parser round-trips output metadata; all sample ranges cover exactly; encrypted bytes are unchanged; Android secure playback is independently demonstrated.

### WP6: single-decoder Android integration

- Add a separate composed-media source/player path.
- Feed the composed output to one Media3 video renderer and one output surface.
- Keep Widevine as the decryption authority for protected content.
- Instrument decoder instance creation, codec name, output surface count, rendered frames, dimensions, frame cadence, dropped frames and playback errors.
- Keep the existing multi-player path available as a fallback.
- Do not instantiate hidden per-feed video decoders in the composer path.

Acceptance: physical-device evidence shows one active hardware video decoder and one output surface while the composed picture renders continuously.

### WP7: expand layouts and validate endurance

Only after two-feed success:
- Extend to 2x2 and then other supported layouts.
- Support layout changes without unnecessary decoder recreation.
- Test live edge, replay seek, discontinuity, feed switching, buffering, pause/resume and audio selection.
- Run long-duration and thermal/stress tests on phone and Android TV hardware.

Acceptance: stable playback with measured frame cadence and no additional video decoder instances.

## Original next coding milestone (historical)

1. Add the new source-cell/output-grid data model and tests.
2. Change the compatibility gate to validate source cells independently from the output grid. Do not yet enable production composition.
3. Add two independently encoded synthetic 960x540 source feeds with matching timing and compatible picture/reference syntax.
4. Implement output VPS/SPS/PPS generation for a 2x1 output grid.
5. Add a parser round-trip test for generated output parameter sets.
6. Keep slice rewriting, CMAF muxing and Android integration as subsequent separately testable milestones.

This is the next milestone. Do not skip ahead to encrypted production playback before the clear-stream composer is correct.

## References

- F1 engineering: https://aws.amazon.com/blogs/media/how-formula-1-uses-aws-to-deliver-multiview-video/
- Independent F1 TV implementation: https://github.com/nicbra/f1tv-tizen
- F1-specific stitcher: https://github.com/nicbra/f1tv-tizen/blob/master/app/service/stitch-hevc.js
- GPAC HEVC/tile processing: https://github.com/gpac/gpac
- OpenVisualCloud Immersive Video Sample: https://github.com/OpenVisualCloud/Immersive-Video-Sample
- Fraunhofer OMAF.js: https://github.com/fraunhoferhhi/omaf.js
- Kvazaar HEVC encoder: https://github.com/ultravideo/kvazaar

Review each project's current licence and component-specific terms before copying code or adding dependencies. No Tiledmedia/TME/ClearVR component is permitted.


## 26 November 2026 — expanded G-C consolidated CI candidate after Build 888

Source checkpoint 2e6e1ec6 and the following consolidated candidate add bounded flag-history sampling, native lap/weather lookup, Cast prediction endpoints, immutable mini-sector normalization cached independently of telemetry, metadata-only commentary timing with reference validation, transactional audio batch admission, and clear baked AVC/AAC transport writing into existing immutable native fragment leases with exact-size Kotlin fallback. Native transport continuity is committed only after successful writing. The existing rolling relay window, independent reader leases, device headroom policy, DRM authorization and platform playback boundaries remain. Circuit and edge label/cache invalidation and allocation accounting were tightened; exact new transport bytes are forecast separately from already-owned queued input. Archive pressure trimming also responds to available memory at or below OS threshold. No quality, buffer duration or display backend reduction is intended.

Verification before CI: 112 Python source/contract tests passed, two changed shell scripts passed bash syntax checks. Native/Android compilation, strict Clippy, host/sanitizer and emulator tests remain mandatory and pending. Added native symbol checks, Android parity tests (including transport continuity across engine switches and sector padding), queue rollback and tower reference parity tests. This is a coding candidate, not proof of runtime correctness or measured improvement.

G.21–G.24: reviewed candidate covers the remaining identified flag/header/sector/edge, commentary queue and transport hot paths; platform-owned rendering, playback/audio selection and authorized DRM remain deliberately unchanged. Metadata reference validation and bounded native-admission fallback remain explicit exceptions to an all-Rust hot path. M2–M11: candidate includes additional ownership, pressure, copy and allocation improvements; do not mark universal memory acceptance or all original P0–P2 items closed. M1 attribution, M13 hardware/graphics attribution and matched physical verification remain open; M12 allocator swap remains conditional on fragmentation evidence. G-D and physical sign-off remain pending. Preserve all older gates and original approved targets, including >=20% matched steady-state PSS reduction and no feature/quality regression. Build 888 remains the last verified green build until this candidate passes CI. No default switch, main merge or release authorization.


## Build 891 coordinated-change correction (26 November 2026)

Build 889 passed all 69 Rust host tests but Clippy rejected assignment formatting; 890 corrected it, passed host/build and API 31/36 instrumentation, and failed API 29 instrumentation due to unavailable BigInteger.longValueExact(). The production and instrumentation fix in e8dcf210 adds exact signed-64 conversion using bitLength and BigDecimal.toBigIntegerExact, retaining fractional rejection and overflow checks. It updates seven existing playback/Cast files and the mandatory instrumentation reference. Build 891 stopped before compilation solely because that commit omitted the required update to this ledger; the missing-artifact failure was consequential, not another compiler error. This checkpoint documents the prior production change and adds independent JVM boundary and fractional regression tests. Existing coordinated-change checks, native tests, API 29/31/36 instrumentation, signed APK publication, DRM and quality gates remain unchanged. No physical or G-C acceptance claim until evidence is available.

## Build 892 follow-up — research and partial source checkpoint (26 November 2026; CI deliberately suppressed)

Build 892 physical PSS 655.19 MiB vs historical Kotlin 888 588.29 MiB; cross-build windows are not formal acceptance. G-C remains OPEN. Retain the six workstreams in the Build 892 handover and the 20% matched PSS reduction goal. No quality reductions, extra decoder, or allocator replacement.

Research examined Netflix TV graphics cache regressions and per-build surface accounting (https://netflixtechblog.com/bringing-rich-experiences-to-memory-constrained-tv-devices-6de771eabb16), Netflix device/runtime OOM analysis (https://netflixtechblog.com/formulating-out-of-memory-kill-prediction-on-the-netflix-app-as-a-machine-learning-problem-989599029109), Android TV memory guidance (https://developer.android.com/training/tv/playback/memory), AOSP producer/consumer buffer ownership (https://source.android.com/docs/core/graphics/arch-bq-gralloc), Chromium VideoFramePool allocation compatibility and shutdown (https://chromium.googlesource.com/chromium/src/+/HEAD/media/base/video_frame_pool.cc), and Media3 DefaultLoadControl, SampleQueue and MediaCodecVideoRenderer source (https://github.com/androidx/media). These support generation-aware release, accounting for non-purgeable versus reclaimable buffers, and reuse only at compatible dimensions. They do not prove which Pitwall vendor graphics allocations are retained. No proprietary Netflix/YouTube player source was available; no upstream code copied.

Source checkpoint: P1-F quality sheet now uses channel metadata and retains internal profile keys, with main tile prioritized before deduplication. P0-A manual adaptive selection bypasses bandwidth eligibility for the highest offered rung at/below the request, still subject to Media3 track constraints and exclusion logic; requested/observed source labels differentiated and unchanged effective quality no longer triggers archive rebuild if no current failure. This is NOT completion of all manual quality semantics, all routing or generation readiness. P0-D shares immutable unchanged sector lists across gap/position snapshots; regression checks historical isolation. This targets actual retained history objects but does not establish material PSS savings. No structural graphics rewrite or new buffering policy. P0-B, P0-C, P1-E and remaining P0-A/D/F auditing and tests still open. Exact 892 native symbolization and full referenced-plan review remain outstanding. No physical sign-off or new build: complete unconditional scope first. Tests added but not executed in this checkpoint.


### Build 892 follow-up checkpoint: status and replay history ownership (CI intentionally deferred)

- P0-B: a later explicit retirement correction plus a newly completed S1/S2 can recover a driver before the next lap crossing; stale laps, repeated split values, late S3 and an uncorroborated false flag do not qualify. Existing lap/PitOut recovery retained with chronological/stale-lap guards. Synthetic regression added; exact Lawson incident not yet reproduced.
- P0-D/P1-E: replay cache readers reuse unchanged three-cell sector lists instead of allocating two lists per snapshot. Cache format v3 also persists the previously omitted temporary stopped flag; older cache versions are intentionally invalidated and rebuilt from source, not silently misread. Round-trip regression covers stopped status and shared immutable list identity.
- This is a partial source checkpoint only. Tests not yet executed; no memory reduction quantified, no release/CI dispatch, no six-workstream sign-off. Remaining quality routing, graphics/player ownership and rendering audits continue to be required.


### Build 892 follow-up — native high-water input capacity checkpoint (CI suppressed)

P0-D/M4/M5: inspection of the actual Rust worker found compact input Vec capacity can retain a historical large input indefinitely, even though later successful owned jobs have smaller exact extents. Apply compatible-size reuse and sustained-size hysteresis (same broad principles as Chromium VideoFramePool; no code copied). Each Rust slot records successful completed owned jobs <= half its input capacity; after eight consecutive such jobs and >=1 MiB capacity, the worker drops the now-unused input backing before Ready. The next job allocates exact input lazily. Larger, failed, obsolete, fixed-output or pressure-trimmed jobs reset evidence. Output/reader leases, deadlines, budgets, full selected resolution and one-decoder path are unchanged. A native regression covers hysteresis, interruption and unchanged output. This targets retained native input capacity, not measured whole-process PSS; exact 892 allocation-site attribution and physical proof remain open. No new dependencies, JNI copies, or Kotlin stitching path. CI/test execution pending until complete scope, not a coding-complete claim.


### Build 892 continuation — native retirement reconciliation checkpoint (CI suppressed)

P0-B/G.22/G.23: move the sparse normalized retirement/correction/progression decision to a safe Rust scalar kernel, with actual selection wired into both archive preview/full parsing and live timing ingest. Android still extracts authorized JSON, holds a single chronological domain history and renders it; no new per-frame JNI allocation, no duplicate native timing history, no second clock or competing status model. Only actual status decisions cross JNI; unchanged normal racing deltas do not. Keep the Kotlin reference for Rust OFF or explicit reason-coded unavailable fallback. Rust host regression, exhaustive 128-flag native/JVM instrumentation parity, historical parser/seek regression and mandatory APK symbol gate extended. No claim of exact Lawson incident reproduction or all P0-B coverage: stale topic, mini-sector and telemetry corroboration, receiver parity and physical validation remain to audit. Both master plans updated; no CI until the unconditional scope is complete.


### 892 continuation: manual source-rendition routing checkpoint (CI deferred)

- Forward positive manual per-feed height to rolling local, protected live and native Cast capture even when every feed has the same preference. Auto remains distinct. HLS/DASH common manual selection does not consult the Auto bandwidth budget and does not silently substitute a lower rung when the requested common geometry is advertised but unsafe. A missing common height can still use a legal lower rung; actual-source disclosure and complete generation-safe transition coverage remain open. Existing hard limits, authorization, SPS/PPS and CBCS validation remain mandatory. A manual request outside the capture limit is rejected explicitly. Finite HLS no longer retries smaller atlas sizes after an incompatible manual selection. Added planner regression tests; not yet executed. These are Android manifest/route policy changes, not a Kotlin replacement for Rust composition. All six workstreams remain subject to the consolidated completion and validation gate. No intermediate build.


### 892 continuation: source preference isolation and offer disclosure (CI deferred)

- Source manual preferences no longer reapply per-feed maximum video dimensions to an already installed composed decoder. Restore still receives the remembered preference. Protected handoff now permits its validated full coded atlas, as the finite archive path already does. Global quality changes rebuild the local one-decoder selection only if an effective feed preference actually changed, including protected playback.
- Finite HLS retains only compact advertised dimension pairs for its active session, not extra manifest or media copies. Quality UI discloses missing manual rungs even if lower rungs are available, separates requested and observed dimensions, and labels preflight observations as potentially belonging to the previous source. Protected and Cast offer inventory/first-frame-generation proof remain open. Tests added for missing exact rung and unknown sizes; not yet run. Android track-selector and UI integration remain Kotlin; no Rust composition rollback. No intermediate build.


### 892 continuation: bounded local quality transition coalescing (CI deferred)

- New local one-decoder quality requests within a 150 ms bounded window replace only the pending callback, not the decoder/capture. An already-running preparation is superseded immediately via the existing generation/cleanup coordinator rather than being allowed to publish a stale choice in the window. The callback validates session, exact tile/player selection and current decoder/Cast state; stop, direct capture start, composed replacement and receiver handoff cancel the pending request. UI exposes the pending/preflight state. Regression covers latest-choice-wins and stop cancellation. Existing active playback and authorization are untouched by the pending callback. Full generation-to-first-frame correlation, reverse-to-active optimization and receiver-side quality transition parity remain open. Tests not executed; no intermediate build.


### 892 continuation: Rust tower history lookup locality (CI deferred)

- Native floor-index batching previously repeated a full binary search via JNI timestamp reads for every driver/history on every poll, even when the selected snapshot was unchanged. Rust now validates the previous output index as a hint against the actual neighbouring timestamps, advances at most eight entries, and falls back to the original exact binary search for reverse seek, stale or invalid hints and large jumps. No persistent second history, native allocation or ABI change; the existing bounded scratch indices are reused. Host regression covers duplicates, empty/invalid histories, forward/reverse and arbitrary hints and verifies only two timestamp reads for an unchanged interval. Performance improvement is a code-level reduction in repeated lookup reads, not a measured PSS or frame-rate claim. Native instrumentation and full CI remain pending. No intermediate build.


### 892 continuation: shared immutable native circuit ownership (CI deferred)

- Map/edge-map/Cast projection instances previously each allocated a separate Rust-prepared direct geometry packet for the same TrackGeometry. Share the immutable read-only packet by exact geometry-owner identity; weak keys and weak packet values do not pin retired sessions or unused native storage. Every consumer keeps its own mutable result scratch and strong packet reference during synchronous JNI. The JVM spatial index is now lazy rather than created for every published geometry regardless of actual reference use. No new history copies, no native fallback change, no geometry simplification. Android native regression covers shared identity, read-only backing, replacement invalidation and independent projection parity. Savings bounded by the compact geometry packet sizes, not graphics/decoder PSS. Tests pending; no intermediate build.


### 892 continuation: Android EGL generation fencing (CI deferred)

- Both TextureView and clear/protected SurfaceView renderers now carry the exact Android surface epoch captured before starting EGL initialization through decoder-input attachment and frame callbacks. A slow obsolete initialization cannot acquire the epoch of its replacement. Stale queued bind operations skip buffer-size changes when their surface/readiness generation no longer matches. Failures from an obsolete output are not delivered as failures of the replacement. Existing Media3 producer detach, GL-thread release, secure EGL configuration, cadence and Cast ownership remain intact. Added lifecycle regression for delayed initialization and stale first-frame acknowledgment. This addresses a source-proven race and avoids unnecessary stale reconfiguration; graphics PSS savings and vendor ownership still require measurement. Tests pending; no intermediate build.


### 892 continuation: scalar graphics ownership accounting (CI deferred)

- Added per-renderer scalar ownership snapshots for app-created EGL contexts/windows (including the clear Cast target where present), decoder textures, original surface/readiness epochs and configured atlas/output pixel dimensions. Existing process memory sample reports counts and owner detail independently of platform graphics PSS and explicitly marks GPU/codec bytes NOT_MEASURED. No View/Surface/player references in registry; a replaced owner overwrites its scalar record, destroy retires it after EGL teardown, and a failed initialization records any partially retained app resources. No per-frame snapshot allocation or new graphics surface. Regression covers replacement, simultaneous owners and teardown. Exact vendor allocations remain unmeasured; no claim that all graphics memory is app-owned. Tests pending; no intermediate build.


### Build 892 continuation: Rust worker-owned workspace retirement (CI deferred)

P0-D/M5/M7: source audit identified that the existing native pressure/pause trim released only free slot buffers and the fragment arena; the worker-local Atlas configuration and retained header slab survived until a later seek/reconfiguration or engine shutdown. Coalesce a trim request under the existing engine mutex, wake the worker, and release its cached parser/atlas workspace at a safe job boundary. An in-flight job finishes with its own buffers, immutable fragment/read leases remain valid, and the next job revalidates the exact original configuration lazily. No new thread, JNI call, copied media, output format change, quality reduction, or Kotlin processing path. Synchronous slot-byte accounting is unchanged and does not count asynchronous workspace retirement. Added Rust host regressions for a retained scratch buffer even without cached configuration, waking an idle worker without changing generation, and successful exact output after trim. No measured PSS benefit claimed; test execution pending. This is an intermediate checkpoint, not unconditional-scope completion. Maintain all original six-workstream gates.


### 892 continuation: Rust pressure-retired slot backing (CI deferred)

P0-D/M4/M5: extend the worker workspace trim checkpoint: a compact slot queued, running, ready or reading during pressure must not become the retained warm pair after its final reader retires. Mark only those live slots for retirement on recycle, without touching admitted work or read leases. The next admitted job rematerializes backing lazily. The synchronous trim result counts only immediately freed capacity; no change to quality, queue, admission ceiling, output format, timing or JNI ABI. Extend the Rust read-lease test to verify exact output before reclamation and after rematerialization; add a pending-work pressure regression. Tests pending; no CI until scope complete.


### 892 continuation: publication-owned protected source reporting and full driver names (CI deferred)

- P0-A/F: protected source dimensions now update only after successful player source installation or rolling publication (including live), not when preflight merely validates a candidate. Preserve the last installed dimensions during preparation, discard them on scene teardown, replace the immutable dimension map atomically, and reuse it for unchanged explicit geometry. Missing validated source geometry is unknown, not a guessed atlas crop. Quality sheet separately exposes decoder first-frame wait and the existing composition failure reason. First-frame flag is observable; this is decoder callback evidence, not a SurfaceFlinger or exact source-generation presentation proof. Full driver name comes from the existing authorized timing metadata only when content ID, racing number and available TLA match; otherwise retain metadata-only title/surname fallback and unchanged internal preference keys. Added positive and conflicting identity regressions. No extra data fetch, decoder or Rust processing rollback. Exact first-frame generation/remote Cast reporting and remaining tests still open; no intermediate build.


### 892 continuation: dynamic F1 rendition picker (user clarification, CI deferred)

- User explicitly requested hiding every resolution not actually supplied for a feed, not a special-case 480p removal. In-player default exposes Auto plus the intersection of verified advertised heights across selected feeds; per-feed choices expose Default/Auto plus that feed's advertised supported heights. Unknown/fixed playlists expose no invented manual rungs. Retain previous saved preferences and disclose a now-unavailable request rather than silently change it. TV focus falls back to the first visible choice when a saved selection is absent. Capture HLS/DASH inventory before current manual/Auto caps, retain compact dimension pairs only, and wire finite, protected rolling, direct Media3 and native Cast evidence through their current owners. Direct source metadata cannot be overwritten by atlas track groups or discarded on parking. Existing hard atlas/SPS/CBCS/device gates remain mandatory; offered heights are not a promise that any mixed atlas is compatible. Tests cover arbitrary missing rungs, different per-feed ladders, common intersection, unknown and invalid sizes. No extra network request, no new decoder, no Rust engine rollback. CI pending, no claim of full six-workstream completion.


### Build 892 continuation: exact native heap attribution and downstream fragment retirement (CI deferred)

Recovered the existing 1,538,356-byte heapprofd trace and exact Build 892 arm64 symbols (artifact 15095557955); ELF build ID 7c1b55147fa6399a7668d8f0a478e2b60bd4f1eb matches trace mapping 88. Parsed 134 packets / 24 profile packets / 12 dumps with 64 KiB sampling; early empty callstack samples remain unattributed. Resolved 37 native frames with addr2line. At the sampled peak (73.12 MiB native live), estimated Rust composed output backing 45.68 MiB, engine input/slot backing 9.82 MiB, HWUI/Skia/text 8.38 MiB, media/codec 6.31 MiB, ART/JNI 2.80 MiB, Rust parser scratch 0.06 MiB. Final dump: 64.40 MiB total, 37.71 MiB output, 10.45 MiB input, 7.62 MiB HWUI, 6.06 MiB media/codec, 2.37 MiB ART, 0.06 MiB parser. These are sampled live estimates, not PSS or precise allocator totals. Output chain resolves to Dispatcher::run_owned_single / Atlas::write_window_fragment_to / FragmentOutput::prepare; input to nativeSubmitAtlasOwnedParts / Engine::enqueue_with / buffer. Clock snapshots align later quality-transition window, not initial steady-state comparison. Final sample nearest log boot ms 814137404 (138 ms difference) reports 845.5 MiB process PSS, 407.5 graphics, 186.2 Java, 94.4 Media3; native fragment capacity 39,832,629 bytes, three live backings, no free fragment backing, engine 10,957,211 bytes. Three parked players report zero allocator use and zero active video/audio decoders; residual platform object memory is not ruled out. HWUI represented cumulative ~2018 MiB is churn, not live memory. Peak Rust output may include unpublished worker output, not proof of a leak. Raw trace/log not committed.

The log's sequential Media3 deliveries around segments 774–783 include full ~12–13 MB fragment-sized increments while the archive retains earlier backing. P0-D/M6: add local-only complete-delivery evidence to the existing archive cache. A subsequent complete read can retire older fully delivered unpinned entries in the same ownership epoch, keeping the latest retry resource, all unread prefetch and active readers. A seek or shared Cast/relay lease invalidates eligibility, stale backing cannot mark its replacement; range/partial reads do not count. Actual native deallocation still occurs in Rust through the existing final NativeFragmentLease release; Android DataSource merely supplies delivery evidence, not a new Kotlin stitcher. No changes to decoder buffering, quality, prefetch bounds, DRM, or independent relay ownership. This is delivery, not proof of decoder presentation. Added regressions for same-epoch retirement, pinning, unread/future retention and stale identity. Recomposition on an older cache miss uses existing foreground path; physical seek/1.5x/pacing and PSS effect still require validation. CI not triggered, no claim of unconditional scope completion.


### 892 continuation: direct Pitwall receiver quality control (CI deferred)

- Direct receiver now negotiates explicit source-quality capability, accepts only known numeric quality choices bound to the current load, and uses an exact supported track-height override (or explicitly disclosed lower available rung for a stale saved request). Auto preserves the existing receiver selector/1080 ceiling. Phone and receiver exchange only bounded dimension pairs and actual video-input format, no URLs, credentials or media. Updates are in-place on the existing player/DRM session, not a new PLAY/authorization cycle. Old receivers keep their existing direct playback but manual choices are hidden until capable and a request reports that an update is required. Receiver metadata is cleared at load/stop; stale packets cannot populate new quality controls. Added protocol regression tests; no compile/CI/device verification yet. Composed/baked receiver replacement parity remains a separate open task.


### 892 continuation: Rust position-read locality and stable Android output scratch (CI deferred)

P1-E: Rust position sampling now reads at most two neighbouring coordinates per axis in one bounded JNI region, reusing the second point on the interpolation call. No extra history copy, heap buffer, interpolation change, or JNI ABI change. Host regression checks reads, bounds, reverse access and interpolated result. Android tower presentation updates existing scratch map entries rather than clearing and reallocating nodes for each poll; removed source/seek keys are retired, immutable published snapshots remain isolated. Regression covers source removal, backward seek and restoration. Numeric lookup/order/normalization remain Rust when requested; no new Kotlin processing path. These changes target allocation and JNI churn, not a demonstrated PSS reduction. CI still deferred; no completion claim.


### 892 continuation: exact-source static regression correction (CI deferred)

The Python suite on checkpoint 0653153 exposed a stale string assertion for ResourceDataSource.close after the delivery-proof change. Preserve and strengthen the production wiring gate: verify that the exact local lease is captured, only complete delivery can certify it, and proof is evaluated before unconditional finally-based detachment and release. This is a test correction for the new ownership contract, not removal of the existing cleanup invariant. No production code or CI gate bypass; rerun exact-source Python suite before final dispatch.


### 892 continuation: primary timing lifecycle precedence and mini-sector corroboration (CI deferred)

- P0-B: TimingData explicit retirement/stop/pit/lap fields take precedence over conflicting TimingDataF1 fields at the same timestamp; sparse supplemental fields remain usable. Lower stale lap counts cannot reassert retirement or change stop/pit lifecycle. A previously uncompleted known mini-sector beyond the last completed slot, changing to recognized measured 2048/2049/2051 status within an unfinished current S1/S2, may corroborate an explicit retirement correction. Duplicate, pit/unknown/late S3 values do not qualify. The normalized evidence still passes through the selected Rust retirement decision kernel, with the existing JVM reference fallback only; no competing status history. Added chronological regression for primary/supplement conflict, stale assertions, genuine mini-sector progression and historical seek. No exact Lawson raw-event proof, no telemetry-only un-retirement, no CI yet.


### 892 continuation: per-source Cast reuse and preparation fencing (CI deferred)

P0-A: replace the old common-height ceiling / mixed-rectangle-count shortcut for reusing an archive composition with a per-source validated-dimension check. An available manual rung must match exactly; a stale unavailable choice can reuse only the best known lower advertised rung. Unknown offers do not justify mismatched reuse. Explicit replacement with Auto forces re-evaluation rather than retaining a previously pinned source. Archive and protected Cast preparations capture immutable requested heights before asynchronous work; both reject stale quality requests before publishing replacement media. Existing Rust stitching, secure CMAF validation, receiver authorization and ownership remain intact; the Kotlin changes are Android-side metadata and transition coordination only. Add JVM cases for mixed up/down changes, unavailable and unknown offers, Auto and invalid/missing dimensions. Active composed/baked transition dispatch, additional regression verification and physical acceptance remain open. Checkpoint only; no build dispatched or compile/test pass claimed.


### 26 November 2026 — Build 892 consolidated engineering candidate

Baseline: Build 892 green, acf72c61dffbe31aed4e533f46482f2eefc37568, workflow 45774853027. All six source workstreams of the agreed Build 892 follow-up have implementation and regression coverage, including the previous deferred checkpoints. This is the coding gate for one consolidated CI build, not physical acceptance or a claim that the RAM target is achieved.

- P0-A: Actual offered feed resolutions remain the basis for quality choices. Receiver-owned composition transitions coalesce changes; archive and protected preparations snapshot requests and reject stale publication. Baked transitions validate the replacement clear archive before retiring the previous capture; old capture/audio is retired before replacing the one local decoder. Restart waits for the exact new archive, first-frame readiness and one active decoder. Preserve selected commentary, backend capability, and receiver/selection identity; stop, seek and later changes invalidate obsolete work. Brief receiver interruption during reanchoring is possible and is preferred to parallel decoder ownership. An unrecoverable restart fails explicitly.
- Google Cast: Match remote media identity before acknowledging activation. The custom CAF controller correlates load IDs and waits for loadeddata plus matching content identity; a baked single complete picture is validated separately from source rendition requirements. Preserve the existing custom layout receiver 720p minimum, with an explicit incompatibility reason. Standard CAF playback status does not establish physical display. Updated custom receiver code is packaged and tested but hosted deployment remains separate; unscoped old custom acknowledgments cannot establish new-load readiness.
- P0-B/C/F: retain previous implemented native retirement/recovery and mini-sector fixes, EGL/surface generation and resource-owner fencing, actual source/channel identity corrections and regression checkpoints. No claim of a reproduced and verified Lawson physical trace.
- P0-D: Rust arena pressure trimming records a serial watermark and retires pressure-marked fragment backing at final reader release instead of repopulating the idle cache. Live immutable readers are never invalidated. New leases retain normal bounded reuse. Native regression covers active reader survival, idle trimming, new lease reuse and eventual zero live backing. No quality reduction, allocator swap, JNI ABI change or change to validated media payload.
- P1-E: Cast timing publisher reuses immutable geometry envelopes within a load and caches unchanged timing table fingerprints, including stopped-driver state. No repeated stringify/reparse for identical geometry or serialization solely to detect unchanged tables. Load/geometry changes invalidate the cache. Numerical/media ownership stays Rust; Android-side transition and protocol coordination stays Kotlin.

Verification: exact source at 425c6cfac7aab9ab4b7f898214284b3cacaa937c plus the staged final wiring regression (verified git blob 172ee8ab7ceaf7fe0c971bf598c6bae766aa105d) passed 113/113 Python tests, both Node Cast receiver suites, and receiver JavaScript syntax validation. Rust, JVM/Android compilation and runtime tests require the existing CI toolchain, which is not installed in the staging environment. Existing CI gates remain unchanged. Dispatch one consolidated candidate; do not infer build success before the workflow completes. Main and parent branches remain untouched.

Physical phone, Android TV, clear/protected playback, local and Cast acceptance, 120 Hz cadence, overlay correctness, memory attribution and sustained/endurance evidence are still open. The prior Rust 892 PSS comparison is not a matched proof of improvement; the agreed 20% lower total PSS target remains unmet/unverified. Vendor allocation and exact display attribution are evidence limitations, not grounds for speculative allocator or structural graphics redesign. Such conditional redesign remains deferred pending evidence.


### Build 893 CI correction and scope status (26 November 2026)

Build 893 (0355c8843483ed24ad183f922efb430cc8f55b22, run 45794381473) failed at compileDebugKotlin: direct Pitwall receiver called DefaultTrackSelector-only setExceedVideoConstraintsIfNecessary on the generic Player TrackSelectionParameters builder, causing four compiler diagnostics. Rust host and sanitizer runs each passed 77/77; Android instrumentation and final signed publication were skipped. Retain the load-owned DefaultTrackSelector, apply quality through its typed builder, clear the reference on stop, preserve strict constraints, track overrides, current player/DRM and existing audio/text parameters. Added production-wiring regression; exact-source compilation and all existing CI gates remain mandatory. This is a correction to the already-dispatched consolidated candidate, not a new feature batch.

Status clarification: six Build 892 follow-up workstreams have candidate implementations, not verified sign-off. M1–M11 have previously implemented coding scope (Build 883 ledger), with further 892 memory/ownership improvements; M12 allocator substitution is conditional and not triggered; M13 instrumentation and feasible lifecycle safeguards are implemented, external GPU/codec attribution and evidence-dependent redesign remain open. Do not treat those as a blanket G-C closure: expanded G.21–G.24 and all applicable native path coverage must be explicitly reconciled, and memory/cadence/cast/physical acceptance and G-D remain open. No measured 20% total PSS reduction claimed. Prior G.0/G-B sign-offs unchanged.


### Build 894 instrumentation-count correction (26 November 2026)

Build 894 (b78cd087dad95a439dced39b1ab95730f5ec9e17; run 45799513249) passed the build/host/unit-test job and signed upgrade and G.0/G-A/G-B instrumentation on all three Android API 29/31/36 runners, but the shell gate rejected the successful G-B JUnit footer 'OK (5 tests)' because it still required 'OK (4 tests)'. Later instrumentation and final publication consequently did not run. Update the exact expected G-B test count to five; retain mandatory execution, exact footer, exit status and restricted pre-start retry. Audit all nine mandatory classes: counts 2/4/2/5/2/1/1/1/2. Add a source regression comparing each explicit runner footer with its actual JUnit test annotations, including singular/plural and distinct mandatory class count. No tests removed, production code or playback changed, or gate relaxed. Exact corrected CI and physical sign-off remain pending. Build 892 remains latest fully green.

## 26 November 2026 — consolidated Build 895 continuation

Build 895 at d5fd4bc4fa5e69bd2fe9aa2de635e3d47ded66f1 remains the comparison baseline. Its five required CI jobs passed. The two source checkpoints at 38f0c37 and 18d60cd are included in this candidate.

The S22 Ultra 895 replay initially published 22 ranked rows at about 1.1 seconds. A position-only update replaced them at 3.7 seconds. The five-second checkpoint had zero ranked rows and 22 positions. Standings returned around 7.0 seconds. Outline changes coincided with the reported map blink. The logs measure publication and readiness. Physical overlay visibility needs a screen recording or a frame timeline.

| Scope | Candidate and evidence |
|---|---|
| A / P0.4 / G.22–23 | `ReplayStartupPublication` selects map and tower histories independently against the same actual replay playhead. The saved corrected clock remains the estimate for the same archive while incoming topics resolve. The cached tower stays usable across a position gap within the saved window. A seek outside its coverage drops the bounded preview. Complete history and valid empty data become authoritative. The first usable geometry is retained through progressive updates until complete geometry arrives. Tests cover the 22-row loss sequence, a corrected clock, a position gap, a seek, a session change and an authoritative empty publication. |
| B / M10 | `ReplayCoreCache` distinguishes preview/core hits, version misses, corruption and outside-window reads. V3 preserves the stopped bit. V2 rebuilds because its history lacks that bit. Existing bounded atomic core and preview writes remain in use. |
| C / M1 / M13 | The captured trace failed strict framing. Recoverable samples matched the exact 895 arm64 ELF build ID `dae6f120f5d2dea86b0df1627d887b717d800ab1`. For PID 21226 the recovered peak was 60.21 MiB of sampled live native heap, with 25.79 MiB attributed to native fragment backing and 7.81 MiB to input workspace. Roughly 3.87 MiB lacked a resolved stack in that sample. Platform graphics PSS was about 247 MiB during the selected steady window. Vendor codec and GPU ownership needs device-level evidence. The candidate fences failed scene inputs and releases their owned graphics resources. The clear replay/baked encoder reuses GL texture storage, shader locations and codec output metadata, and repeated close is safe. |
| D / M3 / M6 | Compact completed position arrays once when capacity is significantly larger than the retained history. Progressive startup shares immutable backing and reports unique primitive bytes. On Android the primary replay timing stream uses a bounded temporary file with a streaming preview and ordered line merge. That eliminates the full primary topic StringBuilder and String overlap in the regular archive path. The observed primary topic was 5.46 million characters. Timestamp ties, optional mini-sector precedence and disordered-source sorting use the existing parser. |
| E / G.3–4 / G.9–10 | A cold eligible clear-archive probe reuses the same-input JVM result for the mandatory native comparison. It can publish the first byte-exact Rust fragment through the existing lease. Hardware admission, exact media/initialization checks and fallbacks remain on the normal route. Preparation logs identify cold actual owner and time to verified media and manifest. Preference and Cast ownership are checked again before playback. |
| F / G.24 | Tower samplers retain history indices and row owners across position-only or geometry-only publications on device and in the Cast sender. A true history or speed change updates the display even during pause. The regular and edge map omit hit-target sampling when controls are off and no pit selection uses it. Drawing keeps the existing motion cadence. |
| G / P0.3 / P1.2 | The BufferQueue analyzer filters by PID and a half-open log interval and correlates teardown within the same process. PID 21226 at 16:45:31.235–16:47:21.235 had 91 nonblocking warnings on the scene SurfaceTexture queue and zero blocking-or-unknown warnings. The selected GL windows show continued acquisition/submission during 120 Hz playback. Keep decoder and GL counters distinct from scanout. The candidate also folds the two video transforms into one graphics layer and removes duplicate optional overlay work. |
| H / G.21–24 | The Architecture G ledger identifies numerical owners on entry to clear/protected and direct/composed playback, Cast sender and receiver, and baked output. Cache, layout and clock ownership changes use the same platform boundaries. |

Build 895 steady S22 Ultra process PSS was 649.6 MiB median and 678.5 MiB sampled peak. The next candidate requires matched Rust ON/OFF PSS, visible overlay continuity, 120 Hz frame timeline and low-RAM TV/Cast acceptance to assess the improvement targets. M12 allocator work and M13 structural display changes require attribution evidence.

Local verification includes 78 Rust host tests and an Android arm64 Rust/JNI compile check, 27 focused JVM tests compiled from the replay implementation, source wiring and analysis checks, and both Cast receiver protocol suites. The JVM harness supplied Android and Compose runtime stand-ins. GitHub Actions supplies the full Android project and instrumentation build. The sandbox refused the GPG agent's Unix socket, so the encrypted handoff test is pending the existing CI gate.

### 26 November 2026 — unified G-TV / G-C implementation checkpoint: native atlas ownership

Candidate G-C1/G-C2 and G-TV1 continuation. A dedicated single idle clear-atlas backing replaces the generic 8 MiB free-pool restriction for adopted Rust output. It is charged by actual capacity, not a reservation; reuse transfers its ownership to the worker, only when the existing demand-fit and packet ceiling checks pass. Generic fragments cannot take the atlas backing. Keep the original no-live-reader retirement rule; disabled, low-RAM or low-memory admission, seek, engine close and pressure retire the idle cache, with a monotonic watermark preventing stale reader releases from recreating it. Wipe adopted allocation capacity before reuse. The recovery-tier adoption allowance matches the previously admitted OS fraction after independent Java staging and remaining native engine commitment, instead of reverting to the smaller normal fraction. A fresh Android headroom sample governs adoption; existing fallback and verification remain unchanged. Counters distinguish idle capacity/limit, empty/fit/ceiling misses and allocation failures, without double-counting idle bytes. Rust regressions cover observed >8 MiB backing, pressure with a pinned reader, 1,000 serial adoptions and zero final capacity; JVM diagnostics test accounting and older snapshots. These are source candidates, not physical or CI results. G-TV2–8, remaining G-C1–6 and conditional G-C7/G-C8 work remain open; no release dispatch until unconditional consolidated scope is reconciled. 

### 26 November 2026 — Pitwall authenticated trust and inactive-player synchronization checkpoint

G-TV4 candidate: the existing physical certificate-code comparison now grants playback authorization without a third Allow prompt. Both updated peers remember exact local+remote certificate identities only after their own confirmation (or prior pin) and authenticated capability negotiation. Stored peers reconnect without confirmation; changed installation identity cannot match the stored pair. A bounded 32-entry app-private trust set and explicit forget-all on both devices are included. First-time comparison on both screens is retained deliberately: discovery name/IP and a remote peer claiming that a TV user approved are not authentication of that TV to an unpaired phone. A single-action first-use UX requires a separately reviewed authenticated out-of-band protocol, not silently trusting LAN discovery. Legacy peer does not acquire new remembered authorization. If only one side forgot the other, the already-pinned side exposes the comparison code during connection. Protected F1 entitlement is unchanged.

G-TV5 candidate adds control close attribution (read/write/EOF/queue/local), elapsed time, handshake state, packet counts and last opcodes with no certificate/address/media/token logging. The reader's UI dispatch retains its own cancellation Job rather than replacing it with the scope's Job. G-TV8 candidate skips periodic clock correction for parked, remote-suspended or no-output source players while preserving selected video/audio/text followers. No changes to Rust media processing or DRM. These changes are uncompiled source candidates; secure first-use protocol refinement, automatic reconnect, correlated sender/receiver playback IDs, media transport, timing, Google Cast, graphics and remaining G-C ownership work are open. Full scope remains incomplete; no signed build.

### 26 November 2026 — timing preview ownership and Rust TS integrity checkpoint
G-TV3/G-C5 candidate: avoid canceling an active same-content preview disk write whenever the playhead advances, and retry a failed write instead of treating the scheduled position as a successful cache. Log actual outcome and rename/IO/oversize failures. Trim preview lap-count and crossing arrays to the requested window plus predecessor/recent crossing context, detach the snapshot sublist from the full history, preserve full cache V3 and stopped-bit semantics. Near-start position parsing retains normal worker priority until first valid preview; backward seeks beyond the bounded preview range no longer suppress subsequent map publications. G-TV6 candidate: Rust validates ADTS declared frame size and frequency index before mux and verifies generated TS packet headers, PID order, continuity, PAT/PMT CRC, PES start/PTS markers and first-video-PCR envelope before publishing its immutable lease. Equivalent JVM input validation and regression fixtures are included; no DRM change or proof that this is the source of the TV PesReader warnings. Existing full-stream decode fixture remains. Source changes only; full Android/Rust CI and device acceptance pending. G-TV2, G-TV5 automatic recovery, G-TV7, remaining G-C1–6 and graphics experiment evidence remain open. No premature signed build.

### 26 November 2026 — active-generation native reuse and admission attribution
G-C1/2: one active clear engine's idle permit now survives the final reader, without a fake sentinel lease; explicit native seek, close or pressure revokes it. Generic final-reader release cannot preserve generic idle allocations or destroy the independently permitted atlas backing. Actual post-allocation capacity is checked. Counters add eligible reuse, pinned/disabled misses, rejected adoption, stale release and unique arena peak; snapshot readers remain compatible with prior sizes. Regression includes the observed 7.64/8.18/9.39 MiB neighborhood and final-reader serial reuse. G-TV1: distinguish exact packet/OS/JVM/native forecast failures from open/library failures. No performance claim from counts; tests added, not run in CI. No change to protected media handling or quality. Other unconditional workstreams remain open.

### 26 November 2026 — bounded Cast recovery and confirmed handoff checkpoint
G-TV5/7/8: optional timing geometry/table/frame chunks are enqueued atomically as one bounded batch, at most one pending/in-flight, and dropped batches request a complete next refresh. Control retains its separate queue capacity. Authenticated, negotiated Pitwall connections get one delayed retry for transport interruption, checking the exact previously pinned installation pair; new or changed identity cannot be auto-approved. User disconnect/forget sends a typed notice before closing; delivery of that notice is best effort on a failed link. Local position restoration occurs before retry. Custom Google layout waits for correlated rendering acknowledgment as well as matching CAF status before stopping local playback; baked/default CAF status remains a protocol proxy rather than display proof. Both load variants reject stale callbacks by client/load identity, and bounded 45s watchdog returns playback on unconfirmed load. Tests added for queue order/pressure and retry/handoff predicates. No CI/physical verification, no claim of complete G-TV5/7; receiver/network diagnostics, exact route tests and remaining workstreams still required.

### 26 November 2026 — archive selection and Media3 owner checkpoint
G-TV2/8: collapse quality changes during network preparation using existing bounded debounce, fence exact feed identity and requested quality before decoder release and again before host replacement, and immediately supersede during actual handoff. Cancellation after source release rolls back through existing ownership path. Stage timings are measured across preflight/validation/release/install, while ordinary playing clock advance is distinguished from a discontinuity using elapsed time and playback speed; coverage still independently gates rolling output. G-C4: Media3 owner snapshots now include forward buffered duration, pending observed media loads and cumulative downloaded bytes grouped by explicit track type (default/muxed stays other). Track sample-queue bytes, free allocator retention and back-buffer duration remain not exposed, not estimated. Ledger is bounded, clears pending observations on park/release and does not alter LoadControl. Tests added; not compiled or physically verified. Remaining graphics, timing and memory integration/evidence gates remain open.


## 26 November 2026 — Post-research continuation implementation checkpoint (uncompiled, build gate CLOSED)

Started at 5ef4fe07a9f5b1ce6517c9ad1fa3c2d5a54f914c. Last confirmed green remains Build 896 (75577ebf25ab006871d6d034ff3eb57a0795e2d8; run 45818263209). All changes below are direct commits on feature/architecture-g-rust-stitching with [skip ci]; no consolidated build or receiver deployment requested. Source checkpoint only, not a completed scope. Main and parent feature branch unchanged by this session.

| Requirement | Source changes / commit | Coding and verification status | Still required |
|---|---|---|---|
| G-TV1 / G-C1–2 / M1–M13 applicable | tiled/NativeClearArchiveBridge.kt 77a6cf2, c9a9cdd; fragment_storage.rs 8f7f57e | Memory admission denial retains native eligibility and rechecks the same device planner after a provisional 10-second interval on existing producer; current epoch checked before publication; hard parity and validation failures still fall back explicitly. Native arena preserves an admitted idle atlas across unrelated fragment churn when combined cap permits, wipes full retained capacity, adds 8 MiB regression. Source only; Rust test not run. | Verify all 4 TV denial vectors, JNI/armv7, headroom/pressure, retry and seek/cancel tests, owner accounting, 1000-cycle and physical exact-engine proof. Transient JVM fallback is not native acceptance. |
| G-TV2 | tiled/F1HlsFeedCapture.kt a621fcf | Bounded interruptible rolling manifest and init I/O and interruptible finite caption probes; ARCHIVE_VOD_STARTUP_PHASES timing for offers, video init, audio, captions and probe. No validation bypass or quality change. Source only. | Exact authorized offer/probe reuse, coalesced selection and seek, safe handoff, optional audio/caption critical path, bounded retries and first-visible-frame regressions. |
| G-TV3 / G-C5 | LiveTiming.kt 9d17249 | At most one pending async map preview and one tower preview; stale publication fenced, pending cleared in finally. Source only. | Cold V2/V3 cache, moving clock rank/position coverage, continued preview beyond initial window, bounded history and retained root audit, independent readiness and physical timing. |
| G-TV4 | Existing paired/pinned TLS implementation unchanged in this session | OPEN. | Complete first-time single TV approval with matching-code authentication; reconnect identity/expiry/revocation tests. No weakening authentication. |
| G-TV5/8 | cast/local/FcastConnection.kt fbf76cc; scene/BakedCastCaptureWorker.kt fa6a01b | Terminal socket closes discard queued payloads; capture cleanup and notification independently guarded, thread quit finally. Source only. | Full retry/reconnect, active media and handoff, stale event ownership, timing batch, close diagnostics and foreground recovery tests. |
| G-TV6 | Existing TS/PES native and transport tests retained | OPEN; no new implementation claim. | Reconcile independent native/JVM transport parity and complete regression coverage, packetization/PTS/PCR/continuity and actual routes. |
| G-TV7 | web/cast/cast-scene-controller.mjs f0a4a1f; tools/test_cast_layout_receiver.mjs db86542 | CAF rendering requires matching adopted content and custom playback ID; LOAD errors carry request identity. Isolated Node regression and syntax check passed on copied controller/test matching Git blob SHAs d950e234... / 5faf0f1a...; asset geometry model hash matched, web model text copied but returned blob SHA did not match local copy, so not a full exact-dependency CI pass. | Verify actual CAF customData retention on physical receiver, old error callbacks, standard/custom/baked handoffs, relay lifetimes, receiver packaging and sender integration. No deployment. |
| G-C3 | fragment storage and bridge above | Partial; no new complete local/Cast delivery audit. | Resolve demonstrable duplication, lease/range/retry/native adoption ownership across all routes and tests. |
| G-C4 | Existing Media3 accounting retained | OPEN. | Receiver equivalent, repeated lifecycle, selected/empty track attribution and retained owner evidence. |
| G-C6/G-C7 | scene/SceneReleaseSteps.kt 9f4f6d6; ReplayClipEncoder.kt b3f076b; SingleDecoderSceneView.kt 2c7754e; ProtectedDecoderSceneView.kt 04bcdba; BakedCastCaptureWorker.kt fa6a01b; SceneReleaseStepsTest.kt 1edbea6 | Independent vendor cleanup steps avoid skipping later owners, correct framework vs transferred SurfaceTexture ownership, clear references and no unsafe PixelCopy bitmap recycling, log failures, quit owner thread. Isolated Kotlin helper compiled and synthetic exception sequence passed (helper blob SHA matched 08330f6...); JUnit and Android integration not run. | Verify detach failure paths, GLES current context and capture producer/consumer shutdown, all owner ledgers, comparable clear SurfaceView experiment and physical cadence. No global renderer default promotion. |
| G-C8/M12/M13 conditional | No allocator substitution or renderer promotion | Evidence-gated, not unconditional completion. | Matched physical evidence before structural choices. |

Referenced handovers and master documents fetched; initial handover read, relevant sections of masters inspected, full line-by-line inherited requirement reconciliation not complete. All inherited G.1–24, R1–8, M1–13, P0–P2, A–H and P0-A–P1-F obligations remain in force; do not infer acceptance from this table. Full Gradle/Android/NDK/Rust suites, signed upgrade and physical phone/TV tests not executed. No memory or startup target claimed. Existing workflow android-debug-apk.yml is push/dispatch with docs-only paths ignored; cast-receiver-pages push is restricted to feature/hevc-stitching-poc. Do not trigger while any unconditional source/test gate is open. This checkpoint is not a request for user testing and no unattended implementation will occur.


## 26 November 2026 — further consolidated continuation (source checkpoint; build gate CLOSED)

Continuation started from 7dda3db92f31506f1c846118bc5ec325055e2b9e. Source head before these two documentation updates: c9427765afc03ea643e37754a9c06a23230e2a4f (29 direct CI-suppressed commits, 19 changed source/test files relative to starting point). Build 896 remains last confirmed green. No Android build or receiver deployment requested. This is not a claim of unconditional completion or physical acceptance.

| Workstream | Source changes and exact evidence | Remaining closure |
|---|---|---|
| G-TV1 / G-C1–3 | 37932d3, 4ffdd13 classify explicit native worker BACKPRESSURE separately from invalid output and byte-parity failures, retire the old engine and defer re-admission using the existing bounded 10-second planner recheck; no forced admission or permission to count JVM fallback as Rust. Earlier native unique arena ownership/reuse and actual capacity guard retained. Reviewed existing exact-length network acquisition, immutable archive leases, offset/range delivery and JNI transfer. | Run four denial-vector/armv7, generation, budget, stale/parallel reader and pressure tests; independently measure actual copy/ownership and engine choice across all routes. Full inherited G-C3 audit not closed. |
| G-TV2 | ea441c6, 88e9b1c reuse a finite validated session to prepare one current-position catch-up group before disturbing the original decoder, without chasing subsequent ordinary playback movement. 12e2d73, c942776 overlap validated optional caption discovery with finite video proof (one additional optional I/O lane), install validated offers before publishing the session, preserve mandatory audio validation, cancellation, manual quality and existing player ownership. Separate stage elapsed times explicitly overlap. | Regression coverage for superseded quality/seek, cancellation, cold versus warm proof, offer reuse and actual first frame. Catch-up adds at most one requested group but may increase startup delay; one-lane optional probing is a provisional throughput/memory tradeoff and still waits for completion. Need matched 2/3/4+ startup evidence. |
| G-TV3 / G-C5 | a3f6db2, d10516a, c9cada6, 792c1ea, 5be4a2b, de3d909 introduce preview-only cache V4 storing independent rank coverage (full archive remains V3, legacy V3 preview conservative, V2 still rebuilds). Reject stale preview write; preserve rows through valid map gaps and propagate readiness bounds. bf0b2c1 streams optional TimingDataF1 to bounded scratch with cleanup, chronological detection, existing ordered merge and heap fallback when scratch unavailable; disconnect HTTP owner after close/failure. df62d85, 6e7970b refresh tower preview when the playhead passes proven coverage. | Full cache/migration, cold/seek/restart/hidden overlay, native timing parity and parser/heap attribution tests. Early primary parser still has a limited lookahead then full merge; complete recovery of moving-clock coverage on slow device and full-root/copy audit require further work. |
| G-TV4 | Existing mutual TLS/SAS comparison, authenticated certificate-pair pins and forget retained. No first-use trust-by-discovery. | Single-TV-action first-use remains genuine protocol/UX decision: TV approval alone cannot authenticate the TV's certificate to an unpaired phone (a spoofed receiver can approve itself). Safe alternative: make phone device selection an authenticated out-of-band scan of TV-generated high-entropy pairing data bound to the receiver certificate/transcript; then one explicit TV approval. Keep existing secure two-sided comparison until such protocol is implemented and reviewed. Do not count the original UX requirement closed or weaken existing authorization. |
| G-TV5 / G-TV8 | 549dcce fences cancelled connection attempt cleanup so it cannot clear replacement retry job/service. fcd2a4f, 46bab08, f77eacf send automatic retry intent on authenticated INITIAL, require deliberate sender selection after receiver disconnect even if typed notice is lost (bounded remembered-peer in-memory block, not a new persistent trust credential), preserve unremembered legacy pairing. 41ec51a, ac3052e fence late relay publication/socket ownership and independent close; emit first successful GET response completion by media type and relay ID, not URL/token or actual frame. 3518dda, 57d82e5 independently retire receiver player, scene, allocator, DRM, native Cast track/capture/relay owners. | Complete deterministic reconnect/forget/old-peer/close-race and sender/receiver correlation regressions; block is process-local, no claim of persisted retry revocation; unknown legacy reconnect marker is rejected for a remembered blocked peer. No proof of physical reconnect or sustained playback. |
| G-TV6 | 20cd5e8 Rust TS validator verifies exact PAT/PMT, PES stream ID, declared AAC/ADTS extent including continuation, modulo PTS ordering and PCR relationship, random-access start; corruption/truncation fixture and wrapped continuity counters. | Rust suite, independent native/reference mux parity and generated-output decode fixtures, actual Media3 receiver route. No claim the warnings in 896 came from this exact mux. |
| G-TV7 | Previous custom CAF load fence retained. Both relay families have first successful media response timing and socket/close fencing (41ec51a, ac3052e). | Full standard/custom/baked sender and receiver correlation/compatibility, lease retention and exact route regression/physical test. No deployment. |
| G-C4 / M9 | 9067226, 4864e8c, 6813ebe report receiver and feed scalar media-load snapshots, selected-track buffered duration or unknown, late callback fences; 690a9fe adds lifecycle and bounded ledger regression tests. | Isolated exact-source Kotlin ledger check passed with Media3 stubs, 1000 retirement cycles, late/duplicate completion and bounded overflow. Full Media3 application-looper integration and allocator telemetry, no sample-queue byte estimate or changed buffer targets. |
| G-C6 / feasible G-C7 | fe585b5 independent GPU capture target/surface/texture/shader retirement and reference clearing; native session/receiver independent owner cleanup above. Prior generation/renderer/cadence instrumentation retained. | Validate platform EGL/producer ordering, partial construction, pending GPU producer/consumer and surface failure paths, actual renderer experiment comparability; no unproven allocator/renderer promotion. |
| Conditional M12/M13 | System allocator and renderer defaults retained. | Matched physical attribution before any structural experiment/promotion. |

Validation performed in test scratch only (no cloned development workspace): copied exact Git blob 5bc2a7a9c8156f5b8854306e1f0d09013e1bbe27 of Media3MemoryOwners.kt into an isolated Kotlin harness with minimal Media3 annotation/allocator constants stubs; kotlinc and JVM checks passed. Copied exact ReplayStartupPublication.kt blob c8d8de249de4366f4665e0ec789e82aacaf6961c into an isolated Kotlin harness with synthetic timing/position model stubs; independent coverage, seek boundaries, map-only update, authoritative empty and core replacement passed. These are not full Gradle/JUnit/Android or real-model tests. Rust compiler/Cargo and Android SDK not present in execution container; native regression added but not executed. All actual Android/Rust/ABI and signed upgrade tests remain unrun. No new measured memory, first-frame, cadence or physical acceptance result. Full line-by-line inherited reconciliation still incomplete. No more build until every unconditional source and regression requirement is reconciled; first-time trust decision remains explicit. No user device testing requested and no unattended continuation implied.


## 26 November 2026 — authenticated selection, transport parity and delivery ownership continuation (build gate CLOSED)

Started at 6c4d27a9eca33338011e5402197d2358931aaa72; source head before this ledger update 71a3f9acd455e221ccf0546e27007e27cc58e47c, 26 CI-suppressed commits across 25 files. Last confirmed green Build 896 remains unchanged. No intermediate APK, deployment or main-branch changes.

| Workstream | Implemented source changes | Verification / remaining closure |
|---|---|---|
| G-TV1 / G-C1–3 | d5b0f86 fences seek-generation publication for both native and JVM paths; bcc2ff3/c246d7a preserve explicit native open rejection and bounded backpressure re-admission. | Full Rust/JNI/32-bit tests and owner/copy audit across routes remain open; do not mistake fallback for native acceptance. |
| G-TV2 | 3bbc3d9/71a3f9a bind cached HLS offers to exact authorized manifest URL, not channel label alone. Existing bounded preparation, handoff and quality checks preserved. | Complete exact startup/handoff/selection/seek regressions and review optional discovery critical path. No startup latency claim. |
| G-TV3 / G-C5 | 64b1029 requires actual published position coverage before suppressing refresh and uses conservative minimum end among covering cars. | Full moving-clock/retained-history/cold cache audit and regression still required. |
| G-TV4 | 2292e52, a8810e6, 8dc7380, 0d69e02, 94d3159, 8e4cc6f, 8e17ec3 implement authenticated first-use physical TV certificate fingerprint selection, verify actual TLS peer before sending control requests, display mutual SAS on both devices and require only TV positive approval. Older/manual name-only selection retains mutual confirmation; remembered certificate pair pins unchanged. | Isolated exact-source fingerprint harness passed, blob fbe51bea06be1b4d8ae75e6d1d6c0a36da7b06b7. UI tradeoff: enter six groups of four hex characters from TV as part of selecting, not naked name tap; no camera dependency. Full handshake/UI/forget/cert replacement tests not run. No trust-by-LAN and no entitlement change. |
| G-TV5 / G-TV8 / G-C3 | fe40181, 2d87488, e173d3f, 313a1e6, 9a19a63, a6d6edc retire baked queue samples by owner; asynchronous mux retirement, immutable heap/native leases across window and relay; postcommit diagnostics/retirement cannot invalidate successful publication; queue retirement regression. 3a11c50 correlates control VERSION/INITIAL/PLAY/RENDERING and terminal close by validated random UUIDs without logging URLs or tokens. | Full sender/receiver reconnect, active reader retirement, route and handoff regression execution pending. |
| G-TV6 | 9267c10, da5843c, 0cb3ccf, aff56c34 real synthetic 50-frame AVC / AAC fixture, production Rust writer, independent Python PES/source byte/continuity/PTS/PCR reconstruction, FFmpeg decode/ffprobe checks, JVM parity across consecutive segments, integrated into consolidated CI. | Local FFmpeg equivalent source-generation commands verified 50 access unit delimiters, IDRs at frames 0/25, 95 ADTS units. Rust binary, independent output parser, full exact-byte JVM parity and decode checks have NOT run; no claim of TV transport repair or Media3 route proof. |
| G-TV7 | 4765835 Google web layout receiver uploads only at decoded frame cadence with fallback, load epoch fences callbacks, redraws geometry against cached texture. | Browser runtime/CAF compatibility, stale frame and actual physical Cast regression tests pending; no receiver deployment. |
| G-C6/G-C7 | 249f7d6 releases partially constructed GPU texture inputs and guards deletion by owning EGL context. | Android EGL construction/teardown and physical comparative renderer tests pending. No default renderer or allocator promotion. |

No Android SDK, Cargo or rustc available in this execution environment. Isolated kotlinc fingerprint tests passed, not an Android/TLS integration test. All new regression source and CI wiring remain uncompiled in Android/Rust. Inherited unconditional reconciliation is not complete, including all outstanding G.1–24, R1–8, M1–13, P0–P2 and A–H work. Conditional allocator/renderer promotion still requires measured physical evidence. No percentage completion or memory improvement claim, no user testing requested, no background continuation implied. Keep build gate closed until remaining unconditional source and regression scope is complete.


## 26 November 2026 — finite startup, moving timing coverage and persisted Cast reconnect continuation (build gate CLOSED)

Source checkpoint from 1315ccad5d50232c115f2c0a3ad904f2c66ac2e5 to 990490535c69201f0e83de0421da3092230a01a4: 30 CI-suppressed commits across 20 source/test files, verified by branch comparison. Last confirmed green remains Build 896 (75577ebf25ab006871d6d034ff3eb57a0795e2d8). No new signed build, receiver deployment, main or parent branch modification requested.

| Workstream | Candidate source / regression changes | Verification and remaining scope |
|---|---|---|
| G-TV1 / G-C1–3 | f870232, 9b18613 share fresh OS/JVM headroom calculation between atlas adoption and necessary native fragment copy, retain conservative low-RAM fraction, Java staging reserve, JNI subtraction of outstanding engine commitment and arena hard limits. 046071f adds four physical-denial-vector tests. | Uncompiled Android/JNI candidate, not proven native execution on TV. Remaining simultaneous-owner/copy attribution, generation/reader pressure checks, complete clear/protected/remote route verification. |
| G-TV2 / G.21 | 1abfeee, 91bc282, 5560f6d, c115908, 50bb655, 4293a0a, a70eadb defer alternate audio/CC validation into two bounded session-owned lanes while primary and selected programme validation overlaps video proof. Atomic metadata publication, explicit selected-programme await, cancellation/ownership fences and compatible audio switch; when a late validated caption child is not installed, use existing one-player handoff. d260371 adds concurrency/retirement/failure tests. dac2a55 prepares the exact current initial handoff fragment with the old source clock frozen only during ownership transition, holds user play state until installation. | Isolated Kotlin coordinator harness passed 100 batches with concurrency<=2, failure isolation and cancellation/late-publication rejection; exact copied blob 714f3ce6fb4b564c942351943cfee846554a930f. Full Android integration tests and native/quality/seek/handoff verification pending. Initial handoff freeze and deferred alternate metadata are provisional engineering choices; first-frame and switch latency not measured. Current/clock programme captions still validated before first installation to preserve CC. |
| G-TV3 / G-C5 | 19a3bae, 4de452c, 8fec999 continue primary timing parse beyond first lookahead, reuse completed ordered primary index if supplemental absence known. f2836b4, 24b4c76, 4e8329e, 7300747 bound progressive rank history to actual clock coverage and retain grid-rank/pit/lap predecessors; preview cache V5 preserves original rank baseline, full V3 and older preview V3/V4 compatible. 1f19df4, 46001de bound merge previews and recover backward-seek coverage. 009aa6f, 77dd7bf, 9e7f043, 5867b7c add regression source and correct fixture owning test class. | Full parser/cache/clock tests not executed. Need independent cache readiness for unavailable sources, exact moving-clock/seek/corrected-clock and retained-root verification, no claim of measured timing or memory improvement. When supplement exists, ordered final merge remains necessary for fidelity; no duplicated complete primary index retained for that path. |
| G-TV4 / G-TV5 | 80139a3, ce4c787 persist a bounded manual-reconnect restriction for exact previously remembered TLS pairs. Block auto retry before receiver advertises readiness; updated deliberate selection clears the restriction; legacy/unknown retry marker requires physical mutual-code confirmation. Forget clears both pins and restrictions. 8d47178, 9904905 add storage regression source. | Android TLS/socket/UI regression not run; app-private preference writes asynchronous on disk (ordinary restart persistence, not a power-loss durability guarantee). No trust-by-name/IP, new trust grants or F1 entitlement changes. |
| G-TV6/7/8, G-C4/6/7 | Prior authenticated selection/TS parity/CAF cadence/graphics and Media3 ownership candidates retained. | Full inherited scope reconciliation, route integration, release/callback/range tests and physical proof remain open. No new claim that these workstreams are fully complete. |
| Conditional G-C8 / M12/M13 | Existing allocator and renderer defaults retained. | Promotion requires matched physical attribution and feature parity. |

No full Gradle/Android/NDK/Rust run performed, no source-complete assertion. Remaining unconditional reconciliation must explicitly cover G.1–24, R1–8, M1–13, P0–P2, A–H, P0-A–P1-F and every route. Specifically review exact source/quality selection, optional programme switch and rollback, clock coverage across cache/merge, native and protected lease paths, reconnect/legacy negotiation, standard/custom/baked Cast correlation and renderer/capture/Media3 owner lifecycle. Review all accumulated uncompiled changes before one consolidated signed CI build. Physical G-C/G-D memory/CPU/first-frame/cadence/endurance and both device/Cast results remain outstanding. No device test requested yet; no unattended continuation implied.


## 26 November 2026 — producer retirement, Cast load ownership, batched native processing and independent replay checkpoints (build gate CLOSED)

Source checkpoint from 0a2a467dd2c2d8ad3b4210abb7c9fa481412f7b5 to 529e0d819674c7cf77dc533a038a789e6e7977c2: 40 direct CI-suppressed commits across 28 source/test files, verified by branch comparison. Last verified green remains Build 896 (75577ebf25ab006871d6d034ff3eb57a0795e2d8). No intermediate signed APK, deployment, main or parent branch modification requested.

| Workstream | Candidate source / regression changes | Verification and remaining scope |
|---|---|---|
| G-C6/G-C7 / G-TV8 / G.24 | 5254b8f, de93194, 514e8ce, 156e48a introduce exact producer retirement: close marks capture retired, detaches its producer EGL window on the owner looper before releasing the queue, and defers overlay queue abandonment past the Main Canvas callback. f06a4cf, f788bb3, 647a6ef, 83b2e30, 60df518 balance app-owned overlapping EGL display initialization with independent contexts/windows. b83445f, cc5da39, abfd42d, cf3f67d, 23beae5, ebfd3c1 account encoder inputs, capture textures/queues and pbuffer, avoid unnecessary bitmap renderer allocation on direct GPU Cast and fence late bitmap creation. | Exact copied blobs of ProducerRetirement.kt (3a5c84cdd4b2564c821ea2bf67ab0bd89b684cdb), SceneEglDisplays.kt (47ffbbc88af8c2d06e54c6c3ce734d7b1221c105) passed isolated kotlinc/JVM 1000 concurrent retire/detach races and 1000 display lease cycles with minimal EGL stubs. Actual EGL/MediaCodec behavior, lifecycle failures, retained capture/renderer experiment and physical attribution unverified. Counters are logical owners, not GPU bytes; failed GL post or vendor destroy is not proof of physical release. |
| G-TV5/G-TV7 / G-C3 | 9e329cc retains rewritten audio/CC playlist bytes via immutable relay read leases until final socket completion, atomically publishes validated metadata and prevents late publication. a06fc1d, b19677b, 7ded924, 9512749, efe1c01 expose non-secret relay UUID to sender. 6efbe74, 8c7f1c3, ea3d8a8, b75bf27, 2db1a77, correlate actual Google URL/custom ID/session, reject foreign/stale clocks, use 2s lost-ownership grace and 60s local first-frame watchdog; restore local playback and avoid stopping foreign media. | Exact CastRecoveryPolicy.kt blob 3280e6f194c1ef3fb64936a6a74644ef0c7ae7b4 passed isolated JVM predicate tests. Full sender/receiver and CAF integration tests, transport timeout, legacy negotiation, physical reconnect/cast and relay socket/lease regressions remain unrun. 60s local and 2s ownership grace are provisional engineering choices, not startup goals; standard receiver playback status is still a proxy for visible output. |
| G.23 / G-TV3 / R5 | b06efbb, 4ecafb6, e4bd8d7 replace three native tower floor batches with one 5 histories per driver plus weather batch (bounded at 321), retaining immutable arrays and bounded search hints. Android regression includes 321 histories, repeated times, stale hints and seeks; 322 rejected, existing instrumentation test count unchanged. | Rust/JNI/ARMv7 and whole tower differential tests not run. No measured JNI latency or RAM improvement. |
| G-TV6 / G-C3 / G.21 / G.24 | df33ac3, 800ea0d, cb07f60, 0f15541, 8a7001d, 046a823, e31c4f6, f566149 write clear encoder AVC access units directly into final owned sample with optional parameter-set prefix, Rust selection for eligible direct codec buffers and compatible JVM reference fallback. Entire Rust AVCC input validated before writing, explicit bounds and codec position preservation; no intermediate complete input or converted payload, no change to encoder profile, secure output or transport continuity. | Exact BakedAvcAccessUnit.kt blob f5d9fe3d88e4f66a9be2268af0a167dbb01e28c4 passed isolated kotlinc/JVM 1000 deterministic generated AVCC reference comparisons with offsets/prefix and malformed rejection; stub deliberately does not execute native. Native Rust unit and existing Android test extended; not run. 48 MiB upper size matches baked queue budget; existing Annex-B identification rule retained. |
| G-TV3 / G-C5 | e64ed4e, d9fafc7, 529e0d8 make V5 startup checkpoint independently persist map-only or tower-only clock-covered data (even without geometry for tower-only), promote checkpoint when second topic becomes available, retain complete V3 format and V3/V4 preview reading. Map position window retains successor for valid interpolation; rank history trimmed to actual coverage. Tests extend migration, stale tower and independent readiness. | Full cache and parser/clock regressions not executed. No stale rank acceptance or complete-archive claim. Managed retained-root and actual cold/warm timing proof remain separate. |
| Other G-TV1/2/4, G-C1/2/4, original G.1–24, R1–8, M1–13, P0–P2 and A–H | Earlier source candidates retained. Current review revisited startup coordinator, Media3 diagnostics and native admission/fragment owners, original master gates. | Complete exact-source requirement/route reconciliation and all applicable regression coverage remain incomplete. No assertion of 100% source closure, no new build authorized by this checkpoint. Conditional allocator/default renderer promotion remains gated on matched physical evidence. |

Validation limited to isolated Kotlin/JVM scratch harnesses, exact Git blob hashes verified. No local development clone. Cargo/rustc and Android SDK not installed in execution container; full tests remain for consolidated CI. No physical performance, memory, TV playback or cast improvement claim. Keep the implementation gate closed while unconditional reconciliation is incomplete. No user device test requested and no unattended continuation implied.

## 26 November 2026 — native retirement and rolling handoff follow-up (build gate CLOSED)

Resumed verified branch HEAD 7fd3af4f3470b829d6c76b8fe22d4834e3a5134d. Source checkpoint 22d4f7ea9bc635c3bb48c6b2a18e780a950e5351, eight direct [skip ci] commits across seven files; compared against the starting head. Last confirmed green remains Build 896. No new signed APK or deployment triggered.

- G-TV1/G-C1–3: 9dab3ad, eb61543 ensure producer unlock/disabled retirement and worker slot release or generation fencing execute even if native lease cleanup throws. JVM-only fast path retries disabled engine retirement after unlocking. Native arena d63cb2f wipes initialized bytes and spare capacity once rather than double-writing the spare tail; adds 1000 actual-size synthetic cycles (8,011,000/8,577,000/9,846,000-byte payloads plus prefix), two retained readers, pressure watermark, reuse/identity/immutability, bounded capacity and final retirement. Native test unrun: no Cargo/rustc installed. No memory improvement measured, no headroom relaxation or change to video quality.
- G-TV2/G-TV8: f614fff, e466f3d, 22d4f7e fence exact selection after detached-window IO and after DASH publication before installing the protected Media3 source; retain current first-frame/error state until replacement validates. 005cc9e/cfeb18b preserve original cancellation/decoder failure while independently attempting diagnostics, rollback and disposal; add JUnit regression for all three throwing. Exact copied Kotlin coordinator blob 58688b6a839b2c5247a905e4f39de1c464dde86f compiled in isolated coroutine/JVM harness and passed 1000 failure sequences plus successful handoff. Android integration and JUnit suite remain unrun; scratch tests only, development directly on GitHub. No change to DRM or renderer defaults.

Shared ChatGPT link could not be retrieved; resumed from recovered prior context, exact live branch and master/continuation records rather than claiming full transcript review. Still outstanding: whole G.1–24/R1–8/M1–13/P0–P2/A–H and all G-TV/G-C route-level source and regression reconciliation, including accumulated uncompiled native/Android/Cast/graphics/timing changes. No unconditional completion claim, no physical sign-off, no new build until the existing gate is satisfied. No further user diagnostics requested.

## 26 November 2026 — seek-safe Cast timing and archive initialization ownership (build gate CLOSED)

Resumed the final-scope handover from verified HEAD b183417391230959f1634693270724cac6c00ddd. Verified source checkpoint e8e557d0a7b0186a84278d39929faafdf068daed: 16 direct CI-suppressed commits, zero behind, 14 changed source/test files. The prior native-retirement/rolling-handoff appendix is present in both master plans. Last confirmed green APK remains Build 896, 75577ebf25ab006871d6d034ff3eb57a0795e2d8. No signed build, Cast deployment, main or parent-branch changes requested.

| Requirement mapping | Exact new implementation and regression source | Verification / residual closure |
|---|---|---|
| G-TV3 / G-TV5 / G-C5 / G.23 | e4cb763 CastTimingPublisher attaches clock epoch to table, bypasses ordinary throttle at seek, samples the tower immediately, and publishes an epoch-consistent full snapshot; 7ecd8cf and 8c1e853 CastTimingReceiver reject old-epoch and (after capability established) untagged tables, invalidate stale ranks and freshness at seek, permit current-generation map motion pending a valid table, preserve legacy untagged peer behavior. 4ffc437 CastTimingSeekGenerationTest covers forward/backward seeks, stale/delayed tables, initial mismatch, same-version new epoch, asynchronous path and legacy. | Source candidate only. Legacy sender retains existing semantics because the missing epoch cannot be inferred. Android unit/integration execution, direct/baked/protocol-rendered route integration, independent map/tower behavior and measured freshness still open. |
| G-TV1 / G-C1–3 / M1–13 (applicable memory diagnostics) | b0d4168 atlas_job records known owned workspace capacity (header slab, initialization/geometry backing and Vec descriptors; excludes transient and atlas internals); 9144872 engine provides atomic mutex snapshot of engine ownership, partial workspace known at worker boundaries, unavailable during execution. 6a593f8 JNI returns twelve values; 7e4255b ProcessMemoryDiagnostic decodes the new layout with backward support for nine/ten values; 79a1bbf adds compatibility/unknown/invalid-field tests; Rust boundary and trim regressions added. | Existing conservative headroom/commitment remains unchanged: owned capacity is not measured resident memory. No assertion of full allocator attribution or simultaneous-owner proof. Native compile/tests and ARMv7 ABI verification still pending. |
| G-TV2 / G-C3 / R1 (finite archive) | ad79722 ArchiveInitializationBacking and 353b42b HevcArchiveVodSession transfer exact immutable input init arrays and borrow synchronously during reference/native composition and alternate initialization validation, retiring only after the last reader. No per-segment source initialization clone; source memory accounted once as CLEAR_SOURCE. 4a8b6cc adds shared identity, cancellation/exception, alias rejection and repeated ownership regression. 262e3a0/c6b408c/a66fc84 additionally transfer first verified output initialization to HeapFragmentLease, retain separate Media3 init range handles, preserve legacy copied API and native expected-init validation, wipe only on final close. e8e557d adds identity, exact equality, range bounds/write, legacy-copy isolation, duplicate close and last-reader retirement tests. | Exact-source isolated JVM harness passed 1,000 concurrent two-reader retirement cycles and 1,000 repeated borrows of ArchiveInitializationBacking.kt (a58475a808a09c49055c40b9eb533b38327b6b10) with MediaMemoryAccounting.kt (50a98148134a6b225bf32377a1d664ed5632153f), blob hashes matched. Output-init changes and full Android/JUnit route tests unrun. Existing reference/native media implementation and Widevine policy retained; no claimed copy-byte savings or PSS result. |
| G-TV4/6/7/8, G-C4/6/7 and remaining inherited requirements | Existing accumulated authenticated pairing, transport parity, CAF ownership, graphics retirement, Media3, timing/cache, quality, startup and native candidates retained. Inspected referenced handover requirements and selected source/test integration, not an exhaustive historical-line or all-route reconciliation. | Full finite requirement/source/test ledger remains incomplete: G.1–24, R1–8, M1–13, P0–P2, A–H, P0-A–P1-F, all G-TV1–8/G-C1–6 and feasible G-C7. Specific residual work: native and protected finite/rolling/remote ownership and exact admission vectors; actual startup/quality/audio/CC supersession, callback rollback and receiver route integration; corrected-clock cache/merge and retained timing roots; EGL/Surface/codec and Media3 lifetime across all routes; TLS revocation/legacy negotiation and reconnect; TS independent native reconstruction/decode; CAF standard/custom/baked and handback/foreign ACK; required whole-branch regression/source audit and actual instrumentation count check. |
| Conditional G-C8/M12/M13 | No allocator replacement or renderer default promotion. | Retain evidence-dependent gate and original physical targets. |

Full Rust/Cargo, Android/NDK/Gradle and consolidated CI remain unrun; local environment lacks the full toolchain. No unconditional completion claim, no signed build dispatch, no receiver deployment, no physical testing requested. Do not treat isolated JVM evidence as native, Android, integration or physical acceptance. Preserve the existing G.0/G-A/G-B historical gates and do not change quality, entitlement, DRM or decoder requirements. The final-scope handover remains the execution index.


## Consolidated source-candidate closure and signed CI gate — 26 November 2026

**Supersedes the previous source-checkpoint status, not the physical acceptance criteria.** Scope is frozen at the post-research final handover. Last verified signed baseline: Build 896, 75577ebf25ab006871d6d034ff3eb57a0795e2d8. Source reconciliation checkpoint: 71f3823cfa8eef41b6dd336f3dc7ff033be8796b (19 commits after 6652615). This closing change adds a cross-language regression contract for all four exact TV admission vectors and synchronizes both master plans. The normal signed CI workflow is the next gate. No new physical result, performance number, universal route proof, release approval, or receiver deployment is implied.

### Finite requirement / owner / regression ledger

Status **S** = identified unconditional implementation and regression source present, submitted as a consolidated candidate; full compiled CI and applicable physical acceptance pending. **E** = evidence-gated optional change; not authorized by current evidence. Historical green results remain historical. This is a requirement-to-owner reconciliation using the previously recorded audits and inspected production entry points, not a claim to have rerun every historical test or inspected every line in every route.

| Requirement | Implementation and regression evidence | Status |
|---|---|---|
| G-TV1 | NativeStitchingEngine admission, NativeClearArchiveBridge, Rust engine/atlas workspace bounds, ProcessMemoryDiagnostic; four exact 896 observations now matched in NativeStitchingFoundationTest, Rust engine tests and ArchitectureGBAndroidTest actual JNI fixture submission/read/release. Low-RAM denial and OS/JVM reserves retained. | S |
| G-TV2 | F1HlsFeedCapture, HevcArchiveVodSession, ArchiveCompositionStartup, ArchiveRetirementSteps, FeedPlayer: exact immutable initialization ownership, mandatory vs optional sidecars, rollback/retirement, seek and programme supersession. ArchiveInitializationBacking and startup/ownership tests; exact media parity retained. | S |
| G-TV3 | LiveTiming, ReplayCoreCache, ReplayStartupPublication, TimingWorkCoordinator, DriverTiming, AppState; coherent cache V5 preview vs complete V3, independent map/tower readiness, corrected clock/generation and seek-aware CastTimingPublisher/Receiver. ReplayCoreCacheTest, ReplayStartupPublicationTest, TimingPreviewEpochsTest, DriverTimingTest, CastTimingSeekGenerationTest. | S |
| G-TV4 | PitwallPairingCode, PitwallTls, PitwallRememberedPeers, PitwallCastSheet, PitwallReceiverActivity, FcastConnection: certificate-pair trust, explicit TV confirmation, legacy comparison and revocation/reconnect. Existing TLS/pairing regression and Android TLS instrumentation. | S |
| G-TV5 | AppState, SceneCastRelay, RelayResourceStore, NativeComposedCastSession, BakedCastLiveSession and FcastConnection: per-load ownership, timeout, lease retention, supersession and local restoration; Cast recovery, native composed and baked relay tests. | S |
| G-TV6 | Rust ts_mux, BakedCastTransportStream, BakedRustTransportParityTest, independent tools/validate_baked_rust_ts.py reconstructs TS/PES, PAT/PMT, PTS and audio/video bytes and uses FFmpeg/ffprobe. Rust wrap/backward PTS and continuity checks. | S |
| G-TV7 | AppState and CastRecoveryPolicy: URL+load ID ownership, CAF ACK and status independent ordering, newest pending controls per load, handback and lost-ownership grace. Custom receiver frame callback and cached geometry; standard/baked CAF state remains a proxy, not physical first-frame proof. CastRecoveryPolicyTest and source-wiring contract. | S |
| G-TV8 | SingleDecoderSceneView, ProtectedDecoderSceneView, ProducerRetirement, SceneEglDisplays, SceneGraphicsOwners, BakedCastGpuCapture, ReplayClipEncoder; exact producer/reader release, idempotent teardown and stale callback fences. ProducerRetirementTest, DisplayLeaseRegistryTest, SceneGraphicsOwnersTest, SceneReleaseStepsTest, SceneSurfaceLifetimeTest, SceneOutputReadinessTest, CaptureRequestLifetimeTest and Surface/Texture/Baked instrumentation. | S |
| G-C1 | engine.rs/atlas_job.rs/JNI and ProcessMemoryDiagnostic: owned workspace capacity and unknown handling; snapshot and budget boundary regressions. Resident memory still requires measurement. | S |
| G-C2 | NativeFragmentLease and fragment_storage.rs: bounded reuse, pressure trimming, native vs heap ownership and actual miss counters; Rust storage/retirement and diagnostic regressions. | S |
| G-C3 | CmafHttpReader, F1ArchiveFeedCapture, F1HlsFeedCapture, HevcArchiveVodSession, ArchiveInitializationBacking, HeapFragmentLease, NativeFragmentLease, ProtectedHevcLiveComposition and NativeComposedCastSession: immutable source/output ownership, exact media, cleanup and direct/rolling/remote integration. Initialization/lease/worker/relay regressions; no unsupported copy-byte estimate. | S |
| G-C4 | Media3MemoryOwners, FeedPlayer, PitwallReceiverActivity: active/parked allocation and bounded load ownership; Media3MemoryOwnersTest and Media3LoadLedgerLifecycleTest. Private vendor/sample-queue pools are not observable as exact owned bytes. | S |
| G-C5 | ReplayCoreCache, LiveTiming, ReplayStartupPublication, DriverTiming and independent overlay owners; shared compact data, bounded progressive publication and clock invalidation. Cache, timing, overlay and seek tests. | S |
| G-C6 | Graphics/codec/Surface/EGL/capture lifetime owners as G-TV8; exact release and late callback guards; unit and renderer instrumentation. | S |
| G-C7 | Reversible clear SurfaceView experiment, existing TextureView default, graphics/scene cadence diagnostics and renderer regression. Physical same-feature comparison required before promotion. | S for instrumentation/experiment; E for promotion |
| G-C8 | Keep existing allocator. Replacement/reclaim experiment only after attributable reproducible live-versus-resident gap. | E |

| Inherited requirement | Source and regression mapping | Status |
|---|---|---|
| G.1 | Baseline scripts and ProcessMemoryDiagnostic; measured matched baseline still required. | S |
| G.2 | Native engine arena, budget and workspace; Rust boundary/admission tests. | S |
| G.3 | Rust clear_atlas and HEVC processing; differential native/Android fixtures. | S |
| G.4 | Rust clear_cmaf, BMFF and initialization; differential and decoded output checks. | S |
| G.5 | NativeProtectedPlan, protected header and ProtectedHevcCmafFragmentWriter; existing secure CBCS and authorization checks retained. | S |
| G.6 | NativeFragmentLease, HeapFragmentLease and ArchiveInitializationBacking; final-reader and range tests. | S |
| G.7 | ArchiveSegmentDelivery, ArchiveBufferPolicy, bounded native work; deadline, cancellation and pressure regressions. | S |
| G.8 | JNI owned/direct buffers and NativeStitchingEngine; exact fixture read/release and packaging gates. | S |
| G.9 | CmafHttpReader and exact authorized HLS/DASH capture; bounded network/input ownership and source contracts. | S |
| G.10 | HevcArchiveVodSession, FeedPlayer and Media3MemoryOwners; data source, ownership and delivery regressions. | S |
| G.11 | Additional SIMD only if attributable CPU bottleneck justifies it; no speculative architecture change. | E |
| G.12 | Rust atlas_job workspace and fragment_storage reuse; capacity/trim regressions. | S |
| G.13 | Playback/load/clock generations, native worker cleanup and archive cancellation; stale callback and seek regression. | S |
| G.14 | Per-feed supported quality, archive prefetch/deadline and compatible switching; quality and startup regressions. | S |
| G.15 | Existing safety, stress, fuzz/sanitizer workflow and bounded-input regressions retained. | S |
| G.16 | Rust/reference parity, actual Android JNI media fixture and independent decoder gates. | S |
| G.17 | Exact normal-RAM TV foreground admission vectors and conservative low-RAM/OS/JVM reserve policy. | S |
| G.18 | Native/managed/graphics/Media3 attribution and cadence instrumentation; no claimed measured reduction. | S |
| G.19 | Local direct, clear/protected composition and authorized native Cast composition plus baked encoding/receiver rendering; route ownership and relay regressions. No artificial stitching in direct routes. | S |
| G.20 | Existing four-ABI packaging, upgrade, signed APK and CI gates. | S |
| G.21 | CmafCastAudio, BakedCastArchiveAudio and native audio metadata/timeline/TS; transport and commentary regressions; platform decode retained. | S |
| G.22 | TrackMap, OverlayPositionSampler, NativeHotPath, Rust overlay_geometry and CastTimingLayer; independent map publication and sampler tests. | S |
| G.23 | OverlayTowerSampler, TvTowerRow, native batched timing histories and CastTimingPublisher/Receiver; timing/tower/seek tests. | S |
| G.24 | Shared local/baked overlay layers, graphics retirement, native numerical sampling and receiver rendering; cadence and graphics regressions. | S |
| R1 / R2 / R3 | Fused/borrowed transfer, bounded deadline scheduling and adaptive arena; initialization, cancellation and Rust capacity tests. | S |
| R4 | Existing bounded concurrency retained; additional tuning requires matched measured evidence. | S / E |
| R5 / R6 / R7 / R8 | Batched JNI and reusable histories/workspace, layout-aware exact quality choices and ownership attribution; native timing, storage, quality and diagnostics regressions. | S |
| M1 / M2 / M3 / M4 | Attribution, budgets, immutable-copy reduction, lazy ownership via G-C1–4; diagnostic/admission/lease regressions. | S |
| M5 / M6 / M7 / M8 | Pressure trim, immutable leases, workspace reuse and bounded queues; Rust and archive lifecycle tests. | S |
| M9 / M10 / M11 | Media3 accounting, bounded timing/media caches, regression suites and CI contracts. | S |
| M12 | Allocator replacement evidence-dependent, as G-C8. | E |
| M13 | Graphics ownership and reversible renderer experiment, as G-C6/7. | S / E |
| P0.1 / P0.2 | Segment producer/deadline and HEVC composition/ownership; G.3–10/17 tests. | S |
| P0.3 / P0.4 | Graphics/rendering, independent map/tower and timing; G-TV3/8, G.22–24. | S |
| P1.1 / P1.2 / P1.3 | Atlas correctness, refresh/cadence, quality and thermal policies; preserve baseline tests plus new ownership/admission tests. | S |
| P2.1 / P2.2 | Cast sender/receiver and playback/timing coordination; G-TV2/3/4/5/6/7/8. | S |
| P2.3 / P2.4 / P2.5 | Existing release/update gates, secure pairing/authorization and Android TV policy; signed upgrade/TLS/TV admission tests. | S |
| P2.6 / P2.7 | Retained automated suites and historical core acceptance; current branch CI and physical regression still required. | S |
| P2.8 / P2.9 | Retained UI/accessibility and caption tracks/appearance, alternate programme sidecars and Cast; startup/quality/CC source contracts. | S |
| A / B / C / D | Overlay stability, cache, graphics and timing retention: G-TV3/8 and G-C4/5/6. | S |
| E / F / G / H | Startup, redraw, cadence and route audit: G-TV1/2/5/7/8 and G.19/24. | S |
| P0-A / P0-B | Manual advertised quality and driver status: inherited source fixes and timing/quality regression contracts retained. | S |
| P0-C / P0-D | Graphics and memory: G-TV8, G-C1–7, release and admission tests. | S |
| P1-E / P1-F | Overlay churn and actual driver labels: shared timing, geometry and display-label regression paths retained. | S |

### Final corrections and preflight evidence

- cfaaa6d / 7240e26: invalid initial Cast timing packet must not commit clock epoch, with regression.
- 249b86c and preceding commits: independent retirement steps, preserve primary failure, newest CAF controls and correlated load ownership, local-load watchdog, exact TS and miss-accounting regressions.
- a405a5f: four historical TV vectors exercised by Android JNI fixture regression, with low-RAM and low-headroom negative cases.
- fa3c447: original cancellation remains primary through startup and capture retirement; seek publication epoch advances only after all chunks encode, with failure/retry regression. Isolated exact-blob JVM helper harness passed 1,000 cycles; this is not full Android/JUnit or native execution.
- 71f3823: audited 288 simple literal checks over changed source, corrected eight stale expectations to the actual stronger ownership/fencing contracts; checked 31 new/replacement source assertions directly. Runner footer counts match nine instrumentation classes and 20 test annotations. Final vector equality contract cross-checks all five host/Android fields and both Rust packet dimensions; direct extraction comparison passed for all four.
- No further specific unconditional source omission identified within this finite reviewed scope. The normal consolidated signed CI candidate can now run; any actual compile/test failure reopens the affected item and must be fixed without weakening the gate. Full Python/JVM/Android/Rust tests and actual ABI/upgrade/signing remain CI obligations, not local passes.

### Decisions and acceptance boundaries

Retain the conservative normal-RAM recovery ceiling (quarter of measured available surplus), low-RAM denial and exact native capacity limits. Pairing uses physical 96-bit code entry and one TV approval for new trust; not unauthenticated zero-input discovery. Local Cast watchdog is 60 seconds; Google lost ownership grace is two seconds. Optional sidecar validation uses bounded lanes and cannot redefine mandatory playback readiness. Retain TextureView default, reversible clear SurfaceView experiment and existing allocator. No new dependencies or backend promotion. Maintain authorized F1 TV login, entitlement, DRM, manual quality, decoder rules and all supported features; existing safe fallback is not acceptance of an eligible native path.

After consolidated CI, require the documented S22 Ultra/TCL 55C655 real-device matrix (including ARMv7/authorized Widevine, actual requested rendition, single/multiple decoder, map/tower/CC, both Cast families, startup/seek/pacing, reconnect and handback). Compare matched Kotlin/Rust memory and CPU; verify at least 20% lower total PSS, 30% lower stitching CPU and 90% less avoidable overlay allocation churn against the existing targets without reducing media quality or functionality. Exact vendor allocations, protected stream behavior, actual first frame and physical Cast playback cannot be established by synthetic tests alone. M12/G-C8 allocator and M13/G-C7 renderer promotion remain conditional. No physical sign-off or green-build claim before evidence. No further speculative source optimization in this batch.


## Build 897 CI correction — final generic retirement after pressure

Build 897 (f42c296e86ba79fedc06d8baa3dc69105c9fa21c, run 45875659456) failed in Architecture G.0 host kernel and Android ABI feasibility: 89/90 Rust tests passed. The unchanged failing assertion in `explicit_pressure_trim_retires_existing_backing_only_after_last_lease` expected zero charged bytes after the final stale sentinel retired, but 512 bytes of newer generic idle backing remained. The stale-release fast path returned before the normal last-owner generic cache cleanup. This is a real Rust ownership bug, not an emulator or test-count failure.

Correct the stale-release branch to drain generic idle backing when the final live lease ends, retaining any independently admitted newer atlas idle permit. Keep the pressure watermark, final-reader immutability, zeroing, original byte/slot limits and existing failing regression unchanged. Add `pressure_retired_last_lease_preserves_new_atlas_idle_permit` to prove that a newly admitted atlas backing remains reusable while the pre-pressure reader retires, with exact capacity, zeroed output and pointer reuse. No change to video quality, decoder, DRM, startup, transport or renderer defaults.

Reopens G-C2/G-C3/M5/M6/M10 only for this CI failure. The correction is source-level; this environment has no Cargo/rustc, so the exact full Rust test suite, Clippy, Android compilation and signed instrumentation/publish gates remain mandatory in the replacement consolidated run. Last verified green remains Build 896. No physical sign-off or performance claim.

## Build 898 CI recovery — shared adoption rejection diagnostics (27 November 2026)

Build 898 (a2f05af53f672d6bf2ac6f4acd5c26cf26d88415; run 45876533634, job 140104907625) verified **91/91 Rust host tests**, including both final-reader pressure retirement regressions. The coordinated change contract passed. Strict `cargo clippy --locked --all-targets -- -D warnings` then failed on the host-only unread `Arena::adopt_rejected` field; later ABI, JVM, Android and publication gates did not run. Build 896 remains the last verified signed green baseline.

Move rejection counting into the common Rust `Arena::adopt` wrapper around the unchanged checked transfer logic; the Android entry point delegates without duplicate counting. Keep the 21-slot JNI snapshot and existing admission, immutable ownership, cache and pressure behavior unchanged. The new host regression `adoption_rejections_count_once_and_preserve_live_ownership` checks budget and overflowing-range rejection before destructive transfer, stale-slot rejection, exact once-only increments, unchanged live-reader bytes and accounting, successful pointer-preserving adoption, final retirement and saturating counter behavior. Existing tests are retained. No lint suppression, gate relaxation, platform workaround, new dependency or change to decoder, DRM, quality, renderer, overlays or Cast.

Only G-C2/G-C3 diagnostic coverage is corrected; prior Build 897 ownership fixes and all final source-scope ledger entries remain intact. Source review only in this connector session; Rust/Android toolchains unavailable locally. The replacement normal consolidated workflow must execute all original gates, with an expected 92 host tests, and all five jobs must pass before reporting a signed candidate. Physical and measurement acceptance remain open; conditional work remains conditional.

## Build 899 CI recovery — align real Cast fixtures with strict publication (27 November 2026)

Build 899 (743dca197bde94bdca5e4c4bade544f922f159ab, run 45877430864, job 140107948897) passed 92/92 Rust host tests, strict Clippy, the 92-test sanitizer run, Android ABI feasibility and the native baked TS independent fixture gate. Build 897/898 ownership/accounting regressions are therefore host-verified. The JVM gate reached 997 tests, with two failures: `BakedCastHlsSessionTest.ffmpegDecodesBothTracksFromTheSameTransportStream` rejected a whole FFmpeg ADTS file as one sample, and `F1HlsFeedCaptureTest.castRelayServesOnlyCapabilityScopedVideoResources` requested video before mandatory validated relay publication (404 rather than 200).

Correct both regression fixtures in one normal replacement commit. Split the actual generated AAC file by each declared ADTS frame length, check sync/frequency/raw block count and bounds, preserve every emitted frame and use 48 kHz/1024-sample timestamps. Keep the independent FFmpeg decode of both tracks, existing malformed-frame rejection and real Rust/reference parity; do not permit multi-frame samples in production. Provide a finite clear selected audio rendition in the relay fixture, invoke the production programme readiness and audio validation sequence, retain all original successful/unauthorized resource assertions and explicitly check prepublication 404s, postpublication master/audio and scoped rewritten audio payload bytes. Do not expose video before validated publication or relax capability isolation. No production source, JNI, ABI, DRM, quality, memory or workflow changes. This is regression-fixture correction, not evidence of a physical Cast fix.

Both master ledgers remain authoritative. Full JVM and downstream signed/instrumentation gates must rerun on the exact replacement SHA. Last verified signed green remains Build 896; physical acceptance and conditional allocator/renderer decisions remain pending.

## Build 900 CI recovery — complete ADTS Android instrumentation fixture (27 November 2026)

Build 900 (dfd53f71cc06b1974d261a578b5dbad1098c22da; run 45878989626) passed the build job 140113205455, 92/92 Rust host and sanitizer tests, strict Clippy, four-ABI native build, independent transport/HEVC checks, JVM tests and signed APK assembly/internal staging. The two Build 899 JVM fixture failures are resolved. All three emulator jobs (API29 140118229008, API31 140118229001, API36 140118229039) passed G.0 4/4 and G-A 2/2, then failed the same G-B test: `nativeFlagsAudioAdmissionAndMetadataMatchReference`, ArchitectureGBAndroidTest.kt:238, IllegalArgumentException requiring complete AAC ADTS frames. G-B 4/5; subsequent mandatory instrumentation and final publication did not pass. Last distributable green remains Build 896.

The fixture previously filled the audio sample with arbitrary bytes and overwrote only the two sync bytes. Correct it using a nonempty deterministic raw AAC transport payload and the existing reference ADTS wrapper. Independently frame the same raw bytes through the actual Rust JNI ADTS path and assert byte equality. Across the original 20 mixed native/reference segment iterations, preserve all TS parity assertions and continuity state; add truncated-unit rejection on both requested modes before each successful segment. The synthetic raw bytes exercise transport framing, not AAC decoding; independent real encoded AVC/AAC decode and native/reference parity gates remain unchanged. No production validation weakening. Reviewed all eleven Android instrumentation Kotlin files for ADTS/TS/relay fixture call sites; only this class directly constructs these mux samples. All nine mandatory runner footer expectations (20 tests total) remain unchanged. No changes to production source, memory, DRM, video quality, renderer, workflow or receiver deployment.

Exact replacement Android instrumentation and all five CI jobs remain required before reporting green. Source review and fixture arithmetic only in this connector turn; no local emulator execution. Preserve all previously reconciled source workstreams and physical acceptance boundaries.

## Build 901 physical follow-up checkpoint (27 November 2026)
Green source 06d874d86e1da2560af61dcb404e21c8071714cc, Actions 45881176799; all five jobs succeeded. User confirms map/tower loaded without blinking in the tested phone clear four-feed SurfaceView configuration; preserve that fix. Not cold-cache, protected, TV or Cast acceptance.
- M1/M13/G-C4: 667.8 MiB dumpsys PSS, graphics 256.5 MiB, one scene owner during playback; vendor bytes not attributed. Add process-lifetime peak owner/context/window/texture counts to reveal short overlap missed by periodic snapshots, without labeling them as GPU bytes. Actual graphics attribution remains open.
- M2/M3/G-C5: Dalvik LOS 128.4 MiB, three parked allocator owners zero. CmafHttpReader uses exact declared bounded range even when open reports unknown length, preserving EOF proof and eliminating avoidable full coalescing on that route. This does not establish reduction of the measured LOS (observed coalesced copies zero). Object-class/retainer evidence remains needed.
- G.24/P0.3: 60Hz and 120Hz UI windows differ, with renderer and refresh confounds. Candidate avoids immediately discarded Compose overlay motion/flag callbacks using short elapsed-work-adjusted rearm; preserve 15ms guard, video clock and mode selection. Physical smoothness and presentation remain open.
- G-C2/M4–M8: 24 allocations, 34 reuses, 58 adopted outputs reported. Added 21 observed output sizes to Rust regression with two pinned readers and existing bounds. No idle permit, capacity, pinned ownership or allocator change without evidence. Further optimization remains open.
- G.18/G.19: 21 native segments, median composition 38ms and preparation 2660ms. Add per-source bounded queue/read/byte scalar diagnostics after successful group, without URLs or headers. Preserve deadlines, buffer targets, concurrency and exact quality; network attribution remains open.
- G.22/G.23: retain verified warm startup; cold miss, interrupted rebuild, seeks, TV/Cast physical coverage pending.
- M1/G-C6: 901 heap trace disconnected with buffer overrun/client timeout and only one snapshot. Optional strict heap quality gate requires PID and distinct snapshot count, rejects interruption/overrun/compressed evidence; existing packet-presence mode unchanged. Exact ELF/stack evidence still separately required.
This is a staged engineering checkpoint, not full completion of the seven requested workstreams. No new signed build until the batch is reviewed. Physical results and numerical targets are not implied by source changes.

### Build 901 follow-up: last-reader retirement and heap capture reliability
Source inspection identified a concrete local archive ownership gap: older delivered entries skipped by the trim loop while readers remain pinned are not retired on final close unless another delivery later triggers trimming. The fix retains the newest fully delivered segment for retry, including out-of-order completion; marks only older same-epoch delivered entries for retirement on final reader close. Seek and shared Cast/relay acquisition invalidate the deferred decisions. Unread warm-ahead, active readers, replacement identity, exact quality and existing cache limits remain intact. This is at the Android DataSource lease boundary; native backing returns to the existing Rust arena without increasing its retained capacity or modifying JNI slots. Added late-reader, out-of-order, epoch invalidation and replacement regressions. Native reuse improvement must still be measured, not inferred from source.
Added versioned v5 heap-only config (90s, 128KiB, 32MiB shared producer buffer, nonblocking client) and documented strict capture preflight. Schema fields verified against Perfetto heapprofd_config.proto. This config intentionally trades sampling resolution for reliability; no direct comparison with v4 allocation totals and no profiler overhead in formal PSS acceptance. 
Candidate scope: exact-range read optimization, bounded overlay callback rearm, peak graphics ownership, per-feed network observations, earlier local archive lease release and profiling checks. Unresolved attribution and performance targets are evidence gates; warm startup user-confirmed, cold startup/TV/Cast still unverified. No renderer/default mode, allocator, bitrate, buffer target, DRM or decoder policy changes.


## Build 901 follow-up: root-cause evidence and Cast controller

User confirmed Build 901 warm tower/map startup without blinking. Build 901 observed PSS 667.8 MiB, graphics 256.5 MiB, Dalvik LOS 128.4 MiB; native output 24 allocations (16 size-fit, six pinned, two disabled), 34 exact reuses. Native trace incomplete (buffer overrun/client timeout), so no allocation-site or vendor-buffer attribution is claimed.

- M1/M13/G-C4: stable owner IDs and CREATE/CHANGE/RELEASE graphics events, per-kind concurrent heap peaks, actual fetched-array lifetimes, scoped meminfo and mappings, graphics/FrameTimeline trace. GPU/codec vendor bytes remain explicitly unmeasured when unavailable.
- M2/M3/G-C5: exact bounded range reads, previous delivered-fragment last-reader retirement, ART Java heap reference graph (no raw field contents). Retaining classes and reference paths, not PSS subtraction, are the evidence for LOS. Java dump phase is excluded from pacing measurements.
- G.24/P0.3: high-refresh overlay callback rearm; graphics trace separates scheduling, GL submission and physical FrameTimeline where supported. No refresh rate or quality downgrade.
- G-C2/M4–M8: bounded wiped idle atlas growth only for near undersize, existing worker ceiling and arena cap unchanged. Same-address/moved/failed realloc separately measured; a realloc is not counted as exact reuse or proof of lower PSS.
- G.18/G.19: per-feed queue/read/byte/timing attribution; existing bounded delivery and buffer targets unchanged pending measured bottleneck.
- G.22/G.23: cold partial topic publication retains last time-covered map/tower independently, authoritative empty/changed session wins. Tests plus physical cold start outstanding.
- M1/G-C6: separate native/graphics/Java capture phases, one run/PID/build manifest, strict native coverage validation, fresh diagnostic log on each enable, all rotated logs collected. See docs/perfetto/capture-root-cause.sh. No claim that every device exports private vendor allocation ownership.
- Cast: native/relay receiver ownership suspends all local selected tracks; baked independent source decoder/encoder remains because it produces TV video, but local preview is blanked and audio muted, controller is outside encoded overlay, PiP/radio disabled during controller mode. No window PixelCopy fallback in baked Cast because it would encode the controller; independent capture failure is explicit. Original replay clip separate. Source geometry editor uses static placeholders. Physical both Cast protocols, clear/protected and local restoration still required.

One consolidated candidate; unchanged signed CI gates. No declaration of device acceptance before matching physical evidence.

### Build 902 CI recovery and evidence integrity
Build 902 a73a7dfee82e60620707737d8ea824110e19281c, Actions 45929762244, native tests and sanitizer 94/94 each; JVM 1005/1006. Failure ArchiveBufferPolicyTest.localDeliveryRetiresOnlyOlderDeliveredUnpinnedBackingInSameEpoch: stale expectation of 2 bytes released in a later completion after the prior last-reader closure already released them. Correct the existing regression to assert old bytes remain intact until close, exact retirement at last-reader close, zero double retirement at later callbacks, same-epoch pinned protection, unread/newest/future preservation. Production ownership rule unchanged. Add session-cumulative deferred retirement bytes and entry counts to segment diagnostics, distinct from instantaneous cache capacity/PSS, so earlier final-reader release is observable rather than missing from immediate trim logs.
Strict heapprofd validation now checks per-trusted-sequence ProfilePacket indexes and final continuation flag (Perfetto profile_packet.proto and trace_packet.proto); reject gaps, missing sequence identity or unfinished continuation, in addition to prior PID/overrun/error/coverage checks. This is packet integrity, not proof of usable symbols, object attribution or vendor GPU byte ownership. The Build 902 controller-only Cast and root-cause bundle remain intact. All signed APK and instrumentation gates remain mandatory. Physical acceptance remains open.


## 27 November 2026 — Build 903 recovered heap evidence and first source correction (build gate CLOSED)

Exact signed baseline d920f3ce9606fc115ef4fa2f281fc71578e6b82e, Actions 45931466349 all five jobs success, signed artifact 15169461833. Documentation checkpoint a322766a763e1c054cbde20c2926c1febe326277. Recovered the user's retry native, Java, graphics, system and CPU stack trace files; no raw evidence committed.

- M1/G-C6: direct protobuf inspection of the native capture confirms PID 22413, nine nonempty snapshots at approximately 10-second spacing, 18 consecutive ProfilePacket indexes (0–17), complete final continuation, zero reported unwind errors, disconnect, overrun, corruption or client error. Final sampled live allocation 27.96 MiB, with 26.09 MiB at a single unsymbolized base.apk native realloc stack (42.84 MiB cumulative allocated there). This is a sampling estimate for allocations observed after profiling starts, not total native PSS, exact source line or a leak. Exact-build ELF symbol resolution remains open.
- M2/M3/G-C5: first complete ART graph (PID 22413, indexes 0–94, 1,270,126 objects, 64,414 root IDs, zero unresolved object edges in the custom parser) gives conservative strong-root traversal of 154.86 MiB. Approximately 79 MiB of byte arrays via Media3 Allocation/filtering allocator and 4.56 MiB via DefaultAllocator available allocations; 5.95 MiB long[] + 5.58 MiB float[] through NativePositionBatch/OverlayPositionSampler; 11.0 MiB long[] through SpeedLog/TimingWorkCoordinator. These are grouped shallow array bytes on shortest observed paths, not exclusive dominators. Later graphs still need full traversal. Do not double-count PSS or assume all map history or buffered media is waste.
- Confirmed avoidable ownership: SpeedBuilder retained power-of-two timestamp/value backing arrays and always separate speed/gear time arrays, although CarData entries often use the same timestamps. Candidate freezes only valid prefixes and uses a publication-scoped, exact-match timestamp interner across drivers/channels. Hash collisions checked byte-for-byte logically; no global cache, no dropped samples, no changed staleness, no change to Rust JNI lookup arrays or numeric processing. New JVM regressions for aligned/differing/missing series, independent values, growth after publication and collisions. This is Android-side ownership of the existing primitive JNI boundary, not a replacement of Rust processing. No measured savings or full compilation claimed.
- Graphics/P0.3: active meminfo 609212 KiB PSS, graphics 261188, Java 152640, native 78760; graphics drop from 261464 to 67592 KiB after owner 2 release, Media3 and native fragments zero at that sample. Scene owners cycle 1,2,3 without evidence of simultaneous redundant EGL contexts; codec input bufferpool observed 39178240 bytes (overlapping accounting). Residual Java requires graph/GC lifecycle analysis. No vendor allocation attribution or renderer promotion claimed.
- G.22/G.23: six warm replay map/tower readiness checkpoints 845–1344 ms, 22 rows/cars, not cold or Cast acceptance. Four requested renditions 1080+3x540 and one decoder with 1920x2176 50fps coded format observed. 27403 rendered / 2 dropped in one 548-second window, not physical presentation proof.
- Remaining unconditional investigation: complete subsequent ART graph traversal and dominators; resolve native ELF build ID/addresses; actual FrameTimeline, CPU scheduling, GPU/codec owner attribution and 60/120 Hz phase matching; root-cause-supported remaining source fixes and tests; reconcile finite ledger; no consolidated build until done. Existing phone/TV, protected/live, multiple decoder, Cast and endurance gates unchanged; no arbitrary buffer, quality, allocator or renderer policy changes. Source candidate not yet compiled; no physical acceptance. Prior master ledger remains authoritative.


### Build 903 evidence follow-up: graphics FrameTimeline and focused regression check

- Direct protobuf inspection of the 60-second graphics trace: PID 22413 activity-window layer, 3334 actual surface events, 2811 late/janky (84.31%), 2163 buffer-stuffing flags (64.88%), 819 application-deadline flags (24.57%), 72 SurfaceFlinger CPU deadline flags and 65 GPU deadline flags. Flags overlap; this is not decoder output or physical scanout. No PRESENT_DROPPED events on this layer. Refresh changed within the recording; no controlled 60/120 Hz comparison or specific scheduling call-site attribution yet.
- GPU kernel gpu_mem_total, PID 22413: 20 counter updates, 254.29–254.86 MiB during the active window. Aggregate only, not per-resource allocation or proof of leakage. Native trace final sampled live 27.96 MiB, dominant unsymbolized realloc stack 26.09 MiB; exact ELF symbol resolution remains open.
- Isolated local Kotlin harness reproducing the new speed timestamp storage logic passed 4101 samples per driver, exact sharing, distinct/empty/stale series, capacity boundaries, hash collision and publication lifetime. This is not compilation of the exact application Git blob or execution of the three checked-in JUnit tests. No Android or Rust toolchain or official Perfetto trace processor available in this analysis environment. System and CPU traces opened but full scheduler attribution and subsequent Java graph dominator analysis remain outstanding.
- First source correction is commit 00b210aa5484f68e40d949b272cdae98d79b1ef0. No measured post-change savings, no claim of 100% unconditional coding completion, no physical acceptance, and no build triggered. Keep consolidated build gate closed. Continue existing scope and resolve remaining attribution with recovered evidence rather than requesting the same captures again.


### Build 903 continuation: symbol-resolved native allocation and bounded Rust correction (build gate CLOSED)

- Downloaded exact Actions native symbol artifact 15170015160, arm64-v8a ELF Build ID 11723731f26e887b0adcbef6eab28601e0b4bcb8 matches native trace mapping 57. Dominant 26.09 MiB sampled live allocation at final snapshot (of 27.96 MiB) resolves via addr2line to engine.rs:540, atlas_job.rs:93/221, clear_atlas.rs:442/91, fragment_storage.rs:166/364, RawVec try_reserve_exact/realloc. It is composed output backing, not parser scratch, not a proven leak. Nine snapshots, peak sampled live 28.41 MiB. Native monotonic-to-boottime anchor 36764066503890 -> 69369739048350 ns; eight aligned runtime samples between ~69379.8 and 69459.8 s show native fragment capacity 26233005–27353109 bytes, 2–3 live backings, occasional one idle buffer, engine payload 8249361 bytes, Media3 allocated 78053376–85393408 bytes. Do not add sampled allocations to PSS.
- All 41 observed near-size realloc attempts by the final app log sample moved, zero same-address and zero failures. The old wiped Vec content is not needed by the overwrite-only writer. Evidence-supported Rust correction: after four consecutive unsuccessful in-place growth attempts (moved or failed), bypass near-size growth in that process-local Arena, drop the unsuitable wiped backing before exact fresh reservation. Successful in-place growth resets the streak before the bypass threshold; compatible exact-fit reuse remains first. No increased capacity, no allocator replacement, no changes to max readers, pressure retirement, wiped contents, JNI media payload or output quality. A threshold of four is a conservative engineering choice, not a measured optimum; policy learns locally and is not persisted across process restart. Existing counters distinguish moved/failed/reused; append optional bypass count to the native memory snapshot (28 values, JVM supports old 5/9/15/21/27). New deterministic Rust regression verifies pinned immutability, zeroed fresh data, unchanged charge, exact fit still reused, retirement, and streak reset; new Kotlin regression verifies 28/27 compatibility. Tests added, not full-compiled yet. No promised PSS saving: target is avoidable copy/transient allocation overlap.
- Parsed all three ART graphs, indexes 0–94, 0–99, 0–104, same PID, zero missing edges. Conservative root traversal excluding outgoing soft/weak/finalizer/phantom references yields 154.86, 161.10, 154.85 MiB; object totals 1270126, 1388586, 1543892. Reachable array size variations track buffering; not evidence of a growing live heap in this ~20s interval. Strong-path attribution not exact dominators. Media3 active and free pools, 26k HLS Segment objects, timing histories still need lifecycle attribution. 201 deduplicated Rust ARCHIVE_VOD_SEGMENT records across mixed phases, aggregate p95 fetch 3199 ms, compose 90 ms, total 3919 ms, 188 trim events, deferred last-reader counters zero. Do not treat aggregate as controlled steady 1.5x acceptance or reduce mandatory buffering.
- CPU sampling trace: sequence-generation-scoped interning, 10648 target-PID stack-bearing samples, main thread 3066; 2766 Compose, 1043 accessibility/semantics, 563 map, 94 tower, 40 Rust frames inclusive (overlap); track draw lambda 482 and NativePositionBatch.sample 88. Worker tid 22625: 1135/1168 in LiveTimingArchive.loadSpeeds, including parsing/inflate; scene GL tid 15201: 449/714 in ProtectedDecoderSceneView.draw, 488 GL/graphics library-inclusive. RenderThread tid 22432: 1415/1419 HWUI, 531 GL/graphics-inclusive. Some unwind errors, profiling overhead, mixed phases; counts not exact CPU durations or proof of root cause for every missed frame. Do not disable accessibility, force 60Hz, or change GPU/DRM policy. Full scheduler correlation and source corrections for other owners remain open.
- Keep all prior plans and acceptance criteria, exact signed 903 source baseline and tests. No APK build until remaining unconditional source and regression scope is closed. No raw logs, traces, APK or symbol binaries committed.


### Build 903 continuation: reduce measured CarData and badge publication churn (build gate CLOSED)

CPU stack samples identified LiveTimingArchive.loadSpeeds on worker 22625 (1135/1168 samples inclusive), and repeated Compose accessibility/semantics on the main thread (1043/3066 inclusive) plus OnboardInfoBadge (108 inclusive). No source-level proof that badges cause every semantics sample. Source inspection finds two concrete avoidable work patterns: the CarData loop instantiates and ends a platform native Inflater, 8192-byte scratch and ByteArrayOutputStream for every compressed JSON record, unlike existing reusable archive position decoder; every visible onboard also schedules its own 10Hz metadata clock. Reuse one existing ArchiveInflater per CarData stream, prefix computed once, valid/malformed and independent channel semantics unchanged; cancel before each record and per driver publication. Existing Android platform native DEFLATE retained; no new Rust parser or library dependency, no shift of existing Rust numeric processing to Kotlin. Regressions cover malformed record followed by valid, BOM, missing/independent speed/gear series, stale values and cancellation. Share a single State<Long> clock for visible onboard badges in ViewerScreen controls layer, read in badge composable only, not the parent. No clock for PiP or no visible drivers; existing standalone fallback and TV/phone cadence, map draw-only clocks and Cast sampling remain unchanged. Preserve all Text/semantic/accessibility nodes and DPAD/touch/visual fields; no accessibility disabling or forced display rate. Added gating and cadence regression; actual Compose scheduling and on-device UI improvement need physical validation. No new source changes to media quality, DRM, decoder/cast policy, no performance claims. Source candidates/tests not compiled, keep consolidated build gate closed pending remaining evidence and implementation reconciliation.


### Build 903 root-cause continuation: dominator and scheduler attribution; immutable history correction

- Calculated immediate dominators and exclusive retained sizes on all three ART graphs (Lengauer–Tarjan over virtual GC root, excluding outgoing soft/weak/finalizer/phantom reference nodes conservatively; class/object IDs and all decoded edges resolved). Reachable totals 162387225, 168926431, 162372660 bytes. DefaultAllocator exclusive retained subtrees 4.5706, 2.63227, 8.38472 MiB; first graph 73 unused 64 KiB backings (4.56334 MiB shallow including headers), plus 1277 active Allocation byte arrays 79.8271 MiB with multiple GC-root paths. Root-labelled allocations cannot be assigned exclusively to one player via dominator tree. No growing reachable heap; no evidence to lower target buffer durations or replace Media3 allocator. Existing trim/park/release and public API boundaries retained. Exact per-track queue/vendored GPU allocations are not exposed.
- Source map history audit: NativePositionBatch references DriverTrack arrays (no full JNI history copy; bounded adjacent reads). Current hot source arrays attributed to map history, not duplicated native cache. Exact equal timestamps across drivers can share backing without changing positions, seek/interpolation, state or coordinates. Reuse existing per-publication exact timestamp pool for completed cold map archive and full/preview cache reads. Preserve old append-only map previews and live path, existing coordinate compaction threshold and disk schema. No persistent pool, no native sampling changes, no assumed equality or claimed savings from trace (values not exported). Tests cover exact equality, mismatched final sample, independent positions, prior snapshot and later append, per-read lifetime and cache roundtrip.
- Important cross-route source regression caught: SpeedBuilder.build() is called from LiveFeed every 2 seconds. Prior archive compaction accidentally made no-argument live build hash/copy all history each tick. Restore baseline zero-copy counted-prefix build for live only, keep archive build(pool) exact. Regression verifies same live backing across refresh, fixed old counts, append growth and archive compaction.
- Graphics trace scheduler: compact sched_switch decoded per CPU, 949876 switches, 569270 wakes, no lost-event bundle flags, common all-CPU span 59.699 s. Matched intervals conservatively exclude cross-bundle first-switch ambiguity (main 271, RT 275, GL 51 unmatched boundary observations). Main tid22413 at least 31.278 CPU s, runnable-to-run p50/p95/p99 0.024/0.935/2.469 ms (16393 matched, 4 >8.333ms); RenderThread22432 19.479 CPU s, 0.038/1.149/2.723 ms (12119, 3 >8.333ms); actual clear GL tid8779 9.138 CPU s, 0.064/1.388/3.512 ms (10964, 9 >8.333ms); native worker8681 0.111 CPU s, p95 0.632 ms, 30 matched; warm worker8917 0.319 CPU s, p95 2.106 ms, 194 matched. No matched >16.667ms delays in these owners. This points to app/HWUI work plus queue pacing rather than chronic CPU runnable starvation in this capture; do not increase thread priority or concurrency indiscriminately. FrameTimeline app layer only; vendor graphics bytes unavailable, measured physical 50/75fps not established. Keep conditional allocator/renderer/SIMD/concurrency experiments gated.
- Changes and regressions remain uncompiled; no APK triggered in this intermediate commit. Preserve finite ledger and existing signed 903 baseline.


### Build 903 captured frame-stage, network, recovery and thermal reconciliation

- Matched nested Android atrace B/E slices for target PID22413 on the 60s graphics trace. Main tid22413 (six boundary E unmatched, no open tail): 1430 Compose:semantics:checkForSemanticsChanges = 10725.6ms inclusive, p95 12.189ms; 1430 sendAccessibilitySemanticsStructureChangeEvents = 9579.8ms inclusive p95 10.959ms; 1408 getAllUncoveredSemanticsNodesToIntObjectMap = 3590.3ms p95 4.385ms; 3331 traversals = 14863.3ms p95 9.939ms; 3330 AndroidOwner:draw = 7700.3ms p95 4.772ms; 3386 recompositions = 5123.5ms p95 4.672ms. These wall-time slices nest/overlap; do not sum. RT22432 3331 Drawing slices = 20097.7ms p95 11.657ms, flush commands 7685.2ms p95 3.97ms, eglSwapBuffersWithDamageKHR p95 2.391ms. GL8779 2924 PitwallSurfaceSceneSwap p95 3.426ms, 2985 PitwallProtectedSceneLatch p95 1.31ms (historical label on clear backend), no open slices. Rust worker8681 10 PitwallNativeAtlas slices total 118.7ms p95 16.338ms; no evidence to prioritize SIMD. These data corroborate UI/semantics/render work as a major contributor to missed app deadlines, not proof of per-frame causal attribution or physical video presentation.
- Exact wholly contained FrameMetrics intervals with no first draw, uniform refresh min/max and thermal2 in same scene: 20 60Hz windows / 2199 frames /185 slow (8.41%); six 120Hz /676 /449 slow (66.42%). 120Hz average command 5.146ms vs 4.746 at 60; draw 3.067 vs 3.311; animation 2.411 vs 2.319; GPU 2.389 vs 2.306. Metric is TOTAL_DURATION > FrameMetrics.DEADLINE (fallback device period), not physical dropped frames. Same trace and scene but different times, not randomized paired A/B; no forced refresh reduction. Atrace and scheduler point to app work and HWUI queue pacing, not prolonged runnable starvation. Implement grouped semantics for noninteractive onboard badge (same existing approach as tower): preserve current number/name/position/lap/pit/gap/speed and real sector pace, one clearAndSetSemantics summary, no hidden telemetry or accessibility disabling. Visual layout and exact rendering unchanged; no focus/click targets removed, Cast overlay uses identical visuals. Extract testable badge content; Robolectric Compose regression verifies one readable summary and updated speed while child text is not separately exposed in merged tree. Physical screen-reader and frame comparison still required.
- Across 203 deduplicated fetch groups max feed queue median/p95/max 2/8/31ms vs max read 964/3172/19743ms; group elapsed median/p95 966/3185ms. Main source 4.06MB median read 807ms; remaining ~1.18MB median reads 587/650/756ms. Slowest source varied (76/20/37/70 groups). Do not increase thread count or reduce quality: network service time dominates steady preparation. 192 speculative RUST records median/p95 fetch 955/1959ms, compose 42/81ms, total 1001.5/1988ms; five exceed 5.76s, six exceed 3.84s. Nine foreground RUST records across startup/seeks: median fetch3199, compose1374, total4348ms, two exceed 5.76s/five exceed3.84s. First fragment of each native generation intentionally performs byte-exact JVM comparison; do not remove proof or claim all steady processing is slow. Aggregate process allocations during foreground include other concurrent threads and are not exclusive stitch allocations. Prior bounded queue, exact read and deadline policies retained.
- All 98 samples with scene owners active: thermal raw1/2/3 counts 28/39/31, effective thermal0 for all (thermal adaptation setting disabled in recorded run). Preserve user setting and do not secretly throttle quality or force60; actual rail/power measurements unavailable, no battery claim. Source owner audit sees no overlapping extra app EGL context/window/texture on three scenes and sequential recreations. Owner1 release: at +6.672s graphics71576 KiB vs259660 pre, Media3/native zero; +16.643s graphics580 KiB/PSS210707, +26.698s PSS203993. Owner2 release +5.310s graphics67592, both Media3/native zero, but subsequent restart contaminates long recovery; owner3 surface recreation retains archive/player while EGL retires, correct distinction from final session stop. GPU vendor per-allocation byte identity inaccessible from captured aggregate counters; no evidence to force driver reclamation or renderer promotion. No controlled 2h endurance/TV/Cast proof.


### Consolidated post-903 source candidate: one signed CI run authorized by source reconciliation

The finite requirement/owner/regression ledger above remains the scope of record. Exact signed 903 source d920f3ce9606fc115ef4fa2f281fc71578e6b82e passed all five Actions jobs (run 45931466349, rechecked). This batch is additive; compared that baseline with current head, reviewed the changed production/test files and relevant original source contracts, current primary 903 handover and both current ledger appendices. The inherited S candidates remain 903-compiled, not newly physically accepted. Do not infer that every historical file was re-read line by line, or that the new source is compiled before Actions.

Current evidence-supported unconditional source scope is reconciled for one build:
- G-C2 / M4–8 / G.12 / R3: symbol-resolved output backing, bounded observed-growth bypass, exact reader immutability/pressure and reuse; source + tests. Final diagnostic ABI validation rejects inconsistent attempt outcomes and bypass counts without overflow; older 27-value and current 28-value vectors supported, unknown remains unknown.
- G-C5 / G-TV3 / G.22–23 / M2–3 / P0.4 / P1-E: archive speed/gear and exact matching map timestamp series compacted within a publication, cache read per-owner, live append-only publication restored, reusable platform native inflater, independent speed/gear, no timeline/sample changes; source + tests.
- G.24 / G-C6 / P0.3 / P1.2: direct frame-stage and scheduler evidence, shared badge metadata clock, grouped accessible onboard values, unchanged map draw clock and UI/DPAD/visual behavior in source; source + tests. Actual performance, accessibility and Cast physical behavior remain open.
- G-C4 / M9: actual active and free Media3 allocation ownership reconciled with three heap graphs; no demonstrated avoidable active buffer or retained parked player; no new buffer/quality cap changes. G-C6 / G-TV8: sequential app graphics owners and final release, exact GPU vendor buffer attribution inaccessible in this capture; no redundant app owner established. Preserve current resource release and experimental renderer controls.
- G.18 / G.19 / R4 / P1.1–3: fetch service time vs queue/compose attribution and thermal setting verified; no scheduler/concurrency escalation or hidden downgrade. Existing byte-exact first-generation reference, DRM, authorized routes, phone/TV, single/multiple, both Cast, captions/alternate audio, layout and quality policies retained; no unproved claim of route acceptance.
- E stays conditional: G-C8/M12 allocator replacement, G-C7/M13 renderer promotion, G.11 extra SIMD, R4 extra concurrency. Current evidence does not justify promotion. Remaining unavailable measurements (actual vendor allocation identity, per-track private sample queue bytes, physical scanout/thermal rail energy, matched old baseline, cold/TV/protected/Cast/endurance) cannot be closed by unconditional source changes. Do not request another repeat of the same profiler captures. Full physical acceptance still needs the existing matrix on exact new signed APK after all five CI jobs pass. No unmeasured savings promised.
- Source checkpoint 9a7cb46ee43f67c41f2ddbdaf7d76fcce8449aec; last source update tightens diagnostic counter invariants and includes regression plus both ledgers. Full Rust/Android toolchains unavailable in analysis environment; new tests not locally executed; final commit intentionally NOT skip-ci, triggers one consolidated existing workflow. No workflow/test/safety gates changed and no intermediate builds. Verify actual resulting head/run/jobs before reporting green or offering APK.


### 27 November 2026 — post-903 physical P0 regression addendum to the current memory workstream

User reports on TCL 55C655 Android TV12: single feed with map and tower works; Google Cast, Pitwall Cast, and local multi-feed single-decoder playback do not. Retired Lindblad remains on map, and phone Back cannot exit app. All three new supplied captures identify signed Build 903 (1.0.1806, Nov27 03:02), not Build 904. Reopen the applicable physical gates and exact defect scope only; retain all previous master requirements and MEM-01–16 proposed follow-up priorities, do not restart Architecture G. Functionality is P0 and must be corrected in the same consolidated next source batch alongside memory improvements. 904 source d48a554bb7c2d4f65651114a99e0eecd961a3bae already submitted to Actions run 45942354697; this addendum is [skip ci] and must not supersede or claim acceptance of 904. No raw logs/credentials/media committed.

| P0 regression / mapping | Measured evidence and next engineering action | Closure requirement |
|---|---|---|
| TV multi-feed single decoder (G-TV1/2/8, G-C1–6, G.7/10/17/19, MEM-08/11/12/15, original P0.1–3) | TV pid11530, 32-bit process, content 1000010442, requested 1080p + 480p + 540p, clear candidate. 10:41:44.497 preflight, 10:41:50.777–.801 playlist boundaries, 10:41:54.492 runtime validation pending, .534 foreground admission wait0, .559 fetch admission wait25ms. 10:42:42.702 actual Rust memory admission true, input 5992599 output 5955630, arena 27473340 workspace 42055388 ceiling 69528728, available 651943936 threshold150994944, JVM headroom297490640, reason BOUNDED_FOREGROUND_RECOVERY. No GB_CLEAR_NATIVE_FRAGMENT or ARCHIVE_VOD_SEGMENT published in this capture; no scene graphics owners in later memory samples. 5235 vendor ROB drop-frame log entries across entire capture (not exact decoder drops). Foreground proof/segment read/validation and cancellation path, clock and fallback AVC surface/decoder ownership need separate stage attribution; do not presume memory denial, eliminate parity, or forcibly admit. Review NativeClearArchiveBridge, NativeClearArchiveJob, HevcArchiveVodSession, F1ArchiveFeedCapture, AppState/FeedPlayer startup and Rust deadline. Optimize redundant proof scans/copies where proven, ensure bounded cancellable startup and one decoder at handoff; preserve quality, safe memory and Software DRM setting. | Exact TV 32-bit actual Rust composition, selected feed quality, first visible video within master warm/cold budgets, no false single-feed success; both overlays, clear and authorized protected, all supported layouts, seek, 1x/1.5x supported, repeat and endurance. Keep working single feed intact. |
| Pitwall Cast (G-TV4–8, G-C3/4/6, G.19/21/24, MEM-16, P2.1/2) | Two correlated sender/TV baked load IDs. Both TLS/control handshake, TV receives PLAY at 10:36:05.249 and 10:36:33.809; phone sends PLAY at 10:36:05.047 and 10:36:33.597. Relay responds playlist/media in both. TV first decoder AVC 1918x894 and AAC initialization observed; no confirmed remote rendered video. Phone LOCAL_CLOSE at 10:36:08.117 and 10:36:36.191; TV PEER_EOF at .315 and .388. Time since sender PLAY 3.070s and 2.594s. Local close may be user action or automatic state transition, not proved timeout. Investigate FcastConnection, AppState localLoadWatchdog/owned status and UI disconnect, PitwallReceiverActivity first-frame/status/ACK. Correlate load ID and actual frame plus audio readiness, distinguish buffering vs failed vs user close, retain session/relay until appropriate retirement. Do not weaken TLS/pairing. | Physical single/multi-feed baked and eligible direct routes, receiver-only visible video/audio, map+tower, no premature termination, play/pause/seek/reconnect/manual stop/handback; exact route-specific resource release. |
| Google Cast (G-TV5/6/7/8, G-C3/4/6, G.19/21/24, MEM-16, P2.1/2) | Sender baked ready/load 10:37:09.908/.911 and 10:37:55.782/.783, relay first playlist/media 10:37:10.251/.330 and 10:37:56.173/.211; load result and receiverConfirmed proxy present. 10:37:30.101 baked commentary UnknownHostException for F1 CDN, 10:37:58.859 Invalid Request, .875 queue item request status2001. No Google receiver-side process capture; no proof of actual frame/audio. Review BakedCastLiveSession, BakedCastArchiveAudio, Rust TS mux/fallback, HLS live start/PTS/segment alignment, CAF media/load ownership and first-frame vs status proxy; bounded recoverable network retries and correct audio clock, preserve authorized playback. Both protocols share producer/relay fixes where valid, not assume identical receiver defects. Baked ready takes ~13.5–14.4s, >10s target; inspect preparation and frame rate. | Both Cast routes single/multi-feed and overlays, continuous video and commentary on TV, no moving local video/audio, remote playback confirmation appropriate to protocol, pause/seek/speed/reconnect, DNS recovery or explicit actionable failure, no false success. |
| Retirement map state (G-TV3, G.22/23/24, G-C5, P0-B/P0.4, MEM-02/03/10) | User reports Lindblad out of race yet dot remains. Logs only show DRIVER_STATUS_ENGINE RETIREMENT_RECONCILED, not per-driver evidence or retirement timestamp. Trace DriverTiming/RetirementStatusPolicy Rust bit flags, authoritative TimingData and appdata precedence, cache full/preview/seek clock, NativePositionBatch.retired, immutable and draw sampler invalidation at unchanged playhead, CastTimingPublisher/Receiver and web receiver. Derive actual cause, no driver-specific hacks and no inference that stationary or pit car is retired. | Recorded or synthetic authoritative retirement -> hide at timestamp, no lingering dot on local/edge/baked/receiver map; tower OUT consistent; seeks before/after; corrected retirement with genuine resumed racing; stale status/metadata and partial publication; Rust/reference parity. |
| Back navigation (P2.8, A–H UI/lifecycle, MEM-14) | Phone logs show onKeyDown KEYCODE_BACK 10:38:04.437, canceled tracking callback onKeyUp .601, playback retires after; repeated idle-home presses not recorded. Source defect: MainActivity Root handlesBack uses !app.isTv OR actionable state, so signed-in mobile home always intercepts Back while AppState.back returns false at idle home. Remove unconditional mobile predicate; pure handlesRootBack policy shared phone/TV, delegate idle root to Android, retain existing handler branches for menus/playback/browse/picker. Regression tests added for root, active playback, nested navigation and progression. | Physical phone predictive gesture and button Back from playback through Home then system exit/background per Android convention; TV D-pad Back, modal precedence, save/resume, Cast/decoder teardown, no resource leak. |
 
Continue existing MEM-01–16 and all inherited G-TV/G-C/G/R/M/P0–P2/A–H/P0-A–P1-F. New issues are not optional experiments. G-C8/M12 allocator replacement, renderer promotion, extra SIMD/concurrency remain evidence-gated. Current user report is valid failure evidence, not proof of exact root causes for Cast/TV/retirement. The Back fix is a source candidate only, not compiled or physically tested. No new build for this intermediate checkpoint; next consolidated build after the remaining P0 source corrections and regression tests are complete, and verify 904 independently.


### Consolidated post-904 functionality + memory execution, TV proof stage (intermediate, no CI)

904 exact source d48a554bb7c2d4f65651114a99e0eecd961a3bae run 45942354697 completed success. Existing Back correction dda0f295 retained. TV pid11530 source fetch complete 10:41:56.531, native admission 10:42:42.702 (46.171s gap includes independent JVM proof and preparation); later fragment capacity 5955630 before trim proves a backing exists, not published or rendered. Independent reference output still mandatory. Source changes: Rust exact comparison of native immutable fragment against independent JVM reference using bounded 16KiB JNI chunks, no extra full payload allocation/pinning, capacity and result validation, shared lease monitor lifetime, exact old-library fallback only for LinkageError. Replaced per-bit JVM reference readBits with equivalent byte-group logic (independence intentionally preserved), regression covers all bit widths and offsets; Android actual native equality test checks boundaries/mismatch/length/retained lease. Log first-reference phase timings and byte-exact match duration separately. These are evidence-directed removal of known work, not proof that comparison alone caused the 46s gap or that TV is fixed. Keep quality, CENC/DRM gates, required reference parity and all source ownership contracts. Full CI and physical proof still pending; continue rest of batch before build.


### Consolidated post-904 batch: Cast commentary ownership and bounded recovery
- Reproduced sender commentary DNS failure in the supplied 903 phone log. A failed later fragment must not advance the committed commentary cursor or leak already decoded samples. BakedAudioReadProgress makes each read batch transactional, wipes rejected samples, and restores both progress fields on failure. BakedCastLiveSession retries only transient DNS/connect/socket timeout errors, with bounded 500/1000/2000 ms backoff; cancellation, invalid media and authorization failures remain terminal. Existing queue bounds and authorized source remain unchanged.
- Regression tests cover failure after a partial batch, rollback, zeroization and retry classification. This is a source correction, not proof of Cast playback on the physical TV. Keep the consolidated build gate closed until the remaining source corrections are ready.


### Post-904 Cast live-window startup and confirmation
- Both baked Cast transports were explicitly selecting zero even though their HLS window advances. Use CAF's default live start and Media3's default media item position for baked live only; retain exact archive-origin seek behavior. Controller remains controller-only and says connecting until matching playback confirmation. Standard Google playback status is not proof of physical display. Added live versus archive position regression. Source correction; receiver physical acceptance remains mandatory.


### Post-904 retired-driver state and derived cache correction
- Rust and independent Kotlin retirement policies now require a fresh explicit retirement correction plus corroborating lap/pit/sector progress before a retired car is restored. Late lap or pit records alone cannot resurrect it. Existing temporary-stop handling, same-time primary precedence and chronological replay remain intact. Added Rust and parser regressions. The exact Lindblad timing packets are not present in the logs, so the reported case still requires physical replay verification.
- Bump disposable replay core/preview semantic versions to 6/7, retaining the binary layouts of 3/5 but requiring one-time reindex of old derived histories which cannot reconstruct raw correction evidence. This deliberately trades a one-time cold rebuild for correct retirement state; no account, layout or user data is removed. Cache tests assert rejection of old preview versions. All previously valid source inputs and normal warm cache behavior after rebuild remain supported.


### Post-904 cold TV decoder competition
- The failing TCL clear multifeed trace shows a source AVC decoder consuming buffers and dropping vendor frames without ever rendering while native validation is pending. For a cold native TV preflight only, defer that unrendered fallback video and pause originals. Existing visible source or composed scene remains playable; phone, JVM-only and Cast route policies stay unchanged. The existing video budget enforces zero source video ownership during the cold proof. On failure, cancellation of the current capture, or transition to the rolling/protected backend, restore normal source ownership. Handoff still waits for verified proof and decoder release; no quality or DRM gate is relaxed. Added predicate regression. This is an evidence-based resource contention correction, not proof of the precise vendor stall cause or measured memory improvement.


### Consolidated functionality and memory candidate: CI gate
- Reduce repeated archive commentary playlist scanning: binary search the committed segment start (no cursor mutation on retry), stop at the requested horizon, retain all fragment and encryption checks. Regression covers 10,000 segments with at most 15 lookups and boundaries. Added full V3 derived-cache rejection test. This is a CPU/wakeup optimization, not a measured PSS saving.
- Candidate includes the Back navigation, Rust byte-exact proof, TV cold-decoder contention, Cast live start and confirmation, transactional commentary and retirement/cache fixes above. Compared with exact green 904; all source changes and regression tests are committed on the existing branch. This final non-skip push opens one coordinated Android CI run. No intermediate APK builds. Automated verification and matched physical tests are still required; no claim of a green build or TV/Cast acceptance before evidence.
- Existing Architecture G, G-TV/G-C, R/M, P0-P2 and MEM-01-16 scope retained. Larger native timing storage, GPU/Media3 ownership and graphics allocation-site attribution remain open for evidence-led follow-up; do not lower selected quality, remove overlays, weaken DRM or force a renderer/allocator switch. Old derived timing caches reindex once. The local tool environment lacks Android SDK and Rust toolchain, so full compilation is delegated to the existing CI gates.


### Post-905 consolidated memory ownership and CI correction (2026-11-27)
- Run 45947306459 failed its mandatory test-footer guard: ArchitectureGBAndroidTest had six tests but the runner expected five. Corrected without suppressing tests; after adding compact timing coverage, runner now requires seven. All nine instrumentation class counts were checked against source.
- MEM-10: completed native archive map timestamps and speed/gear timestamps now use bounded Rust-owned exact 32-bit millisecond deltas and one 64-bit UTC origin (4n+8 bytes versus 8n). Shared exact histories are interned; live builders, old cache format, Java fallback, coordinates and remaining tower metadata stay intact. Registered native payload is capped at 64 MiB and retired by last-holder reachability; GC timing, peak parsing overlap and device memory are not yet measured. Rust and JNI tests cover bounds, interpolation, stale/gap handling, backward seeks and shared ownership. Added nativeCompactTimingOwners/Bytes/Samples diagnostics; these are not additive to PSS.
- MEM-11: verified Media3 1.11.1 exposes unused allocator blocks; report free pool bytes separately, trim released blocks to a 2 MiB warm reserve on active pressure/seek recovery and to zero on retired/parked owners, including receiver retirement. Never reduce DefaultLoadControl's playback buffering targets, discard queued media or change rendition/DRM. Allocator unit test verifies in-use blocks survive trimming. Active Media3 sample-queue payload and deeper per-track attribution remain evidence-gated; no measured reduction claimed.
- MEM-08/09: full-frame color-only GLES output prefers compatible EGL configurations with no depth, stencil or multisampling and requests EGL_BUFFER_DESTROYED for full redraws, including clear cast producer; preserves baseline color/native visual/recordable compatibility and protected attributes. Original config retained when an equivalent is unavailable. Log selected attachment configuration and swap retention support; actual GPU bytes remain unmeasured. Existing SurfaceView A/B remains explicit rather than forcing the experimental renderer. Larger driver/BufferQueue allocation attribution and matched device A/B remain open.
- Consolidated branch commits: 38e2745 (CI count), 3fd6115 (Rust store), 920e784 (production timing integration), 81a4541 (Media3 pool), ec7c6fb (EGL), this final commit (diagnostics, receiver retirement, regression, plans). Earlier 905 functional corrections preserved. No intermediate builds requested; final source-bearing non-skip commit opens one coordinated CI run. Last verified green remains 904 until workflow finishes. Full Android/Rust compilation and all physical TV/Cast/phone acceptance are pending; do not infer savings from source changes.


### Post-906 remaining memory ownership batch (2026-11-27)
- Exact Build 906 run 45954098499 / 7e667ad82d361dfe9987fe254921f853690bdb15 completed successfully. All previous functional fixes and release gates remain.
- MEM-08/09: the existing already-black local baked Cast TextureView now requests a 16x16 opaque buffer while the independent Cast encoder continues at its original resolution; restore full viewer geometry before local draw, reapply after layout changes, record EGL-observed dimensions separately from requested output size. Apply color-only EGL compatibility and non-preserved full-redraw swaps to the encoder too. This does not imply that the vendor released older slots or that physical PSS fell. No selected video/atlas/receiver resolution changed. Existing SurfaceView experiment remains explicit; default promotion remains conditional on matched hardware evidence.
- MEM-11: after confirmed independent receiver ownership, stop seekable non-live local replay media periods instead of leaving their selected/disabled queues resident; preserve media source/playlist and restore remote position before prepare. Live windows and baked producers are excluded. The matching Google receiver clock is used at handoff with a safe duration fallback; Google still uses the existing relay route and direct/native routes are local. Add per-source actual Media3 allocation-block counts at the public Allocator/MediaSource boundary for ordinary muxed feeds, composed archive video, audio and caption children, protected composition and receiver. Preserve Media3's own allocator, target, times, DRM and track selection. Source block capacity is not per-track sample payload; muxed children are not falsely reported as pure tracks. Remaining active local playback payload is not discarded or capped without physical evidence.
- MEM-10: Rust now owns exact eligible archive timestamps (4n+8), coordinates (4n+8 per 256 samples), speed (9 bits) and gear (4 bits including unknown sentinel). Native registered payload shares the existing 64 MiB admission ceiling. Reference-counted/phantom retirement and bounded JNI region reads preserve immutable historical semantics, seek and interpolation. Coordinates must round-trip exactly; invalid/fractional/wide blocks and out-of-range values retain original reference arrays. Completed map and telemetry parser backings are dropped after safe publication; old immutable previews retain their own references. Live histories and sparse tower metadata stay in their current reference models. Cache format unchanged. Native coordinate/telemetry diagnostics report distinct payload sizes, not additional PSS. Added Rust and Android exact round-trip and seek regression coverage.
- Candidate source changes: a566cda (remote replay queue retirement and blank preview), 4121db6 (actual Media3 source ownership), 3f0b92b (Rust coordinates), this final commit (packed values, lifecycle hardening, diagnostics and master plan). One consolidated source-bearing build; no intermediate builds. No physical acceptance claimed. GPU/codec vendor allocation attribution, measured PSS reduction, active local sample payload changes, renderer default promotion, allocator replacement and physical functional/quality/endurance matrix remain evidence-dependent. Existing 20% matched PSS acceptance and all inherited G/R/M/P0-P2/G-TV/G-C/MEM gates retained.


### Post-907 consolidated graphics, buffer ownership and timing implementation
- Build 907 (run 45961665113, 7bc05a1) passed 101 Rust tests and native Android compilation, JVM tests and APK assembly, but failed its final four-ABI JNI symbol gate at armeabi-v7a. The previous static verifier still expected nativeSamplePositions after the intentional nativeSamplePositionsPacked rename. Replaced the stale NativeHotPath list with all Java JNI declarations, verifying both packaged and full dynamic exports, explicit missing-symbol failures and regression. Existing signing, 16KB alignment, build ID and symbolication gates remain mandatory.
- MEM-08/09: fixed TV layout ordinary composed playback now measures the visible window union, outward rounds to physical pixels, and sizes the actual EGL AndroidView to that region when at least 10% smaller. Coordinates are translated one-to-one; source atlas, z ordering, source crops and selected rendition stay identical. Clear and protected scene paths share this change. Keep full canvas during capture/Cast, PiP and free arranging, unchanged black outside video. Existing renderer switch remains explicit; no forced GPU or allocator backend replacement. GPU driver memory remains unmeasured until matched physical traces.
- MEM-10: completed archive state, driver snapshot, crossing, lap-count, tyre and weather timestamps now use the same bounded Rust-owned exact timestamp pool; JNI tower and map metadata lookups retain their owners without recreating long arrays. Existing cache format and live history remain unchanged. Numeric dense archive storage from 907 is preserved. Sparse histories below the native admission threshold or unrepresentable histories retain original arrays; domain snapshots and strings remain on JVM. Added Android round-trip checks for sparse histories and preview baseline.
- MEM-11: active finite clear composed archive Media3 forward buffering now uses measured successful video and audio segment durations and delivery times, after six qualifying observations per lane. Three actual segments plus twice the worst observed combined service time, adjusted for playback speed, defines a conservative high horizon; one segment hysteresis. If either lane is unknown, slow, stale, subtitles are selected, user seeks, source changes, playback stalls, remote ownership, error or unsupported speed, revert to the existing normal Media3 loading policy. The policy changes only further-loading decisions; no discarding active samples, quality or DRM change, no startup/rebuffer/back-buffer rule change. The existing live, protected and independent receiver buffering remains unchanged. This is a candidate adaptive forward-memory reduction, not proven on real networks/devices. Exact selected source and rebuffering tests are mandatory.
- Source checkpoints: e1bca39 packaging fix, 291d656 fixed-layout output, 065c4ba sparse Rust timing, final consolidated commit active archive forward-buffer policy, tests and both master plans. No intermediate builds. Full CI and physical functional and memory acceptance remain required. Preserve prior 20% matched PSS gate and all inherited acceptance scope. No claim of complete device-level optimization or closure.


### Build 908 CI correction
Run 45964981267 at ca6cbf7 passed host Rust and Android ABI feasibility, full-scene HEVC and baked AVC/AAC transport fixtures. Python validation passed 121/122 tests; the one failure was the Build 691 protected-atlas source-binding regression expecting the old `scene` variable instead of `outputScene`. Updated the guard to independently require registered-host binding in both EGL renderer paths, the shared output modifier, exact one-to-one window translation, and the full-canvas fallback for capture, arrangement and PiP. No production code reverted, no test skipped, no release or device acceptance gate relaxed. Exact updated assertions were checked against branch source before committing; Gradle, packaged JNI verification and emulator acceptance still require the new CI run.


### Build 909 physical startup regression correction (2026-11-27)
- CI run 45966326108 passed all five jobs. Physical Samsung S22 Ultra capture pitwall-20261127-090331-183.log.txt shows repeated successful playback offers (one and four feeds, two content IDs) immediately followed by ExoPlayer Init, but no feed preparation/decoder or rendered-frame evidence. Timing archive and Rust library load successfully. User reports both cards and Continue Watching fail. This supersedes physical readiness of 909; memory tests blocked.
- Source inspection of Media3 1.11.1 identifies the new ArchiveMeasuredLoadControl Kotlin interface delegation as a concrete compatibility defect: ExoPlayerImplInternal immediately invokes getBackBufferDurationUs(PlayerId) and retainBackBufferFromKeyframe(PlayerId), whose Java defaults dispatch to deprecated throwing stubs unless overridden; other lifecycle/default callbacks have the same hazard. Forward all current callbacks explicitly to the original DefaultLoadControl, retaining measured archive-only shouldContinueLoading policy, allocator, source allocation ledger, GPU and native compact timing. Exact physical exception was not recorded, so causality remains a strong source hypothesis until regression and device confirmation.
- Add startup-exception type and code locations to playback task failures without logging raw messages, URLs or tokens. Add actual Android ExoPlayer construction, wrapped local PCM playback to READY/ENDED, stop/reprepare and release regression using production load control and allocation factory, mandatory on API 29/31/36; update instrumentation footer from seven to eight. No F1 credentials/DRM changes, no quality change or reversal of memory work. One consolidated CI run. Physical startup and matched memory/TV/Cast acceptance remain open.


### Build 910 evidence-driven M14–M23 implementation checkpoint (2026-11-27)
- Build 910 physical Rust four-feed archive: 663.5 MiB PSS, approximately 84.4 MiB of reachable Media3 allocation byte arrays, one hardware HEVC decoder; captions prevented measured adaptive loading. Production samples and selected child identities must qualify before reducing forward loads. M14/M15: selected audio/video/text child listeners, source installation tokens, six observations per selected child and upstream producer, 25s lower high-water mark, conservative default on unknown/slow/sparse data, seeks, errors, unsupported speeds and remote sessions. Existing DefaultLoadControl lifecycle forwarding unchanged. No sample discard or rendition change. Physical reduction not yet measured. This is an intermediate skip-CI checkpoint; M16–M23 remain open.


### Build 910 physical evidence: consolidated M14–M23 implementation
- M14/M15: selected child event attribution including captions, with actual upstream fragment producer cost, fresh six-sample windows, conservative 25–50 second adaptive range; unknown, sparse, slow, invalid or remote streams retain normal behavior. No caption removal or active sample discard.
- M16: retire EGL thread state and scene geometry references; release tower render-node keys. Actual driver/HWUI retention remains a physical acceptance gate; no hidden GPU controls or forced activity recreation.
- M17: cold Rust proof no longer competes with an unrendered provisional phone/TV source; bypass intentional display deferral under unhealthy cadence. 136 provisional and 1,312 composed queue timeouts in Build 910 require physical retest. No arbitrary codec slot changes.
- M18/M19: Rust capacity class within existing slack/ceiling/idle permit for the measured 7.6–9.5 MiB atlas output range; no increased arena cap. Retire only fully delivered past local-clock segments in the matching seek epoch after last reader; do not touch shared casting leases. Limit a second speculative warm group when fresh advancing local Media3 buffer is already >=25s.
- M20/M21: reduce map draw-loop allocations and repeated conversions, retire retained tower visual keys; existing Android rendering and adaptive refresh paths preserved. No new FPS thinning.
- M22: native exact 16-bit dictionary rows for completed driver histories; repeated domain lists/cells/strings deduplicated, only requested row reconstructed, direct retirement flags for map. Preserve exact JVM fallback for live/unsupported values. Registry telemetry accounting now includes dictionary indices.
- M23: reuse finite immutable MPD backing between preflight and playback; cold provisional decoder deferral above. Existing DRM/session validation, rendition, captions and independent cast source behavior unchanged by design.
- Baseline Build 910 phone PSS 663.5 MiB; historical 903 594.9 MiB is not a matched A/B. Physical targets: <=550 MiB total, active Media3 45–55 MiB, GPU retained above idle after 120s <=20 MiB, native reuse >=85% (target, not prediction), startup <5s, median seek <1s; no regressions in playback quality, captions, decoder count or DRM. Verify same content, qualities, layout and captions at 1x and 1.5x; test phone, TV, both decoder modes, Google Cast and Pitwall Cast. No fresh Kotlin run required. Physical savings and steady queue/driver attribution remain unproven until acceptance. Do not equate green CI with success.

- Final M20 addition: unchanged tower polls return the previous immutable publication without allocating a candidate or performing a second deep comparison. Changed telemetry and seeks retain the original comparison/publication path; regression test covers both. This is the consolidated build trigger after four skip-CI checkpoints, not physical acceptance.


### Build 911 CI contract correction (2026-11-27)
- Run 45997875354 failed in the Python production-wiring suite: 121/122 passed; two stale prefetch literals in one test still expected the pre-M19 thermal-only limit. Rust host suite reported 103 passed; Android compile, instrumentation and publication did not execute.
- Correct both source assertions to positively verify local-only fresh demand at scheduling and each candidate, before delivery; retain existing pressure, thermal, memory admission, cache and shared Cast ownership checks. The worker already recalculates currentLimit, so no production policy rewrite is justified. Extend the behavioral test for exact 25-second/2-second boundaries, absent/future demand and zero/one thermal limits.
- No production source, quality, captions, DRM or decoder/cast behavior changed in this correction. Full CI and matched physical acceptance remain open; no claim of measured savings. Build only once for the correction.


### Build 912 Media3 1.11.1 child listener compile correction (2026-11-27)
- Run 46002404522 passed the previous M19 Python validation and native/fixture gates; failed in :app:compileDebugKotlin at FeedPlayer.kt:1131, onLoadStarted overrides nothing. No Android unit tests, instrumentation or signed APK publication completed.
- Verified against the exact AndroidX Media3 1.11.1 MediaSourceEventListener.java tag: onLoadStarted(windowIndex, mediaPeriodId, loadEventInfo, mediaLoadData, retryCount) requires the fifth Int. Completed/canceled/error signatures already match. Preserve actual child-source identity and installation-token filtering.
- Forward retryCount into ArchiveForwardBufferPolicy: selected retried loads invalidate adaptive eligibility evidence and cannot count as clean samples; unselected alternate retries do not disturb the selected policy. Require fresh observations to requalify. No change to actual Media3 network retry, captions, quality, DRM, Rust engine or Cast paths.
- Add selected retry, unselected retry, failure and fresh-window regression. Isolated Kotlin host policy harness (transcribed production logic with the proposed correction) passed nine checks. This is not an Android build or physical test. Full CI and matched Build 910 physical memory/functionality acceptance remain open.


### Build 913 physical findings and M24–M37 consolidated coding checkpoint (2026-11-27)
- Build 913 is the last green APK. The supplied TV and mobile evidence establishes working TV multifeed, incomplete timing coverage, unstable Cast, and preliminary mobile PSS reduction of 98.9 MiB. See [consolidated M24–M37 engineering checkpoint](handovers/PITWALL_TV_BUILD_913_M24_M37_CONSOLIDATED_ENGINEERING_2026-11-27.md) for evidence, exact commits, implementation boundaries and acceptance criteria.
- Five skip-CI source commits implement bounded rolling position/tower previews, independent panel ownership, reduced diagnostic allocations, shared Cast service startup, receiver status isolation, baked AVC AU detection with Rust fixtures, exact-input proof caching and bounded native size-class candidate. The workstream matrix explicitly records unfinished independent screen-off production, native rolling indexes, Cast pacing, active graphics/frame scheduling and staging/seek/Java ownership.
- This is NOT 100% unconditional coding completion. No CI trigger, no new APK, no physical acceptance. Preserve prior M14–M23 gains and continue the same consolidated scope before triggering CI.


### Build 913 M24–M37 continuation, 2026-11-27

See `docs/handovers/PITWALL_TV_BUILD_913_M24_M37_CONSOLIDATED_ENGINEERING_2026-11-27.md`, continuation checkpoint at `d7e4ac002758f75f831482952eb4eebbcfe8920b`. Ten skip-CI source commits add disk-indexed replay windows, incrementally Rust-packed timing snapshots, independent clear Cast presentation and playback lifetime, baked TS segment completion, relay admission and bounded capture pacing, and reuse of independently verified native output metadata. Isolated JVM logic checks passed; full native/Android build and physical acceptance are not run. Remaining native parsing, cold validation/staging, active graphics and independent presentation scheduling work is explicitly open. Do not trigger consolidated CI or mark completion until outstanding unconditional coding is addressed.


### M24–M37 consolidated compilation/verification checkpoint

- Source integration since Build 913 includes the native rolling position spool index, Rust compact tower deltas and rolling timestamps, independent map/tower publication, app-owned background Cast and private presentation, exact baked transport, bounded status/relay/recovery, exact cold-reference reuse and independent shared EGL latching in clear/protected renderers. Mixed canonical timing records now validate their whole envelope in Rust and pass complex driver rows individually to the existing exact lifecycle/sector parser; canonical simple rows avoid JSONObject allocation. Complex rows are prevalidated before any mutation and released after each record. Remaining noncanonical/unsupported shapes preserve the full reference fallback.
- Cold-reference proof retains the identical full bounded EBSP validation range and parser semantics but reuses one wiped 8 KiB scratch per worker, with logical bit length preventing access to stale tail bytes. Larger legitimate headers retain the original allocation and proof path. Regression checks malformed end-of-prefix sequences and logical length. Sealed timestamp-block publications reuse exact already-owned blocks where aligned.
- Correct mandatory native instrumentation count from eight to twelve tests and register the clear shared-image EGL test in the full emulator job. An unsupported EGL assumption skip is not device compatibility or physical acceptance. No quality, captions, decoder count, DRM or signing gate relaxed.
- Remote workspace read-only checkout succeeded but toolchain installation was rejected by its service. User expressly requested actual GitHub Actions toolchains; this source-bearing coordinated commit opens one existing full verification run, not a declaration of 100% coding/physical completion. Rust host tests/Clippy/ASAN, Android ABI builds, exact fixture proofs, Gradle, signed APK assembly and required emulator jobs must report success. Build 913 remains last green until results. Any still outstanding native complex semantic optimization, measured retention, GPU compatibility or cold-proof latency remain open rather than inventing percentages. Matched physical acceptance on both devices and both Cast routes remains mandatory.


### Build 914 verification and M24–M37 remaining coding batch

- Run 46040883209 (a5f9e8f) passed the coordinated-change check, pinned Rust host tests/Clippy, ASAN/LSAN (115 tests passed), four-ABI native compilation and HEVC/AVC-AAC fixture preparation. It failed before Gradle at the Python instrumentation-class-count guard (121 of 122 checks passed; actual ten classes vs expected nine). Corrected the guard to require exactly ten distinct mandatory classes including the EGL shared-image test and verify each actual JUnit count. Do not claim Android compilation or APK publication for 914.
- M25/M37: native canonical complex timing index validates bounded nested fields, preserves ordered sparse deltas and rejects unsupported coercions/duplicate keys transactionally. The Android adapter reads exact field spans; reference JSON is used for unsupported forms. Rust now performs primary/supplement lifecycle gating, lap-state transition, retirement reconciliation, and exact mini-sector merging/fresh-progress recognition. Retained mini-sector values use primitive immutable arrays. Rolling timestamp publications use Rust span windows sharing existing immutable timestamp payload instead of rebuilding full arrays; parent owners remain pinned until alias retirement. Native registry reports unique backing samples, charges window indexes, preserves existing 64 MiB limit and supports exact batched floor lookup. A short mutable tail is packed once per publication size and reused at the seal. Kotlin still owns Android-facing strings, domain objects, fallback, and UI; no quality or timing fields removed.
- M32/M36: cold independent reference still validates the same complete 8194-byte bounded prefix and full fallback. Both original EBSP conversion and rewritten header materialization now reuse private wiped bounded scratch, with logical range validation. Exact source/output validation, native byte parity, and independent fixture decode remain required. No weaker proof replaces the cold reference. Actual startup/seek target attainment is physical evidence-dependent.
- M33: native staging admission picks the smallest sufficient free backing, or replaces an insufficient free slot before allocation. Keep one useful warm slot matched to the most recent actual admitted demand, never an empty slot or more than one warm backing. No new allocation ceiling or idle reserve, and in-flight/leased payload cannot be retired. The 7.8 MiB observed staging high-water is not proven leaked memory. Physical allocator residency/reuse remain acceptance measurements.
- M34/M35: ledger now admits exactly two EGL contexts only with one decoder texture, as required by the independently latched surfaceless path. Runtime-specific latch faults trigger at most one rebind via the same existing renderer and same protected/clear policy, with Media3 detach and output epoch invalidation before EGL teardown. Added sampling fence wait duration/count evidence and trace section; no extra full-size texture/window, no secure readback, no inferred driver-byte saving. Vendor EGL support, memory, and real physical presentation must be measured.
- New mandatory native instrumentation count is fifteen; complete 10-class runner guard and source recovery regression retained. These are source-complete candidates for the identified unconditional changes, not verified build or measured physical acceptance. Commit this final coordinated batch with the existing full CI gate enabled. Build 913 remains last verified green until a new exact-source run passes. Additional modifications justified only by failed compile/test or new device evidence, not arbitrary attempts to reach numeric memory targets. Physical matrix (phone/TV, clear/protected, single/multiple decoder, captions, Google/Pitwall Cast including screen off, 1x/1.5x, seek/layout/endurance) and all memory/presentation thresholds remain unchanged.

### Build 916 baked HLS integration correction

Build 916 (run 46043318078, f5afc13) passed Rust unit/sanitizer tests (122 each), all four native ABIs, 123 Python tests and Android Kotlin/Java compilation. The Gradle suite ran 1064 tests; only the Media3 HLS regression failed (52 sample records for 50 encoded AUD-free pictures). Independent Rust TS/PES and FFmpeg decode passed. Inspection of pinned Media3 1.11.1 confirms the generic synthesized EOF delimiter and retained slice state can create non-picture metadata across segment boundaries. The baked-only receiver now commits exactly one sample per owned video PES using Media3 public ElementaryStreamReader callbacks, keeps Media3 H264Reader for format and unchanged elementary byte transfer, and retains normal ADTS parsing. PTS and random access derive from the existing local mux's packet headers; no buffer duplication, video encoding change or arbitrary HLS/DRM/caption change. Missing PTS, missing first random access, missing format, unfinished/over-limit PES are rejected. The real fixture regression checks both AUD-present and AUD-free segments with fragmented reads, repeated EOF, exact source bytes, PTS, key flags, 25/50 video pictures, 94 AAC samples and no leftover data. No expected counts relaxed. The full Android suite and physical acceptance still require exact-source CI/device results.

### Build 917 exact-certificate instrumentation correction

Build 917 (46044754266, 9afacc89) passed native compilation/tests/sanitizers, Python (123/123), the Android unit suite including the corrected baked Media3 fixture, and debug/instrumentation APK assembly. All three mandatory emulators (API 29/31/36) reached ArchitectureGBAndroidTest and failed the same obsolete reference-count assertion in verifyArchiveBridge: after a seek to the exact previously byte-matched request the count remains one rather than two. M32/M36 exact certificate reuse still verifies output initialization and media SHA-256 plus independent timing/flags before publication. Correct the test contract, not production behavior: require one reference on identical input, exact output parity and a new generation epoch, retain old-epoch publication rejection, then seek to an uncertified sequence-only changed request with identical media/configuration and require a new independent reference, output digest parity, different media and a distinct epoch. Existing invalid-command fallback, low-RAM admission and cleanup assertions remain. No production runtime changes or removed tests. Other emulator gates after GB had not been reached; full exact-source instrumentation and physical phone/TV acceptance remain outstanding.

Remote preflight: 123/123 Python checks passed on the proposed test change; Android instrumentation requires CI.

### Build 918 correction and complete emulator failure reporting

Build 918 (46047011240, 7bfc112) passed the native/Python and Android unit stages but failed compileDebugAndroidTestKotlin because four new assertNotEquals calls lacked their explicit JUnit import. Add the same supported JUnit assertion used in ArchitectureG0AndroidTest. In addition, the emulator runner now collects all independent mandatory test-class failures and returns nonzero at the end; boot/installation prerequisites still fail immediately, assertion failures are not retried, no test or publication gate is removed, and exact footer checks remain. This is to expose multiple independent regressions in one run instead of serial rebuilds. Host fake-adb regressions check all-pass, multiple failures with subsequent classes executed, and TLS-only failures. The production runtime is unchanged. Actual Android compilation/instrumentation and physical acceptance still require CI/device evidence.

Remote preflight verified bash syntax and all 125 Python tests (including both simulated runner regressions). No Android toolchain in this workspace; exact-source CI remains required.

### M51–M60 physical evidence batch, Build 919 baseline (2026-11-28)

Build 919 cdd1c6d4 is green. Actual 1.0.1838 S22 Android 16 four-clear-feed test: 1080p+3x540p, 671.6 MiB peak PSS, graphics 302.9 MiB, composed Media3 80.75 MiB, 63/107 native backing reuses, 14 pinned/15 fit/15 disabled misses, zero reported decoder drops, 1,686 queue timeout occurrences, median seek 2.86s. No native heap stack/Java graph, no physical TV or Cast evidence. System and CPU trace coverage is partial. Measured GL rates not scanout.

M51 P0 BufferQueue release pacing and M52 P0 graphics allocation: investigation remains open; driver queue count is not allocation ownership proof. Preserve protected and clear path. M53 P0 measured buffer policy: retain qualified horizon when paused, do not drop samples or reduce startup/rebuffer minima. M54 P1 distinguish preempt-only scrub hint from committed generation fence; preserve exact user target and Cast/rolling behavior. M55 P1 startup: cold phases offers 1747ms, video init 3088ms, probe 1831ms; warm 263/667/2689ms. Do not skip independent proof. M56 P1 unbind retired archive production window and token, preserve caption and audio selection resets. Heap graph still required to attribute 123 MiB post-stop JVM. M57 P1 native pool size replay on measured 107 extents still favors current 256KiB class over 64/128/512KiB; pinned/disabled misses need verified lifetime/headroom evidence before changing admission. M58 P1 refresh coordination; M59 P1 reliable diagnostics; M60 P2 accelerated output remains last priority. No target marked accepted from source alone.

Checkpoint only; all ten workstreams remain one batch. No CI until remaining unconditional coding scope is resolved. Physical acceptance and missing ownership evidence cannot be substituted by tests.


### M51–M60 checkpoint 2 (Build 919 evidence)
- M53 soft 55 MiB admission only above the measured safe low watermark; default and rebuffer safeguards untouched. Not a hard cap.
- M55 overlap the same exact declared-clear uniform initialization map with playlist fetches, without extra media fetch or skipping final validation.
- M56 terminal release removes producer binding and source token; Java retained-object attribution still requires heap graph.
- M57 bounded two-request capacity history: ideal immediately retired model 80/107 → 88/107, equal peak 9961472 bytes; real reuse still limited by pins and admission. Reset history at pressure trim.
- M58/M60 reset cadence health on refresh/speed transitions, exclude mixed five-second windows. Do not force display modes or accelerated output.
- M51/M52 vendor queue/graphics attribution and physical acceptance remain open. No speculative codec knobs or resolution changes.


### M51–M60 checkpoint 3
- M58/M60 reject mixed observation windows by correlation owner and revision; stale callbacks cannot restore a healthy accelerated trial after a mode or speed reset.
- M59 legacy capture scripts stage named configurations and preserve CLI diagnostics. Two-command v2 scripts separately supplied. On-device Perfetto coverage not yet verified.
- M51/M52 evidence analyzer groups actual nonblocking queue refusals, app graphics-owner lifetime and platform memory; these are not physical queue bytes or frame loss. No vendor codec parameter changes without physical proof.
- Corrected instrumentation constructor for M53. Android/Rust compiler and physical target validation remain required.
