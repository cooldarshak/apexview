# Pitwall / ApexView — Build 911 M14–M23 CI failure and physical acceptance handover
Date: 2026-11-27

## 1. New-chat execution prompt

Read this entire file, the current master engineering plan and Architecture G handover listed below. Work directly in the existing repository and branch. Verify the latest branch HEAD and actual GitHub Actions status before changing anything. Resume from the M14–M23 consolidated checkpoint; do not restart Architecture G or repeat completed investigation. First resolve the exact Build 911 CI failure and audit the related prefetch wiring in both clear and protected paths. Complete all unconditional corrections and regression tests before triggering a single consolidated build. Preserve every feature and security requirement. A green build is a prerequisite, not acceptance: physical memory and functionality must improve against the matched Build 910 Rust baseline.

## 2. Repository and CI checkpoint (verified during handover)
- Repository: cooldarshak/apexview
- Branch: feature/architecture-g-rust-stitching
- Engineering HEAD before documentation-only handover: 6e5b832ebc97b1b9d2979af1d940a82b4c8e9ce0
- Build 911: completed, failure; https://github.com/cooldarshak/apexview/actions/runs/45997875354
- Failed job: Test and build debug APK, 140501738620.
- Last green build: 910, ee4025fd6d10634fd6c5381acd9e3cd54f604eed, https://github.com/cooldarshak/apexview/actions/runs/45971461424
- 909 green but had physical startup regression; 910 addressed it. 908 and 907 failed; 906 green; 905 failed; 904 and 903 green.
- Prior batch reported five commits (four skip-CI, one trigger), 30 files and 678 changed lines. These counts are historical report, not a fresh diff audit.
- This handover is documentation only; use [skip ci]. No source fix, retry, or new APK is claimed from the handover itself.

## 3. Exact CI failure and next action
The job failed at "Validate physical FrameTimeline comparison tool", which also runs the Python production-wiring tests. 122 tests ran, one failed. Rust native host suite reported 103 passed, zero failed; native/ABI feasibility and baked AVC/AAC transport fixture steps passed. Gradle unit tests, APK assembly, instrumentation and final publication were skipped. Thus no signed Build 911 APK is available from this run, and compilation of all Android changes is unverified.

Failure:
- scripts/test_pitwall_production_wiring.py:383
- ProductionWiringTests.test_production_cadence_audio_seek_thermal_and_async_log_wiring
- Expected literal: val memoryWarmLimit = if (constrained) 0 else ArchiveBufferPolicy.memoryWarmLimit(warmLimit, headroom, observed.input, observed.output)
- Actual clear archive source (app/src/main/java/app/apexview/tiled/HevcArchiveVodSession.kt): computes localLimit = ArchiveBufferPolicy.localWarmLimit(warmLimit, nativeDemand, android.os.SystemClock.elapsedRealtime(), owners.get() == 1 && !baseReleased.get()), then passes localLimit into memoryWarmLimit.
- This establishes a stale source-wiring assertion at the first failure, not proof that all new production behavior is correct.

Required next steps:
1. Inspect full test method, ArchiveBufferPolicy, clear/protected session paths and tests; preserve thermal/OS pressure gates and existing cache/lease admission checks.
2. Update the stale contract to positively verify the new demand-aware localLimit and its wiring, not merely delete or weaken the assertion. Review subsequent assertions for both session variants. Examine in-flight warm worker re-evaluation: existing clear worker still uses warmLimit in the per-candidate memory check; determine if fresh demand must be re-evaluated to enforce M19 without disrupting foreground, stale epochs, shared Cast owners or seeks. Change only with a demonstrated contract gap.
3. Run full Python suite (including production wiring), Rust tests and applicable Android tests where tooling exists; if remote-only, report that distinction. Do not assume the first red assertion is the only possible failure.
4. Audit M14–M23 source changes and regressions before opening the consolidated build gate. Do not revert the intended demand-aware prefetch policy to satisfy a string assertion.
5. One consolidated build, then verify all jobs and signed artifact, including exact-source Android API 29/31/36 instrumentation and final publication. No repeated speculative micro-builds.

## 4. Authoritative plan files
Read in full on the current branch:
- docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md (master; includes original 16 P0–P2 workstreams and Build 910 evidence-driven M14–M23 checkpoint).
- docs/handovers/PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md (Architecture G master, same latest checkpoint).
- docs/handovers/PITWALL_TV_BUILD_903_ROOT_CAUSE_AND_PHYSICAL_ACCEPTANCE_HANDOVER_2026-11-27.md
- docs/handovers/PITWALL_TV_BUILD_896_MEMORY_ENGINEERING_HANDOVER_2026-11-26.md
- docs/handovers/PITWALL_TV_BUILD_896_POST_RESEARCH_CONSOLIDATED_CONTINUATION_2026-11-26.md
- docs/handovers/PITWALL_TV_BUILD_896_FINAL_SCOPE_CONTINUATION_2026-11-26.md
Follow all additional references in the masters; reconcile Architecture G G.1–G.24, G-TV1–8, G-C1–8, R1–8, M1–23, original P0–P2, MEM-01–16 and earlier batch scopes. Older handover names are historical references; verify their existence and use the authoritative masters if a historical file is unavailable. Do not conflate M14–M23 with MEM-14–16 acceptance items.

