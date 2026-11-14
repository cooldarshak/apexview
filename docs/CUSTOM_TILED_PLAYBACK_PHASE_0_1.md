# Custom tiled playback: Phase 0 and Phase 1 findings

**Date:** 2026-11-15  
**Repository:** `cooldarshak/apexview`  
**Working branch:** `customtiledview`  
**Application baseline inspected:** `73fee180797027154f48cbead03416ebc4682dd8`  
**Scope:** Repository/static architecture audit and public technical feasibility research. No playback code was rewritten in these phases.

## Executive finding

Apexview already asks F1 for one multiview play session, but it does **not** use one video decoder. It reads a collection of feed URLs from the multiview response and creates one `FeedPlayer` / `ExoPlayer` for each selected feed. Each of those players owns a video renderer and its own Widevine `DefaultDrmSessionManager`.

A genuine single-decoder solution is technically possible only when the incoming media is authored or packaged as one decodable video bitstream representing the tiled picture (or another source-provided combined representation). It cannot be achieved merely by placing four unrelated encrypted HLS/DASH streams inside one ExoPlayer. GPAC's published HEVC tile workflow demonstrates the important distinction: its HEVC tile aggregation rebuilds one HEVC bitstream for one decoder; its independent DASH SRD streams remain independent video objects.

**Phase 0:** Static code audit complete. Runtime performance baseline is pending device captures.  
**Phase 1:** Public feasibility research complete. F1 stream/manifest inspection is pending an authorised live session and sanitised manifest samples. The currently available source code alone cannot prove whether F1's multiview response contains a suitable combined/tile representation.

No Tiledmedia/TME SDK, library, source code, or proprietary implementation was used in this audit or added to the branch.

---

## Phase 0 — Current implementation audit

### Application and build baseline

| Area | Observed state |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Android Views for Media3 PlayerView surfaces |
| App module | Single Android application module |
| Android | minSdk 29, compileSdk 35, targetSdk 35 |
| Java / Kotlin target | Java 17 / JVM 17 |
| Media engine | AndroidX Media3 / ExoPlayer 1.5.1 |
| Streaming modules | Media3 DASH and HLS |
| DRM | Android Framework MediaDrm / Widevine |
| Maximum feed count | 4 (`AppState.MAX_FEEDS`) |
| Existing unit tests | 20 JVM test source files under `app/src/test` |
| CI | GitHub Actions workflow runs `testDebugUnitTest` and `assembleDebug`; it uploads a distributable APK only when all four persistent signing secrets are configured |

The Android debug workflow did not originally include this new branch in its push filter. The workflow filter has been extended to include `customtiledview`, so the report commit can exercise the existing test/build pipeline. No build result is claimed in this document; CI must finish and its individual steps/artifacts must be checked.

### Playback path found in the code

1. `F1TvApi.multiPlay()` calls F1's `CONTENT/PLAY` endpoint with `player=player_tm`.
2. It reads `resultObj.tme.feeds` and maps each feed's `metadata.channel_id` to that feed's URL.
3. `MultiPlay.stream(url)` creates a `PlayInfo` containing one URL plus the session's licence URL and stream type.
4. `AppState.addFeeds()` calls `newFeed()` for each chosen channel.
5. `newFeed()` constructs a new `FeedPlayer`.
6. Every `FeedPlayer` constructs its own `ExoPlayer`, media source factory, track selector, renderer factory, load control and `DefaultDrmSessionManager`.
7. `Viewer.kt` attaches a separate `PlayerView` to each tile.

This is one F1 multiview play request/session at the API layer, not one decoded video stream at the media layer. A shared play-session token/licence URL does not consolidate independent MediaCodec decoder instances.

### Existing optimisations (keep; do not mistake these for a single-decoder design)

- `AppState.applyVideoBudget()` disables video track selection for tiles that are hidden, occluded or outside the visible layout.
- Android TV queries AVC codec capabilities and reserves a decoder slot; the phone path currently returns a fixed budget of four rather than querying a real device-wide limit.
- Thermal state reduces requested tile resolution; at critical heat, non-main video can be held.
- Each tile has its own `TileFitSelectionFactory` to select a rendition appropriate to its displayed size and share of bandwidth.
- `FrameThinner` discards selected output frames at faster playback speeds. It reduces rendered frame work at higher speed; it does not merge streams or reduce the number of decoders needed at normal speed.
- The app keeps the current multi-session implementation as the working playback path and can fall back to individual `contentPlay` calls if the multiview request fails.

### Resource and rendering observations

