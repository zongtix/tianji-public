"""Read existing Nacos datasource credentials into an ignored local test file, without changing Nacos."""
import argparse
import json
import os
import pathlib
import re
import urllib.parse
import urllib.request

import yaml

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--nacos-url", default="http://127.0.0.1:8848")
parser.add_argument("--namespace", default="tianji-local-boot3")
args = parser.parse_args()
query = urllib.parse.urlencode({"tenant": args.namespace, "group": "DEFAULT_GROUP", "dataId": "shared-mybatis.yaml"})
with urllib.request.urlopen(args.nacos_url.rstrip("/") + "/nacos/v1/cs/configs?" + query, timeout=10) as response:
    source = yaml.safe_load(response.read())["spring"]["datasource"]
def resolve(value):
    def replace(match):
        name, default = match.group(1), match.group(2)
        if name == "tj.jdbc.database":
            return "tj_aigc"
        override = os.environ.get(name.upper().replace(".", "_").replace("-", "_"))
        if override is not None:
            return override
        if default is not None:
            return default
        raise RuntimeError("Datasource contains an unresolved configuration placeholder")
    return re.sub(r"\$\{([^}:]+)(?::([^}]*))?\}", replace, str(value))

url = resolve(source["url"]).replace("autoReconnect=true", "autoReconnect=false")
url += "&connectTimeout=5000&socketTimeout=10000"
if "${" in url or "/tj_aigc" not in url:
    raise RuntimeError("Unexpected datasource URL; refusing to select a different database")
private = pathlib.Path(__file__).resolve().parents[2] / "target/aigc-memory/private"
private.mkdir(parents=True, exist_ok=True)
(private / "mysql.json").write_text(json.dumps({"url": url, "username": resolve(source["username"]),
                                             "password": resolve(source["password"])}), encoding="utf-8")
print("Existing tj_aigc datasource saved to ignored private test file; credentials not printed")
