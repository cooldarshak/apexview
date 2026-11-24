# WP6: Android Media3 path for composed HEVC CMAF

## What is implemented

The Android port follows the useful part of the independent F1 Tizen design: publish one composed DASH presentation rather than asking the player to open each source feed as a separate video player.

- `HevcComposedDashPackageBuilder` validates the composed init segment and every output fragment.
- It requires exactly one clear HEVC video track, complete sample timing and contiguous presentation intervals.
- It creates a static DASH MPD with one AdaptationSet, one Representation, one initialization resource and one URL per composed media fragment.
- `HevcComposedPlayback` serves those resources from memory through Media3's `DashMediaSource` and owns exactly one `ExoPlayer`.
- The existing `FeedPlayer` graph is unchanged and remains the fallback. The composed playback class never creates source-feed players.

This is the Android/Media3 equivalent of Tizen's one-grid-manifest/one-native-player route. The existing Kotlin Tizen algorithm and clear-CMAF composer remain the bitstream and fragment producers.

## DRM boundary

The current Kotlin CMAF composer is clear-only and rejects encrypted source tracks and encryption metadata. This package builder repeats that fail-closed check. It does not add a fake `ContentProtection` element or attach a Widevine licence to clear bytes.

The Tizen implementation has a protected CBCS route that preserves encrypted payload bytes and rebuilds sample-encryption auxiliary metadata. That protected packaging work is not yet ported into the Kotlin composer. Do not use this Media3 adapter for protected F1 feeds. Widevine must remain the only decryption authority when a protected packaging path is implemented and verified. No application code may decrypt samples or handle keys.

## CI validation

The JVM CMAF composer test now also builds and checks the single-representation MPD. It writes:

- `hevc_tizen_cmaf_grid.mp4`
- `hevc_tizen_cmaf_grid.mpd`

The Android instrumentation asset-staging step copies the generated MP4 into the `androidTest` asset directory. The new `HevcComposedPlaybackInstrumentationTest` then opens it through Media3 DASH, assigns one ImageReader output Surface, and records:

- video decoder initializations and maximum concurrent video decoders
- hardware codec name
- rendered frames and output dimensions
- frame-surface timestamp cadence
- dropped decoder buffers and playback errors

A passing device test writes the log marker `MEDIA3_COMPOSED_CMAF_PASS`.

The GitHub Actions workflow compiles and packages the instrumentation APK. It does not execute instrumentation against physical hardware.

## Physical-device test

Install the debug app and the matching instrumentation APK from the same CI run, then run:

```bash
adb install -r app-debug.apk
adb install -r app-debug-androidTest.apk
adb shell am instrument -w app.apexview_custom.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s ApexviewHevcMedia3:I
```

The relevant log must include `MEDIA3_COMPOSED_CMAF_PASS`, a hardware decoder name, one decoder initialization, a maximum of one active video decoder, one output surface, all source samples rendered, and the measured frame cadence.

## Remaining gates

This closes only the Media3 integration implementation and its synthetic device-test harness. It does not close WP6's physical-device acceptance until the new instrumentation test passes on the TCL Android TV (and preferably a second device). It also does not prove that the current F1 feeds are composable or that their protected CBCS packaging is compatible with Android Media3/Widevine.

The next F1-specific gate remains the authorised source-feed/DRM packaging investigation. The new path must not obtain feed URLs from Tiledmedia/TME or the Tizen server, and the protected stream path must not be enabled until the Kotlin implementation can safely preserve encryption metadata while leaving every encrypted payload byte unchanged.
