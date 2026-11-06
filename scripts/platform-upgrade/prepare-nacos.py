"""Create task-owned Nacos configuration for the selected platform baseline.

Requires Python 3, PyYAML, SSH access to the already prepared test resources,
and an existing local SSH tunnel to Nacos. Never changes public/dev configs.
Run only after prepare-test-environment.sh. Credentials are never printed.
"""
import argparse
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

import yaml


NAMESPACE = "tianji-platform-upgrade-test"
GROUP = "DEFAULT_GROUP"
SHARED = ("spring", "mybatis", "redis", "mq", "feign", "xxljob")
SERVICES = ("gateway", "auth", "user", "course", "learning", "remark", "promotion",
            "search", "trade", "exam", "data", "pay", "message", "media", "aigc")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-host", required=True)
    parser.add_argument("--ssh-key", required=True)
    parser.add_argument("--platform", choices=("old", "boot3"), default="boot3")
    args = parser.parse_args()
    namespace = NAMESPACE if args.platform == "old" else NAMESPACE + "-boot3"
    base = "http://127.0.0.1:8848/nacos/v1/"

    def request(endpoint, params=None, method="GET"):
        query = urllib.parse.urlencode(params or {})
        url = base + endpoint
        data = None
        if method == "GET":
            url += "?" + query
        else:
            data = query.encode()
        req = urllib.request.Request(url, data=data, method=method)
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.read().decode()

    namespaces = json.loads(request("console/namespaces"))["data"]
    existing = next((n for n in namespaces if n["namespace"] == namespace), None)
    if existing and existing["namespaceShowName"] != namespace:
        raise RuntimeError("Refusing to change an unowned namespace")
    if not existing:
        result = request("console/namespaces", {
            "customNamespaceId": namespace, "namespaceName": namespace,
            "namespaceDesc": "Task-owned isolated Tianji platform upgrade baseline",
        }, "POST")
        if result.strip() != "true":
            raise RuntimeError("Namespace creation failed")

    password = subprocess.run([
        "ssh", "-i", args.ssh_key, "-o", "BatchMode=yes", "-o", "ConnectTimeout=8",
        args.ssh_host, "cat /root/tianji-platform-upgrade-test/mysql-password",
    ], capture_output=True, text=True, check=True).stdout.strip()
    if len(password) != 48 or any(c not in "0123456789abcdef" for c in password):
        raise RuntimeError("Invalid task credential")

    def publish(data_id, obj):
        content = yaml.safe_dump(obj, allow_unicode=True, sort_keys=False)
        result = request("cs/configs", {
            "tenant": namespace, "group": GROUP, "dataId": data_id,
            "type": "yaml", "content": content,
        }, "POST")
        if result.strip() != "true":
            raise RuntimeError("Config publication failed: " + data_id)
        actual = None
        for attempt in range(20):
            try:
                actual = request("cs/configs", {"tenant": namespace, "group": GROUP, "dataId": data_id})
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
            if actual == content:
                break
            time.sleep(0.25)
        if actual != content:
            raise RuntimeError("Config read-back mismatch: " + data_id)
        print("Verified isolated config: " + data_id)

    for name in SHARED:
        data_id = "shared-" + name + ".yaml"
        source = request("cs/configs", {"tenant": "", "group": GROUP, "dataId": data_id})
        obj = yaml.safe_load(source)
        if not isinstance(obj, dict):
            raise RuntimeError("Expected mapping: " + data_id)
        if name == "mybatis":
            obj["spring"]["datasource"].update({
                "url": "jdbc:mysql://127.0.0.1:3306/${tj.jdbc.database}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai",
                "username": "tj_upgrade", "password": password,
                "hikari": {"maximum-pool-size": 3, "minimum-idle": 1},
            })
        elif name == "redis":
            # Old Redisson authenticates even for an empty password. Omit it entirely.
            obj["spring"]["redis"].pop("password", None)
            obj["spring"]["redis"].update({"host": "127.0.0.1", "port": 16379, "database": 0})
        elif name == "mq":
            obj["spring"]["rabbitmq"].update({
                "host": "127.0.0.1", "port": 5672, "virtual-host": NAMESPACE,
                "username": "tj_upgrade", "password": password,
            })
        elif name == "xxljob":
            # No admin connection: task executors must not register to the live scheduler.
            obj["tj"]["xxl-job"] = {
                "access-token": "", "admin": {"address": ""},
                "executor": {"appname": "${spring.application.name}", "port": 0,
                             "log-path": str(pathlib.Path("target/platform-upgrade/job").resolve()),
                             "log-retention-days": 3},
            }
        if args.platform == "boot3":
            if name == "redis":
                obj["spring"].setdefault("data", {})["redis"] = obj["spring"].pop("redis")
            elif name == "feign":
                feign = obj.pop("feign")
                sentinel = feign.pop("sentinel", None)
                feign["httpclient"].pop("enabled", None)
                feign["httpclient"]["hc5"] = {"enabled": True}
                obj.setdefault("spring", {}).setdefault("cloud", {})["openfeign"] = feign
                if sentinel is not None:
                    obj["feign"] = {"sentinel": sentinel}
            elif name == "mybatis":
                obj["mybatis-plus"]["configuration"]["default-enum-type-handler"] = "com.baomidou.mybatisplus.core.handlers.CompositeEnumTypeHandler"
                obj["mybatis-plus"]["global-config"].pop("field-strategy", None)
        publish(data_id, obj)

    if args.platform == "boot3":
        publish("shared-seata.yaml", {"seata": {
            "enabled": True, "tx-service-group": "tj_upgrade_tx_group",
            "registry": {"type": "file"}, "config": {"type": "file"},
            "service": {"vgroup-mapping": {"tj_upgrade_tx_group": "default"},
                        "grouplist": {"default": "127.0.0.1:8091"}},
        }})

    publish("shared-logs.yaml", {"logging": {
        "level": {"root": "INFO", "com.alibaba.nacos": "WARN"},
        "file": {"name": str(pathlib.Path("target/platform-upgrade/logs/${spring.application.name}.log").resolve())},
    }})
    for service in SERVICES:
        obj = {
            "spring": {"cloud": {"nacos": {
                "server-addr": "127.0.0.1:8848",
                "config": {"namespace": namespace},
                "discovery": {"namespace": namespace, "ip": "127.0.0.1"},
            }, "sentinel": {"enabled": False}}},
            "seata": {"enabled": False},
        }
        if service != "gateway":
            obj["tj"] = {"jdbc": {"database": "tj_upgrade_" + service}}
            if service == "search":
                if args.platform == "boot3":
                    password_file = pathlib.Path(__file__).resolve().parents[2] / "target/aigc-es/private/search-password.txt"
                    obj["spring"]["elasticsearch"] = {
                        "uris": ["http://127.0.0.1:19201"], "username": "tj_search_app",
                        "password": password_file.read_text(encoding="utf-8").strip(),
                    }
                else:
                    obj["spring"]["elasticsearch"] = {"uris": ["http://127.0.0.1:19200"]}
                obj["tj"]["search"] = {"course-index": "tj_upgrade_course_p4"}
                obj["tj"]["interests"] = {"top-number": 10}
            elif service == "aigc" and args.platform == "boot3":
                password_file = pathlib.Path(__file__).resolve().parents[2] / "target/aigc-es/private/application-password.txt"
                obj["spring"]["elasticsearch"] = {
                    "uris": "${ELASTICSEARCH_URIS:http://127.0.0.1:19201}",
                    "username": "${TJ_AIGC_ES_USERNAME:tj_aigc_app}",
                    "password": "${TJ_AIGC_ES_PASSWORD:" + password_file.read_text(encoding="utf-8").strip() + "}",
                }
        else:
            obj["tj"] = {"auth": {"exclude-path": ["/accounts/login", "/accounts/admin/login", "/accounts/refresh"]}}
        publish(service + "-service.yaml", obj)
    publish("platform-upgrade-framework-probe.yaml", {"upgrade": {"probe": {"marker": "isolated-framework-probe"}}})
    print("Ready: namespace " + namespace + "; public/dev configs were only read.")


if __name__ == "__main__":
    main()
