#!/usr/bin/env python3
"""C3 bounded /likes load drill; run from the workstation with existing tunnels."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import csv
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from decisions import (assert_no_loss, load_stage_pass, pressure_reason,
                       qa_queue_permits_test, waterline_from_peak)
from operator_io import (available_mb, ensure_unused_qa_ids, learning_target_collision,
                         mysql_ids, queue_metrics, redis, redis_used_memory, stream_state)


def post(url, number):
    payload = json.dumps({"bizId": number, "bizType": "QA", "liked": True}).encode()
    started = time.monotonic()
    try:
        with urlopen(Request(url, data=payload, method="POST", headers={
                "Content-Type": "application/json", "user-info": "9998"}), timeout=5) as response:
            status = response.status
    except HTTPError as error:
        status = error.code
    except (URLError, TimeoutError):
        status = 0
    return number, status, (time.monotonic() - started) * 1000, time.time()


def percentile(values, percent):
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[min(len(ordered) - 1, int((len(ordered) - 1) * percent))], 2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-host", required=True)
    parser.add_argument("--url", default="http://127.0.0.1:8091/likes")
    parser.add_argument("--qps", default="50,100,200")
    parser.add_argument("--duration", type=int, default=30)
    parser.add_argument("--biz-start", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rates = [int(value) for value in args.qps.split(",")]
    if not rates or rates[0] != 50 or any(rate < 1 or rate > 500 for rate in rates):
        parser.error("Rates must start at 50 QPS and stay <=500")
    if args.duration < 30 or args.duration > 60 or args.biz_start < 1:
        parser.error("Duration must be 30..60 seconds; biz-start positive")
    if not os.environ.get("REDIS_PASSWORD"):
        parser.error("Set REDIS_PASSWORD for the local Redis SSH tunnel")
    planned = sum(rates) * args.duration
    first, last = args.biz_start, args.biz_start + planned - 1
    if mysql_ids(args.ssh_host, 9998, first, last):
        parser.error("Test range already has MySQL rows")
    if stream_state() != (0, 0) or redis("GET", "remark:like:cache:ready") != "READY":
        parser.error("Require READY and empty Stream/PEL before load")
    try:
        qa_queue = queue_metrics(args.ssh_host, "qa.liked.times.queue")
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        parser.error("Cannot inspect legacy QA queue: " + str(error))
    if not qa_queue_permits_test(qa_queue):
        parser.error("Legacy QA queue has no consumer; load would leave uncleanable messages")
    ensure_unused_qa_ids(first, last)
    if learning_target_collision(args.ssh_host, first, last):
        parser.error("Test range overlaps real tj_learning.interaction_reply IDs")

    args.output.mkdir(parents=True, exist_ok=True)
    stop = threading.Event()
    samples = []
    sample_error = []

    def watch():
        while not stop.is_set():
            try:
                available = available_mb(args.ssh_host)
                used = redis_used_memory()
                xlen, pending = stream_state()
                reason = pressure_reason(available, used)
                samples.append({"timestamp": time.time(), "available_mb": available,
                                "redis_used_bytes": used, "xlen": xlen, "pending": pending,
                                "stop_reason": reason or ""})
                if reason:
                    stop.set()
                    break
            except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
                sample_error.append(str(error))
                stop.set()  # failure to observe safety is itself a stop condition
                break
            stop.wait(1)

    results = []
    stages = []
    next_id = first
    initial_available = available_mb(args.ssh_host)
    initial_used = redis_used_memory()
    initial_reason = pressure_reason(initial_available, initial_used)
    if initial_reason:
        parser.error("Pressure limit already crossed: " + initial_reason)
    samples.append({"timestamp": time.time(), "available_mb": initial_available,
                    "redis_used_bytes": initial_used, "xlen": 0, "pending": 0,
                    "stop_reason": ""})
    slots = threading.BoundedSemaphore(48)

    def guarded_post(number):
        try:
            return post(args.url, number)
        finally:
            slots.release()

    monitor = threading.Thread(target=watch, daemon=True)
    monitor.start()
    with ThreadPoolExecutor(max_workers=48) as pool:
        for rate in rates:
            if stop.is_set():
                break
            stage_start = time.monotonic()
            futures = []
            for index in range(rate * args.duration):
                deadline = stage_start + index / rate
                if stop.wait(max(0, deadline - time.monotonic())):
                    break
                if time.time() - samples[-1]["timestamp"] > 3:
                    sample_error.append("Pressure probe stale for over 3 seconds")
                    stop.set()
                    break
                if not slots.acquire(timeout=1):
                    sample_error.append("48 requests already in flight; stop load")
                    stop.set()
                    break
                if stop.is_set():
                    slots.release()
                    break
                futures.append(pool.submit(guarded_post, next_id))
                next_id += 1
            stage_results = [future.result() for future in futures]
            results.extend(stage_results)
            elapsed = max(time.monotonic() - stage_start, 0.001)
            stage = {"target_qps": rate, "seconds": round(elapsed, 2),
                           "planned": rate * args.duration, "sent": len(stage_results),
                           "accepted": sum(row[1] == 200 for row in stage_results),
                           "rejected": sum(row[1] == 503 for row in stage_results),
                           "unknown": sum(row[1] not in (200, 503) for row in stage_results),
                           "actual_tps": round(sum(row[1] == 200 for row in stage_results) / elapsed, 2),
                           "p95_ms": percentile([row[2] for row in stage_results], 0.95)}
            stage["pass"] = load_stage_pass(stage, args.duration)
            stages.append(stage)
            if not stage["pass"]:
                break
    stop.set()
    monitor.join(timeout=3)
    accepted = {number for number, status, _, _ in results if status == 200}
    rejected = sum(status == 503 for _, status, _, _ in results)
    unknown = sum(status not in (200, 503) for _, status, _, _ in results)
    recovery_start = time.monotonic()
    persisted = set()
    converged = False
    recovery_error = None
    for _ in range(90):
        try:
            xlen, pending = stream_state()
            persisted = mysql_ids(args.ssh_host, 9998, first, last)
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
            recovery_error = str(error)
            break
        if xlen == 0 and pending == 0 and persisted == accepted:
            converged = True
            break
        time.sleep(2)
    recovery_seconds = round(time.monotonic() - recovery_start, 2)
    loss_error = None
    try:
        assert_no_loss(accepted, persisted, rejected)
    except AssertionError as error:
        loss_error = str(error)
    observed_peak = max(Counter(int(row[3]) for row in results if row[1] == 200).values(), default=0)
    try:
        suggested = waterline_from_peak(50000, observed_peak, 1)
    except ValueError:
        suggested = None
    report = {"stages": stages, "safety_stop": next((row["stop_reason"] for row in samples
                    if row["stop_reason"]), None), "sample_error": sample_error,
              "max_xlen": max((row["xlen"] for row in samples), default=None),
              "initial_redis_used_bytes": initial_used,
              "peak_redis_used_bytes": max((row["redis_used_bytes"] for row in samples), default=None),
              "minimum_available_mb": min((row["available_mb"] for row in samples), default=None),
              "peak_accepted_qps": observed_peak, "recommended_soft_watermark": suggested,
              "watermark_basis": "50000 - peak_accepted_qps * 1s sample interval; memory budget must also be checked",
              "recovery_seconds": recovery_seconds, "converged": converged,
              "recovery_error": recovery_error,
              "accepted": len(accepted), "rejected": rejected, "unknown": unknown,
              "row_count": len(persisted), "loss_error": loss_error,
              "pass": converged and not unknown and not loss_error and not sample_error and
                      not any(row["stop_reason"] for row in samples) and
                      len(stages) == len(rates) and all(stage["pass"] for stage in stages)}
    with open(args.output / "samples.csv", "w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=("timestamp", "available_mb", "redis_used_bytes",
                                                   "xlen", "pending", "stop_reason"))
        writer.writeheader()
        writer.writerows(samples)
    with open(args.output / "requests.csv", "w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow(("biz_id", "http_status", "latency_ms", "completed_at_epoch"))
        writer.writerows(results)
    (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print("CLEANUP_AFTER_CONVERGENCE: DELETE FROM tj_remark.liked_record "
          f"WHERE user_id=9998 AND biz_type='QA' AND biz_id BETWEEN {first} AND {last};")
    print(f"CLEANUP_AFTER_CONVERGENCE: SREM remark:like:user:QA:9998 <all accepted IDs in {first}..{last}>")
    print(f"CLEANUP_AFTER_CONVERGENCE: ZREM remark:like:count:QA <all accepted IDs in {first}..{last}>")
    print(f"CLEANUP_AFTER_CONVERGENCE: HDEL remark:like:version:QA <all accepted IDs in {first}..{last}>")
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
