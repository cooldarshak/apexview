# PITWALL TV / APEXVIEW — BUILD 880 — APPROVED ARCHITECTURE G-C MEMORY OPTIMIZATION EXECUTION HANDOVER

Date: 25 November 2026
Status: USER APPROVED M1–M13 AND SUCCESS CRITERIA; START IMPLEMENTATION IN NEXT CHAT.
Repository: https://github.com/cooldarshak/apexview
Branch: feature/architecture-g-rust-stitching
Verified engineering baseline: Build 880, commit 4b97ef8c77ee8df047e978f39181ed6795ea0572

## 1. Copy-paste instruction for the new chat

Read this entire handover, then open and read the existing consolidated Architecture G engineering plan and performance audit plan on the specified branch. Verify the actual branch head and exact-source GitHub Actions status before changing anything.

Continue development of the EXISTING Pitwall TV / ApexView application on cooldarshak/apexview, feature/architecture-g-rust-stitching. Build 880 is verified green at the time of this handover. G.0 and G-A are complete, G-B has explicit user sign-off, and G-C is still open. The user has explicitly approved the complete M1–M13 memory optimization scope and the acceptance criteria below. Start executing the plan, not merely proposing or researching it again.

Audit existing implementations against every requirement, reconcile them into the existing engineering plans, and implement all remaining feasible coding deliverables in consolidated, dependency-aware batches. Begin with end-to-end memory ownership/attribution, removal of duplicated storage and shared byte-based resource budgets. Do not duplicate already working systems. Preserve exact existing behavior and regression coverage. Separate coding completion, automated CI verification and actual physical acceptance. Continue until all feasible coding is implemented or a specific measurable evidence gate requires device input; explain blockers precisely and ask for only the necessary tests. Do not claim G-C signed off just because CI is green.

The user is a Lead Business Analyst with no coding background, develops using ChatGPT mobile and GitHub/Composio, and tests on physical Android phone and TV. Give short, clear summaries in plain English and precise test instructions. Do not assume a desktop computer is available.

## 2. Authoritative repository state and existing plans

- Build 880: https://github.com/cooldarshak/apexview/actions/runs/45651321774
- Exact source: https://github.com/cooldarshak/apexview/commit/4b97ef8c77ee8df047e978f39181ed6795ea0572
- Exact-source mandatory jobs verified successful: build and host 139336286363; API36 139342738770; API31 139342738882; API29 139342738927; signed APK publication 139344730867.
- Build 879: https://github.com/cooldarshak/apexview/actions/runs/45642411324 ; source 32278a7f97410a5c1a0b33988510bfb9a06562d5; all five mandatory jobs green. This is the physical profiling baseline, NOT Build 880.
- Existing master plans:
  - docs/handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md
  - docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md
- The master plans contain historical checkpoint headings that may lag the latest appended evidence. Read the whole relevant history and reconcile current status rather than trusting an old headline. This handover is an approved extension to those plans, not a competing architecture or replacement for prior requirements.
- No functional source changes are made by the creation of this handover. A documentation-only commit may or may not trigger CI depending on workflow path filters. Do not label that commit green merely because its parent is green; verify current branch state.

Prior milestones: G.0 feasibility and G-A foundation signed off/complete; G-B user signed off after real four-feed native operation. Build 875 integrated compact JNI transfer, native clear and protected Cast producers, shared native fragment ownership, and resource-aware scheduling. Build 876 optimized clear descriptors and layout-aware common Auto preparation. Build 877 failed Clippy after 52 host tests passed; Build 878 passed host tests, Clippy and ASan but failed Android-only ATrace FFI placement. Build 879 corrected the trace boundary, preserving crate-wide deny(unsafe_code) except the existing audited Android bridge. Build 880 added lazy spare compact slot allocation and passed all gates. Do not repeat those fixes or loosen the safety rules.

## 3. Product scope and non-negotiable constraints

Core product: Android phone and Android TV F1 multiview, one composed HEVC stream, one video decoder and one output surface in single-decoder mode; legitimate F1 TV authentication and entitlement; original Widevine/CDM/MediaCodec handling preserved. Kotlin/Java remain appropriate for Android UI, lifecycle, network APIs and Media3 integration. Rust owns supported compute-intensive stitching and buffer work when selected, not a rewrite of all Android platform code. No deceptive claim of native execution: retain explicit requested/actual/fallback diagnostics. Unsupported or rejected profiles must fail or use the already-authorized validated fallback as specified by the existing plan, never silently downgrade quality.

