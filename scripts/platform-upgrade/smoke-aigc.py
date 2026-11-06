"""Opt-in real DeepSeek smoke test through the isolated gateway (three billed requests)."""
import argparse
import datetime
import json
import pathlib
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--real-model', action='store_true', required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
fixture = json.loads((root / 'target/platform-upgrade/private/login.json').read_text(encoding='utf-8'))
report = []
token = None

def open_request(method, path, data=None, identity=None, forged=False):
    headers = {'Host': 'www.tianji.test:11010', 'Content-Type': 'application/json'}
    if identity: headers['authorization'] = identity
    if forged: headers['user-info'] = '900000001'
    req = urllib.request.Request('http://127.0.0.1:11010' + path, method=method, headers=headers,
                                 data=json.dumps(data, ensure_ascii=False).encode() if data is not None else None)
    try: return urllib.request.urlopen(req, timeout=170)
    except urllib.error.HTTPError as error: return error

def call(method, path, data=None, identity=None, forged=False):
    with open_request(method, path, data, identity, forged) as response:
        body = response.read()
        return response.status, json.loads(body) if body else {}

def check(name, condition, **evidence):
    report.append({'case': name, 'passed': bool(condition), **evidence})
    print(('PASS ' if condition else 'FAIL ') + name, flush=True)
    if not condition: raise AssertionError(name)

def ok(name, result):
    status, body = result
    check(name, status == 200 and body.get('code') == 200)
    return body.get('data')

def events(response):
    event, data = 'message', []
    for raw in response:
        line = raw.decode('utf-8').rstrip('\r\n')
        if not line:
            if data: yield event, json.loads('\n'.join(data))
            event, data = 'message', []
        elif line.startswith('event:'): event = line[6:].strip()
        elif line.startswith('data:'): data.append(line[5:].lstrip(' '))

session = None
try:
    for forged in (False, True):
        status, _ = call('GET', '/ais/session/history', forged=forged)
        check('anonymous AI rejected' if not forged else 'forged AI identity rejected', status == 401)
    token = ok('AI fixture login', call('POST', '/as/accounts/login', dict(fixture, type=1, rememberMe=False)))
    session = ok('create AI session', call('POST', '/ais/session', identity=token))['id']
    marker = 'maple-' + uuid.uuid4().hex[:8]
    for index, question in enumerate((f'请记住我的学习标记是 {marker}。只回复这个标记。', '刚才我的学习标记是什么？只回复标记。')):
        started = time.monotonic()
        request_id = uuid.uuid4().hex
        with open_request('POST', '/ais/chat', {'sessionId': session, 'requestId': request_id, 'question': question}, token) as response:
            check(f'turn {index+1} SSE headers', response.status == 200 and 'text/event-stream' in response.headers.get('Content-Type', '')
                  and response.headers.get('X-Accel-Buffering') == 'no')
            answer, done, deltas = '', None, 0
            for event, value in events(response):
                check_id = value.get('requestId') == request_id
                if not check_id: raise AssertionError('Request identity mismatch')
                if event == 'delta': answer += value['text']; deltas += 1
                if event == 'done': done = value
            check(f'turn {index+1} real model completion and recall', done and done['status'] == 'complete' and marker in answer,
                  delta_events=deltas, answer_chars=len(answer), seconds=round(time.monotonic()-started, 2))
    history = ok('read persistent AI history', call('GET', '/ais/session/' + session, identity=token))
    check('two ordered turns persisted', len(history) == 2 and all(t['status'] == 'complete' and marker in t['answer'] for t in history))
    sessions = ok('list titled AI sessions', call('GET', '/ais/session/history', identity=token))
    check('session title reflects first question', any(s['id'] == session and marker in s['title'] for s in sessions))
    admin = ok('second user login', call('POST', '/as/accounts/admin/login', dict(fixture, username='upgrade_admin_fixture', type=1, rememberMe=False)))
    for name, method, path, data in (
        ('foreign history rejected', 'GET', '/ais/session/' + session, None),
        ('foreign deletion rejected', 'DELETE', '/ais/session/history?sessionId=' + session, None),
        ('foreign stop rejected', 'POST', '/ais/chat/stop?sessionId=' + session + '&requestId=' + uuid.uuid4().hex, None),
        ('foreign generation rejected', 'POST', '/ais/chat', {'sessionId': session, 'requestId': uuid.uuid4().hex, 'question': 'hi'})):
        status, body = call(method, path, data, admin)
        check(name, status == 200 and body.get('code') == 400)
    request_id = uuid.uuid4().hex
    with open_request('POST', '/ais/chat', {'sessionId': session, 'requestId': request_id, 'question': '请详细讲解 Java 集合框架，包含至少十段代码和完整的实践步骤。'}, token) as response:
        received_delta, stopped = False, None
        for event, value in events(response):
            if event == 'delta' and not received_delta:
                received_delta = True
                start_stop = time.monotonic()
                ok('stop live model generation', call('POST', '/ais/chat/stop?sessionId=' + session + '&requestId=' + request_id, identity=token))
            if event == 'done': stopped = value
        check('stop produces immediate terminal event', received_delta and stopped and stopped['status'] == 'stopped',
              stop_seconds=round(time.monotonic()-start_stop, 2) if received_delta else None)
    history = ok('read stopped AI history', call('GET', '/ais/session/' + session, identity=token))
    check('partial answer persists after stop', len(history) == 3 and history[-1]['status'] == 'stopped' and bool(history[-1]['answer']))
    ok('delete AI session and turns', call('DELETE', '/ais/session/history?sessionId=' + session, identity=token))
    status, body = call('GET', '/ais/session/' + session, identity=token)
    check('deleted session unavailable', status == 200 and body.get('code') == 400)
finally:
    (root / 'docs/platform-upgrade-p5-ai.json').write_text(json.dumps({
        'checked_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'provider': 'DeepSeek',
        'environment': 'tianji-platform-upgrade-test-boot3', 'checks': report,
    }, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
