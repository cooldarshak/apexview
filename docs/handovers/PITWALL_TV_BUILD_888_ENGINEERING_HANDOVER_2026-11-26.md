# Pitwall TV / ApexView — Build 888 continuation handover

26 November 2026 | Repository: [cooldarshak/apexview](https://github.com/cooldarshak/apexview) | Branch: `feature/architecture-g-rust-stitching`

## 1. Copy-paste prompt for the next chat

> Read this ENTIRE handover from the repository at
> `docs/handovers/PITWALL_TV_BUILD_888_ENGINEERING_HANDOVER_2026-11-26.md`
> on `cooldarshak/apexview`, branch `feature/architecture-g-rust-stitching`.
> Then read both existing master engineering plans in full, the Build 880
> approved M1–M13 handover, and the referenced scripts and relevant production
> source. Verify actual branch head and Build 888's exact-source GitHub Actions
> status. Continue the existing Pitwall TV project from Build 888 onwards.
> Do NOT restart, redo completed work, merge to another branch, weaken tests,
> alter quality/DRM/authorization, or create a competing plan.
>
> If Build 888 is red, inspect all failed steps and correct all verified
> failures together. If green, review exact-source changes and obtain the
> specific missing on-device native heap / graphics and matched A/B evidence
> using the phone-only instructions below. Do not request a repeat of the
> generic system trace in place of the missing native allocation profiles.
> Work on all remaining feasible approved engineering scope, including the
> original P0–P2 plan, Architecture G.1–G.20, G-C memory optimization M1–M13,
> and G-D integration/release acceptance in dependency order.
> Finish 100% of the agreed unconditional coding scope before ONE consolidated
> functional commit/CI run. Do not stop halfway or trigger intermediate builds.
> Evidence-gated changes must be marked blocked with the exact evidence needed.
> Preserve every existing feature, both platforms and all applicable modes.
> Update BOTH master plans, separate coding/CI/physical acceptance, and report
> what is actually verified and what remains open. User sign-off is mandatory
> for phase closure and changing defaults. Be concise and precise when asking
> the user to test: exact APK, settings, steps, files and expected evidence.

## 2. Exact checkpoint and CI discipline

Build 888 source: `01c0423bfe63aeeda2eddab12a4bb748cbc8e45c`.
[Run 45753602860](https://github.com/cooldarshak/apexview/actions/runs/45753602860).
At handover creation the build job was still in progress; do not assume green.
This handover is a docs-only child of that source commit, not a new APK.
The Android debug workflow ignores docs/** and *.md, so do not dispatch a
redundant build just for the handover. Verify head and exact tested SHA again
in the new chat. If a newer revision exists, reconcile it before editing.

| Build | Source / run | Verified status / relevance |
|---|---|---|
| 880 | 4b97ef8c77ee8df047e978f39181ed6795ea0572 / 45651321774 | Green; lazy compact spare native slot; approved M1–M13 baseline |
| 882 | 2b05c4c320307ec43ac445f97892bf3b29420fda / 45657408075 | Green; compact idle trim + process telemetry |
| 883 | c06a6cc17eee4dccc857fff5553aa148e5390ad8 / 45662092202 | Green; speculative admission and owner accounting |
| 884 | 8793e583fff8dacc44abd3ea057c298e1c0faa46 / 45668693169 | Green, five jobs; consolidated memory scope, conditional M12/M13 retained |
| 885 | 967297cf82c440269bb2fa1bb12cfc65cc11d8a7 / 45742379087 | Failed old JVM-only packaging regression; native host 59/59 passed |
| 886 | dd4e2b45008b6c330fb8645fa0f66a39ef30ebcf / 45746450510 | Green, five jobs; native-backed archive, thermal override, exact-size accounting correction; last measured phone build |
| 887 | 8f9d37798ac0bda63da54fe8d4a27f4beeae5811 / 45752249797 | Failed first Android Rust ABI compile: E0308, i32 INVALID vs i64 result in nativeAdoptAtlasFragment. Host kernel checks ran. |
| 888 | 01c0423bfe63aeeda2eddab12a4bb748cbc8e45c / 45753602860 | Corrected explicit i64 conversion; full Python suite 110/110 and change-contract passed; CI in progress at handover |

Do not mistake a green host test, a green compile, a signed APK, or emulator
test for physical acceptance. Mandatory workflow runs host native tests,
sanitizers, four ABI feasibility/builds, Kotlin/JVM/fixture and independent
decode checks, signed APK identity/ELF symbols/build-ID checks, exact-source
encrypted staging, API 29/31/36 emulator jobs (read actual workflow matrix),
then the final publication gate. Never remove, skip or relax gates.
Use the exact workflow rather than assuming historical test counts/SDKs.
No local Android/Rust compiler was available in the remote staging environment;
Python suite and static review were performed there. New Rust/Android tests
must actually execute in CI. Check actual failing logs, not only job summaries.
Before any commit verify parent HEAD and changed files; atomic consolidated
commit; verify remote blob hashes, source SHA, run ID and conclusions.

## 3. User requirements and architecture

User is a Lead Business Analyst, not a developer; phone-only testing. S22
Ultra SM-S908E (Android 16 in latest logs), adaptive 120 Hz, heat-prone; TCL
55C655 Android TV 12, limited RAM; Software DRM ON needed on TCL.
No PC, root, paid OpenF1, proprietary stitching SDK or hosted video pipeline.
Use legitimate F1 TV authorization/entitlement and the existing app.
Architecture G is native on-device Rust HEVC/CMAF stitching via checked JNI,
bounded native owners and Media3, platform Widevine and MediaCodec.
One composed HEVC stream / one decoder / one surface in single-decoder mode
where eligible; preserve ordinary single-feed and multiple-decoder routes
without inventing stitching where not needed. Do not rewrite UI, player or
entire Kotlin app in Rust. Keep Rust opt-in, actual engine/reason-coded
fallback explicit; do not silently change production default.
Preserve protected stream rules, no key extraction, no secure screen capture
or entitlement bypass. Only validated supported profiles. No LV-specific
special casing. Avoid speculative allocators, NEON, new dependencies or
codec buffer-count hacks without measured justification.
Thermal override: Advanced settings > Temperature-based playback adjustments
(default ON, persisted locally including receiver). OFF disables only
Pitwall's thermal playback policy; actual thermal status still logged,
Android hardware protection, quality selection and memory safeguards intact.
Preserve all existing features: clear/Widevine, finite/rolling, single/mixed
feeds, single/multiple decoders, manual per-feed quality and Auto, existing
canvas/atlas geometry, Google Cast and Pitwall Cast native/relay/baked paths
with commentary, overlays, captions, audio, map/tower and timing controls,
all layouts, named save/restore, keep-clear/locking, portrait, TV DPAD,
Continue Watching, seek/lap seek, 1x/1.5x, login, release/upgrade.
No quality degradation to meet a memory or fps number.

Hard rule: when asked to finish agreed scope, no interim functional commits,
tiny builds, progress-only changes or diagnostic-only substitutions. Work
in staged checkout; audit all unconditional requirements and tests before
commit. Corrective commit allowed for actual CI failure. User explicitly
requested this documentation-only handover, exempt from functional build.
Do not change main or parent branches. Existing branch lineage retained.

## 4. Authoritative plans (read all, do not rely on stale ledger percentages)

- [Architecture G master](PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md):
  G.0, G-A/B/C/D, G.1–G.20, eight refinements, research, ownership/security,
  all CI/physical gates, latest 884–887 entries at top.
- [Performance audit master](../PITWALL_PERFORMANCE_AUDIT_PLAN.md):
  original 16 workstreams, Batch 1 (Media3 1.11.1 and reversible clear
  SurfaceView A/B), Batch 2 (BufferQueue, adaptive refresh, power/memory,
  thermal, seek and DRM), post-833 coordinator, audio and captions, release,
  current G-C/M1–M13 ledger. Historical statuses are not current sign-off.
- [Build 880 approved memory scope](PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md):
  all M1–M13, original research synthesis and exact user-approved success
  criteria. Do not restart from Build 880; apply subsequent implementation.
- G.0 feasibility explicitly signed off. G-A foundation coding and automation
  complete. G-B explicitly signed off by user; carried physical evidence
  still required. G-C integration/performance remains open (physical memory,
  mixed/remote Auto, switching, Cast, real protected/TV). G-D G.18/G.19 and
  carried physical release gates pending. G.1–G.20 still authoritative;
  phases overlap, not separate competing projects.

Original workstream index:

| ID | Scope and remaining acceptance emphasis |
|---|---|
| P0.1 | Segment priority, seek/cancellation and exact seek latency |
| P0.2 | HEVC memory, delivery, GC, copy ownership, sustained peak/retention |
| P0.3 | Codec/GL rendering, BufferQueue ownership, independent presentation |
| P0.4 | Retained timing map/tower, UI deadline and telemetry freshness |
| P1.1 | Smallest conforming HEVC atlas, mixed renditions and device capacity |
| P1.2 | Display mode/refresh matching, physical 50/75fps evidence |
| P1.3 | Quality, manual/Auto and thermal/power policy |
| P2.1 | Pitwall Cast and Google Cast parity, TLS, baked overlays, A/V |
| P2.2 | Playback/timing/Cast coordinator lifecycle and ownership |
| P2.3 | Release, signed update/rollback, exact-source acceptance |
| P2.4 | Authorization/security, Widevine, LAN mutual TLS, redaction |
| P2.5 | Android TV, low-RAM throughput, DPAD, focus, map lock |
| P2.6 | All benchmark/physical acceptance matrices |
| P2.7 | Previously accepted core architecture; preserve single decoder |
| P2.8 | Material 3 Expressive, phone/TV responsiveness, accessibility |
| P2.9 | Rolling/alternate programme CC, size/background opacity, A/V sync and Cast |

Both master plans contain historical coding checkpoints, not a blanket
physical pass. Audit actual remaining coding against the latest entries,
not outdated percentages. Further audio publication-copy reduction,
playback coordinator and timing coordinator extraction, rolling HLS captions,
alternate programme captions and CC text/background controls were part of
the original post-837 scope; preserve and check actual current implementation
and parity. Do not reimplement just because old entries say pending.

## 5. M1–M13 coding and evidence ledger

| ID | Implemented through 888 candidate / remaining evidence |
|---|---|
| M1 | Process/owner PSS/RSS/JVM/native/graphics, per-engine reservations/copies, fragment stats, Media3 parked owners, codec/scene IDs; actual allocation-site and external codec/GPU bytes unknown |
| M2 | Shared byte forecasts, foreground/speculative admission, actual OS headroom, reserved-vs-materialized accounting, native future commitment correction; physical overlapping peak pending |
| M3 | Native direct archive backing, clear one-feed pass-through transfer, shared immutable rolling/relay ownership; 887 owned output moves final Vec rather than copying entire media; physical adoption/fallback ratios pending |
| M4 | Lazy slots, free-only trimming and best-fit backing reuse; verify steady allocation churn |
| M5 | Pressure cooldown/hysteresis, safe seek/pause/stop trim and right-sizing; exercise low-RAM TV |
| M6 | Bounded immutable fragment arena, checked ranges, reader pinning, exact-size output/adoption, bounded reuse; verify bytes/leases/final release |
| M7 | Native header slab reuse, generation-fenced cached config, fixed protected descriptors; profile residual parser allocations rather than guessing |
| M8 | Foreground/speculative and baked queue byte reservations, pending/publishing and pinned limits; verify combined pressure |
| M9 | Explicit Media3 allocator registry and role accounting, trim unused blocks without lower buffering targets; three parked players had zero allocated buffer bytes |
| M10 | Pressure-aware cache retirement and pinned lifetime; repeated cycles, backward seeks, low-memory TV proof pending |
| M11 | Host allocation/byte parity/ownership/ASan, Android fixture and full CI evidence; physical regression and total PSS target not passed |
| M12 | Conditional allocator experiment only if heapprofd shows allocator retention/fragmentation after all feasible ownership changes; default system allocator unchanged |
| M13 | Graphics/decoder/BufferQueue attribution instrumentation; actual vendor ownership bytes and evidence-based optimization open; no speculative duplicate surface deletion |

## 6. Physical evidence from prior captures (do not confuse with 888 measurements)

Build 884 log `pitwall-20261126-015432-681.log.txt`, PID26741,
07:24–07:27 IST, app 1.0.1768. Two earlier Perfetto traces
`trace-taro-BP2A.250605.031.A3-2026-11-26-07-21-36.zip` and
`trace-taro-BP2A.250605.031.A3-2026-11-26-07-23-37.zip` PID21688, not
same process as log. Trace1 had 24 PitwallNativeAtlas slices; trace2 none
(not positive proof Kotlin). Neither native allocation stack capture.
Build 886 user log contains both earlier and latest session; latest
app 1.0.1772 PID30798 at 09:07 IST. Latest trace PID matches, ~97s,
25 native atlas executions, no heapprofd native allocation profiles.
Prior uploads may not be mounted in new chat: use recorded metrics, reopen
actual log/trace if available; never invent names or trace content.

Matched 19 unique segment numbers 795–813, clear HLS, 1080p + 3x480p,
50fps, 1x, tower/map ON, one hardware HEVC decoder (c2.qti.hevc.decoder),
atlas 1920x2176, scene 2316x1080. Native output same byte counts, not
proof of byte parity (host fixtures provide that separately).

| Metric | Earlier Kotlin | Earlier Rust (884) | Latest Rust (886) |
|---|---:|---:|---:|
| Median compose ms | 304 | 24 | 24 |
| Median producer CPU ms | 319 | 40 | 21 |
| Median JVM allocation MiB/segment | 60.0 | 17.8 | 15.8 |
| Median preparation ms | 1238 | 1173 | 776 |
| Segment GC count sum | 39 | 9 | 21 |
| Steady median total PSS MiB | 605.0 | 623.3 | 646.3 |
| Median RSS MiB | 486.8 | 499.6 | 521.5 |
| JVM used MiB | 185.8 | 171.0 | 158.9 |
| Native heap allocated MiB | 40.4 | 58.4 | 84.5 |
| Platform graphics MiB | 240.2 | 249.1 | 267.1 |
| Media3 in-use MiB | 58.8 | 58.9 | 60.1 |

Uncontrolled sequential/cross-build samples, different thermal/network
conditions. Earlier thermal status 3; latest steady 1, actual 0–2,
effective thermal 0 with override OFF. Do not claim memory target from
these figures. 20% target against earlier illustrative baseline is ~484
MiB, latest illustrative gap 162.3 MiB; actual criterion depends on a
fresh matched same-build baseline. Moving memory JVM -> native is not
a total memory reduction. PSS/RSS/graphics categories may overlap.
Build 886: 34 successful native-backed outputs; cumulative JVM output
copy 25.3 KiB vs 128.4 MiB across 20 earlier jobs. Cumulative bytes are
NOT resident memory. Native payload 15.34 MiB; arena capacity 25.75,
live payload 19.14; 6.61 MiB capacity difference. Future commitment
176.66 -> 60.45 MiB. Zero native submit rejections. Three parked players
zero Media3 bytes; don't remove them on unsupported leak hypothesis.
After stop, native and fragment and Media3 storage zero, PSS ~202 MiB.
No persistent media-buffer leak demonstrated in this short test.
Latest latch 49.90 fps, GL submitted 48.75 fps, decoder drops 1, no slow
swaps >18ms in selected steady windows. Actual scanout not proven.
Immediate nonblocking BufferQueue refusals are not decoder-drop counts.
Actual refresh 60/120Hz varies despite manual thermal override; OS remains
in control. Native allocation-site stacks / actual vendor graphics bytes
not recoverable from aggregate PSS or the supplied traces.

## 7. Latest consolidated implementation (887 + 888 correction)

Retain Architecture G, focus end-to-end ownership not further algorithm
optimization. All currently feasible six follow-up coding workstreams
staged/committed; new owned path still needs exact-source CI and physical
verification. Review actual code; avoid repeating completed work.

- `native/architecture-g/src/fragment_storage.rs`: best-fit reuse slack
  min(12.5%, 1 MiB), drop unsuitable idle backing, prefer recent freed
  buffer; 64 MiB total/12 live/2 free/8 MiB idle caps unchanged. Adopt
  exact output Vec with checked range and immutable exposed views.
  Simplified FIFO model of 34 observed sizes: 33 -> 16 allocations,
  peak backing 34.46 -> 27.86 MiB; not hardware measurements.
- `clear_atlas.rs`, `atlas_job.rs`, `engine.rs`: owned single fragment
  writer calculates final packet size and writes directly into exact-sized
  or bounded reused backing; exclusive generation-fenced lease moves Vec,
  no full media copy on successful adoption. Fixed multi-window and generic
  path preserved. Input/header/init and Media3 bounded reads remain.
  Fallback native-copy/heap path under admission refusal preserves quality.
- `jni_bridge.rs`, `NativeStitchingEngine.java`, `NativeClearArchiveJob.kt`,
  `NativeFragmentLease.kt`: explicit owned submit and adopt JNI symbols,
  reserve arena/next-job headroom before destructive transfer, exact
  range/len/capacity checks, lifetime survives engine close. Build 888
  fixes invalid argument return i32 -> i64 at JNI boundary.
- `HevcArchiveVodSession.kt`, `HevcClearCmafGridComposer.kt`,
  `F1HlsFeedCapture.kt`, `ArchiveCompositionStartup.kt`: prompt input
  release and one-feed ownership transfer, interruptible finite probe and
  cancelled handoff disposal before touching active source decoders.
- `SceneFrameDemand.kt`, `SceneFrameCadenceGate.kt`, both scene views:
  no further deferral of already coalesced pending picture; preserve
  oldest age through unavailable output. Immediate latch unchanged.
  `FeedPlayer.kt` logs codec init/release/format, input/output IDs and
  geometry. Do not infer actual decoder allocation from declared dimensions.
- `ProcessMemoryDiagnostic.kt`: nativeOwnedOutputAllocations,
  nativeOwnedOutputReuses, nativeAdoptedFragments, nativeAdoptedBytes,
  nativeEngineOwnedRunning and payload lower-bound flag. Check steady-state
  after job completion; peak pair estimate is not resident bytes.
- Tests: owned output byte parity and no media copy, pinned reader and
  engine close, 34-size regression, adoption/pointer/range/capacity, stale
  and invalid output, cancellation, pacing, diagnostics, ABI symbols.
  Review `ArchitectureGBAndroidTest.kt`, `gb_clear_grid.rs`, Rust module
  tests and Kotlin tests; do not weaken them if they fail.
- Both master plans contain complete latest ledger and original hard rule.

## 8. What to do immediately after Build 888 is green

1. Check exact signed APK artifact and symbols, user can install in-place
   (no uninstall / loss of login). First obtain unprofiled same-build
   Kotlin/Rust A/B memory logs, then separate dedicated native profile.
   Same replay/position/quality/layout/overlays/speed, fresh process,
   same manual thermal setting, similar temperature/refresh and network.
   Follow original measurement protocol below. Avoid a long shopping list
   of tests before evidence for the primary blocker is acquired.
2. Native allocation site / graphics attribution: user only has aShell or
   Bugjaeger. Download the exact green build's
   [capture-memory.sh](../perfetto/capture-memory.sh) to
   /sdcard/Download/capture-memory.sh (if browser uses .txt, rename).
   Read [ASHELL.md](../perfetto/ASHELL.md) and
   [pitwall-memory.cfg](../perfetto/pitwall-memory.cfg). Open app browse
   screen, enable diagnostic logs, select Rust. Capture BEFORE opening replay
   because native heap profiler needs allocations after start.
   Run:
   ```sh
   sh /sdcard/Download/capture-memory.sh start rust
   ```
   Return immediately to Pitwall and start replay; after 90s while still
   playing run:
   ```sh
   sh /sdcard/Download/capture-memory.sh collect rust
   ```
   Export in-app log + Download/pitwall-rust-memory.perfetto-trace,
   pitwall-rust-memory-ownership.txt, pitwall-rust-memory-producers.txt.
   Repeat fresh process for kotlin, substituting kotlin in both commands.
   Script has not yet been exercised on user's phone; if unsupported
   producer/permission/config error, collect exact error, no root/security
   bypass. Existing trace at path must be moved before repeating start.
   The script queries heapprofd and requests layer dump / GPU / FrameTimeline
   evidence, collects package memory and layer names. Availability varies.
3. Analyze profile with `scripts/check_pitwall_memory_trace.py TRACE --require-heap`,
   then verify process, sampled call stacks and exact ABI/build-ID symbols
   from architecture-g0-apk-symbols (check exact artifact name). A profile
   packet alone is not proof usable stacks. Use Perfetto trace processor and
   native heap flamegraphs, process stats, GPU counters and SF layer/frame
   data as available. If unavailable, explicitly state what is unmeasurable.
   Never commit real user logs, credentials, native heap dumps or secure data.
4. Compare physical native adoption and fallback frequency, copy counts,
   slot+arena capacity, actual process PSS and native heap by callsite.
   Quantify owners and allocation retention before changing allocator or
   codec/surface lifecycle. Look at source capture/HTTP, parser scratch,
   native worker, native arena, ART, Media3, codec, graphics separately.
   Identify measured bottleneck, then consolidate all feasible fixes.
   Keep default allocator unless evidence proves benefit and compatibility.
5. Investigate remaining frame coalescing and pacing with matching logs
   and FrameTimeline. Use existing clear TextureView vs SurfaceView A/B
   experiment only in supported clear path; keep default OFF. No protected
   capture. Compare actual display modes, cadence, physical layer
   presentation, decode drops, queue ownership, thermal and A/V, not just
   EGL submit/latch counts. Verify 50fps and accelerated paths where
   supported without forced display/codec settings.
6. Continue G-C remaining original gates: local and remote mixed Auto and
   compatible switch, protected/clear, rolling/finite, both Cast families,
   phone and TCL, manual quality, single/multiple decoder, seek/stop,
   pause/resume and full race. G-D release parity and upgrade still open.
   Report coding/CI/physical separately; do not mark G-C signed off until
   user accepts evidence. If PSS goal not feasible, quantify gap and seek
   explicit approval for any trade-off instead of silently weakening target.

## 9. Exact approved memory success criteria and original protocol


| Metric | Required target |
|---|---|
| Steady-state total process PSS | At least 20% below matched Kotlin baseline (Rust ≤ 0.80 × Kotlin) |
| Peak memory | Lower than matched Kotlin baseline |
| JVM allocation rate | Maintain demonstrated improvement; compare same workload and duration |
| Native allocation churn | Minimize after warm-up; measured stable reuse and bounded allocation |
| Stitching time | Preserve demonstrated Rust performance on matched segments; no material regression |
| Memory growth over full race | No unbounded growth; bounded caches and no leak across cycles |
| Memory recovery after stopping playback | Return close to idle baseline; measure recovery trend and remaining owners |
| Video quality | Identical to selected quality, no hidden reductions |
| Hardware decoding | Preserve single and multiple decoder modes |
| Android TV compatibility | Mandatory, especially low-memory TCL |
| Widevine and Cast | No regression across applicable supported scenarios |

The 20% target is now a user-approved engineering acceptance criterion, not a guaranteed outcome. RSS values from Build 879 cannot substitute for PSS. Do not calculate target using 501 MiB RSS or assert it is achieved from estimated slot savings. Quantify vague terms (e.g. recovery close to idle, churn, acceptable latency variability) using actual baseline and propose objective tolerance before final sign-off; do not silently add weaker substitute thresholds. If target is not feasible without unacceptable functionality/quality trade-offs, present measured gap and alternatives for user approval; do not alter scope or call it achieved.

### Measurement protocol (implementation detail proposed for reproducibility)

- Establish fresh Build 880 or current exact green candidate matched Kotlin/Rust baselines. First verify actual engine, requested qualities, actual rendition, codec count, identical scene/layout, same session and position, same speed, app version/build, refresh mode, software DRM option, network conditions and comparable temperature. Use same physical device per pair; not cross-device comparisons.
- Use cold/fresh process starts, equal warm-up and measurement windows, same segment interval; alternate Rust/Kotlin order and repeat at least 3 matched pairs when practical. Record idle before playback, startup peak, steady playback, seek/1.5× separate scenarios, stop and post-stop snapshots (e.g. 30/60/120 seconds), repeated session cycles and full-race endurance. If repeated measurements unavailable, identify sample limits.
- Minimum first repeat: four manual feeds 1080p + 3×480p, single decoder, 1×, tower/map ON, same replay and start, 60+ seconds steady per mode after warm-up. On TCL use usual Software DRM ON; do not force unsupported settings. For broader acceptance use clear and protected, finite and rolling, single and multi-feed, single/multi-decoder, overlays on/off, both Cast families, seeks, pause/resume, mode toggles, lifecycle, captions and audio. Check manual and all-Auto separately; do not compare Auto against manual. Additional TV or protected profiles only where legitimate entitlement supports playback.
- Capture Pitwall logs, Android System Trace (sched/frequency, app atrace, graphics/frame timeline, process stats, memory/thermal where supported), and PSS snapshots through dumpsys meminfo app.apexview_custom or equivalent app-side Debug.MemoryInfo. Confirm actual package and process IDs. Collect PSS at matched checkpoints; report median/peak sampled PSS and RSS separately with cadence, including child/codec/GPU attribution where observable and scope explicit. System-wide external graphics/codec memory is a separate corroborating metric; do not add PSS and RSS together.
- Capture heapprofd native allocation profile separately if allowed on device, using debug/profileable build and exact ABI symbols. Use Android-compatible profiler config; do not request root or unsupported shell permissions. Native heap profiling is an attribution tool, not the performance benchmark because profiling perturbs resource use. ART heap dump and graphics memory counters only when available, no sensitive data uploads/commits.
- Correlate actual segment byte counts, native wall vs CPU time including worker, GC allocation vs live heap, source/composed cache bytes, native reserved/allocated/used capacity, queue depth/bytes, Media3 allocator, decoder drop deltas, actual frame presentation and buffering. Verify no crash, thermal regression or playback quality change.
- User works from mobile: provide exact short steps in aShell/Bugjaeger or built-in diagnostics, exact files to export. Avoid asking for desktop-only tools. If instrumentation is inadequate implement a safe diagnostic capture first. Ask for tests only when a green candidate and a precise evidence need exist.


## 10. Complete repository reference inventory

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


## 11. Script usage map and verification workflow

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

## 12. Final instruction

Start from Build 888, not Build 880 or any historical checkpoint.
Read exact current source and the two master plans. First resolve CI if
required; otherwise request only the specific device evidence now needed.
Do all feasible approved coding work in consolidated passes. Keep G-C
and original release gates open until actual measured acceptance.
Do not say '100% completed' for physical work that cannot be established
from the available evidence. No new build just for this documentation.

