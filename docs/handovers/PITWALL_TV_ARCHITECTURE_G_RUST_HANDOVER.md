# PITWALL TV / APEXVIEW: ARCHITECTURE G — CONSOLIDATED RUST ENGINEERING PLAN

**Current Architecture G checkpoint (25 November 2026):** Build 865 GREEN, Build 866 FAILED before Android instrumentation. User reaffirmed original targeted low-copy Rust architecture with strengthened all-applicable-stitching coverage, not full Rust rewrite. See the current scope clarification and G-B continuation below. G-C/D remain gated.
**Revision 2 | Dedicated Architecture G branch | 24 November 2026 | Build 842 historical verified GREEN baseline | Planning only**

> Instructions to the next engineering chat: read this entire document before acting. Continue the existing project in its existing working branch. Rust is the selected native implementation language. Start with Phase G.0, not a wholesale rewrite. Preserve all existing features and the original 16-workstream plan. Execute implementation only when instructed to proceed. This document is the single active Architecture G handover and engineering ledger; update it in place rather than creating competing G plans.

### Build 857 verified GREEN — current execution checkpoint (25 November 2026)

Build 857 [run 45539807100](https://github.com/cooldarshak/apexview/actions/runs/45539807100), commit 9dcbef0abc3fb92c35eb6c57d0db665cdb254fb1. Five jobs green including final signed publication. Stable Rust and pinned nightly ASan/LSan 22/22 each; 106 slice exact differential cases, three clear CMAF cases and existing independent decoder/pixel gates; four ABI symbols/ELF/ZIP alignment, signed upgrade, API29/31/36 G.0 4 tests and G-A 2 tests per API. Native bridge churn, device budget and lease/generation logs PASS; existing original tests retained. APK 14989576250; test APK 14989281975; symbols 14989211632; host evidence 14987924146; combined runtime 14989436492. G.0 signed off by user; G-A foundation coding and automated verification complete. **G-B next when explicitly instructed.** Existing diagnostic kernels and clear conversion dispatch do not amount to production Rust HEVC/CMAF stitching. Setting default OFF, actual JVM all paths, clear/protected native route pending. Physical TCL, ARM G-A, matched full playback/DRM and 16KB runtime gates carried forward, not waived. See [new detailed Build 857 handover](PITWALL_TV_BUILD_857_ARCHITECTURE_G_HANDOVER_2026-11-25.md). Maintain this file as canonical G ledger; new handover is a dated snapshot, not a competing plan.

## 1. Project context and exact starting checkpoint

- Repository: `cooldarshak/apexview`
- Working branch for all Architecture G work: `feature/architecture-g-rust-stitching`
- Parent branch (leave unchanged): `feature/pitwall-performance-audit-plan`
- Exact branch creation checkpoint: `1c479fd6fa6ab5d8b0809d3e37f51ce6336a9028` (inherited Architecture G handover; documentation checkpoint, not a new signed APK).
- This revision supersedes the inherited Architecture G handover **on this branch only**. Maintain this file as the single active G plan/ledger. Do not merge to either parent or main without explicit authorization.
- Stable branch: `main`
- Verified working-branch production commit at handover: [b9752ac9115b252c9de1f444662b8eb1252e751e](https://github.com/cooldarshak/apexview/commit/b9752ac9115b252c9de1f444662b8eb1252e751e).
- Latest verified successful signed APK: [Build 842](https://github.com/cooldarshak/apexview/actions/runs/45432244291). GitHub Actions completed successfully, including Kotlin/JVM tests, independent HEVC/CMAF decode and pixel comparison, signed APK assembly/upload, Android RenderNode/TextureView/SurfaceView and baked capture instrumentation, archive delivery and mutual TLS/legacy identity migration on API 29, 31 and 36.
- Main at handover: `7d42ae9b3c9178f838717700586bfb7fb50f4ec7`. Do not merge, move or modify main unless explicitly authorized. A subsequent documentation-only commit is not a new tested APK.
- Authoritative parent plan: [PITWALL_PERFORMANCE_AUDIT_PLAN.md](../PITWALL_PERFORMANCE_AUDIT_PLAN.md). Read all current requirements and still-relevant historical technical constraints, not just the opening status table.
- Supporting documents: [Build 839 handover](PITWALL_TV_BUILD_839_ENGINEERING_HANDOVER.md), [clear/Widevine parity and named layouts](../CLEAR_WIDEVINE_PARITY_AND_NAMED_LAYOUTS.md), [single-decoder layouts and Cast](../SINGLE_DECODER_LAYOUTS_AND_CAST.md), [HEVC stitching references](../HEVC_STITCHING_REFERENCE_REVIEW.md), [CMAF inspector](../HEVC_CMAF_RUNTIME_INSPECTOR.md), [clear CMAF packaging](../HEVC_CLEAR_CMAF_PACKAGING.md), [50fps profiling](../PITWALL_50FPS_DEVICE_PROFILE.md), [aShell/Perfetto](../perfetto/ASHELL.md) and [local TV receiver](../LOCAL_TV_RECEIVER.md).

Re-fetch branch refs, latest commits, plans, exact CI logs/artifacts and source at the beginning of the new chat. The baseline above is a historical checkpoint, not a guarantee that no new build exists. Never restart the project or create a separate application.

### Previous 14-item batch and 16-workstream status

Build 842 includes the identified coding changes for the 13 selected items; item 6 (Architecture G) was intentionally deferred. This is implementation coverage, not blanket 100% release certification. Build 841 failed real AndroidKeyStore mutual TLS; Build 842 corrected missing DIGEST_NONE for opaque EC TLS signing, migrates old keys once, preserves non-exportable identity and peer pinning, and passed the expanded instrumentation. Affected users may need to pair again following migration. Build 842 also moves baked MPEG-TS mux/publication off the GL callback into a bounded worker, requires 12 seconds of startup media, fences audio cleanup, starts archive CarData on a partial timing preview and tolerates malformed entries. Earlier Build 840/841 improvements include ownership/correlation, cadence, CMAF inspection reuse, warm delivery priority, TV defaults, layout and driver status. Preserve these changes.

| Item | Current source/CI status | Remaining evidence |
| --- | --- | --- |
| 1 TLS | Implemented; Android 10/12/16 loopback TLS tests green | Actual Android 12↔16 phone/TV pairing, reconnection and upgrade |
| 2 Baked HLS buffering | Bounded asynchronous publication and startup/retention policy implemented | Sustained receiver playback, seeks, buffering |
| 3 GPU capture | Independent overlay acquisition, adaptive pacing, timing diagnostics | Physical throughput and overlay parity |
| 4 Local presentation/audio | Receiver-confirmed suppression and safe worker retirement | Recovery and A/V continuity |
| 5 TV HEVC throughput | CMAF reuse, priority and composition slack improvements | Actual TCL throughput and GC attribution |
| 6 Architecture G | Deferred in previous batch; Rust now selected for the next phase | G.0 and G.1–G.20 |
| 7 BufferQueue | Surface/codec ownership, timestamp correlation, bounded probes | Physical SurfaceFlinger/FrameTimeline attribution |
| 8 Frame scheduling | Actual cadence and refresh transition corrections | 50/75fps, 60/120Hz, A/V and thermal measurements |
| 9 TV map lock | Auto-hide and focus/chrome reveal | Real D-pad navigation |
| 10 Main + strip | Landscape main-left 2/3, right strip 1/3; portrait bottom | Saved/local/Cast geometry |
| 11 Driver status | STOP/OUT separation and resume | Live/replay/receiver evidence |
| 12 Gear telemetry | Independent CarData, sparse gear data, retries and session fences | Actual F1 archive/live coverage |
| 13 Device defaults | Low-RAM TV 540p, other TV 720p, phone Auto when unset; preferences preserved | Real rendition and thermal results |
| 14 Verification | Exact Build 842 automated suite passed | Matched physical acceptance matrix |

The original 16-workstream plan remains authoritative for non-G work and release acceptance. The current source coverage must not be confused with a requirement-by-requirement closure audit; strict release acceptance at the checkpoint is 1/16 (previously accepted core scope). Do not overwrite it with invented percentages.

| Original ID | Workstream | Relationship to G / baseline |
| --- | --- | --- |
| P0.1 | Segment priority and exact seek | Existing fixes; G.7, G.13, G.14 extend measured delivery |
| P0.2 | HEVC memory and delivery | G.1–G.12, G.15–G.18; native migration deferred until this project |
| P0.3 | Video rendering | Preserve current surface ownership and diagnostics; native processing is not a substitute for physical BufferQueue analysis |
| P0.4 | Map and timing tower | Existing fixes; preserve and test |
| P1.1 | HEVC atlas | G.3–G.5, G.16, G.20; preserve compatible atlas geometry and decoder count |
| P1.2 | Refresh matching | Preserve 50/75fps scheduling; measure separately |
| P1.3 | Quality and thermal | G.17–G.18 plus current defaults |
| P2.1 | Pitwall/Google Cast | Preserve authenticated local Cast and baked overlays; validate separately |
| P2.2 | Coordinator architecture | G.6, G.7, G.13 integrate with current ownership and cancellation |
| P2.3 | Release and updates | G.20, same package/signing/update behavior |
| P2.4 | Authorization and security | G.5 and G.15; existing login/DRM/TLS invariants |
| P2.5 | Android TV | G.17–G.20, preserve TV layout and remote behavior |
| P2.6 | Testing and benchmarks | G.0, G.15, G.16, G.18, exact CI plus physical evidence |
| P2.7 | Core architecture | Preserve previously accepted one-decoder architecture |
| P2.8 | Material 3 Expressive UI | Preserve all existing controls and layouts |
| P2.9 | Closed captions | Preserve rolling HLS captions, alternate programme selection and appearance controls |

## 2. Objective

Improve efficiency, stability and scalability of the existing on-device HEVC stitching engine so playback approaches Netflix/YouTube smoothness, especially on resource-constrained Android TVs. Targets: lower CPU utilization, JVM allocation and GC pressure, memory copying, total process memory, power consumption and thermal load; fewer stutters and frame drops; improved multifeed throughput, seek and feed-switch latency; stable accelerated playback and full race-duration operation. Preserve every existing feature. Quantify improvements; native code is not inherently a universal performance fix.

### 2.1 Why Architecture G was selected — TV evidence and F1 TV research

The user initiated this investigation after earlier TV logs and physical testing showed the TCL 55C655 still struggling to sustain multifeed playback despite successful single-decoder operation and prior improvements. Build 839 observations included interruptions with three feeds plus map/tower; Build 842 automated success is not proof of improved physical throughput. The preceding cloud research asked whether Pitwall could reproduce official F1 TV multiview using free hosted processing. No dependable, permanently free production cloud solution was established. Avoid adding cloud infrastructure, network round trips or recurring service costs.

The prior technical review distinguished upstream cloud encoding from per-viewer playback composition: official F1 TV's reported AWS MediaLive/Tiledmedia approach prepares synchronized independently extractable HEVC tiles upstream and merges compatible bitstreams on the client for single-decoder playback. This is a conceptual reference, not evidence that Pitwall can access identical tile representations or reproduce proprietary encoding assumptions. G.0 must reopen the AWS MediaLive/Tiledmedia primary documentation and compare actual available authorized source profiles and compatibility constraints. Pitwall controls the client, not F1's upstream encoders. Do not assume equivalent source geometry, random-access alignment, timing, or encrypted metadata. Retain all existing compatibility/security checks.

The current best candidate is a measured native low-copy extension of the already working local architecture. Kotlin/JVM optimization remains valuable and is the baseline; C++ remains a limited benchmark control, not a second production port; GPU composition/multiple decoder remain existing alternatives where applicable. Full player rewrite, device-side decode/re-encode and cloud stitching are not part of this plan. Revisit alternatives only if G.0 or later physical evidence shows a material limitation.

Evidence caveat: this planning revision is grounded in the existing handover and prior review; it does not contain a fresh analysis of the original TV traces, a fresh source audit, or measured Rust results. Reopen exact traces and current source in G.0; distinguish stitching CPU/GC/copy pressure from network, codec, display, overlay and Cast bottlenecks. No guaranteed speedup.

## 3. Final proposed Architecture G

Official name: **Native Low-Copy On-Device HEVC Stitching with Bounded Memory Arenas**.

Original identified alternative:

```text
F1 CDN
  → Pitwall TV shared authenticated HTTP/2 OkHttp pool
  → native JNI fixed ring-buffer arena
  → direct ByteBuffer to Media3
  → existing authorized Widevine CDM
  → one MediaCodec video decoder
  → one output surface
```

Selected implementation:

```text
F1 CDN
  ↓
Existing authenticated OkHttp connection pool (HTTP/2 where negotiated)
  ↓
Preallocated bounded Rust input arena
  ↓
Rust HEVC/CMAF engine
  ├─ NAL scanning, VPS/SPS/PPS parsing
  ├─ Slice-header processing and bit-shifting
  ├─ Feed synchronization support and exact compatibility checks
  ├─ CMAF moof/mdat processing and composed sample assembly
  └─ Encryption metadata validation within existing authorized boundaries
  ↓
Bounded reusable native output slots
  ↓
Thin JNI bridge / reusable Direct ByteBuffers with explicit leases
  ↓
Least-invasive optimized existing Media3 publication
  ↓
Existing authorized Widevine CDM for protected content
  ↓
One MediaCodec video decoder and one output surface in single-decoder mode
```

Port the existing stitcher's proven NAL scan, bitstream rewrite and CMAF assembly hot loops, not unrelated authentication, UI, rendering or networking components. Preserve the exact validated algorithms and output invariants. Keep the JVM stitcher as a differential-testing reference and controlled A/B alternative. No cloud hop/server is required. Low-copy deliberately replaces the original aspiration of end-to-end zero-copy: Media3 and MediaCodec may still copy; network-to-native transfers must be counted. Zero per-frame JVM allocations in the stitching hot path is a target, not a promise of no GC across the app. Instant feed switching, no Widevine breakage and elimination of all pauses are not established facts.

## 4. Programming language decision: Rust selected

The user has chosen Rust, not C++, as the native production implementation language. The earlier discussion compared native languages, Google Android research, JNI, multimedia, memory management and production adoption. Rust is the default for G.0 and subsequent G phases subject to feasibility and measured acceptance. Do not start two production ports or silently change the decision. C++ is a small representative benchmark/control and contingency only; if mandatory compatibility or actual playback requirements cannot be met in Rust, document the evidence, investigate the root cause, and seek an explicit architecture decision before changing production language.

Why Rust: native machine-code performance, no GC, ownership and borrowing, compile-time memory safety, safe-Rust data-race prevention, reduced risk of use-after-free/double-free, suitability for concurrent binary parsing, Cargo dependency management, automated testing/fuzzing and long-term maintainability. C++ remains valid and has mature first-party Android NDK/Android Studio integration, multimedia ecosystem/debugging and ARM32 NEON support. Neither language is inherently faster. The architectural improvement comes from bounded native arenas, lower allocation and fewer copies.

Research claims from the previous discussion (verify before quoting): Google's Android engineering publication reportedly found approximately 1,000× lower memory-safety vulnerability density, 4× lower rollback rates for medium/large changes, 25% less code-review time and 20% fewer review revisions. These are Android platform findings with specific definitions, not Pitwall predictions. Verify exact denominators, comparison populations and dates. Review the reported Rust use in Keystore, UWB, networking, virtualization, Google Play services and kernel components; distinguish actual adoption from roadmap. Google favors memory-safe new development rather than indiscriminate C++ rewrites.
Primary source: https://blog.google/security/rust-in-android-move-fast-fix-things/

## 5. Android mobile and TV compatibility

| Android ABI | Rust target |
| --- | --- |
| arm64-v8a | aarch64-linux-android |
| armeabi-v7a | armv7-linux-androideabi |
| x86 | i686-linux-android |
| x86_64 | x86_64-linux-android |

Prioritize ARM64 and ARM32 physical devices; preserve all ABIs already supported by the current APK. Inspect actual ABI list, Android userspace, minSdk, NDK/API selection, installed memory and page size before selecting build flags. A TV can run 32-bit userspace on a 64-bit CPU. Require compatible native ELF and ZIP alignment, runtime behavior on 4KB and 16KB page environments, supported native dependencies and unchanged signed upgrade path. Package Rust as native .so libraries in the existing APK; users need no Rust installation. Avoid hardcoding CPU, vendor or model assumptions. Rust Android targets are supported, but app tooling is less mature than first-party C++ NDK. Verify current supported targets and version compatibility:
- https://doc.rust-lang.org/rustc/platform-support/android.html
- https://github.com/android/ndk/issues/1742
- https://developer.android.com/guide/practices/page-sizes

## 6. Hard mobile-only development constraint

The user develops through ChatGPT Android mobile, Composio, GitHub integration, GitHub Actions, Samsung Galaxy S22 Ultra, TCL 55C655, Bugjaeger, aShell, Android logs and Perfetto. No dedicated laptop or PC. No mandatory Android Studio, local Rust/C++ compiler, local Gradle/CMake, paid cloud development machine or interactive desktop-only debugger. GitHub Actions handles compiling, tests, signed packaging, benchmark execution where feasible and diagnostic processing. Device APK installation, logs, traces and validation are performed from phone/TV. Design a phone-operable workflow for native crashes: build-ID-matched symbols, reproducible fixtures, retained diagnostics, structured logs, offline CI symbolication and tests, rather than requiring local LLDB. Prefer automation and compile-time safety; CI allowances and actual emulator availability still constrain feasibility. Do not promise unrestricted execution or background work.

## 7. Selected technology stack

| Component | Technology |
| --- | --- |
| Native stitching engine | Stable Rust |
| Toolchain/dependency management | Cargo/rustc, pinned rust-toolchain.toml, Cargo.lock |
| Android native toolchain | Pinned Android NDK |
| Cross-compilation | Pinned cargo-ndk |
| JNI | JNI-RS, minimal audited unsafe boundary |
| High-frequency data transfer | Reusable Direct ByteBuffer plus explicit leases |
| Memory | Fixed-capacity/adaptive native arenas and bounded queues |
| HEVC/CMAF | Rust, safe Rust where possible |
| SIMD | ARM64 NEON only when measured; ARM32 Rust first, optional narrowly scoped audited native kernel only if justified and approved |
| Networking/authentication | Existing OkHttp and authorized F1 implementation |
| DRM | Existing Media3/Widevine |
| Decoding | Existing MediaCodec |
| UI | Existing Kotlin/Compose |
| CPU/system/native memory profiling | Simpleperf, Perfetto, heapprofd where supported |
| Code quality | rustfmt, Clippy, Cargo tests |
| Verification | Byte-exact differential tests, fuzzing, applicable sanitizers and independent decoder fixtures |
| CI/CD | Existing GitHub Actions; pinned reproducible versions and retained symbols |

Minimize new dependencies. Commit Cargo.lock, review transitive licenses, pin toolchain and NDK/cargo-ndk/JNI versions after checking compatibility; do not select unverified versions merely because they are latest.

## 8. Research and open-source reference inventory

Reopen these exact references before implementation, verify current source, maturity, licensing and applicability, and record the useful files/algorithms, version or commit, and integration limitations. References are not approved drop-in dependencies. Do not copy code without compatible licensing/attribution; do not bundle entire frameworks where a small safe parser is enough.

| Reference | Engineering relevance and constraints |
| --- | --- |
| [Mozilla MP4Parse](https://github.com/mozilla/mp4parse-rust) | Safe ISO BMFF/MP4 parsing, validation and production Rust multimedia experience; assess bounds and supported boxes |
| [ChromeOS cros-codecs](https://github.com/chromeos/cros-codecs) | H.265 structures and binary parsing; do not adopt ChromeOS hardware decoding in Android |
| [GStreamer Rust plugins](https://github.com/GStreamer/gst-plugins-rs) | fMP4/CMAF muxing, low latency, buffer management; verify actual implementation |
| [JNI-RS](https://github.com/jni-rs/jni-rs) | Kotlin/Rust interoperability, Direct ByteBuffer, references, thread and lifetime rules |
| [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) | ABI builds, packaging and CI |
| [Mozilla UniFFI](https://github.com/mozilla/uniffi-rs) | Kotlin bindings; compare generated binding costs, prefer minimal direct JNI for hot data |
| [Mozilla Application Services](https://github.com/mozilla/application-services) | Production Android Rust library integration |
| [Mullvad VPN](https://github.com/mullvad/mullvadvpn-app) | Compose plus Rust, native packaging/JNI, continuous processing |
| [Signal libsignal](https://github.com/signalapp/libsignal) | Security-sensitive Rust/JNI and cross-platform NDK |
| [Jellybeam](https://github.com/packetinspector/jellybeam) | Potential Kotlin/Compose/Media3/Rust Android TV and CI reference; verify repository and maturity, not proof of protected HEVC stitching |
| [FFmpeg](https://github.com/FFmpeg/FFmpeg) | Multimedia parsing, validation, fixtures and independent decode |
| [GPAC](https://github.com/gpac/gpac) | ISO BMFF/CMAF reference algorithms |
| [Bento4](https://github.com/axiomatic-systems/Bento4) | MP4/CMAF box and sample handling |

Official technical sources:
- https://developer.android.com/training/articles/perf-jni
- https://developer.android.com/ndk
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/ndk/guides/simpleperf
- https://developer.android.com/ndk/guides/ndk-stack
- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/media/media3
- https://github.com/androidx/media
- https://perfetto.dev/

Review earlier project research (Android performance/TV samples, reference HEVC projects and relevant allocation/copy-reduction work) only where applicable. No proprietary SDK, paid OpenF1 or unlicensed code. Links and reported claims from the user's prior discussion are included for Phase G.0 verification, not independently certified in this documentation pass.

## 9. Phase G.0 — mandatory validation before production port

**Phase signed off by user after Build 853; G.0 is closed as an engineering gate.** Carry outstanding physical evidence forward rather than inventing results. The following table records exact completion boundaries.

| ID | Required validation and deliverable | Build 857 checkpoint |
| --- | --- | --- |
| G.0.1 | Audit latest JVM stitcher, actual active entry points, clear/protected paths and existing tests | Active clear/protected routes audited; retain actual runtime profiling |
| G.0.2 | Identify real remaining allocations/copies, quantify ownership and publication points | Source ownership/copy inventory and boundary counters implemented; complete Media3/codec attribution G-C |
| G.0.3 | Establish matched CPU/memory/latency baselines on current JVM, both target devices | Matched physical JVM baseline on both devices deferred |
| G.0.4 | Integrate minimal Rust native module behind diagnostic feature gate | Minimal JNI diagnostic implemented; G-A adds bounded worker |
| G.0.5 | Configure pinned Cargo/rustc/NDK/cargo-ndk/JNI-RS and locked dependencies | Stable Rust 1.91.1, NDK 28.2.13676358, cargo-ndk 4.1.2, JNI 0.21.1, lock verified |
| G.0.6 | Compile ARM32 and ARM64; preserve all existing ABIs, verify x86_64 emulator | All four ABI builds; x86_64 emulators verified |
| G.0.7 | Integrate into existing GitHub Actions without weakening prior checks | Existing CI with host, sanitizer, differential, packaging and runtime gates |
| G.0.8 | Verify signed APK packaging and upgrade compatibility | Signed custom APK, cert identity and upgrade marker pass |
| G.0.9 | Verify library loading on S22 and TCL using phone-operable logs | Phone API36 diagnostic pass; TCL deferred |
| G.0.10 | Verify reusable Direct ByteBuffer interoperability, bounds, read/write ownership | Private direct buffers checked; G-A native-owned views and copies verified |
| G.0.11 | Prototype fixed-capacity arena, lease/generation and exhaustion behavior | Bounded arenas, exclusive leases, exhaustion and generations tested |
| G.0.12 | Representative equivalent JVM/Rust/C++ micro- and fixture benchmarks | Host JVM/Rust/C++ controls and physical phone microbenchmarks; not full playback |
| G.0.13 | Deterministic output equivalence against current JVM and independent fixtures | 106 slice cases and 3 CMAF cases; existing independent decode gates; full native compose G-B |
| G.0.14 | Native ownership/cancellation/seek/close tests including stale lease and races | Host stress, stale lease/close and Android lifecycle tests pass |
| G.0.15 | 4KB/16KB ELF/APK alignment plus runtime compatibility evidence | 4KB runtime verified; 16KB ELF/ZIP alignment pass; physical 16KB runtime deferred |
| G.0.16 | Native crash reporting and build-ID symbolication from mobile-accessible artifacts | GNU build IDs, unstripped/packaged match, source symbolication evidence |
| G.0.17 | Verify authorized DRM integration unchanged; native prototype does not weaken policy | Existing authorization/DRM untouched; real protected native pipeline still G-B |
| G.0.18 | Establish physical-device benchmark collection, upload and analysis through phone | Phone diagnostic log collected; full matched playback traces deferred |
| G.0.19 | Demonstrate complete mobile-only workflow, no PC required | GitHub Actions + phone APK install/aShell workflow demonstrated; TV native run deferred |
| G.0.20 | Evidence-based final feasibility report for selected Rust, comparison with limited C++ control and go/no-go | Rust selected; user sign-off to proceed; no production-default authorization |

### G.0 execution checkpoint — first consolidated feasibility batch (24 November 2026)

Baseline branch ref: 302b52edf8f8a87f670ba1a91b00c20145550351. Parent Actions history verified: 842 success (b9752ac9), 841 failure, 840 success. Source confirms compileSdk 37 / minSdk 29 / targetSdk 36, Media3 1.11.1, OkHttp 4.12.0. Existing debug workflow excludes this new branch until this batch. Preserve all existing CI/signing/DRM/decoder paths; add the branch and feasibility step without removing gates.

Initial audit, not complete: HevcBitstream.NalSpan already borrows backing arrays and materializes only parameter-set spans as needed. HevcClearCmafGridComposer retains borrowed tail ranges and creates output fragment arrays. Existing annexBSizeForSample scans 1–3 byte prefixes; 4-byte fast path defers validation to inspector. ProtectedHevcSampleComposer.Plan.copyInto already supports direct assembly; materialize() allocates an array but actual callers/runtime cost require further audit. HevcArchiveVodSession.ResourceDataSource.read and HevcComposedPlayback.ComposedResourceDataSource.read use byte-array delivery. Inspect active call sites, cache ownership and pinned Media3 internals before changes. No proven current TV root cause. Parent plan notes mixed-build TV traces, including older Build 831; original raw TV traces not present in current turn files. Fresh matched 842/new candidate physical captures required.

Initial diagnostic prototype: dependency-free safe Rust sizing kernel, bounded single-owner slot/lease prototype, negative/lifecycle tests, deterministic synthetic prefix fixtures, actual JVM routine and small C++/Rust host control with warmup and p50/p95/p99/max timing. No protected processing, network, JNI, APK native packaging, settings or DRM changes. Synthetic sizing fixtures are not decodable HEVC; no full composition equivalence yet. Host timing differs from actual Android JIT/thermal/media pipeline. Candidate pins Rust 1.91.1, NDK 28.2.13676358, cargo-ndk 4.1.2; dependency-free lock committed, JNI-RS integration/lock pending. Official Android targets and r28 alignment supported in principle per https://doc.rust-lang.org/rustc/platform-support.html and https://developer.android.com/guide/practices/page-sizes ; AWS confirms client-side rewriting/merging https://aws.amazon.com/blogs/media/f1-sets-new-standard-in-sports-streaming-with-f1-tv-premium-using-aws/ . Remaining reference review open.

G.0.1/2 initial audit in progress; G.0.4 host/ABI prototype only; G.0.5 candidate pins/lock, JNI-RS pending; G.0.6/7 cross-build/CI configured, run pending; G.0.11 single-owner arena prototype, concurrency/JNI pending; G.0.12/13 narrow sizing comparison, broader real fixtures and physical benchmarks pending; G.0.14 single-owner stale-release/exhaustion tests only; G.0.15 64-bit ELF checks configured, APK/runtime pending; other G.0 checks open. G.0.20 go/no-go not reached. Production remains JVM; G-A–G-D not started. Exact CI and physical evidence must be verified; no native APK or speedup claim. Continue G.0 on this branch only.

### Build 843 exact-source feasibility evidence — GREEN

Verified GitHub Actions [run 45441485463](https://github.com/cooldarshak/apexview/actions/runs/45441485463), job 138613513278, exact source [d7734087094a78013f7dfc6d7f8695487942064c](https://github.com/cooldarshak/apexview/commit/d7734087094a78013f7dfc6d7f8695487942064c). All mandatory existing checks green (coordinated change contract, JVM, independent HEVC/CMAF decode/pixel checks, signed APK, RenderNode and API 29/31/36 instrumentation including TLS). New G.0 step succeeded: Rust 1.91.1, cargo-ndk 4.1.2, pinned NDK; two Rust tests passed; Clippy succeeded; all four Android ABI builds and configured 64-bit LOAD alignment checks passed. Host/ELF artifact [14943626918](https://github.com/cooldarshak/apexview/actions/runs/45441485463/artifacts/14943626918); signed JVM-playback APK [14945488087](https://github.com/cooldarshak/apexview/actions/runs/45441485463/artifacts/14945488087); JVM test report [14945428338](https://github.com/cooldarshak/apexview/actions/runs/45441485463/artifacts/14945428338). Native libraries are standalone CI artifacts, **not packaged in the APK**; no JNI invocation or native playback yet.

Host fixture output and timing in nanoseconds per call (p50/p95/p99/max), exact CI log:

| Input bytes / prefix | Expected size (all three agree) | JVM | Rust | C++ |
| --- | ---: | --- | --- | --- |
| 6115 / 1 | 6307 | 442/1261/1335/1605 | 108/119/175/193 | 106/114/189/202 |
| 264266 / 2 | 264522 | 948/1026/1046/1059 | 462/528/548/569 | 434/492/519/521 |
| 1053092 / 3 | 1053348 | 1972/2409/2831/2962 | 1398/1433/1455/1458 | 1439/1474/1487/1515 |

Rust median elapsed time 75.6%, 51.3%, 29.1% lower vs JVM; Rust vs C++ median +1.9%, +6.5%, -2.8% respectively. Within proposed 10% median C++ tolerance for this narrow kernel only. Batch-average per-call values, not individual-call tails, CPU utilization or end-to-end composition. Prefix sizing jumps across payload bytes; byte size is not amount scanned or copied. Synthetic non-decodable samples, sequential host runs, JVM JIT and optimization differences limit inference; no playback/thermal/DRM proof. Existing independent decode tests cover JVM, not the Rust output. No final G.0 go/no-go.

Additional source audit: CmafHttpReader.read allocates a single exact-sized byte array when content length is known; unknown-length path allocates bounded chunks then copies into a complete byte array. HevcArchiveVodSession records preflightInspectMs, fetchMs, composeMs, verifyMs, totalMs, durationMs and producerSlackMs; ART counters are process-wide, not compositor-exclusive. Initialization cache clones remain; verify publication ownership/read copies and actual callers. ProtectedHevcLiveDashSource supports takeOwnership and avoids duplicate fragment clones in that mode, but its DataSource.read copies from retained bytes to Media3 buffer. These are candidate measurement boundaries, not a proven TV root cause.

G.0.6 four target cross-compiles PASS (actual APK ABI preservation/runtime pending); G.0.7 integrated existing CI PASS; G.0.5 pinned core tools validated, JNI-RS/lock pending; G.0.11/G.0.14 limited single-owner tests PASS; G.0.12/G.0.13 narrow host sizing benchmarks/equivalence PASS, full real fixtures/physical pending; G.0.15 configured ELF check PASS, ZIP alignment and runtime pending. G.0.1/2 partial source audit; G.0.3 physical baseline pending; G.0.4 host/ABI proof passed, diagnostic JNI integration pending; G.0.8/9/10/16/17/18/19/20 open. G.1–G.20 unchanged/not started. Keep production JVM default and no release acceptance changes.

Next physical evidence: use signed Build 843 as JVM baseline (version expected 1.0.1686 from run 843, confirm actual app log header). Prioritize TCL 55C655: same authorized replay, actual primary/secondary resolutions and decoder mode, Software DRM ON if needed, single feed with map/tower then three feeds with overlays OFF and ON at 1x, separate short seek/1.5x test; 30s warmup, 60s stable trace where supported (shorter on constrained TV), export app logs for each case, record visible symptoms and device ABI/API/page size. Repeat comparable scenarios on S22; use existing aShell/Bugjaeger and [Perfetto instructions](../perfetto/ASHELL.md), replace old branch in config URL with the dedicated G branch, record failures without disabling security. Save exact settings/version/actual feed sizes. Baseline collection and JNI/real-fixture work remain required. No new build from this evidence-only update.

### G.0 second consolidated feasibility batch — JNI, packaging, runtime and symbols (candidate, not verified)

Source base f51b0d8200c46cef697b672ada33b2f96afb9b3a; Build 843 remains the last verified green signed baseline until this candidate completes its exact CI. This batch extends the existing workflow, never removes existing tests or changes production playback/DRM/casting. JNI-RS 0.21.1 pinned for its documented DirectByteBuffer APIs; the candidate Cargo.lock is the dependency closure of an existing published crates.io registry lock, not a locally generated Cargo result (execution environment lacks Cargo and cannot fetch toolchain). Cargo --locked in CI is the authoritative resolution/build check. Source: https://github.com/ARMSX2/ARMSX2-NATIVE-TOOLS/blob/main/Cargo.lock (registry metadata only; no third-party application code reused); JNI API inspected at https://github.com/jni-rs/jni-rs/blob/v0.21.1/src/wrapper/jnienv.rs . License/transitive review and fresh lock verification remain mandatory before production adoption.

Debug-only Java diagnostic owns two reusable bounded direct buffers; loads native library explicitly, no app startup, production engine or user settings wiring. Native receives local JNI refs, validates sizes/capacities/direct address/alias/read-only output, catches unwind, keeps unsafe localized and retains no pointers or references. Clear length-prefix to Annex-B conversion validates whole input/header/size before writing and retains exact payload bytes; this is not full HEVC parsing, rewriting or protected media processing. One explicit byte-array input copy and a diagnostic output copy remain; timed conversion excludes these, and is NOT zero-copy. Session generation and synchronized close/invalidate prevent stale publication and concurrent memory use; Java-owned buffers are GC-managed (no native pointer lifetime promise). Existing Rust single-owner arena remains separate; full concurrent native-owned arena and end-to-end publication belong to later work.

Add real independently encoded 2x1/2x2 tile-stream fixture equivalence (1–4-byte prefix widths where representable; exact byte comparison against JVM reference; existing size kernel cross-check) and Android runtime timing with source hashes. Timings are local conversion-only, not matched end-to-end playback or CPU counters; emulator results do not establish TV speed. Add malformed-input/no-partial-write Rust tests and Android direct-buffer/invalid generation/concurrent-close tests. JNI instrumentation gated on all three existing API 29/31/36 emulator runs without skipping existing TLS/rendering/archive checks. Diagnostic native .so packaged only in dedicated branch debug APK, all four ABIs. Existing signed Build 843 artifact is downloaded by exact run ID; verify package identity, monotonic versionCode, signer fingerprint, previous native libs preserved, all diagnostic ABIs, 16 KB ZIP alignment; actual emulator upgrade installs baseline then candidate and tests app-data marker persistence. Artifact expiry, signing availability and actual CI remain gates. Retain original unstripped libraries and packaged build IDs, validate exported JNI symbol address maps to source with NDK llvm-addr2line; no intentional production crash. Runtime 16 KB/ARM32/ARM64 physical and actual crash handling remain open.

G.0.4/5/8/9/10/13/14/15/16: candidate diagnostic code and automation wired; CI/physical not yet passed. G.0.6/7 previous four-target/CI pass retained, new candidate revalidation pending. G.0.11 existing arena prototype plus synchronized Java ownership experiment; production native lease arena remains separate. G.0.12 broader conversion fixture timing configured, still no physical baseline or full HEVC/CMAF performance. G.0.1/2 full active-call-site/copy attribution not complete. G.0.3/17/18/19/20 pending actual physical data and complete scope; no claim of authorized protected native playback. User can run matching signed debug and instrumentation APK on phone/TV with existing phone-based ADB/Bugjaeger (install over previous app; do not uninstall, clear data, or change DRM). With both exact same-build APKs installed run `am instrument -w -e class app.apexview.tiled.ArchitectureG0AndroidTest app.apexview_custom.test/androidx.test.runner.AndroidJUnitRunner` in device shell and collect `logcat -d -s PitwallG0:I '*:S'` plus test footer, device API/ABI/page-size line. CI-only upgrade marker is not required on physical device. Reuse existing authorized replay baseline/log/trace protocol separately. G-A–G-D and user-facing setting remain not started; do not switch playback engine/default. No new architecture approval or measured device improvement implied.

### Build 844 failure and consolidated correction — native GNU build ID

Exact [Build 844](https://github.com/cooldarshak/apexview/actions/runs/45446979890), job 138633322774, commit c732db971e0f0fb67f536515c66fcb96d9ab07a7. Rust locked dependency resolution, four Rust tests, Clippy, all four Android ABI builds, full JVM/independent HEVC/CMAF suite, signed assembly, baseline download, APK signing identity, monotonic version, existing native entries, four new ABI entries and 16KB ZIP alignment passed. Native symbol verification failed immediately after identity/ABI PASS; Android emulator gates and APK publication did not execute. Downloaded exact [failure artifact 14946842280](https://github.com/cooldarshak/apexview/actions/runs/45446979890/artifacts/14946842280). The armeabi-v7a-build-id.txt was empty, and both LLVM readelf notes contained only .note.android.ident. Independent local readelf confirmed GNU build IDs absent in all four unstripped and packaged .so files, not a parser-format or stripping issue. GNU nm/addr2line independently resolved nativeConvert to jni_bridge.rs:18 on all four original unstripped binaries. Therefore the specific cause was the Android Rust cdylib linker not emitting GNU build IDs, not JNI compilation or APK signing.

Correction: crate build.rs emits Android-only cargo:rustc-link-arg-cdylib=-Wl,--build-id=sha1; no dependency/toolchain change. Local Clang/LLD sanity check on standalone minimal Android-target shared objects for all four ABIs confirmed the linker flag emits SHA-1 build IDs; actual Rust/NDK build remains CI-dependent. Validate ID immediately after cross-build before Gradle; enforce exactly one nonzero 40-hex SHA-1 ID, exact unstripped/packaged match and source-line symbolication on every ABI. Normalize ARM32 Thumb-state symbol bit for address lookup; explicit diagnostics on failure. Add regression for Build 844's missing note, malformed/duplicate/zero ID and stripped/unstripped mismatch. No gate removal, no fabricated IDs, no unsigned APK, no production playback changes. Build 843 remains latest verified green until corrected candidate passes full CI. G.0.5 locked JNI-RS compiled; G.0.8 packaging/signing/identity passed but actual upgrade pending; G.0.9/10/13/14 runtime instrumentation pending; G.0.15 APK alignment passed but 16KB device runtime open; G.0.16 original symbols resolve, ID gate correction candidate. Physical and protected-native gates remain open.

### Build 845 verified GREEN; third G.0 batch — batched conversion and ownership

Exact [Build 845](https://github.com/cooldarshak/apexview/actions/runs/45450192089), commit 717cc4c4025212ac1e2d950030ccbd4a1cc1c9f5, job 138644846809. Four Rust tests, locked JNI-RS, all four ABI cross-builds, SHA-1 build IDs, 16KB APK alignment, signed identity and emulator upgrade/data retention, packaged/unstripped ID and source-line match on all four, existing regression and Android API 29/31/36 JNI instrumentation passed. All eight independently encoded clear assets matched for 290 prefix-conversion cases per emulator (61+21+21+96+21+26+23+21); no full native stitch/DRM validation. Artifact [APK](https://github.com/cooldarshak/apexview/actions/runs/45450192089/artifacts/14948859550), [instrumentation APK](https://github.com/cooldarshak/apexview/actions/runs/45450192089/artifacts/14948174452), [symbols](https://github.com/cooldarshak/apexview/actions/runs/45450192089/artifacts/14948768808), [reports](https://github.com/cooldarshak/apexview/actions/runs/45450192089/artifacts/14950345398). Actual runtime x86_64, 4096-byte pages; supported ABI list is not proof of ARM execution.

Timing limitation: API 29/31 emulator JVM conversion median was faster than Rust JNI for all eight small groups; API 36 showed opposite. Example source_tiled 5298 bytes p50 ns JVM/Rust: API29 1836/4670, API31 2601/5055, API36 31671/3658. Existing tests exclude load/copy, have sequential JIT/warmup bias and only small workloads. No evidence-based ARM/TV conclusion. New batch adds fixed-size independent sample batch count 1..64 per JNI crossing, prevalidates each independent sample before writing, rejects malformed later sample without publication, no per-sample allocation; JNI ABI version 2. Compare 1,4,16,64 identical real encoded sample groups, JVM array, Rust compute-only, Rust copy-inclusive and unbatched copy-inclusive, log elapsed p50/p95/max, JNI calls and explicit bridge-copy bytes. This is repeatable synthetic conversion workload, not source slicing/CMAF composition; JVM aggregation and unbatched output aggregation have additional copies, not equal boundary counts. Do not generalize benchmark ratios to playback. Native arena now carries unique process-local arena ID, rejects cross-arena leases, tests synchronized concurrent invalidate/release/exhaustion. This is safe serialized host prototype, not native JNI-managed production arena or asynchronous cancellation.

Further source review (exact Build 845): CmafHttpReader.read known length one array, unknown bounded chunks plus full copy; HevcArchiveVodSession.composeAndPublishAdmitted owns composition and records compose/verify/producerSlack, initialization copy at 437 and 659, ResourceDataSource.read at 724. HevcClearCmafGridComposer passthrough clones, parameter-set arrays, source spans and final fragment allocation; ProtectedHevcLiveComposition copy sites 48/61/311/312/329/368/448/592/1015 with lifecycle distinctions requiring actual call-site profiles; ProtectedHevcCmafFragmentWriter.write final ByteArray at 203 and copyInto at 220 uses plans; ProtectedHevcLiveDashSource.publish takes ownership but DataSource.read copies at 632; HevcComposedPlayback read copies at 137. These are candidates, not measured costs. No production changes, login, DRM, cast and existing Media3 remain unchanged.

G.0.4/5/6/7/8/10 diagnostic and automated proof passed 845 (native packaging only debug, real-device upgrade open); G.0.9 physical phone/TV open; G.0.11/14 prototype extended, CI pending; G.0.12/13 initial correctness passed, representative full stitching, bitstream/slice/CMAF and protected-metadata fixtures remain; G.0.15 ELF and APK alignment passed, 4KB emulator passed, 16KB runtime open; G.0.16 build-ID symbol proof passed, actual mobile crash collection open; G.0.1/2 partial audit with measurement pending; G.0.3/17/18/19/20 physical or wider evidence pending. G-A–G-D not started. No production switch or final go decision. Use latest green debug APK and matching instrumentation APK for physical workflow: install both over existing same-signed app, run `am instrument -w -e class app.apexview.tiled.ArchitectureG0AndroidTest app.apexview_custom.test/androidx.test.runner.AndroidJUnitRunner` via aShell/Bugjaeger, export `logcat -d -s PitwallG0:I '*:S'`, expected 4 tests after new candidate green. Run separately from authorized replay traces, record actual device/API/ABI/page size and temperature; do not log credentials or protected payloads.

### Build 846 verified GREEN; fourth G.0 batch — wider diagnostic kernels and CI efficiency

Exact [Build 846](https://github.com/cooldarshak/apexview/actions/runs/45456494640), job 138667241321, commit 22b7036ffee456ed9a56c1ea145411b726f80fed. All existing and new mandatory gates passed, including four-ABI compilation, signed upgrade, JNI tests and fixture/batch equivalence at API 29/31/36. At 64 repeated groups (339072 input bytes) p50 nanoseconds JVM/Rust compute/Rust copy-inclusive/Rust unbatched: API29 34437/12709/30193/161630; API31 156550/14086/28299/236223; API36 77917/12739/30368/248545. Batching reduced copy-inclusive Rust time on those emulator workloads. Do not infer physical ARM speed: small workloads and JVM warmup differ, controls include different aggregation copies, actual stitch not benchmarked.

New diagnostic candidate: Rust bounded MSB-first bitreader and unsigned Exp-Golomb, top-level ISO-BMFF extent scan, synthetic clear/encrypted length metadata accounting with CENC 16-bit clear-length splitting and distinct opaque subsample boundaries. No encrypted payload, keys, IVs, licence or DRM change. Shared deterministic vectors verified against current JVM HevcBitReader, CmafBoxInspector and ProtectedHevcSampleComposer, plus native negative tests. Metadata Rust measures only length accounting while Kotlin exercises full composer incl allocation/copy; box Rust scan vs full JVM inspect is likewise unequal; timings for attribution, not language speed ratios. Full slice-header rewrite, CMAF assembly, matched C++ controls for these new kernels, physical profiles and authorized DRM remain open. Existing C++ sizing benchmark retained. No production player wiring.

Build 846 actual job logs 18:09:54–18:29:29 UTC (19m34s excluding queue). Sequential API29/31/36 emulator steps approx 481s total; assembly/shrinking 339s; JVM tests and compilation 166s; Rust host/ABI 82s; pinned Kvazaar compile 44s; other ~62s. Candidate uses same Gradle daemon across invocations with --parallel and --no-build-cache, stops after assembly; all tests still execute on fresh runner. Cache only pinned Kvazaar executable scoped to runner OS/arch/image version/source commit; version probe before use, rebuild on miss or failed probe. All fixture generation/validation, signing, R8, full Rust and JVM tests, existing emulator matrix, exact signed upgrade and upload gates retained. No caches of test outputs or signed APKs. First run may miss cache; measure subsequent jobs and revert tuning if slower or unstable. Separate emulator matrix could theoretically eliminate ~4m25s of sequential critical-path time before overhead (481s minus longest ~215s), but needs safe staging that preserves no publishable APK before every gate passes and may use more concurrent runner minutes. Not implemented; no guaranteed build-time reduction yet.

G.0 remains open: full active path copy attribution, physical ARM baseline/benchmarks, real 16KB runtime where available, authorized DRM and mobile-only proof, final decision. This batch does not claim production native stitching or complete representative benchmarks. Preserve existing signed JVM engine and G-A gate. Build 846 last verified green until new candidate passes.

### Build 847 verified GREEN; fifth G.0 batch — parallel runtime gates and clear CMAF assembly

Exact [Build 847](https://github.com/cooldarshak/apexview/actions/runs/45461138522), job 138683696366, commit baaf0f137ea038ddb657155936afbfba89490940, all checks passed. Ten Rust tests and all shared diagnostic bitreader/box/metadata vectors passed. Runtime 22m24s (18:35:22–18:57:46 UTC; queue excluded). Build 846 19m34s. G.0 host 108s vs 82s; encoder 61s vs 44s (first cache miss, now saved; image component was empty); JVM 223s vs 166s; assembly 425s vs 339s; sequential emulator 443s vs 481s. Build 847 logs show generateDebugBuildConfig and compileDebugKotlin reran during assembly due changing timestamp/config inputs; not a proven sole cause of all slowdown. Revert --parallel (unmeasured benefit on constrained runner), retain daemon and no build-result cache, freeze timestamp once, configure same signing before first Gradle invocation, scope Kvazaar cache to actual runner image. Cache key change incurs another initial miss.

G branch only: compile/sign/verify once; stage exact signed APKs and exact Build843 baseline in an integrity-protected GPG AES256 encrypted bundle, per-stage HMAC-SHA256 domain-separated passphrase derived from existing repository keystore material (no extra secret configuration, no passphrase/keystore in artifact or logs). Manifest SHA256 checked after decrypt. Separate API29/31/36 jobs run existing exact instrumentation scripts and signed upgrade/data retention, same settings and test counts, fail-fast false, max-parallel 3; no APK recompilation or test-result reuse. Final publication job requires aggregate matrix success plus successful build; downloads same encrypted stage and publishes original four distributable artifact names only after all gates. All non-G branches retain prior sequential jobs and original artifact names. Per-API reports retained and combined; build artifact/evidence retained. Encrypted stage accessible as ciphertext only; 14-day retention for reruns, no private signing key contained. New staging roundtrip/negative tests, guard checks. More concurrent runner minutes, possible queue/transfer overhead, no guaranteed speedup. Theoretical emulator critical-path gain ~443-174=269s before overhead; actual observed run required. Baseline artifact expiry remains a blocker if not retained; do not silently replace it.

Add bounded native clear CMAF writer (single track mfhd/tfhd/tfdt/trun/moof/mdat, exact signed composition offset and sample sizes, output preallocated) and differential against actual JVM writer for two synthetic samples and two 25-picture independently encoded clear feed fixtures. Native host benchmarks assert byte-for-byte equivalence; exact JVM outputs stored in native evidence. Rust reuses output while JVM allocates, timing is architecture comparison, not equal allocation language benchmark. No production native pipeline, no protected data accepted by diagnostic; actual DRM integration unchanged. Native full slice-header rewrite, representative equal C++ controls beyond sizing, full active-path copy/CPU attribution, physical ARM/16KB validation, authorized replay and final go/no-go remain open. Build 847 is last verified green until candidate passes. Do not close G.0 yet. G-A–G-D remain gated.

### Build 848 failure correction — production publication contract

Build 848 run 45465784603 job 138700183564 at e1f74cf108c918b195802e2b5ab41a7c4d54f9d8 stopped at Python regression suite; host Rust/ABI checks passed. Existing test_ci_never_assembles_or_publishes_an_apk_after_failure asserted literal old publication expression, unaware of branch-specific sequential versus matrix publication. Keep original non-G conditions and test ordering; extend mandatory regression to require successful build, all three matrix entries, fail-fast false, aggregate success, gated decrypt, all four final artifact uploads gated, no distributable APK uploads in matrix, ciphertext staging only. Exact corrected method executed against actual candidate workflow and instrumentation source in remote Python preflight, passed. No workflow gates changed by this fix. Kotlin/CMAF differential and matrix execution not reached on 848, remain pending. No production playback changes. Build 847 remains last verified green until corrected CI passes.

### Build 849 GREEN; sixth G.0 batch — clear slice rewrite and matched C++ CMAF control

Exact [Build 849](https://github.com/cooldarshak/apexview/actions/runs/45466965626), commit 95cac9dc2c87b287a70ecbb9e364f2ec2fc85660. All five jobs (build, API29/31/36, final publication) passed. Job log critical path 19:08:08–19:23:23 UTC = 15m15s, 31.9% faster than 847; initial queue excluded. Build job 11m23s; longest matrix job 3m18s (API29 artifact download 55s outlier); final publication 20s. JVM 2m53s, APK assembly 4m37s; compileDebugKotlin UP-TO-DATE in second invocation. R8 still executed. Kvazaar corrected image cache first miss then saved. NDK download + extraction was major native setup overhead; cargo-ndk locked compile 22.83s. Candidate caches only pinned cargo-ndk executable installation, checks actual version, reinstalls on miss, checks NDK source.properties and clang, all native tests and cross-builds rerun. No test/APK cache. Do not compromise R8 or exact emulator gates for more time; measure subsequent builds.

Verified Rust CMAF exact byte matches: encoded-0 25 samples 339040 bytes JVM/Rust p50 60405/6844ns; encoded-1 14966 bytes 19944/191ns; small 145 bytes 8716/16ns. Different allocation strategies; not proof of playback speed. New host-only C++ writer control identical preallocated output, fixture input, metadata and byte-equivalence checks; C++ compiled locally in remote sandbox with -O3 -Wall -Wextra -Werror; CI fixture comparison pending. Native clear slice feasibility kernel transforms already JVM-validated syntax offsets, copies coding fields, rewrites destination/PPS and entry-point syntax, restores alignment, re-escapes header and two clear CABAC bytes, retains exact remaining source tail. Six synthetic clear fixtures from actual JVM rewriter (three payload sizes x two destinations), output exact match required; reports p50/p95/p99/max. Does NOT port full parameter-set/slice parser, protected path or loop-filter override; full implementation belongs G.3, not proof of production stitching. Existing real 2-feed clear grid test logs parse/rewrite/package and avoided-copy counters for attribution. All current production features unchanged; G.0.1/2 remain source/measurement partial, G.0.12/13 broader candidate CI pending. Physical acceptance and go/no-go still open.

Physical baseline can start immediately on exact green 849: [signed APK](https://github.com/cooldarshak/apexview/actions/runs/45466965626/artifacts/14956876033), [matching instrumentation APK](https://github.com/cooldarshak/apexview/actions/runs/45466965626/artifacts/14955393902). Install both over same signed app, no uninstall. On S22 and TCL via aShell/Bugjaeger shell run `am instrument -w -e class app.apexview.tiled.ArchitectureG0AndroidTest app.apexview_custom.test/androidx.test.runner.AndroidJUnitRunner` (expect OK (4 tests)), export `logcat -d -s PitwallG0:I '*:S'`; record API, ABI and page size printed by test. Then separate authorized archive baseline at 1x with identical session/quality/position for single feed + map/tower, three feeds overlays OFF, three feeds overlays ON (TCL Software DRM ON), 30s warmup and 60s trace each, export app logs, note actual rendition and decoder and smoothness. Same on S22 as practical. Separate 1.5x seek interaction; never combine with steady windows. Existing docs/perfetto/ASHELL.md use G branch config. No new native setting yet. No PC requirement. Build 849 is sufficient for these tests; new candidate changes host feasibility only, not phone JNI ABI. Physical results required to close G.0.3/9/17/18/19/20; 16KB runtime only if actual supported device available.

### Workflow repair following d5eed03 failure

User reported failure; connected workflow/check-run API returns Build 849 as latest, no failed run ID for d5eed03. Direct inspection confirmed malformed workflow: replacement-string expansion at shell regex end anchors duplicated workflow suffix, expanding 730 lines to 5820 with repeated job definitions. Reconstructed intended changes from verified 849 workflow using literal replacement callbacks. Retained pinned cargo-ndk cache, NDK revision verification, slice/C++ benchmarks, all three parallel runtime gates, encrypted staging and final publication. Use cargo ndk --version via cached tool PATH; enforce pinned version. Clear slice alignment loop uses is_multiple_of supported by Rust 1.91.1 to avoid Clippy lint. Permanent regression checks unique jobs/native gate and Bash syntax for literal run blocks. Strict duplicate-key YAML parse, targeted production publication regression and G.0 packaging contract tests passed before commit. Full Rust/Android CI not run locally. Exact failed-run error log unavailable. Build 849 remains latest verified green.

### Build 851 GREEN; first physical ARM result; seventh G.0 batch

Build 851 run 45483910506, commit c74162c1ca286f17402b647a880c4b1692fb00c5: all five jobs passed, 12 Rust host tests; all six synthetic clear-slice and three JVM/Rust/C++ clear-CMAF cases matched exactly. 339040-byte CMAF JVM/Rust/C++ p50 ns 72405/9966/10621 (JVM allocates output; native controls reuse). Large 131765-byte synthetic clear slice address 30 JVM/Rust p50 142242/172050 ns, native approximately 21% slower; small/medium native faster. Real JVM two-feed clear composition reported sourceCopyAvoided 713933, rewrittenTailStagingAvoided 713061 across 50 pictures; not measured native end-to-end allocations. Cargo-ndk tool cache saved; no performance guarantee.

User provided successful physical G.0 diagnostic on Android SDK36, supported ABIs arm64-v8a/armeabi-v7a/armeabi, 4096-byte pages, package app.apexview_custom, presumed S22 Ultra (device name and actual process bitness not emitted). Eight encoded fixture hashes, 290 exact prefix conversions. Copy-inclusive batch p50 ns JVM/Rust 1:43177/8073; 4:171041/9323; 16:372240/15208; 64:146771/47864. At 64 groups, Rust unbatched copy-inclusive 497032ns, batched 47864ns (10.38x), same 678144 bridge copy bytes, extra JVM aggregation not equal across cases; JNI-only small single group slower than JVM (e.g. 2145/4794ns). Evidence supports batching, not proof of full stitching speed. No failure of native execution; initial NoClassDefFoundError resolved after matching APK install. User explicitly deferred TV test, proceed with independent engineering while TV/actual playback gates remain open. Add exact package version and process64Bit to future diagnostic logs. No immediate repeat needed.

Next candidate improves clear-only large-tail validation via safe word-at-a-time skip, only when no zero bytes and prior zero count zero. Retains all scalar EPB checks, full source scan and unchanged output/cancellation boundaries; no unsafe or DRM changes. Scalar control retained for exact comparison and performance attribution. Deterministic randomized boundary tests and malformed late/truncated EPB atomic rejection. Extend differential from six synthetic cases to all 100 genuine motion-constrained Kvazaar source slices across existing two-feed 50-picture composer; source/expected bytes from actual JVM path, full equality with scalar and optimized Rust; benchmark first two pictures per feed. Existing independent decode/pixel gates unchanged. New source still prevalidated JVM syntax only; production native parser, protected path and loop override not implemented; full CI and physical playback pending. No production player changes. G.0.9 partial physical pass, TV deferred; G.0.18/19 mobile collection demonstrated for diagnostic only; do not mark G.0 or 16KB acceptance complete.

### Build 852 failure correction — real encoded fixture output ownership

Build 852 run 45531190914 job 138926626978 at 212b7bd2100c4f67846a7759d8e3eed618b30cda: all 14 Rust unit tests passed, host ABI and Clippy passed; JVM stopped at HevcSliceHeaderRewriterTest.exportsAllEncodedTwoFeedSliceFixtures line 300, IndexOutOfBoundsException empty vclNals. Exact composer contract: retainRewritten = includeAnnexBOutput || !emitSampleData; previous test requested includeAnnexBOutput=false with default emitSampleData=true, so retained only sampleData and deliberately released materialized vclNals (not borrowed parts). Correct test requests both false, the existing owned-NAL-only mode, and explicitly requires 2 owned NALs, 2 evidence records, empty sampleData and empty nalParts per picture. All 100 real encoded and six synthetic differential cases remain mandatory. No production code or workflow change. Full JVM/Rust differential CI still pending, no local claim of test pass. Build 851 last green. Phone evidence retained, TV validation deferred.

### G.0 signed off by user; Build 853 verified GREEN; G-A consolidated foundation

User explicitly signed off G.0 and authorized G-A. Record engineering phase gate complete by user decision; deferred physical acceptance not waived: TCL native test, matched JVM playback/DRM baseline on both devices, sustained playback and physical 16KB remain unmeasured. Build 853 run 45532016787 commit 912f9c01093a702be5eb6cc37269c34311091e8a all five jobs GREEN. Fourteen Rust tests, six synthetic and 100 real encoded native/scalar/JVM slice comparisons, three matched clear CMAF C++/Rust comparisons, all emulator tests. 131765-byte case address30 JVM/scalar/optimized p50 99977/112776/27809ns, 3.60x vs JVM, 4.06x vs scalar same host; no playback claim. Cargo-ndk cache hit. Physical SDK36 diagnostic passed 290 cases previously. G.0 project sign-off complete, physical gates carried forward.

G-A candidate: bounded native execution/ownership contract (engine.rs) with single reusable worker, fallible fixed allocation of 2–8 input/output slots, checked budget includes bridge buffers, worker stack and reserve, foreground earliest-deadline scheduling, prefetch reserved slot, explicit admission failure and deadlines, monotonic tokens, exclusive acquisition, read range, release, generation invalidation and worker join. Registry permits only one active native engine per process, checked monotonically increasing opaque session handles; JNI copies from private reusable Java direct buffer into native slots, worker never holds JVM address, publication copies back into private direct buffer, no raw native pointer escapes. Native buffers deterministic on close; Java direct buffers GC-managed, no claim true zero-copy. Existing G.0 diagnostic unchanged. Native dispatch is existing clear conversion only; actual full stitching and protected compatibility remain G-B. No engine is instantiated by default or by Settings; requested selection stored OFF default and explicit requested/actual/fallback text and route diagnostics; actual JVM remains for all paths until integrated clear route; no unnecessary player reset while effective route unchanged. Existing DRM, cast, decoder/quality choices untouched. No hidden fallback. Java budget uses observed maxima, OS reserve and available/JVM headroom, no arbitrary low-RAM reservation. One worker pending physical tuning. Added host deterministic randomized state stress, deadline/priority/ownership/close/seek, malformed data and two mandatory Android JNI tests across all APIs; JUnit settings persistence and memory/deadline policy tests. Full CI pending. No native production dispatch until G-B. Sanitizer/leak tooling and actual device-adaptive integration/measurements remain outstanding, do not claim complete G-A acceptance.

G.1 active path review: AppState.startArchiveSingleDecoderComposition selects authorized HLS candidate before clear inspection; protected rolling and TV live paths use ProtectedHevcLiveComposition/ProtectedHevcLiveDashSource; Native Cast separate protected capture. HevcArchiveVodSession uses bounded ArchiveWorkAdmission, separate foreground/prefetch network pools, single-flight ArchiveSegmentDelivery, immutable validated initializations, BoundedArchiveFragmentCache and ArchiveFragmentLease. CmafHttpReader allocates known-length body once; unknown length chunks are coalesced into a final array. Archive composition already logs fetch, parse, rewrite, packaging, verification, producer CPU/slack, process-wide alloc/GC, final bytes and avoided source NAL/tail staging; add aggregate source input bytes to compare boundary bytes without conflating ART process counters with thread allocation. ResourceDataSource.read copies leased bytes to Media3 caller; protected takeOwnership=true transfers output directly into store, read copies to Media3; retain old leases until read release. Remaining G-C copy accounting includes actual Media3 internal sample queue/codec, protected runtime allocation and physical traces. Do not rewrite existing network/DRM paths in G-A.

### Build 854 GREEN; G-A consolidated completion candidate

Build 854 run 45534701432 commit fbdf85d28c28d11d64094d5f1402fdd8f099178f: all five jobs passed; 20 Rust unit tests; existing 106 clear slice differential cases, three CMAF cases, all three Android APIs (including G-A ownership tests), signed upgrade and publication verified. User requires all remaining G-A coding in one consolidated pass. No extra physical sign-off requested now.

G.1: completed foundation audit and explicit boundary instrumentation for finite clear archive, protected rolling and legacy composed delivery. HTTP reader reports exact successful unknown-length coalescing bytes, zero for exact-length response; archive aggregates media-source coalescing and Media3 DataSource copy bytes (completed reads when resource closed), existing source/fragment/CPU/ART counters retained. Protected and legacy sources account exact successful DataSource read bytes, no content or credential logged. These measure only our boundary, not internal HTTP/Media3/codec or thread-exclusive allocations. G-C/G-D physical instrumentation and protected hot-path attribution remain. G.17: observe actual aggregate source and output high-water sizes from JVM composition, attach application context on explicit Rust request, sample OS available/threshold/low-memory and JVM headroom, reject oversized or insufficient budgets rather than lower quality or allocate. Same device budget provider used by native admission API; actual media-engine invocation belongs to G-B. No arena allocation on current playback, no additional player restart. G.6/G.8: native registry owns both bridge allocations as nonmoving UnsafeCell byte vectors; private Java direct views never escape synchronized session; exact address and capacity checked against live opaque handle before forming slices, Java drops views before close, Rust joins worker and frees slots and bridges together. G.0 diagnostic remains GC-managed and independent. JNI ABI bumped to 2, existing diagnostic ABI unchanged. Global checked lease tokens cannot collide across sequential engine sessions. Explicit bridge copy counters report attempted Java input, accepted native input, successful native output and Java output, rejections; no true zero-copy claim. G.7/G.13/G.15: deterministic expired-work rejection, 64 host and 32 Android repeated engine/bridge lifecycles with abandoned read leases, stale-token cross-session rejection, preexisting random/concurrent seek and malformed cases retained. Mandatory pinned nightly host ASan+LSan run on complete Rust lib tests, separate from stable Android build. Host sanitizer covers Rust foundation and native-owned bridge allocations, not Android-specific JNI unsafe calls; Android runtime checks cover those boundaries on three APIs. Added four-ABI JNI symbol gate including nativeBridge. All original build/signing/decoder/DRM gates remain. G-A coding scope complete candidate; automated verification pending; no measured physical TV/16KB acceptance or G-B implementation claimed. Rust toolchain unavailable in local environment, full CI authoritative.

### Build 855 failure correction

Build 855 run 45537596444 job 138947918530 at a7296c216b67b230be6e3ab2a6b6748783455a2c: stable Rust 22/22 and pinned nightly ASan/LSan 22/22 passed; Android ABI host build completed; Kotlin compile stopped at HevcArchiveVodSession.kt lines 431 and 498, conflicting local outputBytes declarations (ByteArray and Long). Reuse original verified fragment's size.toLong() directly for memory-budget observation. No extra media allocation, no native, DRM, test or workflow changes. Full Kotlin, Android JNI and signed publication gates remain pending. Build 854 last fully green. G-A coding completion candidate retained, physical gates deferred.

### Build 856 pre-build contract correction

Build 856 run 45539438007 job 138954044494 at 51c7e6901a1dfdd1d223d9d69dd7e26e11923ccb stopped before SDK/Gradle at PITWALL_CHANGE_CONTRACT=FAIL: Production changed without a changed automated regression test. The Build 855 naming fix had omitted the required regression file in its atomic commit. Later missing evidence artifact is a consequence of the pre-build stop, not a second compilation defect. Preserve the fail-closed contract. This correction adds a regression for the verified fragment identity, unique outputBytes declaration, and budget observation after cache publication; use that same verified fragment for output byte attribution as well. Both plans updated. Validate prospective change set and targeted regression before push. Full CI still required; Build 854 remains the last fully green run. No safety gate weakened.

### Benchmarks and decision gate

Use identical algorithms, fixture bytes, outputs, optimization levels, compiler settings and comparable CPU/thermal states. Include realistic clear and synthetic protected-metadata fixtures, source resolutions and multifeed arrangements, not only trivial loops. Benchmark NAL scanning; bitstream read/write; slice-header operations; binary parsing; CMAF fragment assembly; copying/publication; arena allocation; JNI overhead; concurrent buffer ownership. Keep C++ limited to representative kernels; no alternative full player or production C++ engine.

Record p50/p95/p99/max latency, throughput and deadline slack, CPU time, JVM/native allocations and bytes, copied bytes/count by boundary, Java heap/native heap/process PSS/RSS, sustained thermal and power behavior. Distinguish CI host results from ARM physical results; emulator performance is not TCL/S22 evidence. Record fixture hashes, git SHA, versions, ABI, quality/DRM, feed count, speed, device state, capture duration, warmup, repetitions and variance. Compare JVM reference and Rust under equivalent work.

Rust is selected. Go forward if equal or better than C++, or within the proposed approximately 10% trade-off and sufficient actual playback headroom. 10% is not an industry standard; device deadlines and memory ceilings win. If materially slower, profile and correct actual bottlenecks before drawing conclusions. If repeated representative results show an important playback deficit or mandatory compatibility/deployment failure, present evidence and a contingency recommendation to the user. Do not silently change the selected language. G.0 is a real gate; do not assert feasibility merely from a cross-compile or popularity.

### G-B first implementation batch

The branch was verified at `670b610c00cd62870a79c3ccd4307648d52a1e8f` before implementation. The only changes after tested Build 857 were documentation. Work starts with clear CMAF and uses the live JVM composer as the reference.

- `bmff.rs` and `cmaf_input.rs` borrow bounded box, initialization and media sample ranges. They check the single-track profile, the sample description and `tfhd`/`tfdt`/`trun`/`trex` defaults, full sample coverage, signed or unsigned CTS offsets and checked DTS/PTS. Recognized encryption and sample-group signalling are rejected by the clear route.
- `hevc_nal.rs`, `hevc_prefix.rs` and `hevc_poc.rs` validate NAL extents, early VPS/SPS/PPS references, coded and cropped geometry, CTB addresses, slice prefixes, POC wrap and random-access epochs. Full parameter/slice semantics, tile/RPS/loop-filter compatibility, output VPS/SPS/PPS generation and generation-bound config caching remain open.
- `cmaf_init.rs` writes byte-exact clear initialization from the JVM-validated output configuration. The clear fragment writer writes rewritten headers and borrowed source NAL tails directly into the final CMAF destination buffer. Arena job dispatch, leased playback publication and controlled engine restart still require integration. JVM playback and licensed Widevine remain the effective routes.
- The new host gate reconstructs the 50-frame 1920×540, two-feed clear stream from independently encoded source CMAF using the existing JVM-validated rewrite descriptors and output configuration. It requires exact initialization and fragment bytes, matching packet PTS/DTS/duration/flags and independent FFmpeg pixel hashes. Targeted host preflight also covers malformed extents, oversized inputs, signed timestamp overflow, encryption markers and deterministic parser mutations. Full CI, native tiled 2×2, mixed geometry, protected metadata parity, and Android seek/rotation evidence remain open.

The relevant external references for this batch are Mozilla MP4Parse `e9ee67b` (bounded container extents, MPL-2.0), ChromeOS cros-codecs `a050c6c` (HEVC syntax, BSD-3-Clause), gst-plugins-rs `a0c371f` (fragmented MP4 structure, Apache-2.0) and the already pinned Kvazaar `c997f08` plus FFmpeg for independent pixels and packet probes. The implementation follows the in-repository Kotlin contracts and adds no dependency or third-party source.

Build 858 passed all five jobs, including exact-source API29/31/36 instrumentation and final signed publication. Its tested commit is `26f2721a112e94d3aaf0154b09dd6c15a79ef015`. The first two-feed Rust CMAF gate ran in that build. [Build 858](https://github.com/cooldarshak/apexview/actions/runs/45545086735).

### G-B native slice and output parameter batch

- The Rust path derives slice rewrite boundaries from source NALs. It checks the supported VPS/SPS/PPS structure, tile extents, explicit and predicted short-term references, long-term references, prediction flags, deblocking, loop-filter bits, entry-point syntax and alignment. Unsupported WPP, PCM, extension and scaling-list combinations fail closed within this clear profile.
- Rust writes the output VPS/SPS/PPS and hvcC with checked dimensions, crop, levels, tile partitions and references. The fixture uses the JVM as an independent byte oracle. It feeds neither JVM output parameter sets nor JVM slice rewrite plans to the Rust path.
- Three clear fixture cases pass local exact bytes, 150 independent decoded frames, packet timing and sync flags. Cases are two single-tile feeds at 1920×540, two fully tiled 2×2 sources at 512×128, and two mixed-width 2×2 sources at 640×128. The fixture runner currently supplies the side-by-side layout.
- The CBCS metadata validator checks synthetic inline senc coverage, the effective per-sample IV width, paired saiz and saio sizes/offsets, truncation and overflow. Active tenc/seig resolution, PSSH/KID preservation across key periods, byte-exact protected composition and authorized playback remain open. The Rust validator reads metadata only.
- Open coding work includes the full playback grid/compatibility policy, session-bound configuration caching, production worker dispatch to final leased CMAF, Android integration and controlled restart/fallback with state preservation. Existing phone, TV, Cast and protected routes still use their verified playback implementations. Full source CI and physical evidence are separate gates.

Build 859 passed all five jobs at `938c29342c0e20726cd4711f1583aced0263306a`, with exact Rust-generated initialization and media, 150 decoded frame comparisons over three clear cases, API29/31/36 instrumentation and signed publication. [Build 859](https://github.com/cooldarshak/apexview/actions/runs/45546930661).

### G-B clear atlas worker and Android JNI verification batch

- The clear atlas accepts an explicit raster tile placement. It checks sample geometry, identical track clock, normalized parameter-set compatibility, exact timing, picture identity/reference sets and fragment-start random access before composing. Ambiguous or unsupported input is rejected. Native output writing uses the admitted slot, with no intermediate full rewritten slice or sample.
- The existing single worker, expiry/priority scheduler and exclusive leases handle this real composition command. Configuration is cached for exact initialization bytes, track, layout and generation. Old configuration cannot be reused after a seek. Clear work has an additional conservative workspace admission check. Admission remains subject to physical TV memory measurements.
- The Android bridge adds a typed entry point to queue the bounded clear job packet through the existing registry and synchronized private direct buffers. A new exact-source Android test uses original 2×2 feed CMAF, verifies JVM-identical initialization and media bytes, and checks stale-lease rejection and malformed acknowledgement. The existing conversion, decoder, DRM/TLS and signing tests stay required.
- CBCS metadata checks also validate tenc and equivalent track/fragment seig defaults and mappings. Synthetic changed-KID cases must fail closed. Protected composition, PSSH wiring and authorized device parity remain open.
- The host worker lease and invalidation tests and the three independent clear output cases pass locally. The playback controller, archive producer and media publication path still need a verified integration, including explicit requested/actual status, safe restart and fallback. Existing app routing remains on JVM. Widevine native protected compatibility and device results are open.

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

### Build 866 failure and consolidated G-B continuation

Build 865 remains verified GREEN. Build 866 [exact CI](https://github.com/cooldarshak/apexview/actions/runs/45584014962), commit `3338f12a3d1197713ff78b23f38304c05c5f1875`, build job 139100878127 failed at step 14, Validate physical FrameTimeline comparison tool. The failing test was `test_post834_protected_payload_written_directly_to_final_fragment`, a source-wiring assertion expecting the exact original direct heap-copy expression. No Android instrumentation or signed publication ran for 866; do not infer native storage runtime or full-sequence success. Restore the actual direct-copy expression without changing its semantics and keep the original assertion; add assertions for direct native destination assembly and independent validation.

This consolidated continuation also connects the existing clear native worker to independently decodable rolling HLS fragments on the local playback path. First initialization uses the existing reference. Subsequent eligible fragments use the same clear bridge, validated input reports, actual memory-budget admission, first exact output comparison, independently checked timing and generation-fenced publication. Native OFF, history-dependent fragments, missing device context, and worker/admission rejection remain explicit JVM paths; history-dependent boundaries invalidate the native proof. Rolling preferences take effect at the next fragment boundary; reset retires the engine. Added actual rolling clear fixture tests for contiguous timestamps, native output, and OFF parity, plus full 4/6/8 logical clear source-slot composition comparisons using the two independently encoded source fixtures. The larger logical feed count fixtures reuse the source media, not independently encoded F1 feeds. Retain previous independent decode, malformed input, CBCS and ownership gates. Targeted original failed regression and new rolling source-wiring test both passed on staged exact source. Full Android/Rust compilation, all API gates and signed publication pending candidate CI; actual F1/device proof remains pending.

G-B coverage and acceptance remain open until the entire candidate passes. Known current bounds: history-dependent clear profiles and other unsupported native inputs still use reason-coded fallback; cast stitching integration is still not native-enabled (G-C coverage requirement); no actual authorized protected playback or phone/TV performance proof. Build 866's fixed maximum native fragment storage is a ceiling, not an approved physical device allocation target: integrate and measure combined native/JVM/codec budget in G-C. Neither the previous hybrid implementation nor the strengthened coverage requirement justifies rewriting the whole app. No new protected-media algorithm, security rule, credential flow, or casting transport is introduced by this batch.


## 10. Architecture G master engineering plan — G.1–G.20

Build 857 is the verified G-A foundation baseline. G.0 is signed off by the user. G-A foundation coding and automated gates are complete; G-B clear media and HEVC syntax implementation is in progress. G-C and G-D remain pending. Existing diagnostic kernels do not establish production stitching. Update coding, automated, physical and measured-result columns with exact evidence. P0 gates protect correctness, safety and compatibility; P1 work is grouped into coherent follow-up batches. Architecture G extends the original plan; no independent competing roadmap.

| ID | Workstream / required deliverable | Priority | Coding | Automated | Physical / measurement |
| --- | --- | --- | --- | --- | --- |
| G.1 | Audit/profile existing JVM stitcher, establish repeatable baseline | P0 | Foundation audit + boundary counters complete; physical profile G-C/D | Build 857 GREEN; foundation gates passed | Deferred |
| G.2 | Fixed-capacity native input/output memory arenas | P0 | Bounded native arena foundation complete | Build 857 GREEN; foundation gates passed | Deferred |
| G.3 | Port native HEVC parsing and stitching with exact invariants | P0 | Partial pending candidate gates. Native slice syntax, references, POC and parameter-set output; finite clear local bridge green Build 862; protected direct batch green Build 865; exact cross-picture native configuration cache and independent rolling clear bridge candidates; history-dependent and other unsupported profiles remain explicit fallback | Original clear and Rust regressions green 865; expanded route and 4/6/8 slot tests pending | Pending |
| G.4 | Native CMAF moof/mdat assembly, exact sizes/offsets/timestamps | P0 | Partial. Clear init/media reader, exact writers, borrowed tails and native worker/JNI leases; real local archive publication verified Build 861; metadata-only protected header green Build 862; native protected sample-map differential green Build 863; direct range-map/moof green Build 865; native-owned protected output arena and direct local DASH publication candidate; JVM-authorized borrowed-byte copy and protected init retained | Build 860 clear fixture and API29/31/36 native worker gates passed | Pending |
| G.5 | Preserve existing DRM compatibility validations and authorized pipeline | P0 | Existing authorized route retained. Read-only CBCS inline auxiliary, tenc and equivalent seig checks; exact protected moof header green Build 862; protected clear-prefix and range-map differential green Build 863; direct native computation green Build 865; native-owned local output/Media3 lease integration candidate; existing JVM authorization/alignment/opaque-byte invariants retained; authorized physical parity pending | Clear rejection and synthetic CBCS coverage tests. Full protected corpus pending | Pending |
| G.6 | Explicit buffer ownership, leases and lifecycle | P0 | Deterministic native slots/bridge ownership and joined close complete | Build 857 GREEN; foundation gates passed | Deferred |
| G.7 | Bounded queues, backpressure and deadline-aware producer/consumer coordination | P0 | Bounded deadline scheduler and expiry tests complete | Build 857 GREEN; foundation gates passed | Deferred |
| G.8 | Reusable Direct ByteBuffer integration, checked JNI ownership | P0 | Checked native-owned direct bridge, batch JNI and copy accounting complete | Build 857 GREEN; foundation gates passed | Deferred |
| G.9 | Reduce network-to-native copies with measured boundary accounting | P1 | Not started | Pending | Pending |
| G.10 | Reduce Media3 publication copies without speculative fork | P1 | Not started | Pending | Pending |
| G.11 | ARM NEON optimization only where profiling justifies it | P1 | Not started | Pending | Pending |
| G.12 | Allocation-free steady-state stitching hot path where achievable | P1 | Not started | Pending | Pending |
| G.13 | Cancellation, seek-generation invalidation, stale publication prevention | P1 | Generation cancellation and globally unique stale leases complete | Build 857 GREEN; foundation gates passed | Deferred |
| G.14 | Compatible feed switching and bounded priority prefetch | P1 | Not started | Pending | Pending |
| G.15 | Leak detection, fuzzing, sanitizer and safety tests | P0 | Foundation stress, ASan/LSan and Android lifecycle gates added | Build 857 GREEN; foundation gates passed | Deferred |
| G.16 | Byte-for-byte JVM/native differential regression plus independent decode | P0 | Partial. Full clear differential for single-tile, 2×2 and mixed fixtures, native lease/fence and synthetic CBCS metadata tests; added 4/6/8 logical clear slot composition and actual rolling clear route tests | Build 865 green 50-picture/400-slice differential; Build 866 failed source-wiring gate before instrumentation; corrected source gate and new rolling wiring gate passed locally, full candidate CI pending | Pending complete supported-profile and authorized physical parity |
| G.17 | Adaptive memory budgets for low-RAM devices | P0 | Observed device-headroom planning and admission provider complete; G-B dispatch pending | Build 857 GREEN; foundation gates passed | Deferred |
| G.18 | Sustained CPU/memory/power/thermal and playback profiling | P0 | Not started | Pending | Pending |
| G.19 | Preserve every existing playback feature and mode; common Rust stitcher on all applicable local and Cast paths, no artificial stitching for direct modes | P0 | Coverage clarified; finite clear and local protected candidates, independent rolling clear candidate; Cast integration remains G-C | Pending complete route/feature matrix | Pending matched phone/TV and Cast evidence |
| G.20 | Android phone/TV ABI, API, alignment and signed update compatibility | P0 | Four-ABI symbols, alignment, signed upgrade and runtime gates retained | Build 857 GREEN; foundation gates passed | Deferred |

### Proposed consolidated implementation batches (refine after audit)

- G.0: feasibility, baseline, minimal JNI+arena, pinned toolchain, CI and physical verification. No default-engine change.
- G-A: correctness foundation — G.1/G.2/G.6/G.7/G.8/G.13/G.15/G.17/G.20; leased bounded memory, cancellation, CI/ABI/diagnostics and controlled engine interface.
- G-B: exact native HEVC and CMAF port — G.3/G.4/G.5/G.16, with reference-output and independent decode tests; maintain existing authorization and fallback. Start with clear and independently gate protected compatibility.
- G-C: integration and performance — G.9/G.10/G.11/G.12/G.14 plus applicable producer/receiver stitching coverage from G.19, measured copies, frame deadlines, compatible switching and prefetch. Complete all supported Cast stitching routes; direct paths do not stitch. Do not optimize unmeasured paths.
- G-D: parity and production acceptance — G.18/G.19 plus outstanding gates from all earlier rows. Matched A/B physical tests and full-race endurance before making Rust default.

Dependencies overlap intentionally: safety/differential/compatibility checks run throughout, not only in the final batch. User may revise batch boundaries after G.0. Never trigger isolated tiny builds or pretend a failed test is acceptance.

### 10.1 Mandatory refinement ledger — eight improvements integrated into existing IDs

These are additional acceptance requirements within G.0/G.1–G.20 and the original workstreams, not a separate competing roadmap. Status reflects Build 857; remaining media integration and physical acceptance are not waived. Audit existing implementations first; retain and reuse any already-proven mechanisms rather than duplicating them.

| Refinement | Workstream mapping | Implementation and verification | Status |
| --- | --- | --- | --- |
| R1 Fused processing | G.3, G.4, G.9, G.12, G.16 | Fuse compatible scan/parse/rewrite/assembly passes; use checked size planning and write to final leased output where permitted; eliminate complete intermediate materializations only after equivalence; measure bytes copied, allocations, CPU and p99; independent decode and protected invariant tests. | Borrowed clear source tails and direct fragment assembly verified on host fixtures. Leased production engine pending G-B/C |
| R2 Deadline-aware scheduling | G.7, G.13, G.14 | Prioritize earliest required playback fragments and seeks over speculative prefetch; derive deadlines/slack from media timeline, playback speed, buffer and measured delivery; bound queues, avoid starvation and respect cancellation/generation; test slow CDN, stalled consumer, seeks and speed changes. | Foundation complete; actual media deadlines/switching integration pending |
| R3 Device-adaptive arenas | G.2, G.6, G.17 | Size bounded arenas using actual fragment distribution, feed geometry, process headroom, low-RAM indicators and codec/overlay needs; enforce total ceiling including in-flight leases and old/new generations; controlled exhaustion, trim at safe boundaries, no arbitrary large TV preallocation; measure native/JVM/PSS and race endurance. | Foundation complete; physical sizing and peak PSS pending |
| R4 Adaptive native worker pool | G.6, G.7, G.13, G.18 | Reusable bounded workers with conservative measured concurrency; preserve order and dependencies, leave CPU capacity for codec/audio/UI/GL/Cast; no thread per feed or forced use of all cores; test 1-worker vs measured alternatives, thermal/CPU contention, cancellation and shutdown; no model-specific hacks. | One bounded native worker implemented; adaptive concurrency tuning pending G-C/D |
| R5 Batched JNI | G.8, G.9, G.12 | Submit fragment/work batches instead of per-NAL JNI crossings, reuse direct views and metadata descriptors; measure JNI call rate/copy/allocation cost; retain checked handles, leases and no unwind across JNI; validate both ARM ABIs and teardown. Android JNI guidance: https://developer.android.com/training/articles/perf-jni | Foundation complete; real fragment dispatch/copy reduction pending |
| R6 Reusable stream analysis | G.3, G.14, G.16 | Bounded cache of validated stream configuration and safe reusable parsing results keyed by stream identity, rendition, codec configuration and relevant generation; invalidate/revalidate at discontinuity, configuration change, seek or track replacement as required; never reuse frame-varying or security-sensitive metadata incorrectly; test stale cache and differential results. | Existing JVM reuse retained; exact native init/layout/generation cache verified on host; playback lifecycle integration pending |
| R7 Layout-aware adaptive quality | G.14, G.17, G.19, existing P1.3 | When Auto is selected, consider actual visible feed dimensions, available compatible renditions, bandwidth, decoder constraints and thermal/memory headroom; use hysteresis to avoid oscillation; preserve explicit manual per-feed selections and existing output geometry, no silent downgrade; verify resize, saved layouts, portrait/TV and Cast. | Pending G-C/D; current per-feed preferences preserved |
| R8 End-to-end attribution | G.0, G.1, G.18, existing P0.3/P1.2 | Correlate generation, fragment/sample IDs and timestamps across CDN, queueing, processing, publication, Media3, decoder, surface/presentation, overlay and Cast; separate CPU and wall time, copy bytes, GC, missed deadlines and frame drops; low-overhead bounded diagnostics with no credentials/media; matched physical A/B. | Boundary counters implemented; full end-to-end physical attribution pending G-C/D |

The scheduler must respond to downstream backpressure and bounded buffer availability; faster stitching must not blindly increase production or SurfaceFlinger congestion. The worker pool and arenas are common infrastructure but must not create contention in modes that do not stitch. Use measured concurrency and bounded memory rather than assuming the Rust port alone fixes TV rendering.

### 10.2 Consolidated phase additions and exit gates

| Phase | Added work and exit evidence |
| --- | --- |
| G.0 | Reopen TV logs/traces; profile latest JVM engine and actual Media3 copy boundaries; verify F1 architecture references; feasibility prototype, test ABI/library loading and mobile-only CI, measure baseline and equivalent Rust/JVM/C++ kernels. No production switch. |
| G-A | Common engine contract, persistent selector and diagnostics, safe restart/fallback, leased arenas, bounded worker/scheduler foundation and CI; no claim that stub/prototype is functional Rust playback. Gate on lifecycle, memory budget, queue and selection tests. |
| G-B | Exact HEVC/CMAF port with fused processing and reusable analysis; independently validate clear and authorized protected modes; byte-exact required fixtures, independent decode/pixels, metadata and negative tests. |
| G-C | Batch JNI, optimize actual network/native/Media3 boundaries, tune concurrency and adaptive memory/quality based on physical evidence; measure playback slack, tail latency and system contention. |
| G-D | Matched on-device A/B across feature matrix, casts, endurance and upgrades; verify selector remains available, default stays OFF unless separately approved, all fallback reasons exposed. No unverified performance claims or release certification. |

## 11. Native arena and ownership design

Investigate bounded preallocated input/output slabs/ring slots, fixed-capacity pools, explicit producer/consumer state, leases, generation IDs, backpressure, adaptive memory ceilings, overflow/error handling, cancel/seek invalidation, safe shutdown and thread lifecycle. Do not move unbounded JVM memory into native heap. Account for all input, output, scratch, retained JNI views, HTTP buffering, Media3 and codec memory. Include peak and steady-state budget; never reserve an arbitrary large arena for low-RAM TV. Choose capacities from measured actual fragment and feed distributions. Reject malformed or oversized input safely; no unchecked sizes, integer overflow, out-of-bounds or use-after-free.

```text
FREE → WRITING → READY → READING → RELEASED → FREE
```

One authoritative owner per writable slot, immutable publication after READY, no recycle until every read lease (Kotlin/Media3/native) ends. Generation changes prevent old seeks from publishing but never invalidate an outstanding pointer early. Bound in-flight work and report backpressure rather than overwriting bytes. On teardown stop accepting work, cancel producers, drain/release leases, join native workers and only then free memory; never block UI/GL unnecessarily. Validate length, capacity, alignment, generation and handle ownership at each JNI transition. Use opaque checked handles, no raw pointers exposed as arbitrary longs. JNI references and thread attach/detach rules must be explicit. Convert native failures to controlled errors, never unwind across JNI, and retain error attribution. Rust memory guarantees stop at unsafe JNI and external owners; keep unsafe localized, documented and reviewed. Test double-release, stale generation, close/read race, queue saturation, repeated seek, cancellation, malformed input, concurrent shutdown and native leak behavior.

## 12. Media3 integration and low-copy limit

Audit the pinned Media3 version and real current custom DataSource/sample delivery, SampleDataQueue, allocator, DecoderInputBuffer, MediaCodec input ownership, DRM and fragment materialization. A native Direct ByteBuffer does not guarantee no copies inside Media3/codec. Map each boundary (network → JVM/native input → parser → assembly → output → Media3 queue → decoder); measure copy count/bytes and allocations, including JNI wrapper allocation. Reuse bounded views rather than creating one wrapper per NAL/frame when feasible. Preserve the existing safe delivery contract and exact seek/length/read semantics. Investigate custom sample delivery only when current APIs and profiling justify it. Prefer the least invasive approach; no Media3 fork or replacement player without separate demonstrated necessity and user approval. Do not claim true end-to-end zero-copy without proof across every boundary.

### 12.1 Three-level Media3 publication strategy

- Level 1 (first integration): retain existing Media3 DataSource/extractor/sample delivery and replace only stitching hot path with leased native output. Establish correctness and measure actual copies; standard DataReader uses a Java byte array, so direct native buffers do not establish end-to-end zero-copy.
- Level 2 (profiling-driven): optimize reusable bridge buffers, read/write granularity and supported sample publication interfaces where evidence shows avoidable copies/allocations. Preserve seek/length/read behavior, ordering, sample timestamps, flags, encryption metadata, ownership and cancellation.
- Level 3 (conditional): investigate a compatible custom sample delivery path only if Level 1/2 measurement proves a meaningful residual bottleneck. Prove authorized clear/protected playback, audio/captions, lifecycle and full regression parity before adoption; no Media3 fork, replacement player, DRM bypass or security-policy change without separate authorization (DRM bypass is never allowed).
- For each level record network → JVM/native → parser/assembly → output → Media3 → decoder copies and bytes, allocation rates, buffer lifetime, p95/p99 latency, PSS and media-seconds/wall-second. Check pinned Media3 source and APIs (https://github.com/androidx/media); do not infer codec internal zero-copy from a Direct ByteBuffer.
- If supported APIs cannot remove a boundary copy safely, retain it and report the limitation; performance optimization must not trade correctness or feature parity.

## 13. DRM and security requirements

Preserve legitimate F1 TV login, entitlement, license acquisition, existing Media3/Widevine CDM and DRM session lifecycle, security-level behavior, secure output, existing encrypted-media compatibility checks and Software DRM setting semantics. Do not decrypt protected media outside the authorized playback pipeline, extract keys, weaken security or bypass DRM. Only process data permitted by existing authorized pipeline; preserve encrypted payload and required metadata invariants. Audit existing validated CBCS ranges, IVs, subsample boundaries, KID/PSSH and alignment checks, and fail closed on unsupported combinations. Do not add speculative protected transformations, export protected pixels/media or send credentials to third parties. Clear output success is not proof of protected compatibility. Verify protected handling separately using existing permitted synthetic metadata fixtures and authorized device playback; never include keys or credentials in diagnostics or benchmark artifacts. Preserve Pitwall mutual TLS/pinning and Google Cast content-policy boundaries.

## 14. Integration and controlled migration

```text
Existing Playback Coordinator
  ↓
Common stitching-engine interface
  ├─ Existing JVM engine (reference)
  └─ Rust native engine (diagnostic A/B initially)
  ↓
Existing Media3 playback pipeline
```

Reference JVM engine provides correctness baseline, performance baseline and controlled fallback. Preserve exactly one video decoder in single-decoder mode; engine selection is not decoder-count selection. Multiple-decoder mode remains functional. Prefer feature-gated native selection for controlled A/B, deterministic tests and safe rollback; no silent quality/DRM changes. Keep native module dormant for unsupported modes until explicitly validated; do not hide native errors through unreported fallback. Do not remove working JVM code prematurely. Native default only after G.0, differential, compatibility, authorized playback and physical performance gates pass. Preserve current logs for decoder count, actual source/quality/renderer and frame timing.

### 14.1 Mandatory persistent Settings toggle (user-approved)

**Exact visible label:** Native Zero-Copy On-Device Stitching using Rust

**Suggested explanatory text:** Experimental native HEVC stitching with reduced allocations and memory copying. Applies to supported stitching paths. Changing this setting safely restarts the affected playback pipeline.

The visible label is requested by the user; the technical term remains native low-copy until actual end-to-end zero-copy is proven. Do not advertise unsupported performance or DRM claims.

| Requirement | Required behavior |
| --- | --- |
| Default / migration | OFF when unset, including upgrades. Keep the existing JVM engine as a permanent selectable path. Persist explicit choice in existing settings storage across restart and signed app updates. No automatic default flip without separate approval. |
| OFF | Existing Kotlin/JVM architecture. No active Rust engine, native arenas or native workers allocated for stitching. |
| ON | Select Rust for every eligible independently validated stitching path, local and Cast, clear and authorized protected, live and replay. Direct/non-stitching paths report NOT_APPLICABLE. Do not claim active Rust for an unimplemented prototype, unsupported mode or fallback; remaining applicable integration is an open coverage item. |
| Phone and Android TV | Same preference and behavior; integrate with existing settings UI/Material 3, accessibility, focus and DPAD support; use existing localization patterns. |
| Decoder mode | Independent of decoder selection; exactly one video decoder in existing single-decoder mode; multiple-decoder and non-stitching paths remain unchanged unless independently validated. A passthrough/non-stitching mode reports Not applicable. |
| DRM / render / quality | Software DRM, clear renderer, quality, layout and captions remain independent; no silent changes to security, rendition, decoder count or surface mode. Clear and protected native support independently gated. |
| Active playback change | Apply via controlled existing coordinator restart at safe boundary, invalidate stale generations, cancel/drain workers and release leases, preserve position and play/pause, layout, feed choices, audio/CC and user settings where supported. Do not run two live stitching engines concurrently or free in-use memory. For active casting coordinate restart or explicitly defer until safe; no silent receiver changes. |
| Unsupported native path | Fail closed for security/incompatible content. Where the existing authorized JVM path is supported, controlled fallback may be used with explicit reason and actual engine indicator; no retries that loop or obscure errors. Retain requested ON state, do not quietly rewrite it to OFF. |
| Native load / operational error | Recoverable failures may fall back through a safe full reset after teardown. Native process abort/segfault cannot be reliably caught in Kotlin; retain matching symbols, diagnose on restart and use a bounded crash-recovery policy that exposes the actual engine. |
| Diagnostics | Report requested engine, actual engine (JVM / Rust / Not applicable / unavailable), build SHA/ABI, eligibility, fallback reason, generation, engine initialization, copy/queue/deadline metrics and active decoder count. Never expose credentials, keys or protected media. |
| A/B verification | Same signed APK, toggle only, identical session, actual quality, feed count, speed, DRM, renderer, layout, refresh and thermal/network state; reject or separately classify fallback runs. Test repeated switching, restart, process death, seek, casting, native load failure, cancellation, setting persistence and upgrades. |

Selection model: requested preference → eligibility/capability/security check → one active engine for the current playback generation → explicit diagnostic state. No implementation in this planning revision. G-A owns preference/selector foundation; G-B independently enables proven eligible clear/protected paths; G-C/G-D validate performance and feature parity.

## 15. Illustrative repository structure

Adapt to the actual repository; do not duplicate modules or reorganize unrelated application code.

```text
apexview/
  app/                         # existing Android application
  native/
    Cargo.toml
    Cargo.lock
    rust-toolchain.toml
    src/
      lib.rs
      hevc.rs
      cmaf.rs
      arena.rs
      stitcher.rs
      jni_bridge.rs
    tests/
    benches/
    fuzz/
  .github/workflows/           # extend existing coordinated workflow
```

Separate pure Rust algorithms, arena/ownership and JNI. Keep fixture manifests and benchmark contracts reviewable and reproducible. Use the current repository's actual paths discovered during audit.

## 16. GitHub Actions and mobile-operable CI/CD

Extend, do not replace, the existing workflow. Install pinned Rust toolchain, SDK/NDK/cargo-ndk; use locked dependency/cache keys; rustfmt, Clippy, Cargo tests; differential and independent regression tests; applicable fuzzing/sanitizer checks; build all supported ABIs; inspect ELF/ZIP alignment, linked dependencies and native libraries; run existing Kotlin/JVM and emulator tests; package the original signed APK; retain exact matching native debug symbols/build IDs; publish machine-readable benchmark/test reports, redacted diagnostics and symbolication artifacts. Preserve mandatory old gates including Android 10/12/16 TLS and key migration, RenderNode, TextureView/SurfaceView, baked capture, HEVC independent decode and archive delivery. Never weaken gates to obtain green.

CI should allow a phone-only user to install signed APK and access artifacts. Test native library loading and JNI on emulator(s) and real phone/TV; validate 16KB runtime where available and separately report missing hardware evidence. Use caching and meaningful consolidated batches; GitHub Actions limits apply. Protect signing secrets and auth credentials; do not log them or publish protected content. Use reproducible build inputs, compare APK signature, package ID and upgrade behavior; retain symbol artifact matched to exact APK. Where hosted emulators cannot supply an ABI/device/performance result, record blocker, not a false pass.

## 17. Device validation and benchmark matrix

**Samsung Galaxy S22 Ultra:** ARM64, up to 120Hz adaptive. Verify current OS/API, ABI/page size and capabilities from device. Test 60Hz/adaptive 120Hz, portrait/landscape, single/multiple feeds, 1×/1.25×/1.5×/2× where supported, seek/lap seek, sustained multiview and Cast. Use aShell, Bugjaeger and Perfetto from phone.

**TCL 55C655 Android TV:** Android TV 12 reported, approximately 2GB RAM subject to actual verification; inspect actual userspace ABI, available memory/low-RAM status, refresh modes and page size. Test Software DRM ON/OFF where authorized and supported; clear SurfaceView experiment ON/OFF; TextureView/SurfaceView; remote/DPAD and all layouts. Test 1, 3 and more feeds where supported; actual mixed resolutions and per-feed selections; sustained playback. Prior Build 839 user observations: multifeed starts across tested DRM/renderer combinations; three feeds plus map/tower had interruptions. Do not assume Build 842 performance or physical pairing fixed without new captures.

Matched JVM vs Rust A/B: same APK/fixture or exact source versions, source session, quality, clock, codec, render path, speed, layout/overlays, network and thermal conditions; counterbalance test order and record warm/cold results. Run clear and authorized Widevine independently; single and multiple decoder; phone and TV; Google Cast and Pitwall Cast; cold start, seek, restart, feed switch, pause/resume, disconnect, upgrade and full race endurance. For network-dependent cases distinguish CDN stalls from processing delay; record actual frame release/latch/presentation instead of interpreting EGL swaps as scanout. Collect logs/traces in user-accessible artifacts, identify exact build and settings, and report missing evidence. No device-specific hacks.

## 18. Performance goals and acceptance criteria

These are proposed engineering targets, not achieved measurements.

| Metric | Proposed acceptance target |
| --- | --- |
| JVM hot-path allocations | Zero steady-state stitching allocations where technically achievable; quantify exceptions |
| Native heap allocation | Zero steady-state stitching hot-path allocations after setup, bounded memory |
| Stitching CPU time | Target ≥30% reduction vs matched current JVM baseline |
| Memory copying | Minimize avoidable copies; count actual copies and bytes |
| Total process memory | Lower than reference, not JVM-to-native displacement |
| Native leaks | Zero detected in applicable tests and sustained operation |
| Video decoder count | Exactly one in single-decoder mode; no regression to multiple decoder |
| Composed canvas | Preserve existing geometry/resolution behavior |
| Widevine | No downgrade or authorization change |
| Audio and features | No A/V regression; all features preserved |
| Sustained playback | Full race-duration physical validation |
| Dropped frames/stuttering | Measurable reduction; no unsupported scanout claims |
| Feed switching / seek | Measurable latency reduction vs reference |
| Thermal / battery / power | Measurable improvement or no regression |
| Correctness | Deterministic equivalent output, required byte-exact fixtures, independent decode/pixels and metadata checks |
| Stability | No new crashes, corruption, lifecycle defects or leaks in agreed acceptance matrix |

For 50fps sources, if every frame is processed:

| Playback speed | Required throughput | Average frame interval |
| --- | --- | --- |
| 1× | 50 fps | 20 ms |
| 1.25× | 62.5 fps | 16 ms |
| 1.5× | 75 fps | 13.33 ms |
| 2× | 100 fps | 10 ms |

These are throughput references, not end-to-end deadlines. Segment duration, bursts, feed count, network, buffering, decoder and display add distinct constraints. Measure sustained media-seconds/wall-second, tail latency/deadline slack and actual rendered frames; compare p95/p99 and worst-case behavior, not averages alone. Do not treat microbenchmarks as playback proof. Record measured vs target separately, and explicitly document why any target cannot be achieved on supported hardware.

### 18.1 Additional acceptance gates for revision 2

- Keep the existing 30% stitching CPU reduction as a proposed target, not a release claim. Compare matched p50/p95/p99/worst-case latency, throughput, allocation/copy counts and total process memory; avoid moving JVM allocations into unbounded native memory.
- Normal 50fps playback and 1.5×/75fps processing throughput where supported; require sustained media delivery with measurable deadline slack, frame-drop/stutter improvement and no A/V, security, quality or thermal regressions. A 20 ms or 13.33 ms average source-frame interval is not a strict per-fragment deadline.
- Collect fresh physical TCL TV and S22 traces before/after and during long race-duration sessions; run same-build requested/actual engine A/B, exclude unreported fallback, record unsupported combinations separately. Compare source processing with surface presentation and Cast, not only benchmark kernels or EGL swaps.
- No added cloud infrastructure required by the architecture (target zero incremental hosted stitching costs; existing CI/service limits still apply).
- Maintain separate BufferQueue/surface, codec, scheduling/refresh, network and baked Cast investigations within the original 16-workstream plan. Android FrameTimeline reference: https://perfetto.dev/docs/data-sources/frametimeline . Do not claim native migration fixes these by itself.
- Update coding, automated, physical and measured status separately for every G workstream and refinement. Record exact commit/build and artifacts. No production-default approval implied by green CI.

## 19. Complete feature parity requirement

Retain clear HEVC; authorized Widevine HEVC; single/multiple decoder; single/multifeed; compatible mixed-resolution sources; per-feed quality and adaptive quality; existing composed canvas; Google Cast and Pitwall Cast including baked overlays; commentary selection, A/V synchronization; captions (rolling HLS and alternate programme), CC text size/background opacity; timing tower, track map, gear/speed/lap/driver overlays; TV/main+strip/single/split/grid presets; free/snap layouts; multiple saved named layouts and persistence; positioning/resizing, opacity/font size/dot/abbreviation controls, map lock/DPAD reveal; portrait/landscape; TV remote/DPAD; seek/lap seek/accelerated playback; Continue Watching/delete; login/DRM; all existing settings including Software DRM and clear renderer experiment. Preserve output/screenshot policy, thermal/quality recommendations and receiver compatibility. Verify actual feature wiring in current repo, do not assume earlier implementation is complete or add unsupported mixed-resolution combinations. Test local and Cast geometry, overlay cadence and caption controls. Never trade a working feature for a benchmark score.

## 20. Issues Architecture G will not necessarily solve

Hardware codec throughput limits; vendor MediaCodec defects; secure output and Widevine device policy; display-driven BufferQueue backpressure; surface lifecycle; frame scheduling and 120Hz adaptive behavior; CDN congestion and internet buffering; Google Cast receiver limitations; Media3 internal copies. Continue root-cause investigation in the original plan and isolate effects. A native port cannot by itself guarantee display-level smoothness, instant switching or zero GC across the whole app.

## 21. Architecture G versus cloud stitching

Investigate on-device G first: no added network hop, dedicated cloud costs, server maintenance or free-cloud quota dependency; reuse existing engine and authorized local playback; potential CPU and memory gains. Do not implement cloud stitching in this plan. Cloud remains a separate alternative if measured on-device architecture cannot meet goals, requiring its own authorization/security, cost and latency assessment.

## 22. Hard engineering rules

1. Do not restart project, create duplicate apps or unnecessary branches.
2. Audit latest repository, plan and CI before changing source.
3. Preserve original 16-workstream plan; maintain one consolidated G ledger here.
4. Rust is the chosen production language; validate before port; no full parallel C++ engine.
5. Group related work into meaningful consolidated changes; no one-/two-line speculative build churn.
6. Finish agreed coding batch before stopping unless a genuine blocker prevents it; disclose exact unfinished work and verification.
7. Separate coding, automation, physical acceptance and measured performance. Never assert 100% when partial.
8. Do not claim speedups, end-to-end zero-copy, instant switching, zero GC, defect-free behavior or hardware acceptance without evidence.
9. No device-specific hacks, proprietary SDKs, paid OpenF1 or unauthorized dependencies.
10. Keep login, authorization, DRM, protected output, TLS/pinning, phone, TV and casting intact.
11. Preserve mobile-only development; no PC or paid development infrastructure requirement.
12. Use safe Rust wherever possible; audit small unsafe JNI boundaries and never evade safety checks for speed.
13. Prefer measurable improvements to trends; preserve working functionality during migration.
14. Keep current signed update and package behavior, tests, and rollback path; do not move main without permission.
15. Explicitly identify blockers, assumptions and decisions; do not promise unexecuted work or unsupported performance.
16. Retain one-decoder invariant in single-decoder mode and maintain existing multiple-decoder functionality.
17. No new source implementation or build from this handover alone. Proceed when explicitly instructed.

### Branch and change-control addendum

The user explicitly authorized a new dedicated branch for Architecture G and a consolidated updated plan, not implementation. All G changes must stay on feature/architecture-g-rust-stitching; main and feature/pitwall-performance-audit-plan remain unchanged unless separately authorized. Keep this inherited handover filename as the single active revised plan in the new branch to avoid duplicate contradictory ledgers. Preserve parent 16-workstream plan and 14-item status. No source, CI or dependency changes in this documentation pass; do not manually trigger a build or open a merge request. Existing push-triggered CI, if any, is governed by existing workflow configuration.

## 23. Historical initial-chat execution checklist (superseded by Build 857 checkpoint above; do not restart G.0)

1. Connect GitHub and inspect latest main/working branch refs, source, plans, latest successful signed APK and CI logs.
2. Confirm Build 842 or newer exact baseline; read the existing plan and all relevant handover/reference docs.
3. Audit current JVM HEVC/CMAF code, protected constraints and Media3 version. Identify genuine remaining allocations/copies and measured bottlenecks after Build 842.
4. Verify all external references, Rust Android target/toolchain compatibility, page sizes, NDK/JNI/cargo-ndk versions, licenses and actual target ABI.
5. Update this existing G ledger with evidence, refine measurable acceptance criteria and coherent batch boundaries. Do not create a competing G plan; link from the original plan when implementing G.
6. Confirm mobile-only CI/device workflow and physical benchmark protocol; identify genuine blockers.
7. When instructed to implement, begin consolidated Phase G.0. Keep the Rust prototype diagnostic and no production default switch. Establish representative JVM/Rust/C++ control benchmarks and evidence-based go/no-go.
8. Only after successful G.0, undertake staged G.1–G.20 implementation with reference parity and existing safeguards. If feasibility fails, investigate and report options; do not silently change language or waive gates.
9. At each consolidated pass verify exact source changes, tests, branch head and workflow result; record physical evidence separately. Do not stop halfway unless blocked.

## 24. Decision and implementation status at this handover

| Item | Status |
| --- | --- |
| Last exact tested baseline | Build 857 GREEN; 9dcbef0abc3fb92c35eb6c57d0db665cdb254fb1 |
| G.0 | Signed off by user; physical deferrals carried forward |
| G-A | Foundation coding and automated verification complete; physical acceptance open |
| G-B | Started with the clear media path and differential fixture. See workstream status and remaining integration above. |
| G-C | Pending: G.9/G.10/G.11/G.12/G.14 |
| G-D | Pending: G.18/G.19 and carried physical gates |
| User settings toggle | Implemented and persisted, OFF by default; actual JVM until integrated eligibility |
| Existing original 13-item coding scope and 16-workstream plan | Preserved; no new release acceptance claim |
| Native code | G-A bounded native worker and native-owned private direct bridge; current dispatch is clear conversion, not full media stitching |
| Rust toolchain/CI | Pinned and verified on four Android ABIs; mobile-only development demonstrated |
| Phone physical | G.0 diagnostic pass; full G-A and playback A/B pending |
| TV and 16KB physical | Deferred; not waived |
| DRM | Existing authorized paths unchanged; actual native protected support pending G-B |
| Main and parent branches | Unchanged; no merge or release authorization |

**Use the [Build 857 new-chat handover](PITWALL_TV_BUILD_857_ARCHITECTURE_G_HANDOVER_2026-11-25.md) for next execution.** Build 857 is the reference, and only the user can authorize next phase or default switch. Preserve every feature and original plan.

