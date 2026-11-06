"""Opt-in real-model acceptance against the isolated live-environment.ps1 processes."""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import pathlib
import re
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
USER = '900000000000000026'
PORTS = {'local': 18096, 'mcp': 18098}
SUPPORT = 'http://127.0.0.1:18100'


def http(method, url, payload=None, timeout=160):
    data = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    return urllib.request.urlopen(urllib.request.Request(url, data=data, method=method,
        headers={'Content-Type': 'application/json', 'user-info': USER}), timeout=timeout)


def call(method, url, payload=None):
    with http(method, url, payload, 15) as response:
        raw = response.read()
    value = json.loads(raw) if raw else None
    if isinstance(value, dict) and 'code' in value:
        if value['code'] != 200:
            raise AssertionError('Internal API returned non-success code')
        return value.get('data')
    return value


def control(case, delay=0):
    call('POST', SUPPORT + '/control', {'case': case, 'mcp_delay_ms': delay})


def jsonlines(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()] if path.exists() else []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--real-model', action='store_true', required=True)
    parser.add_argument('--phase', choices=('core', 'faults', 'down'), default='core')
    parser.add_argument('--only', nargs='*')
    parser.add_argument('--report-name')
    parser.add_argument('--run-name', default='live')
    parser.add_argument('--cases-file', type=pathlib.Path, default=ROOT / 'scripts/mcp-evaluation/cases.json')
    parser.add_argument('--repeat', type=int, default=1)
    parser.add_argument('--record-missing-detail', action='store_true',
                        help='Record absent detail calls as failed checks and continue the content batch')
    parser.add_argument('--fact-answers', action='store_true',
                        help='Functional acceptance of the three fixed fact questions; route/schema differences are diagnostics')
    args = parser.parse_args()
    if not re.fullmatch('[a-z0-9-]+', args.run_name) or not 1 <= args.repeat <= 10:
        parser.error('Use an alphanumeric run name and 1-10 repetitions')
    directory = ROOT / 'target/mcp-evaluation' / args.run_name
    report_name = args.report_name or args.phase
    if not re.fullmatch('[a-z0-9-]+', report_name):
        parser.error('Use an alphanumeric report name')
    report_path = directory / (report_name + '-report.json')
    report = {'phase': args.phase, 'started': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'automatic_checks_passed': False, 'content_review_passed': None, 'turns': [], 'checks': [],
        'cleanup_errors': [], 'cleaned': [], 'scope': 'Isolated full AIGC SSE + actual model/course/search services; synthetic user; no browser/gateway acceptance'}
    report['mode'] = 'fixed-fact-functional' if args.fact_answers else 'model-comparison'
    report['diagnostics'] = []
    sessions = []

    def save():
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def check(name, passed, fatal=True):
        report['checks'].append({'name': name, 'passed': bool(passed)})
        save()
        print(('PASS ' if passed else 'FAIL ') + name, flush=True)
        if not passed and fatal:
            raise AssertionError(name)

    def create(source):
        base = f'http://127.0.0.1:{PORTS[source]}'
        session = call('POST', base + '/session?n=0')['sessionId']
        sessions.append((base, session))
        return base, session

    def chat(source, case, question, session=None, stop_on_answer=False):
        base, session = session or create(source)
        turn = {'source': source, 'case': case, 'question': question, 'sessionId': session,
                'started': time.time(), 'answer': '', 'cards': [], 'events': []}
        report['turns'].append(turn)
        save()
        try:
            with http('POST', base + '/chat', {'sessionId': session, 'question': question}) as response:
                turn['sse'] = 'text/event-stream' in response.headers.get('Content-Type', '')
                for raw in response:
                    line = raw.decode('utf-8').strip()
                    if not line.startswith('data:'):
                        continue
                    event = json.loads(line[5:])
                    turn['events'].append(event)
                    kind = event['eventType']
                    if kind == 1001:
                        if 'first_token_at' not in turn:
                            turn['first_token_at'] = time.time()
                        turn['answer'] += event['eventData']
                        if stop_on_answer and 'stop_at' not in turn:
                            turn['stop_at'] = time.time()
                            call('POST', base + '/chat/stop?' + urllib.parse.urlencode({'sessionId': session}))
                    elif kind == 1004:
                        turn['cards'].extend(event['eventData']['cards'])
                    elif kind == 1005:
                        turn['error'] = event['eventData']
            turn['finished'] = time.time()
            turn['history'] = call('GET', base + '/session/' + session)
            kinds = [event['eventType'] for event in turn['events']]
            check(case + ': SSE/one STOP', turn.get('sse') and kinds.count(1002) == 1)
            return turn
        finally:
            turn['finished'] = time.time()
            save()

    def summarize(turn):
        def belongs(record):
            return record['case'] == turn['case'] and turn['started'] - 0.25 <= record['started'] <= turn['finished'] + 0.25
        models = [record for record in jsonlines(directory / 'model-calls.jsonl') if belongs(record)]
        mcp = [record for record in jsonlines(directory / 'mcp-starts.jsonl') if belongs(record)]
        tools, usage = [], []
        for model in models:
            for response in model.get('responses', []):
                if response.get('usage'):
                    usage.append(response['usage'])
                for choice in response.get('choices', []):
                    for tool in choice.get('message', {}).get('tool_calls', []):
                        tools.append(tool['function'])
        turn.update(model_tool_calls=tools, mcp_requests=mcp, provider_requests=len(models), provider_usage=usage,
            usage_status='available' if usage else 'unavailable',
            seconds=round(turn['finished'] - turn['started'], 3),
            first_token_seconds=round(turn['first_token_at'] - turn['started'], 3) if 'first_token_at' in turn else None)
        original = [model['request']['tools'] for model in models if model['request'].get('tools')]
        turn['tool_order_variants'] = sorted(set(tuple(tool['function']['name'] for tool in schema) for schema in original))
        schemas = [sorted(schema, key=lambda tool: tool['function']['name']) for schema in original]
        turn['schema_hashes'] = sorted(set(hashlib.sha256(json.dumps(schema, sort_keys=True).encode()).hexdigest() for schema in schemas))
        save()

    try:
        if args.phase == 'core':
            cases = json.loads(args.cases_file.read_text(encoding='utf-8'))['cases']
            selected = set(args.only or ['detail', 'catalogue', 'compare', 'recommend', 'price', 'no-detail'])
            if args.fact_answers and selected != {'detail', 'compare', 'depth'}:
                raise ValueError('Fixed-fact acceptance requires exactly detail, compare and depth')
            facts = {id: call('GET', 'http://127.0.0.1:8086/course/' + id + '/baseInfo')
                     for id in ('1589888774267072513', '1589905661084430337')}
            report['course_snapshot_before'] = facts
            expanded = [(repeat, case) for repeat in range(1, args.repeat + 1)
                        for case in cases if case['id'] in selected]
            if not expanded:
                raise ValueError('No selected acceptance cases')
            report['repetitions'] = args.repeat
            for repeat, case in expanded:
                for source in ('local', 'mcp'):
                    name = source + '-' + case['id'] + (f'-r{repeat}' if args.repeat > 1 else '')
                    control(name)
                    turn = chat(source, name, case['question'])
                    turn.update(case_id=case['id'], repetition=repeat)
                    summarize(turn)
                    check(name + ': answer without error', turn['answer'].strip() and 'error' not in turn)
                    detail_calls = [tool for tool in turn['model_tool_calls'] if tool['name'] == 'queryCourseById']
                    actual = [request for request in turn['mcp_requests'] if request['method'] == 'tools/call']
                    turn['mcp_coverage'] = bool(actual)
                    check(name + ': actual source matches detail calls', len(actual) == (len(detail_calls) if source == 'mcp' else 0))
                    if case['id'] != 'no-detail':
                        if args.fact_answers and case['id'] == 'depth':
                            report['diagnostics'].append({'case': name, 'detail_path_exercised': bool(detail_calls),
                                                          'actual_mcp_coverage': bool(actual)})
                        else:
                            check(name + ': detail path actually exercised', bool(detail_calls), fatal=not args.record_missing_detail)
                        expected = {'1589888774267072513', '1589905661084430337'} if case['id'] == 'compare' else {'1589888774267072513'}
                        card_ids = {card['courseId'] for card in turn['cards']}
                        check(name + ': expected course cards', card_ids <= expected if args.fact_answers and case['id'] == 'depth'
                              else card_ids == expected)
                        if args.fact_answers and case['id'] != 'depth' and source == 'mcp':
                            check(name + ': MCP fetched every requested course',
                                  {str(r['params']['arguments']['courseId']) for r in actual} == expected)
                        if case['id'] == 'recommend':
                            names = [tool['name'] for tool in turn['model_tool_calls']]
                            check(name + ': keyword/detail/recommend execution order',
                                  all(tool in names for tool in ('searchCourses', 'queryCourseById', 'recommendCourses'))
                                  and names.index('searchCourses') < names.index('queryCourseById') < names.index('recommendCourses'))
                    else:
                        check(name + ': greeting does not invoke MCP', not actual)
                    for remote in actual:
                        check(name + ': MCP context stays local', remote['params']['name'] == 'getCourseDetail'
                              and set(remote['params']['arguments']) == {'courseId'} and not remote['params'].get('_meta'))
                    for card in turn['cards']:
                        current = call('GET', 'http://127.0.0.1:8086/courses/simpleInfo/list?ids=' + card['courseId'])[0]
                        check(name + ': card current price/validity/name', card['name'] == current['name']
                              and card['priceInCents'] == (0 if current.get('free') else current.get('price'))
                              and card['validDuration'] == current['validDuration'])
                    check(name + ': visible history only', [item['type'] for item in turn['history']] == ['USER', 'ASSISTANT']
                          and turn['history'][0]['content'] == case['question'] and turn['history'][1]['content'] == turn['answer'])
                    check(name + ': persisted cards match visible cards', turn['history'][-1]['params'].get('courseCards', []) == turn['cards'])
                pair = report['turns'][-2:]
                same_schema = pair[0]['schema_hashes'] == pair[1]['schema_hashes']
                if args.fact_answers:
                    report['diagnostics'].append({'case': case['id'], 'same_exposed_model_schema_set': same_schema,
                                                  'note': 'Functional check; no causal performance comparison'})
                else:
                    check(case['id'] + ': same exposed model schema set', same_schema)
            report['course_snapshot_after'] = {id: call('GET', 'http://127.0.0.1:8086/course/' + id + '/baseInfo') for id in facts}
            check('course snapshot unchanged', facts == report['course_snapshot_after'])
        elif args.phase == 'faults':
            question = '请查询课程ID 1589888774267072513 的正式目录，核实详情并展示卡片。'
            control('mcp-timeout', 7000)
            timeout = chat('mcp', 'mcp-timeout', question)
            check('timeout: safe ERROR without card/answer', timeout.get('error', {}).get('code') == 'MCP_TOOL_TIMEOUT'
                  and not timeout['cards'] and not timeout['answer'])
            time.sleep(3)
            summarize(timeout)
            control('mcp-stop-wait', 3500)
            session = create('mcp')
            with concurrent.futures.ThreadPoolExecutor() as pool:
                pending = pool.submit(chat, 'mcp', 'mcp-stop-wait', question, session)
                deadline = time.monotonic() + 35
                while time.monotonic() < deadline:
                    if call('GET', SUPPORT + '/status')['mcp_pending']:
                        break
                    time.sleep(0.1)
                else:
                    raise AssertionError('No MCP call became pending; stop test not exercised')
                stop_at = time.time()
                call('POST', session[0] + '/chat/stop?' + urllib.parse.urlencode({'sessionId': session[1]}))
                stopped = pending.result(timeout=8)
                stopped['stop_seconds'] = round(stopped['finished'] - stop_at, 3)
                check('stop during MCP: no late answer/card and bounded stop', not stopped['answer'] and not stopped['cards']
                      and 'error' not in stopped and stopped['stop_seconds'] < 5)
            control('other-request-after-stop')
            other = chat('mcp', 'other-request-after-stop',
                         '请查询课程ID 1589888774267072513 的当前价格和有效期，并展示课程卡片。')
            check('another request remains usable after stopping MCP', other['answer'].strip() and 'error' not in other)
            time.sleep(4)
            summarize(stopped)
            summarize(other)
            check('stopped call: no following MCP tools', len([r for r in stopped['mcp_requests'] if r['method'] == 'tools/call']) == 1)
            check('shared MCP client remains usable', any(r['method'] == 'tools/call' for r in other['mcp_requests']))
            control('stop-answer')
            partial = chat('local', 'stop-answer', '请详细介绍Java集合框架的使用方式。', stop_on_answer=True)
            check('stop answer: partial memory retained', partial['answer'].strip() and 'error' not in partial
                  and partial['history'][-1]['content'] == partial['answer']
                  and partial['history'][-1]['params'].get('generationStatus') == 'stopped')
            summarize(partial)
        else:
            question = '请查询课程ID 1589888774267072513 的详情，说明适用人群。'
            control('mcp-server-down')
            down = chat('mcp', 'mcp-server-down', question)
            check('server down: safe unavailable ERROR', down.get('error', {}).get('code') == 'MCP_TOOL_UNAVAILABLE'
                  and not down['cards'] and not down['answer'])
            summarize(down)
            control('local-with-mcp-server-down')
            local = chat('local', 'local-with-mcp-server-down', question)
            check('local path works with MCP server down', local['answer'].strip() and 'error' not in local)
            summarize(local)
        report['automatic_checks_passed'] = all(item['passed'] for item in report['checks'])
    except Exception as error:
        report['failure_type'] = type(error).__name__
        report['failure'] = str(error)[:200]
        raise
    finally:
        control(None)
        for base, session in sessions:
            try:
                call('DELETE', base + '/session/' + urllib.parse.quote(session, safe=''))
                report['cleaned'].append(session)
            except Exception as error:
                report['cleanup_errors'].append({'sessionId': session, 'error_type': type(error).__name__})
        report['finished'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        report['cleanup_passed'] = not report['cleanup_errors']
        save()
        print('Report: ' + str(report_path), flush=True)
    if not report['automatic_checks_passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
