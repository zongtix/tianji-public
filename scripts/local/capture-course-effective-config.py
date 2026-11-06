"""Freeze relevant effective properties from the isolated loopback instance without saving credentials."""
import argparse
import hashlib
import json
import pathlib
import re
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
PREFIXES = ('tj.ai.', 'spring.ai.', 'spring.cloud.openfeign.client.config.',
            'spring.cloud.nacos.config.', 'spring.cloud.nacos.discovery.', 'management.tracing.',
            'management.endpoint.env.', 'management.endpoints.web.', 'server.')
SENSITIVE = re.compile(r'api[-_.]?key|password|secret|access[-_.]?token|auth[-_.]?token|public[-_.]?key|private[-_.]?key|credential', re.I)
EXPECTED = {'server.address': '127.0.0.1', 'server.port': '18496',
            'spring.cloud.nacos.discovery.register-enabled': 'false',
            'tj.ai.model': 'deepseek-flash', 'tj.ai.base-url': 'http://127.0.0.1:18500',
            'spring.ai.retry.max-attempts': '1', 'tj.ai.memory.type': 'mysql',
            'tj.ai.knowledge.enabled': 'false', 'tj.ai.course-retrieval.enabled': 'false',
            'tj.ai.course-retrieval.sync.enabled': 'false', 'tj.ai.audio.enabled': 'false',
            'tj.ai.mcp.enabled': 'false', 'tj.ai.mcp.course-detail-source': 'local',
            'tj.ai.prompt.system.chat.data-id': '',
            **{f'tj.ai.prompt.system.agents.{role}.data-id': ''
               for role in ('route', 'recommend', 'consult', 'knowledge')}}


def read(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        value = json.load(response)
    return value.get('data') if isinstance(value, dict) and 'code' in value else value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=pathlib.Path, required=True)
    parser.add_argument('--phase', choices=('before', 'after'), required=True)
    args = parser.parse_args()
    run = args.run.resolve()
    if not run.is_relative_to((ROOT/'target/course-workflow').resolve()):
        raise ValueError('Expected an isolated workspace batch')
    path = run/f'effective-config-{args.phase}.json'
    if path.exists():
        raise ValueError('Never overwrite a frozen config snapshot')
    status = read('http://127.0.0.1:18500/status')
    if args.phase == 'before' and status['models'] != 0:
        raise ValueError('Configuration must be frozen before the first model request')
    env = read('http://127.0.0.1:18496/actuator/env')
    secrets = [str(item['value']) for source in env['propertySources']
               for key, item in source.get('properties', {}).items()
               if SENSITIVE.search(key) and item.get('value') not in (None, '', '******')]

    def sanitize(key, value):
        if SENSITIVE.search(key):
            return '[REDACTED]'
        text = json.dumps(value, ensure_ascii=False)
        for secret in secrets:
            if len(secret) >= 6:
                text = text.replace(secret, '[REDACTED]')
        return json.loads(text)

    sources = []
    effective = {}
    for source in env['propertySources']:
        selected = {key: sanitize(key, item.get('value')) for key, item in source.get('properties', {}).items()
                    if key.startswith(PREFIXES)}
        if selected:
            sources.append({'name': source['name'], 'properties': selected})
            for key, value in selected.items():
                effective.setdefault(key, value)
    verified = {}
    for key, expected in EXPECTED.items():
        resolved = read('http://127.0.0.1:18496/actuator/env/'+urllib.parse.quote(key, safe=''))
        actual = resolved.get('property', {}).get('value')
        if str(actual).lower() != expected:
            raise ValueError('Isolated config mismatch: '+key)
        verified[key] = actual
    properties_hash = hashlib.sha256(json.dumps(effective, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    value = {'capturedAt': time.time(), 'phase': args.phase, 'modelRequestsAtCapture': status['models'],
             'scope': list(PREFIXES), 'effective': effective, 'sources': sources, 'verified': verified,
             'effectiveHash': properties_hash, 'credentialsRedacted': True,
             'limitations': 'Relevant property sources and explicit isolation values; not a full application, secret, or bound-bean snapshot.'}
    if args.phase == 'after':
        before = json.loads((run/'effective-config-before.json').read_text(encoding='utf-8'))
        value['unchangedRelevantProperties'] = before['effective'] == effective
    with path.open('x', encoding='utf-8') as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
    print(json.dumps({'snapshot': str(path), 'properties': len(effective), 'verified': len(verified),
                      'models': status['models'], 'unchanged': value.get('unchangedRelevantProperties')}))


if __name__ == '__main__':
    main()
