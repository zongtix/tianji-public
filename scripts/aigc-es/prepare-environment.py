"""Deploy the dedicated ES instance using the project's existing SSH host."""
import argparse
import pathlib
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ssh-host', default='developer@tianji-dev.example.invalid')
parser.add_argument('--ssh-key', default=str(pathlib.Path.home() / '.ssh/id_ed25519'))
args = parser.parse_args()
ssh = ['ssh', '-i', args.ssh_key, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
       '-o', 'ConnectTimeout=8', args.ssh_host]
script = pathlib.Path(__file__).with_name('prepare-es.sh').read_bytes().replace(b'\r\n', b'\n')
subprocess.run(ssh + ['umask 077; mkdir -p /root/tj-aigc-es; cat > /root/tj-aigc-es/prepare.sh'],
               input=script, check=True)
subprocess.run(ssh + ['bash /root/tj-aigc-es/prepare.sh'], check=True)
result = subprocess.run(ssh + [". /root/tj-aigc-es/runtime.env; printf '%s' \"$TJ_AIGC_ES_PASSWORD\""],
                        capture_output=True, check=True)
private = pathlib.Path(__file__).resolve().parents[2] / 'target/aigc-es/private'
private.mkdir(parents=True, exist_ok=True)
(private / 'application-password.txt').write_bytes(result.stdout)
print('Application password saved to ignored target/aigc-es/private/application-password.txt; not printed')
