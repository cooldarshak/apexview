# Archive composition preflight

Branch: `feature/hevc-stitching-poc`. Baseline: `d92bb405c07775bc2432431784231da0bad84eed` (CI #460).

## Confirmed blocker and scope

The supplied device evidence says `player_tm` feeds were HLS while WEB_DASH was selected. The old parser used each `tme.feeds[].url` verbatim and copied the response's global streamType to every channel. FeedPlayer accepted HLS, but archive capture rejected it **after** source video was disabled. Its error was retried until a generic 15-second timeout.

No authenticated PLAY response from that device is available in this checkout. Consequently, this change does not assert that F1 returned an unused DASH variant, that a particular undocumented response field exists, or that a new PLAY would preserve the session.

## Implemented behavior

- Keep one existing authorised PLAY session. Inspect explicit URL offers within each channel only. Prefer an explicitly returned MPD offer on DASH platforms; preserve the selected offer's streamType and licence endpoint. Never construct URLs or reuse a different channel's URL.
- Log the requested platform, player, content/channel IDs, reported stream type, recognised variant field/protocol/type, selected field and fallback flag. Log response field names for unknown schemas. Never log URL values, headers or tokens.
- For capture, read a bounded manifest response, identify HLS from its body, and parse DASH using its final redirected base URI. URL suffixes are hints, not proof. HLS remains unsupported by this DASH capture path.
- Resolve the VOD Period containing the current archive position. Validate HEVC/CMAF, fragment alignment and composed DASH packaging before disabling any source decoder.
- Keep normal playback, audio and layout during preflight. Permanent source/format/protection failures fail immediately. Only network reads and HTTP 408/429/5xx are retryable. A timeout retains the last specific reason.
- Roll back failed or cancelled handoffs, including failure after the host media source is replaced. Restore original playback after composed Media3 errors or a missing first frame.
- Keep diagnostics in Layout after a failure. Decoder count is explicitly scoped to this app's Media3 initialisation/release callbacks. Hardware classification comes from Android codec metadata. These are not a system-wide codec census.

## Reference checked

`nicbra/f1tv-tizen`'s current `app/js/api.js` uses `player_bm`, validates returned URL and stream type, and issues separate requests for some platform/device-specific offers. That differs from ApexView's one-session `player_tm` contract. Those extra requests were not ported because their effect on the existing session has not been verified. No proprietary SDK or content decryption was added.

## Device acceptance remains open

Start a new archive playback session with two feeds, then choose Layout → Single-decoder test. Reopen Layout for persistent diagnostics. If the authorised session supplies HLS only, the test should explain that DASH is unavailable and both normal feeds should remain visible. If compatible DASH is supplied, require actual video in both tiles, one hardware video decoder, one surface, stable audio and working replay controls before expanding to 3/4 feeds and Android TV.

For aShell on the same phone, capture locally without adb:

```sh
logcat -d -v threadtime -s ApexviewPlaybackOffer:I ApexviewSingleDecoder:I
```

The next source-acquisition decision depends on those sanitised diagnostics. Do not treat a green build, successful package creation, or target decoder count as proof of real-device composition.
