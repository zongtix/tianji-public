"""Summarize actual captured requests/spans; never estimate usage or infer model quality."""
import json
import pathlib
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / 'target/mcp-evaluation/live'


def lines(name):
    path = DIRECTORY / name
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()] if path.exists() else []


def attributes(span):
    values = {}
    for item in span.get('attributes', []):
        value = item['value']
        values[item['key']] = next(iter(value.values()), None)
    return values


def main():
    spans = lines('spans.jsonl')
    models = lines('model-calls.jsonl')
    remote = lines('mcp-calls.jsonl')
    by_span = {span['span_id']: span for span in spans}
    result = {'reports': {}, 'turns': [], 'checked_at': time.time(),
              'real_langfuse_backend_checked': False, 'otlp_collector': 'loopback acceptance receiver',
              'performance_claim': 'Small observational sample; different tool order and model behavior recorded; no P95/cost/speedup claim'}
    for name in ('core', 'recommend', 'faults', 'down'):
        path = DIRECTORY / (name + '-report.json')
        if not path.exists():
            continue
        report = json.loads(path.read_text(encoding='utf-8'))
        result['reports'][name] = {'automatic_checks_passed': report['automatic_checks_passed'],
                                  'content_review_passed': report['content_review_passed'],
                                  'cleanup_errors': report['cleanup_errors']}
        for turn in report['turns']:
            roots = [span for span in spans if span['name'] == 'chat.request'
                     and turn['sessionId'] in str(attributes(span).get('langfuse.session.id', ''))]
            trace_ids = {span['trace_id'] for span in roots}
            related = [span for span in spans if span.get('trace_id') in trace_ids]
            mcp = [span for span in related if span['name'].startswith('mcp.')]
            def under_chat(span):
                visited = set()
                while span.get('parent_span_id') and span['span_id'] not in visited:
                    visited.add(span['span_id'])
                    parent = by_span.get(span['parent_span_id'])
                    if parent is None:
                        return False
                    if parent['name'] == 'chat.request':
                        return parent['trace_id'] == span['trace_id']
                    span = parent
                return False
            called = [call for call in models if call['case'] == turn['case']
                      and turn['started'] - 0.25 <= call['started'] <= turn['finished'] + 0.25]
            usage = [response['usage'] for call in called for response in call.get('responses', []) if response.get('usage')]
            captured_tools = any('tool_results=' in str(attributes(span).get('langfuse.observation.input', ''))
                                 and 'queryCourseById' in str(attributes(span).get('langfuse.observation.input', '')) for span in related)
            calls = [call for call in remote if call['case'] == turn['case'] and call['method'] == 'tools/call'
                     and turn['started'] - 0.25 <= call['started'] <= turn['finished'] + 10]
            result['turns'].append({'case': turn['case'], 'source': turn['source'], 'trace_count': len(trace_ids),
                'mcp_span_count': len(mcp), 'mcp_parent_chat_verified': bool(mcp) and all(under_chat(span) for span in mcp),
                'mcp_spans_ended': all(int(span.get('end_time_unix_nano', 0)) > int(span.get('start_time_unix_nano', 0)) for span in mcp),
                'mcp_stages': [{'name': span['name'], 'status': attributes(span).get('mcp.status'),
                    'seconds': round((int(span['end_time_unix_nano']) - int(span['start_time_unix_nano'])) / 1e9, 4)} for span in mcp],
                'tool_content_exported': captured_tools, 'provider_requests': len(called),
                'usage_records': len(usage), 'usage_status': 'available' if len(usage) == len(called) and called else 'partial' if usage else 'unavailable',
                'actual_prompt_tokens': sum(item.get('prompt_tokens', 0) for item in usage) if usage else None,
                'actual_completion_tokens': sum(item.get('completion_tokens', 0) for item in usage) if usage else None,
                'request_seconds': turn['finished'] - turn['started'], 'actual_remote_calls': len(calls),
                'late_remote_completion': any(call.get('finished', 0) > turn['finished'] for call in calls)})
    backend = [json.loads(path.read_text(encoding='utf-8')) for path in DIRECTORY.glob('langfuse-*.json')]
    result['langfuse_backend_results'] = backend
    result['real_langfuse_backend_checked'] = any(item.get('passed') for item in backend)
    result['langfuse_verification_mode'] = 'Captured application OTLP replayed to configured backend, then queried via Observations API v2'
    path = DIRECTORY / 'trace-audit.json'
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'turns': len(result['turns']), 'mcp_traces_verified': sum(t['mcp_parent_chat_verified'] for t in result['turns']),
                      'path': str(path)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
