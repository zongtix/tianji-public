"""Read the three saved MCP fact scenarios into workflow observations; no HTTP or model calls."""
import argparse
import base64
import hashlib
import importlib.util
import json
import pathlib
import re

spec = importlib.util.spec_from_file_location('workflow', pathlib.Path(__file__).with_name('evaluate-course-workflow.py'))
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)


def executions(models):
    """Proposed calls count only when an actual tool response is captured in a later request."""
    calls, results = {}, {}
    for model in models:
        for response in model.get('responses', []):
            for choice in response.get('choices', []):
                for tool in choice.get('message', {}).get('tool_calls', []):
                    if tool['id'] in calls and calls[tool['id']] != tool['function']:
                        raise ValueError('Conflicting recorded tool-call identity')
                    calls.setdefault(tool['id'], tool['function'])
        for message in model['request']['messages']:
            for tool in message.get('tool_calls', []):
                calls.setdefault(tool['id'], tool['function'])
            if message.get('role') == 'tool':
                if message['tool_call_id'] in results and results[message['tool_call_id']] != message['content']:
                    raise ValueError('Conflicting recorded tool response')
                results[message['tool_call_id']] = message['content']
    tools = []
    for identity, function in calls.items():
        if identity not in results:
            continue
        tools.append({'name': function['name'], 'arguments': json.loads(function['arguments']),
                      'result': json.loads(results[identity]), 'callId': identity})
    return tools, set(calls) == set(results)


def routing(models):
    first = models[0]
    if not first['request'].get('stream') or first['request'].get('tools'):
        raise ValueError('Cannot identify the saved routing request')
    text = ''.join(choice.get('delta', {}).get('content') or ''
                   for response in first['responses'] for choice in response.get('choices', []))
    decision = json.loads(text)
    return decision.get('agent') or 'CLARIFY', decision.get('clarification', '')


def convert(directory, source, dataset, destination):
    artifacts = ['acceptance-report.json', 'audit.json', 'model-calls.jsonl', 'spans.jsonl']
    report = workflow.read(directory / artifacts[0])
    audit = workflow.read(directory / artifacts[1])
    models = [json.loads(line) for line in (directory / artifacts[2]).read_text(encoding='utf-8').splitlines() if line.strip()]
    spans = [json.loads(line) for line in (directory / artifacts[3]).read_text(encoding='utf-8').splitlines() if line.strip()]
    if report.get('mode') != 'fixed-fact-functional':
        raise ValueError('Importer supports only recorded fixed-fact acceptance, not arbitrary historical reports')
    cases = {case['replayKey']: case for case in dataset['cases'] if case.get('replayKey')}
    turns, system_messages, options = [], set(), {}
    for turn in report['turns']:
        if turn['source'] != source:
            continue
        case = cases[turn['case_id']]
        if turn['question'] != case['question']:
            raise ValueError('Historical question differs from the fixed case: ' + case['id'])
        captured = sorted([m for m in models if m['case'] == turn['case']
                           and turn['started'] - .25 <= m['started'] <= turn['finished'] + .25], key=lambda m: m['index'])
        if not captured:
            raise ValueError('Missing provider recording')
        for model in captured:
            if model['status'] != 200:
                raise ValueError('Historical provider error requires a fault-specific adapter')
            request = model['request']
            for message in request['messages']:
                if message.get('role') == 'system':
                    system_messages.add(message['content'])
            value = {key: value for key, value in request.items() if key not in ('messages', 'tools')}
            options[workflow.digest(value)] = value
        tools, complete = executions(captured)
        route, clarification = routing(captured)
        recorded_checks = [c for c in report['checks'] if c['name'].startswith(turn['case'] + ':')]
        def passed(suffix):
            values = [check['passed'] for check in recorded_checks if check['name'].endswith(suffix)]
            return bool(values) and all(value is True for value in values)
        roots = []
        for span in spans:
            attributes = {a['key']: a['value'].get('string_value') for a in span.get('attributes', [])}
            if span['name'] == 'chat.request' and attributes.get('langfuse.session.id', '').endswith('_' + turn['sessionId']):
                roots.append((span, attributes['langfuse.session.id']))
        if len(roots) != 1:
            raise ValueError('Expected one captured root span per historical session')
        trace = roots[0][0]['trace_id']
        normalized = {'caseId': case['id'], 'status': 'completed', 'route': route, 'clarification': clarification,
                      'traceId': base64.b64decode(trace).hex(), 'conversationId': roots[0][1], 'traceVerified': False,
                      'tools': tools, 'toolsComplete': complete, 'answer': turn['answer'], 'cards': turn['cards'],
                      'eventTypes': [event['eventType'] for event in turn['events']],
                      'errorCodes': [event['eventData']['code'] for event in turn['events'] if event['eventType'] == 1005],
                      'checks': {'authorized_scope': audit.get('passed') is True and not audit.get('unapproved_course_ids'),
                                 'history_matches': passed('visible history only') and passed('persisted cards match visible cards'),
                                 'card_facts_match': passed('card current price/validity/name')},
                      'evidence': artifacts, 'reviews': {},
                      'recordedContentCheck': next(a['answer_matches_fresh_snapshot_renderer'] for a in audit['turns'] if a['case'] == turn['case'])}
        normalized['flowValid'] = all(normalized['checks'].values()) and turn.get('sse') is True and (
            normalized['eventTypes'].count(1002) == 1 and normalized['eventTypes'][-1:] == [1002]
            and not normalized['errorCodes'] and bool(normalized['answer'].strip())
            and not re.search(r'DSML|<\|tool|<｜.*?(?:calls|invoke)', normalized['answer'], re.I))
        turns.append(normalized)
    if len(turns) != 3:
        raise ValueError('Expected exactly three historical cases per source')
    metadata = {'sourceKind': 'recorded-real', 'datasetVersion': dataset['datasetVersion'],
                'datasetHash': workflow.digest(dataset), 'dataHash': workflow.digest(report['course_snapshot_before']),
                'promptHash': workflow.digest(sorted(system_messages)), 'model': sorted({m['request']['model'] for m in models}),
                'modelOptions': [options[key] for key in sorted(options)],
                'config': {'detailSource': source, 'recordingMode': report['mode']},
                'configComplete': False,
                'codeVersion': 'recorded-batch:' + directory.name,
                'limitations': 'Only captured model options, system prompts and source selection; not a full effective application configuration snapshot. No current-service or independent-human acceptance.'}
    # Keep relative references to original artifacts; neither copy nor rewrite historical evidence.
    import os
    references = {name: pathlib.Path(os.path.relpath(directory/name, destination.parent)).as_posix() for name in artifacts}
    for turn in turns:
        turn['evidence'] = [references[name] for name in artifacts]
    return {'metadata': metadata, 'label': 'workflow-recorded-' + source,
            'datasetVersion': dataset['datasetVersion'], 'startedUtc': report['started'],
            'independentHumanReview': 'pending', 'turns': turns,
            'evidenceFiles': {references[name]: hashlib.sha256((directory/name).read_bytes()).hexdigest() for name in artifacts}}


def main():
    repo = pathlib.Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recording', type=pathlib.Path, required=True)
    parser.add_argument('--source', choices=('local', 'mcp'), required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--dataset', type=pathlib.Path, default=repo/'docs/course-workflow-evaluation/cases.json')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output exists; preserve previous results')
    result = convert(args.recording.resolve(), args.source, workflow.read(args.dataset), args.output.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'imported': len(result['turns']), 'source': args.source, 'newModelCalls': 0}))


if __name__ == '__main__':
    main()
