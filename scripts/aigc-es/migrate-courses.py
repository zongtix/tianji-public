"""Run on the ES host. Back up and migrate user indices without deleting old data.

--cutover freezes old user indices and compares every document, mapping, alias,
stored script and selected phrase-search result. Stop the old ES separately only
after application tests pass.
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import pathlib
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cutover', action='store_true')
args = parser.parse_args()
directory = pathlib.Path('/root/tj-aigc-es')
secrets = dict(line.split('=', 1) for line in (directory / 'runtime.env').read_text().splitlines())
token = base64.b64encode(('elastic:' + secrets['ELASTIC_PASSWORD']).encode()).decode()

def request(method, path, data=None, old=False, ndjson=False):
    headers = {} if old else {'Authorization': 'Basic ' + token}
    if data is not None:
        headers['Content-Type'] = 'application/x-ndjson' if ndjson else 'application/json'
    body = data.encode() if ndjson else (None if data is None else json.dumps(data).encode())
    base = 'http://127.0.0.1:' + ('9200' if old else '19201')
    with urllib.request.urlopen(urllib.request.Request(base + path, data=body, method=method, headers=headers), timeout=45) as result:
        return json.load(result)

def documents(index, old):
    page = request('POST', '/' + index + '/_search?scroll=1m', {'size': 500, 'sort': ['_doc'], 'query': {'match_all': {}}}, old)
    scroll = page.get('_scroll_id')
    output = {}
    try:
        while page['hits']['hits']:
            for hit in page['hits']['hits']:
                output[hit['_id']] = hit['_source']
            page = request('POST', '/_search/scroll', {'scroll': '1m', 'scroll_id': scroll}, old)
            scroll = page.get('_scroll_id', scroll)
    finally:
        if scroll:
            request('DELETE', '/_search/scroll', {'scroll_id': [scroll]}, old)
    return output

def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'))

indices = [item['index'] for item in request('GET', '/_cat/indices?format=json', old=True) if not item['index'].startswith('.')]
assert set(indices) == {'course', 'tj_upgrade_course_p4'}, 'Unexpected user indices; review scope before migrating'
backup = directory / 'backups' / datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup.mkdir(parents=True, exist_ok=False)
snapshot = {}
scripts = request('GET', '/_cluster/state/metadata?filter_path=metadata.stored_scripts', old=True)['metadata'].get('stored_scripts', {})
frozen = []
complete = False
try:
    for index in indices:
        if args.cutover:
            request('PUT', '/' + index + '/_block/write', old=True)
            frozen.append(index)
        metadata = request('GET', '/' + index, old=True)[index]
        docs = documents(index, True)
        snapshot[index] = {'metadata': metadata, 'documents': docs}
    payload = canonical({'indices': snapshot, 'stored_scripts': scripts})
    (backup / 'snapshot.json').write_text(payload, encoding='utf-8')
    (backup / 'sha256.txt').write_text(hashlib.sha256(payload.encode()).hexdigest() + '\n')
    print('Backup saved: ' + str(backup))
    if not args.cutover:
        raise SystemExit(0)

    # The migration is a one-time operation; never overwrite an existing index.
    for index, entry in snapshot.items():
        try:
            request('GET', '/' + index)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
        else:
            raise RuntimeError('Destination already exists: ' + index + '; inspect before retrying')
        metadata = entry['metadata']
        settings = metadata['settings']['index']
        clean_settings = {key: settings[key] for key in ['number_of_shards', 'number_of_replicas', 'analysis'] if key in settings}
        request('PUT', '/' + index, {'settings': clean_settings, 'mappings': metadata['mappings'], 'aliases': metadata['aliases']})
        lines = []
        for identifier, source in entry['documents'].items():
            lines.extend([json.dumps({'create': {'_index': index, '_id': identifier}}), json.dumps(source)])
        if lines:
            result = request('POST', '/_bulk?refresh=true', '\n'.join(lines) + '\n', ndjson=True)
            assert not result['errors'], 'Bulk migration failed: ' + canonical(result)
        assert documents(index, False) == entry['documents'], 'Document mismatch: ' + index
        target = request('GET', '/' + index)[index]
        assert target['mappings'] == metadata['mappings'], 'Mapping mismatch'
        assert target['aliases'] == metadata['aliases'], 'Alias mismatch'
        for text in ['Java', '优惠券', '微服务', 'java']:
            query = {'query': {'match_phrase': {'name': text}}, 'size': 100}
            left = request('POST', '/' + index + '/_search', query, old=True)
            right = request('POST', '/' + index + '/_search', query)
            assert {hit['_id'] for hit in left['hits']['hits']} == {hit['_id'] for hit in right['hits']['hits']}, 'Search mismatch'
        print(index + ': verified ' + str(len(entry['documents'])) + ' documents, mappings, aliases and phrase-search results')
    for name, script in scripts.items():
        request('PUT', '/_scripts/' + name, {'script': script})
        assert request('GET', '/_scripts/' + name)['script'] == script
    password_path = directory / 'search-password'
    if not password_path.exists():
        import secrets as random_secrets
        password_path.write_text(random_secrets.token_hex(24))
        os.chmod(password_path, 0o600)
    request('PUT', '/_security/role/tj_course_search', {'cluster': ['monitor'], 'indices': [{
        'names': ['course', 'tj_upgrade_course_*'], 'privileges': ['read', 'write', 'create_index', 'view_index_metadata', 'delete_index', 'maintenance']}]})
    request('PUT', '/_security/user/tj_search_app', {'password': password_path.read_text(), 'roles': ['tj_course_search']})
    (directory / 'migration-backup.txt').write_text(str(backup))
    complete = True
    print('Migration verified. Source indices remain write-blocked until cutover; old container and data retained.')
finally:
    if not complete:
        for index in frozen:
            request('PUT', '/' + index + '/_settings', {'index.blocks.write': False}, old=True)
