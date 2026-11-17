# Single-decoder layouts and TV casting status

The Settings decoder preference persists. Multiple mode is capped at four feeds. Single mode allows selection of up to 24 clear archive feeds subject to the source, picture geometry and decoder limits; the protected backend accepts two through six. These are implementation limits, not device validation claims. Single-mode failures must keep source videos stopped and offer retry; they must not silently resume independent video decoders.

Clear archive playback composes source pictures into an HEVC atlas and sends it to one MediaCodec. The phone scene uses GLES to place regions of that atlas in the chosen layout. Timing tower and track map are separate UI overlays. Device evidence in log 483 shows four clear feeds, a 3840x540 composition and advancing Qualcomm decoder frames for over a minute. Five and six feed preparation alone does not establish stable playback. Protected playback and the TV remain unverified.

The protected five-feed layout uses five columns and one row so every output cell has a source. Its width and device capability still need validation; do not infer support from preparation. The TV's striped picture has no confirmed cause. Collect decoder, composition and MediaCodec logs for the affected stream before diagnosing it.

The standard Google Cast picker is wired for compatible clear archive video in either decoder mode. Cast now requests at least 720p per source and validates each source's real HEVC display height. A 540p phone composition is not reused for casting. If the source or combined picture cannot meet the floor, setup returns a quality error rather than silently sending 540p/270p. 1080p is preferred when the user requests 1080p and the combined picture fits. The 720p floor describes source detail, not the screen pixel height of every small window.

A registered custom Cast Web Receiver now receives the actual phone video windows (source mapping, position, size and z order), draws them from the one decoded atlas with WebGL, and accepts updates while windows move. This includes the main-left / three-right arrangement, using the same VideoScene sampling contract as native GLES. It does not re-encode the feeds. With no registered receiver app ID, the Default Media Receiver still shows the encoded grid and cannot apply the phone layout. Registration, receiver GPU compatibility and real layout rendering remain device-validation requirements. Separate Compose timing/map overlays are not captured by this implementation. Receiver compatibility, pairing UI, playback, seeking and session lifecycle require device tests. Live or protected casting is not implemented. It needs legitimate receiver-side Widevine licensing and secure output; the native receiver does not need Google registration. No DRM is bypassed.

The timing archive loads position and timing data concurrently, and parsing is moved off the main thread. Map publication no longer waits for timing history downloads. Missing position data no longer stops timing processing. Each topic publishes independently without erasing the other. This behavior still needs testing on the TCL TV.

Remaining validation: CI for the newest native-receiver changes; signed update install; TV secure-decoder corruption and single-decoder count; phone five-plus-feed stability and cancellation/seeking; independent tower/map loading; all layouts and remote focus; and real Cast receiver behavior including synchronization and error recovery.


## Free local TV casting

The APK now includes a native Pitwall TV receiver and FCast v3 core control with a negotiated Pitwall scene extension. It discovers receivers through Android NSD, transfers the combined clear HEVC stream and applies phone video layout in the TV's single-decoder GLES renderer. It requires no Google developer registration. Phone video/audio tracks stop after the receiver renders; the phone becomes a controller/relay. Google Cast now shares track disabling and remote position handling. Miracast is a system mirroring handoff and requires the phone picture to stay active. See [LOCAL_TV_RECEIVER.md](LOCAL_TV_RECEIVER.md) for setup, protocol, restrictions and pending device tests.

## Optional Google layout receiver setup (registration required)

This alternative has Google's developer-registration fee. It is not needed for the free native Pitwall receiver.


1. Host the bundle produced by `python3 tools/package_cast_receiver.py --output-dir _site` on HTTPS. The `Publish Cast layout receiver` workflow deploys it to the repository's GitHub Pages site; the Android workflow also uploads the bundle as `pitwall-cast-layout-receiver`.
2. In https://cast.google.com/publish/, register a **Custom Receiver** with the deployed HTTPS page as its receiver URL. Register the TV/Cast device for testing if the receiver is unpublished. This step belongs to the developer's Google account and cannot be replaced with an arbitrary public receiver app ID.
3. Set repository Actions variable `PITWALL_CAST_RECEIVER_ID` to the eight-character application ID and run the Android debug workflow again. The ID is not a secret. With it present, the ordinary Cast button launches the layout receiver. An empty variable preserves the Default Media Receiver.
4. Install the signed APK in place. Enable logs, open four compatible replay feeds, select Main + strip and connect through the Cast button. Check for `CAST_SOURCE_QUALITY minimum=720` with actual source dimensions, then `SCENE_RENDERING_CONFIRMED`. Confirm main/side source identity, moves/resizes, audio, pause and seeking on the actual TV.

The implementation is not complete until step 4 passes. The protocol and unit tests alone cannot prove the TV's WebGL video texture support, CAF playback or receiver registration. Never describe a missing receiver ID as a completed layout fix.

Official API references: https://developers.google.com/cast/docs/web_receiver/core_features and https://developers.google.com/cast/docs/reference/web_receiver/cast.framework.CastReceiverOptions .
