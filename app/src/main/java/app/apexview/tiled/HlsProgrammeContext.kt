package app.apexview.tiled

import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist

/** The exact video variant and clock observed by rolling HLS composition. */
internal data class HlsProgrammeContext(
    val master: HlsMultivariantPlaylist,
    val audioGroup: String?,
    val subtitleGroup: String?,
    val playlistUtcStartUs: Long?,
    val playlistSourceStartUs: Long,
    val sourceSegmentStartUs: Long,
    val segmentDurationUs: Long,
    val videoMediaStartUs: Long,
    val discontinuity: Int,
    val live: Boolean,
)
