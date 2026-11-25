# PITWALL TV — BUILD 867 ARCHITECTURE G CONTINUATION HANDOVER

Copy this entire document into a new ChatGPT chat in the F1 project. Continue the existing repository, do not restart or create another app.

## 1. Repository and exact checkpoint

- Repository: `cooldarshak/apexview`
- Work ONLY on `feature/architecture-g-rust-stitching`.
- Latest code candidate: `d2731aa8bc7acb11dc6aecd2de2c7eec653c3b87` (Build 867).
- Build 867: https://github.com/cooldarshak/apexview/actions/runs/45588253375 . At handover, build job 139115331718 was running; no final conclusion established. Recheck exact run before doing anything else. A subsequent documentation-only commit is expected and does not change the binary source or trigger Android CI (workflow ignores docs/** and **/*.md).
- Last verified green binary: Build 865, `0898df41ece25be6f51012f81325b2bee54e1bbb`, https://github.com/cooldarshak/apexview/actions/runs/45577690010 . All five jobs passed: build 139079577499, API29 139084565657, API31 139084565683, API36 139084565756, final publication 139086113365.
- Build 866: `3338f12a3d1197713ff78b23f38304c05c5f1875`, https://github.com/cooldarshak/apexview/actions/runs/45584014962 . Failed build job 139100878127, step 14, Validate physical FrameTimeline comparison tool. Instrumentation and publication skipped.
- Protected branches: main `7d42ae9b3c9178f838717700586bfb7fb50f4ec7`; feature/pitwall-performance-audit-plan `1c479fd6fa6ab5d8b0809d3e37f51ce6336a9028`. Verified unchanged at last code publication. Do not merge, reset, delete or modify either.
- Canonical plans (read both at exact latest ref): [Architecture G master plan](PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md) and [consolidated performance audit plan](../PITWALL_PERFORMANCE_AUDIT_PLAN.md). This dated handover is a continuation snapshot, not a competing plan.

## 2. User-confirmed architecture decision

The user is a lead business analyst, not a developer. Explain outcomes in plain English. Main purpose: smooth, stable, low-resource playback on the TCL 55C655 Android TV 12, also Samsung Galaxy S22 Ultra. Preserve all existing features and avoid rewriting working components merely to increase the percentage of Rust.

The user explicitly reaffirmed original Architecture G on 25 November 2026, with strengthened coverage, NOT a full-app Rust rewrite. Rust is the chosen native language; no C++ production port. Target low-copy on-device HEVC stitching and CMAF processing, bounded native memory, reusable analysis and scheduling. Keep existing Media3, hardware MediaCodec, authorized Widevine, OkHttp/login, Compose/UI, GPU rendering and cast services. Move other expensive code only where profiling demonstrates benefit. No paid cloud, proprietary SDK or OpenF1 paid dependency.

Rust ON requests the common native stitcher for every applicable compatible clear/protected local and Cast stitching route, across phone/TV, live/replay, layouts and qualities. Rust OFF remains the existing JVM engine and the default. Non-stitching paths (including ordinary single-feed or multiple-decoder direct playback) should not allocate a stitcher or perform artificial processing; report NOT_APPLICABLE when genuinely unused. Any unsupported native route, resource admission denial or recoverable failure must have a clear diagnostic reason and actual-engine status. Safe authorized JVM fallback remains permitted, not counted as native success. Never silently lower quality, change decoder mode, alter DRM or reset the setting. A missing native integration on an applicable path is an OPEN coverage requirement, not NOT_APPLICABLE.

Preserve legitimate F1 TV authentication, entitlements and DRM. Native optimizations must not decrypt, extract keys, bypass license or security policy, or change trust boundaries. Keep existing validation and opaque payload invariants. Platform connector code is acceptable; do not confuse native memory ownership with every operation being written in Rust. Media3's standard DataSource reads still require a bounded copy into a byte array. No absolute zero-copy or speedup claims without measured evidence.

## 3. Hard engineering rules

