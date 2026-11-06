#!/usr/bin/env bash
# Replace only the newly created ES container; retain its data volume and predecessor.
set -euo pipefail
umask 077
dir=/root/tj-aigc-es
name=tj-aigc-es
previous=tj-aigc-es-before-shared
image=$(cat "$dir/shared-image.txt")
[ "$(docker inspect --format '{{index .Config.Labels "com.tianji.component"}}' "$name")" = aigc-rag ] || exit 1
if docker container inspect "$previous" >/dev/null 2>&1; then echo 'Backup container already exists; inspect before retrying' >&2; exit 1; fi
docker stop "$name" >/dev/null
docker rename "$name" "$previous"
docker update --restart=no "$previous" >/dev/null
docker run -d --name "$name" --label com.tianji.component=aigc-rag \
    --restart unless-stopped --memory 1g --memory-swap 1g --cpus 1 \
    --log-opt max-size=10m --log-opt max-file=3 \
    -p 127.0.0.1:19201:9200 -v tj-aigc-es-data:/usr/share/elasticsearch/data \
    --env-file "$dir/runtime.env" \
    -e discovery.type=single-node -e cluster.name=tj-aigc-rag -e node.name=tj-aigc-es \
    -e xpack.security.enabled=true -e xpack.security.http.ssl.enabled=false \
    -e xpack.security.transport.ssl.enabled=false -e xpack.security.enrollment.enabled=false \
    -e xpack.ml.enabled=false -e ingest.geoip.downloader.enabled=false \
    -e node.store.allow_mmap=false -e 'ES_JAVA_OPTS=-Xms384m -Xmx384m' \
    "$image" >/dev/null
set -a
. "$dir/runtime.env"
set +a
python3 - <<'PY'
import base64, json, os, time, urllib.request
token = base64.b64encode(('elastic:' + os.environ['ELASTIC_PASSWORD']).encode()).decode()
for attempt in range(150):
    try:
        req = urllib.request.Request('http://127.0.0.1:19201/_nodes/plugins', headers={'Authorization': 'Basic ' + token})
        with urllib.request.urlopen(req, timeout=5) as result:
            nodes = json.load(result)['nodes']
        plugins = {p['name'] for node in nodes.values() for p in node['plugins']}
        assert {'analysis-ik', 'analysis-pinyin'} <= plugins
        print('Shared ES 8.19.23 ready; IK and pinyin plugins loaded')
        break
    except OSError:
        time.sleep(2)
else:
    raise SystemExit('New container not ready; old course-search ES is still running')
PY
cp "$dir/shared-image.txt" "$dir/image.txt"
