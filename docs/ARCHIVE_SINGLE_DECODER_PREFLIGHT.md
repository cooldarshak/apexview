# Archive composition preflight

Branch: `feature/hevc-stitching-poc`. Baseline: `d92bb405c07775bc2432431784231da0bad84eed` (CI #460).

Current native clear-HLS VOD delivery and the build 466 clock failure are documented in
[ARCHIVE_NATIVE_VOD_HANDOFF.md](ARCHIVE_NATIVE_VOD_HANDOFF.md). The separate-clock
rolling path below remains the existing DASH/protected backend.

## Confirmed blocker and scope

The supplied device evidence says `player_tm` feeds were HLS while WEB_DASH was selected. The old parser used each `tme.feeds[].url` verbatim and copied the response's global streamType to every channel. FeedPlayer accepted HLS, but archive capture rejected it **after** source video was disabled. Its error was retried until a generic 15-second timeout.

Build #463 phone log confirms the actual request was WEB_DASH/player_tm and every channel supplied one HLS URL. The response advertised SDR_HD_CMAF. Each of the three selected manifests was fetched and identified as HLS. No alternate feed URL field was advertised. The failure was the DASH-only acquisition assumption, not the user's stream-type setting.

Native `F1ArchiveFeedCapture` now routes those URLs through `F1HlsFeedCapture`. The original DASH parser retains its HLS rejection. No URL is renamed and no extra PLAY is issued.

## Implemented behavior

- Keep one existing authorised PLAY session. Inspect explicit URL offers within each channel only. Prefer an explicitly returned MPD offer on DASH platforms; preserve the selected offer's streamType and licence endpoint. Never construct URLs or reuse a different channel's URL.
- Log the requested platform, player, content/channel IDs, reported stream type, recognised variant field/protocol/type, selected field and fallback flag. Log response field names for unknown schemas. Never log URL values, headers or tokens.
- For capture, read a bounded manifest response, identify HLS from its body, and parse DASH using its final redirected base URI. URL suffixes are hints, not proof. HLS uses Media3 1.5.1 HlsPlaylistParser: select a common HEVC variant, resolve EXT-X-MAP and media/byte ranges, and align VOD segments with EXTINF/program-date-time. This is a separate acquisition path, not HLS passed into the DASH parser.
- Inspect the actual CMAF track before choosing clear or CBCS composition. Clear media uses the existing clear HEVC composer and emits no DRM signalling. Protected media retains CBCS metadata and the existing Widevine manager. AES-128 full-segment encryption is rejected without fetching a key; mixed protection is rejected.
- Cache finite HLS playlists and init segments; bound source/GOP and output windows, and zero discarded bytes. Support ordinary CMAF prefix boxes and sample defaults in tfhd/trex.
- Preserve a separate archive clock for the composed host. The normal follower loop must never seek the grid as if it were another original archive source. Pausing/speed changes continue across players. An explicit seek ends the test and restores normal playback at the requested position; invoke the test again there.
- Resolve the VOD Period containing the current archive position. Validate HEVC/CMAF, fragment alignment and composed DASH packaging before disabling any source decoder.
- Keep normal playback, audio and layout during preflight. Permanent source/format/protection failures fail immediately. Only network reads and HTTP 408/429/5xx are retryable. A timeout retains the last specific reason.
- Roll back failed or cancelled handoffs, including failure after the host media source is replaced. Restore original playback after composed Media3 errors or a missing first frame.
- Keep diagnostics in Layout after a failure. Decoder count is explicitly scoped to this app's Media3 initialisation/release callbacks. Hardware classification comes from Android codec metadata. These are not a system-wide codec census.

## Reference checked

`nicbra/f1tv-tizen`'s current `app/js/api.js` uses `player_bm`, validates returned URL and stream type, and issues separate requests for some platform/device-specific offers. That differs from ApexView's one-session `player_tm` contract. Those extra requests were not ported because their effect on the existing session has not been verified. No proprietary SDK or content decryption was added.

## Device acceptance remains open

Start a new archive playback session with two feeds, then choose Layout → Single-decoder test. Reopen Layout for persistent diagnostics. HLS/CMAF and DASH/CMAF are both eligible when their actual encoded media passes validation. Require actual video in every tile, one hardware video decoder and one surface, stable audio and replay timing before expanding from 2 to 3/4 feeds and Android TV. MPEG-TS/H.264, unsupported protection, incompatible HEVC and misaligned fragments fail preflight and preserve normal video.

For aShell on the same phone, capture locally without adb:

```sh
logcat -d -v threadtime -s ApexviewPlaybackOffer:I ApexviewSingleDecoder:I
```

Native HLS regression tests cover the WEB_DASH/player_tm response profile, 2/3/4-feed clear composition, contiguous segments, cached playlists, body detection, program-date-time rejection, byte ranges, and encryption rejection before decoder release. CI independently decodes the HLS-acquired composed output and compares every frame with the source hstack. These are synthetic fixtures, not device proof. Do not treat a green build, successful package creation, or target decoder count as proof of real-device composition.

## Build #465 phone milestone and subsequent buffering/layout fixes

The next uploaded phone log (named apexview-463-phone.log but containing native HLS capture events on 17 November) shows three source c2.qti.hevc.decoder instances released at 00:29:56, followed by one c2.qti.hevc.decoder instance at 00:29:57 for a 2880x536 composed representation. The recording shows all three real archive cells. That establishes a real phone decoding handoff during the active interval, not TV stability or smooth sustained playback. Another decoder appears during the subsequent exit/restore.

Two integration defects remained: the standard three-tile solver shrank the only visible composed host, and the 750 ms capture horizon was too short to hide network/composition work. The audible source kept advancing during video stalls, causing repeated hard resynchronisation seeks and codec flushes.

- The composed host alone participates in layout. With no reserved panels it gets the full canvas, using PlayerView FIT to preserve every cell. Reserved map/tower workspace remains respected. A horizontal three-feed stream naturally has a wider aspect ratio than the screen.
- HLS acquisition retains the current segment and prefetches up to two following segments. The producer stops before evicting the segment containing the audible archive clock.
- A media-to-archive timestamp mapping makes the first retained output fragment the playback origin, even when several fragments were prefetched or tfdt contains epoch-based timestamps.
- Preflight rejects already expired/future-only output. After a validated current picture takes over, startup holds the clock until actual queued data has three seconds of lead (or the archive ends sooner). This is a media readiness condition, not a timer.
- During real video rebuffering, the audible archive clock waits and resumes with the video. User play/pause stays authoritative. Diagnostics distinguish BUFFERING from ACTIVE and show prepared and player buffer durations.
- Per-batch logs report acquisition and composition times without URLs or tokens, so sustained producer throughput can be measured on phone and TV.
