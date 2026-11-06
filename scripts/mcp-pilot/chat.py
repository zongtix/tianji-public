"""Interactive client for the loopback two-course pilot; credentials stay in its gateway."""
import json
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

USER = '900000000000000027'


def request(base, method, path, payload=None):
    body = json.dumps(payload, ensure_ascii=False).encode() if payload is not None else None
    return urllib.request.urlopen(urllib.request.Request(base + path, data=body, method=method,
        headers={'Content-Type': 'application/json', 'user-info': USER}), timeout=160)


def call(base, method, path):
    with request(base, method, path) as response:
        raw = response.read()
    value = json.loads(raw) if raw else None
    if isinstance(value, dict) and 'code' in value:
        if value['code'] != 200:
            raise ValueError('Pilot API returned a non-success code')
        return value.get('data')
    return value


def main():
    args = json.load(sys.stdin)
    base = f"http://127.0.0.1:{int(args['port'])}"
    state_file = pathlib.Path(args['stateFile'])
    state = json.loads(state_file.read_text(encoding='utf-8')) if state_file.exists() else {'sessionIds': []}
    session = args.get('sessionId')
    if session:
        if session not in state['sessionIds']:
            raise ValueError('Only sessions created by this pilot client can be continued')
        call(base, 'GET', '/session/' + urllib.parse.quote(session, safe=''))
    else:
        session = call(base, 'POST', '/session?n=0')['sessionId']
        state['sessionIds'].append(session)
        state['lastSessionId'] = session
        state_file.parent.mkdir(parents=True, exist_ok=True)
        state_file.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
    print('Session: ' + session, flush=True)
    stopped = False
    failed = False
    try:
        with request(base, 'POST', '/chat', {'sessionId': session, 'question': args['question']}) as response:
            for raw in response:
                line = raw.decode('utf-8').strip()
                if not line.startswith('data:'):
                    continue
                event = json.loads(line[5:])
                if event['eventType'] == 1001:
                    print(event['eventData'], end='', flush=True)
                elif event['eventType'] == 1004:
                    for card in event['eventData']['cards']:
                        cents = card.get('priceInCents')
                        price = f'{cents / 100:.2f}元' if cents is not None else '价格待确认'
                        duration = str(card['validDuration']) + '个月' if card.get('validDuration') is not None else '有效期待确认'
                        print(f"\n[课程] {card['name']} | {price} | {duration}", flush=True)
                elif event['eventType'] == 1005:
                    failed = True
                    print('\n' + json.dumps(event['eventData'], ensure_ascii=False), file=sys.stderr)
                elif event['eventType'] == 1002:
                    stopped = True
        print()
        if failed or not stopped:
            raise ValueError('Pilot answer did not finish successfully')
    except KeyboardInterrupt:
        try:
            call(base, 'POST', '/chat/stop?' + urllib.parse.urlencode({'sessionId': session}))
        except (OSError, ValueError):
            pass
        print('\n已停止本轮问答。', flush=True)


if __name__ == '__main__':
    try:
        main()
    except urllib.error.HTTPError as error:
        print(f'Pilot API HTTP {error.code}', file=sys.stderr)
        raise SystemExit(1)
    except (OSError, ValueError) as error:
        print('Pilot client: ' + type(error).__name__, file=sys.stderr)
        raise SystemExit(1)
