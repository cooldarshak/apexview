import unittest

from validate_pitwall_acceptance import GATES, validate

SHA = "a" * 40
RUN = "45101037185"


def full_issue():
    matrix = ("Galaxy S22 Ultra TCL 55C655 Widevine L1 Widevine L3 "
              "clear HEVC SINGLE MULTIPLE Pitwall Cast closed captions Material 3")
    gates = "\n".join(
        f"GATE {gate}: PASS | evidence: https://github.com/cooldarshak/apexview/issues/123#gate-{index}"
        for index, gate in enumerate(GATES, start=1)
    )
    return f"Accepted commit: {SHA}\nAccepted debug run: {RUN}\n{matrix}\n{gates}\n"


class ReleaseAcceptanceValidationTest(unittest.TestCase):
    def test_valid_owner_reviewed_matrix(self):
        validate(full_issue(), SHA, RUN)
        self.assertEqual(16, len(GATES))

    def test_no_release_with_only_generic_sixteen_gate_claim(self):
        with self.assertRaises(ValueError):
            validate(f"Accepted commit: {SHA}\nAccepted debug run: {RUN}\n16 gates PASS", SHA, RUN)

    def test_missing_and_duplicate_individual_gates_are_rejected(self):
        lines = full_issue().splitlines()
        for data in ("\n".join(lines[:-1]), "\n".join(lines + [lines[-1]])):
            with self.assertRaises(ValueError):
                validate(data, SHA, RUN)

    def test_failed_gate_and_untrusted_evidence_are_rejected(self):
        for changed in (
            full_issue().replace("P0.2: PASS", "P0.2: PENDING"),
            full_issue().replace("https://github.com/", "http://github.com/", 1),
            full_issue().replace("https://github.com/", "https://example.com/", 1),
        ):
            with self.assertRaises(ValueError):
                validate(changed, SHA, RUN)

    def test_cannot_accept_other_commit_run_or_missing_device(self):
        for data in (
            full_issue().replace(f"Accepted commit: {SHA}", f"Accepted commit: {'b' * 40}"),
            full_issue().replace(f"Accepted debug run: {RUN}", "Accepted debug run: 1"),
            full_issue().replace("TCL 55C655", ""),
        ):
            with self.assertRaises(ValueError):
                validate(data, SHA, RUN)


if __name__ == "__main__":
    unittest.main()
