package app.apexview.tiled

import java.math.BigInteger
import java.util.Collections

/**
 * Aligns independent HEVC CMAF feed windows by full POC and parsed picture identity, not by
 * timestamp position. Every sample must carry a full POC derived from a proven temporal-id-zero
 * IRAP anchor, and all feeds must share that anchor POC.
 *
 * The result proves a stable relation between observed timelines. It never rewrites timestamps.
 * Five consecutive decode-order picture matches are required by default.
 */
internal object HevcPictureSequenceAligner {
    data class PictureSample(
        /** Sample index within the inspected CMAF fragment. */
        val sampleIndex: Int,
        val timing: CmafBoxInspector.SampleTiming,
        val picture: HevcPictureIdentityCompatibility.PictureIdentity,
        val irapAnchorPictureOrderCount: Long?,
        /** Monotonic ordinal across the complete feed sequence, not just this fragment. */
        val sequenceSampleOrdinal: Long = sampleIndex.toLong()
    )

    data class FeedWindow(
        val feedId: String,
        val timescale: Long,
        val samples: List<PictureSample>
    )

    data class AlignedSampleEvidence(
        val sampleIndex: Int,
        val sequenceSampleOrdinal: Long,
        val timescale: Long,
        val timing: CmafBoxInspector.SampleTiming,
        val picture: HevcPictureIdentityCompatibility.PictureIdentity,
        val irapAnchorPictureOrderCount: Long?
    )

    data class PictureMatch(
        val masterSampleIndex: Int,
        val sampleIndicesByFeed: Map<String, Int>,
        val fullPictureOrderCount: Long,
        /** Exact sample evidence inspected by the aligner for this picture in every feed. */
        val sampleEvidenceByFeed: Map<String, AlignedSampleEvidence> = emptyMap()
    )

    data class ExactOffset(
        /** Feed minus master, in seconds, as a reduced rational number. */
        val numerator: BigInteger,
        val denominator: BigInteger,
        val offsetMillis: Long?
    )

    /**
     * A proof object can only be minted by this aligner. Collections exposed from it are
     * immutable snapshots; composer-side validation still checks all selected sample evidence.
     */
    class Result internal constructor(
        val compatible: Boolean,
        val masterFeedId: String?,
        val irapAnchorPictureOrderCount: Long?,
        matches: List<PictureMatch>,
        offsetsByFeed: Map<String, ExactOffset>,
        reasons: List<String>,
        private val issuerToken: Any
    ) {
        val matches: List<PictureMatch> = Collections.unmodifiableList(matches.toList())
        val offsetsByFeed: Map<String, ExactOffset> =
            Collections.unmodifiableMap(LinkedHashMap(offsetsByFeed))
        val reasons: List<String> = Collections.unmodifiableList(reasons.toList())

        internal fun wasIssuedBy(expectedIssuer: Any): Boolean = issuerToken === expectedIssuer
    }

    private val proofIssuer = Any()

    fun isAuthentic(result: Result): Boolean = result.wasIssuedBy(proofIssuer)

    private fun snapshotPicture(
        picture: HevcPictureIdentityCompatibility.PictureIdentity
    ) = picture.copy(
        references = Collections.unmodifiableList(picture.references.toList()),
        list0Modification = Collections.unmodifiableList(picture.list0Modification.toList()),
        list1Modification = Collections.unmodifiableList(picture.list1Modification.toList())
    )

    private data class Candidate(val master: PictureSample, val byFeed: List<PictureSample>)