Apply resource improvements across local and remote scenarios wherever applicable: clear HEVC and protected Widevine HEVC, single and multiple video decoder modes, single and multiple feeds, finite/archive and rolling/live, HLS and DASH, Google Cast and Pitwall Cast (including existing baked-scene and other supported paths), with or without tower/map and captions. Preserve audio commentary, subtitle controls, layouts, manual per-feed quality, DPAD and TV usability, seek, speed, handoffs, lifecycle, saved layouts and entitlement. For multiple-decoder playback do not force stitching where it is not needed. For protected data only authorized metadata/clear-prefix operations and existing validated encrypted sample paths; no DRM bypass, decryption or extraction. Preserve Software DRM setting behavior on TCL.

Devices: Samsung Galaxy S22 Ultra SM-S908E (Snapdragon SM8450), current test Android 16 API36, 120 Hz adaptive display. TCL 55C655 Android TV 12, low-memory target, Software DRM ON required in previous physical tests. Do not hardcode device model memory policy; use actual OS headroom, process conditions and device capabilities. Keep compatibility with API29/31/36 and supported Android ABIs armv7, arm64, x86, x86_64.

Hard engineering rules:
- No project restart, separate fork, main/parent branch changes, proprietary SDKs or paid OpenF1. Do not introduce new third-party dependencies unnecessarily. Check repository license and upstream code license before copying anything; use architectural ideas unless reuse is specifically compatible and attributed.
- Consolidate work into coherent changes with tests and documentation. Avoid serial micro-commits and speculative builds. Resolve CI failures at source; no lint/test/ASan bypass, no fake success, no changing tests merely to make broken behavior pass.
- Preserve input/output bitstream and decoded-picture parity, source resolution and manual quality. Do not save memory by silently shrinking image quality, removing overlays, shortening necessary playback buffer below safety requirements, changing playback speed, or falling back to multiple decoders.
- No unbounded queues/caches, active lease eviction, unsafe concurrent ownership or freeing in-use buffers. No new raw JNI pointer lifetimes. Existing unsafe-code exemption remains confined to audited platform bridge; safe Rust engine and parser remain safe.
- Do not add speculative parallel workers, NEON, new allocator, new framework, GPU pipeline, or automatic rendition transitions simply because they sound faster. Conditional work requires evidence and documented disposition. No unnecessary work on the UI unrelated to the measured issue.
- Existing G-C deliverables (Auto mixed quality/receiver geometry/active rendition changes, protection/Cast validation, target-device profiling) remain open where not proven. G-D physical full-feature/endurance acceptance remains distinct. Do not confuse source wiring tests with physical playback.

## 4. Physical evidence and why the new scope exists

Raw evidence names from the Build 879 physical tests (not committed to the repository; retrieve from the original conversation/attachments if available; ask for re-upload only if genuinely necessary):
- pitwall-20261125-155421-484.log.txt
- trace-taro-BP2A.250605.031.A3-2026-11-25-21-26-23.zip (Rust)
- trace-taro-BP2A.250605.031.A3-2026-11-25-21-29-24.zip (Kotlin)
The preceding analysis is also recorded in both master plans under “Build 879 green: physical G-C profiling and memory correction”. Treat these recorded measurements as historical evidence, not newly reproduced results. No raw trace/log contents, URLs containing credentials, entitlement tokens, media or proprietary data should be committed.

Test: app 1.0.1758, Las Vegas race replay, content 1000010442, HLS clear CMAF, one hardware HEVC video decoder, four feeds (1080p + 3 × 480p), composed coded atlas 1920×2176, source 50 fps, 1×, TV layout, timing tower and track map. Rust ON first, then Kotlin. Matched 17 media segments 800–816; identical input bytes 96,128,426 and output bytes 113,108,559 across paths. Explicit actual Rust execution confirmed, not merely requested setting. Initial preparation can report JVM candidate or authorized fallback; use steady actual path evidence.

