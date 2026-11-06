"""Run exactly the two synthetic baseline cases; use only loopback APIs and clean owned sessions."""
import argparse
import datetime
import json
import pathlib
import re
import time
import urllib.parse
import urllib.request
import uuid


def card_matches_facts(card, facts):
    price = 0 if facts.get('free') is True else facts.get('price')
    return (card['name'] == facts['name'] and card['priceInCents'] == price
            and card.get('validDuration') == facts.get('validDuration'))


def flow_checks(turn):
    kinds = turn['eventTypes']
    return {
        'sse': turn['sse'],
        'one_terminal': kinds.count(1002) == 1 and kinds[-1:] == [1002],
        'no_error_event': 1005 not in kinds,
        'nonempty_answer': bool(turn['answer'].strip()),
        'no_tool_protocol_leak': not re.search(r'DSML|<\|tool|<｜.*?(?:calls|invoke)', turn['answer'], re.I),
        'history_matches': turn.get('historyMatches', False),
        'card_facts_match': turn.get('cardFactsMatch', True),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--real-model', action='store_true', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--base-url', default='http://127.0.0.1:8096')
    parser.add_argument('--course-url', default='http://127.0.0.1:8086')
    parser.add_argument('--user-id', default='900000000000000032')
    parser.add_argument('--resume', action='store_true', help='Reuse completed, cleaned cases in the same report without new model calls')
    parser.add_argument('--case-id', help='Run one existing case only, to limit additional real-model calls')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9-]+', args.label) or not args.user_id.isdigit():
        parser.error('Use a numeric synthetic user and lowercase label')
    for url in (args.base_url, args.course_url):
        if urllib.parse.urlsplit(url).hostname not in ('127.0.0.1', 'localhost', '::1'):
            parser.error('Only loopback services are allowed')
    repo = pathlib.Path(__file__).resolve().parents[2]
    dataset = json.loads((repo / 'docs/rag-evaluation/chat-baseline-cases.json').read_text(encoding='utf-8'))
    if args.case_id:
        dataset['cases'] = [case for case in dataset['cases'] if case['id'] == args.case_id]
        if not dataset['cases']:
            parser.error('Unknown case ID')
    output = repo / 'target/langfuse-evaluation' / (args.label + '.json')
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists() and not args.resume:
        parser.error('Report already exists; use a new label to preserve results, or --resume for completed cases')
    report = {'label': args.label, 'datasetVersion': dataset['version'],
              'startedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'turns': [], 'cleanupErrors': [], 'independentHumanReview': 'pending'}
    if args.resume:
        report = json.loads(output.read_text(encoding='utf-8'))
        if report['label'] != args.label or report['datasetVersion'] != dataset['version']:
            parser.error('Resume requires the same label and dataset version')
        if not all(t.get('flowValid') and t.get('cleaned') for t in report['turns']):
            parser.error('Review and resolve the previous incomplete case before resuming')

    def save():
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    def request(method, path, payload=None, base=None, trace=None):
        headers = {'Content-Type': 'application/json', 'user-info': args.user_id}
        if trace:
            headers['traceparent'] = '00-' + trace + '-' + uuid.uuid4().hex[:16] + '-01'
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
        return urllib.request.urlopen(urllib.request.Request((base or args.base_url) + path,
                                      data=data, headers=headers, method=method), timeout=120)

    def call(method, path, payload=None, base=None):
        with request(method, path, payload, base) as response:
            raw = response.read()
        value = json.loads(raw) if raw else None
        if isinstance(value, dict) and 'code' in value:
            if value['code'] != 200:
                raise RuntimeError('Local API did not return success')
            return value.get('data')
        return value

    report['sessionsBefore'] = int(call('GET', '/session?pageNo=1&pageSize=10')['total'])
    try:
        for case in dataset['cases']:
            if any(t['caseId'] == case['id'] for t in report['turns']):
                continue
            session = None
            turn = {'caseId': case['id'], 'question': case['question'], 'reviewCriteria': case['reviewCriteria'],
                    'traceId': uuid.uuid4().hex, 'answer': '', 'cards': [], 'eventTypes': [], 'reviews': {}}
            report['turns'].append(turn)
            try:
                query = '/session?n=0'
                if case.get('knowledgeBaseId'):
                    query += '&knowledgeBaseId=' + urllib.parse.quote(case['knowledgeBaseId'])
                session = call('POST', query)['sessionId']
                turn['sessionId'] = session
                turn['conversationId'] = args.user_id + '_' + session
                started = time.perf_counter()
                with request('POST', '/chat', {'sessionId': session, 'question': case['question']}, trace=turn['traceId']) as response:
                    turn['sse'] = 'text/event-stream' in response.headers.get('Content-Type', '')
                    for raw in response:
                        line = raw.decode('utf-8').strip()
                        if not line.startswith('data:'):
                            continue
                        event = json.loads(line[5:].strip())
                        kind = event['eventType']
                        turn['eventTypes'].append(kind)
                        elapsed = round((time.perf_counter() - started) * 1000, 1)
                        if kind == 1001 and event.get('eventData'):
                            turn.setdefault('clientFirstTokenMs', elapsed)
                            turn['clientLastTokenMs'] = elapsed
                            turn['answer'] += event['eventData']
                        elif kind == 1004:
                            turn['cards'].extend(event['eventData']['cards'])
                turn['clientTotalMs'] = round((time.perf_counter() - started) * 1000, 1)
                history = call('GET', '/session/' + session)
                turn['historyMatches'] = bool(history) and history[-1]['content'] == turn['answer']
                turn['cardFactsMatch'] = True
                turn['courseFacts'] = []
                turn['courseDetails'] = []
                for card in turn['cards']:
                    facts = call('GET', '/courses/simpleInfo/list?ids=' + card['courseId'], base=args.course_url)[0]
                    turn['courseFacts'].append(facts)
                    turn['courseDetails'].append(call('GET', '/course/' + card['courseId'] + '/baseInfo', base=args.course_url))
                    turn['cardFactsMatch'] &= card_matches_facts(card, facts)
                turn['checks'] = flow_checks(turn)
                turn['flowValid'] = all(turn['checks'].values())
            finally:
                if session:
                    try:
                        call('POST', '/chat/stop?sessionId=' + session)
                        call('DELETE', '/session/' + session)
                        turn['cleaned'] = True
                    except Exception as error:
                        report['cleanupErrors'].append(type(error).__name__)
                save()
    finally:
        report['sessionsAfter'] = int(call('GET', '/session?pageNo=1&pageSize=10')['total'])
        report['passed'] = (len(report['turns']) == len(dataset['cases']) and all(t.get('flowValid') and t.get('cleaned') for t in report['turns'])
                            and not report['cleanupErrors'] and report['sessionsBefore'] == report['sessionsAfter'])
        save()
    print(json.dumps({'report': str(output), 'passed': report['passed'], 'turns': [
        {k: t.get(k) for k in ('caseId', 'traceId', 'flowValid', 'clientFirstTokenMs', 'clientTotalMs')} for t in report['turns']]}))
    if not report['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
