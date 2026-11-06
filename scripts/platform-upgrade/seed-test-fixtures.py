"""Seed only synthetic user/auth fixtures in task databases. No production data.

Uses BCrypt from the packaged user service, so no extra crypto library is needed.
The generated test login password stays outside Git under target/platform-upgrade.
"""
import argparse
import json
import pathlib
import secrets
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", required=True)
    parser.add_argument("--ssh-host", required=True)
    parser.add_argument("--ssh-key", required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    private = root / "target/platform-upgrade/private"
    private.mkdir(parents=True, exist_ok=True)
    fixture = private / "login.json"
    if not fixture.exists():
        fixture.write_text(json.dumps({"username": "upgrade_fixture", "password": secrets.token_hex(16)}), encoding="utf-8")
    login = json.loads(fixture.read_text(encoding="utf-8"))
    with zipfile.ZipFile(root / "tj-user/target/tj-user.jar") as jar:
        libs = []
        for entry in jar.namelist():
            if entry.startswith("BOOT-INF/lib/") and any(name in entry for name in ("spring-security-crypto-", "spring-jcl-")):
                path = private / pathlib.PurePosixPath(entry).name
                path.write_bytes(jar.read(entry))
                libs.append(str(path))
    java_source = private / "FixtureHash.java"
    java_source.write_text('''
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class FixtureHash {
    public static void main(String[] args) throws Exception {
        String password = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        System.out.print(new BCryptPasswordEncoder().encode(password));
    }
}
''', encoding="utf-8")
    hashed = subprocess.run([
        str(pathlib.Path(args.java_home) / "bin/java.exe"), "-cp", ";".join(libs), str(java_source),
    ], input=login["password"], capture_output=True, text=True, check=True).stdout.strip()
    if not hashed.startswith("$2a$") or len(hashed) != 60:
        raise RuntimeError("Unexpected BCrypt output")
    sql = f"""
USE tj_upgrade_user;
INSERT INTO user (id,username,cell_phone,password,type,status)
VALUES (900000001,'upgrade_fixture','19900000001','{hashed}',2,1)
ON DUPLICATE KEY UPDATE password=VALUES(password);
INSERT INTO user (id,username,cell_phone,password,type,status)
VALUES (900000002,'upgrade_admin_fixture','19900000002','{hashed}',1,1)
ON DUPLICATE KEY UPDATE password=VALUES(password);
INSERT IGNORE INTO user_detail (id,type,name,role_id) VALUES (900000001,2,'Upgrade Fixture',2);
INSERT IGNORE INTO user_detail (id,type,name,role_id) VALUES (900000002,1,'Upgrade Admin Fixture',1);
USE tj_upgrade_auth;
INSERT IGNORE INTO privilege (id,intro,method,uri,internal) VALUES
(900000001,'Upgrade fixture user info','GET','/users/me',0),
(900000002,'Upgrade fixture sign in','POST','/sign-records',0),
(900000003,'Upgrade fixture sign calendar','GET','/sign-records',0),
(900000004,'Upgrade fixture likes','POST','/likes',0),
(900000005,'Upgrade fixture points','GET','/points/today',0);
INSERT IGNORE INTO role_privilege (id,role_id,privilege_id) VALUES
(900000001,2,900000001),(900000002,2,900000002),(900000003,2,900000003),
(900000004,2,900000004),(900000005,2,900000005);
INSERT IGNORE INTO privilege (id,intro,method,uri,internal) VALUES
(900000006,'Upgrade fixture admin coupon creation','POST','/coupons',0),
(900000007,'Upgrade fixture admin coupon page','GET','/coupons/page',0),
(900000008,'Upgrade fixture admin coupon issue','PUT','/coupons/*/issue',0),
(900000009,'Upgrade fixture coupon claiming','POST','/user-coupons/*/receive',0),
(900000010,'Upgrade fixture coupon exchange','POST','/user-coupons/exchange',0);
INSERT IGNORE INTO role_privilege (id,role_id,privilege_id) VALUES
(900000006,1,900000006),(900000007,1,900000007),(900000008,1,900000008),
(900000009,2,900000009),(900000010,2,900000010);
USE tj_upgrade_learning;
INSERT IGNORE INTO interaction_question (id,title,description,course_id,chapter_id,section_id,user_id,latest_answer_id,answer_times)
VALUES (900000010,'Upgrade fixture question','Synthetic upgrade regression question',900000020,900000021,900000022,900000001,900000011,1);
INSERT IGNORE INTO interaction_reply (id,question_id,user_id,content)
VALUES (900000011,900000010,900000001,'Synthetic upgrade regression answer');
USE tj_upgrade_course;
INSERT IGNORE INTO course (id,name,cover_url,first_cate_id,price,status,purchase_end_time,step,valid_duration,section_num,dep_id,creater,updater)
VALUES (900000020,'Upgrade Fixture Course','/test/fixture.png',900000100,1000,2,'2099-01-01',5,12,0,0,900000002,900000002);
"""
    remote = 'docker exec -i mysql sh -c \'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot\''
    result = subprocess.run([
        "ssh", "-i", args.ssh_key, "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", args.ssh_host, remote,
    ], input=sql, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError("Fixture insertion failed (remote SQL error; credentials suppressed)")
    print("Synthetic fixtures ready in tj_upgrade_user/auth/learning/course; login saved privately.")


if __name__ == "__main__":
    main()