| Metric | Kotlin/JVM | Rust | Interpretation |
|---|---:|---:|---|
| Median composition wall time | 295 ms | 25 ms | 11.8× faster / 91.5% less time |
| Median producer thread CPU | 275 ms | 41 ms | 85.1% lower on producer; excludes Rust worker CPU |
| Median process-wide JVM allocated bytes during segment | 65,237,536 (~62.2 MiB) | 18,678,304 (~17.8 MiB) | 71.4% less allocation churn; NOT live heap or PSS |
| Median full segment processing | 926 ms | 759 ms | ~18% shorter |
| GC event counts summed across matched intervals | 34 | 19 | ~44.1% fewer, not necessarily exclusive to stitcher |
| Native operation outcomes | N/A | 49 successful, 0 rejected | actual recorded native operations |
| Late process RSS snapshots | 512,904 KiB (~501 MiB) | 598,900 KiB (~585 MiB) | ~84 MiB more RSS in Rust run |
| Dropped decoder frames across comparable but non-identical intervals | 84 additional | 0 | encouraging, NOT a controlled physical presentation result |

Trace markers: PitwallNativeAtlas (25 measured spans, median 9.76 ms, max 28.22 ms), PitwallNativePictures (8.69 ms median), PitwallNativeParse (0.053 ms), PitwallNativeMdat (0.867 ms). Fragment cadence 5,760 ms. These are wall durations; nested medians do not necessarily sum. Native worker CPU must be included in aggregate CPU claims. Stitching is not the bottleneck demonstrated on the phone; ARM NEON or extra workers are not justified by this evidence. Repeat on low-end TV before closing conditional gates.

Caveats: two tests occurred sequentially, not controlled cold-start A/B, with different thermal status, 60/120 Hz changes, network/prefetch state and playback history. RSS is not PSS and cannot isolate cause; codec surfaces, GPU resources and other processes are not necessarily included. Decoder counters do not prove every frame displayed. No claim of lower total memory yet. Do not subtract theoretical allocation savings from RSS as a measured result.

### Build 880 correction (already implemented and verified in CI)

Before fix, compact arena reserved 35,890,878 bytes (~34.2 MiB), input capacity 7,780,054 bytes, output capacity 9,870,473 bytes. Two payload pairs reserved for safe overlapping ownership, yet bridge serializes most production jobs. Build 880 allocates the first pair, prefers already allocated free slots, lazily materializes the spare only when necessary; still charges both logical slots to conservative admission. Generic non-compact conversion engines remain eager. Backpressure, generation fencing, read leases, stale result retirement, workspace ceiling and full quality remain unchanged. Estimated avoidance of 17,650,527 bytes (~16.8 MiB) of eager allocation for observed capacities, not yet physically measured. Retain this change and extend it only where evidence warrants. New tests cover lazy sequential reuse, overlap, foreground reserve, backpressure, seek and close. All five Build 880 CI jobs green.

## 5. Research synthesis: what other Rust teams learned

The user specifically asked to research production companies, Zerodha, open-source projects and GitHub references. The following evidence and application recommendations were approved. External results are for their own workloads, not guaranteed Pitwall savings. Prefer studying algorithms, lifecycle patterns and tests over wholesale framework integration. Verify exact source code, current license and compatibility before dependency reuse.

### Production engineering cases

