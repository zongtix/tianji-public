"""Initialize Seata AT probe schema once, in the task database only."""
import argparse
import pathlib
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--ssh-host", required=True)
parser.add_argument("--ssh-key", required=True)
parser.add_argument("--exam", action="store_true", help="Also prepare the isolated exam service for cross-service AT checks")
args = parser.parse_args()
remote = 'docker exec -i mysql sh -c \'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -N -B tj_upgrade_learning\''
command = ["ssh", "-i", args.ssh_key, "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", args.ssh_host, remote]
query = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='tj_upgrade_learning' AND table_name='undo_log';"
exists = subprocess.run(command, input=query, text=True, capture_output=True, check=True).stdout.strip()
if exists == "0":
    sql = pathlib.Path(__file__).resolve().parent / "probes/platform/prepare-probe.sql"
    result = subprocess.run(command, input=sql.read_bytes(), capture_output=True)
    if result.returncode:
        raise RuntimeError("Test undo_log initialization failed; credentials suppressed")
elif exists != "1":
    raise RuntimeError("Unexpected test schema response")
print("Seata AT probe schema ready in tj_upgrade_learning only.")
if args.exam:
    exam_command = command[:-1] + [remote.replace("tj_upgrade_learning", "tj_upgrade_exam")]
    exists = subprocess.run(exam_command, input=query.replace("tj_upgrade_learning", "tj_upgrade_exam"),
                            text=True, capture_output=True, check=True).stdout.strip()
    if exists == "0":
        sql = pathlib.Path(__file__).resolve().parent / "probes/platform/prepare-probe.sql"
        exam_sql = sql.read_text(encoding="utf-8").replace("USE tj_upgrade_learning;", "USE tj_upgrade_exam;")
        result = subprocess.run(exam_command, input=exam_sql, text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError("Test exam undo_log initialization failed; credentials suppressed")
    elif exists != "1":
        raise RuntimeError("Unexpected test exam schema response")
    print("Seata AT business check schema ready in tj_upgrade_exam.")
