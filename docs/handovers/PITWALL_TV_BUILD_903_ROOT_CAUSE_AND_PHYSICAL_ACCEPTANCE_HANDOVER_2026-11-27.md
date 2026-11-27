# Pitwall TV / ApexView — Build 903 root-cause investigation and physical acceptance handover
27 November 2026 | Continue on existing branch | Documentation checkpoint, no APK build requested

## 1. Copy this prompt into the new chat

Read this entire file at `docs/handovers/PITWALL_TV_BUILD_903_ROOT_CAUSE_AND_PHYSICAL_ACCEPTANCE_HANDOVER_2026-11-27.md` in repository `cooldarshak/apexview`, branch `feature/architecture-g-rust-stitching`. Read both authoritative master plans and all relevant referenced handovers, including the finite requirement ledger and latest appendices. Check live branch HEAD, latest Actions status and exact signed APK before doing anything. Build 903 was verified green on all five required jobs; do not repeat the 897–902 CI recovery or restart Architecture G.

Resume from the Build 903 physical evidence and aShell export checkpoint. The user has attempted the separate native, graphics and Java Perfetto captures; the last reported error was `cp: Unknown option 'h' (see "cp --help")` at export Step 5. First determine whether those three already-recorded traces exist and can be exported individually. Do not assume recording failed merely because export failed. Use the precise commands below. Do not ask the user to repeat the full test until the existing files have been checked. If new evidence is attached, analyze it immediately, including trace integrity, actual owners, retained paths, lifetime, scheduler and frame timelines, and compare with the prior Build 901/903 baselines. If files are missing or invalid, request only the missing evidence with a verified command and explicit error handling. No unverified guesses about causes.

Use the collected evidence to pinpoint which object/class/reference chain, native allocation callsite, surface/codec/graphics owner, buffer queue, fetch worker, or scheduling phase is responsible for each problem; quantify the actual attributable bytes/time and identify the code responsible. Implement all remaining unconditional fixes that are justified by the evidence as one coordinated Rust-first engineering batch. Keep the original plans intact, update both ledgers, commit directly in the same Git branch with non-force expected-head updates. Do not clone or work in a separate workspace instead of committing directly. Intermediate [skip ci] commits are permitted; trigger one consolidated signed build only once all identified unconditional source work and regressions are complete, and fix actual CI failures until green. Do not claim physical acceptance from CI alone. Do not stop arbitrarily midway through a finite implementation scope. Do not ask for permission to continue work already authorized. Do not introduce speculative allocator/renderer/bitrate/DRM changes. Preserve everything in sections below.

## 2. Verified checkpoint and CI history

Repository: cooldarshak/apexview
Branch: feature/architecture-g-rust-stitching
Last verified source SHA: d920f3ce9606fc115ef4fa2f281fc71578e6b82e
Source tree: 826d3f17821d4554739d2df509eba38739ffdbe0
Build 903: versionName 1.0.1806, versionCode 101806 (actual package dump)
Actions: https://github.com/cooldarshak/apexview/actions/runs/45931466349
Signed APK: https://github.com/cooldarshak/apexview/actions/runs/45931466349/artifacts/15169461833
Exact native symbols: https://github.com/cooldarshak/apexview/actions/runs/45931466349/artifacts/15170015160
JVM reports: https://github.com/cooldarshak/apexview/actions/runs/45931466349/artifacts/15169451189

All five jobs verified success: Test and build debug APK; Android API29, API31, API36 instrumentation; final APK publication gate. This is compiled/automated acceptance, not full physical acceptance. This handover commit is docs-only and intentionally [skip ci]; the last signed source SHA remains the baseline. Check for concurrent advancement before editing.

Prior history: 896 green; 897 failed stale reader-retirement expectation; 898 lint dead field; 899 Rust passed but JVM audio fixture mismatch; 900 JVM passed but Android ADTS fixture invalid; 901 green (06d874d86e1da2560af61dcb404e21c8071714cc, run 45881176799); 902 failed one stale archive last-reader test (a73a7dfee82e60620707737d8ea824110e19281c, run 45929762244, native/sanitizer 94/94 each, JVM 1005/1006); 903 corrected expected retirement at last-reader close, preserved newest/unread/future/pinned identity and added cumulative retirement counters and strict heap packet continuity. Do not weaken assertions to obtain green.

