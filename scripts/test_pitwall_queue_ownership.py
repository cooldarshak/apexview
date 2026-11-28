import unittest
from analyze_pitwall_queue_ownership import analyze

class QueueOwnershipTest(unittest.TestCase):
    def test_nonblocking_refusals_and_owner_retirement(self):
        q = "BufferQueueProducer: [SurfaceTexture-1](id:a,api:3,p:1,c:1) dequeueBuffer: waitForFreeSlotThenRelock: timeout. dequeuedCount: 21, acquiredCount: 1, mDequeueTimeout: 0"
        data = analyze([q, "BUFFER_QUEUE_TIMEOUT repeats=3 last=" + q,
            "GRAPHICS_OWNER_EVENT owner=1 event=CREATE contexts=2 decoderTextures=1",
            "platformGraphicsKiB=310000 sceneGraphicsOwners=1",
            "SCENE_ASYNC_FENCE_WAIT waits=4 maxNs=8000000",
            "SCENE_DECODER_INGRESS notifications=250 coalesced=0",
            "GRAPHICS_OWNER_EVENT owner=1 event=RELEASE contexts=0 decoderTextures=0",
            "platformGraphicsKiB=0 sceneGraphicsOwners=0"])
        self.assertEqual(data["queues"][0]["events"], 4)
        self.assertEqual(data["queues"][0]["nonblocking"], 4)
        self.assertEqual(data["queues"][0]["max_dequeued"], 21)
        self.assertEqual(data["owners_at_end"], 0)
        self.assertEqual(data["egl_context_peak"], 2)
        self.assertEqual(data["peak_graphics_kb"], 310000)
        self.assertEqual(data["peak_graphics_with_no_scene_kb"], 0)
        self.assertEqual(data["vendor_allocation_bytes"], "NOT_MEASURED")
    def test_normalized_events_are_not_measured_stall_time(self):
        q = "BufferQueueProducer: [SurfaceTexture-1](id:a,api:3,p:1,c:1) waitForFreeSlotThenRelock dequeuedCount: 21, acquiredCount: 1, mDequeueTimeout: "
        row = analyze([q + "0", q + "1000000"], 10)["queues"][0]
        self.assertEqual(0.2, row["events_per_active_second"])
        self.assertEqual(0.1, row["nonblocking_per_active_second"])
        self.assertEqual(1, row["configured_wait_events"])
        self.assertEqual("NOT_MEASURED", row["actual_stall_duration_ns"])
        self.assertIsNone(analyze([q + "0"])["queues"][0]["events_per_active_second"])
        with self.assertRaises(ValueError):
            analyze([], 0)

    def test_missing_not_zero(self):
        data = analyze([])
        self.assertIsNone(data["peak_graphics_kb"])
        self.assertIsNone(data["peak_graphics_with_no_scene_kb"])
        self.assertEqual(data["queues"], [])
