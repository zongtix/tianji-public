"""Create the one task-owned search index, copying mapping only (never course data)."""
import json
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:19200/"
INDEX = "tj_upgrade_course_p4"
OWNER = "tianji-platform-upgrade-test"


def request(path, method="GET", body=None):
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)


def main():
    try:
        mapping = request(INDEX + "/_mapping")[INDEX]["mappings"]
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        mapping = request("course/_mapping")["course"]["mappings"]
        mapping.setdefault("_meta", {})["owner"] = OWNER
        settings = request("course/_settings")["course"]["settings"]["index"]
        request(INDEX, "PUT", {"mappings": mapping, "settings": {
            "number_of_shards": 1, "number_of_replicas": 0,
            **({"analysis": settings["analysis"]} if "analysis" in settings else {}),
        }})
        mapping = request(INDEX + "/_mapping")[INDEX]["mappings"]
    if mapping.get("_meta", {}).get("owner") != OWNER:
        raise RuntimeError("Refusing to use an unowned search index")
    print("Verified task-owned index: " + INDEX + "; mapping only, no business data copied.")


if __name__ == "__main__":
    main()
