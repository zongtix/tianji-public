"""Offline course workflow scoring and regression gate. Never calls services or models."""
import argparse
import hashlib
import json
import pathlib
import re

SCORER = 'course-workflow-v1'
AGENTS = {'RECOMMEND', 'CONSULT', 'KNOWLEDGE', 'CLARIFY', 'ROUTING_FAILED', 'CANCELLED'}
COURSE_TOOLS = {'browseCourses', 'searchCourses', 'searchCoursesByGoal', 'queryCourseById', 'recommendCourses'}
REVIEW_DIMENSIONS = ('fact_correctness', 'evidence_support', 'request_fulfilled')


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(',', ':')).encode('utf-8')).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def validate_dataset(dataset):
    cases = dataset['cases']
    ids = [case['id'] for case in cases]
    if not cases or any(not isinstance(value, str) or not value for value in ids) or len(set(ids)) != len(ids):
        raise ValueError('Dataset must have unique nonempty case IDs')
    for case in cases:
        if not case.get('question') or case.get('split') not in ('dev', 'holdout'):
            raise ValueError('Each case needs a question and a dev/holdout split')
        if not case.get('expectedAgents') or not set(case['expectedAgents']) <= AGENTS:
            raise ValueError('Invalid route gold: ' + case['id'])
        if not case.get('reviewCriteria') or not case.get('requiredChecks'):
            raise ValueError('Each case needs content criteria and required protocol checks')
    return cases


def matches(tool, requirement):
    if tool.get('name') not in requirement['anyOf']:
        return False
    if requirement.get('success', True) and (tool.get('error') or tool.get('result') is None):
        return False
    if requirement.get('success', True) and tool['name'] == 'queryCourseById' and not detail_ids(tool):
        return False
    arguments = tool.get('arguments', {})
    return all(key in arguments and str(arguments[key]) == str(value)
               for key, value in requirement.get('arguments', {}).items())


def detail_ids(tool):
    """Only actual successful detail-bearing results count, including batch tool paths."""
    if tool.get('error') or tool.get('result') is None:
        return set()
    name, result = tool['name'], tool['result']
    if name == 'queryCourseById' and isinstance(result, dict) and result.get('id') is not None:
        requested = str(tool.get('arguments', {}).get('courseId'))
        return {requested} if str(result['id']) == requested else set()
    if name == 'browseCourses' and isinstance(result, dict):
        result = result.get('courses', [])
    if name in ('browseCourses', 'searchCoursesByGoal') and isinstance(result, list):
        return {str(item['course']['courseId']) for item in result
                if isinstance(item, dict) and isinstance(item.get('info'), dict)
                and isinstance(item.get('course'), dict) and item['course'].get('courseId') is not None
                and str(item['info'].get('id')) == str(item['course']['courseId'])}
    return set()


def tool_contract(case, turn):
    if turn.get('toolsComplete') is not True or not isinstance(turn.get('tools'), list):
        return None, ['missing_complete_tool_execution_log']
    tools, failures = turn['tools'], []
    if any(not isinstance(t, dict) or not isinstance(t.get('arguments'), dict)
           or not t.get('name') or 'result' not in t for t in tools):
        return None, ['malformed_tool_execution_log']
    contract = case.get('tools', {})
    for tool in tools:
        if tool['name'] not in COURSE_TOOLS:
            failures.append('unknown_course_tool:' + tool['name'])
    for requirement in contract.get('required', []):
        if not any(matches(tool, requirement) for tool in tools):
            failures.append('required_tool:' + '|'.join(requirement['anyOf']))
    for tool in tools:
        if tool['name'] in contract.get('forbidden', []):
            failures.append('forbidden_tool:' + tool['name'])
    verified, candidates = set(), set()
    for tool in tools:
        if not tool.get('error'):
            result = tool['result']
            if tool['name'] == 'searchCourses' and isinstance(result, list):
                candidates.update(str(card['courseId']) for card in result
                                  if isinstance(card, dict) and card.get('courseId') is not None)
            elif tool['name'] in ('browseCourses', 'searchCoursesByGoal'):
                candidates.update(detail_ids(tool))
        if tool['name'] == 'recommendCourses' and not tool.get('error'):
            result = tool['result']
            if not isinstance(result, list):
                failures.append('invalid_recommendation_result')
            else:
                selected = {str(card.get('courseId')) for card in result if isinstance(card, dict)}
                if len(selected) != len(result) or not selected <= verified:
                    failures.append('recommendation_without_prior_verified_detail')
                if not selected <= candidates:
                    failures.append('recommendation_without_prior_candidate')
                if contract.get('emptySelection') and result:
                    failures.append('expected_empty_selection')
        verified.update(detail_ids(tool))
    for first, second in contract.get('before', []):
        later = [i for i, tool in enumerate(tools) if tool['name'] == second]
        if later and not any(tool['name'] == first for tool in tools[:min(later)]):
            failures.append('tool_order:' + first + '->' + second)
    return not failures, failures


