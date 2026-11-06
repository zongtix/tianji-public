"""Select Redis in the local AIGC Nacos config; back up the old config and retain all other settings."""
import argparse
import datetime
import pathlib
import urllib.parse
import urllib.request

import yaml

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--nacos-url", default="http://127.0.0.1:8848")
parser.add_argument("--store-type", choices=["elasticsearch", "redis"], default="redis")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
private = root / "target/aigc-redis8/private"
private.mkdir(parents=True, exist_ok=True)
params = {"tenant": "tianji-local-boot3", "group": "DEFAULT_GROUP", "dataId": "aigc-service.yaml"}
url = args.nacos_url.rstrip("/") + "/nacos/v1/cs/configs"


def nacos(values, write=False):
    encoded = urllib.parse.urlencode(values)
    request = urllib.request.Request(url if write else url + "?" + encoded,
                                     data=encoded.encode() if write else None)
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.read().decode()


original = nacos(params)
stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
backup = private / ("nacos-before-vector-store-" + stamp + ".yaml")
backup.write_text(original, encoding="utf-8")
config = yaml.safe_load(original) or {}
knowledge = config.setdefault("tj", {}).setdefault("ai", {}).setdefault("knowledge", {})
knowledge["store-type"] = "${TJ_AI_KNOWLEDGE_STORE_TYPE:" + args.store_type + "}"
if args.store_type == "redis":
    knowledge["redis"] = {
        "host": "${TJ_AIGC_VECTOR_REDIS_HOST:127.0.0.1}",
        "port": "${TJ_AIGC_VECTOR_REDIS_PORT:36379}", "username": "default",
        "password": "${TJ_AIGC_VECTOR_REDIS_PASSWORD}",
        "index-name": "tj_aigc_knowledge_v4_1024", "prefix": "tj:aigc:knowledge:v4:1024:",
    }
# Refuse to overwrite a concurrent edit made after the backup was read.
if nacos(params) != original:
    raise RuntimeError("Nacos changed concurrently; nothing published")
# Windows JDK 17 defaults to GBK; this Nacos parser re-encodes the String using that
# default before reading it as UTF-8. ASCII YAML escapes preserve Chinese values.
content = yaml.safe_dump(config, allow_unicode=False, sort_keys=False)
if nacos({**params, "type": "yaml", "content": content}, write=True).strip() != "true":
    raise RuntimeError("Nacos publication failed")
if yaml.safe_load(nacos(params)) != config:
    raise RuntimeError("Nacos read-back mismatch; inspect backup before retrying")
print("Local AIGC vector store selected: " + args.store_type + "; unrelated settings retained")
print("Previous config backed up under ignored target/aigc-redis8/private/; no secrets printed")