1. Cloudflare — DNS cache memory optimization (very high relevance). Production p99 RSS 9.3 GB → 5.3 GB, 43% reduction; benchmarked per-entry footprint 953 → 420 bytes, 56% reduction. Important approaches: avoid wasted collection capacity, consolidate allocation and compact representations, reuse temporary workspace, and preserve encoded representations when possible. Pitwall application: right-sized immutable fragment storage, compact sample/NAL descriptors, reduce repeated parsing, eliminate duplicate representations, prevent unusually large fragment capacity from becoming permanent. Source: https://blog.cloudflare.com/dns-cache-memory-optimization-1111/
2. Datadog — Monocle Rust time-series engine (very high relevance). Shared radix-tree aggregation storage reduced aggregation-specific memory by orders of magnitude and increased aggregation throughput approximately 70%. Not a whole-app RAM result. Pitwall application: shared bounded scratch storage, avoid per-worker/per-feed duplicate temporary buffers, lazy capacity based on actual work, no speculative extra workers. Source: https://www.datadoghq.com/blog/engineering/rust-timeseries-engine/
3. Zerodha — Blobasaur (medium relevance). Published 19 November 2026; Rust blob storage over SQLite avoids GC pressure from handling/compressing large buffers; reported production command latency around 2 ms p99. No directly comparable before/after RAM reduction reported. Lessons: bounded background processing, lifecycle of large payloads and predictable resource use. Do not integrate its database/storage engine or assume its AGPLv3 code can be copied; verify license. Blog: https://zerodha.tech/blog/high-performance-distributed-blob-storage/ ; repo: https://github.com/iamd3vil/blobasaur
4. Discord — Go to Rust read-state service (high relevance). GC-related periodic latency spikes were problematic; cache reduction introduced trade-offs; migration removed GC latency source. Pitwall application: keep the demonstrated Rust advantage and reduce retained memory without trading away smoothness or necessary caching. Source: https://discord.com/blog/why-discord-is-switching-from-go-to-rust
5. Agentgateway — hidden cost of MPSC queues (very high relevance). Tokio channels may allocate blocks of 32 elements, even with nominal capacity 1, so inline large payloads cause unexpectedly large initial allocation. Workload-specific change reported ~50% memory reduction in a representative test, not universal. Audit actual queue implementations and sizes; carry handles rather than large inline payloads; budget bytes, not just count. Source: https://blog.howardjohn.info/posts/mpsc-cost/ ; repo: https://github.com/agentgateway/agentgateway
6. ChainSafe Forest — memory analysis in Rust (additional reference supporting M1/M12). Distinguish live heap, allocator retention and RSS; compare allocators only with controlled measurements. Source: https://blog.chainsafe.io/memory-analysis-in-rust-2/ ; allocator trade-off discussion: https://github.com/ChainSafe/forest/issues/7393 ; repo: https://github.com/ChainSafe/forest

### Open-source design and diagnostic references

| Project | Prior relevance assessment | Relevant features and limits | Primary links |
|---|---|---|---|
| GStreamer + Rust plugins | 10/10 | Reusable buffer pool, min/max allocation negotiation, downstream release and lifecycle deactivation, fragmented MP4/CMAF plugins. Closest media ownership design reference, not a recommendation to replace Media3. | https://gstreamer.freedesktop.org/documentation/additional/design/bufferpool.html ; https://github.com/GStreamer/gst-plugins-rs |
| Tokio Bytes | 10/10 | Shared immutable slices and cheap ownership transfer; BytesMut.try_reclaim for eligible storage. Small views can retain a large backing allocation; measure retained capacity, not view length. Adopt principles in existing pool, avoid unnecessary crate if existing owner suffices. | https://github.com/tokio-rs/bytes ; https://docs.rs/bytes/latest/bytes/struct.BytesMut.html |
| Google Perfetto / heapprofd | 10/10 | Native allocation stack attribution and memory/process tracing; symbolization and profileability required; separate allocator profile from performance runs to avoid overhead confounds. | https://github.com/google/perfetto ; https://perfetto.dev/docs/case-studies/memory ; https://perfetto.dev/docs/data-sources/native-heap-profiler |
| Parallax | 9/10 design reference | Shared ownership, arenas, lightweight handles and allocation regression testing. Linux-targeted design, not proven Android/Widevine integration; verify architecture docs and maturity. | https://github.com/p13marc/parallax ; https://github.com/p13marc/parallax/blob/master/docs/design.md |
| AndroidX Media3 | 9/10 | Existing player allocator, load-control limits, buffer trimming and lifecycle; coordinate with native budget. Not a Rust library. Keep currently deployed Media3 1.11.1 unless separate evidence warrants change. | https://github.com/androidx/media ; https://developer.android.com/reference/androidx/media3/exoplayer/upstream/DefaultAllocator ; https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl |
| DHAT-rs / Rust Performance Book | 9/10 | Allocation/peak heap regression testing on representative host workloads; profiling build only, not production global allocator change. | https://github.com/nnethercote/dhat-rs ; https://nnethercote.github.io/perf-book/heap-allocations.html |
| Lumina Video | 8/10 | Rust media SDK with platform hardware decoding, Android ExoPlayer and GPU transfer approach. Study avoiding unnecessary copies and surface ownership only. No proof of protected Widevine compatibility, equivalent TV hardware or Pitwall memory savings. | https://github.com/lumina-video/lumina-video |
| AWS Firecracker | 7/10 | Resource ceilings as product acceptance; VMM overhead target around 5 MiB for VMM threads, not a transferable media budget or measured Pitwall baseline. | https://github.com/firecracker-microvm/firecracker ; https://github.com/firecracker-microvm/firecracker/blob/main/SPECIFICATION.md |

