package app.apexview

/** Null delegates to the normal multi-player budget; otherwise only the composed host may decode. */
internal fun singleDecoderVideoRole(mode: DecoderMode, feedCount: Int, isComposedHost: Boolean, browsing: Boolean): Boolean? =
    if (mode == DecoderMode.SINGLE && feedCount > 1) isComposedHost && !browsing else null

