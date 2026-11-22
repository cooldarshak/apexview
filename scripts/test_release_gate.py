"""Prevent signing/release safety gates from silently disappearing from CI."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/pitwall-approved-release.yml"


class ReleaseWorkflowGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = WORKFLOW.read_text(encoding="utf-8")

    def test_release_is_opt_in_and_requires_hardware_approval(self):
        self.assertIn("workflow_dispatch:", self.source)
        self.assertNotRegex(self.source, r"(?m)^  push:")
        self.assertIn("physical_acceptance_confirmed == true", self.source)
        self.assertIn("environment: pitwall-hardware-accepted", self.source)

    def test_releases_exact_green_build_with_verified_owner_and_certificate(self):
        for required in (
            "test \"$conclusion\" = success",
            'test "$sha" = "$SOURCE_SHA"',
            'test "$workflow" = "Android debug APK"',
            "APK_SIGNING_CERT_SHA256",
            "apksigner",
            "sha256sum",
            "app.apexview_custom",
            "versionCode=",
        ):
            self.assertIn(required, self.source)


if __name__ == "__main__":
    unittest.main()
