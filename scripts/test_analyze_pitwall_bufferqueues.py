import unittest
from analyze_pitwall_bufferqueues import analyze, at_ms


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

    def test_codec_output_delay_correlates_with_producer_occupancy_without_guessing_root_cause(self):
        log = """11-24 10:02:53.022 I CCodecBufferChannel: [c2.qti.hevc.decoder#318] start: max output delay 22
11-24 10:02:53.545 E BufferQueueProducer: [MediaCodec.release](id:abc) dequeueBuffer: waitForFreeSlotThenRelock: timeout. dequeuedCount: 23, acquiredCount: 0, mDequeueTimeout: 0
11-24 10:03:00.488 E BufferQueueProducer: [SurfaceTexture-1-12058-1](id:def) dequeueBuffer: waitForFreeSlotThenRelock: timeout. dequeuedCount: 21, acquiredCount: 2, mDequeueTimeout: 0
11-24 10:03:02.988 E MediaDrm: Object Already unbinded
"""
        report = analyze(log)
        self.assertEqual({"c2.qti.hevc.decoder": [22]},
                         report["codec_max_output_delay_by_name"])
        self.assertTrue(report["codec_output_delay_near_occupied_slots"])
        self.assertTrue(report["codec_output_delay_match_does_not_prove_causation"])
        self.assertEqual([23],
            report["queue_by_identity"]["MediaCodec.release#abc"]["observed_dequeued_counts"])
        self.assertEqual([0],
            report["queue_by_identity"]["MediaCodec.release#abc"]["observed_acquired_counts"])
        self.assertEqual([21],
            report["queue_by_identity"]["SurfaceTexture-1-12058-1#def"]["observed_dequeued_counts"])
        self.assertEqual(1, report["media_drm_double_unbind_warnings"])
        self.assertEqual("NOT_MEASURED", report["physical_queue_depth"])

    def test_unreported_timeout_semantics_not_labeled_nonblocking(self):
        log = "11-24 10:00:00.000 E BufferQueueProducer: [foo] dequeueBuffer: waitForFreeSlotThenRelock: timeout"
        r = analyze(log)
        self.assertEqual(1, r["blocking_or_unknown_timeouts"])
        self.assertEqual(0, r["nonblocking_timeouts"])

    def test_steady_window_uses_exact_process_and_excludes_a_later_transition(self):
        timeout = "dequeueBuffer: waitForFreeSlotThenRelock: timeout. mDequeueTimeout: 0"
        log = f"""11-26 16:45:31.000 100 100 I Example: SCENE_CODEC_INPUT_ATTACH
11-26 16:45:32.000 100 111 E BufferQueueProducer: [codec](id:a,api:3) {timeout}
11-26 16:45:32.200 200 200 I Example: SCENE_CODEC_INPUT_RELEASE
11-26 16:45:32.300 200 221 E BufferQueueProducer: [codec](id:b,api:3) {timeout}
11-26 16:45:36.000 100 100 I Example: SCENE_CODEC_INPUT_DETACH
11-26 16:45:36.200 100 111 E BufferQueueProducer: [codec](id:c,api:3) {timeout}
"""
        self.assertEqual(0, analyze(log)["queue_by_identity"]["codec#a"]["near_app_detach_or_release"])
        steady = analyze(log, pid=100, start_ms=at_ms("11-26 16:45:31.000"),
                         end_ms=at_ms("11-26 16:45:36.000"))
        self.assertEqual(1, steady["nonblocking_timeouts"])
        self.assertEqual(1, steady["app_attach_events"])
        self.assertEqual(0, steady["app_detach_events"])
        self.assertEqual(["codec#a"], list(steady["queue_by_identity"]))


if __name__ == "__main__":
    unittest.main()