Relevance scores are qualitative engineering judgments, not external rankings. Primary references should be GStreamer, Cloudflare, Datadog, Tokio Bytes and Perfetto, supported by Media3, DHAT and the other examples. Research claims should be revisited at source when making concrete implementation decisions. The handover does not imply that every upstream path/API has been independently checked at a pinned revision.

### Allocator recommendation

Retain Android system allocator first. Android uses Scudo in many configurations since Android 11 and may use jemalloc on low-memory devices; do not assume glibc server results transfer. See https://source.android.com/docs/security/test/scudo . Test jemalloc or mimalloc only after attribution proves allocator retention/fragmentation is materially limiting and controlled experiments establish an improvement across Android ABIs, low-memory TV, speed, peak RSS/PSS, lifecycle and battery. Reference https://github.com/jemalloc/jemalloc/blob/dev/TUNING.md and https://github.com/microsoft/mimalloc . Improve existing media arena before considering global changes. Avoid completely custom global allocator. A released Rust Vec is not proof resident pages returned to OS. Never use forced GC or allocator purge to manufacture acceptance numbers.

## 6. Approved target architecture

Authorized F1 CDN HLS/DASH → existing authenticated acquisition → bounded source fragment ownership/pool → Rust stitching/metadata computation with reusable workspace → immutable output fragment leases → existing Media3 / authorized Cast publication → Widevine CDM where applicable → hardware MediaCodec → existing output surfaces and overlays.

Parallel paths: existing multiple-decoder playback and single-feed passthrough remain appropriate; same resource policy coordinates actual memory owners, not forced use of the stitcher for no-op cases. Kotlin/Java may remain at API boundaries. Do not move encrypted payload handling outside authorization. No additional network hop or proprietary server.

Central principle: one coordinated memory policy, not necessarily one literal shared allocation or single global mutable lock. Charge physical backing storage once per owner, distinguish views/leases, expose conservative admission reservations separately from committed/allocated bytes, and coordinate shared pools, prefetch, source cache, composition cache, relay/Cast, Media3 and rendering. Preserve foreground work, playback continuity, cancellation and independently enforced memory/DRM checks. Use owner identifiers, generation fencing, bounded capacity, safe retirement, pressure hysteresis and release on lifecycle changes. Make units and snapshot scope explicit. Where Android APIs require a copy, document the boundary, measured cost and why it cannot safely be eliminated.

Three highest-value opportunities: (1) prevent unnecessary simultaneous copies across network/Java/Rust/cache/publication; (2) prevent indefinite retention of peak-sized buffers using safe growth/shrink policies; (3) coordinate total memory, especially the TCL TV, rather than optimize isolated heaps.

## 7. Approved M1–M13 plan: mandatory coding scope and conditional evidence gates

All IDs are approved by user. Initial state below is a starting audit hypothesis; verify exact call sites and tests at current branch. M12 is explicitly conditional, not mandatory allocator replacement. M13 requires proof before changing rendering architecture. For every ID, maintain status for coding, automated verification, phone verification, TV verification, applicable mode coverage, blockers, commit/build/evidence. Record actual measurements and unresolved gaps. Keep this mapping in both existing engineering plans; do not invent a parallel tracker as sole source of truth.

### M1 — Native heap profiling and memory attribution (P0; Google, ChainSafe)
Deliver debug/profileable-only native heap capture instructions, symbol artifact mapping per ABI and build SHA, and low-overhead periodic ownership diagnostics. Record process RSS/PSS, Java/native heap allocated/committed, backing capacity, logical used bytes, reservations, cache residency, active/retired/free leases, allocation count/bytes, codec/graphics/dmabuf when observable, OS available/threshold, thermal and refresh rate. Correlate to playback mode, session/generation, actual engine, segment, time and lifecycle. Separate JVM, Rust, libc allocator, Media3 and graphics; avoid double counting RSS/PSS and logical accounting. Capture native allocation stacks via heapprofd where supported; use fallback dumpsys meminfo / in-app counters if access limited; do not represent unavailable GPU attribution as zero. Test accounting invariants and diagnostic lifecycle; no sensitive content or per-NAL log flood.