- Complete the agreed phase's engineering work in consolidated meaningful batches; do not create micro-commits or trigger needless builds. Audit exact code and all affected paths first, fix every confirmed in-scope issue, and keep existing tests intact or strengthen them.
- Do not stop after a superficial fix or claim completion while known coding gaps remain. Clearly distinguish coding, automated CI, physical acceptance and measured performance. Report blockers and remaining gaps; no invented percentages or fabricated evidence.
- No G-C or G-D implementation until explicit approval. G.0 signed off; G-A foundation coding and automated acceptance green in Build 857. Current authorization is finish G-B.
- Both canonical plans must reflect actual evidence and open work in the same consolidated code commit. Preserve all 20 Architecture G workstreams, eight refinements and original 16-workstream performance/release plan.
- User is mobile-only: ChatGPT Android, GitHub/Composio, GitHub Actions, aShell/Bugjaeger and Perfetto. No dedicated PC, Android Studio, local Gradle or Rust toolchain assumed. Use CI for all ABIs, signed APK and API29/31/36 tests. Avoid polling repeatedly; inspect exact failed jobs and logs when needed. Save candidate work before publishing; do not lose uncommitted work.
- Keep all layouts, saved layout settings, per-feed quality, timing tower/map/overlays, audio and captions, controls, DPAD, seek/speed, Google Cast and Pitwall Cast, baked/non-baked modes. Keep Software DRM setting and authorized hardware decoder paths.

## 4. Full Architecture G plan and current status

| ID | Workstream | Phase | Current evidence/status |
| --- | --- | --- | --- |
| G.1 | Audit/profile JVM baseline | G-A | Foundation audit/counters complete; matched physical evidence pending |
| G.2 | Bounded native input/output arenas | G-A | Foundation green 857; protected output arena candidate 866/867 |
| G.3 | Exact native HEVC parsing/stitching | G-B | Partial; clear and protected native computation green through 865; new cache and rolling clear candidate; unsupported profiles/history-dependent clear still open |
| G.4 | Exact CMAF assembly, size/offset/timing | G-B | Partial; clear and protected metadata green; native-owned protected final storage/local DASH candidate pending CI |
| G.5 | Preserve authorized DRM compatibility | G-B | Existing authorization/validation retained; synthetic metadata/clear-prefix tests green 865; new ownership path unverified on real F1 content |
| G.6 | Buffer ownership, leases, lifecycle | G-A | Foundation green 857; expanded protected lease tests pending |
| G.7 | Bounded queues, backpressure and deadline scheduling | G-A | Foundation green; real production tuning later |
| G.8 | Reusable Direct ByteBuffer and JNI ownership | G-A | Foundation green; copy optimization later |
| G.9 | Network-to-native copy reduction | G-C | Not started |
| G.10 | Media3 publication copy reduction | G-C | Not started as phase; use measured existing interfaces, no speculative fork |
| G.11 | ARM NEON where justified | G-C | Not started |
| G.12 | Allocation-free steady-state hot path where achievable | G-C | Not started |
| G.13 | Cancellation and stale-generation fencing | G-A | Foundation green; rolling/native ownership candidates require regression |
| G.14 | Compatible feed switching and bounded prefetch | G-C | Not started |
| G.15 | Fuzzing, leak/sanitizer safety gates | G-A | Foundation green; retain and extend |
| G.16 | Exact differential and independent decode regression | G-B | Partial; 865 full 50-picture protected-labelled differential green; new arena, cache, rolling and 4/6/8-slot tests pending |
| G.17 | Device-adaptive memory budgets | G-A/G-C | Foundation provider green; combined real memory budget/tuning pending |
| G.18 | Sustained CPU/RAM/power/thermal/playback measurement | G-D | Not started |
| G.19 | Every existing feature/mode; all applicable local/Cast native stitching | G-C/G-D | Strengthened scope; Cast integration pending G-C, full matrix pending G-D |
| G.20 | Phone/TV ABI/API/alignment/signed upgrade | G-A | Foundation green 857; retain exact current CI gates |

Eight refinements remain mandatory: R1 fused processing; R2 deadline-aware scheduling; R3 device-adaptive arenas; R4 adaptive bounded worker pool; R5 batched JNI; R6 reusable stream analysis; R7 layout-aware adaptive quality (only Auto, preserve manual choice); R8 end-to-end attribution. Refer to canonical plan for exact mappings.

G-B = G.3/G.4/G.5/G.16, shared native core, eligible local producers, bounded ownership and correctness. G-C = G.9/G.10/G.11/G.12/G.14 plus all remaining applicable producer/receiver stitching integration including Cast, measured copy/deadline/memory/quality optimization. G-D = G.18/G.19 plus outstanding acceptance, complete phone/TV mode matrix, matched A/B and full-race endurance. Do not move a real unresolved core G-B defect to later phase just to declare completion. The user did not approve G-C/D execution yet.

