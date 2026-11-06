"""Deploy matching Kibana on the existing Tianji server."""
import pathlib
import subprocess

ssh = ['ssh', '-i', str(pathlib.Path.home() / '.ssh/id_ed25519'), '-o', 'BatchMode=yes',
       '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=8', 'developer@tianji-dev.example.invalid']
script = pathlib.Path(__file__).with_name('prepare-kibana.sh').read_bytes().replace(b'\r\n', b'\n')
subprocess.run(ssh + ['umask 077; mkdir -p /root/tj-kibana; cat > /root/tj-kibana/prepare.sh'], input=script, check=True)
subprocess.run(ssh + ['bash /root/tj-kibana/prepare.sh'], check=True)
result = subprocess.run(ssh + ['cat /root/tj-kibana/login-password'], capture_output=True, check=True)
private = pathlib.Path(__file__).resolve().parents[2] / 'target/aigc-es/private'
private.mkdir(parents=True, exist_ok=True)
(private / 'kibana-password.txt').write_bytes(result.stdout)
print('Login password saved to ignored target/aigc-es/private/kibana-password.txt; not printed')
