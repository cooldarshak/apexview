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
