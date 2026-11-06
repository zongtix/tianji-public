"""Gate A A10 stop + A11 concurrency smoke against tj-aigc on 8096."""
from __future__ import annotations

import json
import pathlib
import re
import threading
import time
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8096"
USER_A = "900000000000000043"
USER_B = "900000000000000044"
OUT_A10 = pathlib.Path("target/aigc-es/course-materials/gate-a-a10-stop.json")
OUT_A11 = pathlib.Path("target/aigc-es/course-materials/gate-a-a11-concurrency.json")


def request(method: str, path: str, payload=None, user: str = USER_A, timeout=180):
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        BASE + path,
        data=body,
        method=method,
        headers={"Content-Type": "application/json", "user-info": user},
    )
    return urllib.request.urlopen(req, timeout=timeout)


def call(method: str, path: str, payload=None, user: str = USER_A):
    with request(method, path, payload, user=user) as resp:
        raw = resp.read()
    if not raw:
        return None
    value = json.loads(raw.decode("utf-8"))
    if isinstance(value, dict) and "code" in value and "data" in value:
        if value["code"] != 200:
            raise RuntimeError(f"API code={value['code']} path={path} msg={value}")
        return value["data"]
    return value


def new_session(user: str = USER_A, **params) -> str:
    qs = urllib.parse.urlencode(params)
    path = "/session?n=0" + (("&" + qs) if qs else "")
    return call("POST", path, user=user)["sessionId"]


def delete_session(session_id: str, user: str = USER_A) -> None:
    call("DELETE", "/session/" + urllib.parse.quote(session_id, safe=""), user=user)


def stop_session(session_id: str, user: str = USER_A) -> None:
    call("POST", "/chat/stop?" + urllib.parse.urlencode({"sessionId": session_id}), user=user)


def chat_until_stop(session_id: str, question: str, user: str = USER_A, min_chars: int = 80) -> dict:
    """Stream chat; call /chat/stop after answer text reaches min_chars (final-stream phase)."""
    texts_before = []
    texts_after = []
    events = []
    stop_errors = []
    stop_http_seconds = None
    stop_called_mono = None
    stream_end_mono = None
    stop_called = threading.Event()

    def do_stop():
        nonlocal stop_http_seconds, stop_called_mono
        try:
            t0 = time.monotonic()
            stop_session(session_id, user=user)
            stop_http_seconds = time.monotonic() - t0
            stop_called_mono = time.monotonic()
            stop_called.set()
        except Exception as exc:  # noqa: BLE001 — record and continue draining SSE
            stop_errors.append(type(exc).__name__ + ":" + str(exc))
            stop_called_mono = time.monotonic()
            stop_called.set()

    with request("POST", "/chat", {"sessionId": session_id, "question": question}, user=user) as resp:
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
            kind = event.get("eventType")
            events.append(kind)
            data = event.get("eventData")
            if kind == 1001 and isinstance(data, str):
                if stop_called.is_set():
                    texts_after.append(data)
                else:
                    texts_before.append(data)
                    # Stop in final-stream phase: enough text already out (tool likely returned).
                    # Do not trigger on a lone early keyword like「讲义」.
                    joined = "".join(texts_before)
                    ready = len(joined) >= min_chars or (
                        len(joined) >= 40 and bool(re.search(r"／C\d|FAQ", joined))
                    )
                    if ready and not stop_called.is_set():
                        do_stop()
            if kind == 1002:
                stream_end_mono = time.monotonic()

    before = "".join(texts_before)
    after = "".join(texts_after)
    drain_seconds = None
    if stop_called_mono is not None and stream_end_mono is not None:
        drain_seconds = stream_end_mono - stop_called_mono
    return {
        "question": question,
        "sessionId": session_id,
        "user": user,
        "answerBeforeStop": before,
        "answerAfterStop": after,
        "answerBeforeChars": len(before),
        "answerAfterChars": len(after),
        "eventTypes": events,
        "stopEventCount": events.count(1002),
        "errorEventCount": events.count(1005),
        "stopCallSeconds": stop_http_seconds,
        "drainAfterStopSeconds": drain_seconds,
        "stopErrors": stop_errors,
        "stopAccepted": bool(stop_called.is_set()) and not stop_errors,
    }


