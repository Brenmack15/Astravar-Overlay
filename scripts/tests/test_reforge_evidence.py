import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("reforge_evidence", Path(__file__).parents[1] / "reforge_evidence.py")
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


class ReforgeEvidenceTests(unittest.TestCase):
    def test_absent_reports_do_not_become_passing_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual({}, evidence.summarize_results(Path(tmp)))

    def test_failed_and_skipped_cases_remain_distinct(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / "app/build/test-results/testDebugUnitTest"
            output.mkdir(parents=True)
            (output / "TEST-fixture.xml").write_text('<testsuite tests="3" failures="1" skipped="1" time="0.5"><testcase/><testcase><failure/></testcase><testcase><skipped/></testcase></testsuite>')
            report = evidence.summarize_results(root)["app:testDebugUnitTest"]
            self.assertEqual("failed", report["status"])
            self.assertEqual((1, 1, 1), (report["passed"], report["failures"], report["skipped"]))
            self.assertEqual(64, len(report["reports"][0]["sha256"]))

    def test_truncated_report_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            output = root / "rules/build/test-results/test"
            output.mkdir(parents=True)
            (output / "TEST-fixture.xml").write_text('<testsuite tests="2"><testcase/></testsuite>')
            with self.assertRaises(ValueError):
                evidence.summarize_results(root)
