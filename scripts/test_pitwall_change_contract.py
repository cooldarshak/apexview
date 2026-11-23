"""Regression tests for the pre-build coordinated-change contract."""
import unittest

from pitwall_change_contract import is_production, is_test, validate_change_set, PLAN


class CoordinatedChangeContractTests(unittest.TestCase):
    def test_production_without_plan_or_test_fails_both_conditions(self):
        issues = validate_change_set(["app/src/main/java/app/apexview/Viewer.kt"])
        self.assertEqual(2, len(issues))

    def test_plan_and_test_are_both_required(self):
        source = "app/src/main/java/app/apexview/Viewer.kt"
        test = "app/src/test/java/app/apexview/ViewerTest.kt"
        self.assertEqual(1, len(validate_change_set([source, PLAN])))
        self.assertEqual(1, len(validate_change_set([source, test])))
        self.assertEqual([], validate_change_set([source, PLAN, test]))

    def test_workflow_changes_are_subject_to_same_contract(self):
        self.assertTrue(is_production(".github/workflows/android-debug-apk.yml"))
        self.assertEqual(2, len(validate_change_set([".github/workflows/android-debug-apk.yml"])))

    def test_script_changes_and_test_discovery(self):
        self.assertTrue(is_production("scripts/pitwall_change_contract.py"))
        self.assertTrue(is_test("scripts/test_pitwall_change_contract.py"))
        self.assertTrue(is_test("web/cast/receiver.test.mjs"))
        self.assertFalse(is_production("scripts/test_pitwall_change_contract.py"))

    def test_doc_only_or_test_only_does_not_trigger_an_apk_contract(self):
        self.assertEqual([], validate_change_set([PLAN, "README.md"]))
        self.assertEqual([], validate_change_set(["app/src/test/java/Test.kt"]))

    def test_test_and_plan_without_any_production_change_do_not_fail(self):
        self.assertEqual([], validate_change_set([PLAN, "scripts/test_other.py"]))

    def test_changes_across_phone_tv_cast_require_one_coordinated_ledger(self):
        files = ["app/src/main/java/app/apexview/Viewer.kt",
                 "app/src/main/java/app/apexview/cast/local/PitwallReceiverActivity.kt",
                 "app/src/test/java/app/apexview/CastTest.kt", PLAN]
        self.assertEqual([], validate_change_set(files))


if __name__ == "__main__":
    unittest.main()
