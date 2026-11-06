"""Import only the seven captured acceptance traces into the configured loopback Langfuse."""
import argparse
import base64
import json
import os
import pathlib
import re
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
RUN = ROOT/'target/course-workflow/approved-seven-20261027'


def main():
    global RUN
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publish', action='store_true', required=True)
    parser.add_argument('--run', type=pathlib.Path, default=RUN)
    args = parser.parse_args()
    RUN = args.run.resolve()
    if not RUN.is_relative_to((ROOT/'target/course-workflow').resolve()):
        raise ValueError('Batch path is outside the isolated workspace')
    receipt = RUN/'langfuse-trace-import.json'
    if receipt.exists():
        raise ValueError('Import receipt exists; verify storage rather than importing again')
    settings = {}
    config = pathlib.Path(os.environ.get('TJ_AI_LANGFUSE_CONFIG', pathlib.Path.home()/'.tianji/aigc-langfuse.properties'))
    for line in config.read_text(encoding='utf-8').splitlines():
        if line.startswith('tj.ai.knowledge.observation.langfuse.') and '=' in line:
            key, value = line.split('=', 1)
            match = re.fullmatch(r'\$\{([^:]+):(.*)\}', value)
            settings[key.rsplit('.', 1)[-1]] = os.environ.get(match[1], match[2]) if match else value
    endpoint = urllib.parse.urlsplit(settings['endpoint'])
    if endpoint.hostname not in ('127.0.0.1', 'localhost', '::1') or endpoint.scheme not in ('http', 'https'):
        raise ValueError('Only the configured loopback Langfuse is approved for this import')
    authorization = 'Basic '+base64.b64encode((settings['public-key']+':'+settings['secret-key']).encode()).decode()
    observations = json.loads((RUN/'seven-observations.json').read_text(encoding='utf-8'))
    turns = observations['turns']
    spans = [json.loads(s) for s in (RUN/'spans.jsonl').read_text(encoding='utf-8').splitlines() if s.strip()]
    dependency_dir = ROOT/'target/course-workflow/telemetry-python'
    sys.path.insert(0, str(dependency_dir if dependency_dir.exists() else ROOT/'target/mcp-evaluation/python'))
    from google.protobuf.json_format import ParseDict
    from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest
    selected = []
    for turn in turns:
        trace_spans = [span for span in spans if base64.b64decode(span['trace_id']).hex() == turn['traceId']]
        roots = [span for span in trace_spans if span['name'] == 'chat.request']
        if len(roots) != 1 or not roots[0].get('end_time_unix_nano') or not any(
                attr['key'] == 'langfuse.session.id' and attr['value'].get('string_value') == turn['conversationId']
                for attr in roots[0].get('attributes', [])):
            raise ValueError('Captured root/session completion mismatch')
        selected.extend(trace_spans)
    if len(turns) != 7:
        raise ValueError('This importer is restricted to the approved seven traces')
    resources = []
    for original in selected:
        span = dict(original)
        resource = span.pop('resource', {})
        resources.append({'resource': resource, 'scope_spans': [
            {'scope': {'name': 'tianji-course-seven-acceptance'}, 'spans': [span]}]})
    body = ParseDict({'resource_spans': resources}, ExportTraceServiceRequest()).SerializeToString()
    request = urllib.request.Request(settings['endpoint'], data=body, headers={
        'Content-Type': 'application/x-protobuf', 'Authorization': authorization, 'x-langfuse-ingestion-version': '4'})
    with urllib.request.urlopen(request, timeout=20) as response:
        result = {'acceptedHttpStatus': response.status, 'tracesSent': 7, 'spansSent': len(selected),
                  'destinationHost': endpoint.hostname, 'traceIds': [turn['traceId'] for turn in turns],
                  'storageVerified': False, 'newModelCalls': 0}
    receipt.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
