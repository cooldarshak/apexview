# F1 HEVC Grid Composer: implementation plan

Status: Architecture direction selected; implementation not yet started. This is a plan, not evidence that real F1 streams have already been composed or decoded.

## Objective

Compose two or more authorised F1 TV HEVC feeds into one newly constructed HEVC picture/stream, then decode that composed stream with exactly one Android video decoder and one output surface.

The source feeds do not need to arrive with a 2x1 tile grid. The composer creates a new output grid by selecting compatible source picture regions and rewriting output parameter sets and slice headers.

Keep the existing multi-player implementation as a fallback until the composed path is proven on real devices.

## Non-negotiable constraints

- Do not use Tiledmedia, TME, ClearVR, or any Tiledmedia-owned SDK, service, code, or binary.
- Do not bypass, weaken, or remove F1 TV DRM. Use the user's authorised F1 TV subscription and Widevine licence flow.
- Never decrypt protected video payloads, expose keys/KIDs/IVs, or persist/redistribute protected F1 media.
- Preserve encrypted coded payload bytes. Parse only syntax legitimately available in clear sample ranges, and validate the encryption subsample map before touching a slice header.
- Keep exactly one video decoder and one output surface for the composed video path.
- Fail closed on unsupported or ambiguous bitstream/container syntax.
- Do not change the production playback path until the isolated composer and its tests pass.
- Do not claim success from a green CI build alone. Require runtime evidence from a physical device.

## Reference implementation and what it demonstrates

Primary independent F1-specific reference:

- Repository: https://github.com/nicbra/f1tv-tizen
- Implementation: https://github.com/nicbra/f1tv-tizen/blob/master/app/service/stitch-hevc.js
- Licence: MIT. Retain required notices if code is ever reused; prefer an independent Kotlin implementation informed by the algorithm.

The reference implementation does not merely concatenate streams. Its high-level process is:

1. Parse each input init segment and locate the HEVC sample entry and hvcC configuration.
2. Parse each source VPS/SPS/PPS and in-band parameter sets.
3. Inspect each sample's NAL units and parse only the clear slice-header window. Use the subsample encryption map to identify protected ranges.
4. Check that feeds represent the same picture moment: matching sample counts, decode-time anchors, per-sample durations and composition offsets, and compatible picture/slice reference syntax.
5. Determine which source slice/tile supplies each output cell.
6. Construct new output geometry and rewrite output VPS/SPS/PPS, including the output tile grid.
7. Rewrite each selected slice header for its destination CTB/tile address and output parameter-set configuration.
8. Copy coded slice data without decrypting or modifying protected payload bytes.
9. Rebuild output samples and corresponding encryption subsample metadata.
10. Package the composed samples as one output track for one decoder.

This reference is an algorithmic guide, not proof that every current F1 feed pair has the required structure or that its Tizen-specific packaging can be transplanted unchanged to Android/Media3.

## Important correction to the existing compatibility gate

The current HevcParameterSetCompatibility gate expects both source PPS records to declare a 2-column by 1-row tile grid. That is the wrong prerequisite for the F1 grid-composition model.

The source PPS describes each incoming feed. The output PPS describes the newly composed picture. Therefore:

- A source 1x1 picture may be a valid single output cell.
- A source with multiple tiles may contribute one or more selected cells, if its tile geometry and slice structure are supported.
- Source tile declarations must be parsed and validated, but must not be confused with the output grid declaration.
- The composer must derive output CTB geometry from selected source cells and write a new output SPS/PPS.
- Source feeds with different resolutions or cell dimensions are not automatically compatible. Only cells that can be mapped to the output CTB grid without resampling or altering coded payload are eligible.

Do not simply remove the current checks. Replace them with separate source-cell validation and output-grid validation.

## Evidence currently available

