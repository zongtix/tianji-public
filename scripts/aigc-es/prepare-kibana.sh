#!/usr/bin/env bash
set -euo pipefail
umask 077
dir=/root/tj-kibana
name=tj-kibana
network=tj-elastic
mkdir -p "$dir"
if docker network inspect "$network" >/dev/null 2>&1; then
    [ "$(docker network inspect --format '{{index .Labels "com.tianji.component"}}' "$network")" = elastic-stack ] || exit 1
else
    docker network create --label com.tianji.component=elastic-stack "$network" >/dev/null
fi
if ! docker inspect tj-aigc-es --format '{{json .NetworkSettings.Networks}}' | grep -q '"tj-elastic"'; then
    docker network connect "$network" tj-aigc-es
fi
python3 - <<'PY'
import base64, json, os, pathlib, secrets, urllib.request
directory = pathlib.Path('/root/tj-kibana')
settings = dict(line.split('=', 1) for line in pathlib.Path('/root/tj-aigc-es/runtime.env').read_text().splitlines())
token = base64.b64encode(('elastic:' + settings['ELASTIC_PASSWORD']).encode()).decode()
def request(method, path, data=None):
    req = urllib.request.Request('http://127.0.0.1:19201' + path, method=method,
        data=None if data is None else json.dumps(data).encode(),
        headers={'Authorization': 'Basic ' + token, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=30) as response: return json.load(response)
config = directory / 'kibana.yml'
if not config.exists():
    credentials = request('GET', '/_security/service/elastic/kibana/credential')
    if 'tj-kibana' in credentials.get('tokens', {}):
        raise SystemExit('Existing service token without local configuration; inspect before resetting')
    service_token = request('POST', '/_security/service/elastic/kibana/credential/token/tj-kibana')['token']['value']
    lines = {
        'server.name': 'tj-kibana', 'server.host': '0.0.0.0',
        'elasticsearch.hosts': ['http://tj-aigc-es:9200'],
        'elasticsearch.serviceAccountToken': service_token,
        'xpack.security.encryptionKey': secrets.token_hex(32),
        'xpack.encryptedSavedObjects.encryptionKey': secrets.token_hex(32),
        'xpack.reporting.encryptionKey': secrets.token_hex(32),
        'xpack.reporting.enabled': False,
        'telemetry.enabled': False, 'telemetry.optIn': False,
        'i18n.locale': 'zh-CN',
    }
    config.write_text('\n'.join(key + ': ' + json.dumps(value) for key, value in lines.items()) + '\n')
    os.chown(config, 1000, 0)
    os.chmod(config, 0o640)
password_file = directory / 'login-password'
if not password_file.exists():
    password_file.write_text(secrets.token_hex(24))
    os.chmod(password_file, 0o600)
request('PUT', '/_security/role/tj_kibana_data', {
    'cluster': ['monitor'], 'indices': [{'names': ['course', 'tj_aigc_*', 'tj_upgrade_course_*'], 'privileges': ['all']}]
})
request('PUT', '/_security/user/tj_kibana_admin', {
    'password': password_file.read_text(), 'roles': ['kibana_admin', 'tj_kibana_data']
})
print('Kibana service account and separate login user prepared; credentials not printed')
PY
if docker container inspect "$name" >/dev/null 2>&1; then
    [ "$(docker inspect --format '{{index .Config.Labels "com.tianji.component"}}' "$name")" = kibana ] || exit 1
    docker start "$name" >/dev/null
else
    if ss -H -lnt '( sport = :5601 )' | grep -q .; then echo 'Port 5601 already occupied' >&2; exit 1; fi
    [ "$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)" -ge 1048576 ] || { echo 'At least 1 GiB available host memory required' >&2; exit 1; }
    if [ ! -f "$dir/image.txt" ]; then
        docker pull docker.elastic.co/kibana/kibana:8.19.23
        docker image inspect docker.elastic.co/kibana/kibana:8.19.23 --format '{{index .RepoDigests 0}}' > "$dir/image.txt"
    fi
    docker volume create --label com.tianji.component=kibana tj-kibana-data >/dev/null
    docker run -d --name "$name" --label com.tianji.component=kibana \
        --restart unless-stopped --memory 1g --memory-swap 1g --cpus 1 \
        --network "$network" -p 127.0.0.1:5601:5601 \
        -v "$dir/kibana.yml:/usr/share/kibana/config/kibana.yml:ro" \
        -v tj-kibana-data:/usr/share/kibana/data \
        -e 'NODE_OPTIONS=--max-old-space-size=512' \
        --log-opt max-size=10m --log-opt max-file=3 "$(cat "$dir/image.txt")" >/dev/null
fi
python3 - <<'PY'
import base64, json, pathlib, time, urllib.request
password = pathlib.Path('/root/tj-kibana/login-password').read_text()
token = base64.b64encode(('tj_kibana_admin:' + password).encode()).decode()
for attempt in range(180):
    try:
        req = urllib.request.Request('http://127.0.0.1:5601/api/status', headers={'Authorization': 'Basic ' + token})
        with urllib.request.urlopen(req, timeout=5) as response: status = json.load(response)
        if status['status']['overall']['level'] == 'available':
            print('Kibana ' + status['version']['number'] + ': available; login authentication verified')
            break
    except OSError:
        pass
    time.sleep(2)
else:
    raise SystemExit('Kibana not ready; inspect docker logs tj-kibana')
PY
