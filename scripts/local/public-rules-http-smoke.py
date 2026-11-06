"""On-demand public rules HTTP smoke against tj-aigc on 8096.

Cases:
  1) Ordinary session + points question → points signals, no coupon corpus
  2) Ordinary session + coupons question → coupon signals, no points corpus
  3) Legacy tianji-points session + coupon question → refuse, no coupon corpus

Isolation is answer-text based (SSE does not emit hit documentIds).
"""
from __future__ import annotations

import json
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8096"
USER = "900000000000000043"
OUT = pathlib.Path("target/aigc-es/knowledge/public-rules-http-smoke.json")

COUPON_CORPUS_MARKERS = (
    "23456789ABCDEFGHJKLMNPQRSTUVWXYZ",
    "discountType",
    "每满减",
    "termDays",
    "天机学堂优惠券规则",
)
POINTS_CORPUS_MARKERS = (
    "签到",
    "学习积分",
    "points_message",
    "连续签到",
    "天机学堂积分与签到规则",
)
INSUFFICIENT_MARKERS = ("资料不足", "未覆盖", "没有", "无法", "查不到", "找不到", "不在", "无法回答", "未检索", "未找到")


def http(method: str, path: str, payload=None, user: bool = True, timeout: int = 180):
    headers = {"Content-Type": "application/json"}
    if user:
        headers["user-info"] = USER
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")


def new_session(kb: str | None = None) -> tuple[str, str | None]:
    qs = "n=0"
    if kb:
        qs += "&knowledgeBaseId=" + urllib.parse.quote(kb)
    status, raw = http("POST", "/session?" + qs)
    if status != 200:
        raise RuntimeError(f"create session {kb} -> {status} {raw}")
    data = json.loads(raw)
    if isinstance(data, dict) and "data" in data and "code" in data:
        data = data["data"]
    return data["sessionId"], data.get("knowledgeBaseId")


def delete_session(session_id: str) -> None:
    http("DELETE", "/session/" + urllib.parse.quote(session_id, safe=""))


def chat(session_id: str, question: str) -> dict:
    texts = []
    events = []
    headers = {"Content-Type": "application/json", "user-info": USER}
    body = json.dumps({"sessionId": session_id, "question": question}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + "/chat", data=body, method="POST", headers=headers)
    with urllib.request.urlopen(req, timeout=180) as resp:
        for raw in resp:
            line = raw.decode("utf-8", errors="replace").strip()
            if not line or line.startswith(":"):
                continue
            if line.startswith("data:"):
                line = line[5:].strip()
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            events.append(event)
            data = event.get("eventData")
            if isinstance(data, str):
                texts.append(data)
            elif isinstance(data, dict):
                for key in ("text", "content", "message", "delta"):
                    if isinstance(data.get(key), str):
                        texts.append(data[key])
                        break
    answer = "".join(texts).strip()
    return {
        "question": question,
        "sessionId": session_id,
        "answer": answer,
        "answerPreview": answer[:400],
        "eventCount": len(events),
        "eventsSample": events[:6],
        "note": "SSE currently has no hit documentId field; isolation is answer-text based",
    }


def slim_events(case: dict) -> None:
    sample = case.get("eventsSample")
    if not sample:
        return
    slim = []
    for event in sample:
        item = {key: event.get(key) for key in ("eventType", "type", "code", "eventData") if key in event}
        data = item.get("eventData")
        if isinstance(data, str) and len(data) > 200:
            item["eventData"] = data[:200] + "..."
        slim.append(item)
    case["eventsSample"] = slim


def main() -> int:
    health_status, health_body = http("GET", "/actuator/health", user=False, timeout=5)
    if health_status != 200 or "UP" not in health_body:
        raise RuntimeError(f"aigc health not UP: {health_status} {health_body}")

    report = {"base": BASE, "user": USER, "cases": []}

    # 1) Ordinary session — points isolation
    sid, kb = new_session()
    points = chat(sid, "一天学习课程最多能获得多少积分？签到规则是什么？")
    points["id"] = "ordinary_points"
    points["knowledgeBaseId"] = kb
    points["pointsish"] = any(token in points["answer"] for token in ("积分", "签到", "学习"))
    points["couponLeak"] = any(token in points["answer"] for token in COUPON_CORPUS_MARKERS)
    points["ok"] = bool(points["pointsish"] and not points["couponLeak"] and len(points["answer"]) > 20)
    if not points["ok"]:
        points["failReason"] = "ordinary points answer missing points signals or leaked coupon corpus"
    report["cases"].append(points)
    delete_session(sid)

    # 2) Ordinary session — coupons isolation
    sid, kb = new_session()
    coupons = chat(sid, "优惠券金额字段是按元还是按分保存？兑换码有哪些字符限制？")
    coupons["id"] = "ordinary_coupons"
    coupons["knowledgeBaseId"] = kb
    coupons["couponish"] = any(token in coupons["answer"] for token in ("分", "折扣", "优惠券", "兑换"))
    coupons["pointsLeak"] = any(token in coupons["answer"] for token in POINTS_CORPUS_MARKERS)
    coupons["ok"] = bool(coupons["couponish"] and not coupons["pointsLeak"] and len(coupons["answer"]) > 20)
    if not coupons["ok"]:
        coupons["failReason"] = "ordinary coupons answer missing coupon signals or leaked points corpus"
    report["cases"].append(coupons)
    delete_session(sid)

    # 3) Legacy points session — no cross-library coupon corpus
    sid, kb = new_session("tianji-points")
    legacy = chat(sid, "兑换码有哪些字符限制？优惠券金额是按分吗？")
    legacy["id"] = "legacy_points_no_coupon"
    legacy["knowledgeBaseId"] = kb
    legacy["couponCorpusSmell"] = any(token in legacy["answer"] for token in COUPON_CORPUS_MARKERS)
    legacy["insufficient"] = any(token in legacy["answer"] for token in INSUFFICIENT_MARKERS)
    legacy["ok"] = (kb == "tianji-points") and (not legacy["couponCorpusSmell"]) and legacy["insufficient"]
    if not legacy["ok"]:
        legacy["failReason"] = "legacy points session must refuse coupon question without coupon corpus leakage"
    report["cases"].append(legacy)
    delete_session(sid)

    for case in report["cases"]:
        slim_events(case)

    report["summary"] = {case["id"]: case.get("ok") for case in report["cases"]}
    report["allOk"] = all(report["summary"].values())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        json.dumps(
            {
                "report": str(OUT),
                "summary": report["summary"],
                "allOk": report["allOk"],
                "previews": {
                    case["id"]: case.get("answerPreview") or case.get("body") for case in report["cases"]
                },
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0 if report["allOk"] else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print("PUBLIC_RULES_HTTP_SMOKE_FAILED:", error, file=sys.stderr)
        raise