The branch contains a synthetic clear HEVC reconstruction proof and a physical Android hardware-decoder test. The synthetic fixture proves a narrow tile-slice assembly case. The device test proved that generated synthetic 2x1 and 2x2 streams rendered 50 frames using one hardware decoder instance and one output surface on the tested Samsung S22 Ultra.

The user's recent authorised F1 observations include HEVC feeds at different resolutions, including a 1920x1080/50 main feed and 960x540/50 onboard feeds. Some selected 960x540 feeds were reported with matching format/parameter-set fingerprints and matching observed fragment timing anchors. These are promising observations, but they are not proof of matching per-sample CMAF timing, POC/reference syntax, compatible cell geometry, or successful composition.

The current read-only CMAF inspector is diagnostic only. It does not compose samples or change the production player graph. Reports showing tilesEnabled=false mean the source PPS does not declare a tile grid; this alone must not reject a source as a candidate output cell.

Encryption observations must be treated separately. A reported Widevine L1 security level does not prove that the particular video samples inspected are encrypted. Before protected composition, establish the actual sample encryption scheme and metadata from authorised playback. Do not infer encryption from DRM security level.

## Implementation work packages

### WP0: preserve baseline and establish fixtures

- Keep the current synthetic tile-slice assembler and tests as a regression path.
- Add independent synthetic source-picture fixtures representing two separately encoded, same-geometry feeds, not just two slices split from one original picture.
- Include fixtures with different source PPS IDs, source 1x1 PPS, and supported deblocking override syntax.
- Retain deterministic expected output and FFmpeg decode/hash validation.
- Record fixture encoder settings, parameter sets, frame rate, GOP, POC and reference-picture behaviour.

Acceptance: fixtures are reproducible and the existing synthetic proof remains green.

### WP1: source-cell and output-grid model

Create explicit models for source track/sample-entry configuration, source VPS/SPS/PPS and CTB geometry, source picture and slice layout, candidate source cell selection, output grid rows/columns, CTB dimensions, coded dimensions and display crop, and source-to-output CTB address mapping.

Do not represent source PPS tile count as output-grid tile count.

Acceptance: unit tests prove valid 1x1 source cells can be mapped into a new 2x1 output grid, and reject mismatched CTB cell sizes, unsupported crops, invalid tile geometry and overflow.

### WP2: parameter-set rewriting

Implement bounded bit-level rewriting for output VPS/SPS/PPS:
- Generate valid output picture dimensions and CTB grid.
- Generate output tile column/row declarations and spacing.
- Reconcile parameter-set IDs and references.
- Preserve or correctly regenerate profile-tier-level, chroma format, bit depth, VUI, DPB/reorder limits and conformance-window semantics.
- Explicitly reject unparsed extensions or syntax whose semantics cannot be preserved.

Acceptance: parse rewritten parameter sets back and compare their declared geometry/configuration with the output-grid model. Validate with an independent HEVC parser/decoder.

### WP3: slice-header rewriting

Implement a dedicated slice-header parser/rewriter, separate from payload copying:
- Parse NAL header and slice-segment header only within the verified clear range.
- Map source slice/CTB addresses to output addresses.
- Rewrite PPS references and all dependent syntax whose bit positions/values change.
- Support the exact deblocking override and loop-filter syntax needed by observed feeds.
- Validate picture type, POC, temporal ID, reference-picture-list syntax and other picture-level invariants across selected inputs.
- Preserve CABAC-coded slice data byte-for-byte.
- Correctly handle emulation-prevention bytes and NAL length prefixes.

Do not claim support for a syntax element until tests cover both its present and absent forms. Unsupported weighted prediction, entry-point offsets, dependent slices, header extensions, or other syntax must be explicitly rejected until implemented.

Acceptance: rewritten clear synthetic output decodes and matches expected picture geometry/content. Byte-level tests prove coded payload regions are unchanged.

### WP4: frame/sample synchronisation

