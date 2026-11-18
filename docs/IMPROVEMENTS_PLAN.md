# ApexView performance and UI audit plan

Baseline: `improvements` at `17a8d32`, the commit tested by [build #527](https://github.com/cooldarshak/apexview/actions/runs/44283823129). The run passed unit tests, synthetic HEVC checks and APK assembly. Validate protected playback and real performance on the Samsung Galaxy S22 Ultra and TCL 55C655.

## Reference comparison

| Reference | ApexView finding and plan |
| --- | --- |
| [Androidify](https://github.com/android/androidify) and the [Expressive playground](https://github.com/myofficework000/Material3Expressive) | The app has a shared dark Material 3 theme and custom focus effects. Prototype one phone browse-to-play flow with clearer card hierarchy, shape and motion. Verify the selected APIs against the [Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3) before upgrading. |
| [Breezy Weather](https://github.com/breezy-weather/breezy-weather) | The tower already exposes detailed timing. Prioritize position, driver, gap or interval, tyre and pit status. Provide sectors, speed, gear and weather on demand. Preserve Track, Edge and Pit map modes. |
| [Rhythm](https://github.com/cromaguy/Rhythm) and [Compose samples](https://github.com/android/compose-samples) | Home already has a hero and Continue Watching. Move resume and the latest weekend up. Use JetNews for list-to-detail restoration and Jetchat as a secondary input reference if catalog search is added. Make Archive a direct route to recent seasons and older eras, with Popular Races as its own shelf. |
| [Material Theme Builder](https://github.com/material-foundation/material-theme-builder) | Define shared Pitwall surface, text, action and focus colors. Check contrast over artwork and video. Retain the meaning of team, flag, tyre and sector colors. |
| [Now in Android](https://github.com/android/nowinandroid) | AppState coordinates Browse, Playback, Replay and Cast. Narrow state ownership incrementally where traces show unrelated UI work. |
| [Android Performance Samples](https://github.com/android/performance-samples) and Perfetto | Existing diagnostics cover UI frame timing, first video frame, decoder counts and drops. Release uses R8 and resource shrinking. Add repeatable Macrobenchmark flows and profile traces, then measure a Baseline Profile on a release-like build. |
| [Android TV samples](https://github.com/android/tv-samples) and [Roborazzi](https://github.com/takahirom/roborazzi) | Existing remote tests cover seek, sliders, header access and map editing. Use TvMaterialCatalog for focus treatments and JetStreamCompose for a full Browse → Play → Back flow. Add screenshot checks for phone portrait, landscape, large text and focused TV states. |

## Work order and acceptance

1. **First implementation priority — Encode the finished Cast scene.** Implement the sender-side render and encode path described below. Complete the end-to-end clear-source milestone before starting the broader replay or UI work.
2. **P0 — Baseline and CI.** Batch all P0 code changes before manually dispatching one Android debug APK build for `improvements`. Compare cold and warm runs using the same archive session, timestamp, quality and device settings. Record median and slow-run startup, first video frame, usable tower, first moving marker, seek recovery, UI frame timing, video drops and peak memory. Preserve the existing signing path for in-place updates.
3. **P0 — TCL replay overlays.** Compare main only, main + tower, main + map and main + both. Replay already uses background loading, partial results and indexed caches. The map caches its path and publishes slower on TV. Trace loading separately from rendering. Improve the first usable snapshot if parsing dominates. Isolate map marker updates and publish tower changes at the displayed-row level if rendering dominates. Show the actual loading stage. Pass when both overlays load and stay synchronized through a seek on the TCL, with a measured improvement and a phone regression check.
4. **P0 — Protected single-decoder playback.** Start on the TCL with its working Software DRM setting. Test one, two, then four feeds. Capture source and composed resolution, frame rate, Widevine setup, active video decoder count, first frame and seek recovery. Require sustained synchronized two-feed playback and a successful seek with one video decoder before extending the device test to four.
5. **P0 — Native Pitwall receiver.** Diagnose the reported preparation failure on the TCL and validate independently authorized protected playback and full-scene rendering.
6. **P1 — Browse and player UX.** Put Live Now first when available, followed by Continue Watching and Latest Weekend. Promote Archive. Profile eager 1920×1080 hero prefetch against display-sized visible and adjacent artwork. Preserve stable item IDs and scroll position. Keep tap-to-toggle controls on phone and stack video, tower and map in portrait. On TV, keep the large left video preset with right-side tower and map, visible blue focus, move/resize/lock/keep-clear controls and D-pad access to playback, quality and layout. Back must restore the originating card or control and its scroll position.
7. **P2 — Regression coverage.** Measure a Baseline Profile for launch, Browse, Archive and player entry. Add narrow, large-text and TV focus screenshots. Verify login, resume, picture-in-picture, foreground recovery and in-place APK updates alongside performance work.

## First milestone: Cast the finished scene as one video

Render the final viewing scene on the sender into an encoder input surface. Start with four capture-permitted feeds, one large on the left and three stacked on the right. Composite the timing tower and moving track map into the same frames. Use one hardware encoder and publish one Cast-compatible video track with the selected audio on the same timeline. If the sender already has a packed single-decoder stream, reuse its decoded regions as inputs. The standard receiver plays the resulting video with one video decoder and needs no layout or overlay messages to reproduce the picture.

Validate the finished scene with actual capture-permitted playback on the target devices. Wire continuous encoding to the normal Cast flow, including changes to the layout, pause/resume, seek and reconnect. Verify on the S22 Ultra and TCL using the standard receiver. Record first frame, end-to-end latency, drops, audio sync, sender thermal load and receiver decoder count where observable. Require the four arranged feeds, tower and moving map to appear in the received video throughout the test.

Limit sender-side encoding to clear or explicitly capture-permitted sources. For protected F1 content that requires a secure output, use the native Pitwall receiver with its own entitlement and Widevine session. Verify that path independently on the TCL.

## Other casting paths

**One video decoder and visual parity are two requirements.** In the packed single-decoder path, each encoded HEVC frame contains several feed regions. The phone selects those regions and draws them in the chosen scene, such as one large main feed on the left and three smaller feeds stacked on the right. Timing and map are separate application layers. A default Google Cast receiver displays the packed frame directly, so side-by-side feeds can still come from one stream decoded once.

| Path | Plan and expected behavior |
| --- | --- |
| Google Cast default receiver | Document the packed-grid behavior and make it clear in the chooser when full-scene layout is unavailable. |
| Google Cast custom web receiver | Validate video placement on a registered, compatible receiver. Treat timing and map support as a separate implementation and verification item. |
| Native Pitwall TV receiver | Use this path for protected full-scene playback when sender capture is unavailable. Send the packed stream with feed geometry, layer order, timing and map state. Decode video once and render the scene locally. For protected content, use receiver-side entitlement, Widevine and secure output. The reported preparation failure and TCL rendering still require device evidence. |
| System mirroring | Test what the TV actually receives, including how the OS handles protected video. |

Use the same four-feed scene on each applicable path. Verify feed identity, main-left and three-right placement, overlay positions and visibility, selected audio, source quality, audio/video/timing synchronization, and the active video decoder count where the receiver exposes it. Exercise layout changes, pause/resume, seek and reconnect. A full-scene pass requires the receiving screen to reproduce the chosen viewing scene, adapted to its aspect ratio, with four feeds, tower and map visible and synchronized. Record preparation, first frame and overlay readiness separately so a stall can be diagnosed.

See [single-decoder layouts and casting](SINGLE_DECODER_LAYOUTS_AND_CAST.md) and [the native receiver](LOCAL_TV_RECEIVER.md) for implementation details.
