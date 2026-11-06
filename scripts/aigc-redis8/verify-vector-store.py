"""Verify authentication, JSON, filtered HNSW kNN and optional container-restart persistence."""
import argparse
import json
import pathlib
import socket
import struct
import subprocess
import time
import uuid


class Redis:
    def __init__(self, host, port, password=None):
        self.socket = socket.create_connection((host, port), timeout=8)
        self.socket.settimeout(8)
        self.reader = self.socket.makefile("rb")
        if password:
            self.command("AUTH", password)

    def close(self):
        self.reader.close()
        self.socket.close()

    def read(self):
        kind = self.reader.read(1)
        line = self.reader.readline().rstrip(b"\r\n")
        if kind == b"+":
            return line.decode()
        if kind == b"-":
            raise RuntimeError(line.decode())
        if kind == b":":
            return int(line)
        if kind == b"$":
            size = int(line)
            if size < 0:
                return None
            data = self.reader.read(size)
            self.reader.read(2)
            return data.decode()
        if kind == b"*":
            size = int(line)
            return None if size < 0 else [self.read() for _ in range(size)]
        raise RuntimeError("Unexpected Redis response")

    def command(self, *args):
        values = [v if isinstance(v, bytes) else str(v).encode() for v in args]
        self.socket.sendall(b"*" + str(len(values)).encode() + b"\r\n" + b"".join(
            b"$" + str(len(v)).encode() + b"\r\n" + v + b"\r\n" for v in values))
        return self.read()


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--host", default="127.0.0.1")
parser.add_argument("--port", type=int, default=36379)
parser.add_argument("--password-file", type=pathlib.Path,
                    default=pathlib.Path("/root/tj-aigc-redis8/password.txt"))
parser.add_argument("--restart-container", choices=["tj-aigc-redis8"])
args = parser.parse_args()
password = args.password_file.read_text().strip()


def connect():
    for attempt in range(40):
        try:
            return Redis(args.host, args.port, password)
        except (OSError, RuntimeError):
            if attempt == 39:
                raise
            time.sleep(0.25)


client = connect()
unauthenticated = Redis(args.host, args.port)
try:
    try:
        unauthenticated.command("PING")
    except RuntimeError as error:
        assert "NOAUTH" in str(error), str(error)
    else:
        raise AssertionError("Unauthenticated access unexpectedly succeeded")
finally:
    unauthenticated.close()

prefix = "tj_aigc_verify_" + uuid.uuid4().hex + ":"
index = prefix + "index"
keys = [prefix + suffix for suffix in ("near", "far", "excluded")]
created_index = False
try:
    info = dict(line.split(":", 1) for line in client.command("INFO", "server").splitlines()
                if ":" in line and not line.startswith("#"))
    assert info["redis_version"].startswith("8."), info["redis_version"]
    commands = client.command("COMMAND", "INFO", "FT.CREATE", "FT.SEARCH", "JSON.SET")
    assert all(commands), "Missing search or JSON commands"
    assert client.command("CONFIG", "GET", "appendonly") == ["appendonly", "yes"]
    assert client.command("CONFIG", "GET", "maxmemory-policy") == ["maxmemory-policy", "noeviction"]
    modules = client.command("MODULE", "LIST")
    print("Redis " + info["redis_version"] + "; modules=" + ",".join(
        dict(zip(module[::2], module[1::2]))["name"] for module in modules))
    client.command("FT.CREATE", index, "ON", "JSON", "PREFIX", 1, prefix, "SCHEMA",
                   "$.content", "AS", "content", "TEXT",
                   "$.metadata.knowledgeBaseId", "AS", "kb", "TAG",
                   "$.embedding", "AS", "embedding", "VECTOR", "HNSW", 6,
                   "TYPE", "FLOAT32", "DIM", 3, "DISTANCE_METRIC", "COSINE")
    created_index = True
    for key, vector, kb in zip(keys, ([1, 0, 0], [0, 1, 0], [1, 0, 0]), ("probe", "probe", "other")):
        client.command("JSON.SET", key, "$", json.dumps({
            "content": key, "embedding": vector, "metadata": {"knowledgeBaseId": kb}}))

    def verify_search():
        for attempt in range(40):
            result = client.command("FT.SEARCH", index, "(@kb:{probe})=>[KNN 1 @embedding $v AS distance]",
                                    "PARAMS", 2, "v", struct.pack("<3f", 1, 0, 0),
                                    "SORTBY", "distance", "RETURN", 2, "content", "distance", "DIALECT", 2)
            if result[0]:
                assert result[0] == 1 and result[1] == keys[0], result
                fields = dict(zip(result[2][::2], result[2][1::2]))
                assert abs(float(fields["distance"])) < 1e-5, result
                return
            time.sleep(0.1)
        raise AssertionError("Vector search did not return the expected document")

    verify_search()
    if args.restart_container:
        client.close()
        subprocess.run(["docker", "restart", "--time", "20", args.restart_container], check=True,
                       stdout=subprocess.DEVNULL)
        client = connect()
        assert all(client.command("JSON.GET", key) for key in keys), "Documents missing after restart"
        verify_search()
        print("Container restart: JSON documents and vector index retained")
    print("PASS: authentication, AOF, JSON, HNSW kNN and knowledge-base metadata filtering")
finally:
    if created_index:
        client.command("FT.DROPINDEX", index)
    client.command("DEL", *keys)
    client.close()
    print("Temporary verification index and keys cleaned")