Source commits since 901:
- 61d8fb5fbbe3c8f6a6462554aedda3bc643bea29: exact bounded HTTP reads, peak scene owner counts, per-source fetch timing, overlay callback rearm, actual-size native reuse regression, strict heap check.
- 3d3ac7ac19cd8abae2b002d27d4c114cb6a51e6f: deferred retirement of older delivered same-epoch cache backing on last reader close, invalidation on seek/relay, v5 heap config.
- a73a7dfee82e60620707737d8ea824110e19281c: controller-only both Cast protocols, bounded near-size native realloc, owner IDs and simultaneous peaks, independent timing publication, new root-cause capture.
- d920f3ce9606fc115ef4fa2f281fc71578e6b82e: 902 regression correction, last-reader retirement counters, strict ProfilePacket index/continuation check.

## 3. Mandatory reading and scope preservation

Read in full, including appendices (large files: fetch in ranges if necessary):
1. docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md
2. docs/handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md
3. docs/handovers/PITWALL_TV_BUILD_898_CI_RECOVERY_AND_PHYSICAL_ACCEPTANCE_HANDOVER_2026-11-27.md
4. docs/handovers/PITWALL_TV_BUILD_896_FINAL_SCOPE_CONTINUATION_2026-11-26.md
5. docs/handovers/PITWALL_TV_BUILD_896_POST_RESEARCH_CONSOLIDATED_CONTINUATION_2026-11-26.md
6. docs/handovers/PITWALL_TV_BUILD_896_MEMORY_ENGINEERING_HANDOVER_2026-11-26.md
7. docs/handovers/PITWALL_TV_BUILD_895_ENGINEERING_HANDOVER_2026-11-26.md
8. docs/handovers/PITWALL_TV_BUILD_892_PHYSICAL_TEST_ENGINEERING_HANDOVER_2026-11-26.md
9. docs/handovers/PITWALL_TV_BUILD_888_ENGINEERING_HANDOVER_2026-11-26.md
10. docs/handovers/PITWALL_TV_BUILD_880_GC_MEMORY_OPTIMIZATION_HANDOVER_2026-11-25.md
11. docs/handovers/PITWALL_TV_BUILD_867_ARCHITECTURE_G_CONTINUATION_2026-11-25.md
12. docs/handovers/PITWALL_TV_BUILD_857_ARCHITECTURE_G_HANDOVER_2026-11-25.md
13. docs/handovers/PITWALL_TV_BUILD_839_ENGINEERING_HANDOVER.md

Read referenced earlier plans/commits where necessary. Newest measured evidence and explicit user instructions override stale historical statuses, not safety or acceptance criteria. The master finite requirement/owner/regression ledger is the authoritative detailed map; do not regenerate a smaller plan that drops requirements. All G-TV1–G-TV8, G-C1–G-C8, G.1–G.24, R1–R8, M1–M13, original 16 P0.1–P2.9 workstreams, A–H and P0-A–P1-F remain in scope. Source candidates previously marked S now have consolidated Build 903 green CI for that exact source; verify specific tests when claiming a particular gate. Physical evidence not implied. E means conditional experiment, not missing unconditional implementation: G-C8/M12 allocator replacement; G-C7/M13 renderer promotion; G.11 additional SIMD; R4 additional concurrency tuning. Do not promote without reproducible attributed evidence.

## 4. User hard rules

