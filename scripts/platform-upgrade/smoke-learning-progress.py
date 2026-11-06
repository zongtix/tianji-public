"""Verify free enrollment -> MQ -> lesson -> progress/outbox in task databases only."""
import argparse
import datetime
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-host", required=True)
    parser.add_argument("--ssh-key", required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    fixture = json.loads((root / "target/platform-upgrade/private/login.json").read_text(encoding="utf-8"))
    course = 9001000000 + int(uuid.uuid4().hex[:6], 16) * 10
    chapter, section = course + 1, course + 2
    report = []
    token = None

    def sql(database, query):
        assert database in {"course", "learning"}
        remote = ('docker exec -i mysql sh -c \'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N -B tj_upgrade_' + database + "'")
        result = subprocess.run(["ssh", "-i", args.ssh_key, "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", args.ssh_host, remote],
                                input=query, capture_output=True, text=True, timeout=25)
        if result.returncode:
            raise RuntimeError("Task-only learning SQL failed; values suppressed")
        return result.stdout.strip()

    def call(method, path, data=None):
        headers = {"Host": "www.tianji.test:11010", "Content-Type": "application/json"}
        if token:
            headers["authorization"] = token
        req = urllib.request.Request("http://127.0.0.1:11010" + path, method=method, headers=headers,
                                     data=json.dumps(data).encode() if data is not None else None)
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        body = response.read()
        return response.status, json.loads(body) if body else {}

    def check(name, passed):
        report.append({"case": name, "passed": bool(passed)})
        print(("PASS " if passed else "FAIL ") + name)
        if not passed:
            raise AssertionError(name)

    def ok(name, response):
        status, body = response
        check(name, status == 200 and body.get("code") == 200)
        return body.get("data")

    try:
        sql("course", f"""
INSERT INTO course (id,name,cover_url,first_cate_id,second_cate_id,third_cate_id,price,free,status,purchase_end_time,step,valid_duration,section_num,dep_id,creater,updater)
VALUES ({course},'Upgrade Learning Fixture','/test/fixture.png',900000100,900000101,900000102,0,1,2,'2099-01-01',5,12,1,0,900000002,900000002);
INSERT INTO course_catalogue (id,name,course_id,type,parent_catalogue_id,media_duration,c_index,dep_id,creater,updater)
VALUES ({chapter},'Upgrade Chapter',{course},1,0,0,1,0,900000002,900000002),
({section},'Upgrade Video',{course},2,{chapter},100,1,0,900000002,900000002);
""")
        token = ok("learning flow login", call("POST", "/as/accounts/login", dict(fixture, type=1, rememberMe=False)))
        ok("free course enrollment", call("POST", f"/ts/orders/freeCourse/{course}"))
        lesson = None
        for attempt in range(30):
            status, body = call("GET", f"/ls/learning-records/course/{course}")
            lesson = body.get("data")
            if status == 200 and body.get("code") == 200 and lesson:
                break
            time.sleep(1)
        check("enrollment MQ creates learning lesson", lesson is not None and lesson.get("id") is not None)
        lesson_id = int(lesson["id"])
        result = call("POST", f"/ts/orders/freeCourse/{course}")
        check("duplicate free enrollment rejected", result[1].get("code") != 200)
        check("one lesson for user and course", sql("learning", f"SELECT COUNT(*) FROM learning_lesson WHERE user_id=900000001 AND course_id={course};") == "1")
        record = {"sectionType": 1, "lessonId": lesson_id, "sectionId": section, "moment": 60,
                  "duration": 1, "commitTime": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")}
        ok("learning progress uses server catalogue duration", call("POST", "/ls/learning-records", record))
        ok("duplicate progress submission", call("POST", "/ls/learning-records", record))
        check("completed section increments once", sql("learning", f"SELECT learned_sections FROM learning_lesson WHERE id={lesson_id};") == "1")
        check("one finished record", sql("learning", f"SELECT COUNT(*) FROM learning_record WHERE lesson_id={lesson_id} AND section_id={section} AND finished=1;") == "1")
        check("one learning outbox message", sql("learning", f"SELECT COUNT(*) FROM points_message WHERE message_key='learning:{lesson_id}:{section}';") == "1")
        ok("learning record query after commit", call("GET", f"/ls/learning-records/course/{course}"))
        token = ok("other user login", call("POST", "/as/accounts/admin/login", dict(fixture, username="upgrade_admin_fixture", type=1, rememberMe=False)))
        result = call("POST", "/ls/learning-records", record)
        check("other user cannot submit lesson progress", result[1].get("code") != 200)
        check("foreign submission cannot increment section", sql("learning", f"SELECT learned_sections FROM learning_lesson WHERE id={lesson_id};") == "1")
    finally:
        destination = root / "target/platform-upgrade/http-p4-learning.json"
        destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Saved credential-free learning report")


if __name__ == "__main__":
    main()
