"""Loopback-only acceptance proxy: real DeepSeek, controlled MCP transport, OTLP capture.

Authorization headers are never persisted. The only remote destination is pinned below.
"""
import argparse
import gzip
import json
import os
import pathlib
import sys
import threading
import time
import urllib.error
import urllib.request
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APPROVED_COURSE_IDS = frozenset(('1589888774267072513', '1589905661084430337'))


def read_mcp_reply(raw):
    text = raw.decode('utf-8')
    if text.lstrip().startswith('{'):
        return json.loads(text)
    for event in text.replace('\r\n', '\n').split('\n\n'):
        data = '\n'.join(line[5:].lstrip() for line in event.splitlines() if line.startswith('data:'))
        if data:
            return json.loads(data)
    raise ValueError('MCP returned no response')


def probe_mcp(port, token):
    """Fixed, loopback-only readiness probe; no model call and no arbitrary tool."""
    url = f'http://127.0.0.1:{port}/mcp'
    headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
               'Accept': 'application/json, text/event-stream'}

    def rpc(method, params, identifier=None):
        body = {'jsonrpc': '2.0', 'method': method, 'params': params}
        if identifier is not None:
            body['id'] = identifier
        request = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers)
        with urllib.request.urlopen(request, timeout=10) as response:
            session = response.headers.get('Mcp-Session-Id')
            if session:
                headers['Mcp-Session-Id'] = session
            raw = response.read()
        value = read_mcp_reply(raw) if raw else {}
        if 'error' in value:
            raise ValueError('MCP probe failed')
        return value.get('result', {})

    try:
        initialized = rpc('initialize', {'protocolVersion': '2025-06-18', 'capabilities': {},
            'clientInfo': {'name': 'tianji-pilot-readiness', 'version': '1.0.0'}}, 1)
        if initialized.get('serverInfo', {}).get('name') != 'course-catalog':
            raise ValueError('Unexpected MCP server')
        headers['Mcp-Protocol-Version'] = initialized['protocolVersion']
        rpc('notifications/initialized', {})
        tools = rpc('tools/list', {}, 2).get('tools', [])
        if len(tools) != 1 or tools[0].get('name') != 'getCourseDetail':
            raise ValueError('Unexpected MCP tool set')
        course_id = '1589888774267072513'
        result = rpc('tools/call', {'name': 'getCourseDetail', 'arguments': {'courseId': course_id}}, 3)
        if result.get('isError') or len(result.get('content', [])) != 1:
            raise ValueError('MCP detail probe failed')
        value = json.loads(result['content'][0]['text'])
        if value.get('schemaVersion') != 1 or value.get('status') != 'FOUND' or str(value.get('data', {}).get('id')) != course_id:
            raise ValueError('Unexpected MCP detail')
        return {'server': 'course-catalog', 'tool': 'getCourseDetail', 'courseId': course_id, 'passed': True}
    finally:
        if headers.get('Mcp-Session-Id'):
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers=headers, method='DELETE'), timeout=3):
                    pass
            except (OSError, urllib.error.HTTPError):
                pass


