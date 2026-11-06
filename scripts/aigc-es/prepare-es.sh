#!/usr/bin/env bash
# Dedicated RAG learning instance; credentials stay outside source control.
set -euo pipefail
umask 077
dir=/root/tj-aigc-es
name=tj-aigc-es
volume=tj-aigc-es-data
image=docker.elastic.co/elasticsearch/elasticsearch:8.19.23
mkdir -p "$dir"
if docker container inspect "$name" >/dev/null 2>&1; then
    [ "$(docker inspect --format '{{index .Config.Labels "com.tianji.component"}}' "$name")" = aigc-rag ] || { echo 'Container name conflict' >&2; exit 1; }
    [ -f "$dir/runtime.env" ] || { echo 'Existing credentials missing; refusing to reset' >&2; exit 1; }
    docker start "$name" >/dev/null
else
    if ss -H -lnt '( sport = :19201 )' | grep -q .; then echo 'Port 19201 occupied' >&2; exit 1; fi
    if docker volume inspect "$volume" >/dev/null 2>&1; then echo 'Existing volume without container; inspect manually before reuse' >&2; exit 1; fi
    available=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
    [ "$available" -ge 1048576 ] || { echo 'At least 1 GiB available host memory required' >&2; exit 1; }
    if [ ! -f "$dir/runtime.env" ]; then
        printf 'ELASTIC_PASSWORD=%s\nTJ_AIGC_ES_PASSWORD=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" > "$dir/runtime.env"
    fi
    if [ ! -f "$dir/image.txt" ]; then
        docker pull "$image"
        docker image inspect "$image" --format '{{index .RepoDigests 0}}' > "$dir/image.txt"
    fi
    docker volume create --label com.tianji.component=aigc-rag "$volume" >/dev/null
    docker run -d --name "$name" --label com.tianji.component=aigc-rag \
        --restart unless-stopped --memory 768m --memory-swap 768m --cpus 1 \
        --log-opt max-size=10m --log-opt max-file=3 \
        -p 127.0.0.1:19201:9200 -v "$volume:/usr/share/elasticsearch/data" \
        --env-file "$dir/runtime.env" \
        -e discovery.type=single-node -e cluster.name=tj-aigc-rag -e node.name=tj-aigc-es \
        -e xpack.security.enabled=true -e xpack.security.http.ssl.enabled=false \
        -e xpack.security.transport.ssl.enabled=false -e xpack.security.enrollment.enabled=false \
        -e xpack.ml.enabled=false -e ingest.geoip.downloader.enabled=false \
        -e node.store.allow_mmap=false -e 'ES_JAVA_OPTS=-Xms256m -Xmx256m' \
        "$(cat "$dir/image.txt")" >/dev/null
fi
set -a
. "$dir/runtime.env"
set +a
python3 - <<'PY'
import base64
import json
import os
import time
import urllib.error
import urllib.request
import uuid

base = 'http://127.0.0.1:19201'
def request(method, path, data=None, app=False):
    user = 'tj_aigc_app' if app else 'elastic'
    password = os.environ['TJ_AIGC_ES_PASSWORD' if app else 'ELASTIC_PASSWORD']
    token = base64.b64encode(f'{user}:{password}'.encode()).decode()
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(base + path, data=body, method=method,
        headers={'Authorization': 'Basic ' + token, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)

for attempt in range(90):
    try:
        info = request('GET', '/')
        break
    except (OSError, urllib.error.URLError):
        time.sleep(2)
else:
    raise SystemExit('ES startup timed out; inspect docker logs tj-aigc-es')

request('GET', '/_cluster/health?wait_for_status=yellow&timeout=30s')
request('PUT', '/_security/role/tj_aigc_rag', {
    'cluster': ['monitor'], 'indices': [{'names': ['tj_aigc_*'],
    'privileges': ['read', 'write', 'create_index', 'view_index_metadata', 'delete_index']}]
})
request('PUT', '/_security/user/tj_aigc_app', {
    'password': os.environ['TJ_AIGC_ES_PASSWORD'], 'roles': ['tj_aigc_rag']
})
index = 'tj_aigc_setup_check_' + uuid.uuid4().hex
created = False
try:
    request('PUT', '/' + index, {
        'settings': {'number_of_shards': 1, 'number_of_replicas': 0},
        'mappings': {'properties': {'text': {'type': 'text'},
            'embedding': {'type': 'dense_vector', 'dims': 3, 'index': True, 'similarity': 'cosine'}}}
    }, app=True)
    created = True
    request('PUT', '/' + index + '/_doc/near?refresh=true', {'text': 'rag setup check', 'embedding': [1, 0, 0]}, app=True)
    request('PUT', '/' + index + '/_doc/far?refresh=true', {'text': 'other document', 'embedding': [0, 1, 0]}, app=True)
    result = request('POST', '/' + index + '/_search', {
        'knn': {'field': 'embedding', 'query_vector': [1, 0, 0], 'k': 1, 'num_candidates': 2}
    }, app=True)
    assert result['hits']['hits'][0]['_id'] == 'near', 'Unexpected kNN result'
    try:
        urllib.request.urlopen(base + '/', timeout=5)
    except urllib.error.HTTPError as error:
        assert error.code == 401
    else:
        raise AssertionError('Unauthenticated access must fail')
    print('ES ' + info['version']['number'] + ': application write, kNN search and authentication verified')
finally:
    if created:
        request('DELETE', '/' + index, app=True)
print('Temporary verification index removed; persistent data volume retained')
PY
