"""Recompute paired retrieval metrics from frozen evidence identities and captured reports.

Expects A/APRIME/B/C *-answers.json only; Mode D / B_CONTEXT are not included in summary aggregation.
"""
import argparse
import hashlib
import json
import math
import statistics
from pathlib import Path

from compare import compare


def identity(evidence):
    return evidence["documentId"], evidence.get("contentHash", evidence.get("hash")), evidence["chunkIndex"]


def aggregate(dataset, report):
    samples = {sample["id"]: sample for sample in dataset["cases"]}
    if len(report["rows"]) != len(samples) or {row["id"] for row in report["rows"]} != set(samples):
        raise ValueError("Report does not cover the frozen dataset")
    groups = {}
    for group in ("all", "points", "coupons", "materials"):
        rows = [row for row in report["rows"] if group == "all" or row.get("group") == group]
        if not rows:
            continue
        comparable = [row for row in rows if row.get("retrievalComparable", True)]
        coverage, candidates, reciprocal, noise = [], [], [], []
        for row in comparable:
            expected = {identity(evidence) for evidence in samples[row["id"]]["referenceEvidence"]}
            selected = [identity(evidence) for evidence in row["selected"]]
            if not expected:
                continue
            actual = len(expected.intersection(selected)) / len(expected)
            if not math.isclose(actual, row["evidenceRecall"]):
                raise ValueError("Reported evidence metric differs: " + row["id"])
            coverage.append(actual)
            pool = {identity(evidence) for route in row.get("routeCandidates", {}).values() for evidence in route}
            candidates.append(len(expected.intersection(pool)) / len(expected))
            reciprocal.append(next((1 / rank for rank, evidence in enumerate(selected, 1) if evidence in expected), 0))
            noise.append(len(expected.intersection(selected)) / len(selected) if selected else 0)
        metric_rows = comparable or rows
        times = sorted(row["totalMs"] for row in metric_rows)
        first_text = [row["firstTextMs"] for row in metric_rows if row.get("firstTextMs") is not None]
        groups[group] = {
            "cases": len(rows), "retrievalComparableCases": len(comparable),
            "annotatedCases": len(coverage),
            "candidateEvidenceRecall": statistics.mean(candidates) if candidates else None,
            "contextEvidenceRecall": statistics.mean(coverage) if coverage else None,
            "annotatedEvidencePrecision": statistics.mean(noise) if noise else None,
            "selectedContextMRR": statistics.mean(reciprocal) if reciprocal else None,
            "totalP50Ms": statistics.median(times), "totalP95Ms": times[math.ceil(len(times) * .95) - 1],
            "firstTextP50Ms": statistics.median(first_text) if first_text else None,
            "meanEstimatedContextTokens": statistics.mean(row["estimatedContextTokens"] for row in metric_rows),
            "rerankSuccess": sum(row.get("rerankStatus") == "success" for row in metric_rows),
            "forbiddenMaterialLeaks": sum(1 for row in comparable if row.get("forbiddenMaterialLeak") is True),
            "errors": sum(row["status"] != "success" for row in rows),
        }
    return groups


def review_summary(dataset, reports, review):
    if review.get("reviewType") != "ai_source_review":
        raise ValueError("Review type must explicitly identify AI source review")
    expected = {(mode, sample["id"]) for mode in reports for sample in dataset["cases"]}
    actual = [(row["mode"], row["id"]) for row in review["rows"]]
    if len(actual) != len(set(actual)) or set(actual) != expected:
        raise ValueError("Review does not cover exactly the paired reports")
    if any(row["verdict"] not in ("supported", "issue", "needs_review") for row in review["rows"]):
        raise ValueError("Unknown review verdict")
    return {"reviewer": review["reviewer"], "reviewType": review["reviewType"],
            "independentHumanReview": "pending",
            "counts": {mode: {verdict: sum(row["mode"] == mode and row["verdict"] == verdict for row in review["rows"])
                              for verdict in ("supported", "issue", "needs_review")} for mode in reports},
            "issues": [row for row in review["rows"] if row["verdict"] != "supported"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("reports", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reviews", type=Path)
    args = parser.parse_args()
    dataset_bytes = args.dataset.read_bytes()
    dataset = json.loads(dataset_bytes)
    reports = {mode: json.loads((args.reports / f"{mode}-answers.json").read_text(encoding="utf-8"))
               for mode in ("A", "APRIME", "B", "C")}
    dataset_hash = hashlib.sha256(dataset_bytes).hexdigest()
    for report in reports.values():
        if report["datasetHash"] != dataset_hash:
            raise ValueError("Dataset bytes differ from captured reports")
    result = {"datasetVersion": dataset["datasetVersion"], "datasetHash": dataset_hash,
              "origin": dataset["origin"], "independentHumanReview": "pending",
              "comparisons": {mode: compare(reports["A"], report, strict=True)
                              for mode, report in reports.items()},
              "modes": {mode: aggregate(dataset, report) for mode, report in reports.items()},
              "limitations": ["AI-designed cases; not user traffic or an independent holdout",
                              "Do not use points+coupons scores to endorse course-material results (or vice versa)",
                              "Single run per mode; latency includes external model fluctuations",
                              "Candidate recall uses each mode's configured route depth, not a shared Recall@K",
                              "Annotated evidence precision measures agreement with labels, not all semantically relevant context",
                              "Answer quality requires separate semantic review; retrieval metrics are not answer accuracy",
                              "Routing/access/tool-path cases with empty authorization are archival only and excluded from materials evidence means"]}
    if args.reviews:
        result["answerReview"] = review_summary(dataset, reports, json.loads(args.reviews.read_text(encoding="utf-8")))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(json.dumps(result["modes"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