For every composed output sample, compare all selected feeds:
- Track timescale and decode-time anchor.
- Sample count and sample ordering.
- Per-sample duration and composition-time offset.
- Picture order count and reference-picture compatibility.
- IDR/CRA and random-access boundaries.
- GOP structure and discontinuities.
- Required parameter-set changes.

Use exact per-sample timestamps, not chunk-duration approximations. Do not compose samples from different moments merely because chunk start times look close.

Acceptance: deterministic positive tests and negative tests for one-tick offsets, missing samples, different durations, POC mismatch, reference-list mismatch and discontinuity.

### WP5: CMAF output and encryption metadata

Initially support a clear synthetic CMAF output path. Then implement protected sample metadata only after the clear composer is proven.

For protected samples:
- Verify compatible scheme, pattern, IV-size configuration and per-sample encryption layout.
- Parse clear/encrypted subsample ranges without retaining KIDs or IV values.
- Ensure every rewritten header byte remains in a clear range.
- Copy encrypted payload bytes unchanged.
- Rebuild senc/saiz/saio and related auxiliary metadata with exact sample-size/range coverage.
- Reject unsupported auxiliary-info layouts and incompatible encryption configurations.
- Confirm that the Android Widevine/Media3 path can consume the resulting packaging without exposing keys or decrypting payloads in application code.

Do not assume separate source DRM sessions can simply be merged. Determine the supported authorised packaging/session model before integrating protected output.

Acceptance: container parser round-trips output metadata; all sample ranges cover exactly; encrypted bytes are unchanged; Android secure playback is independently demonstrated.

### WP6: single-decoder Android integration

- Add a separate composed-media source/player path.
- Feed the composed output to one Media3 video renderer and one output surface.
- Keep Widevine as the decryption authority for protected content.
- Instrument decoder instance creation, codec name, output surface count, rendered frames, dimensions, frame cadence, dropped frames and playback errors.
- Keep the existing multi-player path available as a fallback.
- Do not instantiate hidden per-feed video decoders in the composer path.

Acceptance: physical-device evidence shows one active hardware video decoder and one output surface while the composed picture renders continuously.

### WP7: expand layouts and validate endurance

Only after two-feed success:
- Extend to 2x2 and then other supported layouts.
- Support layout changes without unnecessary decoder recreation.
- Test live edge, replay seek, discontinuity, feed switching, buffering, pause/resume and audio selection.
- Run long-duration and thermal/stress tests on phone and Android TV hardware.

Acceptance: stable playback with measured frame cadence and no additional video decoder instances.

## Proposed immediate next coding milestone

1. Add the new source-cell/output-grid data model and tests.
2. Change the compatibility gate to validate source cells independently from the output grid. Do not yet enable production composition.
3. Add two independently encoded synthetic 960x540 source feeds with matching timing and compatible picture/reference syntax.
4. Implement output VPS/SPS/PPS generation for a 2x1 output grid.
5. Add a parser round-trip test for generated output parameter sets.
6. Keep slice rewriting, CMAF muxing and Android integration as subsequent separately testable milestones.

This is the next milestone. Do not skip ahead to encrypted production playback before the clear-stream composer is correct.

## References

- F1 engineering: https://aws.amazon.com/blogs/media/how-formula-1-uses-aws-to-deliver-multiview-video/
- Independent F1 TV implementation: https://github.com/nicbra/f1tv-tizen
- F1-specific stitcher: https://github.com/nicbra/f1tv-tizen/blob/master/app/service/stitch-hevc.js
- GPAC HEVC/tile processing: https://github.com/gpac/gpac
- OpenVisualCloud Immersive Video Sample: https://github.com/OpenVisualCloud/Immersive-Video-Sample
- Fraunhofer OMAF.js: https://github.com/fraunhoferhhi/omaf.js
- Kvazaar HEVC encoder: https://github.com/ultravideo/kvazaar

Review each project's current licence and component-specific terms before copying code or adding dependencies. No Tiledmedia/TME/ClearVR component is permitted.