def score_case(case, turn):
    failures = []
    if turn.get('status') != 'completed' or not turn.get('evidence'):
        failures.append('missing_completed_observation_or_evidence')
    route = turn.get('route')
    route_correct = None if route not in AGENTS else route in case['expectedAgents']
    if route_correct is None:
        failures.append('missing_route_observation')
    if route == 'CLARIFY' and not turn.get('clarification', '').strip():
        failures.append('missing_clarification')
    tools_correct, tool_failures = tool_contract(case, turn)
    failures.extend(tool_failures)
    checks = turn.get('checks', {})
    for check in case['requiredChecks']:
        if checks.get(check) is not True:
            failures.append('check:' + check)
    # Critical wire checks are computed from captured output, rather than trusting a passed flag.
    events = turn.get('eventTypes')
    if not isinstance(events, list) or events.count(1002) != 1 or events[-1:] != [1002]:
        failures.append('single_terminal')
    answer = turn.get('answer')
    if not isinstance(answer, str):
        failures.append('missing_answer_capture')
    elif re.search(r'DSML|<\|tool|<｜.*?(?:calls|invoke)', answer, re.I):
        failures.append('tool_protocol_leak')
    elif not case.get('allowEmptyAnswer', False) and not answer.strip():
        failures.append('empty_answer')
    errors = turn.get('errorCodes')
    if not isinstance(errors, list) or errors != ([case['expectedError']] if case.get('expectedError') else []):
        failures.append('unexpected_or_missing_error')
    if isinstance(events, list) and events.count(1005) != len(errors or []):
        failures.append('error_event_count')
    cards = turn.get('cards')
    if not isinstance(cards, list) or len(cards) > 3:
        failures.append('missing_or_unbounded_cards')
    elif case.get('cards') == 'none' and cards:
        failures.append('unexpected_cards')
    elif case.get('cards') == 'required' and not cards:
        failures.append('missing_cards')
    reviews = turn.get('reviews', {})
    quality = []
    for dimension in REVIEW_DIMENSIONS:
        review = reviews.get(dimension, {})
        if review.get('reviewer') and review.get('comment') and review.get('value') in ('pass', 'fail', 'not_applicable'):
            quality.append(review['value'])
        else:
            quality.append('pending')
    if 'fail' in quality:
        failures.append('content_review_failed')
    if turn.get('scenarioPreconditionMet', True) is not True:
        # Preserve the real output and its reviews, but do not grade a two-card scenario with one card.
        route_correct = None
        tools_correct = None
        failures.append('scenario_precondition_not_met')
    return {'caseId': case['id'], 'split': case['split'], 'routeCorrect': route_correct,
            'scoreRoute': case.get('scoreRoute', True), 'toolContractPassed': tools_correct,
            'programPassed': not failures, 'failures': failures,
            'contentReview': 'fail' if 'fail' in quality else 'pending' if 'pending' in quality
            else 'not_applicable' if set(quality) == {'not_applicable'} else 'pass'}


def metric(rows, field):
    values = [row[field] for row in rows]
    unavailable = sum(value is None for value in values)
    return {'passed': sum(value is True for value in values), 'total': len(values),
            'unavailable': unavailable,
            'value': sum(value is True for value in values) / len(values) if values and not unavailable else None}


