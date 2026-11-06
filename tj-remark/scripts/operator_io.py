"""Small dependency-free probes for the operator workstation's SSH/Redis tunnels."""

import os
import re
import socket
import subprocess


def _read_reply(stream):
    marker = stream.read(1)
    line = stream.readline().rstrip(b"\r\n")
    if marker == b"+":
        return line.decode()
    if marker == b"-":
        raise RuntimeError("Redis error: " + line.decode(errors="replace"))
    if marker == b":":
        return int(line)
    if marker == b"$":
        size = int(line)
        return None if size == -1 else stream.read(size + 2)[:-2].decode()
    if marker == b"*":
        size = int(line)
        return None if size == -1 else [_read_reply(stream) for _ in range(size)]
    raise RuntimeError("Invalid Redis reply")


def redis(*parts, host=None, port=None):
    host = host or os.environ.get("REDIS_HOST", "127.0.0.1")
    port = int(port if port is not None else os.environ.get("REDIS_PORT", "6379"))
    password = os.environ.get("REDIS_PASSWORD")
    with socket.create_connection((host, port), timeout=5) as connection:
        connection.settimeout(5)
        stream = connection.makefile("rb")

        def send(command):
            encoded = [str(part).encode() for part in command]
            wire = f"*{len(encoded)}\r\n".encode()
            wire += b"".join(f"${len(value)}\r\n".encode() + value + b"\r\n" for value in encoded)
            connection.sendall(wire)
            return _read_reply(stream)

        if password:
            send(("AUTH", password))
        return send(parts)


def ssh(host, command, timeout=20):
    result = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5", host, command],
        capture_output=True, text=True, timeout=timeout, check=True,
    )
    return result.stdout.strip()


def mysql_ids(host, user_id, first_id, last_id):
    user_id, first_id, last_id = map(int, (user_id, first_id, last_id))
    sql = ("SELECT biz_id FROM tj_remark.liked_record WHERE user_id="
           f"{user_id} AND biz_type=0x5141 AND biz_id BETWEEN {first_id} AND {last_id} ORDER BY biz_id")
    command = ("docker exec mysql sh -c 'MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" "
               f"exec mysql -uroot -Nse \"{sql}\"'")
    output = ssh(host, command)
    return {int(row) for row in output.splitlines()} if output else set()


def available_mb(host):
    return int(ssh(host, "free -m | awk '/^Mem:/ {print $7}'", timeout=3))


def redis_used_memory():
    info = redis("INFO", "memory")
    return int(next(line.split(":", 1)[1] for line in info.splitlines()
                    if line.startswith("used_memory:")))


def stream_state():
    size = redis("XLEN", "remark:like:changes")
    try:
        pending = redis("XPENDING", "remark:like:changes", "remark-like-db-writer")[0]
    except RuntimeError:
        pending = -1
    return size, pending


def queue_metrics(host, queue):
    if queue != "qa.liked.times.queue":
        raise ValueError("Only the non-consuming QA evidence queue is supported")
    container = os.environ.get("RABBIT_CONTAINER", "mq")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", container):
        raise ValueError("Invalid RabbitMQ container name")
    output = ssh(host, f"docker exec {container} rabbitmqctl list_queues -p /tjxt name messages_ready consumers", timeout=20)
    for line in output.splitlines():
        fields = line.split()
        if len(fields) == 3 and fields[0] == queue:
            return int(fields[1]), int(fields[2])
    return None


def ensure_unused_qa_ids(first_id, last_id):
    first_id, last_id = int(first_id), int(last_id)
    members = redis("SMEMBERS", "remark:like:user:QA:9998")
    if any(first_id <= int(member) <= last_id for member in members):
        raise RuntimeError("Test user's Redis Set already contains an ID in range")
    for start in range(first_id, last_id + 1, 500):
        ids = list(range(start, min(start + 500, last_id + 1)))
        if any(value is not None for value in redis("ZMSCORE", "remark:like:count:QA", *ids)):
            raise RuntimeError("QA count ZSet already contains an ID in range")
        if any(value is not None for value in redis("HMGET", "remark:like:version:QA", *ids)):
            raise RuntimeError("QA version Hash already contains an ID in range")


def learning_target_collision(host, first_id, last_id):
    first_id, last_id = int(first_id), int(last_id)
    sql = ("SELECT id FROM tj_learning.interaction_reply WHERE id BETWEEN "
           f"{first_id} AND {last_id} LIMIT 1")
    command = ("docker exec mysql sh -c 'MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" "
               f"exec mysql -uroot -Nse \"{sql}\"'")
    return bool(ssh(host, command))
