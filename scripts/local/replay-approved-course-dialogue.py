"""Exactly the approved seven questions, once, in one owned isolated conversation."""
import argparse
import json
import pathlib
import re
import time
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
RUN = ROOT/'target/course-workflow/approved-seven-20261027'
BASE = 'http://127.0.0.1:18496'
PROXY = 'http://127.0.0.1:18500'
APPROVED = {'1589888774267072513', '1589905661084430337'}


def main():
    global RUN
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--real-model', required=True, action='store_true')
    parser.add_argument('--recover-unspent-preflight', action='store_true')
    parser.add_argument('--run', type=pathlib.Path, default=RUN)
    parser.add_argument('--batch', type=pathlib.Path, default=ROOT/'docs/course-workflow-evaluation/live-batch.json')
    args = parser.parse_args()
    RUN = args.run.resolve()
    if not RUN.is_relative_to((ROOT/'target/course-workflow').resolve()):
        raise ValueError('Batch path is outside the isolated workspace')
    batch = json.loads(args.batch.read_text(encoding='utf-8'))
    limit = batch['maxProviderHttpRequests']
    if batch['maxAnswers'] != 7 or type(limit) is not int or not 1 <= limit <= 80 or len(batch['questions']) != 7:
        raise ValueError('Approved batch changed')
    path = RUN/'seven-turn-report.json'
    if path.exists():
        previous = json.loads(path.read_text(encoding='utf-8'))
        if (not args.recover_unspent_preflight or previous.get('turns') or previous.get('ownedSession')
                or previous.get('proxyStatus', {}).get('models') != 0 or (RUN/'model-starts.jsonl').exists()):
            raise ValueError('Batch was already attempted; never repeat paid questions automatically')
        with (RUN/'seven-turn-unspent-preflight.json').open('x', encoding='utf-8') as archived:
            json.dump(previous, archived, ensure_ascii=False, indent=2)
    user = str(900000000000000000 + uuid.uuid4().int % 100000000)
    report = {'userId': user, 'scope': 'Seven original questions; two courses; local details; no semantic retrieval/MCP/embedding/rerank.',
              'maxAnswers': 7, 'maxProviderHttpRequests': limit, 'started': time.time(), 'turns': [], 'checks': [],
              'automatic_checks_passed': False, 'content_review_passed': None, 'acceptance_passed': False,
              'independentHumanReview': 'pending', 'ownedSession': None, 'cleaned': False}

    def save():
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

    def request(method, route, payload=None, base=BASE, trace=None, timeout=160):
        headers = {'Content-Type': 'application/json', 'user-info': user}
        if trace:
            headers['traceparent'] = '00-'+trace+'-'+uuid.uuid4().hex[:16]+'-01'
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
        return urllib.request.urlopen(urllib.request.Request(base+route, data=data, headers=headers, method=method), timeout=timeout)

    def call(method, route, payload=None, base=BASE):
        with request(method, route, payload, base, timeout=20) as response:
            raw = response.read()
        value = json.loads(raw) if raw else None
        if isinstance(value, dict) and 'code' in value:
            if value['code'] != 200:
                raise ValueError('Non-success local API response')
            return value.get('data')
        return value

    def check(name, passed):
        report['checks'].append({'name': name, 'passed': bool(passed)})

    def snapshot():
        return {identity: {'detail': call('GET', '/course-api/course/'+identity+'/baseInfo', base=PROXY),
                           'simple': call('GET', '/course-api/courses/simpleInfo/list?ids='+identity, base=PROXY)[0]}
                for identity in sorted(APPROVED)}

    save()
    try:
        status = call('GET', '/status', base=PROXY)
        if status['models'] != 0 or status['limit'] != limit or set(status['approvedCourseIds']) != APPROVED:
            raise ValueError('Proxy scope/budget preflight failed')
        report['sessionsBefore'] = int(call('GET', '/session?pageNo=1&pageSize=50')['total'])
        if report['sessionsBefore'] != 0:
            raise ValueError('Dedicated synthetic identity unexpectedly has sessions')
        report['courseBefore'] = snapshot()
        session = call('POST', '/session?n=0')['sessionId']
        report['ownedSession'] = session
        last_cards = []
        for number, question in enumerate(batch['questions'], 1):
            case = f'turn-{number:02d}'
            call('POST', '/control', {'case': case}, PROXY)
            turn = {'case': case, 'question': question, 'traceId': uuid.uuid4().hex,
                    'conversationId': user+'_'+session, 'started': time.time(),
                    'answer': '', 'cards': [], 'events': [], 'priorCards': last_cards, 'checks': {}}
            report['turns'].append(turn)
            save()
            with request('POST', '/chat', {'sessionId': session, 'question': question}, trace=turn['traceId']) as response:
                turn['sse'] = 'text/event-stream' in response.headers.get('Content-Type', '')
                for raw in response:
                    text = raw.decode('utf-8').strip()
                    if not text.startswith('data:'):
                        continue
                    event = json.loads(text[5:].strip())
                    turn['events'].append(event)
                    if event['eventType'] == 1001:
                        turn.setdefault('firstTextAt', time.time())
                        turn['answer'] += event['eventData']
                    elif event['eventType'] == 1004:
                        turn['cards'].extend(event['eventData']['cards'])
                    save()
            turn['finished'] = time.time()
            turn['history'] = call('GET', '/session/'+session)
            kinds = [event['eventType'] for event in turn['events']]
            history = turn['history']
            turn['checks'] = {
                'sse': turn['sse'], 'single_terminal': kinds.count(1002) == 1 and kinds[-1:] == [1002],
                'no_error': 1005 not in kinds, 'nonempty_answer': bool(turn['answer'].strip()),
                'no_tool_protocol': not re.search(r'DSML|<\|tool|<｜.*?(?:calls|invoke)', turn['answer'], re.I),
                'history_matches': len(history) == number*2 and history[-2]['type'] == 'USER'
                    and history[-2]['content'] == question and history[-1]['type'] == 'ASSISTANT'
                    and history[-1]['content'] == turn['answer']
                    and history[-1].get('params', {}).get('courseCards', []) == turn['cards'],
                'card_scope': len(turn['cards']) <= 3 and all(card['courseId'] in APPROVED for card in turn['cards'])}
            facts = []
            for card in turn['cards']:
                fact = call('GET', '/course-api/courses/simpleInfo/list?ids='+card['courseId'], base=PROXY)[0]
                facts.append(fact)
            turn['cardFacts'] = facts
            turn['checks']['card_facts_match'] = all(card['name'] == fact['name']
                and card['priceInCents'] == (0 if fact.get('free') else fact.get('price'))
                and card.get('validDuration') == fact.get('validDuration') for card, fact in zip(turn['cards'], facts))
            if turn['cards']:
                last_cards = turn['cards']
            for name, passed in turn['checks'].items():
                check(case+': '+name, passed)
            save()
            print(json.dumps({'turn': number, 'seconds': round(turn['finished']-turn['started'], 2),
                              'checksPassed': all(turn['checks'].values()), 'cards': len(turn['cards'])}), flush=True)
        # Allow completed child spans to reach the local receiver before stopping this batch's JVM.
        deadline = time.monotonic()+15
        while time.monotonic() < deadline:
            spans_path = RUN/'spans.jsonl'
            spans = [json.loads(line) for line in spans_path.read_text(encoding='utf-8').splitlines() if line.strip()] if spans_path.exists() else []
            if sum(span['name'] == 'chat.request' for span in spans) >= 7:
                break
            time.sleep(.5)
        report['courseAfter'] = snapshot()
        check('course snapshots unchanged', report['courseBefore'] == report['courseAfter'])
    except Exception as error:
        report['errorType'] = type(error).__name__
        # Do not print provider errors or arbitrary exception payloads.
        save()
    finally:
        session = report['ownedSession']
        if session:
            try:
                call('POST', '/chat/stop?sessionId='+urllib.parse.quote(session))
                call('DELETE', '/session/'+urllib.parse.quote(session))
                report['cleaned'] = True
            except Exception as error:
                report['cleanupErrorType'] = type(error).__name__
        try:
            report['sessionsAfter'] = int(call('GET', '/session?pageNo=1&pageSize=50')['total'])
            report['proxyStatus'] = call('GET', '/status', base=PROXY)
        except Exception as error:
            report['finalStatusErrorType'] = type(error).__name__
        status = report.get('proxyStatus', {})
        check('owned synthetic session cleaned', report['cleaned'] and report.get('sessionsAfter') == 0)
        check('request budget respected', status.get('models', limit+1) <= limit and status.get('pending', 1) == 0)
        check('no scope or credential violation', status.get('scopeBlocks', 1) == 0 and status.get('credentialLeaks', 1) == 0)
        report['finished'] = time.time()
        report['automatic_checks_passed'] = len(report['turns']) == 7 and 'errorType' not in report and all(c['passed'] for c in report['checks'])
        save()
    print(json.dumps({'report': str(path), 'turns': len(report['turns']),
                      'automaticChecksPassed': report['automatic_checks_passed'],
                      'providerRequests': report.get('proxyStatus', {}).get('models'), 'cleaned': report['cleaned']}))
    return 0 if report['automatic_checks_passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
