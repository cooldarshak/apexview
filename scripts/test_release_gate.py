"""Verify both manual release entry points enforce the same hard production gate."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OFFICIAL = ROOT / ".github/workflows/official-release.yml"
LEGACY = ROOT / ".github/workflows/pitwall-approved-release.yml"


class ReleaseWorkflowGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = OFFICIAL.read_text(encoding="utf-8")
        cls.legacy = LEGACY.read_text(encoding="utf-8")

    def test_release_is_explicit_and_requires_actual_hardware_approval(self):
        for source in (self.source, self.legacy):
            self.assertIn("workflow_dispatch:", source)
            self.assertNotRegex(source, r"(?m)^  push:")
        self.assertIn("environment: pitwall-production", self.source)
        self.assertIn("pitwall-hardware-accepted", self.source)
        self.assertIn("hardware_acceptance_issue", self.source)
        self.assertIn("16 gates", self.source)
        self.assertIn("physical_acceptance_confirmed == true", self.legacy)
        self.assertIn("uses: ./.github/workflows/official-release.yml", self.legacy)
        self.assertIn("secrets: inherit", self.legacy)
        self.assertNotIn("gh release create", self.legacy)

    def test_releases_exact_green_source_with_verified_owner_and_certificate(self):
        for required in (
            'test "$(jq -r .conclusion <<< "$run_json")" = "success"',
            'test "$(jq -r .head_sha <<< "$run_json")" = "$ACCEPTED_SHA"',
            'test "$(jq -r .name <<< "$run_json")" = "Android debug APK"',
            'test "$(jq -r .commit.sha <<< "$branch_json")" = "$ACCEPTED_SHA"',
            "PITWALL_EXPECTED_SIGNER_SHA256",
            "APK_SIGNING_KEYSTORE_BASE64",
            "apksigner",
            "sha256sum",
            'test "$actual" = "$expected"',
            "PITWALL_VERIFIED_DEBUG_RUN_NUMBER",
            "app/build/outputs/apk/release",
            '--repo "$GITHUB_REPOSITORY"',
            "github.ref_name",
        ):
            if required == "github.ref_name":
                self.assertIn(required, self.legacy)
            else:
                self.assertIn(required, self.source)


if __name__ == "__main__":
    unittest.main()
