# Pitwall TV / ApexView Build 896 engineering handover

26 November 2026 · Architecture G Rust branch · Build 896 verified green

## 1. Prompt to paste into the new chat

> Continue the existing Pitwall TV / ApexView project in `cooldarshak/apexview` on `feature/architecture-g-rust-stitching`. Read this entire handover and the current master performance and Architecture G plans linked in section 3. Re-fetch the branch and verify the exact green build and any later commits. Build 896 is the verified starting APK.
>
> Start the next consolidated G-C memory batch in section 6. The first fix is bounded Rust reuse of real-size composed output buffers. Build 896 allocated 54 and reused zero. Forty-one fragment payloads exceeded the current 8 MiB idle pool limit. In the same batch, reconcile graphics and decoder ownership and remove proven redundant Media3 or Java retention. Preserve the warm replay startup improvement, single-decoder output, manual source quality, playback buffer safety, overlays, captions, commentary, Widevine, and both Cast families. Use the existing captures and code to begin. Ask for a new device capture only when a concrete claim requires a measurement the existing evidence cannot provide.
>
> Preserve both master ledgers, all applicable G.1–G.24, R1–R8, M1–M13, the six 892 follow-ups, and the original 16 release workstreams. Maintain Rust ownership of applicable numerical and media processing, with the existing Android platform boundaries. Complete every unconditional coding item in the authorized batch and its regression checks before triggering one normal signed APK build. CI-suppressed checkpoints are allowed. Correct failed gates on the working branch and verify the signed artifact from the exact commit. Keep main and the parent branch intact.
>
> Report code, CI, and physical evidence as distinct statuses. The release target is Rust steady process PSS at or below 80% of the matched same-build Kotlin reference, with a lower sampled peak and no sustained growth over a full race. Keep M12 allocator and M13 structural graphics decisions tied to evidence. G-C memory sign-off and G-D physical acceptance are open. Use the test protocol in section 8 when the implementation candidate is ready.

## 2. Verified branch and build checkpoint

