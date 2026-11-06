import unittest

from report import aggregate, review_summary


class ReportTest(unittest.TestCase):
    def test_matching_chunk_numbers_in_different_documents_are_not_evidence(self):
        expected = {"documentId": "points", "contentHash": "hash", "chunkIndex": 1}
        wrong_document = {"documentId": "coupons", "hash": "hash", "chunkIndex": 1}
        dataset = {"cases": [{"id": "one", "referenceEvidence": [expected]}]}
        report = {"rows": [{"id": "one", "group": "points", "selected": [wrong_document],
                            "routeCandidates": {"vector": [expected]}, "evidenceRecall": 0,
                            "totalMs": 10, "firstTextMs": 5, "estimatedContextTokens": 20,
                            "status": "success"}]}
        result = aggregate(dataset, report)["all"]
        self.assertEqual(result["candidateEvidenceRecall"], 1)
        self.assertEqual(result["contextEvidenceRecall"], 0)
        self.assertEqual(result["selectedContextMRR"], 0)
        self.assertEqual(result["firstTextP50Ms"], 5)

    def test_no_answer_does_not_enter_evidence_denominator(self):
        dataset = {"cases": [{"id": "one", "referenceEvidence": []}]}
        report = {"rows": [{"id": "one", "group": "points", "selected": [],
                            "routeCandidates": {}, "evidenceRecall": None, "totalMs": 10,
                            "firstTextMs": None, "estimatedContextTokens": 0, "status": "success"}]}
        result = aggregate(dataset, report)["all"]
        self.assertEqual(result["annotatedCases"], 0)
        self.assertIsNone(result["contextEvidenceRecall"])

    def test_review_must_cover_reports_without_duplicate_rows(self):
        dataset = {"cases": [{"id": "one"}]}
        review = {"reviewer": "AI", "reviewType": "ai_source_review", "rows": [
            {"mode": "A", "id": "one", "verdict": "issue", "reason": "unsupported claim"}]}
        self.assertEqual(review_summary(dataset, {"A": {}}, review)["counts"]["A"]["issue"], 1)
        review["rows"].append(review["rows"][0])
        with self.assertRaises(ValueError):
            review_summary(dataset, {"A": {}}, review)


if __name__ == "__main__":
    unittest.main()
