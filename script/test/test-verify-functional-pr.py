import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import os
import unittest

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("functional_gate", Path(__file__).with_name("verify-functional-pr.py"))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class FunctionalGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "-q")
        (self.repo / "existing.java").write_text("class Existing {}\n", encoding="utf-8")
        self.commit()
        self.base = self.git("rev-parse", "HEAD")
        (self.repo / "feature.java").write_text("class Feature {}\n", encoding="utf-8")
        self.commit()
        self.head = self.git("rev-parse", "HEAD")
        self.tree = self.git("rev-parse", "HEAD^{tree}")
        self.reports = self.repo / "reports"
        self.reports.mkdir()
        self.report = self.reports / "suite.xml"
        self.report.write_text('<testsuite name="RequiredTest" tests="1" failures="0" errors="0" skipped="0"/>', encoding="utf-8")
        self.contract = {"base": self.base, "head": self.head, "functions": ["feature behavior"],
                         "entrypoints": ["Feature.run"], "required_paths": ["feature.java"],
                         "production_review_target": 10, "total_review_target": 10, "required_suites": ["RequiredTest"]}
        self.evidence = {"tested_tree": self.tree, "report_root": str(self.reports), "reports": {"RequiredTest": "suite.xml"},
                         "command_exit": 0, "run_started_epoch": time.time() - 1}

    def git(self, *args):
        result = subprocess.run(["git", "-C", str(self.repo), *args], check=True, text=True, capture_output=True)
        return result.stdout.strip()

    def commit(self):
        self.git("add", "--", "existing.java" if not (self.repo / "feature.java").exists() else "feature.java")
        self.git("-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture")

    def verify(self): return GATE.verify(self.repo, self.contract, self.evidence)

    def test_complete_contract_and_current_tree_pass(self):
        self.assertEqual(1, self.verify()["diff"]["production"]["added"])

    def test_missing_runtime_dependency_is_rejected(self):
        self.contract["required_paths"].append("missing.java")
        with self.assertRaises(Exception): self.verify()

    def test_stale_tree_evidence_is_rejected(self):
        self.evidence["tested_tree"] = "unrelated-tree"
        with self.assertRaises(ValueError): self.verify()

    def test_old_report_and_failed_command_are_rejected(self):
        os.utime(self.report, (1, 1))
        with self.assertRaises(ValueError): self.verify()
        os.utime(self.report, None)
        self.evidence["command_exit"] = 1
        with self.assertRaises(ValueError): self.verify()

    def test_missing_zero_failed_or_skipped_reports_are_rejected(self):
        for attributes in (None, 'tests="0" failures="0" errors="0" skipped="0"',
                           'tests="1" failures="1" errors="0" skipped="0"', 'tests="1" failures="0" errors="0" skipped="1"'):
            with self.subTest(attributes=attributes):
                if attributes is None: self.report.unlink(missing_ok=True)
                else: self.report.write_text('<testsuite name="RequiredTest" ' + attributes + '/>', encoding="utf-8")
                with self.assertRaises(Exception): self.verify()

    def test_oversized_diff_requires_an_explanation(self):
        self.contract["production_review_target"] = 0
        with self.assertRaises(ValueError): self.verify()
        self.contract["size_explanation"] = "Required implementation is indivisible"
        self.assertEqual(1, self.verify()["changed_lines"])


if __name__ == "__main__": unittest.main()
