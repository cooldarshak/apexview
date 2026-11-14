# HEVC stitching proof of concept

Status: Phase 1.5, investigation and parser foundation.

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

## Current known risk

The F1 Tizen project describes an F1-specific HEVC packaging profile and CBCS handling. Android Media3/MediaCodec DRM integration may not expose the protected compressed samples in a form that permits stitching. That is a gating technical question. A clear synthetic single-decoder demonstration is necessary but not sufficient to prove protected F1 playback.
