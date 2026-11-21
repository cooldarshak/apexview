# Build 643 Vegas protected playback: severe UI jank despite one working decoder

Device: Samsung Galaxy S22 Ultra, Android 16, 21 Nov 2026.
Inputs: pitwall-20261121-181012-420.log.txt and
trace-taro-BP2A.250605.031.A3-2026-11-21-23-42-07.zip.
App: Build 643. Selected sources: 1080p + 480p + 540p + 540p.
This diagnosis uses the complete textual app log. The binary Perfetto
trace was provided but not decoded by the present tool environment,
so physical GPU/SF FrameTimeline sign-off remains explicitly open.

## Measurements and controlled interpretation

- 23:40:50.098: valid composed 1920x2176 output, Widevine L3
  keys loaded, one c2.qti.hevc.decoder, first frame rendered.
- 23:41:26.263: 2,001 frames rendered, 0 codec-dropped,
  one hardware decoder. The underlying codec is producing frames.
- Scene viewport output 2316x1080, 4 source windows, one
  input external texture, one GPU-rendered output.
- Repeated 5-second windows show 232–250 video frames latched
  (~46–50fps), but only 200–230 scene frames presented
  (~40–46fps). Source camera identities and layout geometry work.
- During 23:41:01–23:41:35 the **app UI** often delivers only
  10–43 frames per two seconds at 120Hz, with individual frames
  lasting 300–994ms and several frozen (>700ms) frames.
  GPU frame times recorded in PitwallFrames are usually <10ms,
  and the independent GL scene logs report low draw/swap times.
  This is an app-window/CPU load, allocation and presentation
  bottleneck, not proof of a HEVC decoder capability failure.
- Garbage collection repeatedly recovers tens of MB of allocations,
  with reported occupied heaps around 155–223MB and low free
  fractions. Full CMAF window publication and per-request
  fragment copies contribute avoidable large buffers.
- Repeated SurfaceTexture BufferQueue timeouts (often 21
  dequeued / 1 acquired) indicate decoder-to-GL buffering
  pressure. They do not alone prove codec frame drops.
- Source audio/video clock positions briefly drop by ~5760ms
  when a dynamic DASH window slides by a 5760ms segment.
  This is a change in **window-relative player position**,
  not by itself a real seek or lost content. The current
  synchronization code subtracts window.positionInFirstPeriodMs,
  so avoid changing the clock policy without stronger evidence.
- HLS composeMs from ~1.9–3.3 seconds per segment, with additional
  fetch time, establishes substantial background work. Surface
  isolation protects video presentation from that UI contention;
  more thorough capture memory profiling is still needed.

## Generic coordinated correction, not a Las Vegas workaround

1. Protected L3 rolling camera scenes previously used
   SingleDecoderSceneView : TextureView. A TextureView is composed
   into the app HWUI window, which Build 643 demonstrably updates
   irregularly. The video can therefore look much worse than the
   decoder/GL frame counters suggest.
2. Use the existing independently composed
   ProtectedDecoderSceneView : SurfaceView for **both** Widevine
   protection modes with explicit strict selection:
   - L1: secure SurfaceView, protected EGL context and window,
     GL_EXT_protected_textures required; never downgrade.
   - Authorized L3: ordinary SurfaceView/EGL, single external
     decoder texture, one hardware codec. No readback or DRM bypass.
   - Finite, clear archive scenes and Cast remain on their existing
     rendering paths; no breaking changes.
3. The live SurfaceFlinger layer is independent of app HWUI
   redraws. Tower/map remain visible in their existing UI layout.
   Add SURFACE_SCENE_EGL_READY and SURFACE_DECODER_SCENE pacing
   diagnostics including EGL swap and scene callback gaps.
4. Reduce encrypted CMAF reader allocation churn:
   use a lease-backed per-publication immutable resource generation
   rather than copying the entire fragment for each Media3
   DataSource read. Old generations are zeroed only when all
   in-flight readers close. Atomic window swap, bounds checks,
   encryption metadata and Widevine ownership are unchanged.
5. Add unit tests for concurrent resource leases across window
   retirement and zeroization, plus synthetic HEVC PixelCopy
   instrumentation for the nonsecure SurfaceView scene (requires
   a physical/appropriate emulator runner to execute).
6. Verify the absence of event-specific playback branches.
   Checked AppState, FeedPlayer, the F1 HLS/Archive capture classes,
   mixed atlas planner, HEVC composer, rolling DASH source, scene
   geometry and scene renderer. No Las Vegas/Sao Paulo conditional
   in these playback paths. The observed difference depends on
   representation geometry, encryption and runtime load.

## Acceptance still required

- One hardware HEVC decoder and valid Widevine entitlement;
  four full-quality source cameras with correct independent
  window positions and full controls.
- At least 50 input frames/second without an unbounded BufferQueue,
  and sustained smooth independent SurfaceView presentation under
  tower/map load.
- Compare source-specific 50fps performance with Sao Paulo using
  **matching** feed quality, duration and overlay settings.
  No current Sao Paulo log was attached to this test, so a
  quantified A/B comparison cannot be claimed.
- Verify L1 protected EGL, Android TV, Cast parity and
  hardware FrameTimeline/SF sign-off. Unit tests/CI are not
  substitutes for physical device validation.
