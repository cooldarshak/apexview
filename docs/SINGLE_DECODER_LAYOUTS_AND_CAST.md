# Single-decoder layouts and Google Cast status

The Settings decoder preference persists. Multiple mode is capped at four feeds. Single mode allows selection of up to 24 clear archive feeds subject to the source, picture geometry and decoder limits; the protected backend accepts two through six. These are implementation limits, not device validation claims. Single-mode failures must keep source videos stopped and offer retry; they must not silently resume independent video decoders.

Clear archive playback composes source pictures into an HEVC atlas and sends it to one MediaCodec. The phone scene uses GLES to place regions of that atlas in the chosen layout. Timing tower and track map are separate UI overlays. Device evidence in log 483 shows four clear feeds, a 3840x540 composition and advancing Qualcomm decoder frames for over a minute. Five and six feed preparation alone does not establish stable playback. Protected playback and the TV remain unverified.

The protected five-feed layout uses five columns and one row so every output cell has a source. Its width and device capability still need validation; do not infer support from preparation. The TV's striped picture has no confirmed cause. Collect decoder, composition and MediaCodec logs for the affected stream before diagnosing it.

The standard Google Cast picker and Default Media Receiver are wired for compatible clear archive video. The relay sends the encoded atlas and selected audio, not the phone's GLES geometry or timing/map overlays. Receiver compatibility, pairing UI, playback, seeking and session lifecycle require device tests. Live or protected casting is not implemented: it needs an appropriately registered and hosted receiver and legitimate Widevine licensing. No DRM is bypassed.

The timing archive loads position and timing data concurrently, and parsing is moved off the main thread. Map publication no longer waits for timing history downloads. Missing position data no longer stops timing processing. Slow positions can still delay publishing the tower; this remains to be addressed and tested on the TCL TV.

Remaining validation: full Gradle and CI tests; signed update install; TV secure-decoder corruption and single-decoder count; phone five-plus-feed stability and cancellation/seeking; independent tower/map loading; all layouts and remote focus; and real Cast receiver behavior including synchronization and error recovery.