def evaluate(dataset, report, selected=None):
    cases = validate_dataset(dataset)
    if selected:
        if not set(selected) <= {case['id'] for case in cases}:
            raise ValueError('Unknown case selection')
        cases = [case for case in cases if case['id'] in selected]
    metadata = report['metadata']
    for key in ('sourceKind', 'dataHash', 'promptHash', 'model', 'modelOptions', 'config', 'codeVersion'):
        if key not in metadata or metadata[key] is None or metadata[key] == 'unavailable':
            raise ValueError('Missing comparable metadata: ' + key)
    if metadata['sourceKind'] not in ('controlled', 'recorded-real', 'live-real'):
        raise ValueError('Unknown observation source kind')
    if metadata.get('datasetVersion') != dataset['datasetVersion'] or metadata.get('datasetHash') != digest(dataset):
        raise ValueError('Dataset version/hash does not match captured observations')
    turns = report['turns']
    if len({turn['caseId'] for turn in turns}) != len(turns):
        raise ValueError('Duplicate case observations')
    if {turn['caseId'] for turn in turns} != {case['id'] for case in cases}:
        raise ValueError('Missing or unexpected case observations; explicitly select a subset if intended')
    by_id = {turn['caseId']: turn for turn in turns}
    rows = [score_case(case, by_id[case['id']]) for case in cases]
    route_rows = [row for row in rows if row['scoreRoute']]
    program = all(row['programPassed'] for row in rows)
    content = 'fail' if any(row['contentReview'] == 'fail' for row in rows) else (
        'pass' if all(row['contentReview'] == 'pass' for row in rows) else 'pending')
    result = {'schemaVersion': SCORER, 'metadata': metadata, 'caseIds': [c['id'] for c in cases],
            'coverage': {'selected': len(cases), 'dataset': len(dataset['cases']), 'complete': len(cases) == len(dataset['cases'])},
            'metrics': {'route_accuracy': metric(route_rows, 'routeCorrect'),
                        'tool_contract_pass_rate': metric(rows, 'toolContractPassed')},
            'rows': rows, 'programPassed': program, 'contentReview': content,
            'independentHumanReview': report.get('independentHumanReview', 'pending'),
            'acceptancePassed': program and all(row['routeCorrect'] is True or not row['scoreRoute'] for row in rows)
                and content == 'pass' and report.get('independentHumanReview') == 'completed'
                and metadata['sourceKind'] != 'controlled' and len(cases) == len(dataset['cases'])}
    policy = dataset['gatePolicy']
    if set(policy) != set(result['metrics']) or any(type(v) not in (int, float) or not 0 <= v <= 1 for v in policy.values()):
        raise ValueError('Gate policy must define a 0..1 floor for each metric')
    result['gatePolicy'] = policy
    result['splitMetrics'] = {split: {
        'cases': sum(row['split'] == split for row in rows),
        'route_accuracy': metric([row for row in route_rows if row['split'] == split], 'routeCorrect'),
        'tool_contract_pass_rate': metric([row for row in rows if row['split'] == split], 'toolContractPassed')}
        for split in ('dev', 'holdout')}
    result['gatePassed'] = program and all(item['value'] is not None and item['value'] >= policy[name]
                                          for name, item in result['metrics'].items())
    return result


def compare(baseline, current, allowed_config_changes=()):
    if baseline['schemaVersion'] != current['schemaVersion'] or baseline['caseIds'] != current['caseIds']:
        raise ValueError('Baseline scorer/case selection is incompatible')
    before, after = baseline['metadata'], current['metadata']
    if before.get('configComplete') is not True or after.get('configComplete') is not True:
        raise ValueError('Incomplete effective configuration: historical observations are not an application regression baseline')
    for key in ('sourceKind', 'datasetVersion', 'datasetHash', 'dataHash', 'promptHash', 'model', 'modelOptions'):
        if before[key] != after[key]:
            raise ValueError('Baseline metadata differs: ' + key)
    changed = {key for key in set(before['config']) | set(after['config'])
               if before['config'].get(key) != after['config'].get(key)}
    if changed - set(allowed_config_changes):
        raise ValueError('Unexpected effective configuration changes: ' + ', '.join(sorted(changed)))
    failures, differences = [], []
    for name, value in current['metrics'].items():
        old = baseline['metrics'][name]['value']
        new = value['value']
        if old is None or new is None:
            failures.append(name + ':unavailable')
        elif new < old:
            failures.append(name + ':regression')
        differences.append({'metric': name, 'baseline': old, 'current': new})
    if not current['programPassed']:
        failures.append('critical_case_failed')
    return {'passed': not failures, 'failures': failures, 'metrics': differences,
            'caseChanges': [{'caseId': a['caseId'], 'baseline': a, 'current': b}
                            for a, b in zip(baseline['rows'], current['rows']) if a != b]}


def main():
    repo = pathlib.Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=pathlib.Path, default=repo / 'docs/course-workflow-evaluation/cases.json')
    parser.add_argument('--input', required=True, type=pathlib.Path)
    parser.add_argument('--baseline', type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--case-id', action='append', help='Explicit subset; never reported as full coverage')
    parser.add_argument('--allow-config-change', action='append', default=[])
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output exists; use a new filename to preserve prior results')
    try:
        report = read(args.input)
        # Every evidence reference must resolve to a frozen local artifact with the captured hash.
        evidence = report.get('evidenceFiles', {})
        referenced = {ref for turn in report['turns'] for ref in turn.get('evidence', [])}
        if not referenced or not referenced <= evidence.keys():
            raise ValueError('Missing evidence manifest or reference')
        for ref in referenced:
            path = args.input.parent / ref
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != evidence[ref]:
                raise ValueError('Evidence file missing or hash mismatch: ' + ref)
        result = evaluate(read(args.dataset), report, args.case_id)
        if args.baseline:
            result['comparison'] = compare(read(args.baseline), result, args.allow_config_change)
        result['gatePassed'] &= result.get('comparison', {}).get('passed', True)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({key: result[key] for key in ('coverage', 'metrics', 'gatePassed', 'contentReview', 'acceptancePassed')}, ensure_ascii=False))
        return 0 if result['gatePassed'] else 1
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(json.dumps({'gatePassed': False, 'error': str(error)}, ensure_ascii=False))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