## 5. Evidence and changes through Build 867

Build 865 exact API36 evidence:
- GB clear tiled worker byte parity and lease fencing PASS.
- GB CBCS metadata-only native/JVM exact moof, IV modes, tfdt versions, opaque payload preservation and negative fallback PASS.
- GB protected clear-prefix native syntax, exact borrowed output, metadata range boundaries and explicit fallback PASS.
- GB complete two-source protected-labelled picture and CMAF differential, incompatible IV rejected before native admission PASS.
- GB direct protected-labelled sequence: frames=50 slices=400 headerOnly=243 threeClear=157 shortSliceFallbacks=43 exact POC/sample/fragment parity PASS.
- GB clear mixed NAL lengths, in-band identity, non-VCL, terminal padding and cached rejection PASS.
- GB archive route exact parity, native-only next fragment, seek publication fence and explicit fallback PASS.
These are independently encoded clear/synthetic-labelled fixtures, NOT real protected F1 playback or performance proof.

Build 866 (20 changed files, candidate; failed before Android gates):
- GBS2 exact ordered clear parameter-triplet cache for cross-picture reuse; one bounded immutable cache, explicit retirement and invalid-key rejection.
- Generic Rust fragment storage arena: 64 MiB charged ceiling, 12 live allocations, two reusable exact-size free buffers totaling at most 8 MiB; final release trims idle arena. This is a maximum, NOT measured TV-safe capacity. G-C must measure/adapt total native+JVM+codec memory.
- NativeFragmentLease, direct CMAF inspection, direct output writes into Rust-owned allocation, retained history and local DASH resource-store leases, Media3 range reads and retirement/cancellation fencing. JVM bulk copies borrowed bytes into final native memory; platform read still copies requested range. Existing authorization, protected init and validation stay unchanged. Cast/legacy callers receive heap snapshots.
- New native storage and full 50-picture regression, cache fixture checks, reader retirement, invalid/stale lease/backpressure tests and ABI symbol checks. No runtime pass established for 866.

Build 866 failed because test_post834_protected_payload_written_directly_to_final_fragment expected source expression sample.copyInto(result, cursor), renamed by writer refactor. Build 867 restores the exact direct-copy expression and keeps its original assertion, adding native destination/independent validation assertions. Both the exact failed test and new rolling source-wiring test passed locally on staged source; no full compilation available in the remote environment.

Build 867 (10 files, one consolidated code commit):
- ClearCmafArchiveComposition now dispatches eligible independently decodable rolling HLS fragments via the proven NativeClearArchiveBridge; first reference initialization, first native parity, validated input reports, budget admission, generation-fenced publication. OFF, history-dependent/multiple-fragment, missing context and admission/worker rejection explicitly remain JVM. History-dependent boundary invalidates native proof; reset retires the engine.
- F1ArchiveFeedCapture/F1HlsFeedCapture and AppState pass local device context and preference. Casting not newly native-enabled.
- Instrumentation adds four contiguous rolling clear fragments, checks actual Rust publication on eligible fragments and OFF parity; full 4/6/8 logical clear source slots test complete native/JVM CMAF equivalence. Larger feed-count tests reuse two encoded source fixtures, not independent F1 feeds.
- Both canonical plans record revised scope and exact 865/866/867 status. Change contract passed. Full Build 867 still pending at handover.

## 6. Immediate execution instructions for new chat

1. Read exact branch HEAD, latest code commit, both plans and exact Build 867 workflow/job status. Do not assume the documentation commit has a matching binary run. If failed, inspect the failing step and exact logs, not the general workflow title. Verify earlier green evidence and preserve all original assertions.
2. Audit the 866/867 diff for compile/runtime errors, including Rust host cache fixture, native arena lifetime, direct ByteBuffer parser, Kotlin ownership/retirement, local DASH builder and rolling clear source/geometry/clock tests. Check all affected call sites and source wiring. Review actual CI evidence before claiming new gates passed.
3. Finish outstanding G-B core work: native HEVC/CMAF supported-profile equivalence, history-dependent clear fragments and other currently unsupported native profiles, correct bounded ownership, exact reference output and independent decode/metadata regression. Enumerate all supported JVM-admitted profiles and distinguish safely unsupported content from an unimplemented native route. Preserve existing authorized protected pipeline; do not bypass protections. For additional protected verification use legitimate device playback and clear/synthetic metadata fixtures with unchanged security boundaries.
4. Audit current actual route inventory: finite clear local, rolling clear HLS, protected local live/replay, direct playback and applicable cast paths. Do not claim that all routes use Rust yet. Cast integration is explicitly G-C, as recorded in the current canonical plan, and must remain an open acceptance requirement. Do not begin G-C without user approval.
5. Consolidate all confirmed in-scope corrections and missing coding into a meaningful change, update both canonical plans, run available checks, preserve the exact source and verify branch heads before publishing. Do not remove tests, disable checks, lower safety requirements or trigger one build per small edit.
6. Report in plain language what is implemented, what actually passed CI, what remains coding, what requires physical verification and any decisions/limitations. Do not mark G-B 100% complete while known core gaps remain. After G-B passes and coding audit is closed, ask for approval before G-C; physical performance validation is separate, not inferred from green CI.

