# Clear HEVC CMAF packaging

## Scope

This milestone joins the output VPS/SPS/PPS and re-identified HEVC samples into a clear, single-track fragmented MP4. It exercises the CMAF container path independently of the Android playback graph.

The writer is intentionally clear-only. It does not accept DRM keys, IVs, KIDs, encryption auxiliary records, or encrypted sample payloads. Protected F1 samples must remain on the normal Widevine playback path and are rejected by the existing clear media-fragment writer.

## Output structure

The initialization segment contains:

- `ftyp` with `iso6`, `cmfc`, and `hvc1` brands
- one `moov/trak/mdia/minf/stbl` video track
- one `hvc1` visual sample entry and matching `hvcC`
- VPS/SPS/PPS arrays sourced from the exact generated output parameter sets
- an empty progressive sample table and `mvex/trex` defaults for fragmented samples

The media segment contains one `moof` and one `mdat`. Its `tfhd` explicitly selects sample-description entry 1, `tfdt` carries the base decode time, and `trun` carries each sample duration, size, flags, and signed composition-time offset.

## Validation

The Kotlin test builds the initialization and media segments from the 50-picture Kvazaar synthetic 2×1 tile fixture, then inspects the combined file with `CmafBoxInspector`. It verifies the track, sample entry, parsed hvcC parameter sets, tile geometry, trex defaults, sample count, timing, and absence of encryption boxes.

The workflow then uses FFprobe and FFmpeg independently. It verifies a single HEVC `hvc1` video stream, expected dimensions and frame count, and frame-by-frame decoded hashes identical to the original reassembled HEVC reference.

This is not a proof of Widevine acceptance, live F1 fragment compatibility, or Android hardware-decoder playback. Those remain separate integration and device-validation milestones.