### M2 — Unified memory budgeting across native, JVM and media caches (P0; GStreamer)
Audit current NativeStitchingDeviceBudget, NativeStitchingEngine, protected storage, producer scheduling, source/composed caches, relay and Media3. One policy must coordinate reservations and realized capacity under live OS and JVM headroom, avoid simultaneous independent overcommit, maintain foreground headroom, safe minimum playback buffer and maximum permitted budgets. Charge shared backing once, separate reserved worst-case from allocated current and pinned bytes, account concurrent handoff/resize and transient copies. Use checked arithmetic, low-RAM policy without model hardcode, periodic pressure update/hysteresis, deterministic failure and backpressure. Do not reduce quality to fit manual requests. Tests for exact-boundary, overflow, low memory, multiple owners, concurrent admission, rollback and close/seek.

### M3 — Eliminate overlapping fragment copies (P0; Tokio Bytes)
Inventory acquisition → network reader → Kotlin data → JNI → native work → output → cache → Media3/relay/Cast including clear/protected and finite/rolling. Build 875 already removed concatenated Java packets and separate JNI bridge buffers; Build 877 unified bounded DASH/HLS reads and direct output copies. Preserve those optimizations. Remove unnecessary duplicate whole-payload copies, prefer immutable handle/slice leases with exact lifetime; integrate existing retained protected fragment storage where safe. Audit range reads, audio/caption publication, relay handoffs and native-to-Media3 API requirements. No dangling raw views or active lease invalidation. Add byte-copy metrics and differential byte/timestamp tests; verify cancellation, slow reader, seek and DRM metadata parity.

### M4 — Lazy native buffer allocation (P0; Datadog, Agentgateway)
Build 880 already implemented lazy spare compact slot, conservative two-slot admission retained. Audit remaining eager per-feed/per-worker buffers, unused spare allocations, queue payload storage and protected/Cast paths. Materialize on actual need, reuse warmed buffers before allocating, return bounded backpressure on allocation failure; no relaxation of logical capacity and workspace ceiling. Add allocation counts/allocated capacity (distinct from reserved budget) and regression tests demonstrating idle/sequential/overlap behavior. Do not repeat Build 880 or count estimated 16.8 MiB as a physical result.

### M5 — Adaptive buffer capacity and idle trimming (P0; GStreamer)
Use observed high-water/rolling workload and low-memory signals; grow within validated bounds, shrink or retire only safe free backing at stable boundaries with cooldown/hysteresis. Avoid permanent peak capacity, resize churn, simultaneous old/new allocation beyond budget, and trimming live leases. Handle seek/generation changes, reconfiguration, pause, app background, stop, engine switch and Cast teardown. Apply equivalent ownership rules to header slab, parser scratch, fragment pools, relay buffers. Record requested vs actual release; OS RSS recovery is separately measured. Test alternating large/small segments, pressure, overlap, stale leases, restart and output parity.

### M6 — Compact immutable fragment storage (P1; Cloudflare)
Keep validated composed fragments in appropriately sized immutable storage with shareable ownership and checked offsets. Measure payload length vs capacity, metadata overhead, small slices pinning large parent, and cache duplication. Reuse current protected native fragment leases rather than introduce incompatible second store. Preserve legal data access and sample/DRM metadata, exact init/media bytes, multi-consumer reads and thread safety. Compare metadata footprint and allocation counts with baseline. Tests for sharing, bounded ranges, release after last reader, cache eviction and stale generation.

### M7 — Reusable parser/stitching workspace (P1; Cloudflare)
Preserve existing flat descriptor slab, reusable per-feed vectors and bounded native header slab. Attribute remaining per-slice/reference parsing and per-job descriptors; reuse bounded workspace where profiling supports, compact structures without unsafe packed field tricks, reset per-generation/per-picture state and avoid repeated parses of immutable validated init. Keep strict HEVC/CMAF bounds, reference picture checks, filter seams, timing, sample count and all prefix widths. Test differential valid/malformed inputs, memory growth/reuse, generation isolation, Rust/Kotlin bitstream parity and independent decoded fixture comparison.

