"""Check the isolated gateway's real OpenAPI aggregation and response schemas."""
import argparse
import json
from pathlib import Path
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", default="target/platform-upgrade/http-openapi.json")
    args = parser.parse_args()
    report = []

    def fetch(path):
        request = urllib.request.Request("http://127.0.0.1:11010" + path, headers={"Host": "www.tianji.test"})
        with urllib.request.urlopen(request, timeout=40) as response:
            return response.status, response.read()

    def check(name, passed):
        report.append({"case": name, "passed": bool(passed)})
        print(("PASS " if passed else "FAIL ") + name)
        if not passed:
            raise AssertionError("OpenAPI contract failed: " + name)

    def check_refs(document):
        def walk(obj):
            if isinstance(obj, dict):
                ref = obj.get("$ref", "")
                if ref.startswith("#/"):
                    target = document
                    for part in ref[2:].split("/"):
                        target = target[part.replace("~1", "/").replace("~0", "~")]
                for child in obj.values():
                    walk(child)
            elif isinstance(obj, list):
                for child in obj:
                    walk(child)
        walk(document)

    try:
        status, body = fetch("/openapi-config")
        config = json.loads(body)
        urls = {item["name"]: item["url"] for item in config["urls"]}
        check("gateway aggregation config", status == 200 and len(urls) == len(config["urls"]))
        for service, prefix in {"user": "us", "auth": "as", "course": "cs", "learning": "ls",
                                "remark": "rs", "promotion": "prs"}.items():
            path = "/" + prefix + "/v3/api-docs/default"
            check(service + " grouped route", urls.get(service + "-service") == path)
            status, body = fetch(path)
            document = json.loads(body)
            check(service + " raw OpenAPI JSON", status == 200 and document.get("openapi", "").startswith("3.")
                  and "code" not in document and bool(document.get("paths")))
            check_refs(document)
            check(service + " schema references resolve", True)
            contracts = {"user": ("/users/me", "get"), "auth": ("/accounts/login", "post"),
                         "learning": ("/sign-records", "post")}
            if service in contracts:
                endpoint, method = contracts[service]
                content = document["paths"][endpoint][method]["responses"]["200"]["content"]
                check(service + " documented response envelope", any(
                    {"code", "msg", "data", "requestId"}.issubset(media.get("schema", {}).get("properties", {}))
                    for media in content.values()))
        status, body = fetch("/swagger-ui/index.html")
        check("gateway Swagger UI", status == 200 and b"swagger-ui" in body)
        status, body = fetch("/swagger-ui/swagger-initializer.js")
        check("UI loads aggregated config", status == 200 and b"/openapi-config" in body)
        # The compatibility entry follows its redirect to the same UI.
        status, body = fetch("/doc.html")
        check("existing doc.html entry", status == 200 and b"swagger-ui" in body)
    finally:
        path = Path(args.report)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
