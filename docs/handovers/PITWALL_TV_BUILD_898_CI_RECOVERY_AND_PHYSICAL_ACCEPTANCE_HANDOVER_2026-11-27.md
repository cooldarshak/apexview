# Pitwall TV / ApexView — Build 898 CI recovery and physical acceptance handover

**Date:** 27 November 2026 (IST). **Repository:** `cooldarshak/apexview`. **Existing branch:** `feature/architecture-g-rust-stitching`. This is the next-chat execution prompt, not a request to restart development.

## 1. Start here — mandatory immediate action

Read this entire file and the current versions of the master plans and handovers in §3, verify live branch HEAD and the latest GitHub Actions run, then immediately resume from the actual failing gate. Work and commit directly in the existing GitHub branch, not a separate cloned development workspace. Do not restart Architecture G, redo the already reconciled scope, or ask the user to repeat the earlier logs and plans. The user expects completion, not another indefinite source-audit loop.

At the time this handover was written the latest **source** commit was `a2f05af53f672d6bf2ac6f4acd5c26cf26d88415` (the handover adds a documentation-only commit). Build 898 has finished and FAILED. The last green signed baseline is still Build 896. A newer run or commit may supersede these facts; verify before changing source.

The first remaining coding task is the Build 898 strict Rust warning failure described in §2. Correct it in Rust, preserve every original assertion and warning gate, add meaningful coverage, update both master ledgers, then trigger one coordinated replacement build. If another gate fails, read its actual logs and fix the demonstrated issue directly; continue toward a green signed build. Do not trigger tiny speculative builds, relax CI, or treat missing physical evidence as an invitation to reopen every coding task. Do not voluntarily stop at a partial implementation or ask for a further “continue” instruction. If the execution environment genuinely cannot complete verification within this turn, report the exact unverified gate and committed checkpoint, not a promise of asynchronous work.

## 2. Verified build history and immediate root cause

