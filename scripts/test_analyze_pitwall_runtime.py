import unittest

from analyze_pitwall_runtime import app_session, summarize


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


if __name__ == "__main__":
    unittest.main()
