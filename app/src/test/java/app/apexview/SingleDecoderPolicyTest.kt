package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingleDecoderPolicyTest {
    @Test fun noSourcePlayerMayDecodeWithoutAnExplicitCompositionOrFallbackOwner() {
        for (count in listOf(2, 4, 24)) {
            val enabled = (0 until count).count {
                singleDecoderVideoRole(DecoderMode.SINGLE, count, isComposedHost = false, browsing = false) == true
            }
            assertEquals(0, enabled)
        }
    }

    @Test fun aNativeFallbackOwnerStillRespectsTheOneDecoderLimit() {
        for (count in listOf(2, 4, 24)) {
            val preview = count - 1
            assertEquals(1, (0 until count).count { index ->
                singleDecoderVideoRole(DecoderMode.SINGLE, count, isComposedHost = index == preview, browsing = false) == true
            })
        }
    }

    @Test fun onlyTheComposedHostCanDecodeAndBrowsingSuspendsIt() {
        for (count in listOf(2, 4, 24)) {
            assertEquals(1, (0 until count).count {
                singleDecoderVideoRole(DecoderMode.SINGLE, count, isComposedHost = it == 0, browsing = false) == true
            })
            assertEquals(false, singleDecoderVideoRole(DecoderMode.SINGLE, count, isComposedHost = true, browsing = true))
        }
    }

    @Test fun multipleDecoderAndSingleFeedKeepTheNormalBudget() {
        assertNull(singleDecoderVideoRole(DecoderMode.MULTIPLE, 4, isComposedHost = false, browsing = false))
        assertNull(singleDecoderVideoRole(DecoderMode.SINGLE, 1, isComposedHost = false, browsing = false))
    }

    @Test fun protectedFeedsMustShareTheRealLicenseBeforeTheComposedPlayerRequestsKeys() {
        assertEquals(true, protectedFeedsShareLicense(listOf("https://license.test", "https://license.test")))
        assertEquals(false, protectedFeedsShareLicense(listOf("https://license.test", "https://other.test")))
        assertEquals(false, protectedFeedsShareLicense(listOf(null, null)))
        assertEquals(false, protectedFeedsShareLicense(emptyList()))
    }
}