### M8 — Byte-based prefetch and queue limits (P1; GStreamer)
Audit existing deadline/speed/thermal scheduling, queues, source cache, rolling windows, Cast publisher and audio staging. Add total bytes-in-flight limits (including pending and pinned bytes), not only segment/job count; enforce shared budget under overlapping jobs. Prioritize foreground and playback safety; speculative work yields under pressure, cancellation releases reservation, no starvation or hidden quality change. Preserve necessary segment boundaries and live latency. Test wide fragment sizes, playback 1×/1.5×, seek, slow network, stalled receiver, pressure and retirement.

### M9 — Coordinate Media3 allocation with Rust (P1; AndroidX)
Inspect actual DefaultAllocator and LoadControl use by all player paths, not assumptions from upstream defaults. Track allocator capacity/usage, audio/video/text allocations, outstanding source reads and multiple player overhead. Coordinate safe byte/time thresholds and trimming with M2/M8 without reducing required playback reserve or causing rebuffering, audio underruns or decoder starvation. Respect Media3 threading/API contracts; no removal of Android platform requirements. Preserve single/multiple decoder, protected/clear, casts and lifecycle. Test startup/seek/stop, playback under pressure, repeated handoffs and no player/allocator leak.

### M10 — Pressure-aware cache eviction (P1; GStreamer, Discord)
Use byte-based occupancy, actual ownership and recency/deadline priority; separate pinned/active from evictable entries, coordinate caches with shared budgets, trim idle cold storage before essential playback. No eviction of current leased fragment, current decoder dependencies, required key/DRM metadata, or seek-critical minimum. Avoid GC-based or quality-based shortcuts. On stop/close release scene-specific caches and owners according to lifecycle; preserve user saved layouts/preferences. Test pressure, stale reads, active prefetch, seeks, Cast handoff, low-RAM policy and recovery.

### M11 — Allocation regression tests in GitHub Actions (P1; DHAT, Parallax)
Add reproducible host allocation counters/peak live bytes and representative clear/protected metadata fixtures; warm-up then steady loop, sequential reuse, exceptional sizes, seek/close, lazy overlap and retained capacity. Use isolated profiling configuration if DHAT or equivalent selected; avoid production allocator dependency unless justified. Assert meaningful upper bounds on allocation count/live bytes/copy count and no growth after warmup; avoid flaky wall-time assertions. Keep existing cargo test, strict Clippy, ASan, fixture parity/decode checks, production wiring, JVM tests, Android instrumentation and publication. Include CI diagnostics artifacts keyed by SHA, ABI, device/run and exact workload. Do not replace real phone/TV acceptance with host benchmark.

### M12 — Memory allocator comparison (CONDITIONAL; ChainSafe)
Only execute controlled allocator experiment if M1 identifies significant allocator-retained memory or fragmentation after M2–M11. First distinguish actual live bytes from allocated capacity and mapped/resident pages. Compare default Android system allocator with compatible jemalloc/mimalloc build variants if warranted, respecting toolchain/ABI and licensing. Include long-run PSS/RSS, peak, CPU, thermal, startup, release and compatibility; retain default unless demonstrably better with no regression. If unnecessary, document evidence and mark conditional item not applicable, not falsely “coded” or “skipped”.

### M13 — GPU and decoder buffer optimization (P1; Lumina, Android)
Attribute codec/graphics/SurfaceTexture/GL/BufferQueue resource cost and frame pacing. Previous BufferQueue backpressure and 120 Hz adaptive issues are relevant; use measured trace evidence, hardware codec counts and actual surface behavior. Avoid CPU frame copy, duplicate output surfaces/texture pools and redundant decoder ownership only where proven; preserve protected output, compositing/overlay rendering, Cast and current device fallback. Use standard Android APIs; do not swap to Lumina or introduce unsafe graphics/DRM paths. Test surface lifecycle, rotation, DPAD, layout changes, cast, protected playback, refresh states, dropped decoder frames and presented frame timing; measure total resource changes without promising physical 50 fps from decoder counts alone.

## 8. User-approved success criteria (must not silently weaken)

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

## 9. Execution strategy and code starting points

Start with verified head and inspect actual code; names below are confirmed from previous work or supplied as search targets, not an exhaustive architecture inventory. Avoid assuming unchanged line numbers.

