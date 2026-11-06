"""Gate A loopback chat smoke against tj-aigc on 8096. Uses synthetic user-info only."""
from __future__ import annotations

import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8096"
USER = "900000000000000043"
OUT = pathlib.Path("target/aigc-es/course-materials/gate-a-chat-smoke.json")


def request(method: str, path: str, payload=None, timeout=180):
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        BASE + path,
        data=body,
        method=method,
        headers={"Content-Type": "application/json", "user-info": USER},
    )
    return urllib.request.urlopen(req, timeout=timeout)


def call(method: str, path: str, payload=None):
    with request(method, path, payload) as resp:
        raw = resp.read()
    if not raw:
        return None
    value = json.loads(raw.decode("utf-8"))
    if isinstance(value, dict) and "code" in value and "data" in value:
        if value["code"] != 200:
            raise RuntimeError(f"API code={value['code']} path={path} msg={value}")
        return value["data"]
    return value


def chat(session_id: str, question: str) -> dict:
    texts = []
    events = []
    with request("POST", "/chat", {"sessionId": session_id, "question": question}) as resp:
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
    return {"question": question, "sessionId": session_id, "answer": answer, "eventCount": len(events)}


def new_session(**params) -> str:
    qs = urllib.parse.urlencode(params)
    path = "/session?n=0" + (("&" + qs) if qs else "")
    return call("POST", path)["sessionId"]


def delete_session(session_id: str) -> None:
    call("DELETE", "/session/" + urllib.parse.quote(session_id, safe=""))


