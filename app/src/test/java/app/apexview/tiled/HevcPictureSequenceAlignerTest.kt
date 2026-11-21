package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcPictureSequenceAlignerTest {
    @Test fun alignsFourFeedsByFullPocAcrossStableElevenSecondOffset() {
        val master = window("main", 50_000L, 0, 0L)
        val offset = window("driver", 50_000L, 12, 576_000L)
        val third = window("map", 100_000L, 40, 1_152_000L)
        val fourth = window("data", 25_000L, 50, 288_000L)

        val result = HevcPictureSequenceAligner.align(listOf(master, offset, third, fourth))

        assertTrue(result.reasons.toString(), result.compatible)
        assertEquals("main", result.masterFeedId)
        assertEquals(0L, result.irapAnchorPictureOrderCount)
        assertEquals(5, result.matches.size)
        assertEquals((0L..4L).toList(), result.matches.map { it.fullPictureOrderCount })
        assertEquals(12, result.matches.first().sampleIndicesByFeed["driver"])
        assertEquals(40, result.matches.first().sampleIndicesByFeed["map"])
        assertEquals(50, result.matches.first().sampleIndicesByFeed["data"])
        assertEquals(11_520L, result.offsetsByFeed["driver"]!!.offsetMillis)
        assertEquals(11_520L, result.offsetsByFeed["map"]!!.offsetMillis)
        assertEquals(11_520L, result.offsetsByFeed["data"]!!.offsetMillis)
    }

    @Test fun rejectsVariableTimestampOffsetAcrossMatchedPictures() {
        val master = window("main", 50_000L, 0, 0L)
        val shifted = window(
            "driver", 50_000L, 20, 576_000L,
            dtsAdjustments = listOf(0L, 0L, 1L, 1L, 1L)
        )
        val result = HevcPictureSequenceAligner.align(listOf(master, shifted))
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("timestamp offset") && it.contains("drifts") })
    }

    @Test fun rejectsDuplicatePocValuesInsteadOfGuessingWhichFrameMatches() {
        val original = window("driver", 50_000L, 10, 576_000L)
        val duplicate = original.copy(samples = original.samples.mapIndexed { index, sample ->
            if (index == 2) sample.copy(picture = picture(1L)) else sample
        })
        val result = HevcPictureSequenceAligner.align(
            listOf(window("main", 50_000L, 0, 0L), duplicate)
        )
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("ambiguous duplicate full POCs") })
    }

    @Test fun rejectsSamePocWhenPredictionReferenceIdentityDiffers() {
        val original = window("driver", 50_000L, 20, 576_000L)
        val mismatch = original.copy(samples = original.samples.mapIndexed { index, sample ->
            if (index == 3) sample.copy(
                picture = picture(3L).copy(
                    references = listOf(
                        HevcSliceHeaderParser.ReferencePicture(
                            deltaPoc = -2, pocLsb = null, usedByCurrent = true, longTerm = false
                        )
                    )
                )
            ) else sample
        })
        val result = HevcPictureSequenceAligner.align(
            listOf(window("main", 50_000L, 0, 0L), mismatch)
        )
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("different HEVC picture identity") })
    }

    @Test fun rejectsWindowsWithoutFiveConsecutiveMatchedPictures() {
        val original = window("driver", 50_000L, 20, 576_000L)
        val short = original.copy(samples = original.samples.take(2))
        val result = HevcPictureSequenceAligner.align(
            listOf(window("main", 50_000L, 0, 0L), short)
        )
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("found 2 consecutive matching pictures") })
    }

    @Test fun rejectsSampleIndexGaps() {
        val original = window("main", 50_000L, 0, 0L)
        val gap = original.copy(samples = original.samples.mapIndexed { index, sample ->
            if (index == 2) sample.copy(sampleIndex = sample.sampleIndex + 1) else sample
        })
        val result = HevcPictureSequenceAligner.align(
            listOf(gap, window("driver", 50_000L, 10, 576_000L))
        )
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("sample ordinals are not contiguous") })
    }

    @Test fun rejectsWindowsWithoutVerifiedIrapAnchor() {
        val original = window("driver", 50_000L, 10, 576_000L)
        val unanchored = original.copy(samples = original.samples.mapIndexed { index, sample ->
            if (index == 0) sample.copy(irapAnchorPictureOrderCount = null) else sample
        })
        val result = HevcPictureSequenceAligner.align(
            listOf(window("main", 50_000L, 0, 0L), unanchored)
        )
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("consistent verified temporal-id-zero IRAP POC anchor") })
    }

    @Test fun rejectsDifferentIrapAnchorEpochsEvenWhenCurrentPocsMatch() {
        val master = window("main", 50_000L, 0, 0L)
        val otherEpoch = window("driver", 50_000L, 10, 576_000L).copy(
            samples = window("driver", 50_000L, 10, 576_000L).samples.map {
                it.copy(irapAnchorPictureOrderCount = -16L)
            }
        )
        val result = HevcPictureSequenceAligner.align(listOf(master, otherEpoch))
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("do not share one verified IRAP POC epoch") })
    }

    @Test fun rejectsMismatchedFrameCadenceOrRandomAccessFlags() {
        val original = window("driver", 50_000L, 20, 576_000L)
        val mismatch = original.copy(samples = original.samples.mapIndexed { index, sample ->
            if (index == 2) sample.copy(
                timing = sample.timing.copy(durationTicks = 999L, sampleFlags = 0L)
            ) else sample
        })
        val result = HevcPictureSequenceAligner.align(
            listOf(window("main", 50_000L, 0, 0L), mismatch)
        )
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("sample duration, composition offset or random-access flags differ") })
    }

    @Test fun acceptsNinetySixRealWindowShapedPicturesAcrossTwoVerifiedIdrEpochs() {
        // Build 595 inspected 96 protected HEV1 samples from each of four feeds.
        // The same full POC can legitimately recur after IDR resets (here at 48).
        // These are metadata-only regression samples, not fake claims of decoded F1 media.
        val feeds = (0 until 4).map { feed ->
            val pictures = (0 until 96).map { index ->
                val epoch = (index / 48).toLong()
                val poc = (index % 48).toLong()
                val dts = 817_920L + index * 1_800L
                HevcPictureSequenceAligner.PictureSample(
                    sampleIndex = index,
                    sequenceSampleOrdinal = index.toLong(),
                    irapEpochOrdinal = epoch,
                    irapAnchorPictureOrderCount = 0L,
                    fragmentBaseDecodeTimeTicks = 817_920L,
                    timing = CmafBoxInspector.SampleTiming(
                        decodeTimeTicks = dts,
                        presentationTimeTicks = dts,
                        durationTicks = 1800L,
                        compositionOffsetTicks = 0L,
                        sampleSizeBytes = 4_000L,
                        sampleFlags = 0x02000000L
                    ),
                    picture = picture(poc)
                )
            }
            HevcPictureSequenceAligner.FeedWindow("channel-${feed}", 90_000L, pictures)
        }
        val proof = HevcPictureSequenceAligner.align(feeds)
        assertTrue(proof.reasons.toString(), proof.compatible)
        assertEquals(96, proof.matches.size)
        assertEquals(2, proof.matches.map { it.sampleEvidenceByFeed.getValue("channel-0").irapEpochOrdinal }.distinct().size)
        assertEquals(0L, proof.matches[0].fullPictureOrderCount)
        assertEquals(0L, proof.matches[48].fullPictureOrderCount)
        assertEquals(48L, proof.matches[48].sampleEvidenceByFeed.getValue("channel-0").sequenceSampleOrdinal)
    }

    @Test fun doesNotTreatAnUnverifiedIdrEpochJumpAsNewValidPictureIdentity() {
        val original = (0 until 2).map { feed ->
            HevcPictureSequenceAligner.FeedWindow("feed-$feed", 90_000L,
                (0 until 6).map { index ->
                    val dts = 817_920L + index * 1_800L
                    HevcPictureSequenceAligner.PictureSample(
                        sampleIndex = index,
                        sequenceSampleOrdinal = index.toLong(),
                        irapEpochOrdinal = if (index == 3) 1L else 0L,
                        irapAnchorPictureOrderCount = 0L,
                        timing = CmafBoxInspector.SampleTiming(
                            decodeTimeTicks = dts, presentationTimeTicks = dts,
                            durationTicks = 1800L, compositionOffsetTicks = 0L,
                            sampleSizeBytes = 100L, sampleFlags = 0x02000000L),
                        picture = picture(index.toLong())
                    )
                })
        }
        val proof = HevcPictureSequenceAligner.align(original)
        assertFalse(proof.compatible)
        assertTrue(proof.reasons.toString(), proof.reasons.any {
            it.contains("unverified or non-monotonic IRAP epoch transition")
        })
    }

    @Test fun mismatchOfARepeatedPocBetweenDistinctEpochsCannotPassAsSamePicture() {
        val base = (0 until 2).map { feed ->
            HevcPictureSequenceAligner.FeedWindow("f-$feed", 90_000L, (0 until 10).map { index ->
                val poc = (index % 5).toLong()
                val dts = 100_000L + index * 1_800L
                HevcPictureSequenceAligner.PictureSample(
                    sampleIndex = index, sequenceSampleOrdinal = index.toLong(),
                    irapEpochOrdinal = (index / 5).toLong(),
                    irapAnchorPictureOrderCount = 0L,
                    timing = CmafBoxInspector.SampleTiming(
                        decodeTimeTicks = dts, presentationTimeTicks = dts,
                        durationTicks = 1800L, compositionOffsetTicks = 0L,
                        sampleSizeBytes = 100L, sampleFlags = 0x02000000L),
                    picture = picture(poc)
                )
            })
        }
        val changed = base[1].copy(samples = base[1].samples.mapIndexed { index, sample ->
            if (index == 7) sample.copy(picture = sample.picture.copy(sliceType = 2))
            else sample
        })
        val proof = HevcPictureSequenceAligner.align(listOf(base[0], changed))
        assertFalse(proof.compatible)
        assertTrue(proof.reasons.toString(), proof.reasons.any { it.contains("different HEVC picture identity") })
    }

    private fun window(
        id: String,
        scale: Long,
        firstIndex: Int,
        dtsStart: Long,
        dtsAdjustments: List<Long> = List(5) { 0L }
    ): HevcPictureSequenceAligner.FeedWindow {
        val samples = (0 until 5).map { index ->
            val dts = dtsStart + index * (scale / 50L) + dtsAdjustments[index]
            val cto = scale / 1250L
            HevcPictureSequenceAligner.PictureSample(
                sampleIndex = firstIndex + index,
                timing = CmafBoxInspector.SampleTiming(
                    decodeTimeTicks = dts,
                    presentationTimeTicks = dts + cto,
                    durationTicks = scale / 50L,
                    compositionOffsetTicks = cto,
                    sampleSizeBytes = 100L,
                    sampleFlags = 0x02000000L
                ),
                picture = picture(index.toLong()),
                irapAnchorPictureOrderCount = 0L
            )
        }
        return HevcPictureSequenceAligner.FeedWindow(id, scale, samples)
    }

    private fun picture(poc: Long) = HevcPictureIdentityCompatibility.PictureIdentity(
        nalUnitType = if (poc == 0L) 20 else 1,
        temporalIdPlus1 = 1,
        pictureOrderCount = poc,
        sliceType = if (poc == 0L) 2 else 1,
        noOutputOfPriorPictures = if (poc == 0L) true else null,
        references = if (poc == 0L) emptyList() else listOf(
            HevcSliceHeaderParser.ReferencePicture(
                deltaPoc = -1, pocLsb = null, usedByCurrent = true, longTerm = false
            )
        ),
        picOutputFlag = true
    )
}
