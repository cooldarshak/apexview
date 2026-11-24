package app.apexview

import app.apexview.tiled.F1DashFeedCapture

/**
 * One entry-point policy for all authorized archive feeds, regardless of the playback
 * backend. We do NOT infer clear media from HLS, or encrypted media from DASH or a
 * licence URL. A candidate is clear only after CMAF init/fragment verification.
 *
 * Alternative URLs are read from the SAME F1 CONTENT/PLAY channel response. We
 * never fetch another channel's playback offer or open an extra PLAY session.
 */
internal object ArchiveCompositionSourcePolicy {
    data class Selection(
        val mayContainClearArchive: Boolean,
        val feeds: List<F1DashFeedCapture.Feed>,
    )

    fun select(sources: List<PlayInfo>): Selection {
        require(sources.isNotEmpty() && sources.map { it.profileGroup }.distinct().size == 1) {
            "Archive composition needs streams from the same authorized session"
        }
        require(sources.map { it.profileLabel }.distinct().size == sources.size) {
            "Archive composition needs distinct authorized channels"
        }
        val candidates = sources.map { source ->
            source.authorizedHlsCandidateUrl?.takeIf {
                ManifestProtocol.fromUrl(it) == ManifestProtocol.HLS
            } ?: source.url.takeIf {
                ManifestProtocol.fromUrl(it) != ManifestProtocol.DASH
            }
        }
        if (candidates.any { it == null }) {
            return Selection(false, sources.map { F1DashFeedCapture.Feed(it.profileLabel, it.url) })
        }
        return Selection(true, sources.indices.map { index ->
            F1DashFeedCapture.Feed(sources[index].profileLabel, requireNotNull(candidates[index]))
        })
    }
}
