# Build 629 Las Vegas protected HEVC failure

Observed on Samsung SM-S908E, Android 16, 21 Nov 2026, 22:14 IST.
Build 629; content 1000010439; four protected feeds; main channel-1033.

The earlier `sinf/tenc/pssh` init rewrite refusal is no longer reported.
`REAL_PROTECTED_SINGLE_SENC_PREFLIGHT` passed with 288 aligned pictures and zero
IV mismatches. At 22:14:11.748 capture rejected `cbcs-hevc-tile-composition`.
The first terminal reason at 22:14:11.753 was: `POC 0: feed 0
(channel-1033) selected source tile does not expose the clear CABAC prefix
required for rewriting`.

## Root cause

The protected composer required the same three clear CABAC bytes as the
all-clear HEVC rewriter. A valid CBCS subsample layout can expose a fully
clear, byte-aligned slice header and immediately protect the following
entropy-coded payload. The old writer used two CABAC bytes plus a third
lookahead byte for emulation-prevention escaping. Reading or re-encoding
protected CABAC is not permitted.

## Correction and invariants

The protected rewriter can now choose a header-only splice if fewer than
three CABAC bytes are clear. The source slice-header terminator and the
new aligned output header must both end in a nonzero clear byte. This
provides a closed emulation-prevention boundary; the original opaque EBSP
CABAC suffix can be transferred without examining, re-escaping, decrypting,
or mutating it. Recalculate CENC subsample ranges starting at the exact
first CABAC byte. Preserve all encrypted bytes and independent CBCS range
boundaries. Fail closed if the header or splice boundary cannot be proven.

The existing three-clear-byte algorithm is retained. No changes to F1
authorization, Widevine, license acquisition, decryption, per-sample IVs,
one-video-decoder requirement, or Cast protocol.

## Regression evidence and acceptance

New synthetic tile-feed regressions compare the opaque splice byte-for-byte
against the existing all-clear rewrite, exercise 0/1/2 clear CABAC bytes,
validate tile coverage, and confirm ciphertext-tail preservation. CI must
pass Kotlin tests, synthetic HEVC checks, Cast checks, APK assembly and native
RenderNode instrumentation. Green CI cannot establish Widevine hardware
success, real presentation cadence, TV capability, or overlay sign-off.

The supplied 22:14:29 Perfetto trace has uptime window
414066.196–414100.147 s. Build 629 log counted 496 UI frames,
239 slow frames, three frozen frames and a 1507ms maximum. Playback did
not enter a composed protected video steady state.

Hardware verification remains **PENDING**. If a future protected picture
does not satisfy the byte-aligned splice invariants, the pipeline must
refuse composition with a precise stage reason, not fall back to a second
video decoder or silently alter encrypted bytes.
