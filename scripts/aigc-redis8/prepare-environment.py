"""Provision the dedicated Redis 8 instance and save its credentials in ignored target/."""
import argparse
import json
import pathlib
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--ssh-host", default="developer@tianji-dev.example.invalid")
parser.add_argument("--ssh-key", default=str(pathlib.Path.home() / ".ssh/id_ed25519"))
parser.add_argument("--verify-restart", action="store_true",
                    help="Restart only the dedicated vector-store container to verify persistence")
args = parser.parse_args()
scripts = pathlib.Path(__file__).resolve().parent
ssh = ["ssh", "-i", args.ssh_key, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
       "-o", "ConnectTimeout=8", args.ssh_host]
subprocess.run(ssh + ["python3 -"], input=(scripts / "prepare-redis8.py").read_bytes(), check=True)
verification = "python3 -" + (" --restart-container tj-aigc-redis8" if args.verify_restart else "")
subprocess.run(ssh + [verification],
               input=(scripts / "verify-vector-store.py").read_bytes(), check=True)
secret = subprocess.check_output(ssh + ["cat /root/tj-aigc-redis8/password.txt"]).strip()
private = scripts.parents[1] / "target/aigc-redis8/private"
private.mkdir(parents=True, exist_ok=True)
(private / "password.txt").write_bytes(secret + b"\n")
(private / "connection.json").write_text(json.dumps({
    "host": "127.0.0.1", "port": 36379, "username": "default", "database": 0,
    "passwordFile": str(private / "password.txt"), "sshHost": args.ssh_host,
}, indent=2), encoding="utf-8")
print("Connection and password saved under ignored target/aigc-redis8/private/; password not printed")
