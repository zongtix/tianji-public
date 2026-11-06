"""Exercise the isolated gateway login chain; never prints tokens/passwords."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import datetime
import http.cookies
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", default="target/platform-upgrade/http-baseline.json")
    parser.add_argument("--learning", action="store_true", help="Also exercise isolated sign-in/points/boards")
    parser.add_argument("--likes", action="store_true", help="Also exercise isolated cross-service like state")
    parser.add_argument("--commerce", action="store_true", help="Also exercise course/Q&A/coupon baseline")
    parser.add_argument("--ssh-host")
    parser.add_argument("--ssh-key")
    args = parser.parse_args()
    if args.commerce and not (args.ssh_host and args.ssh_key):
        parser.error("--commerce requires --ssh-host and --ssh-key for test-only SQL assertions")
    root = pathlib.Path(__file__).resolve().parents[2]
    fixture = json.loads((root / "target/platform-upgrade/private/login.json").read_text(encoding="utf-8"))
    report = []

    def call(method, path, payload=None, token=None, cookie=None, forged=False):
        headers = {"Host": "www.tianji.test:11010", "Origin": "http://www.tianji.test"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["authorization"] = token
        if cookie:
            headers["Cookie"] = cookie
        if forged:
            headers["user-info"] = "900000001"
        req = urllib.request.Request("http://127.0.0.1:11010" + path,
                                     data=json.dumps(payload).encode() if payload is not None else None,
                                     headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        body = response.read()
        return response.status, json.loads(body) if body else {}, response.headers

    def check(name, passed, status, body):
        report.append({"case": name, "passed": bool(passed), "http": status, "code": body.get("code")})
        print(("PASS " if passed else "FAIL ") + name + ": HTTP " + str(status) + ", code " + str(body.get("code")))
        if not passed:
            raise AssertionError("Isolated HTTP baseline failed: " + name)

    try:
        for name, token, forged in [("anonymous user info", None, False), ("invalid JWT", "invalid.jwt", False),
                                    ("forged identity header", None, True)]:
            status, body, _ = call("GET", "/us/users/me", token=token, forged=forged)
            check(name, status == 401, status, body)
        login = dict(fixture, type=1, rememberMe=False)
        status, body, _ = call("POST", "/as/accounts/login", dict(login, password="incorrect-fixture-password"))
        check("wrong password", status == 200 and body.get("code") == 400, status, body)
        status, body, headers = call("POST", "/as/accounts/login", login)
        token = body.get("data")
        cookies = http.cookies.SimpleCookie()
        for value in headers.get_all("Set-Cookie", []):
            cookies.load(value)
        refresh = cookies.get("refresh")
        check("login and HttpOnly refresh cookie", status == 200 and body.get("code") == 200
              and isinstance(token, str) and token.count(".") == 2 and refresh is not None and refresh["httponly"], status, body)
        status, body, _ = call("GET", "/us/users/me", token=token)
        check("authenticated user info", status == 200 and body.get("code") == 200
              and str(body.get("data", {}).get("id")) == "900000001", status, body)
        status, body, headers = call("GET", "/as/accounts/refresh", cookie="refresh=" + refresh.value)
        check("refresh token", status == 200 and body.get("code") == 200 and isinstance(body.get("data"), str), status, body)
        for value in headers.get_all("Set-Cookie", []):
            cookies.load(value)
        status, body, _ = call("POST", "/as/accounts/logout", token=body["data"])
        check("logout", status == 200 and body.get("code") == 200, status, body)
        status, body, _ = call("GET", "/as/accounts/refresh", cookie="refresh=" + cookies["refresh"].value)
        check("logged-out refresh rejected", not (status == 200 and body.get("code") == 200), status, body)
        if args.learning or args.likes or args.commerce:
            status, body, _ = call("POST", "/as/accounts/login", login)
            token = body.get("data")
            check("business login", status == 200 and body.get("code") == 200, status, body)
        if args.learning:
            status, body, _ = call("GET", "/ls/sign-records", token=token)
            check("sign calendar", status == 200 and body.get("code") == 200
                  and isinstance(body.get("data", {}).get("signRecords"), list), status, body)
            already_signed = body["data"]["signRecords"][-1] == 1
            if not already_signed:
                status, body, _ = call("POST", "/ls/sign-records", token=token)
                check("first sign in", status == 200 and body.get("code") == 200
                      and body.get("data", {}).get("signDays", 0) > 0, status, body)
            status, body, _ = call("POST", "/ls/sign-records", token=token)
            check("duplicate sign in rejected", status == 200 and body.get("code") != 200, status, body)
            status, body, _ = call("GET", "/ls/sign-records", token=token)
            check("calendar reflects committed sign in", status == 200 and body.get("code") == 200
                  and body.get("data", {}).get("signRecords", [0])[-1] == 1, status, body)
            for attempt in range(20):
                status, body, _ = call("GET", "/ls/points/today", token=token)
                if status == 200 and body.get("code") == 200 and any(row.get("points", 0) >= 1 for row in body.get("data", [])):
                    break
                time.sleep(1)
            check("outbox MQ consumption credits points", status == 200 and body.get("code") == 200
                  and any(row.get("points", 0) >= 1 for row in body.get("data", [])), status, body)
            status, body, _ = call("GET", "/ls/boards?pageNo=0&pageSize=10", token=token)
            check("invalid board pagination", status == 200 and body.get("code") == 400, status, body)
            status, body, _ = call("GET", "/ls/boards?pageNo=1&pageSize=10", token=token)
            check("current monthly board", status == 200 and body.get("code") == 200
                  and isinstance(body.get("data", {}).get("boardList"), list), status, body)
        if args.likes:
            for name in ("first like", "repeated like"):
                status, body, _ = call("POST", "/rs/likes", {"bizId": 900000011, "bizType": "QA", "liked": True}, token=token)
                check(name, status == 200 and body.get("code") == 200, status, body)
            status, body, _ = call("GET", "/rs/likes/status?bizType=QA&bizIds=900000011", token=token)
            check("liked identity", status == 200 and body.get("code") == 200
                  and [str(i) for i in body.get("data", [])] == ["900000011"], status, body)
            status, body, _ = call("GET", "/rs/likes/times?bizType=QA&bizIds=900000011", token=token)
            check("idempotent like count", status == 200 and body.get("code") == 200
                  and body.get("data", {}).get("900000011") == 1, status, body)
            for name in ("unlike", "repeated unlike"):
                status, body, _ = call("POST", "/rs/likes", {"bizId": 900000011, "bizType": "QA", "liked": False}, token=token)
                check(name, status == 200 and body.get("code") == 200, status, body)
            status, body, _ = call("GET", "/rs/likes/times?bizType=QA&bizIds=900000011", token=token)
            check("unlike count", status == 200 and body.get("code") == 200
                  and body.get("data", {}).get("900000011", 0) == 0, status, body)
            status, body, _ = call("POST", "/rs/likes", {"bizId": 900000011, "bizType": "UNKNOWN", "liked": True}, token=token)
            check("unsupported like type rejected", status == 200 and body.get("code") == 400, status, body)
            status, body, _ = call("POST", "/rs/likes", {"bizId": 900000099, "bizType": "QA", "liked": True}, token=token)
            check("missing reply rejected", status == 200 and body.get("code") == 400, status, body)
        if args.commerce:
            def test_sql(sql):
                remote = 'docker exec -i mysql sh -c \'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N -B tj_upgrade_promotion\''
                result = subprocess.run(["ssh", "-i", args.ssh_key, "-o", "BatchMode=yes", "-o", "ConnectTimeout=8",
                                         args.ssh_host, remote], input=sql, capture_output=True, text=True, timeout=20)
                if result.returncode:
                    raise RuntimeError("Test-only SQL assertion failed")
                return [line.split("\t") for line in result.stdout.strip().splitlines()]

            status, body, _ = call("GET", "/cs/course/900000020?withCatalogue=false&withTeachers=false", token=token)
            check("course basic query", status == 200 and body.get("code") == 200
                  and body.get("data", {}).get("name") == "Upgrade Fixture Course", status, body)
            status, body, _ = call("GET", "/cs/course/900000099?withCatalogue=false&withTeachers=false", token=token)
            check("missing course rejected", status == 200 and body.get("code") == 400, status, body)
            title = "Upgrade question " + uuid.uuid4().hex[:8]
            status, body, _ = call("POST", "/ls/questions", {"courseId": 900000020, "chapterId": 900000021,
                                  "sectionId": 900000022, "title": title, "description": "Synthetic test question", "anonymity": False}, token=token)
            check("question creation", status == 200 and body.get("code") == 200, status, body)
            status, body, _ = call("GET", "/ls/questions/page?courseId=900000020&onlyMine=true&pageNo=1&pageSize=100", token=token)
            question = next((row for row in body.get("data", {}).get("list", []) if row.get("title") == title), None)
            check("question pagination and identity", status == 200 and body.get("code") == 200 and question is not None, status, body)
            status, body, _ = call("POST", "/ls/replies", {"questionId": question["id"], "content": "Synthetic test answer", "anonymity": False}, token=token)
            check("answer creation", status == 200 and body.get("code") == 200, status, body)
            status, body, _ = call("GET", "/prs/coupons/page?pageNo=1&pageSize=10", token=token)
            check("student admin access rejected", status == 200 and body.get("code") == 403, status, body)
            status, body, _ = call("POST", "/as/accounts/admin/login", dict(login, username="upgrade_admin_fixture"))
            admin = body.get("data")
            check("admin login", status == 200 and body.get("code") == 200 and isinstance(admin, str), status, body)

            def create_and_issue(obtain_way):
                name = "Upgrade_" + uuid.uuid4().hex[:8]
                status, body, _ = call("POST", "/prs/coupons", {"name": name, "specific": False, "discountType": 3,
                                      "thresholdAmount": 0, "discountValue": 100, "maxDiscountAmount": 0,
                                      "totalNum": 2, "userLimit": 1, "obtainWay": obtain_way}, token=admin)
                check("coupon creation " + str(obtain_way), status == 200 and body.get("code") == 200, status, body)
                status, body, _ = call("GET", "/prs/coupons/page?pageNo=1&pageSize=10&name=" + name, token=admin)
                rows = body.get("data", {}).get("list", [])
                check("coupon query " + str(obtain_way), status == 200 and body.get("code") == 200 and len(rows) == 1, status, body)
                coupon_id = str(rows[0]["id"])
                if not coupon_id.isdigit():
                    raise RuntimeError("Unexpected coupon id")
                issue = {"issueEndTime": (datetime.datetime.now() + datetime.timedelta(days=1)).strftime("%Y-%m-%d %H:%M:%S"), "termDays": 7}
                status, body, _ = call("PUT", "/prs/coupons/" + coupon_id + "/issue", issue, token=admin)
                check("coupon issuance " + str(obtain_way), status == 200 and body.get("code") == 200, status, body)
                status, body, _ = call("PUT", "/prs/coupons/" + coupon_id + "/issue", issue, token=admin)
                check("repeat issuance rejected " + str(obtain_way), status == 200 and body.get("code") == 400, status, body)
                return coupon_id

            public_id = create_and_issue(1)
            with ThreadPoolExecutor(max_workers=8) as executor:
                results = list(executor.map(lambda _: call("POST", "/prs/user-coupons/" + public_id + "/receive", token=token), range(8)))
            successes = sum(status == 200 and body.get("code") == 200 for status, body, _ in results)
            check("concurrent claiming respects user limit", successes == 1
                  and all(status == 200 and body.get("code") in (200, 400) for status, body, _ in results), 200, {})
            rows = test_sql("SELECT issue_num,(SELECT COUNT(*) FROM user_coupon WHERE coupon_id=" + public_id + ") FROM coupon WHERE id=" + public_id + ";")
            check("claim stock and relationship committed once", rows == [["1", "1"]], 200, {})
            exchange_id = create_and_issue(2)
            codes = []
            for attempt in range(10):
                codes = test_sql("SELECT id,code FROM exchange_code WHERE exchange_target_id=" + exchange_id + " ORDER BY id;")
                if len(codes) == 2:
                    break
                time.sleep(1)
            check("asynchronous exchange code generation", len(codes) == 2, 200, {})
            status, body, _ = call("POST", "/prs/user-coupons/exchange", {"code": codes[0][1]}, token=token)
            check("exchange code redemption", status == 200 and body.get("code") == 200, status, body)
            status, body, _ = call("POST", "/prs/user-coupons/exchange", {"code": codes[0][1]}, token=token)
            check("repeat redemption rejected", status == 200 and body.get("code") == 400, status, body)
            status, body, _ = call("POST", "/prs/user-coupons/exchange", {"code": codes[1][1]}, token=token)
            check("redemption over user limit rejected", status == 200 and body.get("code") == 400, status, body)
            rows = test_sql("SELECT status FROM exchange_code WHERE id=" + codes[1][0] + "; SELECT issue_num,(SELECT COUNT(*) FROM user_coupon WHERE coupon_id=" + exchange_id + ") FROM coupon WHERE id=" + exchange_id + ";")
            check("failed redemption rolls back code and stock", rows == [["1"], ["1", "1"]], 200, {})
    finally:
        path = root / args.report
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
