"""Isolated seven-turn acceptance boundary. Only the approved DeepSeek endpoint is remote."""
import argparse
import gzip
import json
import os
import pathlib
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APPROVED = frozenset(('1589888774267072513', '1589905661084430337'))
DESTINATION = 'https://api.deepseek.com/chat/completions'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class Budget:
    def __init__(self, limit):
        self.limit, self.used, self.lock = limit, 0, threading.Lock()

    def acquire(self):
        with self.lock:
            if self.used >= self.limit:
                return None
            self.used += 1
            return self.used


def outside_ids(value):
    if isinstance(value, dict):
        own = {str(item) for key, item in value.items() if key in ('id', 'courseId')
               and isinstance(item, (str, int)) and str(item).isdigit() and str(item) not in APPROVED}
        return own | set().union(*(outside_ids(item) for item in value.values()), set())
    if isinstance(value, list):
        return set().union(*(outside_ids(item) for item in value), set())
    return set()


def check_model(payload):
    if payload.get('model') != 'deepseek-flash':
        raise ValueError('Unapproved model')
    for message in payload.get('messages', []):
        if message.get('role') == 'tool' and outside_ids(json.loads(message['content'])):
            raise ValueError('Unapproved course in model input')


def restrict_candidates(value):
    wrapped = isinstance(value, dict) and 'data' in value
    candidates = value['data'] if wrapped else value
    if not isinstance(candidates, list):
        raise ValueError('Invalid search result')
    accepted = [item for item in candidates if str(item.get('id') if isinstance(item, dict) else item) in APPROVED]
    return ({**value, 'data': accepted} if wrapped else accepted), len(candidates) - len(accepted)


