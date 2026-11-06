"""Prepare synthetic AI course fixtures in the existing tj_course database.

Connection JSON arrives on stdin from seed-ai-courses.ps1 and is never logged.
Default: inspect only. --rehearse inserts and verifies, then rolls back.
--apply commits one transaction. Existing rows must match; nothing is overwritten.
"""
import argparse
import datetime
import hashlib
import html
import json
import pathlib
import sys
import urllib.parse

import pymysql

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURE = pathlib.Path(__file__).with_name("fixtures") / "ai-demo-courses.json"
TABLES = ("course", "course_draft", "course_content", "course_content_draft",
          "course_catalogue", "course_catalogue_draft")
MARKER = "AI联调演示数据 aigc-course-demo-v1；仅用于开发验证，不含视频，不提供真实授课。"


def load_fixture():
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    if fixture["dataset"] != "aigc-course-demo-v1":
        raise ValueError("Unexpected fixture dataset")
    ids = [int(course["id"]) for course in fixture["courses"]]
    if len(ids) != 12 or len(set(ids)) != 12 or ids != list(range(910000000000010001, 910000000000010013)):
        raise ValueError("Unexpected fixture course IDs")
    for course in fixture["courses"]:
        if len(course["chapters"]) != 4 or course["price"] < 0:
            raise ValueError("Invalid course outline or price")
    return fixture


def category_path(cursor, category_id):
    result = []
    for level in (3, 2, 1):
        cursor.execute("SELECT id,parent_id,level,status,deleted FROM category WHERE id=%s", (category_id,))
        row = cursor.fetchone()
        if not row or row["level"] != level or row["status"] != 1 or row["deleted"] != 0:
            raise ValueError("Missing or disabled category: " + str(category_id))
        result.append(row["id"])
        category_id = row["parent_id"]
    if category_id != 0:
        raise ValueError("Invalid category ancestry")
    return list(reversed(result))


def build_rows(cursor, fixture):
    # Reuse a known existing cover and ownership fields, without changing that course.
    cursor.execute("SELECT cover_url,dep_id,creater,updater FROM course WHERE id=%s AND deleted=0",
                   (1589905661084430337,))
    template = cursor.fetchone()
    if not template or not template["cover_url"]:
        raise ValueError("Expected development course template is unavailable")
    rows = {table: [] for table in TABLES}
    for course in fixture["courses"]:
        course_id = int(course["id"])
        first, second, third = category_path(cursor, course["categoryId"])
        common = dict(id=course_id, name="【AI演示】" + course["name"], course_type=2,
                      cover_url=template["cover_url"], first_cate_id=first, second_cate_id=second,
                      third_cate_id=third, free=int(course["price"] == 0), price=course["price"],
                      template_type=1, template_url="", status=course.get("status", 2),
                      purchase_start_time=datetime.datetime(2020, 1, 1),
                      purchase_end_time=datetime.datetime(2020 if course.get("expired") else 2099, 12, 31),
                      step=5, score=0, media_duration=0, valid_duration=12, section_num=0,
                      dep_id=template["dep_id"], creater=template["creater"], updater=template["updater"],
                      deleted=0, publish_time=datetime.datetime(2026, 10, 22))
        rows["course"].append(common)
        rows["course_draft"].append(dict(common, can_update=1, c_version=1))
        detail = "<p>" + html.escape(MARKER) + "</p><p>" + html.escape(course["intro"]) + "</p>"
        detail += "<p>适用人群：" + html.escape(course["audience"]) + "</p><ol>"
        detail += "".join("<li>" + html.escape(chapter) + "</li>" for chapter in course["chapters"]) + "</ol>"
        if len(detail) > 1024 or len(MARKER + course["intro"]) > 512 or len(course["audience"]) > 512:
            raise ValueError("Fixture text exceeds database column size")
        content = dict(id=course_id, course_introduce=MARKER + course["intro"],
                       use_people=course["audience"], course_detail=detail, dep_id=template["dep_id"],
                       creater=template["creater"], updater=template["updater"], deleted=0)
        rows["course_content"].append(content)
        rows["course_content_draft"].append(dict(content))
        for index, chapter in enumerate(course["chapters"], 1):
            chapter_id = 910000000001000000 + (course_id - 910000000000010000) * 100 + index
            chapter_row = dict(id=chapter_id, name=chapter, course_id=course_id, type=1,
                               parent_catalogue_id=0, c_index=index, dep_id=template["dep_id"],
                               creater=template["creater"], updater=template["updater"], deleted=0)
            rows["course_catalogue"].append(chapter_row)
            rows["course_catalogue_draft"].append(dict(chapter_row, can_update=1))
    return rows


