"""Opt-in real model/SSE checks with synthetic questions; cleans only its own sessions."""
import argparse
import datetime
import json
import pathlib
import re
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--real-model', action='store_true', required=True)
    parser.add_argument('--guide', action='store_true', help='Also check suitability, comparison, purchase entry and clarification')
    parser.add_argument('--base-url', default='http://127.0.0.1:18096')
    parser.add_argument('--course-url', default='http://127.0.0.1:18086')
    parser.add_argument('--user-id', default='900000000000000001')
    parser.add_argument('--report', default='target/course-semantic-live/report.json')
    args = parser.parse_args()
    for address in (args.base_url, args.course_url):
        if urllib.parse.urlsplit(address).hostname not in ('127.0.0.1', 'localhost', '::1'):
            parser.error('Only loopback services are allowed')
    if not args.user_id.isdigit():
        parser.error('Synthetic internal user ID must be numeric')
    sessions, turns, checks, cleanup_errors = [], [], [], []
    passed = False

    def request(method, path, payload=None, base=None):
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
        req = urllib.request.Request((base or args.base_url).rstrip('/') + path, data=data, method=method,
                                     headers={'Content-Type': 'application/json', 'user-info': args.user_id})
        return urllib.request.urlopen(req, timeout=150)

    def call(method, path, payload=None, base=None):
        with request(method, path, payload, base) as response:
            body = response.read()
            result = json.loads(body) if body else None
        if isinstance(result, dict) and 'code' in result:
            if result['code'] != 200:
                raise AssertionError('API returned code ' + str(result['code']))
            return result.get('data')
        return result

    def check(name, result):
        checks.append({'case': name, 'passed': bool(result)})
        print(('PASS ' if result else 'FAIL ') + name, flush=True)
        if not result:
            raise AssertionError(name)

    def create():
        session = call('POST', '/session')['sessionId']
        sessions.append(session)
        return session

    def chat(session, question):
        turn = {'question': question, 'answer': '', 'cards': [], 'events': []}
        turns.append(turn)
        with request('POST', '/chat', {'sessionId': session, 'question': question}) as response:
            check('SSE content type', 'text/event-stream' in response.headers.get('Content-Type', ''))
            for raw in response:
                line = raw.decode('utf-8').strip()
                if not line.startswith('data:'):
                    continue
                event = json.loads(line[5:].strip())
                kind = event['eventType']
                turn['events'].append(kind)
                if kind == 1001:
                    turn['answer'] += event['eventData']
                elif kind == 1004:
                    turn['cards'].extend(event['eventData']['cards'])
                elif kind == 1005:
                    turn['error'] = event['eventData']
        check('one terminal event without error', turn['events'].count(1002) == 1 and 'error' not in turn)
        check('nonempty answer', bool(turn['answer'].strip()))
        return turn

    def verify_cards(turn, expected):
        cards = turn['cards']
        check('bounded cards include expected semantic course', 1 <= len(cards) <= 3
              and any(card['courseId'] == expected for card in cards))
        check('unavailable demo courses excluded', all(card['courseId'] not in
              ('910000000000010011', '910000000000010012') for card in cards))
        ids = ','.join(card['courseId'] for card in cards)
        facts = call('GET', '/courses/simpleInfo/list?' + urllib.parse.urlencode({'ids': ids}), base=args.course_url)
        by_id = {str(course['id']): course for course in facts}
        check('cards use live name price and duration', all(card['courseId'] in by_id
              and card['name'] == by_id[card['courseId']]['name']
              and card['priceInCents'] == (0 if by_id[card['courseId']].get('free') is True else by_id[card['courseId']]['price'])
              and card['validDuration'] == by_id[card['courseId']]['validDuration'] for card in cards))
        check('purchase start comes from current course API', all(by_id[card['courseId']].get('purchaseStartTime') for card in cards))

    try:
        session = create()
        question = '我已经会 Java 基础，想解决多个请求同时扣库存的问题，请推荐一门最合适的课程并展示卡片。'
        first = chat(session, question)
        verify_cards(first, '910000000000010003')
        stored = call('GET', '/session/' + session)
        check('original question and final answer saved once', len(stored) == 2
              and stored[0]['content'] == question and stored[1]['content'] == first['answer'])
        check('semantic cards persisted', stored[1]['params'].get('courseCards') == first['cards'])
        second = chat(session, '刚才推荐的并发课多少钱？学习有效期是多少个月？')
        verify_cards(second, '910000000000010003')
        check('followup duration uses months', bool(re.search(r'12\s*个?月', re.sub(r'[*_`]', '', second['answer']))))
        stored = call('GET', '/session/' + session)
        check('followup history has no internal route turns', len(stored) == 4
              and [item['type'] for item in stored] == ['USER', 'ASSISTANT'] * 2)

        mysql = create()
        turn = chat(mysql, '订单列表查询太慢，想学习执行计划和索引优化，请推荐一门最合适的课程并展示卡片。')
        verify_cards(turn, '910000000000010004')
        if args.guide:
            suitability = chat(session, '我会 Java 语法和集合，还没学 Spring Boot。刚才那门并发课适合我吗，需要什么基础？')
            verify_cards(suitability, '910000000000010003')
            chat(session, '这门课提供一对一答疑吗？能保证我学完找到工作吗？')

            comparison_session = create()
            pair = chat(comparison_session,
                '我会 Java 基础、集合和基础 SQL，想解决库存更新冲突。请推荐两门课程，一门帮助理解应用内线程安全，一门帮助理解数据库事务与行锁，分别展示卡片。')
            check('semantic recommendation supplies both courses for followup comparison',
                  {card['courseId'] for card in pair['cards']} == {'910000000000010003', '910000000000010004'})
            verify_cards(pair, '910000000000010003')
            comparison = chat(comparison_session,
                '刚才推荐的这两门有什么区别，按我已有的基础和库存更新冲突的目标，应该怎么选？')
            compare_ids = {card['courseId'] for card in comparison['cards']}
            check('comparison stays with the two named courses', compare_ids ==
                  {'910000000000010003', '910000000000010004'})
            check('comparison displays detail cards without new recommendation reasons',
                  all(card.get('reason') is None for card in comparison['cards']))
            verify_cards(comparison, '910000000000010003')
            purchase = chat(comparison_session, '我想进一步了解并发那门，多少钱，有效期多久，在哪里看详情并继续购买？')
            verify_cards(purchase, '910000000000010003')
            check('purchase followup has current price and duration',
                  bool(re.search(r'159(?:\.0+)?\s*元|[¥￥]\s*159(?:\.0+)?', re.sub(r'[*_`]', '', purchase['answer'])))
                  and bool(re.search(r'12\s*个?月', re.sub(r'[*_`]', '', purchase['answer']))))

            vague_session = create()
            vague = chat(vague_session, '我想学点技术，推荐一门适合我的课。')
            check('insufficient needs do not emit course cards', not vague['cards'])
            unrelated_session = create()
            unrelated = chat(unrelated_session, '我想学习法语发音，用于去法国旅行，请推荐相关课程。')
            check('no unrelated course cards for unsupported subject', not unrelated['cards'])

            stored = call('GET', '/session/' + comparison_session)
            check('recommendation comparison and purchase history preserve visible turns only', len(stored) == 6
                  and [item['type'] for item in stored] == ['USER', 'ASSISTANT'] * 3
                  and stored[1]['content'] == pair['answer'] and stored[3]['content'] == comparison['answer']
                  and stored[5]['content'] == purchase['answer'])
        passed = True
    finally:
        for session in sessions:
            try:
                call('POST', '/chat/stop?' + urllib.parse.urlencode({'sessionId': session}))
                call('DELETE', '/session/' + session)
            except Exception as error:
                cleanup_errors.append({'sessionId': session, 'error': type(error).__name__})
        report = pathlib.Path(args.report)
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps({'checked_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                          'passed': passed and not cleanup_errors, 'checks': checks, 'turns': turns,
                          'created_sessions': sessions, 'cleanup_errors': cleanup_errors,
                          'guide_review_required': ['recommendation reasons grounded in current course material',
                              'prerequisites match material without invented requirements',
                              'comparison explains Java thread safety versus database transactions',
                              'unsupported services and employment guarantees are not invented',
                              'vague needs ask for goals and background before selecting courses',
                              'duration does not invent a purchase or activation start rule',
                              'purchase followup points to course card without claiming an order was placed'] if args.guide else [],
                          'scope': 'isolated loopback AIGC + real course API, configured real models; gateway/browser not tested'},
                         ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print('Report: ' + str(report), flush=True)
        if cleanup_errors:
            raise AssertionError('Cleanup failed; inspect report')


if __name__ == '__main__':
    main()
