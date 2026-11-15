# HEVC stitching proof of concept

Status: Phase 1.9. Bounded HEVC/CMAF parsing and fail-closed compatibility checks are in place. Complementary 2x1 synthetic tile fixtures and a narrow Kotlin assembler are under validation. Android MediaCodec runtime proof and authorised F1 metadata validation remain open.

## Goal

Prove that two authorised F1 HEVC camera feeds can be reconstructed into one valid HEVC picture/stream and decoded by exactly one Android hardware decoder. Expand to four feeds only after the two-feed proof passes.

## Non-negotiable constraints

- Do not use Tiledmedia, TME, or any Tiledmedia-owned SDK, service, code, or binary.
- Do not bypass, weaken, or remove F1 TV DRM. Use the user's authorised F1 TV session and Widevine licence flow.
- Do not download, persist, or redistribute protected F1 media.
- Keep the existing multi-player playback path intact as a fallback.
- Do not claim single-decoder success from a green Gradle build. It requires runtime decoder-instance evidence on a physical Android device.
- Never feed encrypted bytes into a parser that assumes clear NAL payload. Respect the sample encryption map and only parse bytes that are legitimately clear under the media format.
- Fail closed when stream compatibility cannot be established.

## Findings from reference implementations

### nicbra/f1tv-tizen

Primary behavioural reference for the F1-specific HEVC grid reconstruction. Its `service/stitch-hevc.js` implementation documents strict assumptions: matching feed frame counts and decode times, compatible frame durations/composition offsets, matching reference-picture-list syntax, compatible HEVC parameter sets, tile geometry/CTB alignment, and CBCS encryption layout. It reconstructs picture/slice headers while preserving protected payload ranges for the platform DRM path.

This is not a generic arbitrary-HEVC muxer. Treat every documented assumption as a validation requirement, not as a guarantee that all F1 streams match it. Re-check the upstream implementation and licence before copying any code.

### OpenVisualCloud 360SCVP

The `E_STREAM_STITCH_ONLY` API and HEVC tile merge implementation are useful native C++ references. Do not import the whole reference Android player: its DecoderManager creates decoder instances per video ID, which is not the architecture being proved here. The relevant 360SCVP source is BSD-3-Clause; review component-specific licensing before adding dependencies.

### GPAC

Use `tileagg` as a standards and bitstream-aggregation reference for split HEVC tile tracks. It is not assumed to accept F1's exact feed packaging.

### Fraunhofer OMAF.js

Reference only for extractor-track reconstruction concepts. Its source licence has redistribution and use restrictions; do not add it as an application dependency without a separate legal review.

## Proposed implementation stages

1. Inventory F1 session metadata, manifest type, codec string, init segments, sample encryption scheme, subsample encryption maps, and timing for two feeds. Do not persist protected media.
2. Build a clear synthetic test fixture with two HEVC tile feeds and known expected output. Keep fixtures generated locally and redistributable.
3. Implement and unit-test container/sample inspection and HEVC NAL/header parsing.
4. Implement a fail-closed compatibility validator. It must compare parameter sets, picture timing, POC/reference-picture syntax, tile geometry, CTB alignment, slice layout, and encryption metadata.
5. Implement the stitcher behind an isolated interface. Initially support only the exact proven stream profile; reject everything else.
6. Decode the reconstructed clear synthetic stream using one MediaCodec instance and record codec name, output dimensions, frame cadence, and decoder counters.
7. Only then investigate the authorised F1 DRM-protected path. Confirm whether the platform can decrypt the reconstructed samples in the selected packaging and whether secure MediaCodec output is supported.
8. Expand from two to four feeds; add layout changes and long-duration stability tests.

## Android integration boundary

The first prototype must not modify FeedPlayer or the production playback graph. Add a separate tiled package and an explicit experimental entry point only after the bitstream proof is independently testable. The eventual playback path must use one DRM session/decoder where supported; do not assume that four independent F1 DRM sessions can be combined into one protected stream.

## Acceptance criteria

- Unit tests cover Annex-B NAL splitting, HEVC NAL headers, emulation-prevention removal, bit parsing, malformed input, and compatibility rejection.
- Synthetic two-feed input produces a valid combined stream accepted by an HEVC decoder.
- Runtime instrumentation proves exactly one active video decoder for the tiled path.
- No Tiledmedia/TME dependency or code is present.
- Existing multi-player playback and its tests remain unchanged.
- Any F1-specific limitation is documented with evidence from the actual authorised stream metadata.

## Current synthetic fixture boundary

The fixture generator uses FFmpeg/libx265 to create two disposable, time-aligned HEVC test streams. It checks codec, dimensions, frame rate and frame count. It does not split a single encoded picture into independent tile streams, rewrite slice headers, combine streams, or prove decoder interoperability. The next engineering gate is bitstream-level SPS/PPS, POC, tile geometry and slice-header analysis before any stitcher is attempted.

### Predicted short-term RPS parser update

