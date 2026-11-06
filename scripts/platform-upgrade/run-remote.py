"""Upload a task shell script as LF bytes, then execute it; avoids stdin loss/CRLF."""
import argparse
import pathlib
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("script", choices=("inspect-environment.sh", "prepare-test-environment.sh", "prepare-seata-test.sh"))
parser.add_argument("--ssh-host", required=True)
parser.add_argument("--ssh-key", required=True)
args = parser.parse_args()
source = pathlib.Path(__file__).resolve().parent / args.script
remote_dir = "/root/tianji-platform-upgrade-test"
remote_path = remote_dir + "/" + args.script
remote = "umask 077; mkdir -p " + remote_dir + "; cat > " + remote_path + "; bash " + remote_path
result = subprocess.run(["ssh", "-i", args.ssh_key, "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", args.ssh_host, remote],
                        input=source.read_bytes().replace(b"\r\n", b"\n"))
raise SystemExit(result.returncode)
