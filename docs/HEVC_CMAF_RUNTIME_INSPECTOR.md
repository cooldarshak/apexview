# Read-only F1 HEVC/CMAF runtime inspector

## Purpose

The Tile testing layout can now inspect the actual CMAF initialization metadata and media-fragment metadata delivered for two manually selected F1 feeds. It is a diagnostic milestone only. It does not rewrite streams, create a stitched output, or change the active playback graph.

The architecture invariant remains one hardware video decoder for the eventual composed output. This inspector does not attempt composition or claim that the current two independent playback feeds use one decoder.

## How to run it on a phone

1. Install the debug APK built from this branch and open ApexView.
2. Start an F1 session and allow the feeds you intend to compare to load normally. The app captures only parsed initialization metadata for registered F1 feeds as they start.
3. Open **Layout → Tile testing**.
4. In the feed picker, manually choose exactly two feeds from that same F1 session, then start/apply the selection.
5. Keep the feeds playing while the inspector waits for media fragments. The bottom **F1 Stream Inspector** panel shows the existing HEVC profile comparison followed by the CMAF results.
6. When the counters show that both feeds have init metadata and media fragments, tap **Copy report** and share the copied text. Do not send raw stream fragments, manifests, or license URLs.

If one feed was already playing before this build was installed, restart that feed/session in the updated app so its init segment is fetched and observed. The inspector does not force a player restart when Tile testing is opened.

## What is inspected

- Video track/sample-entry information, dimensions, track timescale and parsed hvcC VPS/SPS/PPS counts.
- Parsed PPS tile-grid fields exposed by the current HEVC parser.
- Fragment sample counts, base decode time, first DTS/PTS, sample durations and whether the timing table is complete.
- Encryption structure presence and completeness, including subsample range coverage, saiz/saio, auxiliary records and seig mapping checks.
- A fail-closed profile gate, HEVC tile-configuration gate and fragment timing/encryption gate.

The app retains parsed init metadata in memory so that separately downloaded media fragments can be interpreted with the correct track defaults. Init responses are observed for registered F1 feeds. Full media-fragment responses are copied for inspection only for the two selected Tile testing feeds.

## Safety and scope boundaries

- The observer is pass-through: the bytes returned to Media3 are unchanged.
- Captures are transient and bounded to 8 MiB per response. Oversized or incomplete captures are skipped.
- Captured buffers are zeroed after synchronous inspection. Parsed metadata only is retained in memory.
- No stream URL or license URL is added to diagnostics.
- KID values and IV values are not returned or logged. IV bytes are skipped when the existing CMAF parser reads sample auxiliary structures.
- No protected media sample payload is decrypted, decoded by the inspector, saved, exported, or logged.
- Widevine remains the only component that handles decryption for playback.
- Tiledmedia, TME, ClearVR and Tiledmedia-owned components are not used.

## How to interpret results

MATCH means only that the specific metadata gate passed for the observations it received. It is not proof that independent F1 camera feeds can be assembled into a valid single HEVC picture, that the slice headers can be rewritten, that Widevine will accept a newly composed stream, or that playback will remain stable.

WAITING means the corresponding init segment or media fragment has not yet been captured or parsed. NOT_COMPATIBLE is fail-closed and includes the reason(s) reported by the current parser. Missing, unsupported or incomplete metadata is not treated as compatible.

The current HEVC tile-composition gate is still deliberately narrow. It must not be described as supporting arbitrary tile grids or arbitrary F1 feed combinations. Slice-header rewriting, actual tile extraction from independent feeds, safe encrypted CMAF repackaging, single-decoder playback integration, and on-device proof with real F1 media remain separate follow-on work.
