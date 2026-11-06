"""Delete only owned synthetic sessions explicitly recorded by this acceptance run."""
import json
import pathlib
import urllib.error
import urllib.parse

from importlib.machinery import SourceFileLoader

ROOT = pathlib.Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / 'target/mcp-evaluation/live'
api = SourceFileLoader('mcp_live_run', str(pathlib.Path(__file__).with_name('live-run.py'))).load_module()
recorded = set()
for path in DIRECTORY.rglob('*.json'):
    report = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(report, dict):
        continue
    for turn in report.get('turns', []):
        if isinstance(turn, dict) and turn.get('sessionId'):
            recorded.add(turn['sessionId'])
result = {'recorded': len(recorded), 'deleted': [], 'already_unavailable': [], 'errors': []}
base = 'http://127.0.0.1:18096'
for session in sorted(recorded):
    url = base + '/session/' + urllib.parse.quote(session, safe='')
    try:
        api.call('GET', url)  # The application verifies ownership before returning any history.
    except urllib.error.HTTPError as error:
        if error.code in (400, 403, 404):
            result['already_unavailable'].append(session)
        else:
            result['errors'].append({'sessionId': session, 'status': error.code})
        continue
    try:
        api.call('DELETE', url)
        result['deleted'].append(session)
    except Exception as error:
        result['errors'].append({'sessionId': session, 'error_type': type(error).__name__})
result['passed'] = not result['errors']
(DIRECTORY / 'cleanup-report.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps({key: len(value) if isinstance(value, list) else value for key, value in result.items()}))
if not result['passed']:
    raise SystemExit(1)