def main() -> int:
    report = {"base": BASE, "user": USER, "cases": []}
    summary = {}

    # A1 Redis lecture
    s1 = new_session()
    c1 = chat(s1, "【AI演示】Redis 缓存与热点治理讲义里怎样解释缓存击穿？互斥锁怎么用？")
    c1["id"] = "A1"
    c1["expectHints"] = ["击穿", "互斥", "讲义", "Redis", "锁"]
    c1["hitHints"] = [h for h in c1["expectHints"] if h in c1["answer"]]
    c1["looksLikeLecture"] = len(c1["hitHints"]) >= 2 and len(c1["answer"]) > 40
    c1["hasCitation"] = bool(re.search(r"C\d|／|/", c1["answer"])) and ("讲义" in c1["answer"] or "FAQ" in c1["answer"])
    c1["ok"] = bool(c1["looksLikeLecture"])
    report["cases"].append(c1)
    delete_session(s1)
    summary["A1"] = c1["ok"]

    # A6 cross-course wording on Redis course
    s2 = new_session()
    c2 = chat(s2, "【AI演示】Redis 缓存与热点治理这门课讲义有没有讲 MySQL 索引失效？")
    c2["id"] = "A6"
    c2["mysqlLeak"] = bool(re.search(r"索引失效|执行计划|事务隔离", c2["answer"])) and "MySQL" in c2["answer"]
    c2["okNoMysqlLecture"] = (
        (not c2["mysqlLeak"])
        or ("资料不足" in c2["answer"])
        or ("没有" in c2["answer"])
        or ("未" in c2["answer"])
        or ("查不到" in c2["answer"])
        or ("找不到" in c2["answer"])
    )
    c2["ok"] = bool(c2["okNoMysqlLecture"])
    report["cases"].append(c2)
    delete_session(s2)
    summary["A6"] = c2["ok"]

    # A5 points isolation
    s3 = new_session(knowledgeBaseId="tianji-points")
    c3 = chat(s3, "【AI演示】Redis 缓存与热点治理讲义里缓存击穿怎么讲的？")
    c3["id"] = "A5"
    c3["mentionsLectureToolish"] = "searchCourseMaterials" in c3["answer"]
    # 回答里复述用户问题或建议检索词不算串讲义；以引用/FAQ/演示课正文为准。
    c3["hasPointsCorpus"] = ("积分" in c3["answer"]) or ("签到" in c3["answer"])
    c3["hasLectureCitation"] = bool(
        re.search(r"缓存击穿与互斥锁 FAQ|分布式锁的适用边界|／C\d|【AI演示】Redis 缓存与热点治理／", c3["answer"])
    )
    c3["redisLectureHeavy"] = c3["hasLectureCitation"] and ("互斥锁" in c3["answer"]) and len(c3["answer"]) > 120
    c3["looksIsolated"] = (
        c3["hasPointsCorpus"]
        and not c3["hasLectureCitation"]
        and not c3["mentionsLectureToolish"]
    )
    c3["ok"] = bool(c3["looksIsolated"])
    report["cases"].append(c3)
    delete_session(s3)
    summary["A5"] = c3["ok"]

    # A2 no course pointer — general tech
    s4 = new_session()
    c4 = chat(s4, "缓存击穿一般怎么处理？用互斥锁可以吗？")
    c4["id"] = "A2"
    c4["mentionsDemoCourse"] = "【AI演示】Redis 缓存与热点治理" in c4["answer"]
    c4["mentionsMaterialCitation"] = bool(
        re.search(r"缓存击穿与互斥锁 FAQ|／C\d|讲义《", c4["answer"])
    )
    # Should answer generally without pretending to quote the demo lecture corpus.
    c4["ok"] = (not c4["mentionsMaterialCitation"]) and len(c4["answer"]) > 20
    report["cases"].append(c4)
    delete_session(s4)
    summary["A2"] = c4["ok"]

    # A3 depth / coverage asking for lecture evidence
    s5 = new_session()
    c5 = chat(
        s5,
        "【AI演示】Redis 缓存与热点治理讲义里「缓存击穿」讲多深？是否讲全了互斥锁重建缓存？请依据讲义原文回答，不要只用目录标题。",
    )
    c5["id"] = "A3"
    c5["catalogueOnlySmell"] = bool(
        re.search(r"目录标题包括|正式目录标题", c5["answer"])
    ) and not bool(re.search(r"C\d|讲义|FAQ|互斥", c5["answer"]))
    c5["hasLectureSignal"] = bool(
        re.search(r"讲义|FAQ|互斥|击穿|C\d|资料不足|检索", c5["answer"])
    )
    c5["ok"] = (not c5["catalogueOnlySmell"]) and c5["hasLectureSignal"] and len(c5["answer"]) > 40
    report["cases"].append(c5)
    delete_session(s5)
    summary["A3"] = c5["ok"]

    # A4 price + lecture mixed
    s6 = new_session()
    c6 = chat(
        s6,
        "【AI演示】Redis 缓存与热点治理多少钱？另外讲义里缓存击穿和互斥锁怎么讲的？",
    )
    c6["id"] = "A4"
    c6["mentionsPrice"] = bool(re.search(r"\d+\s*元|价格|费用", c6["answer"]))
    c6["mentionsLecture"] = bool(re.search(r"击穿|互斥|讲义|FAQ|C\d", c6["answer"]))
    c6["interrupted"] = "本次回答中断" in c6["answer"]
    c6["ok"] = c6["mentionsPrice"] and c6["mentionsLecture"] and not c6["interrupted"]
    report["cases"].append(c6)
    delete_session(s6)
    summary["A4"] = c6["ok"]

    # A12 light: plain consult still works (course intro)
    s7 = new_session()
    c7 = chat(s7, "【AI演示】Redis 缓存与热点治理介绍一下")
    c7["id"] = "A12"
    c7["interrupted"] = "本次回答中断" in c7["answer"]
    c7["ok"] = (not c7["interrupted"]) and len(c7["answer"]) > 30 and "Redis" in c7["answer"]
    report["cases"].append(c7)
    delete_session(s7)
    summary["A12"] = c7["ok"]

    report["summary"] = summary
    report["allCriticalOk"] = all(summary.get(k) for k in ("A1", "A2", "A3", "A4", "A5", "A6"))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(
        json.dumps(
            {
                "report": str(OUT),
                "summary": summary,
                "allCriticalOk": report["allCriticalOk"],
                "previews": {
                    c["id"]: c["answer"][:220]
                    for c in report["cases"]
                    if c["id"] in ("A2", "A3", "A4", "A12")
                },
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0 if report["allCriticalOk"] else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print("GATE_A_SMOKE_FAILED:", error, file=sys.stderr)
        raise
