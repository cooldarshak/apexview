package app.apexview

/** Null uses the normal budget; otherwise only the composed host or named native preview may decode. */
internal fun singleDecoderVideoRole(mode: DecoderMode, feedCount: Int, isComposedHost: Boolean, browsing: Boolean): Boolean? =
    if (mode == DecoderMode.SINGLE && feedCount > 1) isComposedHost && !browsing else null

/** The composed source must use the selected feeds' actual F1 license endpoint. */
internal fun protectedFeedsShareLicense(licenses: List<String?>): Boolean =
    licenses.isNotEmpty() && !licenses.first().isNullOrBlank() && licenses.all { it == licenses.first() }

