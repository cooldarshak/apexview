import tempfile
import unittest
import zipfile
from pathlib import Path

from validate_pitwall_memory_release import metadata, extract_bundle, require_matrix


class PhysicalMemoryReleaseTest(unittest.TestCase):
    def test_pinned_evidence_metadata_and_duplicate_rejection(self):
        body = ("Memory baseline debug run: 913\nMemory baseline commit: " + "a"*40 +
                "\nMemory evidence asset ID: 123\nMemory evidence SHA256: " + "b"*64)
        self.assertEqual("913", metadata(body)["baseline_run"])
        for invalid in (body + "\nMemory evidence asset ID: 456", body.replace("123", "https://example.com")):
            with self.assertRaises(ValueError):
                metadata(invalid)

    def test_archive_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bundle = root / "evidence.zip"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("../outside", "not allowed")
            with self.assertRaises(ValueError):
                extract_bundle(bundle, root / "safe")

    def test_incomplete_matrix_rejected(self):
        with self.assertRaisesRegex(ValueError, "Missing paired route"):
            require_matrix({"routes": []})
