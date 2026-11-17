# Native archive VOD handoff

## Observed failure in build 466

`apexview-466-phone.log` never reaches decoder handoff. Three real HLS/CMAF feeds
compose successfully, but their 5.76-second batches take 15.517, 13.914 and 7.334
seconds. Preflight continually samples the advancing original audio player's
position, so output cannot catch that clock before the generic timeout.

The defect is delivery and clock ownership, even though HEVC/CMAF composition
passes. A longer timeout cannot fix a producer that consistently falls behind.

## Replacement for compatible clear HLS archives

`HevcArchiveVodSession` prepares a fixed position while normal feeds keep playing.
It publishes a finite DASH timeline with one video representation and generates
numbered composed CMAF chunks when Media3 requests them. There are at most two
source fetches concurrently, one warm-ahead job and three cached output chunks.
The default common source cap is 720p for two feeds, 540p for three/four, subject
to actual offered renditions, total width and the device's HEVC size/rate support.
All source tiles are selected to preserve complete feed pictures.

Only after a real chunk, MPD and hardware size/rate check succeed does the handoff
wait for all source video decoder release callbacks. Original loaders and audio
are then stopped. The existing host ExoPlayer, renderer and PlayerView play the
composed DASH video plus the selected native HLS audio rendition, using Media3's
`FilteringMediaSource` and `MergingMediaSource`. Native buffering pauses both.
No extra PLAY request, independent source clock or rolling archive publication
loop drives this path. Pause, speed and seek operate on the host's finite archive
position. A verified HLS program-date-time epoch is carried into the static DASH
timeline so timing/map overlays retain their replay clock. Continue Watching saves
the native host position and original tile bounds. Exit/failure restores all original feeds there, under the normal decoder
budget, and preserves pause/mute state and saved tile bounds.

The composed PlayerView occupies the available video canvas. Hidden source feeds
are excluded from the surface selection and playback/synchronization loops. FIT
preserves the encoded picture's aspect ratio; a horizontal encoded row can have
letterboxing. A large-main/small-side encoded layout remains separate work.

## Protection and compatibility boundary

This backend accepts only genuinely clear HEVC CMAF. Both initialization and media
are independently checked by the existing clear composer. No encryption metadata,
keys or sample protection is removed. A protected track or actual DASH response
routes to the existing strict Widevine/CBCS backend, which remains available and
is not proven by these clear archive tests. Native audio uses the host's authorized
source factory and existing DRM callback. No application-side content decryption
or HLS key fetching is added.

Finite DASH random seeks require an independently decodable first sample in every
advertised source chunk. Inspecting a preceding GOP is insufficient if only a
non-IRAP target fragment is delivered to a newly reset decoder. The native path
rejects a mid-GOP probe before release and reports a later incompatible chunk
without retrying that permanent failure. Supporting non-independent fragments
requires a different GOP-aware output index, not relabeling a sample as sync.

Cross-feed PTS/DTS, picture identity/POC, SPS/PPS, tile geometry and source slice
compatibility remain enforced by the existing composer. Representation changes,
HLS gaps/discontinuities, mismatched audio timelines, unsupported protection,
unavailable common renditions and unsupported decoder sizes fail with normal
playback retained/restored. Only recoverable source read errors are retried.

## Reference review

The existing Kotlin bitstream port retains the F1 Tizen MIT attribution in
`LICENSES/F1-TV-Tizen-MIT.txt`. Its native replay design motivates finite indexed
segments and one playback clock. ApexView keeps its own bounded parsers/composer.

Reviewed GPAC `src/filters/hevcmerge.c` (LGPL family; file declares LGPL v2-or-later)
includes an encryption-rewrite TODO. Intel's `360SCVPHevcTileMerge.cpp`,
`OmafTilesStitch.cpp` and Android `VideoDecoder_hw.cpp` carry BSD-style notices;
the surrounding sample is not a one-decoder playback proof. Eyevinn hi265's MIT
`pkg/retile` requires compatible, CTB-aligned, motion-constrained streams, with
additional tile/WPP/header restrictions. None fixes the advancing-clock defect
or supplies a verified protected F1 Android pipeline. No code from those three
projects is imported by this change.

Primary references:
- https://github.com/nicbra/f1tv-tizen/blob/master/app/service/stitch-hevc.js
- https://github.com/gpac/gpac/blob/master/src/filters/hevcmerge.c
- https://github.com/OpenVisualCloud/Immersive-Video-Sample/blob/master/src/360SCVP/360SCVPHevcTileMerge.cpp
- https://github.com/OpenVisualCloud/Immersive-Video-Sample/blob/master/src/OmafDashAccess/OmafTilesStitch.cpp
- https://github.com/OpenVisualCloud/Immersive-Video-Sample/blob/master/src/player/player_lib/Decoder/VideoDecoder_hw.cpp
- https://github.com/Eyevinn/hi265/tree/main/pkg/retile
- https://github.com/androidx/media/tree/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source

## Validation

Focused Robolectric regressions exercise fixed-position preparation despite an
advancing external clock, bounded parallel reads, finite timeline/epoch timestamps,
nonsequential reads, legal rendition selection, same-offer native audio selection,
preflight failure before release, protected/clear disagreement, mid-GOP rejection,
resource ranges/close and complete source tile preservation. CI independently
FFmpeg-decodes two/three/four-feed outputs and compares every frame with a decoded
source hstack, including full 2x2-tiled source pictures.

A passing JVM test or FFmpeg decode does not establish MediaCodec/Widevine/TV
success. New real-phone and TV results must still show all real camera pictures,
correct surface dimensions, native audio/seek, sustained rendered frames and one
active hardware video decoder. Callback counts are app-observed counts, not an
OS-wide decoder inventory. Protected footage requires its own keys-loaded proof.

## Phone-only capture (aShell)

Start logging before opening the archive and running Layout > Single Encoder
Testing with two feeds first. Keep testing active when taking the codec dump.

```sh
logcat -c
logcat -v time ApexviewSingleDecoder:V ApexviewPlaybackOffer:V MediaCodec:I CCodec:I Codec2Client:I ExoPlayerImpl:I MediaCodecVideoRenderer:I '*:S' > /sdcard/Download/apexview-archive-test.log
```

After testing pause, resume and seek, return to aShell and stop capture with
Ctrl+C. If returning to the shell stopped its foreground command, collect the
buffer instead:

```sh
logcat -d -v time | grep -iE 'ApexviewSingleDecoder|ApexviewPlaybackOffer|MediaCodec|CCodec|Codec2|ExoPlayer|Widevine|Drm|BufferQueue|Surface' > /sdcard/Download/apexview-archive-test.log
dumpsys media.codec > /sdcard/Download/apexview-codecs.txt
```

Some phone firmware supplies little/no `media.codec` dump. The rendered-frame,
surface and per-player decoder callbacks in the test panel/log remain necessary.
Share the log and a screenshot of that panel; a recording helps assess layout and
buffering. These commands run directly in aShell and do not use `adb`.
