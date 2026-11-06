"""Local protocol, privacy and CLI checks. All upstreams are loopback test fixtures."""
import importlib.util
import json
import os
import pathlib
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = pathlib.Path(__file__).resolve().parent
SUPPORT = HERE.parent / 'mcp-evaluation/live-support.py'
spec = importlib.util.spec_from_file_location('pilot_support', SUPPORT)
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)


class Fixture:
    def __init__(self):
        self.requests = []
        self.extra_tool = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def send(self, value=None, status=200, sse=False, session=False):
                raw = json.dumps(value, ensure_ascii=False).encode() if value is not None else b''
                if sse:
                    raw = b'data: ' + raw + b'\n\n'
                self.send_response(status)
                self.send_header('Content-Type', 'text/event-stream' if sse else 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                if session:
                    self.send_header('Mcp-Session-Id', 'probe-session')
                self.end_headers()
                self.wfile.write(raw)

            def do_DELETE(self):
                owner.requests.append(('DELETE', self.path, dict(self.headers), None))
                self.send()

            def do_GET(self):
                owner.requests.append(('GET', self.path, dict(self.headers), None))
                self.send([])

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
                value = json.loads(raw) if raw else None
                owner.requests.append(('POST', self.path, dict(self.headers), value))
                if self.path == '/mcp':
                    method = value['method']
                    if method == 'notifications/initialized':
                        self.send(status=202)
                        return
                    if method == 'initialize':
                        result = {'protocolVersion': '2025-06-18', 'serverInfo': {'name': 'course-catalog'}}
                    elif method == 'tools/list':
                        tools = [{'name': 'getCourseDetail'}]
                        if owner.extra_tool:
                            tools.append({'name': 'writeSomething'})
                        result = {'tools': tools}
                    else:
                        result = {'content': [{'type': 'text', 'text': json.dumps({'schemaVersion': 1,
                            'status': 'FOUND', 'data': {'id': '1589888774267072513'}})}]}
                    self.send({'jsonrpc': '2.0', 'id': value['id'], 'result': result}, sse=True, session=method == 'initialize')
                elif self.path.startswith('/session?'):
                    self.send({'sessionId': 'owned-pilot-session'})
                elif self.path == '/chat':
                    events = [{'eventType': 1001, 'eventData': '原始前置条件：熟练使用SpringBoot、MyBatis。'},
                        {'eventType': 1004, 'eventData': {'cards': [{'name': '本地夹具课程', 'priceInCents': None, 'validDuration': 12}]}},
                        {'eventType': 1002}]
                    raw = ''.join('data: ' + json.dumps(e, ensure_ascii=False) + '\n\n' for e in events).encode()
                    self.send_response(200)
                    self.send_header('Content-Length', str(len(raw)))
                    self.send_header('Content-Type', 'text/event-stream')
                    self.end_headers()
                    self.wfile.write(raw)
                elif self.path == '/chat/completions':
                    self.send({'choices': [{'message': {'content': 'PRIVATE_ANSWER_SENTINEL'}}],
                               'usage': {'prompt_tokens': 1, 'completion_tokens': 1}})
                else:
                    self.send(status=404)

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class PilotTest(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture()
        self.temporary = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.fixture.close()
        self.temporary.cleanup()

    def test_probe_discovers_reads_and_closes_session_without_model(self):
        result = support.probe_mcp(self.fixture.port, 'fixture-token')
        self.assertTrue(result['passed'])
        self.assertEqual(['initialize', 'notifications/initialized', 'tools/list', 'tools/call'],
            [r[3]['method'] for r in self.fixture.requests if r[0] == 'POST'])
        self.assertEqual('DELETE', self.fixture.requests[-1][0])
        self.assertTrue(all(r[2]['Authorization'] == 'Bearer fixture-token' for r in self.fixture.requests))
        self.assertEqual('probe-session', self.fixture.requests[-1][2]['Mcp-Session-Id'])

    def test_probe_rejects_extra_tool_and_still_closes_session(self):
        self.fixture.extra_tool = True
        with self.assertRaises(ValueError):
            support.probe_mcp(self.fixture.port, 'fixture-token')
        self.assertEqual('DELETE', self.fixture.requests[-1][0])
        self.assertFalse(any(r[3] and r[3].get('method') == 'tools/call' for r in self.fixture.requests))

    def run_client(self, session=None):
        return subprocess.run([sys.executable, '-X', 'utf8', str(HERE / 'chat.py')], input=json.dumps({
            'question': '这门课适合什么基础的人？', 'sessionId': session, 'port': self.fixture.port,
            'stateFile': str(pathlib.Path(self.temporary.name) / 'sessions.json')}, ensure_ascii=False),
            text=True, encoding='utf-8', capture_output=True, timeout=10)

    def test_cli_preserves_chinese_sse_unknown_price_and_owned_continuation(self):
        first = self.run_client()
        self.assertEqual(0, first.returncode, first.stderr)
        self.assertIn('熟练使用SpringBoot、MyBatis', first.stdout)
        self.assertIn('价格待确认', first.stdout)
        self.assertIn('12个月', first.stdout)
        self.assertEqual('这门课适合什么基础的人？', next(r[3]['question'] for r in self.fixture.requests if r[1] == '/chat'))
        second = self.run_client('owned-pilot-session')
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(1, sum(r[1].startswith('/session?') for r in self.fixture.requests))

    def test_cli_rejects_unrecorded_session_without_api_request(self):
        result = self.run_client('foreign-session')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.fixture.requests)

    def test_pilot_disables_faults_and_omits_prompt_response_and_credentials(self):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        output = pathlib.Path(self.temporary.name) / 'gateway'
        wrapper = '''import runpy,sys,urllib.request
script,output,provider,port,mcp=sys.argv[1:]
original=urllib.request.urlopen
def open_local(request,*args,**kwargs):
    url=request.full_url if hasattr(request,'full_url') else str(request)
    if url.startswith('https://api.deepseek.com/'):
        request=urllib.request.Request('http://127.0.0.1:'+provider+'/chat/completions',data=request.data,headers=dict(request.header_items()),method=request.get_method())
    elif url.startswith('https://'):
        raise OSError('External destinations disabled in this test')
    return original(request,*args,**kwargs)
urllib.request.urlopen=open_local
sys.argv=[script,'--pilot','--port',port,'--mcp-port',mcp,'--output',output]
runpy.run_path(script,run_name='__main__')
'''
        env = {**os.environ, 'MCP_EVAL_PROVIDER_KEY': 'provider-fixture-secret', 'MCP_EVAL_MODEL_KEY': 'client-fixture-secret',
               'TJ_MCP_SERVER_TOKEN': 'fixture-token'}
        process = subprocess.Popen([sys.executable, '-X', 'utf8', '-c', wrapper, str(SUPPORT), str(output),
            str(self.fixture.port), str(port), str(self.fixture.port)], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        try:
            base = f'http://127.0.0.1:{port}'
            deadline = time.monotonic() + 8
            while True:
                try:
                    with urllib.request.urlopen(base + '/status', timeout=1) as response:
                        self.assertEqual('pilot', json.load(response)['mode'])
                    break
                except OSError:
                    if time.monotonic() > deadline:
                        self.fail('Pilot fixture gateway did not start')
                    time.sleep(.05)
            def post(path, payload):
                return urllib.request.urlopen(urllib.request.Request(base + path, data=json.dumps(payload).encode(),
                    headers={'Content-Type': 'application/json', 'Authorization': 'Bearer client-fixture-secret'}), timeout=4)
            with self.assertRaises(urllib.error.HTTPError) as fault:
                post('/control', {'mcp_delay_ms': 90000})
            self.assertEqual(404, fault.exception.code)
            with post('/chat/completions', {'messages': [{'role': 'user', 'content': 'PRIVATE_QUESTION_SENTINEL'},
                    {'role': 'tool', 'content': '{"id":"1589888774267072513"}'}]}) as response:
                self.assertIn('PRIVATE_ANSWER_SENTINEL', response.read().decode())
            with self.assertRaises(urllib.error.HTTPError) as blocked:
                post('/chat/completions', {'messages': [{'role': 'tool', 'content': '{"id":"910000000000010007"}'}]})
            self.assertEqual(403, blocked.exception.code)
            with urllib.request.urlopen(base + '/pilot/check', timeout=5) as response:
                self.assertTrue(json.load(response)['passed'])
        finally:
            process.terminate()
            process.communicate(timeout=5)
        logs = '\n'.join(p.read_text(encoding='utf-8') for p in output.glob('*.jsonl'))
        for secret in ('PRIVATE_QUESTION_SENTINEL', 'PRIVATE_ANSWER_SENTINEL', 'provider-fixture-secret', 'client-fixture-secret', 'fixture-token'):
            self.assertNotIn(secret, logs)
        metadata = [json.loads(line) for line in (output / 'model-calls.jsonl').read_text(encoding='utf-8').splitlines()]
        self.assertEqual(1, len(metadata))
        self.assertNotIn('request', metadata[0])
        self.assertNotIn('responses', metadata[0])


if __name__ == '__main__':
    unittest.main()
