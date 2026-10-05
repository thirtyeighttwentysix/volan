"""A published comparison must not silently omit or mix measured cases."""
import copy
import json
import tempfile
import unittest
from pathlib import Path

import report


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.results = json.loads((Path(__file__).parent / "results/jmh-2026-09-07.json").read_text(encoding="utf-8"))

    def test_original_report_remains_reproducible(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            table = report.render(self.results, output)
            self.assertIn("**Volan**", table)
            self.assertTrue((output / "read-latency.svg").exists())

    def test_refuses_missing_and_duplicate_cases(self):
        for results in (self.results[:-1], self.results + [self.results[0]]):
            with self.assertRaises(ValueError):
                report.validate(results)

    def test_refuses_invalid_metrics_and_smoke_runs(self):
        for field, value in (("score", float("nan")), ("scoreError", float("inf")), ("scoreUnit", "ms/op")):
            results = copy.deepcopy(self.results)
            results[0]["primaryMetric"][field] = value
            with self.assertRaises(ValueError):
                report.validate(results)
        results = copy.deepcopy(self.results)
        results[0]["forks"] = 1
        with self.assertRaises(ValueError):
            report.validate(results)

    def test_refuses_different_jvms_and_wrong_threads(self):
        for field, value in (("jdkVersion", "another JVM"), ("threads", 4)):
            results = copy.deepcopy(self.results)
            results[0][field] = value
            with self.assertRaises(ValueError):
                report.validate(results)

    def test_extended_suite_requires_all_workloads(self):
        extended = []
        for workload, (_, _, threads) in report.SCENARIOS.items():
            for original in self.results:
                result = copy.deepcopy(original)
                result["benchmark"] = workload
                result["threads"] = threads
                extended.append(result)
        self.assertEqual(40, len(report.validate(extended)))
        with self.assertRaises(ValueError):
            report.validate(extended[:-1])
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            report.render(extended, output)
            self.assertEqual(4, len(list(output.glob("*.svg"))))


if __name__ == "__main__":
    unittest.main()