- Android phone plus Android TV. Samsung S22 Ultra SM-S908E, actual latest Android 16 API36, 120Hz adaptive; TCL 55C655 Android TV12/API31, normally Software DRM ON (historical physical requirement). Preserve supported ON/OFF behavior, no device-specific hacks.
- Keep Architecture G: one composed HEVC input stream, one decoder and output surface, authorized Widevine session where applicable; preserve multiple-decoder mode, clear and protected, live/replay, F1 legitimate login/entitlement, CBCS, secure output. Never bypass DRM, extract keys, use proprietary SDKs or paid OpenF1.
- Rust for application-owned processing, stitching, compact memory storage, overlay numeric sampling and transport; Kotlin/Java only appropriate Android integration/lifecycle/UI/framework boundaries and safe reference path. No wholesale rewrite or migration back to Kotlin.
- No hidden quality downgrade, no unsupported quality options. Show real main/driver channel names, actual selected and effective source resolution. Keep 1080p composed canvas target and per-feed controls. Preserve layouts, TV default, free/snap, DPAD, map/tower sizing, opacity, geometry and saved layouts, continue watching, overlay speed/driver/lap bar, mini-sector colors, small map dots, captions, caption size/background opacity and alternate audio/CC, seek, pause, playback speed, recovery.
- BOTH Google Cast and Pitwall Cast must make phone a control center only, no visible moving local video or audible commentary; all feeds, timing tower, map and commentary on receiver. Direct handoff should stop local selected tracks/decoder where route allows. Baked route may still need source decoder and encoder to generate the TV stream, but must not present video/audio locally or encode phone controls. Preserve protected route restrictions, exact audio and clock; test disconnect/handback/reconnect/pause/seek and controls. No duplicate local presentation, no window PixelCopy fallback that accidentally records controller.
- Do not request endless diagnostics to postpone known fixes. Root-cause diagnostics must support attribution, not just totals; preserve user data/credentials and do not commit raw logs, traces, media, secrets or APKs.
- Direct branch commits, one consolidated build, source/automated/physical/conditional status separate. Never invent percent complete or use green CI as proof of measured performance.

## 5. Physical evidence received and actual capture failure

Previously attached files (private conversation evidence; reattach if not available in new chat):
- pitwall-901-memory.txt; pitwall-build-901-rust-on.perfetto-trace; pitwall-20261127-015429-262.log.txt
- Pitwall-evidence-rust903.tar.gz (218 KiB), pitwall-20261127-032231-221.log.txt, pitwall-20261127-032401-567.log.txt.
- pitwall-903-retry-configs.zip (three text configs; reproduced in section 7).
- At handover, no retry native/graphics/Java trace files have been provided yet. Last message is export error, not confirmed capture failure.

Build 903 archive was reopened: manifest PID 22413 throughout, browse at uptime 67527s / UTC 03:23:58; native-start same time; native START_FAILED; idle label uptime 67828s / UTC 03:28:59; collected; log_files=1. Both smaps_rollup attempts unavailable. native-perfetto.txt is empty. No native, graphics, or Java trace in archive. Exact Perfetto startup failure reason unknown. Browse snapshot is valid browse state; the idle-labelled snapshot is actually active playback, so do not use it as post-stop recovery or leak evidence. Separate app logs contain overlap and must be deduplicated by PID/time/segment. Version 1.0.1806, same replay content 1000010442. First timing load logged 08:52:42.932 IST, preview 60ms, core 510ms from cache; warm result, not cold. 61 main-test Rust segments and exact four selected renditions reported; validate dimensions and window in source logs again when available. Clear archive experimental SurfaceView, not protected acceptance.

Exact archive meminfo:
- browse TOTAL PSS 211199 KiB (206.25 MiB), graphics 16840 KiB, Java 28312 KiB, native 30692 KiB.
- mislabeled idle TOTAL PSS 706728 KiB (690.16 MiB), RSS 849396 KiB, graphics 259384 KiB (EGL 234484 + GL 24900), Java 205584 KiB, native 85164 KiB, LOS 134759 KiB (131.60 MiB). Not a post-stop measurement.
- Native trace missing, Java retained graph missing, physical frame timeline missing. No exact vendor graphics/codec attribution. No actual leak or reduction gate claim.

Previous chat's preliminary comparison (reproduce from raw logs, same windows/filters before engineering; not matched controlled acceptance):
| Metric | 901 | 903 |
|---|---:|---:|
| Median playback PSS MiB | 655.1 | 646.9 |
| Median graphics PSS MiB | 256.5 | 253.3 |
| Exact native output reuse rate | 58.6% | 71.0% |
| Peak native fragment capacity MiB | 34.3 | 27.0 |
| Median segment preparation ms | 2660 | 1401 |
| Median compose ms | 38 | 40 |
| Dalvik LOS snapshot MiB | 128.4 | 131.6 |

