"""Run on the existing Linux Docker host; provision only the dedicated vector store."""
import hashlib
import json
import os
import pathlib
import secrets
import socket
import subprocess

NAME = "tj-aigc-redis8"
VOLUME = NAME + "-data"
LABEL = "aigc-vector-redis8"
ROOT = pathlib.Path("/root") / NAME
# Redis 8.10.2; checked against the official Docker Hub redis:8 manifest.
DIGEST = "sha256:2c2dff791878316e3b083188be0d578423b24813cdda5de0e80ea4f7b3e6bfd6"
IMAGE_SOURCES = ["redis@" + DIGEST, "m.daocloud.io/docker.io/library/redis@" + DIGEST]


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


os.umask(0o077)
existing = subprocess.run(["docker", "container", "inspect", NAME], capture_output=True)
if existing.returncode == 0:
    container = json.loads(existing.stdout)[0]
    if container["Config"]["Labels"].get("com.tianji.component") != LABEL:
        raise SystemExit("Container name belongs to another component")
    if not (ROOT / "password.txt").is_file():
        raise SystemExit("Existing container has no recorded credentials; refusing to replace it")
    docker("start", NAME)
    print("Retained and started existing dedicated Redis 8 container")
    raise SystemExit(0)

with socket.socket() as probe:
    if probe.connect_ex(("127.0.0.1", 36379)) == 0:
        raise SystemExit("Remote port 36379 is occupied")
if subprocess.run(["docker", "volume", "inspect", VOLUME], capture_output=True).returncode == 0:
    raise SystemExit("Data volume already exists; refusing to attach it to a new container automatically")
available_kb = int(next(line.split()[1] for line in pathlib.Path("/proc/meminfo").read_text().splitlines()
                        if line.startswith("MemAvailable:")))
if available_kb < 384 * 1024:
    raise SystemExit("Less than 384 MiB available; retaining existing services")

ROOT.mkdir(mode=0o700, exist_ok=True)
password_file = ROOT / "password.txt"
if not password_file.exists():
    password_file.write_text(secrets.token_hex(24) + "\n")
password_file.chmod(0o600)
password_hash = hashlib.sha256(password_file.read_text().strip().encode()).hexdigest()
config_dir = ROOT / "conf"
config_dir.mkdir(mode=0o755, exist_ok=True)
config_dir.chmod(0o755)
config_file = config_dir / "redis.conf"
config_file.write_text(
    "bind 0.0.0.0\nprotected-mode yes\nport 6379\ndir /data\n"
    "appendonly yes\nappendfsync everysec\nsave 300 1\n"
    "maxmemory 128mb\nmaxmemory-policy noeviction\n"
    f"user default on #{password_hash} ~* &* +@all\n"
)
# Only the password hash is in the mounted configuration, never the plaintext secret.
config_file.chmod(0o644)
image_file = ROOT / "image.txt"
if not image_file.exists():
    image = next((source for source in IMAGE_SOURCES if subprocess.run(
        ["docker", "image", "inspect", source], capture_output=True).returncode == 0), None)
    if image is None:
        subprocess.run(["docker", "pull", IMAGE_SOURCES[0]], check=True, timeout=180)
        image = IMAGE_SOURCES[0]
    image_file.write_text(image + "\n")
image = image_file.read_text().strip()
if image not in IMAGE_SOURCES:
    raise SystemExit("Image does not match the verified official Redis digest")
docker("volume", "create", "--label", "com.tianji.component=" + LABEL, VOLUME)
docker("run", "-d", "--name", NAME, "--label", "com.tianji.component=" + LABEL,
       "--restart", "unless-stopped", "--memory", "256m", "--memory-swap", "256m", "--cpus", "0.5",
       "--log-opt", "max-size=10m", "--log-opt", "max-file=2",
       "-p", "127.0.0.1:36379:6379", "-v", VOLUME + ":/data",
       "-v", str(config_dir) + ":/usr/local/etc/tianji-redis:ro",
       image, "redis-server", "/usr/local/etc/tianji-redis/redis.conf")
print("Dedicated Redis 8 created on remote loopback port 36379; authentication and AOF enabled")