## 5. M14–M23 implementation checkpoint
Source-level implementation recorded in both masters. CI and physical acceptance are not complete.

| ID | Implemented design | Verification requirement |
|---|---|---|
| M14 | Caption-aware measured Media3 forward buffer policy, 25–50 s range, six fresh observations per selected source and producer | Captions enabled and actually visible; normal fallback on unknown/slow/sparse sources, unsupported speeds, seeks, errors and remote sessions; no rebuffer regression |
| M15 | Actual selected child audio/video/text load events, installation tokens and upstream fragment production time | Accurate source attribution, fresh windows, no inferred track class, stale source or duplicate latency accounting |
| M16 | EGL thread state retirement, scene geometry and tower RenderNode key release | Graphics PSS recovery after 120 s, correct context/thread ownership, no GL/DRM regression |
| M17 | Cold provisional decoder deferral on phone and TV; bypass intentional display deferral when cadence unhealthy | No startup deadlock, decoder contention, queue timeout or pacing regression; preserve fallback |
| M18 | Rust fragment capacity matching for measured 7.6–9.5 MiB output range within existing slack/ceiling/idle permit | >=85% reuse target; never raise arena ceiling or violate leases |
| M19 | Retire fully delivered past local-clock segments in matching seek epoch after last reader; restrict second speculative warm group when fresh advancing Media3 buffer >=25 s | Full/partial deliveries, seek and stale epochs, Cast leases, rematerialization and pressure; current CI blocker above |
| M20 | Map draw-loop allocation reduction and retained tower keys; unchanged tower polls return previous immutable publication | Smooth map/tower, exact telemetry, changes and seeks still publish; no stale visual state |
| M21 | Evidence-aware display pacing; no new frame thinning | 50 fps at 1x, 75 where supported at 1.5x; adaptive display and FrameTimeline/BufferQueue comparison |
| M22 | Rust exact 16-bit dictionary records for completed driver timing histories, deduplicated domain lists/cells/strings, reconstruct requested row, direct retirement flags | Lossless parity including mini-sectors, seeks, live/unsupported fallback, correct registry accounting and actual net PSS reduction |
| M23 | Reuse immutable finite MPD backing between preflight and playback, cold decoder deferral | Correct manifest lifetime, fast startup and seeks; captions, DRM and independent Cast source retained |

## 6. Physical evidence and baseline
Build 903: 594.9 MiB PSS in one snapshot (graphics 255.1, Java 149.1, native 76.9, other 113.8), RSS 679.8 MiB. Other playback samples near 650 MiB. Java reference paths identified ~79.1 MiB Media3 allocated blocks, 11.5 MiB map history and 11 MiB speed/gear timestamps, 4.6 MiB available pool; these are not exclusive retained dominators. Native sample ~26.1 MiB composed backing. Graphics dropped from 255.3 to 66.0 MiB after release, not proof of a persistent leak. One decoder and 50 fps, 27,403 rendered frames and two decoder drops in ~9 min 8 s, yet activity FrameTimeline 84.31% late/janky, 64.88% buffer stuffing, 24.57% app deadline misses (overlapping categories, not physical scanout). Selected 60/120 Hz windows had 8.41%/66.42% slow app-window frames, not a controlled A/B. Accessibility main-thread work ~10.7 s per 60 s, p95 12.19 ms. Build 903 is historical context, NOT matched A/B with 910.

Build 910 physical Rust four-feed archive is the immediate baseline:
- 663.5 MiB playback PSS.
- ~79 MiB active Media3 sample capacity; ~84.4 MiB reachable allocation arrays per master (different accounting).
- One hardware HEVC decoder; captions prevented adaptive buffer policy qualification.
- Graphics retained above idle: 57 MiB (historical reported baseline).
- Rust reuse: 55.9%; output 7.6–9.5 MiB.
- Startup 8.19 s, median seek 1.56 s.
- 136 provisional and 1,312 composed queue timeouts reported.
- Raw previous evidence names: Pitwall-Rust-evidence.tar.gz, pitwall-20261127-112854-729.log.txt, pitwall-20261127-112632-140.log.txt; earlier Pitwall-evidence-rust903.tar.gz and logs. Retrieve actual attachments from previous project chats if available; these filenames are references, not proof of access. The two chat-export PDFs retain historical analysis, not complete raw evidence or every prior response.
- Prior diagnostics on aShell: multiline commands concatenated, mkdir option error, missing variable led to /start.txtdumpsys read-only error, media.codec service absent. Future commands must be paste-safe single lines with explicit absolute paths under /sdcard/Download/Pitwall, semicolon separation or separate commands, service discovery and nonfatal unavailable service handling; never assume root.

