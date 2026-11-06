"""Opt-in real model/SSE checks for course browsing; deletes only its own test sessions."""
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
    parser.add_argument('--report', default='target/course-browse-live/report.json')
    args = parser.parse_args()
    user_id = '900000000000000024'
    sessions = []
    report = {'started': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'turns': [], 'checks': []}
    output = pathlib.Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def request(method, port, path, payload=None):
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
        return urllib.request.urlopen(urllib.request.Request(
            f'http://127.0.0.1:{port}' + path, method=method, data=data,
            headers={'Content-Type': 'application/json', 'user-info': user_id}), timeout=180)

    def call(method, port, path, payload=None):
        with request(method, port, path, payload) as response:
            body = response.read()
        value = json.loads(body) if body else None
        if isinstance(value, dict) and 'code' in value:
            if value['code'] != 200:
                raise AssertionError('API code ' + str(value['code']))
            return value.get('data')
        return value

    def check(name, passed):
        report['checks'].append({'name': name, 'passed': bool(passed)})
        save()
        print(('PASS ' if passed else 'FAIL ') + name, flush=True)
        if not passed:
            raise AssertionError(name)

    def chat(session, question, allow_empty=False):
        turn = {'sessionId': session, 'question': question, 'answer': '', 'cards': [], 'events': []}
        report['turns'].append(turn)
        try:
            with request('POST', 8096, '/chat', {'sessionId': session, 'question': question}) as response:
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
            check('nonempty answer without internal retrieval narration', bool(turn['answer'].strip()) and not any(
                text in turn['answer'] for text in ('searchCourses', 'browseCourses', 'hasEnrollmentData',
                                                   '关键词检索', '语义检索', '空列表', 'AI演示', '演示数据')))
            if allow_empty and not turn['cards']:
                check('empty free results are explained without cards', turn['events'].count(1004) == 0
                      and '免费' in turn['answer'] and any(word in turn['answer'] for word in ('暂', '未', '没有', '无法')))
            else:
                check('one bounded card event before completion', 1 <= len(turn['cards']) <= 3
                      and turn['events'].count(1004) == 1 and turn['events'].index(1004) < turn['events'].index(1002))
            if question in ('有哪些热门课程推荐？', '随便推荐几门', '有什么免费课', '我也不知道学什么'):
                check('browsing leaves validity figures on the cards', re.search(r'\d+\s*个月', turn['answer']) is None)
            return turn
        finally:
            save()

    def facts(turn, free_only=False):
        if not turn['cards']:
            return
        ids = ','.join(card['courseId'] for card in turn['cards'])
        current = call('GET', 8086, '/courses/simpleInfo/list?' + urllib.parse.urlencode({'ids': ids}))
        by_id = {str(value['id']): value for value in current}
        for card in turn['cards']:
            value = by_id[card['courseId']]
            detail = call('GET', 8086, '/course/' + card['courseId'] + '/baseInfo')
            report.setdefault('courseFacts', {})[card['courseId']] = {'simple': value, 'detail': detail}
            check('card uses current available course facts', value['status'] in (2, 4)
                  and card['name'] == value['name'] and card['validDuration'] == value['validDuration']
                  and card['priceInCents'] == (0 if value.get('free') is True else value['price']))
            check('real teaching course rather than demo', not any(marker in json.dumps(detail, ensure_ascii=False)
                  for marker in ('【AI演示】', 'AI联调演示数据', '不提供真实授课')))
            check('placeholder slogans do not justify a recommendation',
                  (detail.get('detail') or '').strip() != '慢工出细活' or bool(detail.get('catalogue')))
            if free_only:
                check('free request uses current explicit free flag', value.get('free') is True)

    try:
        ranked = call('GET', 8083, '/courses/popular')
        report['ranked'] = ranked
        counts = [value['sold'] for value in ranked if value.get('sold') is not None]
        check('business search returns enrollment order', bool(ranked) and counts == sorted(counts, reverse=True))
        check('dedicated synthetic identity starts empty', int(call('GET', 8096, '/session?pageNo=1&pageSize=10')['total']) == 0)
        for question in ('有哪些热门课程推荐？', '随便推荐几门', '有什么免费课', '我也不知道学什么'):
            session = call('POST', 8096, '/session')['sessionId']
            sessions.append(session)
            turn = chat(session, question, allow_empty='免费' in question)
            facts(turn, '免费' in question)
            source = call('GET', 8083, '/courses/popular?free=true') if '免费' in question else ranked
            if '免费' in question:
                report['freeRanked'] = source
            check('cards came from ranked business search', all(card['courseId'] in {str(value['id']) for value in source}
                  for card in turn['cards']))
            if question == '有哪些热门课程推荐？':
                followup = chat(session, '第一门多少钱，学习有效期是多少？简短回答。')
                check('followup preserves selected first course', followup['cards'][0]['courseId'] == turn['cards'][0]['courseId'])
                facts(followup)
            if question == '随便推荐几门':
                followup = chat(session, '这几门分别多少钱，学习有效期各是多少个月？请按课程逐一说明。')
                check('multi-course followup preserves every selected course',
                      {card['courseId'] for card in followup['cards']} == {card['courseId'] for card in turn['cards']})
                facts(followup)
                for card in followup['cards']:
                    check('followup includes each current validity',
                          str(card['validDuration']) in followup['answer'])
            history = call('GET', 8096, '/session/' + session)
            turns = [value for value in report['turns'] if value['sessionId'] == session]
            check('history stores visible questions answers and cards once', len(history) == len(turns) * 2
                  and all(history[2*i]['content'] == value['question']
                          and history[2*i+1]['content'] == value['answer']
                          and history[2*i+1]['params'].get('courseCards', []) == value['cards']
                          for i, value in enumerate(turns)))
        report['automatedPassed'] = True
    finally:
        report['cleanup'] = []
        for session in sessions:
            try:
                history = call('GET', 8096, '/session/' + session)
                expected = [value['question'] for value in report['turns'] if value['sessionId'] == session]
                actual = [value['content'] for value in history if value['type'] == 'USER']
                if actual != expected:
                    raise AssertionError('session question sequence changed; retained')
                call('DELETE', 8096, '/session/' + session)
                report['cleanup'].append({'sessionId': session, 'deleted': True})
            except Exception as error:
                report['cleanup'].append({'sessionId': session, 'error': str(error)})
        save()


if __name__ == '__main__':
    main()