    fun align(
        feeds: List<FeedWindow>,
        minimumConsecutivePictures: Int = DEFAULT_MINIMUM_MATCHES,
        context: String = "HEVC picture sequence alignment"
    ): Result {
        val reasons = mutableListOf<String>()
        if (feeds.size !in MIN_FEEDS..MAX_FEEDS) {
            reasons += context + ": between two and six feed windows are required"
        }
        if (minimumConsecutivePictures !in MINIMUM_MATCHES..MAX_WINDOW_SAMPLES) {
            reasons += context + ": minimum consecutive-picture count is outside supported bounds"
        }
        if (feeds.any { it.feedId.isBlank() } ||
            feeds.map { it.feedId }.distinct().size != feeds.size) {
            reasons += context + ": feed IDs must be non-empty and unique"
        }
        if (reasons.isNotEmpty()) return rejected(feeds.firstOrNull()?.feedId, null, reasons)

        feeds.forEach { feed -> reasons += validateWindow(feed, context) }
        if (reasons.isNotEmpty()) return rejected(feeds.firstOrNull()?.feedId, null, reasons)

        val anchorPocs = feeds.map { it.samples.first().irapAnchorPictureOrderCount }.distinct()
        if (anchorPocs.size != 1 || anchorPocs.singleOrNull() == null) {
            reasons += context + ": feeds do not share one verified IRAP POC epoch"
            return rejected(feeds.firstOrNull()?.feedId, null, reasons)
        }
        val sharedAnchorPoc = requireNotNull(anchorPocs.single())

        val master = feeds.first()
        val indexes = feeds.drop(1).associate { feed ->
            feed.feedId to feed.samples.associateBy { it.picture.pictureOrderCount }
        }
        val candidates = mutableListOf<Candidate?>()
        master.samples.forEach { masterSample ->
            val matched = mutableListOf(masterSample)
            var isMatch = true
            feeds.drop(1).forEach { feed ->
                val candidate = indexes.getValue(feed.feedId)[masterSample.picture.pictureOrderCount]
                if (candidate == null) {
                    isMatch = false
                } else {
                    val identity = HevcPictureIdentityCompatibility.compare(
                        listOf(masterSample.picture), listOf(candidate.picture)
                    )
                    if (!identity.compatible) {
                        isMatch = false
                        reasons += context + ": feed " + feed.feedId +
                            " has a different HEVC picture identity for POC " +
                            masterSample.picture.pictureOrderCount
                    } else if (!timingCompatible(
                            masterSample, master.timescale, candidate, feed.timescale
                        )) {
                        isMatch = false
                        reasons += context + ": feed " + feed.feedId +
                            " sample duration, composition offset or random-access flags differ for POC " +
                            masterSample.picture.pictureOrderCount
                    } else {
                        matched += candidate
                    }
                }
            }
            candidates += if (isMatch) Candidate(masterSample, matched) else null
        }

        val runs = mutableListOf<List<Candidate>>()
        var current = mutableListOf<Candidate>()
        fun closeRun() {
            if (current.isNotEmpty()) runs += current.toList()
            current = mutableListOf()
        }
        candidates.forEach { candidate ->
            if (candidate == null) {
                closeRun()
            } else {
                val previous = current.lastOrNull()
                val sequential = previous == null ||
                    (candidate.master.sampleIndex.toLong() ==
                        previous.master.sampleIndex.toLong() + 1L &&
                        candidate.byFeed.indices.all { index ->
                            candidate.byFeed[index].sampleIndex.toLong() ==
                                previous.byFeed[index].sampleIndex.toLong() + 1L
                        })
                if (!sequential) closeRun()
                current += candidate
            }
        }
        closeRun()

        val bestRun = runs.maxByOrNull { it.size }.orEmpty()
        if (bestRun.size < minimumConsecutivePictures) {
            reasons += context + ": found " + bestRun.size +
                " consecutive matching pictures; at least " +
                minimumConsecutivePictures + " are required"
            return rejected(master.feedId, sharedAnchorPoc, reasons)
        }

        val offsets = linkedMapOf<String, ExactOffset>()
        feeds.forEachIndexed { feedIndex, feed ->
            val firstPair = bestRun.first()
            val offset = if (feedIndex == 0) {
                ExactOffset(BigInteger.ZERO, BigInteger.ONE, 0L)
            } else {
                exactOffset(
                    firstPair.master.timing.decodeTimeTicks!!,
                    master.timescale,
                    firstPair.byFeed[feedIndex].timing.decodeTimeTicks!!,
                    feed.timescale
                )
            }
            bestRun.drop(1).forEachIndexed { matchIndex, candidate ->
                if (feedIndex > 0) {
                    val candidateOffset = exactOffset(
                        candidate.master.timing.decodeTimeTicks!!,
                        master.timescale,
                        candidate.byFeed[feedIndex].timing.decodeTimeTicks!!,
                        feed.timescale
                    )
                    if (candidateOffset.numerator != offset.numerator ||
                        candidateOffset.denominator != offset.denominator) {
                        reasons += context + ": timestamp offset for feed " + feed.feedId +
                            " drifts at matched picture " + (matchIndex + 1)
                    }
                    val ptsOffset = exactOffset(
                        candidate.master.timing.presentationTimeTicks!!,
                        master.timescale,
                        candidate.byFeed[feedIndex].timing.presentationTimeTicks!!,
                        feed.timescale
                    )
                    if (ptsOffset.numerator != offset.numerator ||
                        ptsOffset.denominator != offset.denominator) {
                        reasons += context + ": presentation-time offset for feed " + feed.feedId +
                            " does not equal its decode-time offset"
                    }
                }
            }
            offsets[feed.feedId] = offset
        }

        val matches = bestRun.map { candidate ->
            val sampleIndices = feeds.indices.associate { index ->
                feeds[index].feedId to candidate.byFeed[index].sampleIndex
            }
            val evidence = feeds.indices.associate { index ->
                val feed = feeds[index]
                val sample = candidate.byFeed[index]
                feed.feedId to AlignedSampleEvidence(
                    sampleIndex = sample.sampleIndex,
                    sequenceSampleOrdinal = sample.sequenceSampleOrdinal,
                    timescale = feed.timescale,
                    timing = sample.timing.copy(),
                    picture = snapshotPicture(sample.picture),
                    irapAnchorPictureOrderCount = sample.irapAnchorPictureOrderCount
                )
            }
            PictureMatch(
                masterSampleIndex = candidate.master.sampleIndex,
                sampleIndicesByFeed = Collections.unmodifiableMap(LinkedHashMap(sampleIndices)),
                fullPictureOrderCount = candidate.master.picture.pictureOrderCount,
                sampleEvidenceByFeed =
                    Collections.unmodifiableMap(LinkedHashMap(evidence))
            )
        }
        return Result(
            compatible = reasons.isEmpty(),
            masterFeedId = master.feedId,
            irapAnchorPictureOrderCount = sharedAnchorPoc,
            matches = if (reasons.isEmpty()) matches else emptyList(),
            offsetsByFeed = if (reasons.isEmpty()) offsets else emptyMap(),
            reasons = reasons.distinct().take(MAX_REASONS),
            issuerToken = proofIssuer
        )
    }

