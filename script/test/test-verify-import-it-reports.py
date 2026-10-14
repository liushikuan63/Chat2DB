import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("verifier", Path(__file__).with_name("verify-import-it-reports.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class ReportGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def reports(self, change=None):
        for name, minimum in verifier.EXPECTED.items():
            attrs = dict(name="example." + name, tests=str(minimum), failures="0", errors="0", skipped="0")
            if change:
                change(name, attrs)
            ET.ElementTree(ET.Element("testsuite", attrs)).write(self.root / ("TEST-example." + name + ".xml"))

    def test_accepts_complete_executed_gate(self):
        self.reports()
        verifier.verify(self.root)

    def test_rejects_missing_reports(self):
        with self.assertRaisesRegex(ValueError, "Missing"):
            verifier.verify(self.root)

    def test_rejects_skipped_failed_errored_or_underexecuted_suite(self):
        for key in ("skipped", "failures", "errors", "tests"):
            with self.subTest(key=key):
                self.reports(lambda name, attrs: attrs.update({key: "1"}) if name == "MySQLTaskRoundTripIT" else None)
                with self.assertRaisesRegex(ValueError, "rejected"):
                    verifier.verify(self.root)


if __name__ == "__main__":
    unittest.main()
