# Single-decoder scene layouts and full-layout casting

## Phone evidence retained

Build 470 was confirmed working by the user on the Galaxy S22 Ultra. Its log shows
three original HEVC decoders released, then one `c2.qti.hevc.decoder` rendering the
2880x540 archive composition at approximately 50 fps. The buffer reached 52.3 s.
Two short buffering periods coincided with large replay-position jumps. This is
clear archive footage, not proof of protected Widevine or TV playback.

## Playback mode and layout are separate

Settings offers **Multiple decoders** and **Single decoder** independently of
Main + strip, Grid, TV, My layout, Snap/Free, drag/resize, overlap and z order.
The preference survives app restarts; a particular incompatible stream can still
fall back safely without changing the saved preference. The source players are
paused during activation and released only after preflight succeeds.
The existing solvers and tile controls are reused. The atlas's camera/source order
stays fixed; changing the main window or dragging a tile only changes its sampled
region and destination rectangle. It does not start additional video players.
Audio selection reuses the native host and validated per-channel audio renditions.
Adding/removing/replacing sources rebuilds the atlas with original source video
locked off during that change. Failure restores normal playback explicitly.

One MediaCodec output SurfaceTexture feeds GLES. GLES samples each camera region
into one visible TextureView output. The internal codec-to-texture queue and the
visible output queue are distinct; this is one hardware decoder and one composed
video view, not a claim that Android allocates only one Surface object in total.
Timing/map/UI remain Compose overlays. GLES uses a per-frame texture update and
cached geometry; layout changes do not re-encode or rewrite video samples.
This new scene renderer still needs real-phone and TV validation. The measured
build-470 decoder result does not validate the new GLES output automatically.

Only verified clear archive CMAF takes this renderer. Protected streams retain the
existing guarded playback backend. No protected texture is read into an unprotected
context, and no DRM flag is disabled. Live/six-feed/protected layout support is not
claimed. Existing app feed limits remain unchanged.

## Implemented casting preview

The earlier paired-LAN browser receiver remains in the source as an experimental
component, but its browser/address/code workflow is no longer exposed in the UI.
It is not the Google Cast experience requested for production.

The TV pulls one composed HEVC stream and selected native audio into one HTML video
player. A WebGL scene places its camera regions using the phone's window geometry.
A separate transparent UI-only image layer includes the actual timing tower, map,
labels and controls. Video views are deliberately outside this capture layer.
There is no MediaProjection, display mirroring, video screenshot transport,
hardware video encoder, application decryption or re-encoding of camera pixels.
UI snapshots are timestamped and bounded so the receiver can choose the overlay
nearest its video position. The receiver preserves the phone canvas aspect ratio.
The sender carries pause, seek, speed, mute and audio-selection changes.

The relay keeps F1 URLs/authentication on the phone. It exposes only a random
session capability after pairing, whitelisted generated media and audio resources,
sanitized scene metadata and UI images. It never provides arbitrary URL proxying,
a key URI, a license endpoint or tokens. Audio protection is checked before the
receiver address is exposed. Pairing attempts, sockets, worker queues, response
sizes, cached UI images and media caches are bounded. Stopping casting or closing
the viewer releases the receiver. Source leases allow decoder-mode changes without
zeroing a still-active receiver's stream. Changing the selected feeds stops the
old receiver so it cannot silently show a different selection.

This is an experimental receiver-link preview, not finished Google Cast discovery or
background casting. Browser codec/GPU support and actual A/V/UI behavior must be
verified on a real receiving device. Phone UI capture currently requires the app
to remain open. Cast-specific hardware decoder counts cannot be inferred from an
HTML video element count. Do not claim protected or six-feed casting is working.

## Google Cast integration boundary

Google's Default Media Receiver cannot render custom timing/map/UI. A custom Web
Receiver must be hosted over HTTPS and registered to get a receiver application ID.
That user-owned configuration is not present in this repository. No default
receiver is substituted for full-layout casting and no unregistered ID is invented.
Google Cast discovery/launch and a platform-native TV receiver remain outstanding.
The Google Cast entry in the layout menu reports that it is unavailable until a
real registered full-layout receiver and sender have been integrated and tested.

## Startup and seeking

Offer, initialization and audio-manifest requests for separate cameras run in
parallel, and the fragment reader uses at most four concurrent I/O workers. A
seek cancels an unrelated warm-ahead request so it cannot keep processing the old
position. Actual time to first frame still depends on network, GOP and decoder.
Device measurements are needed; instant playback at every random seek is not claimed.

Primary references:
- https://developers.google.com/cast/docs/registration
- https://developers.google.com/cast/docs/web_receiver
- https://developers.google.com/cast/docs/web_receiver/basic
- https://source.android.com/docs/core/graphics/arch-st
- https://github.com/video-dev/hls.js/tree/v1.6.19

The browser preview uses pinned hls.js 1.6.19 from jsDelivr, Apache-2.0 licensed.
Its source/license link is retained in the receiver asset. No Tiledmedia/ClearVR
code or proprietary stitching SDK is used.

## Checks

The existing debug unit/assembly tasks remain required. Added scene tests cover
preset geometry, source identity through z-order changes, crop boundaries,
off-screen/free overlap and orientation. Receiver tests cover the same contract,
including a six-window protocol case without asserting six-feed device support.
Robolectric/socket tests cover pairing, unauthorized resource rejection, bounded
attempts, media-source lease lifetime and finite HLS/codec parsing by Media3.
Existing FFmpeg pixel comparisons and single-decoder diagnostics remain enabled.

The debug test APK now also contains `SingleDecoderSceneInstrumentationTest`. It
plays the clear synthetic composed fixture through the actual GLES view, samples
the output pixels, moves each camera to a different rectangle and overlaps them.
It requires source pixels to follow the windows and the top window to win while
exactly one hardware decoder initialization is observed. It is packaged by CI, but
only running it on a device proves that device's renderer. From aShell on the phone,
after installing the matching app and instrumentation APKs:

```sh
am instrument --user 0 -w -e class app.apexview.scene.SingleDecoderSceneInstrumentationTest app.apexview_custom.test/androidx.test.runner.AndroidJUnitRunner
logcat -d -v time | grep -iE 'ApexviewSingleDecoder|MediaCodec|CCodec|ExoPlayer' > /sdcard/Download/apexview-scene.log
```

Synthetic pixel verification is separate from acceptance with genuine F1 footage,
authorized protection, long-running playback and the receiving TV's decoder count.

Phone acceptance: test 2, 3, 4 feeds in both decoder modes; Main + strip, Grid and
TV; Snap/Free movement/resizing/overlap; audio; seeking/pause/speed; rotation/PiP;
source add/remove/switch; mode changes and fallback. Require actual pictures in
all cells, accurate map/tower timing and one video-decoder callback count in Single.
Then test receiver pairing, every overlay, control changes, stop/disconnect and
both aspect ratios on the TV. The new native/receiver paths are unverified until
those device results are supplied.
