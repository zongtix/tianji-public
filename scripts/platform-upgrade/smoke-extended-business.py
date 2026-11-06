"""Run task-only search, trade, exam and data HTTP/SQL checks. Credentials stay private."""
import argparse
import json
import pathlib
import subprocess
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-host", required=True)
    parser.add_argument("--ssh-key", required=True)
    parser.add_argument("--report", default="target/platform-upgrade/http-p4-business.json")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    fixture = json.loads((root / "target/platform-upgrade/private/login.json").read_text(encoding="utf-8"))
    report = []
    token = None

    def call(method, path, data=None, base="http://127.0.0.1:11010", authenticated=True):
        headers = {"Host": "www.tianji.test:11010", "Content-Type": "application/json"}
        if token and authenticated:
            headers["authorization"] = token
        req = urllib.request.Request(base + path, method=method, headers=headers,
                                     data=json.dumps(data).encode() if data is not None else None)
        try:
            response = urllib.request.urlopen(req, timeout=35)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read()
        return response.status, json.loads(raw) if raw else {}

    def check(name, passed, result=None):
        status, body = result if result else (None, {})
        report.append({"case": name, "passed": bool(passed), "http": status, "code": body.get("code")})
        print(("PASS " if passed else "FAIL ") + name)
        if not passed:
            raise AssertionError(name + " failed; response values suppressed")

    def ok(name, result):
        status, body = result
        check(name, status == 200 and body.get("code") == 200, result)
        return body.get("data")

    def sql(database, statement):
        if database not in {"trade", "exam"}:
            raise RuntimeError("Unapproved SQL database")
        remote = ('docker exec -i mysql sh -c \'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; '
                  'exec mysql -uroot -N -B tj_upgrade_' + database + "'")
        result = subprocess.run(["ssh", "-i", args.ssh_key, "-o", "BatchMode=yes", "-o", "ConnectTimeout=8",
                                 args.ssh_host, remote], input=statement, capture_output=True, text=True, timeout=25)
        if result.returncode:
            raise RuntimeError("Task-only SQL failed; details suppressed")
        return result.stdout.strip()

    trigger = None
    try:
        result = call("GET", "/ts/carts", authenticated=False)
        check("anonymous cart requires login", result[0] == 401, result)
        token = ok("extended business login", call("POST", "/as/accounts/login", dict(fixture, type=1, rememberMe=False)))
        index = "tj_upgrade_course_p4"
        metadata = call("GET", "/" + index + "/_mapping", base="http://127.0.0.1:19200")[1]
        check("owned search index", metadata[index]["mappings"].get("_meta", {}).get("owner") == "tianji-platform-upgrade-test")
        call("PUT", "/" + index + "/_doc/900000020?refresh=wait_for", {
            "id": 900000020, "name": "Upgrade Fixture Course", "teacher": 900000002,
            "categoryIdLv1": 900000100, "categoryIdLv2": 900000101, "categoryIdLv3": 900000102,
            "free": False, "type": 2, "sold": 1, "price": 1000, "score": 50,
            "sections": 0, "coverUrl": "/test/fixture.png", "publishTime": "2026-10-12T12:00:00",
        }, base="http://127.0.0.1:19200")
        data = ok("search through gateway", call("GET", "/ss/courses/portal?keyword=Upgrade&pageNo=1&pageSize=10"))
        check("search resolves actual teacher over Feign", len(data["list"]) == 1 and data["list"][0]["teacher"] == "Upgrade Admin Fixture")
        data = ok("search category filter", call("GET", "/ss/courses/portal?categoryIdLv2=900000101&free=false&type=2"))
        check("search filter result", int(data["total"]) == 1)
        data = ok("search empty filter", call("GET", "/ss/courses/portal?free=true"))
        check("search filter excludes paid course", not data["list"])

        for name in ("add cart with course Feign", "duplicate cart is idempotent"):
            ok(name, call("POST", "/ts/carts", {"courseId": 900000020}))
        carts = ok("query cart with actual price", call("GET", "/ts/carts"))
        rows = [row for row in carts if str(row["courseId"]) == "900000020"]
        check("one cart and course price", len(rows) == 1 and rows[0]["nowPrice"] == 1000)
        confirm = ok("pre-place order over course Feign", call("GET", "/ts/orders/prePlaceOrder?courseIds=900000020"))
        check("pre-place price", confirm["totalAmount"] == 1000)
        order_id = int(confirm["orderId"])
        ok("place order and detail transaction", call("POST", "/ts/orders/placeOrder", {"orderId": order_id, "courseIds": [900000020]}))
        check("order and detail committed", sql("trade", f"SELECT (SELECT COUNT(*) FROM `order` WHERE id={order_id}) + (SELECT COUNT(*) FROM order_detail WHERE order_id={order_id});") == "2")
        carts = ok("paid selection removed from cart", call("GET", "/ts/carts"))
        check("cart removed after place order", not any(str(row["courseId"]) == "900000020" for row in carts))
        for name in ("cancel unpaid order", "duplicate cancellation is idempotent"):
            ok(name, call("PUT", f"/ts/orders/{order_id}/cancel"))
        ok("query my order page", call("GET", "/ts/orders/page?pageNo=1&pageSize=10"))

        confirm = ok("prepare rollback order", call("GET", "/ts/orders/prePlaceOrder?courseIds=900000020"))
        rollback_id = int(confirm["orderId"])
        candidate = "upgrade_p4_" + uuid.uuid4().hex[:12]
        sql("trade", f"DELIMITER $$\nCREATE TRIGGER {candidate} BEFORE INSERT ON order_detail FOR EACH ROW BEGIN IF NEW.order_id={rollback_id} THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Intentional isolated rollback check'; END IF; END$$\nDELIMITER ;\n")
        trigger = candidate
        result = call("POST", "/ts/orders/placeOrder", {"orderId": rollback_id, "courseIds": [900000020]})
        check("detail failure rejects order", result[1].get("code") != 200, result)
        check("detail failure rolls back order row", sql("trade", f"SELECT (SELECT COUNT(*) FROM `order` WHERE id={rollback_id}) + (SELECT COUNT(*) FROM order_detail WHERE order_id={rollback_id});") == "0")
        sql("trade", "DROP TRIGGER " + trigger)
        trigger = None

        biz_id = 900098001
        ok("exam replace question links", call("POST", "/es/question-biz/list", [{"bizId": biz_id, "questionId": 900098010}, {"bizId": biz_id, "questionId": 900098011}]))
        links = ok("exam query question links", call("GET", f"/es/question-biz/biz/{biz_id}"))
        check("exam saved both links", len(links) == 2)
        ok("exam replace is transactional", call("POST", "/es/question-biz/list", [{"bizId": biz_id, "questionId": 900098012}]))
        links = ok("exam query replaced links", call("GET", f"/es/question-biz/biz/{biz_id}"))
        check("exam old links removed", len(links) == 1 and str(links[0]["questionId"]) == "900098012")
        sql("exam", f"DELETE FROM question_biz WHERE biz_id={biz_id};")

        admin_fixture = dict(fixture, username="upgrade_admin_fixture", type=1, rememberMe=False)
        token = ok("dashboard admin login", call("POST", "/as/accounts/admin/login", admin_fixture))
        ok("data writes to isolated Redis", call("PUT", "/ds/data/today/set", {"version": 0, "visits": 1.25, "orderAmount": 2.5, "orderNum": 7, "stuNewNum": 3}))
        data = ok("data reads from isolated Redis", call("GET", "/ds/data/today"))
        check("dashboard number serialization", data["visits"] == 1.25 and data["orderNum"] == 7)
        for route in ("ss", "ts", "es", "ds"):
            result = call("GET", "/" + route + "/v3/api-docs/default")
            check(route + " actual OpenAPI", result[0] == 200 and "openapi" in result[1] and "paths" in result[1], result)
    finally:
        if trigger:
            sql("trade", "DROP TRIGGER IF EXISTS " + trigger)
        destination = root / args.report
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Saved credential-free business report: " + str(destination))


if __name__ == "__main__":
    main()
