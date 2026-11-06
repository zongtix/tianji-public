"""Coupons public-base HTTP smoke against tj-aigc on 8096. Synthetic user-info only.

Isolation is asserted from answer text (SSE does not currently emit hit documentIds).
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
OUT = pathlib.Path("target/aigc-es/knowledge/coupons-http-smoke.json")
COUPON_CORPUS_MARKERS = (
    "23456789ABCDEFGHJKLMNPQRSTUVWXYZ",
    "discountType",
    "每满减",
    "termDays",
    "天机学堂优惠券规则",
)
POINTS_CORPUS_MARKERS = ("签到", "学习积分", "points_message", "连续签到", "天机学堂积分与签到规则")
INSUFFICIENT_MARKERS = ("资料不足", "未覆盖", "没有", "无法", "查不到", "找不到", "不在", "无法回答")


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
        "answerPreview": answer[:300],
        "eventCount": len(events),
        "eventsSample": events[:6],
        "note": "SSE currently has no hit documentId field; isolation is answer-text based",
    }


def main() -> int:
    report = {"base": BASE, "user": USER, "cases": []}

    status, raw = http("POST", "/session?n=0&knowledgeBaseId=tianji-unknown")
    report["cases"].append(
        {"id": "unknown400", "status": status, "body": raw[:200], "ok": status == 400 and "知识库" in raw}
    )
    status, raw = http("POST", "/session?n=0&knowledgeBaseId=tianji-coupons", user=False)
    report["cases"].append({"id": "anon401", "status": status, "body": raw[:200], "ok": status == 401})

    sid, kb = new_session("tianji-coupons")
    coupons = chat(sid, "优惠券金额字段是按元还是按分保存？折扣80表示什么？")
    coupons["id"] = "coupons_q"
    coupons["knowledgeBaseId"] = kb
    couponish = any(token in coupons["answer"] for token in ("分", "折扣", "discount", "优惠券"))
    points_leak = any(token in coupons["answer"] for token in POINTS_CORPUS_MARKERS)
    coupons["pointsLeak"] = points_leak
    coupons["ok"] = bool(couponish and not points_leak and len(coupons["answer"]) > 20)
    if not coupons["ok"]:
        coupons["failReason"] = "coupons answer missing coupon signals or leaked points corpus"
    report["cases"].append(coupons)
    delete_session(sid)

    sid, kb = new_session("tianji-points")
    points = chat(sid, "一天学习课程最多能获得多少积分？")
    points["id"] = "points_q"
    points["knowledgeBaseId"] = kb
    pointsish = any(token in points["answer"] for token in ("积分", "签到", "学习"))
    coupon_leak = any(token in points["answer"] for token in COUPON_CORPUS_MARKERS)
    points["couponLeak"] = coupon_leak
    points["ok"] = bool(pointsish and not coupon_leak and len(points["answer"]) > 20)
    if not points["ok"]:
        points["failReason"] = "points answer missing points signals or leaked coupon corpus"
    report["cases"].append(points)
    delete_session(sid)

    sid, kb = new_session("tianji-points")
    cross = chat(sid, "兑换码有哪些字符限制？优惠券金额是按分吗？")
    cross["id"] = "points_cross_coupon_q"
    cross["knowledgeBaseId"] = kb
    cross["couponCorpusSmell"] = any(token in cross["answer"] for token in COUPON_CORPUS_MARKERS)
    cross["insufficient"] = any(token in cross["answer"] for token in INSUFFICIENT_MARKERS)
    # Must refuse AND must not leak coupon corpus (not OR).
    cross["ok"] = (not cross["couponCorpusSmell"]) and cross["insufficient"]
    if not cross["ok"]:
        cross["failReason"] = "points session must refuse coupon question without coupon corpus leakage"
    report["cases"].append(cross)
    delete_session(sid)

    for case in report["cases"]:
        sample = case.get("eventsSample")
        if not sample:
            continue
        slim = []
        for event in sample:
            item = {key: event.get(key) for key in ("eventType", "type", "code", "eventData") if key in event}
            data = item.get("eventData")
            if isinstance(data, str) and len(data) > 200:
                item["eventData"] = data[:200] + "..."
            slim.append(item)
        case["eventsSample"] = slim

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
        print("COUPONS_HTTP_SMOKE_FAILED:", error, file=sys.stderr)
        raise
