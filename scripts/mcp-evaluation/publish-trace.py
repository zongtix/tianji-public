"""Publish selected synthetic acceptance traces to the already configured Langfuse."""
import argparse
import base64
import datetime
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / 'target/mcp-evaluation/live'
sys.path.insert(0, str(ROOT / 'target/mcp-evaluation/python'))
from google.protobuf.json_format import ParseDict
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publish', action='store_true', required=True)
    parser.add_argument('--report', default='core-report.json')
    parser.add_argument('--case', default='mcp-detail')
    parser.add_argument('--query-only', action='store_true')
    args = parser.parse_args()
    endpoint = os.environ['MCP_EVAL_LANGFUSE_ENDPOINT']
    uri = urllib.parse.urlsplit(endpoint)
    if uri.username or uri.password or uri.query or uri.fragment or not (
            uri.scheme == 'https' or uri.scheme == 'http' and uri.hostname in ('localhost', '127.0.0.1', '::1')):
        raise ValueError('Use the trusted configured Langfuse endpoint')
    credential = base64.b64encode((os.environ['MCP_EVAL_LANGFUSE_PUBLIC'] + ':' + os.environ['MCP_EVAL_LANGFUSE_SECRET']).encode()).decode()
    report = json.loads((DIRECTORY / args.report).read_text(encoding='utf-8'))
    turn = next(turn for turn in report['turns'] if turn['case'] == args.case)
    spans = [json.loads(line) for line in (DIRECTORY / 'spans.jsonl').read_text(encoding='utf-8').splitlines()]
    roots = [span for span in spans if span['name'] == 'chat.request' and any(
        item['key'] == 'langfuse.session.id' and turn['sessionId'] in item['value'].get('string_value', '')
        for item in span.get('attributes', []))]
    if len(roots) != 1:
        raise ValueError('Expected one completed acceptance trace')
    trace_id = roots[0]['trace_id']
    selected = [span for span in spans if span['trace_id'] == trace_id]
    resources = []
    for span in selected:
        span = dict(span)
        resource = span.pop('resource', {})
        resources.append({'resource': resource, 'scope_spans': [{'scope': {'name': 'tianji-mcp-acceptance'}, 'spans': [span]}]})
    payload = ParseDict({'resource_spans': resources}, ExportTraceServiceRequest()).SerializeToString()
    accepted = None
    if not args.query_only:
        request = urllib.request.Request(endpoint, data=payload,
            headers={'Content-Type': 'application/x-protobuf', 'Authorization': 'Basic ' + credential,
                     'x-langfuse-ingestion-version': '4'})
        with urllib.request.urlopen(request, timeout=12) as response:
            accepted = response.status
    hex_id = base64.b64decode(trace_id).hex()
    def timestamp(nanos):
        return datetime.datetime.fromtimestamp(nanos / 1e9, datetime.timezone.utc).isoformat().replace('+00:00', 'Z')
    query = urllib.parse.urlencode({'traceId': hex_id, 'fields': 'core,basic,usage', 'limit': 100,
        'fromStartTime': timestamp(min(int(span['start_time_unix_nano']) for span in selected) - 60_000_000_000),
        'toStartTime': timestamp(max(int(span['end_time_unix_nano']) for span in selected) + 60_000_000_000)})
    url = urllib.parse.urlunsplit((uri.scheme, uri.netloc, '/api/public/v2/observations', query, ''))
    fetched = None
    last_status = None
    for _ in range(15):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'Authorization': 'Basic ' + credential}), timeout=10) as response:
                fetched = json.loads(response.read())
                last_status = response.status
                if any(item.get('name', '').startswith('mcp.') for item in fetched.get('data', [])):
                    break
        except urllib.error.HTTPError as error:
            last_status = error.code
            if error.code != 404:
                break
        time.sleep(2)
    observations = fetched.get('data', []) if fetched else []
    mcp = [observation for observation in observations if observation.get('name', '').startswith('mcp.')]
    result = {'case': args.case, 'trace_id': hex_id, 'ingest_status': accepted, 'query_status': last_status,
              'span_count_sent': len(selected), 'observations_received': len(observations),
              'mcp_observations_received': len(mcp), 'passed': bool(mcp), 'destination_host': uri.hostname}
    (DIRECTORY / ('langfuse-' + args.case + '.json')).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
