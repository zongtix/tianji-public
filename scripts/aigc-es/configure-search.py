"""Publish the shared ES connection without printing or committing credentials."""
import argparse
import datetime
import json
import pathlib
import subprocess
import urllib.parse
import urllib.request

import yaml

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--ssh-host', default='developer@tianji-dev.example.invalid')
parser.add_argument('--ssh-key', default=str(pathlib.Path.home() / '.ssh/id_ed25519'))
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
private = root / 'target/aigc-es/private'
private.mkdir(parents=True, exist_ok=True)
result = subprocess.run(['ssh', '-i', args.ssh_key, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
                         args.ssh_host, 'cat /root/tj-aigc-es/search-password'], capture_output=True, check=True)
password = result.stdout.decode().strip()
(private / 'search-password.txt').write_text(password)
base = 'http://127.0.0.1:8848/nacos/v1/cs/configs'
def nacos(params, write=False):
    query = urllib.parse.urlencode(params)
    req = urllib.request.Request(base if write else base + '?' + query,
                                 data=query.encode() if write else None)
    with urllib.request.urlopen(req, timeout=20) as response:
        return response.read().decode()

backup = {}
namespaces = ['tianji-local-boot3', 'tianji-platform-upgrade-test-boot3']
for namespace in namespaces:
    params = {'tenant': namespace, 'group': 'DEFAULT_GROUP', 'dataId': 'search-service.yaml'}
    backup[namespace] = nacos(params)
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
(private / ('nacos-before-shared-' + stamp + '.json')).write_text(json.dumps(backup))
for namespace in namespaces:
    params = {'tenant': namespace, 'group': 'DEFAULT_GROUP', 'dataId': 'search-service.yaml'}
    config = yaml.safe_load(backup[namespace]) or {}
    config.setdefault('spring', {})['elasticsearch'] = {
        'uris': ['http://127.0.0.1:19201'], 'username': 'tj_search_app', 'password': password}
    content = yaml.safe_dump(config, allow_unicode=True, sort_keys=False)
    assert nacos({**params, 'type': 'yaml', 'content': content}, write=True).strip() == 'true'
    assert yaml.safe_load(nacos(params)) == config
    print('Verified shared ES connection: ' + namespace + '/search-service.yaml')
print('Search credentials saved in ignored target/aigc-es/private/search-password.txt; not printed')