def chat_full(session_id: str, question: str, user: str = USER_A) -> dict:
    texts = []
    events = []
    started = time.time()
    with request("POST", "/chat", {"sessionId": session_id, "question": question}, user=user) as resp:
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
            events.append(event.get("eventType"))
            data = event.get("eventData")
            if event.get("eventType") == 1001 and isinstance(data, str):
                texts.append(data)
    ended = time.time()
    answer = "".join(texts)
    return {
        "question": question,
        "sessionId": session_id,
        "user": user,
        "answer": answer,
        "eventTypes": events,
        "stopEventCount": events.count(1002),
        "errorEventCount": events.count(1005),
        "startedAt": started,
        "endedAt": ended,
        "durationSeconds": round(ended - started, 3),
    }


def cites_material(answer: str, needles: list[str]) -> bool:
    return any(n in answer for n in needles)


def run_a10() -> dict:
    sid = new_session(USER_A)
    try:
        result = chat_until_stop(
            sid,
            "【AI演示】Redis 缓存与热点治理讲义里怎样解释缓存击穿？互斥锁怎么用？请尽量依据讲义详细说明。",
            user=USER_A,
            min_chars=80,
        )
        # Stop protocol: API accepted, single STOP event, no ERROR, stream drains promptly.
        # In-flight token buffer after stop is allowed; we do not require zero after-stop chars.
        result["id"] = "A10"
        result["okStopAccepted"] = (
            result["stopAccepted"]
            and result["stopCallSeconds"] is not None
            and result["stopCallSeconds"] < 5
        )
        result["okTerminalStop"] = result["stopEventCount"] == 1
        result["okNoError"] = result["errorEventCount"] == 0
        result["okDrainPrompt"] = (
            result["drainAfterStopSeconds"] is not None and result["drainAfterStopSeconds"] < 8
        )
        result["okHadLectureSignal"] = bool(
            re.search(r"击穿|互斥|讲义|Redis|缓存", result["answerBeforeStop"] + result["answerAfterStop"])
        )
        result["ok"] = all(
            [
                result["okStopAccepted"],
                result["okTerminalStop"],
                result["okNoError"],
                result["okDrainPrompt"],
                result["okHadLectureSignal"],
                result["answerBeforeChars"] >= 20,
            ]
        )
        return result
    finally:
        delete_session(sid, USER_A)


def intervals_overlap(a_start: float | None, a_end: float | None, b_start: float | None, b_end: float | None) -> bool:
    if None in (a_start, a_end, b_start, b_end):
        return False
    return a_start < b_end and b_start < a_end


