# Build 637 protected scene and playback handoff review

Device: Galaxy S22 Ultra (SM-S908E), Android 16, Pitwall 1.0.637.
Input: Screenshot_20261121_230541.jpg, pitwall-20261121-173510-093.log.txt
and trace-taro-BP2A.250605.031.A3-2026-11-21-23-06-39.perfetto-trace.
Content 1000010439, protected Las Vegas qualifying, four source channels:
1033 / 1009 / 1011 / 1008. Actual qualities: 1920x1080 / 640x360 /
960x540 / 960x540.

## Verified advance

23:05:30.901: protected capture and composition succeeds.
23:05:30.954: generated protected DASH init validation passed.
23:05:31.041: previous decoder released, active video decoder count = 0.
23:05:35.809: first frame of 1920x2176 composite rendered by one
c2.qti.hevc.decoder, Widevine L3 keys loaded.
23:05:52.867: composite running, 850 rendered frames, zero drops.
23:05:55.382: 975 frames observed on composed host.
The screenshot confirms four camera pictures were decoded, but their
source atlas pixels were displayed as a narrow portrait-shaped video,
not as four independently laid out visual windows.

## Cause 1: tall atlas was incorrectly displayed as a video feed

The protected rolling code handed the valid decoder output to a single
PlayerView, expanded only one source tile to screen size, hid the other
tiles and forced SINGLE_DECODER_TEST layout. A mixed-resolution HEVC
picture of 1920x2176 is a codec tile atlas, not a scene composition.
The app already had VideoScene UV crop mapping and the
SingleDecoderSceneView one-output GLES renderer for finite clear
archives. That renderer was not connected to protected rolling
composition.

Change: retain source-to-tile UI layout, expose the real mixed
HevcMixedArchiveAtlasPlan.sourceRects via guarded protected CMAF
geometry inspection, and draw four UV-sampled windows using one
decoder texture. Preserve the actual HEVC stream and source order.
Dynamic changes to source geometry update scene metadata only.

For authorized L3 playback, the existing single-decoder texture scene
is reused. For L1, the renderer requires protected EGL and a secure
output SurfaceView; no insecure fallback, DRM downgrade, re-encryption
or decrypted-frame readback is added.

## Cause 2: user layout change terminated the compositor

The chooseLayout method explicitly cancelled protected composition
unless nativeArchiveTiles was populated. That flag applied only to
the finite clear archive path, never protected rolling playback.
The new protected session also forced SINGLE_DECODER_TEST, so simply
selecting TV or another layout stopped capture and restored the
original streams. This matches the log:
23:05:55.382: one composed decoder playing.
23:05:56.754: composed host discontinuity from 11,978 to 564,882ms.
23:05:56.759: original codec flushed, media item restored.
23:05:57.426: layout switched back to TV and composition absent.
There was no preceding ExoPlayer fatal error.

Change: cosmetic TV/grid/featured layout changes retain protected
decoder ownership, capture job and source-to-window mapping.
Explicit source-ownership operations still stop composition safely.
No automatic layout-mode switch occurs at protected startup.

## Surface and graphics concerns

Repeated SurfaceView BufferQueue dequeue timeouts with about 20-21
dequeued buffers and 3-4 acquired buffers coincide with the improper
raw-atlas presentation. The existing GLES scene has independent
frame-latch, vsync and EGL swap metrics; the new protected path will
expose those diagnostic records. No claim is made that actual physical
50fps presentation or map/tower graphics have passed sign-off.
The attached Perfetto still requires independent trace decoding.

## Regression coverage

1. Synthetic protected CMAF init -> four-source scene geometry.
2. Correct 50fps derived from CMAF duration and sample count.
3. Each source gets a distinct tile crop and retains source identity
   after reordering/moving TV windows.
4. Both mixed-resolution source rectangles and equal-size PPS tile
   fallback supported.
5. Protected and clear rolling handoff type checks remain strict.
6. Existing widevine, HEVC, archive, Cast, RenderNode and signed
   Android build tests remain required.

## Open hardware acceptance gates

Four-feed source-correct windows on Galaxy phone and Android TV,
sustained playback across multiple rolling fragments, single video
decoder at all times, runtime Widevine entitlement, secure L1 GPU
availability, video/overlay frame pacing, and Cast parity. CI alone
cannot verify those, and the map/tower sign-off remains OPEN.
