# HEVC stitching proof of concept

Status: Phase 1 implementation is still under validation. Bounded HEVC/CMAF parsing, fail-closed compatibility checks, genuine complementary 2x1 synthetic tile fixtures, and an isolated Kotlin assembler are present. CI validation of the Kotlin reconstruction and encryption parser is ongoing. Physical Android hardware-decoder proof and authorised F1 feed inspection remain open.

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

## Current synthetic fixture and Kotlin assembler

The CI fixture generator uses Kvazaar to encode one 256x128 HEVC sequence with a 2x1 tile grid and tile-slice mode. It separates each picture's first tile slice and continuation tile slice into two complementary Annex-B feeds. The right feed is intentionally not independently decodable. The fixture contains 50 pictures and 100 source VCL tile slices.

The Kotlin assembler is deliberately narrow. It requires byte-identical VPS/SPS/PPS, checks the VPS/SPS/PPS ID chain, the 2x1 CTB geometry and the two expected slice-segment addresses for every picture. It checks picture order and reference syntax, then writes the original tile NALs in tile-scan order without modifying slice headers or coded payload. It rejects unsupported NAL types and syntax rather than trying to repair arbitrary HEVC streams.

The test compares the Kotlin output with the deterministic reference stream, compares the parsed picture signatures and asks FFmpeg to decode the Kotlin-produced stream. The decoded frame hashes must match the original 50-picture source. This is evidence for the synthetic clear-stream subset only. It is not evidence that two arbitrary independent full-frame cameras, or F1's protected CMAF feeds, can be stitched.

### Predicted short-term RPS parser update

The bounded parser now expands inter-predicted short-term reference picture sets into explicit negative and positive POC deltas, including `delta_idx_minus1` for an inline slice-header RPS. The derivation follows the ordering in H.265 7.4.8 and retains both `used_by_curr_pic` and `use_delta` semantics. Synthetic tests cover an SPS-predicted set and an inline predicted set that references an earlier SPS entry.

This closes one parser gap only. It does not establish complete H.265 conformance or slice-header rewrite safety. The remaining slice-header gaps include weighted-prediction tables, per-slice deblocking overrides, entry-point offsets, slice-header extensions, CABAC boundaries and the syntax needed to safely rewrite multiple tile slice segments.

### Parameter-set and CMAF inspection update

The bounded SPS/PPS model now captures general and sub-layer profile-tier-level values, chroma format, bit depth, conformance-window display dimensions, DPB/reorder limits, tile geometry, loop-filter and deblocking metadata, and selected PPS coding tools. VUI parsing now extracts aspect-ratio, video-signal/colour, timing and HRD-presence metadata while walking the full VUI syntax. SPS parameter-set extensions remain explicitly unsupported and are rejected. Shared VUI remains safe for this synthetic assembler only when both input feeds carry byte-identical SPS NALs.

The hvcC reader extracts decoder configuration fields and bounded VPS/SPS/PPS arrays. The track-level tenc reader records protection scheme shape, pattern and IV-size defaults while skipping KID and constant-IV bytes. The fragmented MP4 reader extracts track IDs, timescales, tfdt decode-time anchors, trun sample durations and composition offsets. It derives decode and presentation timestamps only when the necessary timing fields are present.

The bounded senc parser obtains the effective IV size from tenc defaults or a senc override, skips IV and KID bytes without retaining them, and parses clear/encrypted byte-range lengths when the subsample flag is set. The trun parser captures per-sample sizes from trun/tfhd/trex; parsed subsample ranges must cover each declared sample size exactly. The saiz/saio parser validates versions, flags, counts, entry sizes, offset cardinality and aux-info type/parameter consistency. When inline senc data is present, each saiz entry must match the corresponding IV plus subsample-map byte length. The seig parser supports bounded fragment-local sgpd/sbgp mappings, CBCS pattern, protection state and IV-size/constant-IV-size metadata. It skips KID and IV values without retaining them. Unsupported multi-key entries and unresolved track-level description references fail closed. The compatibility validator still rejects protected stitching: it does not compare KID identity or IV byte values, does not follow saio into external auxiliary payload bytes, and therefore does not claim cryptographic equivalence. Parsing a map is not proof that protected samples may be combined.

### Phase 1 exit gates still open

- [ ] Inspect sanitised, non-secret metadata from two actual F1 feeds in an authorised session: manifest/codec, init-segment boxes, timescale, fragment timing, protection scheme, and sample-encryption signalling. Do not retain protected samples, licence responses, KIDs or IVs.
- [x] Parse bounded saiz/saio structure, capture sample sizes, verify inline senc auxiliary record sizes, and validate clear/encrypted subsample ranges against sample sizes. Parse fragment-local seig sample-group descriptions and sample-to-group mappings without retaining KIDs, IVs or protected data. Correct per-sample senc parsing under active seig overrides and external saio payload verification remain open; encrypted compatibility stays rejected.
- [ ] Prove the supported HEVC slice-header subset with positive and negative fixtures. The assembler currently accepts only the explicitly tested independent 2x1 tile-slice profile and rejects dependent slices, WPP, weighted prediction, list modification, SPS extensions and unsupported non-VCL NALs. VUI is parsed and must be byte-identical as part of the shared SPS. It does not rewrite slice headers or claim generic HEVC support.
- [ ] Complete CI validation of the Kotlin assembler: all 50 picture pairs must pass header/POC/reference checks, Kotlin output must match the deterministic NAL reference, and FFmpeg must decode all 50 frames with hashes identical to the source.
- [ ] Build and install the app plus instrumentation APK, then run the MediaCodec test on a physical Android device with hardware HEVC support. Verify codec name, hardware-accelerated status, 50 decoded frames, 256x128 output and exactly one created decoder instance. CI compilation, a Gradle build, or FFmpeg decode alone does not satisfy this.
- [ ] Only after the synthetic and device tests pass, inspect the DRM/secure-surface feasibility of the actual authorised F1 delivery. Confirm that the supported input representation can reach one secure decoder without weakening or bypassing Widevine.



Phase 2 must not start by wiring a speculative stitcher into production playback. Start it only after the complementary-tile fixture and the isolated one-decoder synthetic proof have passed. The existing multi-player path remains the fallback and must not be modified by this POC.

## Current known risk

The F1 Tizen project describes an F1-specific HEVC packaging profile and CBCS handling. Android Media3/MediaCodec DRM integration may not expose the protected compressed samples in a form that permits stitching. That is a gating technical question. A clear synthetic single-decoder demonstration is necessary but not sufficient to prove protected F1 playback.


### Synthetic assembler scope and limitation

The Kotlin assembler supports only two independent tile slices per picture in a fixed, pre-aligned elementary-stream sequence. Annex-B has no timestamps; production inputs must be aligned using verified CMAF decode/presentation timing before reaching this API. It rejects non-VCL metadata NALs other than VPS/SPS/PPS, unsupported PPS tools, non-uniform tile layouts, dependent slices, and picture/reference mismatches. It does not decrypt samples, handle CBCS subsample maps, rewrite slice headers, or integrate with FeedPlayer. No protected F1 sample is read or modified.


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
