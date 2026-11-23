import tempfile
import unittest
from pathlib import Path
from pitwall_acceptance_matrix import Case, validate_groups, MIN_ENDURANCE_S


class EnduranceMatrixTest(unittest.TestCase):
    def source(self, **overrides):
        value = dict(label="s22-off-1x", overlay="OFF", speed=1.0,
                     device="SM-S908E", build="1.0.2000",
                     content_id="archive-session-42", source_profile="50fps-540p-4feeds",
                     decoder_mode="SINGLE", protection="CLEAR_HEVC",
                     delivery="PHONE", feed_count=4, display_hz=120.0,
                     thermal_start=0, thermal_end=1,
                     app_log="session.log", timeline_csv="trace.csv")
        value.update(overrides)
        return Case.parse(value, Path("/tmp"))

    def test_requires_eight_matching_scenarios_per_real_device_and_profile(self):
        rows = [self.source(speed=speed, overlay=overlay,
                            label=f"{speed}-{overlay}")
                for speed in (1.0, 1.5) for overlay in ("OFF", "MAP", "TOWER", "BOTH")]
        self.assertEqual(1, len(validate_groups(rows)))
        with self.assertRaisesRegex(ValueError, "Incomplete matching"):
            validate_groups(rows[:-1])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            validate_groups(rows + rows[0:1])

    def test_fails_closed_on_mismatched_actual_feed_quality_and_temperature(self):
        rows = [self.source(speed=speed, overlay=overlay, label=f"{speed}-{overlay}")
                for speed in (1.0, 1.5) for overlay in ("OFF", "MAP", "TOWER", "BOTH")]
        rows[-1] = self.source(speed=1.5, overlay="BOTH", label="other-quality",
                               source_profile="50fps-720p-4feeds")
        with self.assertRaisesRegex(ValueError, "Incomplete matching"):
            validate_groups(rows)
        rows[-1] = self.source(speed=1.5, overlay="BOTH", label="hot",
                               thermal_start=4, thermal_end=4)
        with self.assertRaisesRegex(ValueError, "Thermal excursion"):
            validate_groups(rows)

    def test_does_not_accept_invented_modes_or_missing_capture_metadata(self):
        with self.assertRaisesRegex(ValueError, "Invalid playback"):
            self.source(decoder_mode="SINGLE_PLUS_HIDDEN_DECODERS")
        with self.assertRaisesRegex(ValueError, "Invalid playback"):
            self.source(protection="DRM_BYPASS")
        with self.assertRaisesRegex(ValueError, "Missing physical-run"):
            Case.parse({"overlay": "OFF"}, Path("/tmp"))
        self.assertEqual(600, MIN_ENDURANCE_S)


if __name__ == "__main__":
    unittest.main()