| Resource | Current behaviour | Consequence for the new design |
|---|---|---|
| Video decoder | One ExoPlayer/video renderer per visible feed | Primary source of decoder-instance pressure |
| DRM manager | One manager is constructed inside each FeedPlayer | Extra per-feed DRM lifecycle; sharing a manager alone would not share video decoding |
| Video surface | One PlayerView per tile | One decoder's output is not automatically fanned out into several independent decoded surfaces |
| L1 / L3 surface choice | SurfaceView for non-software DRM; TextureView for software DRM | Protected GPU texture/crop rendering must be proved on target OEM hardware |
| Hidden feeds | Video track is disabled, but the player/session remains alive | Saves video work, but does not make the feed equivalent to a fully released session |
| Phone decoder budget | Fixed four-feed allowance | Can exceed actual device decoder capacity on some phones |
| TV decoder budget | Derived from codec capability information with conservative fallback | Useful guardrail, but advertised max instances are not a runtime guarantee |

### Baseline limitations

This was a static repository audit, not a runtime device benchmark. There was no connected Android phone or TV available through the repository tooling, and the protected F1 stream was not started during this audit. Therefore no CPU, GPU, memory, battery, temperature, startup latency, dropped-frame, MediaCodec allocation, or long-duration stability figures are reported.

The existing code and test suite were inspected, but the test suite has not yet been executed as part of this report. The branch push workflow is now configured to run it. Runtime Phase 0 still needs controlled captures from at least one lower-end Android phone and one Android TV device.

---

## Phase 1 — Single-decoder feasibility

### 1. What the F1 integration tells us — and what it does not

The current source confirms that Apexview receives a list of feed URLs from the F1 multiview response and then treats each URL as a separate media item. The source does **not** contain a captured response body, DASH MPD, HLS master/media playlist, initialization segment, media segment, codec configuration, sample encryption metadata, or timestamp trace.

The JSON property is currently named `tme`. That name is recorded only as an observation of the existing integration. Its ownership/meaning has not been independently established here. The new engine must not depend on TME/Tiledmedia code, SDKs, formats, metadata, or proprietary behaviour. Removing the legacy field dependency is a separate migration question because the repository currently uses it to obtain its multiview feed URLs.

Until the actual manifests are inspected, we cannot responsibly assert whether those feed URLs are:
- wholly independent camera streams;
- separate views into a shared encoded/tiled representation; or
- references to some other F1-authored combined delivery arrangement.

This is the most important outstanding evidence gap.

### 2. What a real one-decoder tiled stream requires

There are two technically distinct models:

**A. One pre-composed mosaic stream**

The source/packager combines the camera views into one encoded video frame (for example a 2×2 grid). Android decodes that one video stream once. A compositor displays the appropriate crop/region in each app tile. Audio selection can be handled separately.

This is the simplest conceptual model, but the composition must happen before the Android decoder. If Apexview first downloads four separate Widevine-protected feeds, it would need to decode all four before it could compose them, which defeats the single-decoder goal. The client cannot turn four encrypted camera streams into one pre-encoded mosaic for free.

**B. One HEVC bitstream made from motion-constrained tiles**

The source must be encoded and packaged in a compatible tiled HEVC format. Tile data is assembled into one standards-compliant HEVC bitstream, then sent to one decoder. The application/compositor can use the decoded picture's regions as its viewports.

This is not the same as taking arbitrary independent camera feeds and labelling them as HEVC tiles. The source authoring/packaging has to create a compatible common bitstream, with the necessary tile boundaries, codec configuration and timing alignment.

### 3. Independent public proof-of-concept reference: GPAC

GPAC is an independent open-source multimedia framework. Its public documentation provides a useful, non-TME technical reference for the distinction we need to implement:

- GPAC's HEVC tile guide describes motion-constrained HEVC tile encoding, tile packaging and DASH adaptation. Its playback pipeline aggregates the tile media into a **single HEVC bitstream fed to the decoder**. <https://github.com/gpac/gpac/wiki/HEVC-Tile-based-adaptation-guide>
- GPAC's DASH SRD / HEVC comparison distinguishes independently encoded streams from motion-constrained HEVC tiles. The latter can be reassembled into a conforming HEVC bitstream and use one decoder. <https://github.com/gpac/gpac/wiki/MPEG-DASH-SRD-and-HEVC-tiling-for-VR-videos>
- GPAC's own licensing page describes its dual licensing model and LGPL obligations. GPAC is being used here as a research reference only; it has **not** been added as an app dependency. <https://gpac.io/licensing/>

This validates the underlying one-decoder media concept. It does not prove that F1's current feed delivery is compatible with that concept, and it does not mean GPAC's implementation should be copied into Apexview.

### 4. Android and DRM constraints

- MediaCodec is configured with an output surface. Its `setOutputSurface()` API changes that output surface; it is not an API for duplicating one decoder's output to four independent surfaces. <https://developer.android.com/reference/android/media/MediaCodec#setOutputSurface(android.view.Surface)>
- Android/Media3 recommends SurfaceView where possible, including for secure DRM output. TextureView is not an automatic substitute for a protected L1 video surface. <https://developer.android.com/media/media3/ui/surface>
- A protected GPU texture path is possible only where the platform and device implementation support the protected-content EGL/GL capabilities. AOSP documents the protected-surface mechanism and also makes clear that the required driver support is up to device manufacturers. <https://source.android.com/docs/core/graphics/arch-st>
- Media3 supports Widevine-protected DASH and fMP4 HLS. It also permits a custom DRM session manager to be shared by multiple players. That is a DRM lifecycle optimisation, not a way to merge multiple video decoders into one. <https://developer.android.com/media/media3/exoplayer/drm>