- native/architecture-g/src/engine.rs, atlas_job.rs, clear_atlas.rs, clear_slice.rs, clear_cmaf.rs, hevc_slice.rs, fragment_storage.rs, protected_plan.rs, protected_header.rs, jni_bridge.rs and lib.rs.
- app/src/main/java/app/apexview/tiled/NativeStitchingEngine.java, NativeStitchingDeviceBudget.kt, NativeClearArchiveBridge.kt, NativeClearArchiveJob.kt, CmafHttpReader.kt, F1DashFeedCapture.kt, F1ArchiveFeedCapture.kt; locate HLS acquisition, existing clear/protected producer, native retained fragment store and relay ownership by search.
- app/src/main/java/app/apexview/AppState.kt and player/FeedPlayer.kt (locate actual path), existing playback coordinator and timing coordinator, scene views, NativeComposedCastSession.kt, Cast sender/receiver, existing cache/load-control/prefetch/thermal policy.
- scripts/test_pitwall_production_wiring.py, scripts/pitwall_change_contract.py, native Cargo tests and fixture/parity tests, Kotlin unit tests, Android instrumentation, .github/workflows/android-debug-apk.yml.

Proposed consolidated delivery order, not a requirement for exactly four commits:
A. Reconcile plans and implement M1/M2 observability and shared admission foundation with existing owners. Audit M3–M10 and M13 across modes and inventory allocation/copy ownership. Include regression fixtures/counters (M11) from first functional batch.
B. Implement measured/structural copy, lazy allocation, size-class/reuse, trimming and compact storage/workspace improvements M3–M7, integrating M2. Preserve bounded ownership and all bitstream correctness. Include M8 where necessary to avoid overcommit.
C. Finish unified prefetch/cache/Media3 coordination M8–M10 and remaining M11. Apply across clear/protected, local/Cast, multiple-decoder where appropriate. Address M13 only with evidence; include any diagnostics and proven lifecycle improvements. Do not stop merely after one easy optimization.
D. Verify physical matched comparisons and full target-device coverage. Use M12 experiment only if warranted; consider NEON/worker concurrency only if low-end target profile proves a bottleneck and benefit after memory goals. Close conditional items with explicit rationale. G-C sign-off only after user-approved acceptance; G-D remains distinct.

This ordering is a dependency suggestion; consolidate differently if actual code demands. The user expects all feasible implementation, not just a new plan. If physical data is needed for a conditional decision, complete other coding work and state exact evidence required. Update existing master plans with M1–M13 mapping, current source and CI status, implementation details, regressions, acceptance evidence and unresolved risks. Retain history; never overwrite user sign-offs.

## 10. Verification and reporting discipline

Before commit: verify branch head; inspect exact relevant current files; stage coherent changes; run all locally available checks and identify what cannot run; verify source dependencies match baseline by Git blob SHA when reusing fetched files. Atomic multi-file commit to feature/architecture-g-rust-stitching only, no force push. Ensure all necessary implementation/test/plan files included; no credentials, logs/traces, actual media, signing keys or temporary generated files.

CI: exact candidate SHA, Rust host tests, strict Clippy with warnings denied, ASan/leak checks, native Android ABI builds, byte/fixture decode parity, JVM and production wiring regression tests, Android API29/31/36 instrumentation and signed APK publication. All mandatory jobs must be successful, not skipped. Distinguish completed green baseline vs candidate in progress. If failed, retrieve failed job logs, identify root cause and correct in consolidated manner; do not disable gates. Check Android-only cfg code before push; Build 878 demonstrated host-only compilation misses it.

Report each consolidated result in simple terms: build number and exact commit, implemented M IDs and remaining work, automated verification vs physical evidence, what changed in expected memory/quality/behavior, actual measured savings vs theoretical estimates, exact next user test if required. Provide build and commit links. No background promises; do work in current chat or clearly report pending CI. Do not request repeated tests already provided unless new candidate or specific measurement requires it. Never state a percentage of coding completion without traceable deliverables and proof.

Final goal: demonstrate a lower total memory footprint than Kotlin, with at least 20% lower matched steady-state process PSS, lower peak memory, sustained Rust speed and fewer unnecessary copies, reliable low-memory Android TV playback, unchanged manual video quality, supported DRM/Cast and all existing functions. This is the approved work, not a request to rethink Architecture G from scratch.
