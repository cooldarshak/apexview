# Build 919 / M51-M60 engineering checkpoint (28 Nov 2026)

Baseline: Build 919 green, cdd1c6d4ee47d8c6e492e7d2322ddf9f8abd7e7b. Branch feature/architecture-g-rust-stitching. Source checkpoints a051555b, d14a6395, 20555aa0 plus this commit. All are skip-ci. No new consolidated build requested while unconditional P0 correction remains unresolved. Do not label M51-M60 fully complete.

## Evidence
Physical S22 Ultra Android 16 build 1.0.1838, 4 clear HEVC feeds (1080p + 3x540p), experimental SurfaceView and independent texture latch. Peak PSS 687718 KiB (671.6 MiB), graphics 310208 KiB (302.9 MiB), composed Media3 84672512 bytes (80.75 MiB). One decoder texture, two EGL contexts, one window. Native 63 reuses /107 outputs, 15 fit / 14 pinned /15 disabled misses. Median initial GL output 7.88s, seek buffering 2.86s. At 1.5x submission remains near 49.19fps. Actual codec drops reported zero. Producer timeout strings include immediate nonblocking refusals; 21-23 dequeued, 0-1 acquired. Do not interpret these counts as measured physical queue bytes or dropped frames. No graphics owners after retirement; driver graphics allocation attribution remains unresolved. JVM usage after stop 123 MiB is not a proven leak. System and CPU trace cover only portions; native heap and Java graph absent.

## Implemented candidates and blockers
| ID | Source state | Acceptance / remaining |
|---|---|---|
| M51 P0 | Queue ownership analyzer, exact owner evidence, no speculative driver parameters | Vendor producer allocation/release evidence needed; eliminate actual recurrent stall, not merely hide diagnostics |
| M52 P0 | Graphics lifecycle evidence and queue correlation, existing retirement preserved | Establish specific graphics buffer owner and reduce 302.9 MiB peak without reducing image quality |
| M53 P0 | Keep qualified measured horizon when paused; soft 55 MiB gate above safe measured low watermark; no sample eviction, rebuffer/default behavior unchanged | Verify real allocations under 55 MiB without regressions; soft gate is not hard cap |
| M54 P1 | Split scrub hint from committed seek fence; one generation invalidation at actual target | Verify median <1s, stale output rejected |
| M55 P1 | Overlap identical clear-declared init map in selected playlist IO lanes; final validation unchanged | Verify startup <5s; cold proof mandatory |
| M56 P1 | Retire archive production binding and token at source/terminal release | Need actual Java graph to identify other retained objects |
| M57 P1 | Rust two-previous-size bounded reservation, pressure resets history. Ideal 80/107 to 88/107, same 9961472-byte peak | Verify physical reuse >85%; pinned and disabled misses still dependent on lifecycle/headroom |
| M58 P1 | Correlation owner/revision excludes mixed speed/display windows | Measure physical scanout and jank, no forced modes |
| M59 P1 | Named nonempty config staging and CLI status; corrected manual v2 scripts supplied separately | Simulated shell test all four exports passed; actual device producers still must be verified |
| M60 P2 | Same owner/revision gating for accelerated output, preserve 1x | No promise of 75fps on 60Hz or under thermal/decoder pressure; verify capable device after P0 |

## Validation
127 Python regression tests passed in exact-source scratch before checkpoint 3. Shell syntax passes. Manual v2 two-command simulated Linux shell test invoked fake Perfetto with four real nonempty named config files, empty query, detached sessions, stop, bundle and cleanup. This is not Android tracing proof. No Android/Rust compiler present in remote scratch. Need actual CI and physical A/B with identical source, settings, renderer, display and thermal state. Do not run heap profiling during latency acceptance test. Keep existing DRM, caption, quality, multi-decode, Cast routes; no release gate reduction. For M51/M52 use existing bufferqueue analyzer plus new ownership analyzer; compare no-surface and active samples and validate FrameTimeline. For M56 capture ART heap dominators, and native callsites for M57. Corrected two-command capture may report partial; collect bundle anyway and check start statuses and trace content. 
