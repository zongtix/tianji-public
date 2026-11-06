"""Opt-in daily-service checks for two published courses with a synthetic user.

Uses configured real models, saves each turn, and deletes only owned test sessions.
Automated success still requires a separate review of answer grounding.
"""
import argparse
import datetime
import json
import pathlib
import re
import urllib.parse
import urllib.request

FRONTEND = '1589888774267072513'
MICROSERVICES = '1589905661084430337'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--real-model', action='store_true', required=True)
    parser.add_argument('--core', action='store_true', help='Run four turns covering recommendation, evidence follow-up, prerequisites and comparison')
    parser.add_argument('--presentation-rerun', action='store_true', help='Repeat only the three previously verbose turns, with identical questions')
    parser.add_argument('--consultation-rerun', action='store_true', help='Repeat only the two verbose suitability and comparison turns, with identical questions')
    parser.add_argument('--intake-rerun', action='store_true', help='Replay the seven-turn browsing, uncertainty and personalized recommendation flow')
    parser.add_argument('--base-url', default='http://127.0.0.1:8096')
    parser.add_argument('--course-url', default='http://127.0.0.1:8086')
    parser.add_argument('--user-id', default='900000000000000022')
    parser.add_argument('--report', default='target/published-course-pilot/guide.json')
    args = parser.parse_args()
    if sum((args.core, args.presentation_rerun, args.consultation_rerun, args.intake_rerun)) > 1:
        parser.error('Choose only one of --core, --presentation-rerun, --consultation-rerun or --intake-rerun')
    for address in (args.base_url, args.course_url):
        if urllib.parse.urlsplit(address).hostname not in ('127.0.0.1', 'localhost', '::1'):
            parser.error('Only loopback services are allowed')
    if not args.user_id.isdigit():
        parser.error('Synthetic user ID must be numeric')
    report_path = pathlib.Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    started = datetime.datetime.now(datetime.timezone.utc)
    report = {'started': started.isoformat(), 'passed': False, 'automatic_checks_passed': False,
              'content_review_passed': None, 'acceptance_passed': False, 'checks': [], 'sessions': [], 'turns': [],
              'cleanup_errors': [], 'cleaned': [], 'scope': 'Daily AIGC + actual course HTTP API, configured real models; synthetic identity; gateway/browser not exercised',
              'content_review_required': ['reasons grounded in actual material', 'SpringBoot and MyBatis prerequisites are not relaxed or invented',
                  'unknown services and learning results are not promised', '9999-month field is preserved without inventing an activation date or permanent entitlement']}

    def save():
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def request(method, path, payload=None, base=None):
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
        req = urllib.request.Request((base or args.base_url).rstrip('/') + path, data=data, method=method,
                                     headers={'Content-Type': 'application/json', 'user-info': args.user_id})
        return urllib.request.urlopen(req, timeout=180)

    def call(method, path, payload=None, base=None):
        with request(method, path, payload, base) as response:
            body = response.read()
        result = json.loads(body) if body else None
        if isinstance(result, dict) and 'code' in result:
            if result['code'] != 200:
                raise AssertionError('API did not return success: ' + path)
            return result.get('data')
        return result

    def check(name, result):
        report['checks'].append({'name': name, 'passed': bool(result)})
        save()
        print(('PASS ' if result else 'FAIL ') + name, flush=True)
        if not result:
            raise AssertionError(name)

    def create():
        session_id = call('POST', '/session')['sessionId']
        record = {'id': session_id, 'questions': []}
        report['sessions'].append(record)
        save()
        return record

    def chat(session, question):
        turn = {'sessionId': session['id'], 'question': question, 'answer': '', 'cards': [], 'events': []}
        report['turns'].append(turn)
        session['questions'].append(question)
        save()
        try:
            with request('POST', '/chat', {'sessionId': session['id'], 'question': question}) as response:
                check('daily stream uses SSE', 'text/event-stream' in response.headers.get('Content-Type', ''))
                for raw in response:
                    line = raw.decode('utf-8').strip()
                    if not line.startswith('data:'):
                        continue
                    event = json.loads(line[5:].strip())
                    turn['events'].append(event)
                    if event['eventType'] == 1001:
                        turn['answer'] += event['eventData']
                    elif event['eventType'] == 1004:
                        turn['cards'].extend(event['eventData']['cards'])
            types = [event['eventType'] for event in turn['events']]
            check('single terminal event without retrieval error', types.count(1002) == 1 and 1005 not in types)
            check('nonempty visible answer', bool(turn['answer'].strip()))
            if turn['cards']:
                check('cards precede terminal event', types.index(1004) < types.index(1002))
            return turn
        finally:
            save()

    def verify_cards(turn, expected):
        expected_ids = [expected] if isinstance(expected, str) else expected
        check('cards stay on the selected published courses', len(turn['cards']) == len(expected_ids)
              and {card['courseId'] for card in turn['cards']} == set(expected_ids))
        for card in turn['cards']:
            course_id = card['courseId']
            current = call('GET', '/courses/simpleInfo/list?ids=' + course_id, base=args.course_url)[0]
            expected_price = 0 if current.get('free') is True else current.get('price')
            check('card facts match current course service', card['name'] == current['name'] and card['priceInCents'] == expected_price
                  and card['validDuration'] == current['validDuration'])
            report.setdefault('course_facts', {})[course_id] = {'simple': current,
                    'base': call('GET', '/course/' + course_id + '/baseInfo', base=args.course_url)}
        save()

    def verify_history(session):
        history = call('GET', '/session/' + session['id'])
        turns = [turn for turn in report['turns'] if turn['sessionId'] == session['id']]
        check('history contains only original visible turns', len(history) == len(turns) * 2
              and [m['type'] for m in history] == ['USER', 'ASSISTANT'] * len(turns)
              and [m['content'] for m in history[::2]] == session['questions']
              and [m['content'] for m in history[1::2]] == [turn['answer'] for turn in turns])
        check('history preserves exact current cards', all(history[2*i+1]['params'].get('courseCards', []) == turn['cards'] for i, turn in enumerate(turns)))

    def core_scenarios():
        scenario_scope = ('Two previously verbose consultation turns' if args.consultation_rerun else
                          'Three previously verbose turns' if args.presentation_rerun else 'Four core turns')
        report['scope'] = scenario_scope + ' through real local AIGC and course APIs; synthetic identity; no gateway/browser checks'
        if not args.consultation_rerun:
            frontend = create()
            turn = chat(frontend, '我有 Java 后端开发经验，工作中需要兼顾前端，想学习 HTML、CSS、JavaScript。请推荐最适合的一门课程，说明与我的目标和基础的关系并展示卡片，简短回答。')
            verify_cards(turn, FRONTEND)
            if not args.presentation_rerun:
                turn = chat(frontend, '刚才这门课的正式目录能确认教 React 吗？请区分介绍里的声明与实际目录，没核实的直接说明，简短回答。')
                verify_cards(turn, FRONTEND)
            verify_history(frontend)

        suitability = create()
        turn = chat(suitability, '我只学完 Java 基础，还没用过 Spring Boot 和 MyBatis。《可能是史上最全的微服务技术栈课程》适合现在学吗？课程明确要求什么基础？请展示卡片并简短回答。')
        verify_cards(turn, MICROSERVICES)
        check('suitability cites the two explicit framework prerequisites', 'springboot' in re.sub(r'\s+', '', turn['answer']).lower() and 'mybatis' in turn['answer'].lower())
        verify_history(suitability)

        comparison = create()
        turn = chat(comparison, '我有 Java 后端经验，熟练使用 Spring Boot 和 MyBatis，目前最想补前端页面开发。《Java程序员的前端课程》和《可能是史上最全的微服务技术栈课程》有什么区别，哪门更贴合我现在的目标？请核对两门资料，展示两门卡片，简短回答；资料未说明的不要猜。')
        verify_cards(turn, [FRONTEND, MICROSERVICES])
        verify_history(comparison)

    def intake_scenarios():
        report['scope'] = 'Seven synthetic turns reproducing browsing, uncertainty, course reference and personalization; real local AIGC and course APIs'
        session = create()
        questions = [
            '有哪些热门课程推荐？',
            '这俩有啥区别',
            '我也不知道',
            '第二个是啥',
            '有没有课程推荐',
            '我不需要热门课程，我需要你给我量身定制课程',
            '我会Java，能用Spring Boot和MyBatis写后端接口，现在想学HTML、CSS和JavaScript做网页，请按我的情况推荐一门课程。',
        ]
        for index, question in enumerate(questions):
            turn = chat(session, question)
            # Keep failed expectations and continue the dialogue so a reference failure
            # cannot hide a later personalization failure. Protocol/history errors still abort.
            try:
                if index in (0, 1):
                    verify_cards(turn, [FRONTEND, MICROSERVICES])
                elif index == 3:
                    verify_cards(turn, MICROSERVICES)
                elif index == 6:
                    verify_cards(turn, FRONTEND)
                else:
                    check('intake does not repeat historical course cards', not turn['cards'])
                    check('insufficient user information is not reported as no course match',
                          not any(phrase in turn['answer'] for phrase in ('未找到', '没找到', '没有匹配')))
                    check('intake does not repeat irrelevant popularity disclaimer',
                          not any(phrase in turn['answer'] for phrase in ('热度依据', '报名量', '热门榜')))
            except AssertionError:
                pass
            try:
                check('answer does not add unasked prices or validity',
                      not re.search(r'(?:149|199)\s*元|9999\s*个?月', turn['answer']))
                if index in (1, 6):
                    check('answer does not introduce unasked framework catalogue gaps',
                          not re.search(r'vue|react', turn['answer'], re.IGNORECASE))
            except AssertionError:
                pass
            verify_history(session)

    try:
        baseline = call('GET', '/session?pageNo=1&pageSize=50')
        check('dedicated synthetic identity starts empty', int(baseline['total']) == 0)
        if args.intake_rerun:
            intake_scenarios()
            report['automatic_checks_passed'] = all(item['passed'] for item in report['checks'])
            if not report['automatic_checks_passed']:
                raise AssertionError('Intake replay contains failed expectations; inspect every turn')
            return
        if args.core or args.presentation_rerun or args.consultation_rerun:
            core_scenarios()
            report['automatic_checks_passed'] = True
            return
        frontend = create()
        turn = chat(frontend, '我有 Java 后端开发经验，工作中需要兼顾前端，想快速学习 HTML、CSS、JavaScript 和常用前端框架。请推荐最适合的一门课程，说明理由并展示卡片，简短回答。')
        verify_cards(turn, FRONTEND)
        turn = chat(frontend, '刚才这门多少钱，学习有效期是多少个月？从什么时候开始算？在哪里继续了解？请简短回答。')
        verify_cards(turn, FRONTEND)
        plain = re.sub(r'[*_`,，]', '', turn['answer'])
        check('frontend price and raw month duration are explained', bool(re.search(r'149(?:\.0+)?\s*元|[¥￥]\s*149(?:\.0+)?', plain)) and bool(re.search(r'9999\s*个?月', plain)))
        turn = chat(frontend, '这门课提供一对一答疑和证书吗？能保证学完找到工作吗？资料没有写就直接说明。请简短回答。')
        verify_cards(turn, FRONTEND)
        verify_history(frontend)

        suitability = create()
        turn = chat(suitability, '我只学完 Java 基础，还没用过 Spring Boot 和 MyBatis。《可能是史上最全的微服务技术栈课程》适合现在学吗？课程明确要求什么基础？请展示卡片并简短回答。')
        verify_cards(turn, MICROSERVICES)
        check('suitability cites the two explicit framework prerequisites', 'springboot' in re.sub(r'\s+', '', turn['answer']).lower() and 'mybatis' in turn['answer'].lower())
        verify_history(suitability)

        qualified = create()
        turn = chat(qualified, '我有 Java 开发基础，已熟练使用 Spring Boot 和 MyBatis，想系统了解微服务技术栈，选择适合已有这些基础的课程。请推荐最合适的一门，说明理由并展示卡片，简短回答。')
        verify_cards(turn, MICROSERVICES)
        turn = chat(qualified, '刚才这门课现在多少钱，学习有效期是多少个月？在哪里查看详情再决定？请简短回答。')
        verify_cards(turn, MICROSERVICES)
        plain = re.sub(r'[*_`,，]', '', turn['answer'])
        check('microservices price and raw month duration are explained', bool(re.search(r'199(?:\.0+)?\s*元|[¥￥]\s*199(?:\.0+)?', plain)) and bool(re.search(r'9999\s*个?月', plain)))
        verify_history(qualified)
        report['automatic_checks_passed'] = True
    except Exception as error:
        report['error'] = {'type': type(error).__name__, 'message': str(error)[:500]}
        raise
    finally:
        for session in report['sessions']:
            try:
                call('POST', '/chat/stop?sessionId=' + session['id'])
                listed = call('GET', '/session?pageNo=1&pageSize=50')['list']
                meta = next(row for row in listed if row['sessionId'] == session['id'])
                created = datetime.datetime.fromisoformat(meta['createTime']).replace(tzinfo=datetime.timezone(datetime.timedelta(hours=8)))
                if not started - datetime.timedelta(minutes=1) <= created <= datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(minutes=1):
                    raise AssertionError('Cleanup creation time mismatch')
                history = call('GET', '/session/' + session['id'])
                questions = [m['content'] for m in history if m['type'] == 'USER']
                if questions != session['questions'][:len(questions)] or len(questions) > len(session['questions']):
                    raise AssertionError('Cleanup question sequence mismatch')
                session['serverCreatedAt'] = meta['createTime']
                session['savedQuestions'] = questions
                call('DELETE', '/session/' + session['id'])
                report['cleaned'].append(session['id'])
            except Exception as error:
                report['cleanup_errors'].append({'sessionId': session['id'], 'type': type(error).__name__, 'message': str(error)[:300]})
            save()
        if not report['cleanup_errors']:
            report['remaining_synthetic_sessions'] = int(call('GET', '/session?pageNo=1&pageSize=50')['total'])
        report['passed'] = bool(report.get('automatic_checks_passed')) and not report['cleanup_errors'] and report.get('remaining_synthetic_sessions') == 0
        report['completed'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        save()
        print('Report: ' + str(report_path), flush=True)
        if report['cleanup_errors']:
            raise AssertionError('Cleanup failed; inspect saved report')


if __name__ == '__main__':
    main()
