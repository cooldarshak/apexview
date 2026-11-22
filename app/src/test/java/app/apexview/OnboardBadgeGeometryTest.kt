package app.apexview

import org.junit.Assert.*
import org.junit.Test

class OnboardBadgeGeometryTest {
    @Test fun rightAnchorAndOffsetsStayWithinFeed() {
        assertEquals(4f to 4f, OnboardBadgeGeometry.position(
            334f, 188f, 110f, 24f, OnboardBadgeAnchor.TOP_LEFT, 0f, 0f))
        assertEquals(220f to 4f, OnboardBadgeGeometry.position(
            334f, 188f, 110f, 24f, OnboardBadgeAnchor.TOP_RIGHT, 0f, 0f))
        assertEquals(224f to 164f, OnboardBadgeGeometry.position(
            334f, 188f, 110f, 24f, OnboardBadgeAnchor.TOP_RIGHT, 500f, 500f))
        assertEquals(0f to 0f, OnboardBadgeGeometry.position(
            334f, 188f, 110f, 24f, OnboardBadgeAnchor.TOP_LEFT, -500f, -500f))
    }

    @Test fun narrowWindowCannotPushBadgeOutsideItsBounds() {
        val point = OnboardBadgeGeometry.position(
            90f, 35f, 100f, 40f, OnboardBadgeAnchor.TOP_RIGHT, 20f, -20f)
        assertEquals(0f to 0f, point)
    }

    @Test fun invalidPrefsUseSafeDefaultsAndOpacityDoesNotControlVideo() {
        assertEquals(0f, OnboardBadgeGeometry.offset(Float.NaN))
        assertEquals(OnboardBadgeGeometry.OFFSET_LIMIT_DP,
            OnboardBadgeGeometry.offset(100000f), 0f)
        assertEquals(0.20f, OnboardBadgeGeometry.opacity(-1f), 0f)
        assertEquals(OnboardBadgeGeometry.DEFAULT_OPACITY,
            OnboardBadgeGeometry.opacity(Float.POSITIVE_INFINITY), 0f)
    }
}