def run_a11() -> dict:
    report = {"id": "A11", "users": {"A": USER_A, "B": USER_B}, "turns": []}
    results = {}
    errors = {}

    def worker(name: str, user: str, question: str):
        sid = None
        try:
            sid = new_session(user)
            turn = chat_full(sid, question, user=user)
            turn["name"] = name
            results[name] = turn
        except Exception as exc:  # noqa: BLE001
            errors[name] = type(exc).__name__ + ":" + str(exc)
        finally:
            if sid:
                try:
                    delete_session(sid, user)
                except Exception as exc:  # noqa: BLE001
                    errors[name + "_cleanup"] = type(exc).__name__

    q_redis = "【AI演示】Redis 缓存与热点治理讲义里缓存击穿是怎么发生的？请引用讲义。"
    q_mysql = "【AI演示】MySQL 索引与事务优化讲义里二级索引为什么要回表？请引用讲义。"
    q_cross = "【AI演示】Redis 缓存与热点治理讲义里有没有讲索引失效和 EXPLAIN？只依据 Redis 课讲义回答。"

    concurrent_wall_start = time.time()
    t1 = threading.Thread(target=worker, args=("redis_userA", USER_A, q_redis))
    t2 = threading.Thread(target=worker, args=("mysql_userB", USER_B, q_mysql))
    t1.start()
    t2.start()
    t1.join(timeout=240)
    t2.join(timeout=240)
    concurrent_wall_end = time.time()

    # sequential overreach on B after concurrent pair
    sid_b = new_session(USER_B)
    try:
        cross = chat_full(sid_b, q_cross, user=USER_B)
        cross["name"] = "redis_overreach_userB"
        results["redis_overreach_userB"] = cross
    finally:
        delete_session(sid_b, USER_B)

    report["turns"] = list(results.values())
    report["errors"] = errors
    report["concurrentWall"] = {
        "startedAt": concurrent_wall_start,
        "endedAt": concurrent_wall_end,
        "durationSeconds": round(concurrent_wall_end - concurrent_wall_start, 3),
    }

    redis = results.get("redis_userA") or {}
    mysql = results.get("mysql_userB") or {}
    over = results.get("redis_overreach_userB") or {}

    report["redisLooksRedis"] = cites_material(
        redis.get("answer", ""),
        ["击穿", "热点", "互斥", "Redis", "缓存击穿"],
    ) and not cites_material(redis.get("answer", ""), ["回表", "二级索引", "MVCC"])
    report["mysqlLooksMysql"] = cites_material(
        mysql.get("answer", ""),
        ["回表", "索引", "MySQL", "二级"],
    ) and not cites_material(mysql.get("answer", ""), ["缓存击穿", "互斥锁重建"])
    over_ans = over.get("answer", "")
    # Prefer explicit insufficient / not-found phrasing over a bare「未」substring.
    report["overreachNoLeak"] = (not cites_material(over_ans, ["回表", "二级索引", "事务隔离"])) and (
        bool(re.search(r"没有找到|资料不足|未检索到|查不到|无法确认|未命中", over_ans))
        or not cites_material(over_ans, ["EXPLAIN", "索引失效"])
    )
    report["pairSessionsDistinct"] = (
        redis.get("sessionId")
        and mysql.get("sessionId")
        and redis.get("sessionId") != mysql.get("sessionId")
    )
    report["streamsOverlapped"] = intervals_overlap(
        redis.get("startedAt"),
        redis.get("endedAt"),
        mysql.get("startedAt"),
        mysql.get("endedAt"),
    )
    report["ok"] = (
        not errors
        and report["redisLooksRedis"]
        and report["mysqlLooksMysql"]
        and report["overreachNoLeak"]
        and report["pairSessionsDistinct"]
        and report["streamsOverlapped"]
        and len(redis.get("answer", "")) > 30
        and len(mysql.get("answer", "")) > 30
    )
    for turn in report["turns"]:
        ans = turn.get("answer", "")
        turn["answerPreview"] = ans[:280]
        turn["answerChars"] = len(ans)
        turn.pop("answer", None)
        turn.pop("eventTypes", None)
    return report


def main(argv: list[str] | None = None) -> int:
    import sys

    args = argv if argv is not None else sys.argv[1:]
    only = args[0] if args else "all"
    OUT_A10.parent.mkdir(parents=True, exist_ok=True)

    a10 = a11 = mixed = None
    if only in ("all", "a10"):
        a10 = run_a10()
        OUT_A10.write_text(json.dumps(a10, ensure_ascii=False, indent=2), encoding="utf-8")
    if only in ("all", "a11"):
        a11 = run_a11()
        OUT_A11.write_text(json.dumps(a11, ensure_ascii=False, indent=2), encoding="utf-8")
    if only in ("all", "a4"):
        sid = new_session(USER_A)
        try:
            mixed = chat_full(
                sid,
                "【AI演示】Redis 缓存与热点治理多少钱？另外讲义里互斥锁超时为什么要强调？",
                user=USER_A,
            )
            mixed["mentionsPrice"] = bool(re.search(r"\d+\s*元|价格|费用", mixed["answer"]))
            mixed["mentionsLecture"] = bool(re.search(r"互斥|超时|锁|讲义|FAQ|C\d", mixed["answer"]))
            mixed["ok"] = mixed["mentionsPrice"] and mixed["mentionsLecture"]
            mixed["answerPreview"] = mixed["answer"][:280]
            mixed.pop("answer", None)
            mixed.pop("eventTypes", None)
        finally:
            delete_session(sid, USER_A)

    summary = {
        "A10": None if a10 is None else a10["ok"],
        "A11": None if a11 is None else a11["ok"],
        "A4_retest": None if mixed is None else mixed["ok"],
        "reports": {"A10": str(OUT_A10), "A11": str(OUT_A11)},
        "mixed": mixed,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    flags = [v for v in (summary["A10"], summary["A11"], summary["A4_retest"]) if v is not None]
    return 0 if flags and all(flags) else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print("GATE_A_A10_A11_FAILED:", error, flush=True)
        raise
