"""Fail-closed release workflow contracts checked by the existing CI Python suite."""
import os
import pathlib
import re
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
OFFICIAL = ROOT / '.github/workflows/official-release.yml'
LEGACY = ROOT / '.github/workflows/pitwall-approved-release.yml'
KEYS = (
    'APK_SIGNING_KEYSTORE_BASE64',
    'APK_SIGNING_STORE_PASSWORD',
    'APK_SIGNING_KEY_ALIAS',
    'APK_SIGNING_KEY_PASSWORD',
    'SIGNER_SHA256',
)


class OfficialReleaseWorkflowTest(unittest.TestCase):
    def test_secret_gate_uses_actual_indirect_bash_expansion(self):
        text = OFFICIAL.read_text()
        match = re.search(
            r'(?ms)^          for key in APK_SIGNING_KEYSTORE_BASE64 .+?^          done$',
            text,
        )
        self.assertIsNotNone(match, 'The mandatory release secret gate is missing')
        block = 'set -euo pipefail\n' + match.group(0)
        complete_env = {'PATH': os.environ.get('PATH', '/usr/bin')}
        complete_env.update({name: 'dummy-nonsecret-value' for name in KEYS})
        ok = subprocess.run(['bash', '-c', block], capture_output=True, text=True, env=complete_env)
        self.assertEqual(0, ok.returncode, ok.stderr)
        for missing in KEYS:
            env = {name: value for name, value in complete_env.items() if name != missing}
            failed = subprocess.run(['bash', '-c', block], capture_output=True, text=True, env=env)
            self.assertNotEqual(0, failed.returncode, f'Missing {missing} was wrongly accepted')

    def test_all_entry_points_use_identical_verified_release_gate(self):
        official = OFFICIAL.read_text()
        legacy = LEGACY.read_text()
        self.assertIn('  workflow_call:', official)
        self.assertIn('uses: ./.github/workflows/official-release.yml', legacy)
        self.assertIn('secrets: inherit', legacy)
        self.assertIn('hardware_acceptance_issue:', legacy)
        self.assertIn('publish: ${{ inputs.publish }}', legacy)
        self.assertNotIn('gh release create', legacy)
        for marker in (
            'pitwall-hardware-accepted',
            'PITWALL_EXPECTED_SIGNER_SHA256',
            'accepted_debug_run_id',
            'accepted_commit_sha',
            'test "$actual" = "$expected"',
        ):
            self.assertIn(marker, official)


if __name__ == '__main__':
    unittest.main()