def check_rows(cursor, rows):
    missing = {table: [] for table in TABLES}
    for table, entries in rows.items():
        for row in entries:
            columns = ",".join("`" + key + "`" for key in row)
            cursor.execute(f"SELECT {columns} FROM `{table}` WHERE id=%s", (row["id"],))
            existing = cursor.fetchone()
            if existing is None:
                missing[table].append(row)
            elif existing != row:
                raise ValueError(f"Existing data differs; refusing to overwrite {table}/{row['id']}")
    return missing


def original_snapshot(cursor, rows):
    result = {}
    for table, entries in rows.items():
        ids = [entry["id"] for entry in entries]
        cursor.execute(f"SELECT * FROM `{table}` WHERE id NOT IN ({','.join(['%s'] * len(ids))}) ORDER BY id", ids)
        data = json.dumps(cursor.fetchall(), sort_keys=True, default=str, ensure_ascii=False)
        result[table] = hashlib.sha256(data.encode()).hexdigest()
    return result


def run(connection, fixture, mode):
    with connection.cursor() as cursor:
        if mode == "inspect":
            cursor.execute("SET TRANSACTION READ ONLY")
        connection.begin()
        rows = build_rows(cursor, fixture)
        missing = check_rows(cursor, rows)
        report = dict(dataset=fixture["dataset"], database="tj_course", mode=mode,
                      planned_rows={table: len(entries) for table, entries in rows.items()},
                      new_rows={table: len(entries) for table, entries in missing.items()},
                      course_ids=[course["id"] for course in fixture["courses"]],
                      fixture_sha256=hashlib.sha256(FIXTURE.read_bytes()).hexdigest())
        before = original_snapshot(cursor, rows)
        if mode != "inspect":
            for table, entries in missing.items():
                for row in entries:
                    columns = ",".join("`" + key + "`" for key in row)
                    sql = f"INSERT INTO `{table}` ({columns}) VALUES ({','.join(['%s'] * len(row))})"
                    cursor.execute(sql, list(row.values()))
            if any(check_rows(cursor, rows).values()):
                raise ValueError("Fixture verification failed")
        if original_snapshot(cursor, rows) != before:
            raise ValueError("Existing course data changed; refusing to commit")
        report["original_rows_unchanged"] = True
        if mode == "apply":
            connection.commit()
        else:
            connection.rollback()
        report["committed"] = mode == "apply"
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true")
    mode.add_argument("--rehearse", action="store_true")
    args = parser.parse_args()
    connection = None
    try:
        fixture = load_fixture()
        config = json.load(sys.stdin)
        location = urllib.parse.urlparse(config["url"].removeprefix("jdbc:"))
        if location.scheme != "mysql" or location.path != "/tj_course":
            raise ValueError("Only the existing tj_course database is allowed")
        connection = pymysql.connect(host=location.hostname, port=location.port or 3306,
                                     user=config["username"], password=config["password"], database="tj_course",
                                     charset="utf8mb4", connect_timeout=5, read_timeout=15, write_timeout=15,
                                     cursorclass=pymysql.cursors.DictCursor, autocommit=False)
        selected = "apply" if args.apply else "rehearse" if args.rehearse else "inspect"
        report = run(connection, fixture, selected)
        output = ROOT / "target/ai-course-demo"
        output.mkdir(parents=True, exist_ok=True)
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        (output / (selected + "-" + stamp + ".json")).write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))
    except ValueError as error:
        print("Course fixture check failed: " + str(error), file=sys.stderr)
        return 1
    except Exception as error:
        print("Course fixture operation failed: " + type(error).__name__ + "; connection details suppressed", file=sys.stderr)
        return 1
    finally:
        if connection is not None:
            connection.rollback()
            connection.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
