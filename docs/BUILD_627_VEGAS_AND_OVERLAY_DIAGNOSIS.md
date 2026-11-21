# Build 627: Vegas protected-init failure and overlays

Source: S22 Ultra Build 627 log, November 21 2026. Baseline commit 26ce0c8e9caeca3d97de1e817d77f742c5feff90.

## Confirmed

- Vegas content 1000010439 switches to four protected CBCS HLS HEVC feeds at 21:11:28.
- All four 288-sample windows pass HEV1 parameter recovery and 3-epoch POC alignment.
- `REAL_PROTECTED_SINGLE_SENC_PREFLIGHT` reports 288 aligned pictures, 0 IV mismatches, matching CBCS defaults and key identities.
- At 21:11:34.898, `cbcs-hevc-tile-composition` fails before protected decoder start: `sinf, tenc or pssh signalling changed during protected init rewrite`.
- Production source compares separate `List<ByteArray>` objects by `==`, which compares each ByteArray by reference identity. This necessarily fails for nonempty identical source and result protection boxes.
- Correction compares each extracted box's raw bytes via `contentEquals` and keeps strict count/order/integrity checks; no DRM check is disabled.

## Overlay CPU evidence

| Owner | Samples | Weighted mean | Maximum |
|---|---:|---:|---:|
| Map car/flag draw | 5,406 | 1.910 ms | 27.582 ms |
| Track draw position | 4,676 | 0.241 ms | 20.322 ms |
| Map motion clock | 6,260 | 0.450 ms | 64.129 ms |
| Tower sampler | 172 | 2.795 ms | 33.638 ms |
| Tower row draw | 2,107 | 0.430 ms | 14.882 ms |
| Track event display list | 1,847 | 0.036 ms | 1.158 ms |

All instrumented overlay seek-reset counts in the reporting windows were zero. Track event retained-list changed only twice across 1,847 samples.
85 app UI windows report 9,236 frames, of which 3,706 were slow and 4 were frozen >700ms. These include startup and paused playback; they are **not** a comparable stable-video cadence statistic.
Sampled VOD segments allocated about 49-184 MiB each, and SurfaceTexture timeout reports recur. Neither is proven caused by the map or tower.

## Sign-off

Map/tower: NOT SIGNED OFF. Protected one-decoder: NOT SIGNED OFF. Source-level first-failure gate corrected, but real secure decode, key provisioning, receiver Cast, GPU/FrameTimeline, device UI and physically presented 50fps remain to be verified separately. Do not weaken tests or tell user to test an intermediate build.

## Three Perfetto FrameTimeline traces, correlated to app uptime

The three raw files were decoded from their Perfetto protobuf FrameTimelineEvent
packets. Start/end are computed from the actual packet timestamps, converted
using the application's monotonic uptime mapping, not just upload filenames.

| Trace filename suffix | Local time IST | What is captured |
|---|---|---|
| 21-09-51 | 21:09:18.3–21:09:56.4 | Four-feed clear archive video, map and tower |
| 21-11-06 | 21:10:09.3–21:11:11.6 | Pause/resume and archive playback |
| 21-11-51 | 21:11:26.2–21:11:56.9 | Protected Vegas initialization and refusal |

For the Pitwall application's \`ActualSurfaceFrameStart\` FrameTimeline records:

| Trace | Pitwall app frames | PRESENT_LATE | App deadline missed bit | Buffer stuffing bit |
|---|---:|---:|---:|---:|
| 21-09-51 | 2,283 | 2,261 (99.0%) | 892 | 1,802 |
| 21-11-06 | 3,537 | 3,297 (93.2%) | 1,433 | 1,974 |
| 21-11-51 | 483 | 480 (99.4%) | 206 | 398 |

The two jank-bit columns overlap; they are not mutually exclusive.
These are **application surface** timing records, not independently measured
video-decoder physical presents. Do not report them as 50fps dropped-video
percentages. The 120Hz display, pause, transitions and preflight are
confounders; a controlled steady-playback surface-present analysis is still
needed for acceptance. The high BufferQueue stuffing flag rate is real
independent evidence of presentation pressure and is not an overlay-specific
causal attribution. Native Overlay RenderNode tests and low average CPU
work do not overturn this failing/uncertain physical presentation evidence.

### CI test expectation correction

An intentional mutation to a \`tenc\` box also changes its parent \`sinf\`
byte sequence, which is inspected earlier. Assert that the enclosing \`sinf\`
comparison catches it. Preserve the independent source parsing and CBCS
defaults consistency verification. Do not weaken either gate.
