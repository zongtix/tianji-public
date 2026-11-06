"""Add AIGC's own ES connection to existing Nacos configs, preserving other settings."""
import datetime
import json
import pathlib
import urllib.parse
import urllib.request

import yaml

root = pathlib.Path(__file__).resolve().parents[2]
private = root / 'target/aigc-es/private'
password = (private / 'application-password.txt').read_text(encoding='utf-8').strip()
if not password:
    raise RuntimeError('AIGC ES password is missing')
base = 'http://127.0.0.1:8848/nacos/v1/cs/configs'

def nacos(params, write=False):
    query = urllib.parse.urlencode(params)
    req = urllib.request.Request(base if write else base + '?' + query,
                                 data=query.encode() if write else None)
    with urllib.request.urlopen(req, timeout=20) as response:
        return response.read().decode()

namespaces = ['tianji-local-boot3', 'tianji-platform-upgrade-test-boot3']
original = {}
for namespace in namespaces:
    original[namespace] = nacos({'tenant': namespace, 'group': 'DEFAULT_GROUP', 'dataId': 'aigc-service.yaml'})
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
(private / ('nacos-before-aigc-es-' + stamp + '.json')).write_text(json.dumps(original), encoding='utf-8')
for namespace, before in original.items():
    params = {'tenant': namespace, 'group': 'DEFAULT_GROUP', 'dataId': 'aigc-service.yaml'}
    config = yaml.safe_load(before) or {}
    config.setdefault('spring', {})['elasticsearch'] = {
        'uris': '${ELASTICSEARCH_URIS:http://127.0.0.1:19201}',
        'username': '${TJ_AIGC_ES_USERNAME:tj_aigc_app}',
        'password': '${TJ_AIGC_ES_PASSWORD:' + password + '}',
    }
    content = yaml.safe_dump(config, allow_unicode=True, sort_keys=False)
    if nacos({**params, 'type': 'yaml', 'content': content}, write=True).strip() != 'true':
        raise RuntimeError('Nacos publication failed: ' + namespace)
    if yaml.safe_load(nacos(params)) != config:
        raise RuntimeError('Nacos read-back mismatch: ' + namespace)
    print('Verified AIGC ES configuration: ' + namespace + '/aigc-service.yaml; credentials not printed')
