"""Prepare Boot 3 local configuration using the existing business databases.

Reads public configuration without changing it. Credentials stay in memory and
Nacos; only data IDs and checksums are printed. Requires PyYAML.
"""
import argparse
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request

import yaml


NAMESPACE = "tianji-local-boot3"
GROUP = "DEFAULT_GROUP"
SERVICES = ("gateway", "auth", "user", "course", "learning", "remark", "promotion",
            "search", "trade", "exam", "data", "pay", "message", "media", "aigc")


def migrate(data_id, obj, seata_address):
    if data_id == "shared-redis.yaml":
        spring = obj["spring"]
        spring.setdefault("data", {})["redis"] = spring.pop("redis")
    elif data_id == "shared-feign.yaml":
        feign = obj.pop("feign")
        sentinel = feign.pop("sentinel", None)
        feign.setdefault("httpclient", {}).pop("enabled", None)
        feign["httpclient"]["hc5"] = {"enabled": True}
        obj.setdefault("spring", {}).setdefault("cloud", {})["openfeign"] = feign
        if sentinel is not None:
            obj["feign"] = {"sentinel": sentinel}
    elif data_id == "shared-mybatis.yaml":
        obj["mybatis-plus"]["configuration"]["default-enum-type-handler"] = (
            "com.baomidou.mybatisplus.core.handlers.CompositeEnumTypeHandler")
        obj["mybatis-plus"]["global-config"].pop("field-strategy", None)
    elif data_id == "shared-seata.yaml":
        # Keep global transactions enabled, using the existing local TC tunnel.
        # Its original Nacos namespace no longer exists on this server.
        seata = obj["seata"]
        group = seata["tx-service-group"]
        seata["registry"] = {"type": "file"}
        seata["config"] = {"type": "file"}
        seata["service"] = {"vgroup-mapping": {group: "default"},
                            "grouplist": {"default": seata_address}}
    return obj


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nacos-url", default="http://127.0.0.1:8848")
    parser.add_argument("--seata-address", default="127.0.0.1:8099")
    args = parser.parse_args()
    base = args.nacos_url.rstrip("/") + "/nacos/v1/"

    def request(endpoint, params=None, method="GET"):
        query = urllib.parse.urlencode(params or {})
        url = base + endpoint
        data = None
        if method == "GET":
            url += "?" + query
        else:
            data = query.encode()
        with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method), timeout=20) as response:
            return response.read().decode()

    entries = json.loads(request("cs/configs", {
        "search": "accurate", "dataId": "", "group": GROUP,
        "tenant": "", "pageNo": 1, "pageSize": 100,
    }))
    if entries["totalCount"] > 100:
        raise RuntimeError("Unexpected number of public configurations")
    source = {}
    configs = {}
    for entry in entries["pageItems"]:
        data_id = entry["dataId"]
        if not data_id.endswith(".yaml"):
            continue
        content = request("cs/configs", {"tenant": "", "group": GROUP, "dataId": data_id})
        source[data_id] = content
        obj = yaml.safe_load(content)
        if not isinstance(obj, dict):
            raise RuntimeError("Expected YAML mapping: " + data_id)
        configs[data_id] = migrate(data_id, obj, args.seata_address)

    for name in SERVICES:
        configs.setdefault(name + "-service.yaml", {"spring": {"cloud": {"nacos": {
            "config": {"namespace": NAMESPACE}, "discovery": {"namespace": NAMESPACE},
        }}}})

    namespaces = json.loads(request("console/namespaces"))["data"]
    existing = next((item for item in namespaces if item["namespace"] == NAMESPACE), None)
    if existing and existing["namespaceShowName"] != NAMESPACE:
        raise RuntimeError("Refusing to replace an unrelated namespace")
    if not existing:
        result = request("console/namespaces", {
            "customNamespaceId": NAMESPACE, "namespaceName": NAMESPACE,
            "namespaceDesc": "Tianji Boot 3 local development with existing business data",
        }, "POST")
        if result.strip() != "true":
            raise RuntimeError("Namespace creation failed")

    for data_id, obj in configs.items():
        # Preserve authenticated ES connections on repeated local setup.
        if data_id in ("search-service.yaml", "aigc-service.yaml"):
            try:
                current = yaml.safe_load(request("cs/configs", {
                    "tenant": NAMESPACE, "group": GROUP, "dataId": data_id})) or {}
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                current = {}
            elasticsearch = current.get("spring", {}).get("elasticsearch")
            if elasticsearch:
                obj.setdefault("spring", {})["elasticsearch"] = elasticsearch
            if data_id == "aigc-service.yaml":
                knowledge = current.get("tj", {}).get("ai", {}).get("knowledge")
                if knowledge:
                    obj.setdefault("tj", {}).setdefault("ai", {})["knowledge"] = knowledge
                    obj.setdefault("spring", {}).setdefault("ai", {}).setdefault("model", {})["embedding"] = "none"
        content = yaml.safe_dump(obj, allow_unicode=True, sort_keys=False)
        result = request("cs/configs", {"tenant": NAMESPACE, "group": GROUP,
                         "dataId": data_id, "type": "yaml", "content": content}, "POST")
        if result.strip() != "true":
            raise RuntimeError("Publication failed: " + data_id)
        for attempt in range(20):
            try:
                actual = request("cs/configs", {"tenant": NAMESPACE, "group": GROUP, "dataId": data_id})
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                actual = None
            if actual == content:
                break
            time.sleep(0.25)
        if actual != content:
            raise RuntimeError("Read-back mismatch: " + data_id)
        print("Verified " + data_id + " sha256=" + hashlib.sha256(content.encode()).hexdigest())

    for data_id, content in source.items():
        actual = request("cs/configs", {"tenant": "", "group": GROUP, "dataId": data_id})
        if actual != content:
            raise RuntimeError("Public configuration changed during preparation: " + data_id)
    print("Ready: " + NAMESPACE + "; original database/Redis/MQ settings retained; public unchanged.")


if __name__ == "__main__":
    main()