## 7. Source entry points and development workflow

- Rust: native/architecture-g/src/{clear_atlas.rs,atlas_job.rs,protected_plan.rs,protected_header.rs,cbcs_metadata.rs,fragment_storage.rs,jni_bridge.rs,engine.rs,hevc_parameters.rs,hevc_slice.rs,cmaf_input.rs,cmaf_init.rs}.
- Kotlin/Java: app/src/main/java/app/apexview/tiled/{NativeStitchingEngine.java,NativeStitchingSelection.kt,NativeStitchingDeviceBudget.kt,NativeClearArchiveBridge.kt,NativeClearArchiveJob.kt,NativeFragmentLease.kt,ClearCmafArchiveComposition.kt,F1ArchiveFeedCapture.kt,F1HlsFeedCapture.kt,HevcArchiveVodSession.kt,ProtectedHevcLiveComposition.kt,ProtectedHevcPictureComposer.kt,ProtectedHevcSampleComposer.kt,ProtectedHevcCmafFragmentWriter.kt,ProtectedHevcLiveDashSource.kt,CmafBoxInspector.kt}; AppState.kt, FeedPlayer.kt, cast/NativeComposedCastSession.kt.
- Regression: app/src/androidTest/java/app/apexview/tiled/ArchitectureGBAndroidTest.kt, native/architecture-g/src/bin/gb_clear_grid.rs, scripts/test_pitwall_production_wiring.py, scripts/pitwall_change_contract.py, scripts/verify_architecture_g0_apk.sh.
- GitHub Actions .github/workflows/android-debug-apk.yml: existing Android branch, all four ABI build/symbol/alignment gates, Rust host tests/Clippy/sanitizers, independent HEVC pixel/timing verification, JVM tests, API29/31/36 Android instrumentation and final signed publication. Docs-only pushes ignored.
- Connected GitHub tools can read exact commits/files and fetch exact workflow jobs/logs. Composio remote workbench can stage source, verify Git blob SHA (sha1 of git blob header + bytes), run Python source checks, create one Git tree/commit with expected parent and non-force update branch reference. Existing remote candidate workspace /mnt/files/pitwall-gb867-candidate, earlier /mnt/files/pitwall-gb866-candidate; published.json records exact commit/tree/paths. Reuse cached bytes only when SHA matches latest tree; otherwise fetch exact blob. No assumption remote workspace persists into new chat. Remote workspace lacks cargo/rustc/Java/Gradle; full toolchain verification via CI. Never claim tests ran if not run.
- Verify protected branch heads before and after every write. No changes to main or parent. Documentation-only handover does not trigger new build.

## 8. Eventual physical acceptance (do not confuse with current coding)

Use matched Rust OFF vs ON on TCL 55C655 and S22 Ultra, same signed APK/content/actual feed resolutions, layout, refresh, network, decoder/DRM/render preferences and speed. Test single/multifeed, single/multiple decoders, clear/authorized protected, live/replay, 1x/1.5x, seek, feed switch, map/tower on/off, audio/CC, all supported cast modes and handoffs, race-length endurance. Track actual route/fallback, CPU, GPU, native/JVM/PSS RAM, GC, frame deadline/drop, BufferQueue backpressure, buffering, producer slack, thermal/power and latency. TV previously had low-resource struggles and required Software DRM in some older builds; later observations showed multiple feed combinations working with both DRM toggles. Do not assume a vendor condition is universal. No silent quality reductions; preserve manual selections. The aim is better performance, not an arbitrary Rust percentage.

**Continue G-B from exact current code and CI evidence. Do not restart the project, repeat completed G.0/G-A work or begin G-C/D without approval.**
