package app.apexview

/** Read-only audit. The configured adaptive envelope is not a measured
 * allocation and cannot be shrunk without validating all negotiated formats. */
internal object CodecEnvelopeAudit {
    fun classify(codedWidth: Int, codedHeight: Int, maxWidth: Int, maxHeight: Int): String {
        if (minOf(codedWidth, codedHeight, maxWidth, maxHeight) <= 0) return "UNREPORTED"
        if (maxWidth < codedWidth || maxHeight < codedHeight) return "BELOW_CODED"
        return if (maxWidth == codedWidth && maxHeight == codedHeight) "EXACT_CODED"
            else "LARGER_THAN_CODED"
    }
}
