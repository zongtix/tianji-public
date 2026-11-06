"""Compare paired, previously captured RAG reports without calling services."""
import argparse
import json
import statistics
from pathlib import Path


def compare(baseline, candidate, strict=False):
    fields = ("datasetVersion", "dataVersion", "promptHash", "chatModel", "answersEnabled")
    differences = [field for field in fields if baseline.get(field) != candidate.get(field)]
    if differences:
        raise ValueError("Reports differ in: " + ", ".join(differences))
    frozen_fields = ("datasetHash", "indexSnapshot", "runGroup", "codeVersion", "generationOptions", "properties")
    if strict:
        if any(baseline.get(field) is None or baseline.get(field) == "" or candidate.get(field) is None or candidate.get(field) == ""
               for field in fields + frozen_fields):
            raise ValueError("Strict comparison requires complete dataset, index and execution fingerprints")
        if any(baseline[field] != candidate[field] for field in frozen_fields):
            raise ValueError("Strict comparison fingerprints differ")
        if baseline.get("indexUnchanged") is not True or candidate.get("indexUnchanged") is not True:
            raise ValueError("Strict comparison requires an unchanged index in both runs")
    warnings = [f"{field} is missing; comparability cannot be established"
                for field in fields if baseline.get(field) is None or baseline.get(field) == ""]
    if baseline.get("codeVersion") != candidate.get("codeVersion"):
        warnings.append("codeVersion differs; changes other than the retrieval mode may affect results")
    if not baseline.get("codeVersion") or not candidate.get("codeVersion"):
        warnings.append("codeVersion is missing; source equivalence cannot be established")
    if baseline.get("properties") != candidate.get("properties"):
        warnings.append("connection or model properties differ; inspect the reports")
    if baseline.get("generationOptions") != candidate.get("generationOptions"):
        warnings.append("generationOptions differ; answer and latency comparisons may be confounded")
    warnings.append("Case IDs do not prove identical questions, labels, corpus or index snapshots; verify separately")

    def index(report):
        rows = report.get("rows")
        if not isinstance(rows, list) or not rows:
            raise ValueError("Each report must contain nonempty rows")
        result = {}
        for row in rows:
            case_id = row.get("id")
            if not case_id or case_id in result:
                raise ValueError("Missing or duplicate case id")
            result[case_id] = row
        return result

    left, right = index(baseline), index(candidate)
    if left.keys() != right.keys():
        raise ValueError("Reports contain different case IDs")
    if any(left[case_id].get("split") != right[case_id].get("split") for case_id in left):
        raise ValueError("Case splits differ")
    failed = [case_id for case_id in left
              if left[case_id].get("status") != "success" or right[case_id].get("status") != "success"]
    if failed:
        raise ValueError("Unsuccessful or missing status in cases: " + ", ".join(failed))
    degraded = [case_id for case_id in left if any(
        (row.get("failedRoutes") or row.get("normalModeExecution") is False)
        and row.get("retrievalComparable", True)
        for row in (left[case_id], right[case_id]))]
    if degraded:
        raise ValueError("Degraded or non-normal executions: " + ", ".join(degraded))
    mismatched = [case_id for case_id in left if
                  (left[case_id].get("evidenceRecall") is None) != (right[case_id].get("evidenceRecall") is None)]
    if mismatched:
        raise ValueError("Evidence denominator differs for cases: " + ", ".join(mismatched))
    annotated = [case_id for case_id in left if left[case_id].get("evidenceRecall") is not None]
    for case_id in annotated:
        if any(not isinstance(row["evidenceRecall"], (int, float)) or isinstance(row["evidenceRecall"], bool)
               or not 0 <= row["evidenceRecall"] <= 1 for row in (left[case_id], right[case_id])):
            raise ValueError("Invalid evidence coverage: " + case_id)
    deltas = [{"id": case_id, "baseline": left[case_id]["evidenceRecall"],
               "candidate": right[case_id]["evidenceRecall"],
               "delta": right[case_id]["evidenceRecall"] - left[case_id]["evidenceRecall"]}
              for case_id in annotated]
    return {"datasetVersion": baseline.get("datasetVersion"), "cases": len(left),
            "annotatedCases": len(annotated), "warnings": warnings,
            "metric": "mean per-case annotated evidence coverage in final context; not answer accuracy",
            "baselineMean": statistics.mean(item["baseline"] for item in deltas) if deltas else None,
            "candidateMean": statistics.mean(item["candidate"] for item in deltas) if deltas else None,
            "meanDelta": statistics.mean(item["delta"] for item in deltas) if deltas else None,
            "baselineMedianMs": median_latency(left), "candidateMedianMs": median_latency(right),
            "changedCases": [item for item in deltas if item["delta"] != 0]}


def median_latency(rows):
    values = [row.get("totalMs") for row in rows.values()]
    if any(not isinstance(value, (int, float)) or isinstance(value, bool) or value < 0 for value in values):
        return None
    return statistics.median(values)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()
    try:
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        candidate = json.loads(args.candidate.read_text(encoding="utf-8"))
        print(json.dumps(compare(baseline, candidate, strict=args.strict), ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Comparison refused: {error}\n")


if __name__ == "__main__":
    main()
