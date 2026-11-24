package app.apexview.tiled

import android.net.Uri
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist

/** Actual source master and selected variant, not a guessed subtitle endpoint. */
internal data class ArchiveCaptionRendition(val uri: Uri, val language: String, val label: String)

internal data class ArchiveCaptionSource(
    val masterUri: Uri,
    val streamKeys: List<StreamKey>,
    val renditions: List<ArchiveCaptionRendition>,
) {
    companion object {
        fun matchesTimeline(
            text: HlsMediaPlaylist, programme: HlsMediaPlaylist, clock: HlsMediaPlaylist,
        ): Boolean {
            fun agreesWith(video: HlsMediaPlaylist): Boolean {
                val segmentTolerance = video.targetDurationUs.coerceIn(1L, 20_000_000L)
                val compatibleOrigin = !text.hasProgramDateTime || !video.hasProgramDateTime ||
                    kotlin.math.abs(text.startTimeUs - video.startTimeUs) <= 250_000L
                return compatibleOrigin && kotlin.math.abs(text.durationUs - video.durationUs) <= segmentTolerance
            }
            return agreesWith(programme) && agreesWith(clock)
        }
    }
}
