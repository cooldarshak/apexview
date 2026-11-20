import unittest

from analyze_pitwall_overlay_ab import Window, percentages


class OverlayABTest(unittest.TestCase):
    def test_accepts_valid_windows_and_rejects_unsafe_or_reversed_intervals(self):
        self.assertEqual(Window("MAP", 20.0, 40.0), Window.parse("map:20:40"))
        for value in ("MAP:20:20", "OFF:40:20", "BAD:0:10", "MAP:1", "MAP:-1:20"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                Window.parse(value)

    def test_deadline_percentage_is_not_video_drop_percentage(self):
        result = percentages({"frames": 100, "missed": 52, "late": 3, "dropped": 0})
        self.assertEqual(52.0, result["missed"])
        self.assertEqual(3.0, result["late"])
        self.assertEqual(0.0, result["dropped"])

    def test_missing_frame_timeline_has_no_fabricated_percentages(self):
        self.assertIsNone(percentages({"frames": 0, "missed": 0,
                                      "late": 0, "dropped": 0})["missed"])


if __name__ == "__main__":
    unittest.main()