903 stable refresh windows previously reported UI slow/deadline-miss fraction 12.9% at 60Hz and 57.6% at 120Hz. Verify the metric definition and filter for active steady playback, phase/temperature and actual refresh; app-window lateness is NOT decoder drops or physical video scanout. No formal proof of refresh change regression/improvement across different windows. Native reuse and lower capacity are promising, overall PSS remains ~650 MiB, graphics/Java dominant; network prep improvement not necessarily caused by code. 903 logs provide per-feed queue/read/bytes. Example segment 860 readMs=1039,774,1032,1465, queueMs=0,0,1,1, groupElapsedMs=1468, composeMs=72, totalMs=1554. Segment 861 queueMs=0,0,1,1, readMs=694,482,964,487, groupElapsedMs=967, totalMs=1051. This suggests fetch service time dominates those examples, not thread queueing; audit all samples. Deferred retirement counters zero in those samples, do not attribute measured reuse gain solely to that fix.

901 reference: one hardware c2.qti.hevc.decoder; clear SurfaceView scene atlas 1920x2176 to 2316x1080; 21/21 Rust native fragments; 24 allocations/34 reuses/58 adoptions; 16 fit, 6 pinned, 2 disabled misses; 12 active samples PSS median 655.127 MiB (634.054–666.936), graphics 256.523, Java 169.025, native 76.787, Media3 in use 76.812, native engine payload 7.867, arena 25.558. Three parked Media3 players zero allocations. EGL+GL vendor bytes unmeasured by app registry. Codec BufferPool 39,178,240 bytes is overlapping, not additive PSS. After exit and HWUI trim, PSS ~193 MiB; one cycle not endurance proof. GL 5764 latches/5701 submissions, median ~50/~49.3fps; no decoder drops, 63 coalesced; exact codec timestamp matches only 771, physical scanout unknown. Native trace only one snapshot, buffer overrun/disconnect/client TIMEOUT, 300 unwind errors; not trustworthy allocation-site comparison. 901 user confirmed warm map/tower stopped blinking. 895/896 prior matched-ish PSS ~652/637 MiB; graphics ~247/252, compose ~42ms; different onboard and renderer, no controlled acceptance. Historical 896 TV produced 12 JVM segments despite Rust requested, admission denied four times; physical Rust TV activation not verified by 903 phone test. Keep that gate open.

## 6. Immediate next step: recover existing retry traces, no reinstall or repeat yet

User reported cp Unknown option h at Step 5 (prior block included ls -lh). Likely command concatenation in aShell; not proven. Run each command individually, never paste multiple commands into the same invocation. Check source before copy; if missing/zero, send exact output. Do NOT overwrite existing valid trace with a new capture. If exported already, just upload files.

```sh
ls -l /data/misc/perfetto-traces/pitwall-903-native.perfetto-trace
```
```sh
ls -l /data/misc/perfetto-traces/pitwall-903-graphics.perfetto-trace
```
```sh
ls -l /data/misc/perfetto-traces/pitwall-903-java.perfetto-trace
```
```sh
cp /data/misc/perfetto-traces/pitwall-903-native.perfetto-trace /sdcard/Download/
```
```sh
cp /data/misc/perfetto-traces/pitwall-903-graphics.perfetto-trace /sdcard/Download/
```
```sh
cp /data/misc/perfetto-traces/pitwall-903-java.perfetto-trace /sdcard/Download/
```
```sh
dumpsys meminfo -a app.apexview_custom > /sdcard/Download/pitwall-903-playing-memory.txt
```
```sh
ls -l /sdcard/Download/pitwall-903-*.perfetto-trace
```

Memory snapshot only if playback is actually active. If already stopped, label appropriately, do not misclassify or require restarting solely to collect another snapshot if valid evidence exists. Export all fresh log segments separately. Record PID, version, replay/playhead, exact source/decoder/renderer/rate, phase start/end, thermal and display mode. A file size alone is not validity. Missing traces, unsupported producers, permissions, truncation must be reported explicitly. User should share trace files and logs rather than screenshots of normal playback. If aShell still merges arguments, issue one exact command at a time and inspect stdout/stderr. No new APK necessary for export.

## 7. Retry configs and commands, only for missing/invalid captures

Original docs/perfetto/capture-root-cause.sh stdin launch failed on this device with empty error output; do not keep repeating it. Separate file-based configs were prepared in pitwall-903-retry-configs.zip. These are exact content from that ZIP, not yet physically confirmed. Save each named file in Download, copy individually to /data/misc/perfetto-configs/ using shell, inspect permissions/errors. Use unique output filename if prior capture exists. No root, no bypass of OS security. If --background-wait fails, capture exact stderr/help and use supported foreground/file-based invocation if feasible; do not guess. Start logging before replay, record phases and PID. Do not run profilers simultaneously; Java graph pause excluded from cadence benchmark.