    private fun validateWindow(feed: FeedWindow, context: String): List<String> {
        val reasons = mutableListOf<String>()
        if (feed.timescale <= 0L) {
            reasons += context + ": feed " + feed.feedId + " timescale is not positive"
        }
        if (feed.samples.size !in 1..MAX_WINDOW_SAMPLES) {
            reasons += context + ": feed " + feed.feedId + " sample window is outside supported bounds"
        }
        val duplicatePocs = feed.samples.groupBy { it.picture.pictureOrderCount }
            .filterValues { it.size > 1 }.keys
        if (duplicatePocs.isNotEmpty()) {
            reasons += context + ": feed " + feed.feedId + " has ambiguous duplicate full POCs"
        }
        val anchors = feed.samples.map { it.irapAnchorPictureOrderCount }.distinct()
        if (anchors.size != 1 || anchors.singleOrNull() == null) {
            reasons += context + ": feed " + feed.feedId +
                " has no consistent verified temporal-id-zero IRAP POC anchor"
        }
        feed.samples.forEachIndexed { index, sample ->
            val previous = feed.samples.getOrNull(index - 1)
            val expectedSequenceOrdinal = previous?.let {
                runCatching { Math.addExact(it.sequenceSampleOrdinal, 1L) }.getOrNull()
            }
            if (sample.sampleIndex < 0 || sample.sequenceSampleOrdinal < 0L ||
                (previous != null &&
                    sample.sampleIndex.toLong() != previous.sampleIndex.toLong() + 1L) ||
                (previous != null && expectedSequenceOrdinal != sample.sequenceSampleOrdinal)) {
                reasons += context + ": feed " + feed.feedId +
                    " fragment indices/global sequence sample ordinals are not contiguous"
            }
            val timing = sample.timing
            if (timing.decodeTimeTicks == null || timing.presentationTimeTicks == null ||
                timing.durationTicks == null || timing.durationTicks <= 0L ||
                timing.compositionOffsetTicks == null || timing.sampleFlags == null) {
                reasons += context + ": feed " + feed.feedId + " sample " + index +
                    " timing/flags are incomplete"
            } else {
                val expectedPts = runCatching {
                    Math.addExact(
                        requireNotNull(timing.decodeTimeTicks),
                        requireNotNull(timing.compositionOffsetTicks)
                    )
                }.getOrNull()
                if (expectedPts == null || expectedPts != timing.presentationTimeTicks) {
                    reasons += context + ": feed " + feed.feedId + " sample " + index +
                        " PTS is inconsistent with DTS+CTO"
                }
                if ((timing.sampleFlags and RESERVED_SAMPLE_FLAGS_MASK) != 0L) {
                    reasons += context + ": feed " + feed.feedId + " sample " + index +
                        " reserved sample_flags bits are non-zero"
                }
            }
            if (sample.picture.temporalIdPlus1 !in 1..7) {
                reasons += context + ": feed " + feed.feedId + " sample " + index +
                    " temporal_id_plus1 is invalid"
            }
        }
        return reasons.distinct()
    }

