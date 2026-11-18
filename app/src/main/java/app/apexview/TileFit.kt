package app.apexview

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.ImmutableList

/** How big a feed is shown (px), read on the player's thread. */
class TileFit {
    @Volatile var width = Int.MAX_VALUE
    @Volatile var height = Int.MAX_VALUE
    /** This feed's part of the connection, while other feeds are downloading pictures too. */
    @Volatile var share = 1f
    /** 0 means automatic tile fitting; otherwise prefer the best available height up to this cap. */
    @Volatile var qualityHeight = 0
    /** Prefer a smaller first segment on a replay resume or explicit seek in Auto. */
    @Volatile var fastStart = false
}

/**
 * Each feed's part of the connection: by the area it shows, since that's what its rendition
 * follows, with a floor so a small tile can still hold a watchable picture.
 */
internal fun bandwidthShares(areas: List<Float>): List<Float> {
    val total = areas.sum()
    return areas.map { if (total > 0f) (it / total).coerceIn(MIN_BANDWIDTH_SHARE, 1f) else 1f / areas.size }
}

private const val MIN_BANDWIDTH_SHARE = 0.1f

/** Highest rendition height available without exceeding the user's manual quality choice. */
internal fun highestAvailableVideoHeightAtOrBelow(
    renditions: List<Pair<Int, Int>>,
    maxHeight: Int,
): Int? = renditions.mapNotNull { (_, height) ->
    height.takeIf { it > 0 && it <= maxHeight }
}.maxOrNull()

/**
 * The most pixels a tile of this size needs: the smallest rendition that fills it, or the largest
 * there is if none does.
 */
internal fun pixelCeiling(renditions: List<Pair<Int, Int>>, width: Int, height: Int): Int =
    renditions.filter { it.first >= width && it.second >= height }.minOfOrNull { it.first * it.second }
        ?: renditions.maxOfOrNull { it.first * it.second }
        ?: Int.MAX_VALUE

/**
 * Picks each feed's rendition for the size of its tile. Limiting the tracks through the player's
 * settings (a viewport) throws away what's buffered whenever a tile changes size, which stalled
 * both feeds in a swap, sound included. Here every rendition stays selected and the size only
 * guides the choice, so the feed plays on and changes quality within a chunk or two.
 *
 * All the players share one bandwidth meter, which measures the whole connection. Left alone,
 * every feed would size itself as if it had that to itself, and together they'd ask for far more
 * than there is, so each sees only its share.
 */
@OptIn(UnstableApi::class)
class TileFitSelectionFactory(private val fit: TileFit) : AdaptiveTrackSelection.Factory() {
    override fun createAdaptiveTrackSelection(
        group: TrackGroup,
        tracks: IntArray,
        type: Int,
        bandwidthMeter: BandwidthMeter,
        adaptationCheckpoints: ImmutableList<AdaptiveTrackSelection.AdaptationCheckpoint>,
    ): AdaptiveTrackSelection = object : AdaptiveTrackSelection(
        group, tracks, type,
        object : BandwidthMeter by bandwidthMeter {
            override fun getBitrateEstimate() = (bandwidthMeter.bitrateEstimate * fit.share).toLong()
            override fun getTimeToFirstByteEstimateUs() = bandwidthMeter.timeToFirstByteEstimateUs
        },
        QUALITY_UP_AFTER_MS,
        // A shrunken tile steps down however much is buffered.
        Long.MAX_VALUE / 1_000,
        KEEP_BEFORE_UPGRADE_MS,
        // A grown tile replaces what's buffered past that, at any size.
        Int.MAX_VALUE, Int.MAX_VALUE,
        DEFAULT_BANDWIDTH_FRACTION,
        DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
        adaptationCheckpoints,
        Clock.DEFAULT,
    ) {
        private val availableRenditions = (0 until length()).map {
            getFormat(it).let { candidate -> candidate.width to candidate.height }
        }

        override fun canSelectFormat(format: Format, trackBitrate: Int, effectiveBitrate: Long): Boolean {
            val forcedHeight = fit.qualityHeight.takeIf { it > 0 }?.let {
                highestAvailableVideoHeightAtOrBelow(availableRenditions, it)
            }
            val fits = if (forcedHeight != null && format.height > 0) {
                // Manual quality pins the resolution while AdaptiveTrackSelection can still
                // move between bitrates at that resolution. Auto retains tile-size fitting.
                format.height == forcedHeight
            } else {
                val ceiling = pixelCeiling(availableRenditions, fit.width, fit.height)
                val fastStartCeiling = if (fit.fastStart) availableRenditions
                    .filter { (width, height) -> width > 0 && height in 1..540 }
                    .maxOfOrNull { (width, height) -> width * height } else null
                format.width <= 0 || format.height <= 0 ||
                    format.width * format.height <= minOf(ceiling, fastStartCeiling ?: ceiling)
            }
            return fits && super.canSelectFormat(format, trackBitrate, effectiveBitrate)
        }
    }

    private companion object {
        const val QUALITY_UP_AFTER_MS = 3_000L
        const val KEEP_BEFORE_UPGRADE_MS = 4_000L
    }
}