### pitwall-903-native.cfg
```text
buffers { size_kb: 32768 fill_policy: RING_BUFFER }
data_sources { config { name: "android.heapprofd" target_buffer: 0 heapprofd_config {
  process_cmdline: "app.apexview_custom" sampling_interval_bytes: 131072
  shmem_size_bytes: 33554432 block_client: false
  continuous_dump_config { dump_interval_ms: 10000 }
} } }
duration_ms: 90000
write_into_file: true
file_write_period_ms: 5000
max_file_size_bytes: 134217728
```

### pitwall-903-graphics.cfg
```text
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" target_buffer: 0 ftrace_config {
  ftrace_events: "gpu_mem/gpu_mem_total"
  ftrace_events: "kmem/rss_stat"
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_wakeup"
  atrace_categories: "gfx" atrace_categories: "video" atrace_categories: "dalvik"
  atrace_categories: "view" atrace_apps: "app.apexview_custom"
} } }
data_sources { config { name: "linux.process_stats" target_buffer: 0 process_stats_config {
  scan_all_processes_on_start: true proc_stats_poll_ms: 1000
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 0 } }
duration_ms: 60000
write_into_file: true
file_write_period_ms: 2500
max_file_size_bytes: 268435456
```

### pitwall-903-java.cfg
```text
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "android.java_hprof" target_buffer: 0 java_hprof_config {
  process_cmdline: "app.apexview_custom" dump_smaps: true
  continuous_dump_config { dump_phase_ms: 10000 dump_interval_ms: 10000 }
} } }
duration_ms: 25000
write_into_file: true
file_write_period_ms: 2500
max_file_size_bytes: 268435456
```

Commands (individual invocations, only missing phases):
```sh
cp /sdcard/Download/pitwall-903-native.cfg /data/misc/perfetto-configs/
```
```sh
cp /sdcard/Download/pitwall-903-graphics.cfg /data/misc/perfetto-configs/
```
```sh
cp /sdcard/Download/pitwall-903-java.cfg /data/misc/perfetto-configs/
```
```sh
perfetto --background-wait --txt -c /data/misc/perfetto-configs/pitwall-903-native.cfg -o /data/misc/perfetto-traces/pitwall-903-native.perfetto-trace
```
Return to app and play >=95s, then if required:
```sh
perfetto --background-wait --txt -c /data/misc/perfetto-configs/pitwall-903-graphics.cfg -o /data/misc/perfetto-traces/pitwall-903-graphics.perfetto-trace
```
Play >=65s, then if required:
```sh
perfetto --background-wait --txt -c /data/misc/perfetto-configs/pitwall-903-java.cfg -o /data/misc/perfetto-traces/pitwall-903-java.perfetto-trace
```
Play >=30s; export individually as above. For actual memory recovery stop playback, confirm no active scene/codec, measure +30/+60/+120s and report phase/PID. For a new test keep same replay, 1080+3x540 exact, Rust ON, single decoder, both overlays, SurfaceView experiment ON, adaptive 120Hz, 1x; fresh process when comparing matched runs. Do not claim these were the settings for an unobserved retry. Do not include profiled periods in unprofiled PSS acceptance. Review trace metadata before sharing. No raw video or object field values requested.

## 8. Prioritized next engineering workstreams and actual root-cause proof

