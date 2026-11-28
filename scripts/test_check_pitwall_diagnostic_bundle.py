import pathlib
import tempfile
import unittest
from unittest.mock import patch
from check_pitwall_diagnostic_bundle import verify, digest


class DiagnosticBundleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.meta = dict(package="app.apexview_custom", pid="123", end_pid="123",
                         mode="system", start_epoch="1", end_epoch="2",
                         start_status="DETACHED", stop_status="STOPPED", export_status="0")
        for name in ("apk-start.sha256", "apk-end.sha256"):
            (self.root / name).write_text("a" * 64 + "  base.apk\n")
        trace = self.root / "trace.pftrace"
        trace.write_bytes(b"test-only")
        (self.root / "trace.sha256").write_text(digest(trace) + "  trace.pftrace\n")

    def save(self):
        (self.root / "manifest.txt").write_text("".join(f"{k}={v}\n" for k,v in self.meta.items()))

    def test_system_requires_target_table_coverage(self):
        self.save()
        with self.assertRaisesRegex(ValueError, "processor"):
            verify(self.root)
        with patch("check_pitwall_diagnostic_bundle.count_sql", side_effect=[10, 0]):
            with self.assertRaisesRegex(ValueError, "app_frame_timeline"):
                verify(self.root, "processor")
        with patch("check_pitwall_diagnostic_bundle.count_sql", return_value=10):
            self.assertIn("TARGET_TABLE_COVERAGE", verify(self.root, "processor"))

    def test_changed_pid_or_trace_rejected(self):
        self.meta["end_pid"] = "124"
        self.save()
        with self.assertRaisesRegex(ValueError, "PID"):
            verify(self.root)
        self.meta["end_pid"] = "123"
        self.save()
        (self.root / "trace.pftrace").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "trace"):
            verify(self.root)

    def test_early_stop_rejected(self):
        self.meta["stop_status"] = "EARLY_OR_FAILED"
        self.save()
        with self.assertRaisesRegex(ValueError, "stop"):
            verify(self.root)

    def test_unprofiled_requires_actual_pss(self):
        self.meta.update(mode="memory", start_status="UNPROFILED", stop_status="UNPROFILED")
        self.save()
        for name in ("meminfo-start.txt", "meminfo-end.txt"):
            (self.root / name).write_text("Permission denied")
        with self.assertRaisesRegex(ValueError, "PSS"):
            verify(self.root)
        for name in ("meminfo-start.txt", "meminfo-end.txt"):
            (self.root / name).write_text("TOTAL PSS: 123")
        self.assertIn("UNPROFILED_SNAPSHOTS_ONLY", verify(self.root))
