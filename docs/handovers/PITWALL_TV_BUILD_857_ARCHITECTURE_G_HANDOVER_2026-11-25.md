# PITWALL TV / APEXVIEW — BUILD 857 ARCHITECTURE G HANDOVER

**Copy or attach this entire Markdown file to the new chat.** Continue the existing project; all prior constraints and completed work carry forward. This is a dated, read-only checkpoint of the active plans, not a new competing plan.

## Current checkpoint and mandatory next action

**As of 25 November 2026, Build 857 is verified GREEN.** G.0 has explicit user sign-off (given after Build 853); G-A foundation coding and automated verification are complete. Do not repeat G.0/G-A or claim production Rust stitching already works. The next authorized implementation phase, when the user instructs you to start, is **G-B: exact native HEVC/CMAF stitching and protected compatibility (G.3, G.4, G.5, G.16)**. Do not start G-C/G-D or merge branches without authorization. Physical TCL, matched playback/DRM and 16KB runtime gates remain open. This new file is a handover snapshot, not a competing live plan; maintain the [Architecture G master plan](PITWALL_TV_ARCHITECTURE_G_RUST_HANDOVER.md) and [original performance plan](../PITWALL_PERFORMANCE_AUDIT_PLAN.md) in subsequent commits.

| Item | Verified value |
| --- | --- |
| Repository | cooldarshak/apexview |
| Working branch | feature/architecture-g-rust-stitching |
| Last tested source | 9dcbef0abc3fb92c35eb6c57d0db665cdb254fb1 |
| Build | [857 / run 45539807100](https://github.com/cooldarshak/apexview/actions/runs/45539807100), all five jobs successful |
| Debug app | app.apexview_custom; versionName 1.0.1714, versionCode 101714 (version scheme 100000 + 2*857) |
| Main (do not modify) | 7d42ae9b3c9178f838717700586bfb7fb50f4ec7 |
| Parent (do not modify) | feature/pitwall-performance-audit-plan @ 1c479fd6fa6ab5d8b0809d3e37f51ce6336a9028 |
| Previous working production checkpoint | Build 842 / b9752ac9115b252c9de1f444662b8eb1252e751e |
| Signed APK | [artifact 14989576250](https://github.com/cooldarshak/apexview/actions/runs/45539807100/artifacts/14989576250) |
| Matching instrumentation APK | [artifact 14989281975](https://github.com/cooldarshak/apexview/actions/runs/45539807100/artifacts/14989281975) |
| Host/ABI evidence | [14987924146](https://github.com/cooldarshak/apexview/actions/runs/45539807100/artifacts/14987924146) |
| Native symbols / package evidence | [14989211632](https://github.com/cooldarshak/apexview/actions/runs/45539807100/artifacts/14989211632) |
| Unit reports | [14989665547](https://github.com/cooldarshak/apexview/actions/runs/45539807100/artifacts/14989665547) |
| Combined instrumentation | [14989436492](https://github.com/cooldarshak/apexview/actions/runs/45539807100/artifacts/14989436492) |
| Independent HEVC output validation | [14989815315](https://github.com/cooldarshak/apexview/actions/runs/45539807100/artifacts/14989815315) |

Exact CI job IDs: build 138955288810, API29 138959216384, API31 138959216334, API36 138959216406, final publication 138960317481. All successful. Stable Rust 22/22 and host ASan/LSan 22/22; Kotlin suite, 6 synthetic + 100 real encoded clear slice differential, 3 CMAF differential controls, original independent decoder/pixel tests, four ABI builds, build-ID/source symbolication, signed cert/package/upgrade, mandatory G.0 four and G-A two tests per emulator, and original full API29/TLS API31/36 gates passed. API29/31/36 process64Bit=true, pageBytes=4096. Actual G-A logs explicitly report bounded worker/lease/generation PASS and malformed batch/exclusive admission/native bridge churn/device budget PASS. No physical ARM G-A test yet. Host ASan/LSan cannot exercise Android-specific JNI unsafe operations. Existing G.0 phone proof is not an entire Rust playback pipeline.

A docs-only commit after this tested SHA is not a different tested APK. Re-fetch head and CI on the next chat; do not overwrite newer work. Build 857 completed final publication at approximately 03:38 UTC, 25 Nov 2026. Artifact links are retention-limited; download matching versions from GitHub while available.

## Prior engineering context (preserve all 14 items and original 16 workstreams)

### Previous 14-item batch and 16-workstream status

Build 842 includes the identified coding changes for the 13 selected items; item 6 (Architecture G) was intentionally deferred. This is implementation coverage, not blanket 100% release certification. Build 841 failed real AndroidKeyStore mutual TLS; Build 842 corrected missing DIGEST_NONE for opaque EC TLS signing, migrates old keys once, preserves non-exportable identity and peer pinning, and passed the expanded instrumentation. Affected users may need to pair again following migration. Build 842 also moves baked MPEG-TS mux/publication off the GL callback into a bounded worker, requires 12 seconds of startup media, fences audio cleanup, starts archive CarData on a partial timing preview and tolerates malformed entries. Earlier Build 840/841 improvements include ownership/correlation, cadence, CMAF inspection reuse, warm delivery priority, TV defaults, layout and driver status. Preserve these changes.

| Item | Current source/CI status | Remaining evidence |
| --- | --- | --- |
| 1 TLS | Implemented; Android 10/12/16 loopback TLS tests green | Actual Android 12↔16 phone/TV pairing, reconnection and upgrade |
| 2 Baked HLS buffering | Bounded asynchronous publication and startup/retention policy implemented | Sustained receiver playback, seeks, buffering |
| 3 GPU capture | Independent overlay acquisition, adaptive pacing, timing diagnostics | Physical throughput and overlay parity |
| 4 Local presentation/audio | Receiver-confirmed suppression and safe worker retirement | Recovery and A/V continuity |
| 5 TV HEVC throughput | CMAF reuse, priority and composition slack improvements | Actual TCL throughput and GC attribution |
| 6 Architecture G | G.0 signed off and G-A foundation green Build 857 | G-B/G-C/G-D and physical gates |
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

Evidence caveat: original raw TV trace review and matched physical playback are deferred; current source audit and G.0 diagnostic benchmarks are recorded below. Distinguish stitching CPU/GC/copy pressure from network, codec, display, overlay and Cast bottlenecks. No guaranteed speedup.

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


## 10. Architecture G master engineering plan — G.1–G.20

Build 857 is the verified G-A foundation baseline. G.0 is signed off by the user. G-A foundation coding and automated gates are complete; G-B–G-D are not started as integrated phases. Existing diagnostic kernels do not establish production stitching. Update coding, automated, physical and measured-result columns with exact evidence. P0 gates protect correctness, safety and compatibility; P1 work is grouped into coherent follow-up batches. Architecture G extends the original plan; no independent competing roadmap.

| ID | Workstream / required deliverable | Priority | Coding | Automated | Physical / measurement |
| --- | --- | --- | --- | --- | --- |
| G.1 | Audit/profile existing JVM stitcher, establish repeatable baseline | P0 | Foundation audit + boundary counters complete; physical profile G-C/D | Build 857 GREEN; foundation gates passed | Deferred |
| G.2 | Fixed-capacity native input/output memory arenas | P0 | Bounded native arena foundation complete | Build 857 GREEN; foundation gates passed | Deferred |
| G.3 | Port native HEVC parsing and stitching with exact invariants | P0 | Not started | Pending | Pending |
| G.4 | Native CMAF moof/mdat assembly, exact sizes/offsets/timestamps | P0 | Not started | Pending | Pending |
| G.5 | Preserve existing DRM compatibility validations and authorized pipeline | P0 | Not started | Pending | Pending |
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
| G.16 | Byte-for-byte JVM/native differential regression plus independent decode | P0 | Not started | Pending | Pending |
| G.17 | Adaptive memory budgets for low-RAM devices | P0 | Observed device-headroom planning and admission provider complete; G-B dispatch pending | Build 857 GREEN; foundation gates passed | Deferred |
| G.18 | Sustained CPU/memory/power/thermal and playback profiling | P0 | Not started | Pending | Pending |
| G.19 | Preserve every existing playback feature and mode | P0 | Not started | Pending | Pending |
| G.20 | Android phone/TV ABI, API, alignment and signed update compatibility | P0 | Four-ABI symbols, alignment, signed upgrade and runtime gates retained | Build 857 GREEN; foundation gates passed | Deferred |

### Proposed consolidated implementation batches (refine after audit)

- G.0: feasibility, baseline, minimal JNI+arena, pinned toolchain, CI and physical verification. No default-engine change.
- G-A: correctness foundation — G.1/G.2/G.6/G.7/G.8/G.13/G.15/G.17/G.20; leased bounded memory, cancellation, CI/ABI/diagnostics and controlled engine interface.
- G-B: exact native HEVC and CMAF port — G.3/G.4/G.5/G.16, with reference-output and independent decode tests; maintain existing authorization and fallback. Start with clear and independently gate protected compatibility.
- G-C: integration and performance — G.9/G.10/G.11/G.12/G.14, measured copies, frame deadlines, compatible switching and prefetch. Do not optimize unmeasured paths.
- G-D: parity and production acceptance — G.18/G.19 plus outstanding gates from all earlier rows. Matched A/B physical tests and full-race endurance before making Rust default.

Dependencies overlap intentionally: safety/differential/compatibility checks run throughout, not only in the final batch. User may revise batch boundaries after G.0. Never trigger isolated tiny builds or pretend a failed test is acceptance.

### 10.1 Mandatory refinement ledger — eight improvements integrated into existing IDs

These are additional acceptance requirements within G.0/G.1–G.20 and the original workstreams, not a separate competing roadmap. Status reflects Build 857; remaining media integration and physical acceptance are not waived. Audit existing implementations first; retain and reuse any already-proven mechanisms rather than duplicating them.

| Refinement | Workstream mapping | Implementation and verification | Status |
| --- | --- | --- | --- |
| R1 Fused processing | G.3, G.4, G.9, G.12, G.16 | Fuse compatible scan/parse/rewrite/assembly passes; use checked size planning and write to final leased output where permitted; eliminate complete intermediate materializations only after equivalence; measure bytes copied, allocations, CPU and p99; independent decode and protected invariant tests. | Diagnostic kernels proven; full fused engine pending G-B/C |
| R2 Deadline-aware scheduling | G.7, G.13, G.14 | Prioritize earliest required playback fragments and seeks over speculative prefetch; derive deadlines/slack from media timeline, playback speed, buffer and measured delivery; bound queues, avoid starvation and respect cancellation/generation; test slow CDN, stalled consumer, seeks and speed changes. | Foundation complete; actual media deadlines/switching integration pending |
| R3 Device-adaptive arenas | G.2, G.6, G.17 | Size bounded arenas using actual fragment distribution, feed geometry, process headroom, low-RAM indicators and codec/overlay needs; enforce total ceiling including in-flight leases and old/new generations; controlled exhaustion, trim at safe boundaries, no arbitrary large TV preallocation; measure native/JVM/PSS and race endurance. | Foundation complete; physical sizing and peak PSS pending |
| R4 Adaptive native worker pool | G.6, G.7, G.13, G.18 | Reusable bounded workers with conservative measured concurrency; preserve order and dependencies, leave CPU capacity for codec/audio/UI/GL/Cast; no thread per feed or forced use of all cores; test 1-worker vs measured alternatives, thermal/CPU contention, cancellation and shutdown; no model-specific hacks. | One bounded native worker implemented; adaptive concurrency tuning pending G-C/D |
| R5 Batched JNI | G.8, G.9, G.12 | Submit fragment/work batches instead of per-NAL JNI crossings, reuse direct views and metadata descriptors; measure JNI call rate/copy/allocation cost; retain checked handles, leases and no unwind across JNI; validate both ARM ABIs and teardown. Android JNI guidance: https://developer.android.com/training/articles/perf-jni | Foundation complete; real fragment dispatch/copy reduction pending |
| R6 Reusable stream analysis | G.3, G.14, G.16 | Bounded cache of validated stream configuration and safe reusable parsing results keyed by stream identity, rendition, codec configuration and relevant generation; invalidate/revalidate at discontinuity, configuration change, seek or track replacement as required; never reuse frame-varying or security-sensitive metadata incorrectly; test stale cache and differential results. | Existing JVM reuse retained; native config cache pending G-B |
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


## Engineering evidence and implementation map

### G.0 and G-A source inventory

| Actual path (relative to repo) | What is implemented / boundary |
| --- | --- |
| native/architecture-g/Cargo.toml, Cargo.lock, rust-toolchain.toml, build.rs | Rust 1.91.1 stable, JNI-RS 0.21.1, four Android ABIs, reproducible locked dependencies, GNU build ID |
| native/architecture-g/src/lib.rs, annex_b.rs | G.0 bounded length-prefixed clear NAL conversion, fixed batch; legacy feasibility Arena; not complete composer |
| native/architecture-g/src/feasibility.rs | Bounded bit/Exp-Golomb reader, top-level box extent validation, synthetic encrypted range metadata arithmetic (no protected processing) |
| native/architecture-g/src/clear_slice.rs | Clear slice rewrite using JVM-prevalidated syntax descriptors; fixed prefix/header scratch; safe word-at-a-time skip for nonzero EBSP tail preserving malformed escape checks; scalar control; **not a complete native parser** |
| native/architecture-g/src/clear_cmaf.rs | Clear single-track fragment writer with exact boxes/offsets/flags/durations, bounded output; no full init writer or complete native composition |
| native/architecture-g/src/engine.rs | G-A fixed slots, single reusable worker, checked total admission, foreground EDF among queued work, speculative reserve, deadline rejection, one exclusive read lease, global checked tokens, stale cancellation and joined shutdown; current dispatch only convert_fixed_batch |
| native/architecture-g/src/jni_bridge.rs | G.0 ABI2 diagnostic; G-A native ABI2, single active opaque registry handle, native-owned private direct views, exact address/capacity validation, no unwind across JNI; views never exposed externally |
| app/src/main/java/app/apexview/tiled/NativeStitchingEngine.java | Explicit open off UI, two native-owned private Direct ByteBuffers, synchronized methods, submit/acquire/read/release/seek/close, copy statistics; private views cleared before deterministic native teardown |
| app/src/main/java/app/apexview/tiled/NativeStitchingDeviceBudget.kt | Actual ActivityManager low-RAM/low-memory/available/threshold and JVM headroom provider; observed input/output maxima; explicit no-admission; no native allocation from current playback |
| app/src/main/java/app/apexview/tiled/NativeStitchingSelection.kt | Requested versus actual routing; CLEAR_PIPELINE_INTEGRATED=false; all routes JVM until validated full native implementation. Do not merely flip this constant |
| Settings.kt, Sheets.kt, AppState.kt (app/src/main/java/app/apexview/) | Persisted nativeRustStitching=false default, Advanced switch exact requested label, explicit JVM active explanatory text, candidate/protected/live/Cast logging. No restart while effective route unchanged |
| HevcArchiveVodSession.kt, CmafHttpReader.kt, ProtectedHevcLiveDashSource.kt, HevcComposedPlayback.kt (tiled/) | JVM reference plus observed source/output sizes, HTTP unknown-length coalescing, completed Media3 DataSource read counters and native admission projection on explicit request |
| HevcTizenGridComposer.kt, HevcClearCmafGridComposer.kt, HevcSliceHeaderParser.kt, HevcSliceHeaderRewriter.kt, HevcParameterSets.kt, HevcOutputParameterSetWriter.kt | Existing validated JVM reference; port actual parser/writer/composer semantics, not assumptions |
| ProtectedHevcLiveComposition.kt, ProtectedHevcCmafSampleExtractor.kt, ProtectedHevcSampleComposer.kt, ProtectedHevcCmafFragmentWriter.kt, ProtectedHevcSliceHeaderRewriter.kt | Existing authorized protected reference, CBCS metadata and opaque encrypted payload invariants; unchanged by G-A |
| ArchitectureG0AndroidTest.kt, ArchitectureGAAndroidTest.kt, NativeStitchingFoundationTest.kt, CmafHttpReaderTest.kt | Existing Android/JVM regressions; preserve all and expand for real G-B |
| HevcSliceHeaderRewriterTest.kt, ArchitectureG0ClearCmafTest.kt, ArchitectureG0KernelVectorsTest.kt | JVM exports shared fixture contracts and existing exact differential evidence |
| .github/workflows/android-debug-apk.yml, scripts/verify_architecture_g0_apk.sh, scripts/run_rendernode_instrumentation.sh, scripts/architecture_g0_stage.py, scripts/test_architecture_g0_packaging_contract.py | Coordinated build, four ABI symbol/16KB checks, encrypted staging, three runtime jobs, final publication after all succeed |

Exact existing clear archive entry: AppState.startArchiveSingleDecoderComposition → ArchiveCompositionSourcePolicy (authorized HLS candidate only) → F1ArchiveFeedCapture.prepareVod → F1HlsFeedCapture.prepareVod → HevcArchiveVodSession (validated actual clear CMAF, per-feed aligned source media, HevcClearCmafGridComposer) → leased finite DASH resources → Media3. Single source may use passThrough. Existing ArchiveSegmentDelivery/ArchiveWorkAdmission, single-flight, bounded fetch pools/cache, owner retirement and PlaybackWorkCoordinator already implement important JVM lifecycle. Reuse, do not duplicate. Protected rolling/live and native Cast use ProtectedHevcLiveComposition and ProtectedHevcLiveDashSource; distinguish clear HLS candidate from verified clear initialization. Do not reroute DRM or capture unsupported data merely because a URL ends in .m3u8.

G-A budget: up to 8 slots in Rust, Java plan prefers 2 low-RAM / 4 other and shrinks toward 2, <=8 MiB individual input/output, <=12 MiB low-RAM or 32 MiB general ceiling and min of (available minus OS threshold)/8, JVM headroom/2. Reserved bytes = (slots+1)*(input+output)+512 KiB worker stack+64 KiB overhead reserve. One session per process, no overlapping old/new native arena; actual memory/admission may reject a big multi-feed segment. Do not silently downscale or bypass bound; split into safe fragment/sample work where exact semantics permit, or explicit JVM fallback. A budget is a planned reservation, not measured PSS. Native input/slot and slot/output bridge copies still occur; Java input/output arrays also copy at present. No direct Media3 lease view, true zero-copy, arbitrary native heap or multiple native workers claimed. Ready/reading buffers are immutable; stale reading slot stays reserved until release, no stale publication; old queued/ready work recycled and running obsolete work discarded. Registry owns backing buffers, no pointers as arbitrary handles, views private and cleared before close. G.0 probe still has separate GC-managed direct buffers, explicit diagnostic only.

### Benchmarks (do not overinterpret)

| Measured test | JVM p50 | Rust p50 | Evidence / limitation |
| --- | --- | --- | --- |
| Build 857 synthetic clear slice, 131765 bytes, addr30 | 108292 ns | 27794 ns | 3.90x faster than same host JVM, scalar Rust 113027 ns (4.07x); prevalidated syntax and reuse, not full stitching |
| Build 857 real clear CMAF writer, 25 samples, 339040 bytes | See exact CI | 6842 ns | C++ 7157 ns; native reusable output, exact match; JVM allocates |
| Build 853 same large slice | 99977 ns | 27809 ns | 3.60x vs JVM; scalar 112776 ns |
| Build 851 physical phone, 64 group copy-inclusive batch | 146771 ns | 47864 ns | 3.07x vs JVM on that narrow fixture; unbatched Rust 497032 ns, 10.38x batching gain with equal bridge copy bytes, workload aggregation differs |
| Build 851 phone single small fixture | 2145 ns | 4794 ns | JNI overhead can outweigh native kernel savings |
| Build 851 phone batches 1/4/16 | 43177/171041/372240 ns | 8073/9323/15208 ns | noisy JVM warmup/allocation; not generalized playback result |

Phone diagnostic SDK36, ABI list ARM64/ARM32, 4096-byte pages, 8 real fixture groups and 290 exact conversions. Presumed S22 Ultra; exact model/process bitness not in that earlier log. G.0 instrumentation mismatch initially caused NoClassDefFoundError, resolved by matching app+test APK. User deferred TCL test; no immediate test request while developing G-B. Target 30% lower matched end-to-end stitching CPU, better p95/p99 and actual presentation, not microbenchmark extrapolation.

### Build history and lessons

| Build / checkpoint | Result / important lesson |
| --- | --- |
| 842 | Last pre-G stable parent checkpoint; preserve prior 13-item coding work |
| 843 | Host prototype, all ABI tests; baseline artifact for signed upgrade |
| 844 → 845 | Missing GNU build ID fixed with Android linker flag; signed upgrade and actual JNI runtime |
| 846 | Batched JNI diagnostic, cross-arena ownership, 4 tests |
| 847 | Feasibility bits/boxes/metadata, slower CI ~22m24s despite daemon/cache |
| 848 → 849 | Parallel runtime gates and encrypted staging; corrected production wiring test; green ~15m15s |
| 850 → 851 | Malformed workflow caused JS literal replacement expansion of shell dollar syntax; restored unique jobs and strict YAML/shell checks; first phone diagnostic after matching APK |
| 852 → 853 | Wrong JVM output mode selected: sample-only omitted owned VCL NAL arrays; use includeAnnexBOutput=false, emitSampleData=false for owned-NAL fixture; 106 cases green; G.0 user sign-off |
| 854 | G-A initial engine, setting and scheduler; 20 Rust tests, all jobs green |
| 855 | 22 Rust and ASan/LSan tests passed; Kotlin duplicate local outputBytes failed compile |
| 856 | Corrected duplicate local but failed coordinated change contract (no changed automated test) |
| 857 | Same verified output fragment reused in budget/size metric, targeted regression and both plans in same commit; full CI green |

Do not repeat prior failures: every production-affecting commit must update docs/PITWALL_PERFORMANCE_AUDIT_PLAN.md and meaningful changed automated regression. Preflight validate_change_set against exact prospective changed paths, run targeted tests, strict YAML duplicate-key check and bash -n for any workflow edits. Keep replacement literals safe (JS String.replace callback, not interpolated replacement string containing $ patterns). Check exact branch SHA before atomic commit. No manual workflow trigger if push already triggered; no per-line commits. Evidence artifacts may fail after early gate because no files exist; find first root cause.

### CI/mobile-only operating model

Existing Android debug workflow runs on ubuntu-24.04, JDK21, Android compileSdk37/minSdk29/targetSdk36 (verify current files), Media3 1.11.1, OkHttp 4.12 (verify). Pinned Rust stable 1.91.1, NDK 28.2.13676358, cargo-ndk 4.1.2, JNI 0.21.1; separate nightly-2025-11-15 host ASan/LSan; no weakening stable release/ABI gates. Cache tool executable and pinned Kvazaar; freeze build timestamp/signing before Gradle; reuse daemon; retain R8, all original unit, renderer, decoder, Cast, TLS and macrobenchmark assembly gates. Build job creates encrypted GPG stage with SHA256 manifest using secret-derived per-stage passphrase; no installable plaintext APK publication before API29/31/36 matrix and final gate. Preserve exact signed app/instrumentation artifact names; no keystore or secret in artifacts. Build 843 baseline artifact (retention 14 days) is still pinned, refresh to a verified green signed baseline before expiry, with regression test and exact source proof, never disable upgrade verification. GitHub Actions logged Node20 deprecation warnings; future maintenance, not current failure. Parallel jobs reduce elapsed time but cost extra runner minutes; keep all coverage; do not promise build time. Build 857 took approximately 13m25s from first contract at 03:25:07 UTC to final job end 03:38:32 UTC, excluding initial checkout/queue; actual per-job duration from logs.

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
| ON | Select Rust for eligible, independently validated stitching paths. Do not claim active Rust for an unimplemented prototype, unsupported mode or fallback. |
| Phone and Android TV | Same preference and behavior; integrate with existing settings UI/Material 3, accessibility, focus and DPAD support; use existing localization patterns. |
| Decoder mode | Independent of decoder selection; exactly one video decoder in existing single-decoder mode; multiple-decoder and non-stitching paths remain unchanged unless independently validated. A passthrough/non-stitching mode reports Not applicable. |
| DRM / render / quality | Software DRM, clear renderer, quality, layout and captions remain independent; no silent changes to security, rendition, decoder count or surface mode. Clear and protected native support independently gated. |
| Active playback change | Apply via controlled existing coordinator restart at safe boundary, invalidate stale generations, cancel/drain workers and release leases, preserve position and play/pause, layout, feed choices, audio/CC and user settings where supported. Do not run two live stitching engines concurrently or free in-use memory. For active casting coordinate restart or explicitly defer until safe; no silent receiver changes. |
| Unsupported native path | Fail closed for security/incompatible content. Where the existing authorized JVM path is supported, controlled fallback may be used with explicit reason and actual engine indicator; no retries that loop or obscure errors. Retain requested ON state, do not quietly rewrite it to OFF. |
| Native load / operational error | Recoverable failures may fall back through a safe full reset after teardown. Native process abort/segfault cannot be reliably caught in Kotlin; retain matching symbols, diagnose on restart and use a bounded crash-recovery policy that exposes the actual engine. |
| Diagnostics | Report requested engine, actual engine (JVM / Rust / Not applicable / unavailable), build SHA/ABI, eligibility, fallback reason, generation, engine initialization, copy/queue/deadline metrics and active decoder count. Never expose credentials, keys or protected media. |
| A/B verification | Same signed APK, toggle only, identical session, actual quality, feed count, speed, DRM, renderer, layout, refresh and thermal/network state; reject or separately classify fallback runs. Test repeated switching, restart, process death, seek, casting, native load failure, cancellation, setting persistence and upgrades. |

Selection model: requested preference → eligibility/capability/security check → one active engine for the current playback generation → explicit diagnostic state. G-A preference and selector foundation implemented and green. Actual route remains JVM until G-B independently enables proven eligible clear/protected paths; G-C/G-D validate performance and feature parity.


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


## Next implementation phase: G-B, exact native HEVC/CMAF stitching

Read actual branch source and current canonical plan before coding. The following is the work breakdown for the next consolidated batch, not a claim it has been implemented. Finish agreed G-B scope in coherent changes and keep every test; if blocked report exact missing work/evidence. G.0 and G-A do not need reimplementation. Do not start G-C/D before the user approves the next phase.

| ID | Required work and exact exit evidence |
| --- | --- |
| G.3 | Native bounded ISO-BMFF/NAL/RBSP parsing and complete HEVC parameter/slice semantics from actual JVM reference. VPS/SPS/PPS validation, tile and CTB layout, POC, IRAP/random access, RPS, dependent slices/entry offsets, loop-filter and compatibility invariants, mixed geometry, source timing, complete native output initialization/parameter sets. Use validated config caching with exact stream identity/generation. Port all supported clear composition paths; unsupported explicitly rejected, no faux output. |
| G.4 | Native CMAF initialization and fragment parsing/assembly, exact moof/mdat/trun/tfhd/tfdt/sizes/offsets/flags, CTS/DTS, durations/timescale, sample metadata and random access; support actual JVM reference and multi-feed/atlas integration. Plan bounded final leased output, write in place where safe, no hidden intermediate full copies. Existing clear_cmaf writer is only a foundation; preserve exact reference bytes when required. |
| G.5 | Preserve authorized DRM, existing Media3/Widevine, encrypted payload opacity, CENC/CBCS sample/subsample/IV/group metadata, KID/PSSH, validation and compatible protected input constraints. Do not decrypt, extract keys, circumvent CDM, modify policy or force security downgrade. Start with clear independently verified; protected native eligibility only when full authorized compatibility is proven. Keep existing JVM protected fallback explicit and fail closed for incompatible/unauthorized inputs. |
| G.16 | Complete native/JVM differential corpus for real clear full composed stream, protected metadata/layout invariant corpus, malformed/truncated/oversized/overflow/discontinuity/seek/rotation negative cases, multiple feeds, 2x2 and mixed configurations. Independently decode valid clear output via existing FFmpeg/Kvazaar pixel/geometry/frame/PTS checks; don't merely compare native vs native or synthetic header kernels. Expand fuzzing and ASan/LSan; no weakening gates. |

Suggested coherent execution order inside G-B: inventory real public/validated code contracts and fixtures; implement safe Rust parser/config and full clear compose plan; use existing validated G.0 kernels only where semantics match; implement complete clear init and media writer; wire native worker dispatch and lease/publication API for actual fragments, not just convert_fixed_batch; exercise real clear exact reference and independent decode gates; gate effective Rust route only once verified clear and integrated. Then independently implement protected-compatible metadata handling and exact validated authorized path; retain existing licensed Widevine flow. If full protected parity cannot be demonstrated safely, document G.5 partial/pending and keep JVM route. Implement controlled coordinator restart preserving position, playing state, feed/layout/quality/audio/CC, cancel/drain/close; no double-active session and no stale read. For current G-A default OFF, no change on unsupported routes. A route marked RUST must be genuinely executing the new pipeline, not a diagnostic or JVM-produced result. Update UI requested/actual text, eligibility reason, generation and logging. Existing verified clear/native route is not available yet. Do not flip CLEAR_PIPELINE_INTEGRATED alone. Physical comparison after meaningful real integration; ask user only at exact actionable checkpoint. No full native playback/DRM performance claim until tests.

G-C retains G.9 network copy reduction, G.10 least-invasive Media3 publication, G.11 measured NEON only, G.12 bounded hot-path allocations, G.14 feed switching/quality/prefetch. G-D retains G.18 sustained matched CPU/memory/power/thermal/playback and G.19 all feature parity plus deferred physical gates; never silently turn default ON. Preserve old JVM as permanent reference and fallback. All work is on existing app, no separate engine project or cloud service.

### Physical test commands and outstanding acceptance

When user is ready, install matching signed app AND instrumentation APKs over existing installation; do not uninstall and lose data. First verify version:

```sh
dumpsys package app.apexview_custom | grep -E 'versionName=|versionCode='
am instrument -w -e class app.apexview.tiled.ArchitectureG0AndroidTest app.apexview_custom.test/androidx.test.runner.AndroidJUnitRunner
logcat -d -s PitwallG0:I '*:S'
am instrument -w -e class app.apexview.tiled.ArchitectureGAAndroidTest app.apexview_custom.test/androidx.test.runner.AndroidJUnitRunner
logcat -d -s PitwallGA:I '*:S'
```

For Build 857 expect G.0 OK (4 tests), G-A OK (2 tests). API29/31/36 emulator proof not physical ARM32 or 16KB runtime proof. Need physical S22 and TCL native G-A lifecycle validation; TCL G.0 deferred by user. Use phone-only aShell/Bugjaeger/Perfetto guide in docs/perfetto/ASHELL.md. Test baseline same source/position/quality, single decoder, 1×: one feed with overlays, three feeds overlays OFF, three feeds ON. 30s warmup and 60s trace per scenario, separately 1.5× and seek/lap seek; maintain same refresh and thermal state; phone 60Hz vs 120Hz adaptive separately. TCL Software DRM ON if authorized playback requires it; record actual negotiated DRM and renderer, avoid unsafe assumptions. No need to rerun these now solely to finish the handover. After actual native implementation use same APK toggle-only A/B; keep complete logs and exact source/runtime version. Test phone+TV, clear+authorized Widevine, single+multiple decoders, Google Cast+Pitwall Cast, overlays/captions/quality/layout, sustained full race, PSS/GC/power/thermal, frame presentation and network vs producer slack. For real 16KB environment, require runtime proof before certification. Physical work remains deferred not waived.

### Hard rules and next-chat instructions

- User works from ChatGPT mobile, no dedicated PC. Use existing GitHub/Composio connection and Actions; never require desktop-only compiler/debugger. Source/CI artifacts via exact authenticated connector. Respect tool limitations and never claim tests not run.
- Always begin with repository branch HEAD and exact latest successful run; verify five jobs and final publication, fetch failed job logs if red. Use exact SHA. If user asks to fix failed build, do not advance phases. Recurring monitoring only via actual supported automation, hourly maximum, stop once current run green. No unattended claims.
- Single consolidated meaningful implementation pass; no micro-commit churn; complete all agreed coding items before stopping unless actual blocker. Every source change includes regression and both plan updates in same atomic commit. Preflight before commit; no manual rebuild storm. Preserve main and parent. No release or merge without explicit permission.
- Clearly report coding, automated, physical and measured statuses separately; G-A foundation is complete, not all G.1/G.15/G.17/G.20 full production acceptance. Never claim full zero-copy, universal speed gains, complete parity or no leaks from limited tests.
- No proprietary SDKs, paid OpenF1, paid/cloud stitching, login/DRM circumvention, session-specific patches, unsafe TLS fallback or feature removal. Keep all existing user settings and decoder invariants. Maintain independent measurements for BufferQueue, decoder, display, network, overlays and Cast.
- If user asks to begin G-B, perform actual engineering against existing code, not just a new plan. On a failure inspect first failing gate, use meaningful regression, verify exact new build, keep hard rules. Do not ask for information already supplied. Request physical test only when meaningful, with exact APK artifacts, aShell command and expected evidence. Keep future chats grounded in canonical plans and actual source.

**Final handover state:** Build 857 fully green; G.0 signed off; G-A foundation coding and automated verification complete; G-B/G-C/G-D integrated phases not started. User has not authorized a default engine switch or production release. Proceed to G-B only when instructed.
