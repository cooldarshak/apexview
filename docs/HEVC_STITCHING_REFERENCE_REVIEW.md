# HEVC stitching reference review

This note records how the supplied open-source projects inform the independent ApexView single-decoder POC. None of these references authorises use of Tiledmedia, TME or ClearVR technology. Those remain excluded.

## Primary F1-specific reference: nicbra/f1tv-tizen

Repository: https://github.com/nicbra/f1tv-tizen  
Relevant implementation: https://github.com/nicbra/f1tv-tizen/blob/master/app/service/stitch-hevc.js  
License declared by the repository: MIT.

The repository describes an F1-specific HEVC tile compositor. Its documented model is that feeds are encoded in lockstep and that each 1080p feed contains four 960x544 tile slices. The compositor selects cells from (feed, tile), creates a combined picture, rewrites VPS/SPS/PPS and slice headers, and copies encrypted slice payload ranges without decrypting them. It preserves/rewrites CMAF subsample encryption metadata so the TV Widevine implementation remains responsible for decryption.

This is the closest reference to the product objective. It is a reference to study and independently validate, not proof that the exact same feed profile or encryption layout is present in every F1 session or device. Its source makes claims about the Premium tvOS offer; ApexView must verify the actual init segments and media fragments it receives.

Important concepts to investigate and port independently:
- Cell selection is a pair: source feed plus tile index, not simply one feed per output cell.
- Reconfigure output SPS dimensions and PPS tile grid for the selected layout.
- Rewrite each selected tile slice header with its output CTB address.
- Preserve picture identity, POC, reference syntax and NAL temporal/layer identifiers.
- Set loop-filter-across-slice behaviour based on whether adjacent cells came from the same source feed.
- Parse CMAF sample boundaries and tfdt/sample timing before combining samples.
- Parse subsample encryption ranges structurally; never decrypt, log, retain or expose KIDs, IV bytes, keys or protected payloads.

## GPAC

Repository: https://github.com/gpac/gpac  
Relevant source files: src/filters/tileagg.c, src/filters/tilesplit.c, src/filters/hevcmerge.c, src/filters/hevcsplit.c.  
License: LGPL-2.1.

GPAC documents HEVC tile aggregation into one bitstream exposed to one decoder. Its tileagg filter is especially relevant to timing/alignment and reaggregation; hevcmerge is relevant to rewriting/recomputing slice addresses when a grid is reconfigured. This is the strongest general-purpose implementation reference for the bitstream aggregation side. Do not copy GPAC code into the Android app without a separate LGPL compliance and integration decision.

## Fraunhofer HHI omaf.js

Repository: https://github.com/fraunhoferhhi/omaf.js  
The project describes resolving HEVC extractor NAL units from multiple tile tracks and repackaging them into one HEVC track for one decoder instance. It is useful for understanding extractor tracks and single-bitstream repackaging. Its browser/MSE and OMAF-specific assumptions differ from F1's CMAF delivery. Review its repository license before considering code reuse.

## OpenVisualCloud Immersive-Video-Sample

Repository: https://github.com/OpenVisualCloud/Immersive-Video-Sample  
The project documents OMAF tiled delivery, MCTS-based HEVC encoding and a reference Android player. It is archived and no longer maintained. Its reference player includes a multi-stream decoding path, so it is not itself evidence of the single-decoder target; its encoding/packing and viewport/tile-processing components remain useful background.

## Kvazaar

Repository: https://github.com/ultravideo/kvazaar  
License: BSD-3-Clause.

Kvazaar is the synthetic encoder used by this POC. The --tiles, --slices tiles, --no-wpp and --mv-constraint frametilemargin options allow reproducible complementary tile-slice fixtures. The 2x2 fixture added alongside this note is generated locally from one synthetic source picture sequence, then independently decoded and frame-hash compared.

## ApexView implementation boundary

The branch now has both clear and protected CMAF writers/composers and an archive
playback handoff. The early complementary-slice proof is no longer its full scope.
See [ARCHIVE_NATIVE_VOD_HANDOFF.md](ARCHIVE_NATIVE_VOD_HANDOFF.md) for the current
Tizen/GPAC/Intel/Eyevinn review, finite native clear-HLS replay path, strict
protection boundary and remaining real-device acceptance. Synthetic frame hashes
and phone callback evidence do not establish stable protected/TV playback.
