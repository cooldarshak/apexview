package app.apexview.cast

import org.junit.Assert.*
import org.junit.Test

class CastOverlayDeliveryPolicyTest {
    @Test fun clearComposedPictureIsAlwaysBakedAndKeepsOverlays() {
        for (local in listOf(false, true)) for (registered in listOf(false, true)) {
            val route = CastOverlayDeliveryPolicy.choose(true, local, registered)
            assertEquals(CastOverlayDelivery.CLEAR_BAKED_PICTURE, route)
            assertTrue(route.fullOverlays)
            assertTrue(route.baked)
            assertFalse(CastOverlayDeliveryPolicy.requiresVideoOnlyConsent(route, true))
        }
    }

    @Test fun protectedNativeCastUsesReceiverSideOverlaysRatherThanUnsafeCapture() {
        val route = CastOverlayDeliveryPolicy.choose(false, true, false)
        assertEquals(CastOverlayDelivery.PITWALL_NATIVE_RECEIVER, route)
        assertTrue(route.fullOverlays)
        assertFalse(route.baked)
        assertFalse(CastOverlayDeliveryPolicy.requiresVideoOnlyConsent(route, true))
    }

    @Test fun bothGoogleReceiversNeedExplicitConsentBeforeDroppingVisibleOverlays() {
        for (registered in listOf(false, true)) {
            val route = CastOverlayDeliveryPolicy.choose(false, false, registered)
            assertEquals(if (registered) CastOverlayDelivery.GOOGLE_CUSTOM_VIDEO_LAYOUT
                else CastOverlayDelivery.GOOGLE_DEFAULT_VIDEO_GRID, route)
            assertFalse(route.fullOverlays)
            assertFalse(route.baked)
            assertTrue(CastOverlayDeliveryPolicy.requiresVideoOnlyConsent(route, true))
            assertFalse(CastOverlayDeliveryPolicy.requiresVideoOnlyConsent(route, false))
        }
    }
}
