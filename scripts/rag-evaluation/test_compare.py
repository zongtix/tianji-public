import copy
import importlib.util
import json
import re
import unittest
from pathlib import Path


spec = importlib.util.spec_from_file_location("rag_compare", Path(__file__).with_name("compare.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def report():
    return {"datasetVersion": "v1", "dataVersion": "hash", "promptHash": "prompt",
            "chatModel": "model", "answersEnabled": True, "codeVersion": "commit",
            "properties": {}, "rows": [
                {"id": "one", "status": "success", "evidenceRecall": 0.5, "totalMs": 10},
                {"id": "two", "status": "success", "evidenceRecall": None, "totalMs": 20}]}


class CompareTest(unittest.TestCase):
    def test_paired_coverage_excludes_unannotated(self):
        baseline = report()
        candidate = copy.deepcopy(baseline)
        candidate["rows"][0]["evidenceRecall"] = 1.0
        result = module.compare(baseline, candidate)
        self.assertEqual(result["annotatedCases"], 1)
        self.assertEqual(result["meanDelta"], 0.5)
        self.assertEqual(result["baselineMedianMs"], 15)

    def test_rejects_mismatch_and_degraded_runs(self):
        for mutate in (lambda r: r.update(datasetVersion="v2"),
                       lambda r: r["rows"].pop(),
                       lambda r: r["rows"][0].update(status="error"),
                       lambda r: r["rows"][0].update(normalModeExecution=False),
                       lambda r: r["rows"][0].update(evidenceRecall=None),
                       lambda r: r["rows"].append(r["rows"][0])):
            candidate = copy.deepcopy(report())
            mutate(candidate)
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                module.compare(report(), candidate)

    def test_warns_when_provenance_is_missing(self):
        baseline = report()
        del baseline["promptHash"]
        result = module.compare(baseline, copy.deepcopy(baseline))
        self.assertTrue(any("promptHash" in warning for warning in result["warnings"]))

    def test_coupon_draft_references_existing_sections(self):
        repo = Path(__file__).resolve().parents[2]
        dataset = json.loads((repo / "docs/rag-evaluation/coupon-cases-draft.json").read_text(encoding="utf-8"))
        source = dataset["source"]
        headings = set(re.findall(r"^## (.+)$", (repo / source).read_text(encoding="utf-8"), re.MULTILINE))
        self.assertEqual(dataset["status"], "draft")
        self.assertEqual(len(dataset["cases"]), 12)
        self.assertEqual(len({case["id"] for case in dataset["cases"]}), len(dataset["cases"]))
        for case in dataset["cases"]:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["split"], "draft")
                self.assertTrue(case["referenceEvidence"])
                for evidence in case["referenceEvidence"]:
                    self.assertEqual(evidence["sourceFile"], source)
                    self.assertIn(evidence["section"], headings)
                    self.assertNotIn("chunkIndex", evidence)

    def test_strict_comparison_requires_matching_execution_and_index(self):
        baseline = report()
        baseline.update(datasetHash="dataset", indexSnapshot={"digest": "index"}, runGroup="batch",
                        generationOptions={"temperature": 0}, indexUnchanged=True)
        self.assertEqual(module.compare(baseline, copy.deepcopy(baseline), strict=True)["meanDelta"], 0)
        for field, value in (("indexSnapshot", {"digest": "different"}), ("datasetHash", "different"),
                             ("indexUnchanged", False), ("runGroup", None)):
            candidate = copy.deepcopy(baseline)
            candidate[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                module.compare(baseline, candidate, strict=True)


if __name__ == "__main__":
    unittest.main()
