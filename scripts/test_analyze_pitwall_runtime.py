import unittest

from analyze_pitwall_runtime import app_session, summarize, nearest_rank_p95


class RuntimeLogTests(unittest.TestCase):
    def test_selects_only_requested_build_in_appended_capture(self):
        log = "Apexview 1.0.572 · phone\nold\nApexview 1.0.573 · phone\nnew\n"
        self.assertIn("new", app_session(log, "1.0.573"))
        self.assertNotIn("old", app_session(log, "1.0.573"))
        with self.assertRaises(ValueError):
            app_session(log, "1.0.574")

    def test_sums_deduplicated_queue_warnings_and_hwui_intervals(self):
        log = """11-20 I PitwallFrames: role=player intervalMs=2000 frames=100 slow=60 frozen700ms=1
11-20 I PitwallFrames: role=player intervalMs=3000 frames=150 slow=75 frozen700ms=0
11-20 E BufferQueueProducer: [SurfaceTexture-1] dequeueBuffer: waitForFreeSlotThenRelock: timeout
11-20 E BUFFER_QUEUE_TIMEOUT repeats=3 last=BufferQueueProducer: [SurfaceTexture-1] dequeueBuffer: waitForFreeSlotThenRelock: timeout
11-20 I ApexviewSingleDecoder: SINGLE_DECODER_SCENE sources=4 pacingLatched=251 pacingPresented=248
11-20 I ApexviewSingleDecoder: ARCHIVE_VOD_PREPARED feeds=4 output=1920x2176
11-20 I ApexviewSingleDecoder: F1_COMPOSITION_PLAYBACK stage=ACTIVE activeVideoDecoders=1
"""
        result = summarize(log)
        self.assertEqual(result["frames"], 250)
        self.assertEqual(result["slow_pct"], 54.0)
        self.assertEqual(result["covered_seconds"], 5.0)
        self.assertEqual(result["queue_timeouts"], 4)
        self.assertEqual(result["queue_per_second"], 0.8)
        self.assertEqual(result["latched"], 251)
        self.assertEqual(result["drawn"], 248)
        self.assertEqual(result["active_decoders"], ["1"])
        self.assertTrue(result["four_feed_prepared"])

    def test_reports_protected_six_feed_allocations_and_real_window_metrics(self):
        log = "\n".join([
            "I PitwallFrames: role=player intervalMs=2000 frames=100 slow=20 "
            "inputP95UpperMs=23 animationP95UpperMs=30 thermalStatus=3",
            "I PitwallFrames: role=player intervalMs=2000 frames=100 slow=10 "
            "inputP95UpperMs=19 animationP95UpperMs=45 thermalStatus=4",
            "I F1_COMPOSITION_PLAYBACK feeds=6 stage=ACTIVE activeVideoDecoders=1",
        ] + [
            "I PROTECTED_CMAF_SEGMENT feeds=6 sampleCount=288 "
            "processAllocBytesDuringSegment={} processGcDuringSegment={} "
            "composeMs={} outputBytes=10000".format(alloc, gc, compose)
            for alloc, gc, compose in [
                (400, 9, 600), (350, 8, 550), (300, 7, 500),
                (200, 4, 350), (180, 3, 300), (160, 2, 270),
            ]
        ])
        report = summarize(log)
        self.assertEqual([6], report["protected_active_feeds"])
        self.assertEqual([3, 4], report["reported_thermal_statuses"])
        self.assertEqual(23, report["input_p95_bucket_max_ms"])
        self.assertEqual(45, report["animation_p95_bucket_max_ms"])
        self.assertEqual(6, report["protected_segments_total"])
        self.assertEqual(3, report["protected_segments_steady"])
        self.assertEqual(180, report["protected_alloc_median_bytes"])
        self.assertEqual(3, report["protected_gc_median"])
        self.assertEqual(300, report["protected_compose_median_ms"])
        self.assertEqual(10000, report["protected_output_median_bytes"])

    def test_p95_foreground_requests_count_hits_and_misses_without_bias(self):
        report = summarize("\\n".join(
            ["I ARCHIVE_VOD_REQUEST number={} cached=true waitMs=1".format(i) for i in range(18)]
            + ["I ARCHIVE_VOD_REQUEST number=18 cached=false waitMs=90",
               "I ARCHIVE_VOD_REQUEST number=19 cached=false waitMs=600",
               "I ARCHIVE_VOD_SEGMENT number=19 cachedBytes=5242880 outputBytes=4000",
               "I ARCHIVE_VOD_SEGMENT number=20 cachedBytes=12582912 outputBytes=6000",
               "I PROTECTED_CMAF_SEGMENT feeds=8 retainedBytes=67108864 outputBytes=8000"]
        ))
        self.assertEqual(20, report["archive_requests_total"])
        self.assertEqual(18, report["archive_cache_hits"])
        self.assertEqual(90.0, report["archive_cache_hit_pct"])
        self.assertEqual(90, report["archive_request_p95_ms"])
        self.assertEqual(600, report["archive_cache_miss_p95_ms"])
        self.assertEqual(1, report["archive_cache_hit_p95_ms"])
        self.assertEqual(12582912, report["archive_cache_peak_bytes"])
        self.assertEqual(67108864, report["protected_history_peak_bytes"])

    def test_empty_metrics_do_not_fabricate_latency_or_memory(self):
        report = summarize("unrelated log text")
        self.assertIsNone(nearest_rank_p95([]))
        self.assertEqual(3, nearest_rank_p95([1, 2, 3]))
        self.assertEqual(0, report["archive_requests_total"])
        self.assertIsNone(report["archive_request_p95_ms"])
        self.assertIsNone(report["archive_cache_peak_bytes"])
        self.assertIsNone(report["protected_history_peak_bytes"])

    def test_reports_steady_cmaf_allocations_without_startup_warmup(self):
        log = "\n".join(
            "I ARCHIVE_VOD_SEGMENT number={} processAllocBytesDuringSegment={} "
            "processGcDuringSegment={} composeMs={} rewriteMs={} outputBytes=9000".format(
                index, allocation, gc, compose, compose - 10)
            for index, allocation, gc, compose in [
                (1, 630_000_000, 19, 1200), (2, 535_000_000, 17, 1100),
                (3, 386_000_000, 11, 950), (4, 150_000_000, 4, 500),
                (5, 160_000_000, 3, 550), (6, 170_000_000, 4, 600)])
        result = summarize(log)
        self.assertEqual(result["archive_segments_total"], 6)
        self.assertEqual(result["archive_segments_steady"], 3)
        self.assertEqual(result["archive_alloc_median_bytes"], 160_000_000)
        self.assertEqual(result["archive_gc_median"], 4)


if __name__ == "__main__":
    unittest.main()