| Priority / IDs | Existing candidate and evidence | Required next action |
|---|---|---|
| P0 M1/M13/G-C4, G-C6/G-TV8 | Stable graphics owner IDs, peak contexts/windows/textures, CREATE/CHANGE/RELEASE, no exact vendor bytes | Correlate native allocations, GPU process counters, SurfaceFlinger layer IDs, codec pool identity/size, EGL/HWUI ownership and lifetime. Separate driver/internal buffers from app-owned avoidable ones; do not claim GPU owner attribution if inaccessible. Fix only confirmed redundant resources/lifetimes. |
| P0 M2/M3/G-C5 | Bounded exact range reads, heap owner detail, compact replay publication, final-reader retirement | ART graph dominators + GC roots + byte[] length/retained sizes + Java/native timing; Media3 allocator active ~77 MiB previously, free pool and sample queue split NOT_EXPOSED. Distinguish Media3 live blocks, source arrays, replay primitive histories, images, cache, parked players; inspect strong refs in closed leases. Fix proven copy/retention/ownership, preserve required buffered media. No blind allocator replacement. |
| P0 G.24/P0.3/P1 cadence | Overlay rearm source; 120Hz UI deadline misses persist | Use actual graphics trace, intended vsync/FrameTimeline, GL latch/swap, scheduler runnable delay, RenderThread/GPU, UI phase and queue IDs. Compare 60/120Hz under identical renderer/thermal/quality. Avoid forcing 60Hz or reducing quality merely to pass. Verify displayed video 50fps/75fps policy separately. |
| P0 G-C2/M4–M8 | Rust fit/pin counters, near-size bounded resize, reuse up to 71%, peak fragment capacity ~27 MiB | Inspect exact fit/too-small/too-large/pinned/disabled, resize same/moved/fail, actual backing capacities and release. Earlier deferred retirement cumulative counter can be zero in native-cache path; verify applicability. Preserve immutable pinned data, no cap growth without evidence. |
| P1 G.18/G.19 | per-feed queue/read/bytes, median prep improved; source reads dominate examples | Attribute each bottleneck with matched per-feed timings, requests, slack, 1x/1.5x and seeks; preserve exact quality and buffering. Keep existing deadline and concurrency policy unless measured change justified. |
| P1 G.22/G.23/G-TV3 | warm startup 901 user-confirmed, 903 cache preview/core timings | Validate cold cache, independently ready map/tower, no blink/zero rows, clock seeks, authoritative OUT/retired vs actual resumed driver, mini-sector order, cast timeline. |
| P1 M1/G-C6 | strict heap packet continuity, v5 lower overhead; script native START_FAILED | Validate new traces by actual parse, correct PID, snapshots/time and stack symbols; fix capture command reliably. No third round of broad diagnostic changes without cause. |
| P0 G-TV1–8/G.19/G-C3 | controller-only source green; TV prior Rust fallback and Cast physical not validated | Test real TV Rust activation/Software DRM, both Cast routes and multi-feed. Track localVideoSubmissions/castVideoSubmissions, CAST_LOCAL_PREVIEW_BLANKED, remote first frame, audio, queue/transport state and exact restoration. Baked phone producer may remain; local presentation must not. Fix specific regressions in one batch. |

M1–M13, G-C1–8, G-TV1–8, G.1–24, R1–8, P0–P2, A–H, P0-A–P1-F remain tracked through master ledger; this priority table supplements, not replaces, the detailed mappings. Retain all current source candidates until demonstrated defect. Do not label 100% physical complete based on code coverage. User wants actual solution, not another round of vague instrumentation. When no supported vendor-level accounting exists, state precisely which data is inaccessible and use bounded experiments with known ownership rather than fabricating byte totals.

## 9. Analysis tooling and acceptance

