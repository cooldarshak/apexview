package app.apexview

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastHandoffTest {
    @Test fun handoffDisablesActualMedia3TracksAndBudgetOrAudioChangesCannotReactivateThem() {
        val feed = FeedPlayer(RuntimeEnvironment.getApplication(),
            PlayInfo("https://127.0.0.1:1/unused.mpd", null, "DASH"), "", "", "Pitwall-test", false, true, "en")
        try {
            feed.setAudible(true)
            feed.player.playWhenReady = true
            feed.suspendForRemotePlayback(true)
            feed.setVideo(true, 1920, 1080)
            feed.keepVideo()
            feed.setAudible(true)
            assertFalse(feed.player.playWhenReady)
            assertFalse(feed.showingVideo)
            assertEquals(0f, feed.player.volume, 0f)
            assertTrue(feed.player.trackSelectionParameters.disabledTrackTypes.containsAll(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)))
            // Removing/replacing a composed host can restore its original media source during casting.
            // That path must stay suppressed, and disconnect must still be able to re-enable it.
            feed.stopProtectedComposedPlayback(positionMs = 100L, playWhenReady = true, enableVideo = true)
            assertFalse(feed.showingVideo)
            assertFalse(feed.player.playWhenReady)
            assertTrue(C.TRACK_TYPE_VIDEO in feed.player.trackSelectionParameters.disabledTrackTypes)
            feed.suspendForRemotePlayback(false)
            feed.setVideo(true, 1920, 1080)
            feed.setAudible(true)
            assertTrue(feed.showingVideo)
            assertFalse(C.TRACK_TYPE_VIDEO in feed.player.trackSelectionParameters.disabledTrackTypes)
            assertFalse(C.TRACK_TYPE_AUDIO in feed.player.trackSelectionParameters.disabledTrackTypes)
            assertFalse(C.TRACK_TYPE_TEXT in feed.player.trackSelectionParameters.disabledTrackTypes)
        } finally { feed.release() }
    }
}
