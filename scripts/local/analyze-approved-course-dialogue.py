"""Score the approved recorded batch from actual model/tool spans; never makes model calls."""
import base64
import argparse
import datetime
import hashlib
import importlib.util
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
RUN = ROOT/'target/course-workflow/approved-seven-20261027'
spec = importlib.util.spec_from_file_location('workflow', pathlib.Path(__file__).with_name('evaluate-course-workflow.py'))
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)


def main():
    global RUN
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=pathlib.Path, default=RUN)
    parser.add_argument('--reviews', type=pathlib.Path)
    args = parser.parse_args()
    original_run = RUN.resolve()
    RUN = args.run.resolve()
    if not RUN.is_relative_to((ROOT/'target/course-workflow').resolve()):
        raise ValueError('Batch path is outside the isolated workspace')
    if RUN != original_run and not args.reviews:
        raise ValueError('A new batch requires new evidence-based reviews; never reuse the old verdicts')
    supplied_reviews = workflow.read(args.reviews) if args.reviews else None
    report = workflow.read(RUN/'seven-turn-report.json')
    frozen = workflow.read(RUN/'frozen-metadata.json')
    models = [json.loads(s) for s in (RUN/'model-calls.jsonl').read_text(encoding='utf-8').splitlines() if s.strip()]
    spans = [json.loads(s) for s in (RUN/'spans.jsonl').read_text(encoding='utf-8').splitlines() if s.strip()]
    cases, turns = [], []
    evidence_names = ['seven-turn-report.json', 'frozen-metadata.json', 'model-calls.jsonl', 'spans.jsonl']
    content_notes = {
        1: ('fail', '正文正确说明无可靠热度依据，但第一张可见卡片的reason主动附带“具体前端技术点目录本轮未展开，可进详情页核对”，用户未问目录边界。'),
        6: ('fail', '第3轮已问“你目前学过哪些编程知识，能独立做点什么”，第5轮再次问编程基础，第6轮又问近义问题。未达到原七轮标准中第3/6轮不重复询问的要求。')}
    for number, turn in enumerate(report['turns'], 1):
        scenario_met = number not in (2, 4) or len(turn['priorCards']) >= 2
        captured = sorted([m for m in models if m['case'] == turn['case']], key=lambda m: m['index'])
        route_calls = [m for m in captured if m['request'].get('max_tokens') == 256]
        if len(route_calls) != 1 and not (not route_calls and not scenario_met):
            raise ValueError('Expected one recorded router call per approved turn')
        text = ''.join(c.get('delta', {}).get('content') or '' for m in route_calls
                       for response in m['responses'] for c in response.get('choices', []))
        decision = json.loads(text) if route_calls else {}
        tool_spans = []
        for span in spans:
            if base64.b64decode(span['trace_id']).hex() != turn['traceId']:
                continue
            attrs = {a['key']: a['value'].get('string_value') for a in span.get('attributes', [])}
            if attrs.get('spring.ai.tool.definition.name'):
                tool_spans.append((span, attrs))
        tools = [{'name': attrs['spring.ai.tool.definition.name'],
                  'arguments': json.loads(attrs['langfuse.observation.input']),
                  'result': json.loads(attrs['langfuse.observation.output']),
                  'spanId': base64.b64decode(span['span_id']).hex()}
                 for span, attrs in sorted(tool_spans, key=lambda item: int(item[0]['start_time_unix_nano']))]
        proposed = [tool['function'] for m in captured for response in m['responses']
                    for choice in response.get('choices', []) for tool in choice.get('message', {}).get('tool_calls', [])]
        expected = [(tool['name'], json.loads(tool['arguments'])) for tool in proposed]
        actual = [(tool['name'], tool['arguments']) for tool in tools]
        complete = expected == actual and all(span.get('end_time_unix_nano') for span, _ in tool_spans)
        required, forbidden = [], []
        if number == 1:
            required = [{'anyOf': ['browseCourses']}, {'anyOf': ['recommendCourses']}]
        elif number == 2:
            required = [{'anyOf': ['queryCourseById'], 'arguments': {'courseId': identity}}
                        for identity in sorted(report['courseBefore'])]
            forbidden = ['recommendCourses']
        elif number in (3, 6):
            forbidden = sorted(workflow.COURSE_TOOLS)
        elif number == 4:
            prior = turn['priorCards']
            if len(prior) >= 2:
                required = [{'anyOf': ['queryCourseById'], 'arguments': {'courseId': prior[1]['courseId']}}]
            forbidden = ['recommendCourses']
        elif number == 7:
            required = [{'anyOf': ['searchCourses', 'searchCoursesByGoal', 'browseCourses']},
                        {'anyOf': ['recommendCourses']}]
        # Turn 5 may first ask for missing personal context; tool absence is not invented as a retrieval failure.
        case = {'id': turn['case'], 'split': 'holdout', 'question': turn['question'],
                'expectedAgents': [frozen['expectedAgents'][number-1]],
                'tools': {'required': required, 'forbidden': forbidden},
                'cards': 'none' if number in (3, 6) else 'required' if number in (1, 2, 4, 7) else 'optional',
                'requiredChecks': ['authorized_scope', 'history_matches', 'card_facts_match'],
                'reviewCriteria': '保留已知条件；不编造事实；有依据正常回答；第3/6轮不重复问基础；第4/7轮不附带未问资料边界。'}
        cases.append(case)
        value, note = content_notes.get(number, ('pass', '已逐项对照本批课程快照、当前问题、历史和卡片；未发现关键事实或条件错误。'))
        reviews = {dimension: {'value': value if dimension == 'request_fulfilled' else 'pass',
                               'reviewer': 'codex_source_review', 'comment': note if dimension == 'request_fulfilled'
                               else '对照本批新读取的两门课程原始适用人群、目录及卡片事实；不视为独立人工复核。'}
                   for dimension in workflow.REVIEW_DIMENSIONS}
        if supplied_reviews is not None:
            reviews = supplied_reviews[turn['case']]
            if set(reviews) != set(workflow.REVIEW_DIMENSIONS) or any(
                    item.get('value') not in ('pass', 'fail', 'not_applicable') or not item.get('comment')
                    or item.get('reviewer') != 'codex_source_review' for item in reviews.values()):
                raise ValueError('Each new turn needs a complete attributed source review')
        turns.append({'caseId': turn['case'], 'status': 'completed', 'traceId': turn['traceId'],
                      'conversationId': turn['conversationId'], 'traceVerified': False,
                      'route': (decision.get('agent') or 'CLARIFY') if route_calls else None,
                      'clarification': decision.get('clarification', ''), 'scenarioPreconditionMet': scenario_met,
                      'toolsComplete': complete, 'tools': tools, 'answer': turn['answer'], 'cards': turn['cards'],
                      'eventTypes': [event['eventType'] for event in turn['events']],
                      'errorCodes': [event['eventData']['code'] for event in turn['events'] if event['eventType'] == 1005],
                      'checks': {'authorized_scope': report['proxyStatus']['scopeBlocks'] == 0,
                                 'history_matches': turn['checks']['history_matches'],
                                 'card_facts_match': turn['checks']['card_facts_match']},
                      'flowValid': all(turn['checks'].values()), 'evidence': evidence_names, 'reviews': reviews})
    dataset = {'datasetVersion': frozen.get('datasetVersion', 'approved-seven-20261027-v1'), 'origin': 'Original seven-turn functional replay; gold routes frozen before execution.',
               'gatePolicy': {'route_accuracy': 1.0, 'tool_contract_pass_rate': 1.0}, 'cases': cases}
    prompt_hashes = {k: v for k, v in frozen['classFiles'].items() if '/prompts/' in k}
    options = {workflow.digest({k: v for k, v in m['request'].items() if k not in ('messages', 'tools')}):
               {k: v for k, v in m['request'].items() if k not in ('messages', 'tools')} for m in models}
    metadata = {'datasetVersion': dataset['datasetVersion'], 'datasetHash': workflow.digest(dataset),
                'sourceKind': 'live-real', 'dataHash': workflow.digest(report['courseBefore']),
                'promptHash': workflow.digest(prompt_hashes), 'model': 'deepseek-flash',
                'modelOptions': [options[k] for k in sorted(options)],
                'config': {arg[2:].split('=', 1)[0]: arg.split('=', 1)[1]
                           for arg in frozen['effectiveOverrides']['arguments'] if arg.startswith('--') and '=' in arg},
                'configComplete': False, 'codeVersion': frozen['head']+'+classes:'+workflow.digest(frozen['classFiles']),
                'limitations': 'Runtime classes, CLI overrides, raw model requests and prompt resources frozen. A new batch may also include a pre-call relevant effective-config snapshot; full application/environment equivalence with the old batch is not established, so baseline comparison remains blocked.'}
    normalized = {'metadata': metadata, 'datasetVersion': dataset['datasetVersion'], 'label': RUN.name,
                  'startedUtc': datetime.datetime.fromtimestamp(report['started'], datetime.timezone.utc).isoformat(),
                  'independentHumanReview': 'pending', 'turns': turns,
                  'evidenceFiles': {name: hashlib.sha256((RUN/name).read_bytes()).hexdigest() for name in evidence_names}}
    result = workflow.evaluate(dataset, normalized)
    for name, value in [('seven-cases.json', dataset), ('seven-observations.json', normalized), ('seven-evaluation.json', result)]:
        path = RUN/name
        if path.exists():
            raise ValueError('Preserve prior analysis outputs')
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'route': result['metrics']['route_accuracy'], 'tools': result['metrics']['tool_contract_pass_rate'],
                      'contentReview': result['contentReview'], 'acceptancePassed': result['acceptancePassed'],
                      'toolExecutions': sum(len(turn['tools']) for turn in turns)}))


if __name__ == '__main__':
    main()
