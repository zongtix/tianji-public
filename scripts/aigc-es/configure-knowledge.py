"""Enable DashScope embeddings and the ES knowledge store without storing API keys."""
import datetime
import base64
import json
import os
import pathlib
import urllib.parse
import urllib.error
import urllib.request

import yaml

if not os.getenv('DASHSCOPE_API_KEY') or not os.getenv('DASHSCOPE_BASE_URL'):
    raise RuntimeError('Set DASHSCOPE_API_KEY and DASHSCOPE_BASE_URL before enabling the knowledge store')

root = pathlib.Path(__file__).resolve().parents[2]
private = root / 'target/aigc-es/private'
base = 'http://127.0.0.1:8848/nacos/v1/cs/configs'

# Prepare the empty production index without embedding or importing business data.
es_password = (private / 'application-password.txt').read_text(encoding='utf-8').strip()
token = base64.b64encode(('tj_aigc_app:' + es_password).encode()).decode()
index = 'tj_aigc_knowledge_v4_1024'
def es(method, path, data=None):
    req = urllib.request.Request('http://127.0.0.1:19201' + path, method=method,
        data=None if data is None else json.dumps(data).encode(),
        headers={'Authorization': 'Basic ' + token, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)

try:
    metadata = es('GET', '/' + index)
except urllib.error.HTTPError as error:
    if error.code != 404:
        raise
    es('PUT', '/' + index, {
        'settings': {'number_of_shards': 1, 'number_of_replicas': 0},
        'mappings': {'properties': {'id': {'type': 'keyword'}, 'content': {'type': 'text'},
            'metadata': {'type': 'object'},
            'embedding': {'type': 'dense_vector', 'dims': 1024, 'index': True, 'similarity': 'cosine'}}}
    })
    metadata = es('GET', '/' + index)
field = metadata[index]['mappings']['properties']['embedding']
if field.get('type') != 'dense_vector' or field.get('dims') != 1024 or field.get('similarity') != 'cosine':
    raise RuntimeError('Existing knowledge index does not match the configured vector schema; no data overwritten')
print('Verified production knowledge index: ' + index + ', 1024 dimensions, cosine')

def nacos(params, write=False):
    query = urllib.parse.urlencode(params)
    req = urllib.request.Request(base if write else base + '?' + query,
                                 data=query.encode() if write else None)
    with urllib.request.urlopen(req, timeout=20) as response:
        return response.read().decode()

original = {}
for namespace in ['tianji-local-boot3', 'tianji-platform-upgrade-test-boot3']:
    original[namespace] = nacos({'tenant': namespace, 'group': 'DEFAULT_GROUP', 'dataId': 'aigc-service.yaml'})
stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
(private / ('nacos-before-knowledge-' + stamp + '.json')).write_text(json.dumps(original), encoding='utf-8')
for namespace, before in original.items():
    config = yaml.safe_load(before) or {}
    config.setdefault('tj', {}).setdefault('ai', {})['knowledge'] = {
        'enabled': '${TJ_AI_KNOWLEDGE_ENABLED:true}',
        'embedding': {'api-key': '${DASHSCOPE_API_KEY:}', 'base-url': '${DASHSCOPE_BASE_URL:}',
                      'model': 'text-embedding-v4', 'dimensions': 1024},
        'index-name': 'tj_aigc_knowledge_v4_1024',
    }
    config.setdefault('spring', {}).setdefault('ai', {}).setdefault('model', {})['embedding'] = 'none'
    content = yaml.safe_dump(config, allow_unicode=True, sort_keys=False)
    params = {'tenant': namespace, 'group': 'DEFAULT_GROUP', 'dataId': 'aigc-service.yaml'}
    if nacos({**params, 'type': 'yaml', 'content': content}, write=True).strip() != 'true':
        raise RuntimeError('Nacos publication failed: ' + namespace)
    if yaml.safe_load(nacos(params)) != config:
        raise RuntimeError('Nacos read-back mismatch: ' + namespace)
    print('Verified knowledge configuration: ' + namespace + '; API key stays in environment variables')
