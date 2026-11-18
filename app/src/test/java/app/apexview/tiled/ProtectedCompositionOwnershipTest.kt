package app.apexview.tiled

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ProtectedCompositionOwnershipTest {
    @After fun clear() { ProtectedHevcLiveComposition.clearAll() }
    @Test fun stoppingPhoneCompositionDoesNotDeleteTheIndependentlyOwnedTvProducer() {
        ProtectedHevcLiveComposition.selectFeeds("phone", listOf("main", "onboard"))
        ProtectedHevcLiveComposition.selectFeeds("cast", listOf("main", "onboard"), fullPictures = true)
        val castState = ProtectedHevcLiveComposition.status("cast")
        ProtectedHevcLiveComposition.clearAll(retainGroups = setOf("cast"))
        assertEquals("PROTECTED_COMPOSITION_NOT_SELECTED", ProtectedHevcLiveComposition.status("phone"))
        assertEquals(castState, ProtectedHevcLiveComposition.status("cast"))
        ProtectedHevcLiveComposition.clearSelection("cast", discardInitialization = true)
        assertEquals("PROTECTED_COMPOSITION_NOT_SELECTED", ProtectedHevcLiveComposition.status("cast"))
    }
}