def unapproved_course_ids(payload):
    found = set()

    def visit(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key in ('id', 'courseId') and isinstance(item, (str, int)) and str(item).isdigit():
                    if str(item) not in APPROVED_COURSE_IDS:
                        found.add(str(item))
                visit(item)
        elif isinstance(value, list):
            for item in value:
                visit(item)

    for message in payload.get('messages', []):
        if message.get('role') == 'tool':
            visit(json.loads(message['content']))
    return found


def restrict_search_results(value):
    wrapped = isinstance(value, dict) and 'data' in value
    candidates = value['data'] if wrapped else value
    if not isinstance(candidates, list):
        raise ValueError('Expected a search result list')
    accepted = [item for item in candidates if str(item.get('id') if isinstance(item, dict) else item) in APPROVED_COURSE_IDS]
    if wrapped:
        value = {**value, 'data': accepted}
    else:
        value = accepted
    return value, len(candidates) - len(accepted)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18100)
    parser.add_argument('--mcp-port', type=int, default=18097)
    parser.add_argument('--search-port', type=int, default=8083)
    parser.add_argument('--pilot', action='store_true', help='No fault injection or prompt/response capture')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    output = pathlib.Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    if not args.pilot:
        sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / 'target/mcp-evaluation/python'))
        from google.protobuf.json_format import MessageToDict
        from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

    provider_key = os.environ.pop('MCP_EVAL_PROVIDER_KEY')
    model_key = os.environ.pop('MCP_EVAL_MODEL_KEY')
    server_token = os.environ.pop('TJ_MCP_SERVER_TOKEN', '')
    lock = threading.Lock()
    private_values = [value for value in (provider_key, model_key, server_token) if value]
    state = {'case': None, 'mcp_delay_ms': 0, 'mcp_pending': 0, 'models': 0, 'mcp': 0, 'spans': 0, 'credential_leaks': 0,
             'filtered_candidates': 0, 'blocked_model_requests': 0,
             'mode': 'pilot' if args.pilot else 'evaluation', 'approved_course_ids': sorted(APPROVED_COURSE_IDS)}

    def append(name, value):
        with lock:
            serialized = json.dumps(value, ensure_ascii=False)
            if any(secret in serialized for secret in private_values):
                state['credential_leaks'] += 1
                for secret in private_values:
                    serialized = serialized.replace(secret, '[REDACTED]')
            with (output / name).open('a', encoding='utf-8') as stream:
                stream.write(serialized + '\n')

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *args):
            pass

        def response(self, status, data=b'', content_type='application/json'):
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True

        def do_GET(self):
            if args.pilot and self.headers.get('Origin'):
                self.response(403)
                return
            if self.path == '/status':
                with lock:
                    snapshot = dict(state)
                self.response(200, json.dumps(snapshot).encode())
            elif args.pilot and self.path == '/pilot/check':
                try:
                    self.response(200, json.dumps(probe_mcp(args.mcp_port, server_token)).encode())
                except Exception:
                    self.response(503, b'{"error":"pilot MCP probe failed"}')
            elif self.path == '/mcp':
                self.response(405)
            elif urllib.parse.urlsplit(self.path).path in ('/search-api/courses/name', '/search-api/courses/popular'):
                try:
                    path = self.path.removeprefix('/search-api')
                    request = urllib.request.Request(f'http://127.0.0.1:{args.search_port}' + path,
                        headers={'x-request-from': 'feign'})
                    with urllib.request.urlopen(request, timeout=10) as upstream:
                        value, removed = restrict_search_results(json.load(upstream))
                    with lock:
                        state['filtered_candidates'] += removed
                        case = state['case']
                    append('scope-filters.jsonl', {'case': case,
                        'path': urllib.parse.urlsplit(path).path if args.pilot else path, 'removed': removed})
                    self.response(200, json.dumps(value, ensure_ascii=False).encode())
                except Exception:
                    self.response(502, b'{"error":"evaluation search unavailable"}')
            else:
                self.response(404)

        def do_DELETE(self):
            if self.path == '/mcp':
                self.forward_mcp(b'')
            else:
                self.response(404)

        def do_POST(self):
            if self.headers.get('Origin'):
                self.response(403)
                return
            if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                pieces = []
                while True:
                    size = int(self.rfile.readline().split(b';', 1)[0].strip(), 16)
                    if not size:
                        while self.rfile.readline() not in (b'\r\n', b'\n', b''):
                            pass
                        break
                    pieces.append(self.rfile.read(size))
                    self.rfile.read(2)
                body = b''.join(pieces)
            else:
                body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            if self.path == '/control':
                if args.pilot:
                    self.response(404)
                    return
                control = json.loads(body)
                with lock:
                    state['case'] = control.get('case')
                    state['mcp_delay_ms'] = int(control.get('mcp_delay_ms', 0))
                self.response(200, b'{}')
            elif self.path == '/chat/completions':
                self.forward_model(body)
            elif self.path == '/mcp':
                self.forward_mcp(body)
            elif self.path == '/v1/traces':
                if args.pilot:
                    self.response(404)
                    return
                if self.headers.get('Content-Encoding') == 'gzip':
                    body = gzip.decompress(body)
                request = ExportTraceServiceRequest.FromString(body)
                for resource in request.resource_spans:
                    for scope in resource.scope_spans:
                        for span in scope.spans:
                            record = MessageToDict(span, preserving_proto_field_name=True)
                            record['resource'] = MessageToDict(resource.resource, preserving_proto_field_name=True)
                            append('spans.jsonl', record)
                            with lock:
                                state['spans'] += 1
                self.response(200, b'', 'application/x-protobuf')
            else:
                self.response(404)

        def forward_model(self, body):
            if self.headers.get('Authorization') != 'Bearer ' + model_key:
                self.response(401)
                return
            payload = json.loads(body)
            try:
                outside = unapproved_course_ids(payload)
            except (ValueError, TypeError, KeyError):
                outside = {'unparseable-tool-result'}
            if outside:
                with lock:
                    state['blocked_model_requests'] += 1
                    case = state['case']
                append('scope-blocks.jsonl', {'case': case, 'unapproved_ids': sorted(outside) if not args.pilot else None})
                self.response(403, b'{"error":{"message":"evaluation course scope blocked"}}')
                return
            with lock:
                state['models'] += 1
                record = {'index': state['models'], 'case': state['case'], 'started': time.time()}
                if not args.pilot:
                    record['request'] = payload
            chunks = []
            status = None
            try:
                request = urllib.request.Request('https://api.deepseek.com/chat/completions', data=body,
                    headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + provider_key})
                with urllib.request.urlopen(request, timeout=100) as upstream:
                    status = upstream.status
                    self.send_response(status)
                    self.send_header('Content-Type', upstream.headers.get('Content-Type', 'application/json'))
                    self.send_header('Connection', 'close')
                    self.end_headers()
                    if payload.get('stream'):
                        for line in upstream:
                            if line.startswith(b'data:'):
                                text = line[5:].strip()
                                if text and text != b'[DONE]':
                                    chunks.append(json.loads(text))
                            self.wfile.write(line)
                            self.wfile.flush()
                    else:
                        data = upstream.read()
                        chunks.append(json.loads(data))
                        self.wfile.write(data)
            except urllib.error.HTTPError as error:
                status = error.code
                # No raw provider errors in client-visible responses or console logs.
                self.response(status, b'{"error":{"message":"evaluation upstream request failed"}}')
            except ConnectionError:
                record['downstream_cancelled'] = True
            except Exception as error:
                record['error_type'] = type(error).__name__
                if status is None:
                    self.response(502, b'{"error":{"message":"evaluation upstream unavailable"}}')
            finally:
                record.update(status=status, finished=time.time())
                if args.pilot:
                    record['usage'] = [chunk['usage'] for chunk in chunks if chunk.get('usage')]
                else:
                    record['responses'] = chunks
                append('model-calls.jsonl', record)
                self.close_connection = True

        def forward_mcp(self, body):
            payload = json.loads(body) if body else {}
            method = payload.get('method', self.command)
            with lock:
                state['mcp'] += 1
                record = {'index': state['mcp'], 'case': state['case'], 'started': time.time(),
                          'method': method, 'params': payload.get('params')}
                delay = state['mcp_delay_ms'] if method == 'tools/call' else 0
                if method == 'tools/call':
                    state['mcp_pending'] += 1
            append('mcp-starts.jsonl', record)
            try:
                if delay:
                    time.sleep(delay / 1000)
                headers = {key: value for key, value in self.headers.items()
                           if key.lower() in ('authorization', 'accept', 'content-type', 'mcp-session-id', 'mcp-protocol-version')}
                request = urllib.request.Request(f'http://127.0.0.1:{args.mcp_port}/mcp',
                    data=body if self.command == 'POST' else None, headers=headers, method=self.command)
                try:
                    upstream = urllib.request.urlopen(request, timeout=10)
                except urllib.error.HTTPError as error:
                    upstream = error
                with upstream:
                    data = upstream.read()
                    record['status'] = upstream.status
                    record['response_bytes'] = len(data)
                    self.send_response(upstream.status)
                    for name in ('Content-Type', 'Mcp-Session-Id', 'Mcp-Protocol-Version'):
                        if name in upstream.headers:
                            self.send_header(name, upstream.headers[name])
                    self.send_header('Content-Length', str(len(data)))
                    self.send_header('Connection', 'close')
                    self.end_headers()
                    self.wfile.write(data)
            except ConnectionError:
                record['downstream_cancelled'] = True
            except Exception as error:
                record['error_type'] = type(error).__name__
                try:
                    self.response(503, b'{"error":"evaluation MCP unavailable"}')
                except ConnectionError:
                    pass
            finally:
                if method == 'tools/call':
                    with lock:
                        state['mcp_pending'] -= 1
                record['finished'] = time.time()
                append('mcp-calls.jsonl', record)
                self.close_connection = True

    print(json.dumps({'listening': args.port, 'mcp_upstream': args.mcp_port}), flush=True)
    ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()


if __name__ == '__main__':
    main()
