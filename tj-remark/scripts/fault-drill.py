#!/usr/bin/env python3
"""C2 operator drill. Runs only an owned tj-remark process and isolated test IDs."""

import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from decisions import assert_no_loss, qa_queue_permits_test, redis_startup_failure
from operator_io import ensure_unused_qa_ids, learning_target_collision, mysql_ids, queue_metrics, redis, stream_state


def request(url, method="GET", body=None, user_id=9998):
    headers = {"user-info": str(user_id), "Content-Type": "application/json"}
    try:
        with urlopen(Request(url, method=method, data=body, headers=headers), timeout=4) as response:
            return response.status
    except HTTPError as error:
        return error.code
    except (URLError, TimeoutError):
        return 0


def launch(java, jar, log_path, overrides):
    stream = open(log_path, "w", encoding="utf-8")
    command = [java, "-Dfile.encoding=UTF-8", "-Dspring.profiles.active=local",
               *overrides, "-jar", str(jar)]
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT,
                               creationflags=flags)
    return process, stream


def stop(process, stream):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    if stream is not None:
        stream.close()


def wait_http(base_url, process, expected, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status = request(base_url + "/likes/times?bizType=QA&bizIds=1")
        if status == expected:
            return status
        if process.poll() is not None:
            return 0
        time.sleep(1)
    return 0


def wait_convergence(host, first_id, last_id, expected, timeout=90):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        size, pending = stream_state()
        rows = mysql_ids(host, 9998, first_id, last_id)
        last = {"xlen": size, "pending": pending, "mysql_ids": sorted(rows)}
        if size == 0 and pending == 0 and rows == expected:
            assert_no_loss(expected, rows, 0)
            return last
        time.sleep(2)
    raise AssertionError("Not converged within 90 seconds: " + repr(last))


def log_evidence(path):
    if not path.exists():
        return []
    return [line.strip() for line in path.read_text(encoding="utf-8", errors="replace").splitlines()
            if "ALERT" in line or "处理点赞缓存批次失败" in line or "like.metrics" in line
            or "redissonClient" in line or "RedisConnectionException" in line][-50:]


def last_fail_count(lines):
    values = [int(match.group(1)) for line in lines
              if (match := re.search(r"failCount=(\d+)", line))]
    return values[-1] if values else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=("r1", "r2", "r3"))
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--ssh-host", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--base-url", default="http://127.0.0.1:8091")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--count", type=int, default=10)
    parser.add_argument("--biz-start", type=int, required=True)
    args = parser.parse_args()
    if not args.jar.is_file() or args.count < 1 or args.count > 30 or args.biz_start < 1:
        parser.error("jar must exist; count must be 1..30; biz-start must be positive")
    if not os.environ.get("REDIS_PASSWORD"):
        parser.error("Set REDIS_PASSWORD for the local Redis SSH tunnel")
    first, last = args.biz_start, args.biz_start + args.count - 1
    if mysql_ids(args.ssh_host, 9998, first, last):
        parser.error("Test ID range already has MySQL rows; clean previous run first")
    ensure_unused_qa_ids(first, last)
    if learning_target_collision(args.ssh_host, first, last):
        parser.error("Test range overlaps real tj_learning.interaction_reply IDs")
    if stream_state() != (0, 0) or redis("GET", "remark:like:cache:ready") != "READY":
        parser.error("Require READY and empty Stream/PEL before injection")
    if args.scenario != "r1":
        try:
            qa_queue = queue_metrics(args.ssh_host, "qa.liked.times.queue")
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
            parser.error("Cannot inspect legacy QA queue: " + str(error))
        if not qa_queue_permits_test(qa_queue):
            parser.error("Legacy QA queue has no consumer; test messages cannot be selectively cleaned")
    target = args.base_url.split("://", 1)[-1].split("/", 1)[0]
    host, port = target.rsplit(":", 1)
    try:
        with socket.create_connection((host, int(port)), timeout=1):
            parser.error("Stop the existing remark process first; port must be free")
    except OSError:
        pass

    args.output.mkdir(parents=True, exist_ok=True)
    evidence = {"scenario": args.scenario, "test_user": 9998, "biz_range": [first, last],
                "accepted": [], "rejected": [], "other_http": [], "pass": False}
    process = stream = killer = killer_log = None
    fault_log = args.output / "fault-app.log"
    normal_log = args.output / "recovery-app.log"
    try:
        if args.scenario == "r3":
            evidence["mq_before"] = queue_metrics(args.ssh_host, "qa.liked.times.queue")
        overrides = {"r1": ["-Dspring.redis.port=16379"],
                     "r2": [], "r3": ["-Dtj.mq.port=15673"]}[args.scenario]
        process, stream = launch(args.java, args.jar, fault_log, overrides)
        evidence["fault_start_http"] = wait_http(
            args.base_url, process, 503 if args.scenario == "r1" else 200)
        if args.scenario == "r1":
            evidence["fault_process_exit"] = process.poll()
            evidence["fault_write_http"] = request(
                args.base_url + "/likes", "POST",
                json.dumps({"bizId": first, "bizType": "QA", "liked": True}).encode())
        if args.scenario == "r2":
            server_script = Path(__file__).with_name("kill-remark-mysql-server.sh")
            killer_log = open(args.output / "mysql-kill.log", "w", encoding="utf-8")
            with open(server_script, "rb") as script_input:
                killer = subprocess.Popen(["ssh", "-o", "BatchMode=yes", args.ssh_host,
                                           "bash", "-s", "--", "45"], stdin=script_input,
                                          stdout=killer_log, stderr=subprocess.STDOUT)
            time.sleep(2)
        test_ids = range(first, last + 1) if args.scenario != "r1" else ()
        for number in test_ids:
            body = json.dumps({"bizId": number, "bizType": "QA", "liked": True}).encode()
            status = request(args.base_url + "/likes", "POST", body)
            if status == 200:
                evidence["accepted"].append(number)
            elif status == 503:
                evidence["rejected"].append(number)
            else:
                evidence["other_http"].append([number, status])
            time.sleep(0.15)
        if args.scenario == "r2":
            time.sleep(32)  # capture a 30-second like.metrics line while KILL is active
        elif args.scenario == "r3":
            time.sleep(65)  # allow lagMs to cross the configured 60-second ALERT
        try:
            evidence["injection_stream"] = dict(zip(("xlen", "pending"), stream_state()))
        except (OSError, RuntimeError) as error:
            evidence["injection_redis_error"] = str(error)
        if killer is not None:
            killer.wait(timeout=60)
            killer_log.close()
            killer_log = None
            evidence["mysql_killer_exit"] = killer.returncode
        stop(process, stream)
        process = stream = None
        evidence["fault_log"] = log_evidence(fault_log)
        process, stream = launch(args.java, args.jar, normal_log, [])
        evidence["recovery_http"] = wait_http(args.base_url, process, 200)
        evidence["recovery_state"] = wait_convergence(
            args.ssh_host, first, last, set(evidence["accepted"]))
        if args.scenario == "r3":
            try:
                evidence["mq_after"] = queue_metrics(args.ssh_host, "qa.liked.times.queue")
            except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
                evidence["mq_error"] = str(error)
        fault_alerts = evidence["fault_log"]
        if args.scenario == "r1":
            observed = (redis_startup_failure(
                evidence["fault_process_exit"], evidence["fault_start_http"],
                fault_log.read_text(encoding="utf-8", errors="replace")) and
                evidence["fault_write_http"] == 0)
        elif args.scenario == "r2":
            observed = (evidence.get("mysql_killer_exit") == 0 and
                        evidence.get("injection_stream", {}).get("xlen", 0) > 0 and
                        last_fail_count(fault_alerts) > 0 and
                        any("处理点赞缓存批次失败" in line for line in fault_alerts))
        else:
            observed = (bool(evidence["accepted"]) and
                        evidence.get("injection_stream", {}).get("xlen", 0) > 0 and
                        any("点赞落库延迟超阈值" in line for line in fault_alerts) and
                        any("处理点赞缓存批次失败" in line for line in fault_alerts))
        evidence["pass"] = bool(observed and not evidence["other_http"] and
                                evidence["recovery_http"] == 200)
    except (AssertionError, OSError, RuntimeError, subprocess.SubprocessError) as error:
        evidence["error"] = str(error)
    finally:
        if killer is not None and killer.poll() is None:
            killer.terminate()
            killer.wait(timeout=5)
        if killer_log is not None:
            killer_log.close()
        stop(process, stream)
        evidence["recovery_log"] = log_evidence(normal_log)
        (args.output / "evidence.json").write_text(
            json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(evidence, ensure_ascii=False, indent=2))
        print("CLEANUP_AFTER_CONVERGENCE: DELETE FROM tj_remark.liked_record "
              f"WHERE user_id=9998 AND biz_type='QA' AND biz_id BETWEEN {first} AND {last};")
        print(f"CLEANUP_AFTER_CONVERGENCE: SREM remark:like:user:QA:9998 {' '.join(map(str, range(first, last + 1)))}")
        print(f"CLEANUP_AFTER_CONVERGENCE: ZREM remark:like:count:QA {' '.join(map(str, range(first, last + 1)))}")
        print(f"CLEANUP_AFTER_CONVERGENCE: HDEL remark:like:version:QA {' '.join(map(str, range(first, last + 1)))}")
    return 0 if evidence["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
