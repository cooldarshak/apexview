package app.apexview.cast

/**
 * Capability contract for single-decoder Cast. A video atlas renderer
 * must never be reported as a complete native timing overlay receiver.
 *
 * Protected video cannot be copied out of a secure surface to fake baked
 * playback. Native Pitwall TV obtains its own authorized Widevine license.
 */
internal enum class CastOverlayDelivery(val fullOverlays: Boolean, val baked: Boolean) {
    CLEAR_BAKED_PICTURE(true, true),
    PITWALL_NATIVE_RECEIVER(true, false),
    GOOGLE_CUSTOM_VIDEO_LAYOUT(false, false),
    GOOGLE_DEFAULT_VIDEO_GRID(false, false),
}

internal object CastOverlayDeliveryPolicy {
    fun choose(
        verifiedClearComposedVideo: Boolean,
        localPitwallReceiver: Boolean,
        registeredGoogleLayoutReceiver: Boolean,
    ): CastOverlayDelivery = when {
        verifiedClearComposedVideo -> CastOverlayDelivery.CLEAR_BAKED_PICTURE
        localPitwallReceiver -> CastOverlayDelivery.PITWALL_NATIVE_RECEIVER
        registeredGoogleLayoutReceiver -> CastOverlayDelivery.GOOGLE_CUSTOM_VIDEO_LAYOUT
        else -> CastOverlayDelivery.GOOGLE_DEFAULT_VIDEO_GRID
    }

    fun requiresVideoOnlyConsent(route: CastOverlayDelivery, mapOrTowerRequested: Boolean): Boolean =
        mapOrTowerRequested && !route.fullOverlays
}
