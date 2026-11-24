# Clear Archive versus Widevine HEVC: coordinated optimization and layout audit

Branch: feature/pitwall-performance-audit-plan
Base: passing Build 650
Validation: existing GitHub Actions Android tests, source audit, prior real S22 Ultra logs. No new synthetic video playback tests were added.

## Comparison and implementation status

| Area | Finite clear HEVC VOD | Protected CBCS HEVC rolling archive | Status |
|---|---|---|---|
| Manifest source | Clear HLS CMAF with finite index | Actual HLS/DASH resolved and encryption verified | Separate by source metadata |
| DRM | Unencrypted samples | Widevine authorized; CBCS KID/IV and encrypted bytes retained | Separate by necessity |
| Feed network requests | Bounded parallel foreground and warmup lanes | HLS offers, playlists, init, media use bounded parallel lanes up to 4; DASH feed requests likewise parallelized | Implemented |
| Composition scheduling | Foreground on demand, low-priority speculative HEVC warmup | Dedicated lower-than-UI-priority HEVC composition workers for HLS and DASH, independent of per-feed IO | Implemented |
| Future fragment scheduling | Single-flight and bounded cache, two speculative fragments; seek preempts | Bounded three-fragment clock-driven window with POC/CBCS continuity | Equivalent bounded intent, different seek semantics |
| Initialization and metadata | Cached verified CMAF initialization | Cached per-feed init; reused verified fragment inspections where unchanged | Implemented |
| Mixed atlas | Cached HEVC geometry per VOD session | Cached atlas planner keyed by verified HEVC parameter fingerprints | Implemented |
| HEVC configuration generation | Session-level output SPS/PPS | Output parameter-set writer cached for stable request and verified fingerprint | Implemented |
| Media memory | Bounded fragment cache, one owner until DataSource delivery | Detached startup transfer; ownership-based rolling window handoff; reader leases; secure zero after final consumer | Implemented |
| Video decoding | One hardware HEVC MediaCodec | One hardware HEVC decoder, authorized Widevine where required | Preserved |
| Video GPU | One GLS external texture on TextureView for finite clear and Cast | One external texture on SurfaceView, with protected EGL for L1 | Purposefully different output surface |
| Frame pacing | Choreographer, SceneFrameDemand, SceneFrameCadenceGate and SceneFramePacing | Same pacing and coalescing reused, SurfaceTexture latched immediately to relieve BufferQueue | Implemented |
| Performance metrics | ARCHIVE_VOD_SEGMENT, SINGLE_DECODER_SCENE | HLS_CMAF_CAPTURE, PROTECTED_CMAF_SEGMENT, PROTECTED_ATLAS_PLAN, DASH_CMAF_CAPTURE, SURFACE_DECODER_SCENE | Implemented |
| Seek and controls | Finite random access and prefetch invalidation | Recompose sliding protected window at target without forcing GRID mode | Correct per backend |
| Timing tower and track map | Retained UI | Same overlay engine | Retained |

## Invariants

No race- or circuit-specific logic. Sources and their codec/protection metadata determine behavior.
Never interpret Widevine protected samples through the clear composer.
Keep the current authorization and encryption scheme, one DRM session, one HEVC decoder,
one final video surface. Neither geometry caching nor pacing touches encrypted pixels.

The S22 Ultra Build 643 log showed a hardware decoder producing approximately 50fps while
the UI sustained 300-994ms frames. Isolation to SurfaceFlinger and shared pacing were
implemented subsequently. Real protected streaming smoothness remains to be measured
on the S22 Ultra, TCL TV, and Cast. A green CI does not prove real F1 DRM playback.

## Driver position, racing number, name and lap timing

Previously the onboard DriverCard was suppressed when tile width was below 230dp.
This made its presence seem specific to Grid mode. All real onboard feeds with
timing now display a suitable card in any layout. Wide feeds retain full sector
bars; narrow ones show a two-row compact driver position, number, surname and lap
clock. Missing F1 timing still means no invented data.

## Named layout library

Existing single saved layout keys stay available. The new versioned, validated
credential-free SharedPreferences library stores up to 50 user-named layouts,
including feed positions/aspect, originating layout type, map visibility/mode,
all map panels with normalized position/size/rotation, opacity, label/dot sizes,
map timing offset, lock/reservation, timing tower visibility, geometry, opacity,
font scale, TV width, overlay placement and snap setting. UI supports
save, rename, load, delete, set default, export and import.

Loading preserves the running feed and single decoder. An independent pending
snapshot reapplies saved geometry after the built-in TV/grid layout solver.
Legacy saved layout keys migrate without deleting old data.

Android Auto Backup is explicitly enabled with preexisting credential exclusions
for auth.xml and WebView. A BackupManager dataChanged request follows saving.
Automatic reinstall restoration is conditional on Android backup being configured,
completed and restored for the compatible app identity/signing key. Local data
is deleted by uninstall. Portable JSON export/import is provided for users with
no usable cloud backup. Without an app-owned cloud sync service, guaranteed
restore on every device cannot be promised.

## Verification and remaining physical acceptance

No new synthetic video fixture tests were added in this work; existing CI still
runs its older synthetic CMAF and Android graphics regression suite. New tests
cover preference serialization and real SharedPreferences persistence only.
CI success means the code compiles and those tests run. Licensed Widevine L1/L3,
50fps physical presentation, ongoing overlay jank, Cast parity, and actual
platform backup restoration require hardware and authorized user account testing.