Repository scripts:
- docs/perfetto/ASHELL.md, capture-root-cause.sh, capture-memory.sh, pitwall-heap-only-v5.cfg, pitwall-memory.cfg, pitwall-50fps.cfg, frame_timeline_export.sql
- scripts/analyze_pitwall_runtime.py --build 1.0.1806 (inspect --help for exact arguments); analyze_pitwall_bufferqueues.py; analyze_pitwall_frames.py; analyze_pitwall_overlay_ab.py; check_pitwall_memory_trace.py; pitwall_acceptance_matrix.py; validate_pitwall_acceptance.py.
- Native integrity: python scripts/check_pitwall_memory_trace.py TRACE --strict-heap --pid ACTUAL_PID --min-snapshots 3 (prefer >=8 for 90s when supported; consult --help); reject error/disconnect/overrun, packet index gaps, missing trusted sequence, incomplete continuation, mismatched PID and inadequate snapshots. Count distinct PID/timestamp; verify exact ELF Build ID and meaningful unwind/symbolization separately. Profile size and presence alone insufficient. Use official Perfetto trace_processor and heap graph tables/dominator paths if available. Check trace stats, producer coverage, data loss and actual time bounds. Separate native sampled estimates from exact live ownership and Java retained sizes; no PSS/RSS overlap summation or ungrounded subtraction. Use exact signed symbols artifact. Report if tool unavailable; do not pretend trace was parsed.
- Match source window/PID/playback clock, dedup overlapping captures, exclude startup/seek/ART profile/foreground changes from steady statistics; report median, peak, p95/p99, sample count, intervals, actual resolutions, decoded/GL/display counters separately. Trace and app uptime clocks may differ; correlate explicitly.
- Unprofiled matched Rust ON/OFF same signed build, same feeds/resolutions/layout/renderer/DRM/cache/playhead/rate/network/thermal and actual refresh; alternate order >=3 pairs >=60s each where practical. Cold/warm start separately; map OFF/MAP/TOWER/BOTH, 1x/1.5x, seek/audio/CC, stop +30/60/120s. 10-minute overlay/rate matrix plus >=2h endurance phone and TCL, single/multiple decoder, clear/authorized protected live/replay. Only request test subset now needed; no repetitive blanket requests.
- Targets from master: Rust steady median PSS <=80% of matched Kotlin, lower peak/no sustained growth, >=30% stitching CPU reduction, >=90% avoidable overlay allocation churn reduction; 50fps at 1x, supported 75fps at 1.5x, no A/V/quality/DRM/feature regression. TV first visible <=15s warm/30s cold; p95 producer <5.76s at 1x/<3.84s at supported 1.5x; warm map/tower <=5s, cold aim <=10s; prepared Cast first media aim <=10s. No fake gate pass. Preserve existing targets and policy.
- Cast after memory capture: test Pitwall and Google separately, single and multi-feed, overlays, actual TV playback, local no motion/audio, controller screen and static layout editing, pause/resume/seek, source quality, commentary/CC, trusted reconnect/manual disconnect/handback, screen off/background, full race. Export logs separately. Direct local decoder/track retirement vs baked off-screen producer distinction mandatory.

## 10. CI and direct branch workflow

Read .github/workflows/android-debug-apk.yml and scripts/pitwall_change_contract.py. Existing source changes need tests and both master ledgers in same consolidated final source commit, preserve exact-source contracts, four ABI builds, signed upgrade from exact Build 843, strict Clippy, host/sanitizer, JNI byte-exact clear/protected parity, Rust TS fixture FFmpeg/ffprobe independent decode, JVM and Android API29/31/36 instrumentation, encrypted internal stage and fail-closed final publication. Commands:
```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/pitwall_change_contract.py
bash -n scripts/run_rendernode_instrumentation.sh
node tools/test_cast_receiver.mjs
node tools/test_cast_layout_receiver.mjs
node --check web/cast/receiver.mjs
cargo test --locked --manifest-path native/architecture-g/Cargo.toml
```
Use exact pinned workflow commands for Clippy, sanitizer, Gradle, all four ABIs, independent decode and signing; do not claim local toolchain execution if unavailable. Check actual annotation count vs runner expectations. Keep tools/test_cast_receiver.mjs, tools/test_cast_layout_receiver.mjs, native/architecture-g/src/bin/baked_ts_fixture.rs, tools/validate_baked_rust_ts.py, BakedRustTransportParityTest.kt, scripts/verify_architecture_g0_apk.sh, architecture_g0_build_id.py, architecture_g0_stage.py and all test scripts. Never trigger unrelated release/Pages deployment. GitHub direct API: fetch ref and commit/tree, exact source blob, create blob/tree/commit, update ref expected_sha=old head force=false, verify resulting head and diff. If conflict read new head and reconcile, no force push. Docs-only handover [skip ci]. No repeated micro-builds. Report actual run/job conclusion and exact signed artifact only after all five pass.

## 11. Completion and current blockers

Current task is evidence recovery and analysis, not another speculative implementation or APK. Pending: retry trace files, reliable native/Java/graphics ownership attribution, unprofiled controlled acceptance, cold timing startup, true TV Rust activation, actual both-protocol controller-only Cast, protected/live/multiple-decoder/seek/quality/audio/CC and full endurance. Existing 903 app logs are useful and must not be discarded. The previous chat's numerical comparison is preliminary and should be revalidated, not treated as independently controlled measurement. The prior export diagnosis (aShell merged commands) is a plausible explanation, not verified. No production code changed by this handover. Update master plans after actual new engineering or verified physical evidence. Return concise next action, then concrete root-cause findings and one consolidated implementation with tests, exact commit/run and separately identified physical gates. No promises of background work.
