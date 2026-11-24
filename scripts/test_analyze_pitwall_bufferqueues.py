import unittest
from analyze_pitwall_bufferqueues import analyze


class BufferQueueEvidenceTests(unittest.TestCase):
    def test_distinguishes_internal_codec_queue_from_texture_and_expanded_repeats(self):
        log = """11-24 10:02:58.000 I ApexviewSingleDecoder: SCENE_CODEC_INPUT_DETACH renderer=TextureView surfaceId=12 inputEpoch=2
11-24 10:02:59.000 E BufferQueueProducer: [MediaCodec.release](id:abc,api:3) dequeueBuffer: waitForFreeSlotThenRelock: timeout. dequeuedCount: 23, acquiredCount: 0, mDequeueTimeout: 0
BUFFER_QUEUE_TIMEOUT repeats=11 last=11-24 10:02:59.500 E BufferQueueProducer: [MediaCodec.release](id:abc,api:3) dequeueBuffer: waitForFreeSlotThenRelock: timeout. dequeuedCount: 23, acquiredCount: 0, mDequeueTimeout: 0
11-24 10:03:10.000 E BufferQueueProducer: [SurfaceTexture-4](id:def,api:3) dequeueBuffer: waitForFreeSlotThenRelock: timeout. dequeuedCount: 2, acquiredCount: 1, mDequeueTimeout: 0
11-24 10:02:59.000 E BufferQueueProducer: [MediaCodec.release](id:abc,api:3) detachBuffer: slot 1 is not owned by the producer (state = FREE)
11-24 10:02:59.010 E BufferQueueProducer: [MediaCodec.release](id:abc,api:3) cancelBuffer: slot 2 is not owned by the producer (state = FREE)
"""
        r = analyze(log)
        self.assertEqual(15, r["queue_events"])
        self.assertEqual(13, r["nonblocking_timeouts"])
        self.assertEqual(1, r["app_detach_events"])
        self.assertEqual(0, r["app_attach_events"])
        codec = r["queue_by_identity"]["MediaCodec.release#abc"]
        self.assertEqual(12, codec["dequeue_timeout"])
        self.assertEqual(14, codec["near_app_detach_or_release"])
        self.assertEqual(1, r["queue_by_identity"]["SurfaceTexture-4#def"]["dequeue_timeout"])
        self.assertEqual(0, r["queue_by_identity"]["SurfaceTexture-4#def"]["near_app_detach_or_release"])
        self.assertEqual("NOT_MEASURED", r["physical_video_frames_dropped"])

    def test_missing_owner_evidence_does_not_imply_stall_or_release(self):
        r = analyze("11-24 10:00:00.000 E BufferQueueProducer: [MediaCodec.release](id:99) dequeueBuffer: waitForFreeSlotThenRelock: timeout. mDequeueTimeout: 0")
        self.assertEqual(1, r["nonblocking_timeouts"])
        self.assertEqual(0, r["queue_by_identity"]["MediaCodec.release#99"]["near_app_detach_or_release"])
        self.assertEqual(0, r["app_release_events"])
        self.assertEqual("NOT_MEASURED", r["physical_queue_depth"])

    def test_unreported_timeout_semantics_not_labeled_nonblocking(self):
        log = "11-24 10:00:00.000 E BufferQueueProducer: [foo] dequeueBuffer: waitForFreeSlotThenRelock: timeout"
        r = analyze(log)
        self.assertEqual(1, r["blocking_or_unknown_timeouts"])
        self.assertEqual(0, r["nonblocking_timeouts"])


if __name__ == "__main__":
    unittest.main()
