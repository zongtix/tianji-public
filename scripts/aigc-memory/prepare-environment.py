"""Prepare the authorized remote MongoDB and additive MySQL schema; never print credentials."""
import argparse
import pathlib
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--ssh-host", default="developer@tianji-dev.example.invalid")
parser.add_argument("--ssh-key", default=str(pathlib.Path.home() / ".ssh/id_ed25519"))
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
ssh = ["ssh", "-i", args.ssh_key, "-o", "BatchMode=yes", "-o", "ConnectTimeout=8",
       "-o", "StrictHostKeyChecking=yes", args.ssh_host]

# Upload and execution are separate, so child processes cannot consume script input.
script = pathlib.Path(__file__).with_name("prepare-mongodb.sh").read_bytes().replace(b"\r\n", b"\n")
subprocess.run(ssh + ["umask 077; mkdir -p /root/tj-aigc-mongodb; cat > /root/tj-aigc-mongodb/prepare.sh"],
               input=script, check=True)
subprocess.run(ssh + ["bash /root/tj-aigc-mongodb/prepare.sh"], check=True)

schema = (root / "tj-aigc/src/main/resources/db/chat-memory.sql").read_bytes().replace(b"\r\n", b"\n")
remote = "docker exec -i mysql sh -c 'export MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\"; exec mysql -uroot --batch tj_aigc'"
subprocess.run(ssh + [remote], input=schema, check=True)
print("MySQL tj_aigc.chat_message prepared; existing tables and rows retained")

# Only this ignored file receives the application URI. No admin password is exported.
result = subprocess.run(ssh + [". /root/tj-aigc-mongodb/runtime.env; "
    "printf 'mongodb://tj_aigc_app:%s@127.0.0.1:27017/tj_aigc?authSource=tj_aigc' \"$TJ_AIGC_MONGO_APP_PASSWORD\""],
    capture_output=True, check=True)
private = root / "target/aigc-memory/private"
private.mkdir(parents=True, exist_ok=True)
(private / "mongodb-uri.txt").write_bytes(result.stdout)
print("Application URI saved in ignored target/aigc-memory/private/mongodb-uri.txt; not printed")