The bounded parser now expands inter-predicted short-term reference picture sets into explicit negative and positive POC deltas, including `delta_idx_minus1` for an inline slice-header RPS. The derivation follows the ordering in H.265 7.4.8 and retains both `used_by_curr_pic` and `use_delta` semantics. Synthetic tests cover an SPS-predicted set and an inline predicted set that references an earlier SPS entry.

This closes one parser gap only. It does not establish complete H.265 conformance or slice-header rewrite safety. The remaining slice-header gaps include weighted-prediction tables, per-slice deblocking overrides, entry-point offsets, slice-header extensions, CABAC boundaries and the syntax needed to safely rewrite multiple tile slice segments.

### Parameter-set and CMAF inspection update

The bounded SPS/PPS model now captures general and sub-layer profile-tier-level values, chroma format, bit depth, conformance-window display dimensions, DPB/reorder limits, tile geometry, loop-filter and deblocking metadata, and selected PPS coding tools. Unsupported VUI and parameter-set extensions are surfaced so the validator can reject them rather than silently assuming defaults.

The hvcC reader now extracts decoder configuration fields and bounded VPS/SPS/PPS arrays. The track-level tenc reader records the protection scheme shape, pattern and IV-size metadata while skipping KID and constant-IV bytes. The fragmented MP4 reader extracts track IDs, timescales, tfdt decode-time anchors, trun sample durations and composition offsets. It derives decode and presentation timestamps only when the necessary timing fields are present.

A separate compatibility check compares sample counts and exact media times across potentially different track timescales. It also checks decoder configuration and the parsed parameter sets. It deliberately rejects protected-fragment compatibility while KID identity, per-sample IV/subsample maps, external auxiliary data and sample-group overrides remain unverified. The current senc inspection only reads its header, flags and sample count; it does not decode IVs or subsample ranges.

### Phase 1 exit gates still open

- [ ] Inspect sanitized, non-secret metadata from two actual F1 feeds in an authorised session: manifest/codec, init-segment boxes, timescale, fragment timing, protection scheme and sample-encryption signalling. Do not retain protected samples, licence responses, KIDs or IVs.
- [ ] Complete the encryption metadata comparison safely, including per-sample subsample ranges and any seig sample-group overrides. Do not infer compatibility from tenc alone.
- [ ] Finish the supported slice-header subset, including weighted prediction and entry-point offsets, or explicitly constrain and prove a narrower encoder profile with tests.
- [ ] Generate two complementary HEVC tile feeds from the same encoded picture. The current FFmpeg/libx265 fixtures are two independent full-frame encodes and are not a tiled-feed fixture.
- [ ] Validate that the reconstructed synthetic stream is accepted by a standards-compliant HEVC decoder.
- [ ] Instrument a physical Android device and prove one active MediaCodec decoder for the reconstructed synthetic stream, recording codec name, dimensions, frame cadence and decoder counters.

Phase 2 must not start by wiring a speculative stitcher into production playback. Start it only after the complementary-tile fixture and the isolated one-decoder synthetic proof have passed. The existing multi-player path remains the fallback and must not be modified by this POC.

## Current known risk

The F1 Tizen project describes an F1-specific HEVC packaging profile and CBCS handling. Android Media3/MediaCodec DRM integration may not expose the protected compressed samples in a form that permits stitching. That is a gating technical question. A clear synthetic single-decoder demonstration is necessary but not sufficient to prove protected F1 playback.
\n\n### Synthetic assembler scope and limitation\n\nThe Kotlin assembler supports only two independent tile slices per picture in a fixed, pre-aligned elementary-stream sequence. Annex-B has no timestamps; production inputs must be aligned using verified CMAF decode/presentation timing before reaching this API. It rejects non-VCL metadata NALs other than VPS/SPS/PPS, unsupported PPS tools, non-uniform tile layouts, dependent slices, and picture/reference mismatches. It does not decrypt samples, handle CBCS subsample maps, rewrite slice headers, or integrate with FeedPlayer. No protected F1 sample is read or modified.\n

## Running the physical-device MediaCodec proof

The CI build creates two artifacts from the same commit: the normal debug APK and the
`apexview-hevc-single-decoder-instrumentation` APK. Both must be installed together on a
physical Android device with a hardware HEVC decoder (Android API 29 or newer).

With ADB connected to that device:

```bash
adb install -r app-debug.apk
adb install -r app-debug-androidTest.apk
adb shell am instrument -w app.apexview_custom.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s ApexviewHevcProof:I
```

A valid device result must contain
`SYNTHETIC_SINGLE_HARDWARE_DECODER_PASS`, `hardwareAccelerated=true`,
`decoderInstancesCreated=1`, `frames=50`, and `dimensions=256x128`. A Gradle compile,
APK upload, emulator run, or FFmpeg decode is not a substitute for this physical-device
runtime result. If the device has no hardware HEVC decoder, the test fails rather than
silently accepting a software fallback.
