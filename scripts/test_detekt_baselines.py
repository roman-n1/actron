"""Contract tests for the debt ratchet, independent of Kotlin finding spelling."""
import importlib.util
from html import escape
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
        + "".join(f"<ID>{escape(finding)}</ID>" for finding in findings)
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


class AuthorizedLoggingRelocationTest(unittest.TestCase):
    path = "config/detekt/baselines/actron-logging.xml"
    old = "TooGenericExceptionCaught:Plugin.kt:<no name provided>$e: Exception"
    new = "TooGenericExceptionCaught:LoggingDelivery.kt:LoggingDelivery$e: Exception"

    def test_exact_authorized_relocation_is_allowed(self):
        self.assertEqual(set(), guard.additions(baseline(self.old), baseline(self.new), self.path))

    def test_old_entry_must_be_removed(self):
        self.assertEqual(
            {("CurrentIssues", self.new)},
            guard.additions(baseline(self.old), baseline(self.old, self.new), self.path),
        )

    def test_source_debt_must_exist_at_review_base(self):
        self.assertEqual({("CurrentIssues", self.new)}, guard.additions(baseline(), baseline(self.new), self.path))

    def test_other_new_debt_is_still_rejected(self):
        self.assertEqual(
            {("CurrentIssues", "other finding")},
            guard.additions(baseline(self.old), baseline(self.new, "other finding"), self.path),
        )

    def test_relocation_is_scoped_to_one_module(self):
        self.assertEqual(
            {("CurrentIssues", self.new)},
            guard.additions(baseline(self.old), baseline(self.new), "config/detekt/baselines/other.xml"),
        )

    def test_manual_suppression_is_not_an_authorized_target(self):
        self.assertEqual(
            {("ManuallySuppressedIssues", self.new)},
            guard.additions(baseline(self.old), baseline(manual=f"<ID>{escape(self.new)}</ID>"), self.path),
        )

    def test_old_location_cannot_be_reintroduced_after_relocation(self):
        self.assertEqual(
            {("CurrentIssues", self.old)},
            guard.additions(baseline(self.new), baseline(self.old, self.new), self.path),
        )

    def test_any_other_target_is_rejected(self):
        target = self.new.replace("$e:", "$other:")
        self.assertEqual({("CurrentIssues", target)}, guard.additions(baseline(self.old), baseline(target), self.path))


if __name__ == "__main__":
    unittest.main()
