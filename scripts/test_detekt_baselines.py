"""Contract tests for the debt ratchet, independent of Kotlin finding spelling."""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "baseline_guard", Path(__file__).with_name("check-detekt-baselines.py")
)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


def baseline(*findings, manual=""):
    return (
        "<SmellBaseline><ManuallySuppressedIssues>" + manual
        + "</ManuallySuppressedIssues><CurrentIssues>"
        + "".join(f"<ID>{finding}</ID>" for finding in findings)
        + "</CurrentIssues></SmellBaseline>"
    )


class BaselineRatchetTest(unittest.TestCase):
    def test_removing_resolved_debt_is_allowed(self):
        self.assertEqual(set(), guard.additions(baseline("old", "resolved"), baseline("old")))

    def test_new_debt_is_rejected(self):
        self.assertEqual({("CurrentIssues", "new")}, guard.additions(baseline("old"), baseline("old", "new")))

    def test_manual_suppression_cannot_hide_new_debt(self):
        self.assertEqual(
            {("ManuallySuppressedIssues", "new")},
            guard.additions(baseline(), baseline(manual="<ID>new</ID>")),
        )

    def test_new_module_must_start_clean(self):
        self.assertEqual(set(), guard.additions(baseline(), baseline()))
        self.assertTrue(guard.additions(baseline(), baseline("new module debt")))


if __name__ == "__main__":
    unittest.main()