Therefore the intended design needs two proofs, not one: (1) the source must be a genuine single decodable tiled/composite video representation; and (2) the decoded output must be cropped/composited in a way that still works with F1's required Widevine security level on actual Android devices.

### 5. F1 platform constraint

F1's current supported-device help page states that Multiview is supported on Android 14+ phones/tablets with HEVC capability, while its listed Android TV devices do not support Multiview. This is a limitation of F1's official supported-device matrix, not a limitation on what a third-party Android app could attempt. It does mean we must test Android TV independently and must not assume F1's official TV app provides a supported multiview path. <https://f1help.formula1.com/article/Supported-devices>

### 6. Practical trade-offs to test

A 2×2 mosaic at 1920×1080 provides only 960×540 pixels per view. To preserve four 1920×1080 views at native tile resolution, the combined picture would be 3840×2160. A single 4K decoder may still be too demanding for lower-end hardware, so "one decoder" by itself is not a performance guarantee. Resolution, frame rate, codec profile/level, bit depth, memory bandwidth, GPU crop cost and thermal behaviour all need measurement.

The source must also align the camera frames to a common media timeline. We need to inspect segment boundaries, frame timestamps, keyframe cadence, live-edge behaviour and audio tracks before choosing a synchronisation model.

---

## Phase 1 verdict

**Feasible in principle; not yet proven feasible with F1's current authorised feed delivery.**

Apexview cannot get a genuine one-decoder result by simply replacing four ExoPlayer objects with one ExoPlayer while continuing to feed it four independent media URLs. The required input is a single composite/tiled video representation that the decoder can consume as one bitstream.

The next decisive test is a non-invasive inspection of one authorised F1 multiview session:
1. Capture a sanitised `player_tm` response shape (remove bearer tokens, entitlement tokens, cookies, signed URL query values and personal/account identifiers).
2. Inspect the feed manifests and a small set of init/media segment metadata locally, without decrypting or bypassing DRM.
3. Determine whether there is a common base/tile representation, shared timing/codec configuration and a supported DRM model.
4. If no combined/tile representation exists, record that a server-authored F1 combined rendition (or a separately authorised upstream packager) is a prerequisite. Do not attempt client-side re-encoding of protected feeds.

No F1 credentials or secrets should be committed to GitHub or included in diagnostics.

---

## Decisions and guardrails for the next phase

1. Keep the current ExoPlayer path intact as the fallback while a separate custom engine is prototyped.
2. Do not integrate any Tiledmedia/TME library, SDK, code, metadata contract or proprietary implementation.
3. Do not claim "single decoder" unless runtime instrumentation proves exactly one video decoder instance is active for the tiled session.
4. Do not treat a shared F1 play session, shared DRM manager, shared bandwidth meter, or a shared UI surface as proof of one decoder.
5. Prove protected rendering before designing around TextureView/OpenGL cropping.
6. Measure a 2×2 target at 540p-per-view and 1080p-per-view-equivalent to quantify the decoder/GPU trade-off.
7. Preserve all existing controls, layouts, map, timing tower, D-pad behaviour, audio selection, playback speed, subtitles and picture-in-picture.

## Evidence references

- Apexview: `F1TvApi.kt` — `PlayInfo`, `MultiPlay`, `multiPlay()` and `CONTENT/PLAY` request.
- Apexview: `AppState.kt` — `addFeeds()`, `newFeed()`, `makeFeed()`, `streamFor()`, `applyVideoBudget()`, `videoDecoderBudget()` and `MAX_FEEDS`.
- Apexview: `FeedPlayer.kt` — per-feed DRM manager, ExoPlayer, renderers, load control and video-track enable/disable.
- Apexview: `Viewer.kt` and `player_surface.xml` / `player_texture.xml` — per-tile PlayerView and secure/software surface selection.
- Apexview: `TileFit.kt` and `FrameThinning.kt` — current per-feed rendition selection and frame thinning.
- F1 Help: <https://f1help.formula1.com/article/Supported-devices>
- Android MediaCodec: <https://developer.android.com/reference/android/media/MediaCodec#setOutputSurface(android.view.Surface)>
- Android Media3 DRM: <https://developer.android.com/media/media3/exoplayer/drm>
- Android Media3 surface types: <https://developer.android.com/media/media3/ui/surface>
- AOSP protected SurfaceTexture: <https://source.android.com/docs/core/graphics/arch-st>
- GPAC HEVC tiling: <https://github.com/gpac/gpac/wiki/HEVC-Tile-based-adaptation-guide>
- GPAC SRD vs HEVC tile comparison: <https://github.com/gpac/gpac/wiki/MPEG-DASH-SRD-and-HEVC-tiling-for-VR-videos>
- GPAC licensing: <https://gpac.io/licensing/>