def approved_course_request(method, route, body):
    parsed = urllib.parse.urlsplit(route)
    if method == 'GET' and re.fullmatch(r'/course/\d+/baseInfo', parsed.path):
        ids = [parsed.path.split('/')[2]]
    elif method == 'GET' and parsed.path == '/courses/simpleInfo/list':
        ids = [value for item in urllib.parse.parse_qs(parsed.query).get('ids', []) for value in item.split(',')]
    elif method == 'POST' and parsed.path == '/courses/base-info/batch':
        values = json.loads(body)
        if not isinstance(values, list):
            raise ValueError('Invalid batch IDs')
        ids = [str(value) for value in values]
    else:
        raise ValueError('Unapproved course endpoint')
    if not ids or not set(ids) <= APPROVED:
        raise ValueError('Unapproved course IDs')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--course-port', type=int, required=True)
    parser.add_argument('--search-port', type=int, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--limit', type=int, default=80)
    parser.add_argument('--recover-unspent-startup', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.limit <= 80:
        raise ValueError('The provider request limit must stay within the authorized upper bound of 80')
    args.output.mkdir(parents=True, exist_ok=True)
    marker = args.output/'proxy-created.txt'
    if marker.exists():
        if not args.recover_unspent_startup or any((args.output/name).exists() for name in
                ('model-starts.jsonl', 'model-calls.jsonl', 'seven-turn-report.json')):
            raise ValueError('Never reset an attempted model batch')
    else:
        marker.open('x').close()
    provider_key = os.environ.pop('COURSE_REPLAY_PROVIDER_KEY')
    model_key = os.environ.pop('COURSE_REPLAY_MODEL_KEY')
    secrets = [provider_key, model_key]
    budget = Budget(args.limit)
    upstream_client = urllib.request.build_opener(NoRedirect)
    lock = threading.RLock()
    state = {'case': None, 'models': 0, 'pending': 0, 'filteredCandidates': 0, 'scopeBlocks': 0,
             'budgetBlocks': 0, 'credentialLeaks': 0, 'spans': 0, 'limit': args.limit,
             'destination': DESTINATION, 'approvedCourseIds': sorted(APPROVED)}
    repo = pathlib.Path(__file__).resolve().parents[2]
    sys.path.insert(0, str(repo/'target/mcp-evaluation/python'))
    from google.protobuf.json_format import MessageToDict
    from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

    def append(name, value):
        serialized = json.dumps(value, ensure_ascii=False)
        with lock:
            if any(secret in serialized for secret in secrets):
                state['credentialLeaks'] += 1
                for secret in secrets:
                    serialized = serialized.replace(secret, '[REDACTED]')
            with (args.output/name).open('a', encoding='utf-8') as output:
                output.write(serialized+'\n')

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *values):
            pass

        def reply(self, status, value=None, content_type='application/json'):
            data = b'' if value is None else json.dumps(value, ensure_ascii=False).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True

        def body(self):
            if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                pieces = []
                while True:
                    count = int(self.rfile.readline().split(b';', 1)[0].strip(), 16)
                    if not count:
                        while self.rfile.readline() not in (b'\r\n', b'\n', b''):
                            pass
                        return b''.join(pieces)
                    pieces.append(self.rfile.read(count))
                    self.rfile.read(2)
            return self.rfile.read(int(self.headers.get('Content-Length', '0')))

        def do_GET(self):
            if self.path == '/status':
                with lock:
                    self.reply(200, dict(state))
            else:
                self.business(b'')

        def do_POST(self):
            if self.headers.get('Origin'):
                self.reply(403)
                return
            body = self.body()
            if self.path == '/control':
                value = json.loads(body)
                if not re.fullmatch(r'turn-0[1-7]', value.get('case', '')):
                    self.reply(400)
                    return
                with lock:
                    state['case'] = value['case']
                self.reply(200, {})
            elif self.path == '/chat/completions':
                self.model(body)
            elif self.path == '/v1/traces':
                if self.headers.get('Content-Encoding') == 'gzip':
                    body = gzip.decompress(body)
                export = ExportTraceServiceRequest.FromString(body)
                for resource in export.resource_spans:
                    for scope in resource.scope_spans:
                        for span in scope.spans:
                            value = MessageToDict(span, preserving_proto_field_name=True)
                            value['resource'] = MessageToDict(resource.resource, preserving_proto_field_name=True)
                            append('spans.jsonl', value)
                            with lock:
                                state['spans'] += 1
                self.reply(200, content_type='application/x-protobuf')
            else:
                self.business(body)

        def business(self, body):
            try:
                route = self.path
                search = route.startswith('/search-api/')
                if search:
                    route = route.removeprefix('/search-api')
                    if self.command != 'GET' or urllib.parse.urlsplit(route).path not in ('/courses/name', '/courses/popular'):
                        raise ValueError('Unapproved search endpoint')
                    port = args.search_port
                elif route.startswith('/course-api/'):
                    route = route.removeprefix('/course-api')
                    approved_course_request(self.command, route, body)
                    port = args.course_port
                else:
                    self.reply(404)
                    return
                request = urllib.request.Request(f'http://127.0.0.1:{port}'+route, method=self.command,
                    data=body if self.command == 'POST' else None,
                    headers={'x-request-from': 'feign', 'Content-Type': 'application/json'})
                with upstream_client.open(request, timeout=15) as upstream:
                    value = json.load(upstream)
                removed = 0
                if search:
                    value, removed = restrict_candidates(value)
                elif outside_ids(value):
                    raise ValueError('Unapproved course response')
                with lock:
                    state['filteredCandidates'] += removed
                    case = state['case']
                append('business-reads.jsonl', {'case': case, 'method': self.command, 'path': route,
                                              'filtered': removed, 'response': value})
                self.reply(200, value)
            except ValueError:
                with lock:
                    state['scopeBlocks'] += 1
                self.reply(403, {'error': 'replay data scope blocked'})
            except Exception:
                self.reply(502, {'error': 'replay business read failed'})

        def model(self, body):
            if self.headers.get('Authorization') != 'Bearer '+model_key:
                self.reply(401)
                return
            try:
                payload = json.loads(body)
                check_model(payload)
                if any(secret.encode() in body for secret in secrets):
                    raise ValueError('Credential in payload')
            except (ValueError, TypeError, KeyError):
                with lock:
                    state['scopeBlocks'] += 1
                self.reply(403, {'error': {'message': 'replay model scope blocked'}})
                return
            index = budget.acquire()
            if index is None:
                with lock:
                    state['budgetBlocks'] += 1
                self.reply(429, {'error': {'message': 'replay request budget exhausted'}})
                return
            with lock:
                state['models'] = index
                state['pending'] += 1
                record = {'index': index, 'case': state['case'], 'started': time.time(), 'request': payload}
            append('model-starts.jsonl', {'index': index, 'case': record['case'], 'started': record['started']})
            chunks, status = [], None
            try:
                request = urllib.request.Request(DESTINATION, data=body,
                    headers={'Content-Type': 'application/json', 'Authorization': 'Bearer '+provider_key})
                with upstream_client.open(request, timeout=100) as upstream:
                    status = upstream.status
                    self.send_response(status)
                    self.send_header('Content-Type', upstream.headers.get('Content-Type', 'application/json'))
                    self.send_header('Connection', 'close')
                    self.end_headers()
                    if payload.get('stream'):
                        for line in upstream:
                            if line.startswith(b'data:'):
                                value = line[5:].strip()
                                if value and value != b'[DONE]':
                                    chunks.append(json.loads(value))
                            self.wfile.write(line)
                            self.wfile.flush()
                    else:
                        data = upstream.read()
                        chunks.append(json.loads(data))
                        self.wfile.write(data)
            except urllib.error.HTTPError as error:
                status = error.code
                self.reply(status, {'error': {'message': 'replay provider failed'}})
            except Exception as error:
                record['errorType'] = type(error).__name__
                if status is None:
                    self.reply(502, {'error': {'message': 'replay provider unavailable'}})
            finally:
                record.update(status=status, finished=time.time(), responses=chunks)
                append('model-calls.jsonl', record)
                with lock:
                    state['pending'] -= 1
                self.close_connection = True

    print(json.dumps({'port': args.port, 'limit': args.limit, 'destination': DESTINATION}), flush=True)
    ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()


if __name__ == '__main__':
    main()
