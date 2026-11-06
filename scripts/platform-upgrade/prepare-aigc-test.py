"""Apply the AI schema only to the task-owned test database; never exports credentials."""
import argparse
import pathlib
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ssh-host', required=True)
parser.add_argument('--ssh-key', required=True)
args = parser.parse_args()
schema = (pathlib.Path(__file__).resolve().parents[2] / 'tj-aigc/src/main/resources/db/schema.sql').read_bytes()
sql = b"CREATE DATABASE IF NOT EXISTS tj_upgrade_aigc CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;\n"
sql += b"GRANT ALL PRIVILEGES ON tj_upgrade_aigc.* TO 'tj_upgrade'@'%';\nUSE tj_upgrade_aigc;\n" + schema
remote = "docker exec -i mysql sh -c 'export MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\"; exec mysql -uroot --batch'"
result = subprocess.run(['ssh', '-i', args.ssh_key, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8',
                         args.ssh_host, remote], input=sql.replace(b'\r\n', b'\n'), capture_output=True)
if result.returncode:
    raise RuntimeError('Task AI schema initialization failed; inspect the private server console')
print('Verified task-owned database tj_upgrade_aigc and AI schema; no production data copied.')
