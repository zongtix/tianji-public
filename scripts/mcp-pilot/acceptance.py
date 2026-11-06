"""Opt-in natural-question acceptance on the running two-course pilot, no fault controls."""
import argparse
import hashlib
import json
import pathlib
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = 'http://127.0.0.1:19096'
USER = '900000000000000029'
IDS = ('1589888774267072513', '1589905661084430337')


def http(method, path, payload=None, base=BASE):
    body = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    return urllib.request.urlopen(urllib.request.Request(base + path, data=body, method=method,
        headers={'Content-Type': 'application/json', 'user-info': USER, 'x-request-from': 'feign'}), timeout=160)


def call(method, path, payload=None, base=BASE):
    with http(method, path, payload, base) as response:
        raw = response.read()
    value = json.loads(raw) if raw else None
    if isinstance(value, dict) and 'code' in value:
        if value['code'] != 200:
            raise ValueError('API returned non-success code')
        return value.get('data')
    return value


def lines(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()] if path.exists() else []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--real-model', required=True, action='store_true')
    parser.parse_args()
    run = pathlib.Path(json.loads((ROOT / 'target/mcp-pilot/current.json').read_text())['directory'])
    out = ROOT / 'target/mcp-pilot' / ('natural-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    sources = json.loads((run / 'sources.json').read_text())
    source = f"http://127.0.0.1:{sources['coursePort']}"
    facts = {id: call('GET', f'/course/{id}/baseInfo', base=source) for id in IDS}
    status = call('GET', '/status', base='http://127.0.0.1:19100')
    assert status['mode'] == 'pilot' and set(status['approved_course_ids']) == set(IDS)
    report = {'passed': False, 'run': str(run), 'turns': [], 'cleanup': [], 'snapshots': facts,
              'jarSha256': hashlib.sha256((ROOT / 'tj-aigc/target/tj-aigc.jar').read_bytes()).hexdigest()}
    sessions = []

    def save():
        (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    def chat(name, question, expected, session=None, depth=False):
        if session is None:
            session = call('POST', '/session?n=0')['sessionId']
            sessions.append(session)
        turn = {'name': name, 'question': question, 'sessionId': session, 'started': time.time(), 'events': []}
        report['turns'].append(turn)
        save()
        with http('POST', '/chat', {'question': question, 'sessionId': session}) as response:
            assert 'text/event-stream' in response.headers.get('Content-Type', '')
            for raw in response:
                if raw.startswith(b'data:'):
                    turn['events'].append(json.loads(raw[5:]))
        turn['finished'] = time.time()
        answer = ''.join(e['eventData'] for e in turn['events'] if e['eventType'] == 1001)
        cards = [c for e in turn['events'] if e['eventType'] == 1004 for c in e['eventData']['cards']]
        turn['answer'], turn['cards'] = answer, cards
        kinds = [e['eventType'] for e in turn['events']]
        # Metadata is written asynchronously after the last response bytes.
        time.sleep(0.3)
        reads = [r for r in lines(run / 'mcp-starts.jsonl') if r['method'] == 'tools/call'
                 and turn['started'] <= r['started'] <= turn['finished']]
        turn['mcpReads'] = reads
        assert kinds.count(1002) == 1 and 1005 not in kinds and answer.strip(), 'SSE completion'
        assert {r['params']['arguments']['courseId'] for r in reads} == set(expected), 'actual MCP coverage'
        assert {c['courseId'] for c in cards} == set(expected), 'course cards'
        for id in expected:
            assert facts[id]['name'] in answer
            if not depth:
                assert facts[id]['usePeople'] in answer, 'source audience unchanged'
        if depth:
            assert '无法确认讲解深度' in answer and '无法确认是否完整覆盖' in answer
            titles = [line[2:] for line in answer.splitlines() if line.startswith('- ')]
            assert titles and all(title in facts[IDS[0]]['catalogue'] for title in titles)
        for card in cards:
            current = call('GET', '/courses/simpleInfo/list?ids=' + card['courseId'], base=source)[0]
            assert card['name'] == current['name'] and card['validDuration'] == current['validDuration']
            assert card['priceInCents'] == (0 if current.get('free') else current.get('price'))
        history = call('GET', '/session/' + session)
        assert history[-2]['content'] == question and history[-1]['content'] == answer
        assert history[-1]['params'].get('courseCards', []) == cards
        turn['historyVerified'] = True
        save()
        print('PASS ' + name, flush=True)
        return session

    try:
        session = chat('natural-audience', 'Java程序员的前端课程适合什么基础的人？', [IDS[0]])
        chat('followup-depth', '这门课的JavaScript讲多深？能确认完整覆盖吗？', [IDS[0]], session, depth=True)
        chat('natural-compare', 'Java程序员的前端课程和可能是史上最全的微服务技术栈课程，适用人群有什么区别？', IDS)
        chat('natural-microservice', '可能是史上最全的微服务技术栈课程要求什么基础？', [IDS[1]])
        report['snapshotsUnchanged'] = facts == {id: call('GET', f'/course/{id}/baseInfo', base=source) for id in IDS}
        after = call('GET', '/status', base='http://127.0.0.1:19100')
        report['modelRequests'] = after['models'] - status['models']
        assert report['snapshotsUnchanged'] and after['blocked_model_requests'] == status['blocked_model_requests']
        assert after['credential_leaks'] == status['credential_leaks']
        report['passed'] = True
    finally:
        for session in sessions:
            try:
                call('GET', '/session/' + session)
                call('DELETE', '/session/' + session)
                report['cleanup'].append({'sessionId': session, 'deleted': True})
            except Exception as error:
                report['cleanup'].append({'sessionId': session, 'deleted': False, 'error': type(error).__name__})
        save()
        print(str(out), flush=True)


if __name__ == '__main__':
    main()
