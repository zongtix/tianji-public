"""离线汇总受控固定案例；不访问模型或服务，不把 Codex 初审称为人工验收。"""
import argparse
import json
import math
import statistics
from pathlib import Path


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    repo = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=repo / "target/rag-optimization")
    parser.add_argument("--output", type=Path, default=repo / "docs/rag-evaluation/results")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    dataset = read(repo / "docs/rag-evaluation/cases.json")
    samples = {c["id"]: c for c in dataset["cases"]}
    reviews = read(repo / "docs/rag-evaluation/reviews.json")
    summary = {"datasetVersion": dataset["datasetVersion"], "configVersion": "rag-v1",
               "reviewVersion": reviews["reviewVersion"], "independentHumanReview": "pending",
               "modelJudgeCalibration": "not_performed", "modes": {}}
    selections = {}
    for mode in ("A", "APRIME", "B", "B_CONTEXT"):
        report = read(args.input / f"{mode}-answers.json")
        rows = report["rows"]
        assert len(rows) == len(samples) and {r["id"] for r in rows} == set(samples), "Incomplete evaluation"
        report["usageCollectionVersion"] = report.get("usageCollectionVersion", "v1-last-chat-observation-only")
        legacy_usage = report["usageCollectionVersion"].startswith("v1-")
        report["usageLimitations"] = "本次v1报告每例仅保留最后一次聊天模型回调用量；多轮改写用量可能被覆盖，embedding用量未采集，不能当作全链路总成本。" if legacy_usage else "按阶段分别记录聊天及embedding实际返回用量，缺失标为unavailable；未返回的字段不按估算补齐。"
        report["codeVersion"] = report.get("codeVersion", "0686f84-working-tree")
        report["codeProvenance"] = "本次真实运行使用未提交工作区；运行后补齐取消迟到错误丢弃、401/403终止识别、同步观测、用量缺失、评测清单及来源/模型身份字段。当前资料单一来源/模型，新增身份字段不改变该数据的去重或排序；最后补充由本地检查验证，未追加外部请求。"
        report["promptHash"] = report.get("promptHash")
        report["generationOptions"] = report.get("generationOptions", {
            "temperature": 0.0, "maxTokens": 2048, "toolChoice": "none", "thinking": "not_explicit_in_v1_runner"})
        report["promptProvenance"] = "v1运行器未记录执行时提示词哈希；共用系统提示来自当时Nacos，知识角色提示沿用资源或服务端覆盖，now固定2026-10-24 12:00:00。后续运行器已补promptHash，不能追溯声称本次已记录该哈希。"
        report["configVersion"] = "rag-v1"
        report["dataVersion"] = dataset["contentHash"]
        selections[mode] = {}
        for row in rows:
            row["stageMs"].pop("rag.request", None)  # v1 doFinally可能在下游完成后结束，总耗时用独立时钟。
            row["allAnnotatedEvidence"] = row.pop("allNecessaryEvidence", None)
            row["answerReview"] = reviews["overrides"].get(mode, {}).get(row["id"], {
                "status": reviews["default"], "reason": "已逐例对照公开来源和预期条件初审，未发现关键事实错误；仍待独立人工验收。"})
            selections[mode][row["id"]] = [d["id"] for d in row.get("selected", [])]
        comparable = [r for r in rows if r.get("evidenceRecall") is not None]
        latency = sorted(r["totalMs"] for r in rows)
        statuses = [r["answerReview"]["status"] for r in rows]
        stage_names = sorted({s for r in rows for s in r["stageMs"]})
        values = {"cases": len(rows), "transportErrors": sum(r["status"] != "success" for r in rows),
                  "degradedCases": sum(bool(r.get("failedRoutes")) for r in rows),
                  "annotatedCases": len(comparable),
                  "meanAnnotatedEvidenceRecall": statistics.mean(r["evidenceRecall"] for r in comparable),
                  "allAnnotatedEvidenceCases": sum(r["allAnnotatedEvidence"] for r in comparable),
                  "meanReciprocalRankAtSelectedContext": statistics.mean(r["reciprocalRank"] for r in comparable),
                  "totalP50Ms": statistics.median(latency), "totalP95Ms": latency[math.ceil(.95*len(latency))-1],
                  "firstTextP50Ms": statistics.median(r["firstTextMs"] for r in rows if r.get("firstTextMs") is not None),
                  "meanEstimatedContextTokens": statistics.mean(r.get("estimatedContextTokens", 0) for r in rows),
                  "stageMeanMs": {s: statistics.mean(r["stageMs"][s] for r in rows if s in r["stageMs"]) for s in stage_names},
                  "codexInitialReviewFails": statuses.count("fail"), "codexInitialReviewWarnings": statuses.count("warn"),
                  "independentHumanReview": "pending"}
        if legacy_usage:
            values["recordedLastChatTotalTokens"] = sum(v.get("totalTokens", 0) or 0 for r in rows for v in r.get("usage", {}).values() if isinstance(v, dict))
            values["fullPipelineUsage"] = "unavailable_v1_collection_incomplete"
        else:
            entries = [entry for r in rows for stage in r.get("usage", {}).values() for entry in stage]
            values["recordedProviderTotalTokens"] = sum((entry.get("usage") or {}).get("totalTokens", 0) or 0 for entry in entries)
            values["missingUsageCalls"] = sum(entry.get("status") != "provider_reported" for entry in entries)
            values["fullPipelineUsage"] = "provider_reported_fields_only"
        summary["modes"][mode] = values
        write(args.output / f"{mode}.json", report)
    summary["bAndContextIdenticalSelections"] = selections["B"] == selections["B_CONTEXT"]
    for mode in ("C", "D"):
        skipped = {"mode": mode, "status": "not_run", "reason": "缺少已核实的专用rerank模型、地域、权限、费用、完整接口与独立凭据；未发送请求，未用回退或Mock结果代替。",
                   "rows": [{"id": case, "status": "not_run"} for case in samples]}
        summary["modes"][mode] = {"status": "not_run", "reason": skipped["reason"]}
        write(args.output / f"{mode}.json", skipped)
    write(args.output / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