| Item | Verified value |
| --- | --- |
| Working branch | [feature/architecture-g-rust-stitching](https://github.com/cooldarshak/apexview/tree/feature/architecture-g-rust-stitching) |
| Parent branch | `feature/pitwall-performance-audit-plan` |
| Exact Build 896 source | [75577ebf25ab006871d6d034ff3eb57a0795e2d8](https://github.com/cooldarshak/apexview/commit/75577ebf25ab006871d6d034ff3eb57a0795e2d8) |
| Build 896 | [Run 45818263209](https://github.com/cooldarshak/apexview/actions/runs/45818263209) |
| Build 896 APK | [Signed apexview-custom-debug-apk](https://github.com/cooldarshak/apexview/actions/runs/45818263209/artifacts/15116828186) |
| Build 896 symbols | [Architecture G native symbols](https://github.com/cooldarshak/apexview/actions/runs/45818263209/artifacts/15117201883) |
| Build 896 device identity | App `1.0.1792`, build metadata 26 November 12:19 UTC (17:49 IST), package `app.apexview_custom` |
| Exact Build 895 source | [d5fd4bc4fa5e69bd2fe9aa2de635e3d47ded66f1](https://github.com/cooldarshak/apexview/commit/d5fd4bc4fa5e69bd2fe9aa2de635e3d47ded66f1) |
| Build 895 | [Run 45804191998](https://github.com/cooldarshak/apexview/actions/runs/45804191998), app `1.0.1790`, [exact symbols](https://github.com/cooldarshak/apexview/actions/runs/45804191998/artifacts/15109770724) |

All five Build 896 jobs completed successfully. Build and tests job `139903512609`, API 31 `139910232973`, API 36 `139910233049`, API 29 `139910233165`, and signed publication `139911942309`. The native host suite had 78 passing tests. Local source checks also included an arm64 Rust/JNI compile check, focused replay and tower JVM tests with platform stand-ins, and both Cast JavaScript protocol suites. The exact-source Actions run is the Android build, instrumentation, and signing authority.

The Build 895 handover `5d98678` is followed by CI-suppressed implementation checkpoints `38f0c37` (clock-covered replay publication) and `18d60cd` (replay storage/graphics ownership, cold Rust probe, overlay changes). Build 896 includes both checkpoints and the final consolidated change at `75577eb`. This file is a documentation checkpoint. Use the exact build commit and artifact for APK identity or profiling. Verify GitHub branch and run state again at the start of the next chat.

Earlier context that still matters

- Build 892 was fully green, app `1.0.1784`. Build 893 hit the Media3 receiver track-selector type error. Build 894 had a stale expected instrumentation footer even though the G-B class passed all five tests. Build 895 corrected the footer and passed all required jobs. Preserve the regression that ties all nine mandatory runner footers to their JUnit counts. The counts at that checkpoint were 2/4/2/5/2/1/1/1/2. See the [Build 895 engineering handover](PITWALL_TV_BUILD_895_ENGINEERING_HANDOVER_2026-11-26.md).
- G.0 and G-B are user-signed-off. G-A foundation coding and CI verification are complete. G-C has extensive verified implementation, with the memory work in section 6 and applicable cross-route verification still open. G-D covers physical A/B, device, protected playback, Cast, endurance and release acceptance. Build 896 phone data covers one clear local four-feed replay setup. Keep the existing sign-offs and require actual evidence for additional closures.
- The independent exact-source memory and architecture review recommended in the 895 handover remains a review checkpoint when requested. The confirmed coding work can proceed from the present audit.

## 3. Existing plans and hard rules

Use the following as the source of truth. For any apparent conflict, apply the newest explicit user decision and the latest verified ledger entry. The next implementation updates both master ledgers in place.

1. [Master P0–P2 performance and release ledger](../PITWALL_PERFORMANCE_AUDIT_PLAN.md). The original 16 workstreams, prior 14-item batch, Batch 1 and Batch 2, physical gates and current consolidated implementation entries.
2. [Architecture G consolidated Rust engineering plan](PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md). G.0 and G-A through G-D, G.1–G.24, R1–R8, M1–M13, approved Rust ownership and the 896 route review at the end.
3. [Build 895 engineering handover](PITWALL_TV_BUILD_895_ENGINEERING_HANDOVER_2026-11-26.md). Eight follow-up items A–H, overlay dropout root cause, trace/benchmark details and working rules.
4. [Build 892 physical-test handover](PITWALL_TV_BUILD_892_PHYSICAL_TEST_ENGINEERING_HANDOVER_2026-11-26.md). Six follow-ups P0-A–P1-F, previous device evidence, the complete file and script inventory, and phone-only heap capture configuration.
5. [Build 888 engineering handover](PITWALL_TV_BUILD_888_ENGINEERING_HANDOVER_2026-11-26.md) and [approved Build 880 G-C memory plan](PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md). Baseline comparisons, research, acceptance criteria, and buffer ownership design.
6. [Build 867 continuation](PITWALL_TV_BUILD_867_ARCHITECTURE_G_CONTINUATION_2026-11-25.md), [Build 857 foundation](PITWALL_TV_BUILD_857_ARCHITECTURE_G_HANDOVER_2026-11-25.md), and [Build 839 engineering handover](PITWALL_TV_BUILD_839_ENGINEERING_HANDOVER.md). Historical decisions, evidence, and the original batches.

### Product and engineering constraints

- Continue the same Android phone and Android TV project, package and signing chain. Keep upgrades, saved preferences, persistent named layouts, single/split/grid/TV/free/snap layouts, portrait and landscape arrangements, D-pad focus, and Continue Watching deletion.
- Preserve legitimate F1 TV login, entitlement, hardware decoder and authorized Widevine operation, Android secure-output policy, and Pitwall Cast peer identity, TLS and pairing. Keep existing package/signing verification and applicable ABI/API coverage. Use the current open-source stack and authorized account data. Keep the project free of proprietary SDK and paid OpenF1 dependencies.
- Preserve one composed HEVC stream, one hardware video decoder and one visible output for single-decoder composition, plus one authorized Widevine session when applicable. Preserve single-feed passthrough and the existing multiple-decoder option. Keep the validated media geometry and selected quality. Select an exact offered and compatible manual rendition, hide unavailable choices generally, and report actual retained selection or explicit incompatibility. Preserve the Auto and thermal adaptation controls.
- Cover eligible clear and authorized protected media, replay and live, single and multiple feeds/decoders, local phone and TV, Google Cast and Pitwall Cast. Preserve baked continuous output with map, tower and selected commentary where supported, plus the supported receiver-rendered paths. Keep all overlay sizes, opacity, placement, lock/keep-clear, selectable driver dots and names, laps/sectors, and caption appearance and programme selection. Keep seek/speed, A/V sync and accessibility.
- Rust ON requests actual validated Rust for applicable stitching, app-owned audio processing, track/edge-map numerical work and timing tower numerical work. Rust OFF retains the equivalent Kotlin reference and is the default until separately approved. Report requested, actual and reason per subsystem and route. Use `NOT_APPLICABLE` for true passthrough or disabled work. Keep Media3, MediaCodec, Widevine, AudioTrack, authenticated networking, Android/Compose UI, graphics platform binding and lifecycle in their established owners. The browser receiver samples and renders delivered protocol data in its own runtime. Keep one clock and generation owner per path.
- Complete all authorized unconditional coding in one consolidated batch, with meaningful regression coverage and both ledger updates. Use intermediate `[skip ci]` checkpoints if helpful. Trigger signed CI only after that scope is complete. Preserve tests, required instrumentation, source/artifact matching, and the signed publication gate. Keep pre-existing device and release gates honest. Restrict code and documentation commits to the existing Rust feature branch.
- Use generalized fixes, including for Las Vegas. Use the existing F1 account-backed data paths and maintain the end-to-end authorization policy. Raw private logs, traces, media, account details and credentials belong in their existing private attachment scope. Commit reproducible summaries and safe instrumentation.

## 4. Physical evidence for Build 896

### 4.1 Identify the files by contents

| Evidence | Identity and use |
| --- | --- |
| Latest combined Pitwall log, displayed as `pitwall-20261126-111807-938.log(1).txt` | SHA-256 `4fa8b87816af03072aef779b088ec08c1cd688a142e830f2416a4d920dff14e2`. The exported name can omit `(1)`. It appends old Build 895 PID 21226 entries before Build 896 app `1.0.1792`, PID 17680. Select the latter for the new capture. |
| Latest heap capture, displayed as `pitwall-rust-895(1).perfetto-trace` | SHA-256 `da2a182b3c39b0d0644053849cb7a5612249e7c94a8089b483bb9a7c1c672c86`. Embedded arm64 ELF GNU build ID `8de967d1bffe27f74520c565ad7d224955b51aa9` matches **Build 896**, and the profile PID is 17680. The base filename also appears on the older trace. |
| Earlier combined log, `pitwall-20261126-074432-668.log(1).txt` | SHA-256 `50062c1401c94902c674bc02a8d1af95cd9cb1dd3d7e0ce745633f2bed6437ed`. Pick app `1.0.1790` PID 21226, trace ID `9386484`, for the completed Build 895 startup and steady baseline. See the 895 handover for the interrupted PID 20365 and version `1.0.1784` sections. |
| Earlier Build 895 heap capture, `pitwall-rust-895.perfetto-trace` | SHA-256 `6a436c5ab860d50368636bdd83d00aa30035d9669c500463746a2db129a255e9`. Embedded build ID `dae6f120f5d2dea86b0df1627d887b717d800ab1` matches the exact 895 symbols. Strict parsing fails. Recovered packets retain useful allocation groups with partially missing stacks. |

Retrieve existing attachments through authorized access if a fresh workspace lacks them. The summary and source audit are sufficient to start the confirmed fix. Select exact symbols and verify the ELF build ID before assigning stack names or lines. Record build, PID, archive/cache status, placement and route with every result.

### 4.2 Comparable scene and numbers

Physical phone was a Samsung Galaxy S22 Ultra SM-S908E, Android 16/API 36 in this capture, at adaptive 120 Hz. The replay was Las Vegas race, content `1000010442`, Rust ON, single hardware Qualcomm HEVC decoder, 1×, TextureView, four feeds (1080p main + three 540p onboard), timing tower and track map in the existing TV-layout setup. Coded atlas was 1920×2176 and scene output 2316×1080. One of the 540p onboard feeds differed between builds. The companion TV target is TCL 55C655, Android TV 12. Its usual Software DRM setting is ON. Preserve that setup for its acceptance run.

For the comparison, use the shared replay-position interval `[4,610,000, 4,690,000)` ms (1:16:50 through 1:18:10). Interpolate positions from adjacent `F1_COMPOSITION_PLAYBACK` records in the same continuous 1× active scene, and align `GC_PROCESS_MEMORY` uptime to the corresponding PID's log timestamps. Keep startup, seek, renderer restart, quality changes, stop and idle outside the steady window. Eight deduplicated memory samples per build. Build 895 samples at about 16:45:50.871–16:47:00.864 IST, Build 896 about 18:08:39.362–18:09:49.361 IST.

| Metric | Build 895 | Build 896 | Interpretation |
| --- | ---: | ---: | --- |
| Process PSS, median | 652.0 MiB | 637.0 MiB | Down about 15.0 MiB (2.3%) across the two Rust captures |
| Process PSS, highest sample in window | 678.5 MiB | 654.1 MiB | Down 24.4 MiB in this window |
| Process RSS, median | 533.8 MiB | 540.9 MiB | Up about 7.1 MiB, keep it distinct from PSS |
| Reported platform graphics, median | 247.1 MiB | 252.1 MiB | Up about 5.0 MiB, largest reported bucket |
| Platform Java heap PSS, median | 189.1 MiB | 182.7 MiB | Down about 6.4 MiB |
| Platform native heap PSS, median | 73.5 MiB | 76.4 MiB | Up about 2.9 MiB |
| Native fragment arena capacity, median | 24.9 MiB | 24.3 MiB | Comparable held capacity |
| Native engine materialized payload, median | 7.81 MiB | 7.87 MiB | Comparable input workspace |
| Active Media3 allocator, median | 76.8 MiB | 77.5 MiB | Three parked players report zero allocator bytes on both builds |
| Segment composition, median | 42 ms | 42 ms | Steady Rust composition timing held |
| Full segment preparation, median | 907 ms | 883 ms | Small change in observed delivery timing with fetch variability |
| Slow app-window frames | 2112 / 6949 (30.4%) | 2095 / 6938 (30.2%) | Similar window jank |
| Decoder drops | 0 | 0 | Decoder diagnostic during the selected active windows |
| Nonblocking queue warnings in interval | 63 | 60 | One scene SurfaceTexture queue per window and zero blocking or unknown timeouts |
| GL latches / submissions / coalesced arrivals | 4002 / 3993 / 9 | 4004 / 4000 / 4 | App-side counters. Use platform FrameTimeline for scanout. |

The longer stable Build 896 second scene had 18 memory samples, a 645.5 MiB median and a 676.0 MiB sampled peak. Keep that peak visible alongside the shorter comparison. PSS was higher late in that run while the main arena, workspace and Media3 owners remained bounded. Native playback and Media3 counters went to zero and the scene owner detached after final stop. The app settled near 191 MiB PSS. This is observed recovery after one stop. Full-race and repeated lifecycle acceptance remain open. Count PSS, RSS, allocator bytes, reserved bytes, sample-weighted native heap, and platform graphics separately because views of the same memory can overlap.

### 4.3 Overlay startup and first composed frame

| Check | Build 895 | Build 896 |
| --- | --- | --- |
| First usable tower and map | About 1.09 s, 22 rows and 22 cars | 1.144 s and 1.348 s in the two recorded starts |
| Five-second checkpoint | Zero ranked rows and 22 cars after a map-only publication | 22 ranked rows and 22 cars in both starts, with stable-readiness markers |
| Core timing cache | Miss in the reproduced regression | Hit on both starts, with 362-point final outlines |
| Timing archive complete | 24.90 s in the 895 miss | 3.79 s and 2.32 s with cache hits |
| First composed GL output relative to timing load | 14.80 s | 10.74 s and 10.14 s |

In 895, an incomplete map-only snapshot overwrote timing rows around 3.73 s, and standings returned around 6.99 s. 896 logs show continuous readiness after first availability in the two warm starts. Cache state differs, so treat archive completion and first frame changes as observations of those sessions. The logs measure sampled readiness and GL submission. Cold/core-miss and interrupted build-up, seek outside the preview window, actual visual continuity, and TV/Cast still need controlled coverage.

Latest timing trace IDs are `14351340` and `14381936`. The saved previews were about 0.47 MiB on disk and initially had no usable positions or ranks at the provisional clock in these starts. The warm core supplied data when the playhead resolved. Completed timing publications reported one owner and 12,199,324 unique primitive bytes (~11.6 MiB). Java heap also includes other owners and object overhead. The 896 local cold composition probe logged actual `RUST` for both starts and produced native-backed output. Prepared times of 4.37 s and 5.04 s include the verified media/manifest preparation. Keep the initial same-input Kotlin differential proof in the equivalent path and measure its transient work separately.

### 4.4 Native heap attribution and remaining unknowns

- The 896 trace is complete at the recorded packet level. 120 seconds, 64 KiB native heap sampling, 12 process snapshots, 133 trace packets and 24 profile packets, PID 17680, no reported buffer overrun or corruption. The latest sampled live native peak was 50.04 MiB and the final profile snapshot 50.02 MiB. The exact symbols resolve about 7.87 MiB to the input workspace at `jni_bridge::submit_atlas_parts` (line 293 in the 896 sources). A large ~25 MiB group has an unresolved stack, with a similar magnitude in arena counters. Treat its owner as unverified from the trace.
- The 895 original trace failed strict framing and recovery skipped about 144.8 kB. Recoverable data peaked at 60.21 MiB of sampled live native allocations. The exact ELF attributes roughly 25.79 MiB to native fragment backing and 7.81 MiB to input workspace at that peak. Missing/incomplete stacks and different capture windows limit comparisons. The lower recovered peak for 896 merits follow-up. Process PSS and platform native heap PSS are separate measurements.
- The 896 logs record 54 native output allocations, 54 adopted fragments and zero output reuses. The fragments range from 7.64 to 9.39 MiB, with an 8.18 MiB median and 41 payloads above 8 MiB. Existing `MAX_FREE_BYTES` rejects backing larger than 8 MiB from the idle pool even when it would fit the next compatible output. Capacity includes framing and spare capacity. Eligibility for the smaller fragments also depends on a final reader release, budget, fit and worker timing.
- Graphics remained about 252 MiB with one counted scene owner, one EGL context/window and one decoder texture during the matched 896 window. The trace captured heap data only. Device-wide shared buffers, codec vendor allocations, compositor imports, physical display cadence and their attribution require additional device evidence. Heap allocation totals describe bytes allocated and freed during profiling. Evaluate resident memory separately. See [Perfetto native heap profiling](https://perfetto.dev/docs/data-sources/native-heap-profiler) and [Perfetto GPU tracing](https://perfetto.dev/docs/data-sources/gpu).
- Both comparison runs here used Rust. The historical 888 pair measured about 588.29 MiB Kotlin and 588.38 MiB Rust, from profiled runs with shared-process and thermal confounds. That pair demonstrated faster stitching and lower JVM allocation, and it provides historical context only. Establish the acceptance denominator on the same signed build and same exact workload.

## 5. Build 896 implementation to preserve

| 895 work item | Incorporated in 896 and remaining evidence |
| --- | --- |
| A. Stable overlay publication | `ReplayStartupPublication` picks clock-covered map and tower histories independently. Position-only updates preserve valid rows and the first usable geometry. Corrected anchor, coverage gaps, seeks and authoritative empty state have regressions. Warm phone checkpoints passed as observed. |
| B. Replay cache and upgrade | `ReplayCoreCache` reports hit, miss, version, corruption and outside-window reasons, and keeps bounded atomic writes. V3 preserves stopped state. Rebuild V2 whose records lack that bit. Keep cache identity and cancellation fences. |
| C. Graphics lifetime and attribution | Scene input failure and teardown are generation-fenced. The clear replay/baked encoder reuses texture storage, shader locations and codec metadata, and close is idempotent. Physical buffer ownership still needs the M13 work below. |
| D. Java archive history | Compact oversized completed position arrays, share immutable backing, report unique primitive bytes, spool the primary topic to bounded temporary storage and stream the ordered merge. Preserve precedence and sorting for disordered input, optional mini-sectors, and cancellation. |
| E. Initial composition probe | Reuse the same-input JVM result for the cold native proof. Publish a validated native lease when applicable. Keep independent byte/metadata parity, hardware admission, route/preference fencing, and explicit fallback. Two starts logged actual native cold output. |
| F. Overlay redraw and sampler ownership | Preserve tower state/cache across unrelated map updates locally and on the Cast sender. Keep static maps and row regions retained. Omit optional map hit-target sampling only when neither active control uses it. Maintain draw cadence and speed/telemetry updates. |
| G. BufferQueue and cadence | Analyzer filters by PID and half-open time interval. Existing GL and decoder counters help identify stage ownership. The phone sample still has similar slow-window share. Keep physical frame timeline and 120 Hz/50 fps acceptance open. |
| H. G.21–G.24 route audit | Branch ledger contains source-owner mapping across clear/protected, local, Cast sender/receiver and baked output. Retain the map across new fixes and prove actual eligible route coverage in CI and on hardware. |

## 6. Next consolidated memory batch

The first actionable code issue is near-miss output reuse. In parallel within the same engineering pass, attribute the largest remaining footprint and retire redundant owners where evidence supports it. Implement the unconditional changes and measurable diagnostics using the existing capture. Device-dependent changes to the renderer or allocator remain tied to the controlled evidence below.

### 6.1 Rust output buffer reuse and device headroom

**Maps to M2, M4–M8, M10–M11 and G.2/G.6/G.7/G.10/G.12/G.13/G.17.** Start in [fragment_storage.rs](../../native/architecture-g/src/fragment_storage.rs), [engine.rs](../../native/architecture-g/src/engine.rs), [jni_bridge.rs](../../native/architecture-g/src/jni_bridge.rs), and the attached-memory boundary in [NativeFragmentLease.kt](../../app/src/main/java/app/apexview/tiled/NativeFragmentLease.kt).

1. Trace the full ownership cycle for a finite clear Rust output. The worker takes or allocates a Vec, `Arena::adopt` takes checked ownership, publication borrows an immutable view, and the final reader releases the backing. Measure how many releases happen before the next eligible compose request, and classify misses by oversize, fit, headroom, pressure, stale owner/generation, or active readers. Include live, free and total charged capacity, payload and backing sizes, warm allocation/reuse counters, budget denial, and retirement reasons. Keep diagnostics sampled or aggregated.
2. Reuse an eligible released backing for the next compatible job. Start with one bounded idle backing per active composition where sharing and total budgets permit it. Size admission against requested output, actual capacity, measured device headroom, native worker and shared process commitments, and current pressure. Keep an excess-capacity bound for close-size reuse. Use the existing allocator, adoption, and native lease flow. A fixed increase to 16 MiB or to every owner would omit risk from other simultaneous owners.
3. Account for bytes exactly once across worker, live lease, idle backing, local delivery/cache and Cast/relay. Preserve limit checks, lazy allocation and direct ownership transfers. Give foreground composition/seek priority over idle storage and optional prefetch. Use existing device headroom and speculative admission policy. Avoid reserving future output as though the backing were already resident.
4. Retain last-reader and pressure-watermark checks, with correct release on seek, route or generation retirement, fallback, cancellation and stop. Keep published views immutable, preserve existing wipe/security semantics, and preserve correct handling of still-pinned or advertised Cast media. Maintain the conservative low-RAM policy when headroom is limited. Trace allocation recycling versus memory released to the OS as separate effects.
5. Add representative Rust allocation regressions using the 7.64–9.39 MiB fragment range, fluctuating sizes, oversize and unsuitable backing, active/pinned readers, low-RAM and high pressure, concurrent seek/close and many cycles. Verify byte-exact payload, no stale reuse, bounded idle/live/worker capacity, and materially more reuse after warm-up while avoiding a higher peak PSS. Extend the existing `fragment_storage`, engine and Android integration fixtures. A reuse win is measured by allocation rate, latency and PSS together.

### 6.2 Graphics, codec and BufferQueue ownership

**Maps to P0.3, P1.1–P1.2, M1/M13 and G.18/G.24.** Follow [Viewer.kt](../../app/src/main/java/app/apexview/Viewer.kt), [SingleDecoderSceneView.kt](../../app/src/main/java/app/apexview/scene/SingleDecoderSceneView.kt), [ProtectedDecoderSceneView.kt](../../app/src/main/java/app/apexview/scene/ProtectedDecoderSceneView.kt), [SceneGraphicsOwners.kt](../../app/src/main/java/app/apexview/scene/SceneGraphicsOwners.kt), [ReplayClipEncoder.kt](../../app/src/main/java/app/apexview/scene/ReplayClipEncoder.kt), and existing EGL, video buffer, FrameMetrics and timing diagnostics.

1. Instrument lifetime and overlap for codec output, atlas, EGL window, app and compositor surfaces, textures, retained overlay layers and Cast capture resources. Follow created, attached, resized, replaced, detached, released and vendor-reported ownership, keyed to playback and surface generation. Correlate release times with process PSS, permitted GPU/codec/SurfaceFlinger counters, latch timing and actual presentation. Preserve unknown vendor bytes as unknown. Ensure profiling signals retain their original definitions.
2. Compare the existing experimental clear-video SurfaceView route with TextureView in separate otherwise-identical runs. Include startup, steady playback, pause/resume, repeated seeks, switching, stop/restart and overlay off/map-only/tower-only/both. Keep same atlas and output quality, renderer eligibility and DRM policy. Match 1× and test 1.5× separately. Include S22 Ultra 120 Hz and actual TCL supported refresh, plus TV Software DRM ON for compatibility. Use device-available system graphics and frame-timeline captures separately from heapprofd. See [Android graphics buffer-cache behavior](https://source.android.com/docs/core/graphics/reduce-consumption).
3. Remove or retire only identified duplicates, stale generation surfaces, or resources whose release can be made earlier without affecting active readers. Scale proven improvements across supported paths and retain the opt-in renderer experiment until measured parity. Retain protected surfaces and MediaCodec lifetimes. Vendor pool redesign, a new renderer or a default renderer change needs measured ownership, presentation and compatibility evidence.
4. Evaluate real buffer pressure via queue identity, originating PID, and event phase. Compare submit/latch gaps and physical layers alongside Media3 decoder drops, producer slack, 120 Hz UI work and thermal state. A queue warning count measures warning events. Keep the actual 50/75 fps and user-selected refresh and thermal policies supported. Test recovery through decoder/renderer transitions without a second hidden decoder or output buffer queue.

### 6.3 Media3 handoff and Java retention

**Maps to P0.1–P0.2, P0-D, P2.2, M1–M3, M6, M8–M10 and G.8–G.10/G.13/G.14.** Review [HevcArchiveVodSession.kt](../../app/src/main/java/app/apexview/tiled/HevcArchiveVodSession.kt), [ArchiveSegmentDelivery.kt](../../app/src/main/java/app/apexview/tiled/ArchiveSegmentDelivery.kt), [ArchiveBufferPolicy.kt](../../app/src/main/java/app/apexview/tiled/ArchiveBufferPolicy.kt), [FeedPlayer.kt](../../app/src/main/java/app/apexview/FeedPlayer.kt), [Media3MemoryOwners.kt](../../app/src/main/java/app/apexview/Media3MemoryOwners.kt), [MediaMemoryAccounting.kt](../../app/src/main/java/app/apexview/MediaMemoryAccounting.kt), [ProcessMemoryDiagnostic.kt](../../app/src/main/java/app/apexview/ProcessMemoryDiagnostic.kt), and the existing clear/protected rolling and Cast publishers.

1. Follow actual source reads through Kotlin/network ownership, JNI, immutable composed lease, Media3 DataSource/extractor/loading, active sample buffers and Cast publication. Identify when a heap/native copy is made and when a safe terminal release is possible. Distinguish the active Media3 playback window (about 77.5 MiB in this run) from the native cache and parked allocators. Measure renderer/player/sample, audio and other remaining owners separately.
2. Extend the existing `completeLocalDelivery` and retired-reader evidence only where appropriate. Preserve retry data, latest in-order reads, unread prefetch, active readers, optional HTTP ranges, reader cancellation, shared relay ownership and seek epochs. Keep stable Media3 LoadControl safety and source quality. Use native leases through existing resource publication when the entry point supports them.
3. Use an authorized debug/profileable managed heap snapshot for dominant retained Java objects around the same steady window and after a seek/restart/stop. Inspect timing core/preview and render histories, player lifecycle, network work, artwork/caches and optional child workers based on actual retained roots. Reuse or release demonstrably overlapping objects and retain the versioned timing cache and correct stopped state. Use cancellation and generation ownership to keep after-stop state bounded. The tracked three parked players had zero allocator bytes in 896. Prioritize the active owner while verifying any suspected residual references with evidence.
4. Keep low-allocation Kotlin reference behavior, shared rendering improvements and measured genuine Rust hot paths. Audit startup reference/copy peak separately from steady work. Retain required native-versus-reference media proof and apply low-copy improvements across valid clear/protected and local/remote routes at the same semantic boundary. Record remaining measured gaps instead of removing validation or shrinking appropriate buffers.

### 6.4 Consolidation and exit conditions for the batch

- Use source and existing capture evidence to begin each unconditional change. Keep existing Rust arenas, memory pressure handling, renderer and coordinator abstractions. Make code changes where ownership or churn is demonstrated. Measure where the current capture ends at the Android platform boundary.
- Validate every touched path across applicable phone/TV, clear/protected, single/multiple decoder and Google/Pitwall Cast routes. Test retirement, retry, prefetch, malformed data and overlapping epochs. Retain timestamp, rendered-pixel checks and protected metadata comparisons through the existing tests.
- Update the two authoritative ledgers with measurements, actual source/test coverage, known constraints and pending physical evidence. Run one consolidated signed CI build after completing the coding scope and checks. Continue through any recoverable CI failure on the same branch. M12 production allocator changes require controlled fragmentation evidence. M13 hardware or rendering redesign requires measured avoidable ownership and parity.

## 7. Full inherited scope and cross-reference

### 7.1 Architecture G workstreams and refinements

| IDs | Scope to preserve and reconcile |
| --- | --- |
| G.0, G-A, G-B | User-signed-off feasibility and G-B migration, plus verified G-A foundation. Preserve ABI, JNI and reference parity gates. |
| G.1–G.5 | G.1 reference profiling, G.2 bounded native arenas, G.3 HEVC parse/stitch, G.4 CMAF assembly, G.5 authorized DRM compatibility. |
| G.6–G.10 | G.6 leases and lifecycles, G.7 deadline queues, G.8 JNI/direct buffers, G.9 network-to-native copies, G.10 low-copy Media3 publication. |
| G.11–G.15 | G.11 SIMD/NEON conditioned on measured hotspots, G.12 reusable hot paths, G.13 seek/cancel and generations, G.14 feed switching and prefetch priority, G.15 fuzz/sanitizer/leak checks. |
| G.16–G.20 | G.16 differential and independent decode, G.17 low-RAM device budgeting, G.18 end-to-end profiling, G.19 local/Cast coverage and actual owner attribution, G.20 signed ABI/API/upgrade compatibility. |
| G.21 | App-owned audio parsing, timestamp/framing, continuity, bounded queues and low-copy publication. Preserve native numerical work for supported audio and platform handling of decode/output, session, and DRM. |
| G.22 | Track/edge-map geometry, indexes, interpolation, time coverage, flags and driver state. Keep controls, Rust actual-owner reporting, and independent local/receiver motion clocks. |
| G.23 | Tower normalization, ordering, per-row and field changes, laps, sectors, interval, flags, headers, gear and status. Preserve fresh playhead-aligned data, D-pad navigation and receiver parity. |
| G.24 | Shared retained rendering, static/dynamic work separation, versioning, bounded draw buffers, baked capture and receiver integration. Keep the existing platform API boundaries. |
| G-C and G-D | Complete evidence-led optimization and applicable integration, then matched physical acceptance and release gates. |

R1 fused processing, R2 deadline-aware scheduling, R3 device-adaptive arenas, R4 measured bounded worker concurrency, R5 batched JNI, R6 reusable validated stream analysis, R7 layout-aware Auto quality and R8 end-to-end attribution remain part of the same plan. Treat algorithm, worker count and platform choices as measurable refinements.

### 7.2 All thirteen memory requirements

| ID | Requirement and current focus |
| --- | --- |
| M1 | Attribute native allocation sites and process memory with exact-build symbols. Extend now to platform graphics and matched retained Java evidence. |
| M2 | Account for device headroom and byte budgets across native, JVM, Media3, pending groups and Cast. Charge capacity once and preserve foreground priority. |
| M3 | Remove overlapping fragment and history copies through verified ownership transfers. Follow the active Media3 and relay path in this batch. |
| M4 | Allocate materialized native inputs/outputs lazily and preserve bounded spare slots. Measure reuse, peak capacity and warm rematerialization. |
| M5 | Keep adaptive trimming and final-reader retirement safe. Limit warm retained capacity by actual demand and pressure. |
| M6 | Share compact immutable backing, publish checked views and retire when the last owner completes. |
| M7 | Reuse parser/workspace and metadata with generation fencing and scoped proof. Move only measured duplicate work. |
| M8 | Coordinate byte-based prefetch and pending/publication queues, including remote readers and provider timings. Keep deadline safety. |
| M9 | Attribute active and parked Media3 bytes and trim genuinely free storage without reducing load-control safety margins. |
| M10 | Preserve pressure-aware retirement, retry and seek safety, replay cache versioning and restart continuity. |
| M11 | Preserve exact-source allocation, copy, lifecycle, quality and rendering regressions. Add representative 896 size/churn fixtures. |
| M12 | Keep the allocator comparison conditional on reproducible fragmentation or retention evidence and device parity. |
| M13 | Attribute decoder and graphics memory, remove proven avoidable ownership and gate structural changes on controlled hardware measurements. |

### 7.3 Build 892 follow-ups

- **P0-A, per-feed quality.** Hide every unavailable rung, preserve exact offered manual selections and actual source reporting, generation-safe changes in composed/local/baked/receiver routes, and actual render acknowledgement. The Build 896 sample requested and delivered 1080p plus three 540p feeds. Broader instrumented/device parity remains open.
- **P0-B, driver status.** Preserve chronology and sparse topic precedence, stopped/retired distinction and valid recovery for cases such as Lawson. Require evidence of resumed activity. Preserve full tower and mini-sector behavior.
- **P0-C, graphics ownership.** Preserve EGL and codec-surface fencing, validated geometry, exact detach/release and diagnostic owner counts. Continue section 6.2 for physical byte attribution.
- **P0-D, native pressure and JVM footprint.** Preserve old-reader retirement watermark, right-sized input, native leases and parked-player allocator telemetry. Continue sections 6.1 and 6.3.
- **P1-E, timing retention and stability.** Preserve interned sector data, Cast timing fingerprints, V3 stopped state, independent readiness, and static/dynamic retained layers. Cover warm, cold, interrupted and upgrade timing in verification.
- **P1-F, driver names.** Preserve verified driver number/TLA/session mapping to display names without changing stable internal feed identity.

### 7.4 Original P0–P2 release ledger

| Workstream | Scope kept in the master ledger |
| --- | --- |
| P0.1 | Segment scheduling, foreground priority, seek latency and exact seek behavior |
| P0.2 | HEVC throughput, memory ownership and segment delivery |
| P0.3 | Rendering, codec/SurfaceTexture/BufferQueue, GPU and presentation |
| P0.4 | Stable track map, edge map and timing tower |
| P1.1 | HEVC atlas geometry, single decoder and mixed-resolution compatibility |
| P1.2 | 50/75 fps cadence, refresh matching and actual presentation |
| P1.3 | Manual/Auto quality, device defaults, and thermal policy |
| P2.1 | Google Cast and Pitwall Cast, direct/composed, baked overlays and commentary |
| P2.2 | Playback and timing coordinators, cancellation, audio publication and lifecycle |
| P2.3 | Signed builds, update/install, upgrade and release |
| P2.4 | F1 login, entitlement, Widevine and TLS authorization |
| P2.5 | TV layout, focus, D-pad and actual TCL compatibility |
| P2.6 | Fixtures, instrumentation, benchmarks and full-race/endurance evidence |
| P2.7 | Existing accepted core architecture and single-decoder invariant |
| P2.8 | Material 3 Expressive UI, layouts, controls and accessibility |
| P2.9 | Rolling HLS and alternate-programme captions, size and background opacity, full route parity |

The original 14-item follow-up covered TLS, baked HLS buffering, GPU capture, local suppression/audio, TV composition throughput, Architecture G feasibility, BufferQueue, cadence, TV map lock, main-plus-strip layout, driver status, gear telemetry, device defaults and verification. All remain covered by the ledger and its acceptance criteria. Batch 1 covers Media3 1.11.1, reversible clear SurfaceView and presentation visibility. Batch 2 covers the physical regressions and hardening around BufferQueue, adaptive 120 Hz, power/thermal/memory, seek, lifecycle and DRM. Treat historical matrix percentages and earlier coding checkpoints as dated. The latest exact source and real acceptance evidence control the current status. Keep the separately requested caption, coordinator and audio publication work in the original ledger.

## 8. Acceptance, reproduction and mobile testing

### 8.1 Performance gates

1. **RAM.** Fresh processes on the same signed build, Rust ON/OFF with the actual engine confirmed. Keep exact feed IDs, selected/actual resolutions, playhead, layout, renderer, audio, overlays, playback speed, display refresh, Software DRM, network and thermal conditions identical per pair. Alternate order and repeat at least three paired windows when practical. Reuse the current common four-feed 1080p + three 540p scene as the first fixed test where these rungs are actually offered.
2. **Memory metric.** Sample idle, startup/seek peak, at least 60 seconds of settled playback, stop and 30/60/120-second recovery and repeated session cycles. Track median/peak sampled process PSS, RSS and owner bytes separately. Run steady PSS acceptance without heap profiler overhead, and collect separate heap and system traces for causality. Target Rust median steady PSS ≤ 0.80 × the matched Kotlin run, lower sampled peak, stable reuse, recovery near idle and no sustained increase across at least one full race (two hours or more). Record any unmatched variable. Keep estimated savings out of acceptance totals.
3. **Performance and parity.** Preserve the Architecture G target of at least 30% stitching CPU reduction versus the matched reference and the overlay target of at least 90% reduction in measured avoidable map/tower allocations versus the matched pre-optimization baseline. Define the allocation sites and sample windows. Compare p50/p95/p99 timings, GC, full producer slack, buffering, GL/decoder output and physical presentation. Keep selected video/audio/caption fidelity, 50/75 fps behavior, same seek semantics and zero new visible overlay regressions.
4. **Feature matrix.** Confirm cold/warm/cache-miss/upgrade startup and no valid-row loss by five seconds when suitable data covers the playhead, exact playhead seeks in and out of a preview, independent map/tower on/off, programme and caption selection and appearance, playback speed, and stop/restart. Check clear/authorized protected, finite/rolling, single feed/multifeed, single/multiple decoders, local phone/TV and both Cast families across supported routes. Test D-pad and saved layout behavior and TCL Software DRM ON. Preserve phone/TV endurance and release/upgrade tests. Label coding completion, Actions result, physical test and user sign-off separately.

### 8.2 Existing scripts and capture paths

| Need | Repository entry point |
| --- | --- |
| App-side runtime counters by version | [analyze_pitwall_runtime.py](../../scripts/analyze_pitwall_runtime.py), pass `--build 1.0.1792` for 896 diagnostic exports and select a homogeneous session before comparing |
| Queue ownership in one window | [analyze_pitwall_bufferqueues.py](../../scripts/analyze_pitwall_bufferqueues.py), use `--pid`, `--from` inclusive and `--until` exclusive with logcat `MM-DD HH:MM:SS.mmm` times |
| Actual frame and overlay comparisons | [analyze_pitwall_frames.py](../../scripts/analyze_pitwall_frames.py), [analyze_pitwall_overlay_ab.py](../../scripts/analyze_pitwall_overlay_ab.py), [frame_timeline_export.sql](../perfetto/frame_timeline_export.sql) |
| Heap capture preflight | [check_pitwall_memory_trace.py](../../scripts/check_pitwall_memory_trace.py), pass `--require-heap`, then check continuity, PID, sampling, clocks and matching arm64 ELF |
| Mobile aShell/Bugjaeger | [ASHELL.md](../perfetto/ASHELL.md), [capture-memory.sh](../perfetto/capture-memory.sh), [pitwall-memory.cfg](../perfetto/pitwall-memory.cfg), [pitwall-50fps.cfg](../perfetto/pitwall-50fps.cfg) |
| Device matrix, release and artifact gates | [pitwall_acceptance_matrix.py](../../scripts/pitwall_acceptance_matrix.py), [validate_pitwall_acceptance.py](../../scripts/validate_pitwall_acceptance.py), [verify_architecture_g0_apk.sh](../../scripts/verify_architecture_g0_apk.sh) |
| Exact CI, instrumentation and fixture suites | [Android debug APK workflow](../../.github/workflows/android-debug-apk.yml), [run_rendernode_instrumentation.sh](../../scripts/run_rendernode_instrumentation.sh), [Build 892 script and reference inventory](PITWALL_TV_BUILD_892_PHYSICAL_TEST_ENGINEERING_HANDOVER_2026-11-26.md#91-complete-repository-reference-inventory) |

Where local toolchains are available, run the existing static and applicable native/Android/fixture checks on the exact working tree. The workflow defines the current Android SDK/NDK, Gradle, Rust and instrumented gates.

~~~bash
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/pitwall_change_contract.py
bash -n scripts/run_rendernode_instrumentation.sh
node tools/test_cast_receiver.mjs
node tools/test_cast_layout_receiver.mjs
node --check web/cast/receiver.mjs
cargo test --locked --manifest-path native/architecture-g/Cargo.toml
~~~

Use the existing phone-only heap v4 config when a new capture becomes necessary. It profiles `app.apexview_custom` with 120 seconds, 64 KiB sampling and 10-second dumps. Its installed config is under `/data/misc/perfetto-configs/`. The full setup is in the [Build 892 handover](PITWALL_TV_BUILD_892_PHYSICAL_TEST_ENGINEERING_HANDOVER_2026-11-26.md). For example, to reproduce the 896 heap capture on that signed build using a fresh output path and available shell access

~~~bash
perfetto --background-wait --txt -c /data/misc/perfetto-configs/pitwall-heap-only-v4.cfg -o /data/misc/perfetto-traces/pitwall-build-896-rust-on.perfetto-trace
~~~

Start the agreed playback immediately after starting the capture. After the 120-second capture ends

~~~bash
cp /data/misc/perfetto-traces/pitwall-build-896-rust-on.perfetto-trace /sdcard/Download/
~~~

For later builds choose their real build numbers and distinct ON/OFF filenames. Export the in-app Pitwall log and record the exact feed identities, playhead and settings. Use a separate system trace or available BufferQueue/GPU/SurfaceFlinger evidence when the graphics question requires it. Keep user instructions phone-sized and give commands only when the specific test is ready.

## 9. Research and implementation references

The [approved Build 880 research and success criteria](PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md#5-research-synthesis-what-other-rust-teams-learned) contain primary links and context for Cloudflare compact cache storage, Datadog shared Rust workspace, Zerodha Blobasaur bounded payload lifecycles, Discord GC and cache trade-offs, Agentgateway queued payload sizing, and ChainSafe allocator attribution. Apply the techniques to this app's measured owners and existing Rust implementation. Keep external workload results separate from Pitwall forecasts.

- Buffer negotiation, bounded downstream ownership, and safe reuse. [GStreamer buffer pools](https://gstreamer.freedesktop.org/documentation/additional/design/bufferpool.html) · [Tokio Bytes](https://github.com/tokio-rs/bytes).
- Existing player allocation and device capture tools. [AndroidX Media3](https://github.com/androidx/media) · [Perfetto Android memory analysis](https://perfetto.dev/docs/case-studies/memory) · [Rust allocation regression approach](https://nnethercote.github.io/perf-book/heap-allocations.html).
- Codebase parity, geometry and protected-path context. [Clear/Widevine and named layouts](../CLEAR_WIDEVINE_PARITY_AND_NAMED_LAYOUTS.md) · [Single-decoder layouts and Cast](../SINGLE_DECODER_LAYOUTS_AND_CAST.md) · [Local TV receiver](../LOCAL_TV_RECEIVER.md) · [50 fps device profile](../PITWALL_50FPS_DEVICE_PROFILE.md).
- HEVC and CMAF context. [Stitching reference review](../HEVC_STITCHING_REFERENCE_REVIEW.md) · [Clear CMAF packaging](../HEVC_CLEAR_CMAF_PACKAGING.md) · [CMAF runtime inspector](../HEVC_CMAF_RUNTIME_INSPECTOR.md) · [Renderer and lag diagnosis](../BUILD_643_LAG_AND_SURFACE_RENDERING_DIAGNOSIS.md).

## 10. Completion report for the next implementation

Report the exact branch/commit, linked run and signed APK, what completed in the consolidated coding batch, and the checks that actually ran. Summarize buffer reuse, source and delivery ownership, and measured memory without counting retained capacities twice. Identify which device acceptance evidence is still needed and why. Keep the 20% target, full-race evidence and supported route parity as explicit gates. When the candidate is green, give the user one clear, matched phone test first and the device-specific TV/Cast steps when those results are needed.
