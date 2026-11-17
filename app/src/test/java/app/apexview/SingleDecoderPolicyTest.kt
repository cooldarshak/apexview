package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingleDecoderPolicyTest {
    @Test fun sourcePlayersCannotDecodeWhileSingleDecoderIsStartingOrHasFailed() {
        for (count in listOf(2, 4, 24)) {
            val enabled = (0 until count).count {
                singleDecoderVideoRole(DecoderMode.SINGLE, count, isComposedHost = false, browsing = false) == true
            }
            assertEquals(0, enabled)
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
}
