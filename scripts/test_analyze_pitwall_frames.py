import unittest

from analyze_pitwall_frames import percentile, summary


class FrameTimelineReportTest(unittest.TestCase):
    def row(self, token, ts, name="/system/bin/surfaceflinger", status="On-time Present"):
        return {"ts": ts, "dur": 1_000_000, "display_frame_token": str(token),
                "surface_frame_token": "0" if "surfaceflinger" in name else "10",
                "present_type": status, "jank_type": "None", "layer_name": "",
                "process_name": name}

    def test_separates_app_late_frames_from_display_and_deduplicates_sf_tokens(self):
        rows = [self.row(1, 0), self.row(1, 0), self.row(2, 8_333_333, status="Late Present"),
                self.row(3, 16_666_666, status="Dropped Frame"),
                self.row(2, 8_333_333, name="app.apexview_custom", status="Late Present")]
        result = summary(rows)
        self.assertEqual(3, result["display_tokens"])
        self.assertEqual(3, result["display"]["frames"])
        self.assertEqual(1, result["app"]["late"])
        self.assertEqual(1, result["display"]["dropped"])
        self.assertEqual(2, result["display_interval_bins"]["<13ms"])

    def test_independent_video_surface_not_attributed_to_ui(self):
        rows = [self.row(1, 0), self.row(2, 20_000_000)]
        video = self.row(2, 10_000_000, name="app.apexview_custom")
        video["layer_name"] = "SurfaceView[app.apexview_custom/app.apexview.MainActivity]"
        app = self.row(2, 10_000_000, name="app.apexview_custom", status="Late Present")
        app["layer_name"] = "app.apexview_custom/app.apexview.MainActivity"
        app["surface_frame_token"] = "22"
        result = summary(rows + [video, dict(video), app])
        self.assertEqual(1, result["video"]["frames"])
        self.assertEqual(0, result["video"]["late"])
        self.assertEqual(1, result["app"]["late"])
        self.assertEqual(2, result["display_tokens"])

    def test_textureview_composed_into_ui_window(self):
        app = self.row(1, 0, name="app.apexview_custom")
        app["layer_name"] = "TextureView[app.apexview_custom]"
        result = summary([self.row(1, 0), app])
        self.assertEqual(0, result["video"]["frames"])
        self.assertEqual(1, result["app"]["frames"])

    def test_empty_or_single_frame_does_not_fabricate_video_fps(self):
        report = summary([self.row(12, 1_000_000)])
        self.assertIsNone(report["display_gap_p95_ms"])
        self.assertEqual(0, sum(report["display_interval_bins"].values()))
        self.assertEqual(20, percentile([10, 20], 95))


if __name__ == "__main__":
    unittest.main()
