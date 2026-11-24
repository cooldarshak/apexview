# Single-decoder layouts and TV casting status

**Current checkpoint (24 November 2026):** [Build 839 GREEN](https://github.com/cooldarshak/apexview/actions/runs/45391665766), source `5090df55`. Native multifeed, map/tower and caption appearance have been observed on phone and TV; TV three-feed stability, Pitwall TLS and baked Google Cast buffering remain open. Physical/release acceptance is not implied by CI. The [16-workstream master plan](PITWALL_PERFORMANCE_AUDIT_PLAN.md) owns the active work queue.

## Current playback and layouts

Multiple mode is capped at four feeds. Single mode permits up to 24 compatible clear archive feeds, subject to source geometry and hardware limits; protected composition supports up to six. These are implementation limits, not hardware acceptance claims. A single-mode failure must not silently start multiple independent video decoders.

Clear and authorized protected composition use one composed HEVC track and a single hardware decoder where the compatible path succeeds. Native map and timing tower are separate overlays. Phone and TCL native multifeed have been demonstrated, but three-feed TV stability and physical presentation still require work. Main + strip can currently select a bottom strip in landscape; the intended main-left 2/3 and right strip 1/3 layout, lock auto-hide and timing defects are open in the master plan.

## Current Cast routes

- **Clear baked Google Cast:** tested output includes actual scene overlays, AVC video and AAC commentary via rolling HLS. It buffers intermittently; phone production continues. Direct EGL capture and bounded clear-window fallback are implemented. Startup buffering, capture pacing, retention and unnecessary local presentation are open.
- **Native Pitwall:** the receiver uses the composed stream and native scene geometry/overlays. Authorized protected media requires the TV's own entitlement and Widevine session. Mutual TLS, matching pairing codes and pinned media connections are required. Build 839 fails with SSLHandshakeException on the tested pair; this is not accepted.
- **Optional registered Google layout receiver:** separate from baked output; requires a real registered receiver ID and hardware verification. Do not claim registration or protected Google Cast acceptance from the clear baked test.

See [native TV setup](LOCAL_TV_RECEIVER.md) and [device profiling](PITWALL_50FPS_DEVICE_PROFILE.md). Caption appearance works natively on both tested devices; Cast continuity and full track/programme acceptance remain open.

## Optional Google layout receiver setup (registration required)

This alternative has Google's developer-registration fee. It is not needed for the free native Pitwall receiver.


1. Host the bundle produced by `python3 tools/package_cast_receiver.py --output-dir _site` on HTTPS. The `Publish Cast layout receiver` workflow deploys it to the repository's GitHub Pages site; the Android workflow also uploads the bundle as `pitwall-cast-layout-receiver`.
2. In https://cast.google.com/publish/, register a **Custom Receiver** with the deployed HTTPS page as its receiver URL. Register the TV/Cast device for testing if the receiver is unpublished. This step belongs to the developer's Google account and cannot be replaced with an arbitrary public receiver app ID.
3. Set repository Actions variable `PITWALL_CAST_RECEIVER_ID` to the eight-character application ID and run the Android debug workflow again. The ID is not a secret. With it present, the ordinary Cast button launches the layout receiver. An empty variable preserves the Default Media Receiver.
4. Install the signed APK in place. Enable logs, open four compatible replay feeds, select Main + strip and connect through the Cast button. Check for `CAST_SOURCE_QUALITY minimum=720` with actual source dimensions, then `SCENE_RENDERING_CONFIRMED`. Confirm main/side source identity, moves/resizes, audio, pause and seeking on the actual TV.

The implementation is not complete until step 4 passes. The protocol and unit tests alone cannot prove the TV's WebGL video texture support, CAF playback or receiver registration. Never describe a missing receiver ID as a completed layout fix.

Official API references: https://developers.google.com/cast/docs/web_receiver/core_features and https://developers.google.com/cast/docs/reference/web_receiver/cast.framework.CastReceiverOptions .