## 7. Acceptance targets and test methodology
| Metric | Build 910 reported | Acceptance |
|---|---:|---:|
| Steady playback PSS | 663.5 MiB | <=550 MiB |
| Active Media3 sample capacity | ~79 MiB | 45–55 MiB |
| Graphics retained above idle at 120 s | 57 MiB | <=20 MiB |
| Rust reuse | 55.9% | >=85% |
| Startup | 8.19 s | <5 s |
| Median seek | 1.56 s | <1 s |

550 MiB requires ~113.5 MiB / 17.1% reduction from 910. Engineering goal 450 MiB; stretch 400 MiB, not promises. Maintain historical matched Kotlin-baseline targets where evidence already exists; user explicitly refuses fresh Kotlin testing. Use Rust-only Build 910 versus next green build, same device/content/feeds/qualities/layout/captions/DRM/network/refresh/thermal state, 1x and 1.5x. Compare repeated idle, cold start, stable playback, seeks (forward/backward/lap), post-release at 120 s, and >=2h endurance phone/TV. Record PSS/RSS, Java/native/graphics separately, allocator used/free backing, Rust committed/live/pool/reuse, queue timeouts, actual frame delivery, thermal and CPU; do not sum overlapping GPU/codec or heap accounting. Record exact build SHA, version, timestamps, content and conditions, distinguish peak from steady state. Keep original output resolution and caption configuration. A reduction due to missing feed, caption, failed playback or quality downgrade is invalid.

## 8. P0 functionality that remains subject to physical acceptance
- Samsung Galaxy S22 Ultra (current captured Android 16/API 36) and TCL 55C655 Android TV 12. TV historically requires Software DRM ON; preserve that path and supported hardware paths.
- TV single feed + map + tower worked on 903. TV multifeed single decoder, Pitwall Cast and Google Cast failed. Earlier source corrections are not proof of device success.
- TV startup evidence: ~58 s from composition to admission, ~46 s download-to-admission delay, ~477 MiB headroom above system threshold, 5,235 vendor drop-frame log entries, no published Rust fragment. Prior corrections: native byte-exact parity comparison using bounded 16 KiB scratch, independent reference validation retained, less provisional decoder competition.
- Pitwall Cast: connection/load/AVC decoder initialized, sender closed ~2.6–3 s after load. Google Cast: baked segments served, commentary DNS failure and Invalid Request observed. Prior source changes include rolling-live starting position, transactional audio cleanup/retry, controller confirmation. Verify actual moving video and audible commentary, not just connection or LOAD acknowledgment.
- Retired drivers (reported Lindblad) must disappear on map at correct playback timestamp, support genuine reactivation and seek/live consistency; prior universal Rust/reference retirement changes and cache invalidation. Never hardcode driver/race.
- Mobile Back must exit from Home after normal navigation; prior MainActivity correction. Verify card and Continue Watching startup (909 regression), layout persistence, captions, per-feed offers (hide unavailable quality), map/tower readiness and update rate.
- Test single/multiple decoders, clear and legitimately entitled Widevine HEVC, live/replay, phone/TV, Google Cast/Pitwall Cast; full baked map/tower/commentary, captions where supported by existing path, no regression. One composed HEVC stream -> one Widevine session -> one MediaCodec decoder -> one output surface in single-decoder mode; no weakening DRM or independent validation. Preserve controller-only phone behavior while casting and existing receiver codec contract.

## 9. Hard rules and completion
Rust for all applicable processing; Android/Media3/EGL/JNI lifecycle boundaries remain platform-managed where required. No speculative allocator swap, forced vendor GPU controls, arbitrary codec buffer limits, forced 60 Hz, frame thinning, lower resolution, reduced selected feeds, dropped captions, or DRM/login bypass. Preserve legitimate F1 TV entitlement, all layouts/UI/accessibility, captions and controls, commentary, both decoder modes and both Cast paths. No proprietary SDK or paid OpenF1 dependency. Work and commit directly in this branch; no restart or unnecessary intermediate CI. Complete unconditional engineering work before one build. Evidence-gated vendor GPU attribution, steady BufferQueue pacing, thermal/power and device physical tests remain open. If targets are missed, use measured owner-specific evidence to plan one further consolidated batch, not generic diagnostics or unsupported savings predictions. Report coding, CI, physical acceptance separately. Do not claim 100% success until physical evidence supports it.

## 10. Handover limitations
This file reconciles the supplied prior chat summary, attached Build 903/910 conversation exports, current master checkpoint excerpts, actual branch ref and Build 911 CI job log. It is not a full new source review or raw trace reanalysis. The source-wiring failure was verified; no code was changed to fix it in this documentation pass. Exact individual vendor GPU allocations remain unattributed. No Build 911 physical measurement exists. Recheck all live repository and CI state in the next chat.