| Build | Exact source SHA | Actions run | Result |
|---|---|---|---|
| 895 | d5fd4bc4fa5e69bd2fe9aa2de635e3d47ded66f1 | [45804191998](https://github.com/cooldarshak/apexview/actions/runs/45804191998) | Green |
| 896 | 75577ebf25ab006871d6d034ff3eb57a0795e2d8 | [45818263209](https://github.com/cooldarshak/apexview/actions/runs/45818263209) | Last verified green baseline |
| 897 | f42c296e86ba79fedc06d8baa3dc69105c9fa21c | [45875659456](https://github.com/cooldarshak/apexview/actions/runs/45875659456) | 89/90 Rust host tests passed; one real ownership failure |
| 898 | a2f05af53f672d6bf2ac6f4acd5c26cf26d88415 | [45876533634](https://github.com/cooldarshak/apexview/actions/runs/45876533634) | 91/91 Rust host tests passed; strict warning gate failed afterward |

### Build 897 root cause — fixed and host-verified in 898

Failed job 140101958845, step Architecture G.0 host kernel and Android ABI feasibility. Rust test `fragment_storage::tests::explicit_pressure_trim_retires_existing_backing_only_after_last_lease` at `native/architecture-g/src/fragment_storage.rs:474`: left 512, right 0. A pressure watermark marked an older sentinel lease for retirement. New generic idle backing accumulated while that sentinel remained pinned. The stale-release branch returned early when the final live sentinel closed, skipping the ordinary final-owner generic cache cleanup.

Commit a2f05af fixes the production `Arena::release` stale branch: after subtracting released capacity, if no live owners remain, call `trim_generic()`. It preserves separately admitted post-pressure atlas idle backing rather than indiscriminately trimming it. The original failing test is unchanged; added `pressure_retired_last_lease_preserves_new_atlas_idle_permit` verifies exact capacity, unchanged pre-pressure reader contents, pointer reuse and zeroing of the new atlas backing. Build 898's 91/91 host pass confirms both regressions passed. Do not revert this fix or change the expected zero to 512.

### Build 898 root cause — OPEN, next chat must fix first

Failed job **140104907625**: [job log](https://github.com/cooldarshak/apexview/actions/runs/45876533634/job/140104907625). Coordinated change contract passed (7 contract tests; changed_files=3). Rust host suite passed 91/91 in 34.63 seconds. All binary/doc-test harnesses completed. Immediately afterward the strict host compile/lint gate failed:

```text
error: field `adopt_rejected` is never read
  --> src/fragment_storage.rs:50:5
50 |     adopt_rejected: u64,
   = note: `-D dead-code` implied by `-D warnings`
error: could not compile `pitwall_g0` (lib test) due to 1 previous error
Process completed with exit code 101.
```

In the current implementation `Arena` is compiled for Android or host tests, but the public JNI-facing `adopt` wrapper increments `adopt_rejected` only under Android cfg and the 21-field Android snapshot reads it only under Android cfg. Host tests call `Arena::adopt` directly, so the field is not read in the host test build. This is not a runtime regression in the 91 passing tests. The later Android ABI, FFmpeg transport/HEVC, Gradle, instrumentation, signing and final publication gates were skipped.

**Recommended fix (not yet implemented):** make rejected-adoption accounting a shared arena-level behavior exercised by host and Android, rather than merely adding an allow-dead-code attribute. For example, split `Arena::adopt` into a checked implementation plus a wrapper that increments the saturating rejected counter exactly once when the operation returns None. Remove duplicate accounting in the Android wrapper. Add a host regression that reads the counter and verifies rejection before destructive transfer, successful transfer does not increment it, and multiple rejection reasons are counted exactly once without charging or modifying existing live readers. Alternatively retain Android-specific accounting and cfg-gate the field appropriately if source review proves it is truly platform-only, with equivalent Android coverage. Prefer the first option because the counter is an actual memory diagnostic requirement. Preserve the 21-value JNI layout and slot ordering, existing native admission/retirement behavior, and all other counters; do not silence warnings globally or delete the field. Read the exact current code and CI command before implementation. The recommendation is an engineering proposal, not a verified patch.

Check the entire strict Clippy gate and Android-target lint/build, not just the first error. No signed APK from either failed run. Do not ask for a new physical test until a verified signed candidate exists.

## 3. Mandatory repository reading order

Use the live branch versions, not stale copies. Read complete referenced requirements, acceptance criteria, prior observations, and the final finite closure ledger at the end of both master documents.

- `docs/handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md`
- `docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md`
- `docs/handovers/PITWALL_TV_BUILD_896_FINAL_SCOPE_CONTINUATION_2026-11-26.md`
- `docs/handovers/PITWALL_TV_BUILD_896_POST_RESEARCH_CONSOLIDATED_CONTINUATION_2026-11-26.md`
- `docs/handovers/PITWALL_TV_BUILD_896_MEMORY_ENGINEERING_HANDOVER_2026-11-26.md`
- `docs/handovers/PITWALL_TV_BUILD_895_ENGINEERING_HANDOVER_2026-11-26.md`
- `docs/handovers/PITWALL_TV_BUILD_892_PHYSICAL_TEST_ENGINEERING_HANDOVER_2026-11-26.md`
- `docs/handovers/PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md`
- `docs/handovers/PITWALL_TV_BUILD_867_ARCHITECTURE_G_CONTINUATION_2026-11-25.md`
- `docs/handovers/PITWALL_TV_BUILD_857_ARCHITECTURE_G_HANDOVER_2026-11-25.md`
- `docs/LOCAL_TV_RECEIVER.md`, `.github/workflows/android-debug-apk.yml`, `scripts/run_rendernode_instrumentation.sh`, `scripts/pitwall_change_contract.py`
- Other referenced plans, trace capture configurations, source contracts and actual implementation as needed. Keep one consolidated performance master and its synchronized Architecture G companion. Do not invent a competing plan.

## 4. Scope completion: separate source, CI, physical evidence

The final scope reconciliation committed in Build 897 (`f42c296e`) records S = source implementation and regression source present, E = evidence-dependent. This was a finite coding candidate closure, not a verified runtime/physical acceptance. Build 898 additionally confirms the native host test suite passes. Only demonstrated failures should reopen specific requirements. Do not set all rows to partial because the full CI has not yet passed.

| Workstream | Source candidate status | Remaining gates |
|---|---|---|
| G-TV1–G-TV8 | 8/8 mapped to source and tests | Full CI and actual phone/TV/Cast scenarios |
| G-C1–G-C6 | 6/6 mapped | G-C2/G-C3 diagnostic lint correction; full CI and matched physical evidence |
| G-C7 | Feasible instrumentation/reversible experiment implemented | Renderer promotion conditional on measurement |
| G-C8 | Conditional, not required coding | Allocator evidence first |
| G.1–G.24 | 23 unconditional rows mapped; G.11 additional SIMD conditional | CI and physical gates; preserve original G.0/G-A/G-B historical acceptance |
| R1–R8 | All eight applicable baseline refinements mapped | Additional R4 worker tuning conditional |
| M1–M13 | 12 applicable baseline rows mapped, M12 conditional | M5/M6/M10 ownership regression passed on host; diagnostic correction and full CI; M13 promotion conditional |
| Original P0–P2 | All 16 workstreams mapped | Retained regression and release matrix |
| A–H | 8/8 mapped | Startup stability, cache and physical graphics/pacing measurement |
| P0-A–P1-F | 6/6 mapped | Quality, status, memory, overlay and driver labels physical checks |

No numerical percentage for measured performance or physical acceptance can be inferred. Do not claim 100% CI or release acceptance. The current known coding blocker is specific; fix it rather than starting another audit cycle.

### Source checkpoint inventory (preserve all)

- G-TV1/G-C1–3: Rust `engine.rs`, `atlas_job.rs`, `fragment_storage.rs`, `jni_bridge.rs`; Java `NativeStitchingEngine`; `NativeStitchingDeviceBudget`, `NativeClearArchiveBridge`, `NativeFragmentLease`, `ProcessMemoryDiagnostic`. Normal-RAM exact foreground recovery uses at most one quarter of measured OS surplus; preserves independent JVM reserve and low-RAM rejection. Four Build 896 TV observations are tested in host planner, Rust capacity boundaries and Android JNI fixture (`a405a5f`). Actual physical ARMv7 admission not yet proven. Workspace accounting is partial owned capacity, not resident bytes.
- G-TV2/G-C3: immutable archive initialization backing and output initialization/fragment reader leases; bounded sidecar validation, primary/selected audio and captions overlap video proof; latest selection/generation fences and cancellation cleanup (`fa3c447`). Maintain exact media/DRM validation, mandatory primary requirements, optional programme choices and all rollback behavior.
- G-TV3/G-C5/G.22–23: `ReplayStartupPublication`, `ReplayCoreCache` V5 independent preview and V3 complete cache, corrected playback clock, independent map/tower coverage and readiness, chronological stopped/retired/rank history, bounded native batched sampling. `CastTimingPublisher/Receiver` seek epoch, stale packets, chunk generation fences; cfaaa6d/7240e26 invalid first packet cannot adopt epoch. Retry incomplete publication with a fresh sample; retain map motion independent of table. No stale race or stale standings.
- G-TV4: TLS exact certificate pair, physical 96-bit TV selection code and TV-only approval for authenticated selection, legacy mutual comparison, remembered peers/revocation/manual reconnect restrictions. Never trust discovery name/IP; do not weaken TLS, licensing or authentication.
- G-TV5/G-TV6/G-TV7: direct/native composed/finite relay/baked Pitwall routes, bounded immutable resources and socket reader leases, exact per-load IDs, first-frame timeout, CAF custom/standard/baked ownership and pending commands, handback and lost-ownership grace. Preserve local 60s watchdog and Google 2s ownership grace as recovery guards, not startup targets. Rust TS/PES writer and independent parser/FFmpeg decoder, actual synthetic AVC/AAC byte parity, timestamp/continuity and ADTS checks. Standard/baked CAF status is only a rendering proxy.
- G-TV8/G-C4/G-C6/G-C7/G.24: Media3 active/parked weak ownership ledger and actual load lifecycle, graphics scalar owner tracking, producer detach before releasing consumer, EGL display leases, stale Surface epochs, bounded encoder/capture teardown; retained TextureView default and reversible clear SurfaceView experiment. Unknown vendor graphics/codec/sample-queue bytes explicitly unknown; no double counting. No additional full-frame copies/decoder/surface as a workaround.
- G.21 audio: `CmafCastAudio`, `BakedCastArchiveAudio`, Rust audio metadata/scaling/ADTS/TS, bounded publication and programme selection. Android Media3/MediaCodec/AudioTrack, platform rendering, authorized DRM, networking/UI remain platform boundaries. No proprietary SDK.
- `71f3823` and `f42c296e`: coordinated source-wiring updates, 288 simple assertions reviewed, 31 new/replacement source assertions checked, all nine instrumentation runner footers match 20 test annotations. These checks are not full test execution. Preserve existing tests and all actual acceptance gates.

## 5. Hard engineering rules

- Work on the existing branch directly. No new feature branch, no merge to main, no receiver deployment without authorization. Intermediate commits may use [skip ci]; group related fixes/tests/master-plan updates. Use the normal existing GitHub Actions workflow for the replacement signed candidate. Verify HEAD/parent and avoid overwriting concurrent changes. A non-skip coordinated commit touching source/regressions (not docs alone) triggers CI. Use the same commit for the required test and consolidated plan update; the prebuild contract checks HEAD^..HEAD. Do not use empty commits to bypass the contract.
- Rust for applicable application-owned media processing, timing/map/tower numeric sampling, memory ownership and transport. Kotlin/Java for Android lifecycle, UI, framework integration and safe reference/fallback. Preserve authorized F1 login/entitlement, Widevine CBCS and existing secure output. Never extract DRM keys or weaken verification. No proprietary SDK, paid OpenF1 or device-specific workaround.
- Preserve phone and Android TV, single-decoder composed HEVC (one output surface, one decoder and authorized DRM session when supported), multiple decoder mode, clear/protected, live/replay, all layouts, selected per-feed quality, audio/CC, map/tower settings, captions size/background opacity, DPAD, Google Cast and Pitwall Cast. No silent resolution downgrade; only show actual advertised valid rungs; disclose actual selected source and driver names. No memory target by dropping features.
- User previously objected to dozens of small builds and repeated blanket partial statuses. Finish the bounded recovery, retain test evidence, keep all prior signed-off source work unless a concrete failure warrants change. No speculative new allocator/renderer/SIMD/concurrency work in this batch.
- Keep Rust preference/default and existing renderer policy unchanged until physical validation and user approval. A reason-coded fallback is legitimate safety behavior but not proof of Rust activation.

## 6. Exact CI verification checklist

Read the live workflow for authoritative command flags and pinned tools. At minimum preserve and run its original checks:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/pitwall_change_contract.py
bash -n scripts/run_rendernode_instrumentation.sh
node tools/test_cast_receiver.mjs
node tools/test_cast_layout_receiver.mjs
node --check web/cast/receiver.mjs
cargo test --locked --manifest-path native/architecture-g/Cargo.toml
# Run the workflow's exact strict Clippy command, all four ABI builds,
# sanitizer/host fixture checks and native symbol verification.
```

Keep the real TS fixture `native/architecture-g/src/bin/baked_ts_fixture.rs`, `tools/validate_baked_rust_ts.py`, `BakedRustTransportParityTest.kt` and the FFmpeg/ffprobe independent decode; run using the actual compiled Rust binary. Maintain byte-exact clear CMAF and protected reference parity, synthetic HEVC independent decode/pixel checks, Android unit and exact-source instrumentation, Media3 and four-ABI checks, APK signing and upgrade from exact signed Build 843. API29 full, API31/API36 TLS gates and encrypted internal stage/final publication must remain fail-closed. Count actual @Test annotations against runner expectations if changed. All five required jobs must succeed before reporting green; verify exact artifact/source SHA. Never publish or recommend a failed candidate APK.

If a test or strict lint fails, preserve its original assertion and correct the cause. Keep a concise evidence ledger in both master plans with run ID, commit, actual counts, root cause and correction. No fake local test results: prior environment lacked full Rust/Android toolchain; GitHub Actions is authoritative for full execution.

## 7. Physical acceptance after CI green, not before

Devices: Samsung Galaxy S22 Ultra, Android 14/120Hz adaptive; TCL 55C655 Android TV 12/API31 ARMv7 (normally Software DRM ON required). Preserve setting and record actual decoder, selected source quality, actual native vs JVM route, fallback reason, OS/JVM reserves and simultaneous memory owners. User has aShell/Bugjaeger and previously supplied logs/Perfetto traces. Reuse the existing capture commands and scripts from master handovers; do not request redundant diagnostics before coding recovery.

Prior 896 physical baseline: phone ~637 MiB median process PSS, graphics ~252 MiB (approximate prior observations, not controlled acceptance). TV was observed to produce 12 JVM segments despite Rust requested, admission denied for historical 5–7 MB packet vectors; latest source proposes bounded normal-RAM recovery, not a confirmed TV fix. Earlier 895 median PSS 649.6 MiB; sampled peak 678.5 MiB. 895 logs showed 22 ranked rows ~1.1s, partial position update ~3.7s caused zero ranked rows at 5s, standings recovered ~7s; map blink reported. Latest fixes must be physically verified for first/stable readiness and correct playhead. Do not confuse graphics PSS with removable app allocations.

Use matched Kotlin/Rust ON/OFF same build and same replay/feed resolutions/layout/DRM/playhead/speed/thermal and display conditions. Include cold/warm cache, upgrade, playback start, multi-feed startup, 1x/1.5x, forward/backward seeks, manual quality, programme audio/CC, stopped/retired driver recovery, map-only/tower-only/both, single/multiple decoder, 60/120Hz as appropriate, single and multi-feed cast, reconnect, manual stop, handback, receiver rendering, and clear/authorized protected replay/live. Verify full race endurance at least two hours on phone and TV with required features. Use existing `scripts/analyze_pitwall_runtime.py`, `analyze_pitwall_bufferqueues.py`, `analyze_pitwall_frames.py`, `analyze_pitwall_overlay_ab.py`, `check_pitwall_memory_trace.py`, `pitwall_acceptance_matrix.py`, `validate_pitwall_acceptance.py` and existing Perfetto capture scripts. Retain exact-build ELF/symbol attribution, memory trace framing and PID/time window comparisons.

User-approved targets (from master plans): steady Rust PSS <=80% matched Kotlin, reduced sampled peak and no sustained growth; >=30% stitching CPU reduction; >=90% measured avoidable overlay allocation churn reduction. No regression in media quality, playback, entitlement/DRM, audio/captions, timing/map correctness, controls, Cast or battery/thermal behavior. Startup and p95/p99 segment/clock/frame deadlines evaluated separately from steady averages. No claim of measured targets from code/test counts. Vendor opaque graphics/codec pools and private Media3 queues remain unknown unless defensible attribution exists. M12/G-C8 allocator, structural M13/G-C7 renderer promotion, G.11 extra SIMD and R4 extra workers require evidence.

## 8. Next chat reporting and completion

Start by inspecting Build 898 (or newer if present), implement the exact known remaining correction, commit both ledgers, and run coordinated CI. After verified green, give the user the exact signed APK run/artifact and a compact phone/TV physical test plan using existing commands. Report source completion, automated results, physical acceptance and conditional tasks separately. If still failing, state the exact failed step/error, the committed correction, and verified run status; do not claim green or request physical tests on failed builds. Preserve all previous plans. No additional user confirmation is needed for this continuation.

**Handover boundary:** this file itself is documentation only. The Build 898 lint fix is recommended but not implemented in this handover commit, and no new build is triggered by this documentation change. The 897 runtime ownership correction is verified by the 91 passing host tests in 898; remaining CI and physical acceptance remain open.
