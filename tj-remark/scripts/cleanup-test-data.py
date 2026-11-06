#!/usr/bin/env python3
"""Remove only C2/C3 user 9998 QA IDs after Stream/PEL convergence."""

import argparse
import os

from operator_io import ensure_unused_qa_ids, mysql_ids, redis, ssh, stream_state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-host", required=True)
    parser.add_argument("--first", type=int, required=True)
    parser.add_argument("--last", type=int, required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if args.first <= 0 or args.last < args.first or args.last - args.first > 50000:
        parser.error("Invalid test ID range")
    sql = ("DELETE FROM tj_remark.liked_record WHERE user_id=9998 AND biz_type=0x5141 "
           f"AND biz_id BETWEEN {args.first} AND {args.last}")
    print("MYSQL:", sql + ";")
    print("REDIS: SREM remark:like:user:QA:9998 each ID in range")
    print("REDIS: ZREM remark:like:count:QA each ID in range")
    print("REDIS: HDEL remark:like:version:QA each ID in range")
    if not args.execute:
        print("Dry run. Re-run with --execute after reviewing range and evidence.")
        return
    if not os.environ.get("REDIS_PASSWORD"):
        parser.error("Set REDIS_PASSWORD for local Redis tunnel")
    if stream_state() != (0, 0) or redis("GET", "remark:like:cache:ready") != "READY":
        parser.error("Refuse cleanup before READY and Stream/PEL empty")
    command = ("docker exec mysql sh -c 'MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" "
               f"exec mysql -uroot -Nse \"{sql}\"'")
    ssh(args.ssh_host, command)
    for number in range(args.first, args.last + 1):
        redis("SREM", "remark:like:user:QA:9998", number)
        redis("ZREM", "remark:like:count:QA", number)
        redis("HDEL", "remark:like:version:QA", number)
    remaining = mysql_ids(args.ssh_host, 9998, args.first, args.last)
    if remaining:
        raise RuntimeError(f"MySQL cleanup incomplete: {sorted(remaining)}")
    ensure_unused_qa_ids(args.first, args.last)
    print(f"CLEANUP_OK user=9998 first={args.first} last={args.last}")
    print("MQ copies in legacy qa.liked.times.queue are intentionally untouched.")


if __name__ == "__main__":
    main()