    private fun timingCompatible(
        master: PictureSample,
        masterScale: Long,
        candidate: PictureSample,
        candidateScale: Long
    ): Boolean {
        val a = master.timing
        val b = candidate.timing
        if (!sameTime(a.durationTicks, masterScale, b.durationTicks, candidateScale)) return false
        if (!sameTime(
                a.compositionOffsetTicks, masterScale,
                b.compositionOffsetTicks, candidateScale
            )) return false
        val firstFlags = a.sampleFlags ?: return false
        val secondFlags = b.sampleFlags ?: return false
        return ((firstFlags xor secondFlags) and RANDOM_ACCESS_DEPENDENCY_MASK) == 0L
    }

    private fun sameTime(
        firstTicks: Long?,
        firstScale: Long,
        secondTicks: Long?,
        secondScale: Long
    ): Boolean {
        if (firstTicks == null || secondTicks == null) return false
        val first = BigInteger.valueOf(firstTicks).multiply(BigInteger.valueOf(secondScale))
        val second = BigInteger.valueOf(secondTicks).multiply(BigInteger.valueOf(firstScale))
        return first == second
    }

    private fun exactOffset(
        masterTicks: Long,
        masterScale: Long,
        feedTicks: Long,
        feedScale: Long
    ): ExactOffset {
        val numerator = BigInteger.valueOf(feedTicks)
            .multiply(BigInteger.valueOf(masterScale))
            .subtract(BigInteger.valueOf(masterTicks).multiply(BigInteger.valueOf(feedScale)))
        val denominator = BigInteger.valueOf(masterScale).multiply(BigInteger.valueOf(feedScale))
        val divisor = numerator.abs().gcd(denominator)
        val reducedNumerator = numerator.divide(divisor)
        val reducedDenominator = denominator.divide(divisor)
        val millis = toMillis(reducedNumerator, reducedDenominator)
        return ExactOffset(reducedNumerator, reducedDenominator, millis)
    }

    private fun toMillis(numerator: BigInteger, denominator: BigInteger): Long? {
        val scaled = numerator.multiply(THOUSAND)
        val sign = scaled.signum()
        val division = scaled.abs().divideAndRemainder(denominator)
        val rounded = if (division[1].multiply(TWO) >= denominator) {
            division[0].add(BigInteger.ONE)
        } else division[0]
        val signed = if (sign < 0) rounded.negate() else rounded
        return runCatching { signed.longValueExact() }.getOrNull()
    }

    private fun rejected(
        masterFeedId: String?,
        irapAnchorPoc: Long?,
        reasons: List<String>
    ) = Result(
        compatible = false,
        masterFeedId = masterFeedId,
        irapAnchorPictureOrderCount = irapAnchorPoc,
        matches = emptyList(),
        offsetsByFeed = emptyMap(),
        reasons = reasons.distinct().take(MAX_REASONS),
        issuerToken = proofIssuer
    )

    private const val MIN_FEEDS = 2
    private const val MAX_FEEDS = 6
    private const val DEFAULT_MINIMUM_MATCHES = 5
    private const val MINIMUM_MATCHES = 2
    private const val MAX_WINDOW_SAMPLES = 4096
    private const val MAX_REASONS = 12
    private const val RESERVED_SAMPLE_FLAGS_MASK = 0xf0000000L
    private const val RANDOM_ACCESS_DEPENDENCY_MASK = 0x0fff0000L
    private val THOUSAND = BigInteger.valueOf(1_000L)
    private val TWO = BigInteger.valueOf(2L)
}
