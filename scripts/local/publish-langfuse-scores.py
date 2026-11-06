"""Publish reviewed synthetic-run results to local Langfuse. Does not call any LLM."""
import argparse
import base64
import json
import os
import pathlib
import re
import urllib.parse
import urllib.request
import uuid

DEFINITIONS = {
    'tj_flow_valid_v1': ('BOOLEAN', '程序检查：SSE协议、单次终止、无错误事件、无工具标记泄漏、历史一致及卡片事实一致。通过不代表回答内容正确。'),
    'tj_fact_correctness_v1': ('CATEGORICAL', '对照业务来源复核关键事实。pass=一致；fail=有事实错误；not_applicable=无可检查事实。未复核不打分。'),
    'tj_evidence_support_v1': ('CATEGORICAL', '结论是否受本轮资料支持，是否添加未说明的价格、有效期、课程内容或效果。未复核不打分。'),
    'tj_request_fulfilled_v1': ('CATEGORICAL', '是否回应当前需求、保留已知条件，避免无谓澄清和偏题。未复核不打分。'),
    'tj_first_token_ms_v1': ('NUMERIC', '服务端从聊天订阅到首次非空文本事件产生的毫秒数，不含客户端网络传输。'),
    'tj_answer_complete_ms_v1': ('NUMERIC', '服务端回答管线正常完成耗时，发生在标题生成之前；取消或失败不能补造此值。'),
    'tj_total_ms_v1': ('NUMERIC', '服务端整轮聊天耗时，包含首轮标题及生成占位释放。'),
    'tj_route_correct_v1': ('BOOLEAN', '固定课程案例的路由金标匹配；不代表回答内容正确。只发布有记录、有判定的真实调用结果。'),
    'tj_tool_contract_v1': ('BOOLEAN', '实际工具执行是否满足必要读取、参数、依赖顺序及禁止操作。日志缺失不发布，不要求唯一工具序列。'),
}


def build_scores(report, workflow_summary=None):
    scores = []
    workflow_rows = {}
    if workflow_summary is not None:
        if (workflow_summary.get('schemaVersion') != 'course-workflow-v1'
                or workflow_summary.get('metadata') != report.get('metadata')
                or report.get('metadata', {}).get('sourceKind') not in ('recorded-real', 'live-real')):
            raise ValueError('Workflow scoring requires matching real-observation provenance')
        workflow_rows = {row['caseId']: row for row in workflow_summary['rows']}
        if (len(workflow_rows) != len(workflow_summary['rows'])
                or set(workflow_rows) != {turn['caseId'] for turn in report['turns']}):
            raise ValueError('Workflow score cases do not match report cases')
    for turn in report['turns']:
        if not turn.get('traceVerified'):
            raise ValueError('Verify the stored trace/session mapping before publishing scores')
        values = [('tj_flow_valid_v1', int(turn['flowValid']), 'automated_protocol_check', json.dumps(turn['checks'], ensure_ascii=False))]
        if workflow_rows:
            row = workflow_rows[turn['caseId']]
            fields = [('tj_tool_contract_v1', 'toolContractPassed')]
            if row['scoreRoute']:
                fields.append(('tj_route_correct_v1', 'routeCorrect'))
            for name, field in fields:
                value = row[field]
                if value is None:
                    continue
                if type(value) is not bool:
                    raise ValueError('Workflow scores must be booleans or unavailable')
                values.append((name, int(value), 'automated_workflow_check', json.dumps(
                    {'scorer': workflow_summary['schemaVersion'], 'failures': row['failures'],
                     'evidence': turn['evidence']}, ensure_ascii=False)))
        for metric in ('first_token', 'answer_complete', 'total'):
            value = turn.get('serverTimings', {}).get('chat.' + metric + '.ms')
            if value is not None:
                values.append(('tj_' + metric + '_ms_v1', float(value), 'server_measurement', 'Monotonic server timing; not an answer-quality score.'))
        for dimension in ('fact_correctness', 'evidence_support', 'request_fulfilled'):
            review = turn.get('reviews', {}).get(dimension)
            if not review:
                continue
            if review.get('value') not in ('pass', 'fail', 'not_applicable') or not review.get('reviewer') or not review.get('comment'):
                raise ValueError('Reviewed scores need a valid category, reviewer identity/type and evidence comment')
            values.append(('tj_' + dimension + '_v1', review['value'], review['reviewer'], review['comment']))
        for name, value, reviewer, comment in values:
            scores.append({
                'id': str(uuid.uuid5(uuid.NAMESPACE_URL, 'tianji:' + turn['traceId'] + ':' + name)),
                'traceId': turn['traceId'], 'name': name, 'value': value, 'source': 'API',
                'timestamp': report['startedUtc'], 'dataType': DEFINITIONS[name][0], 'comment': comment,
                'metadata': {'caseId': turn['caseId'], 'experimentLabel': report['label'],
                             'datasetVersion': report['datasetVersion'], 'reviewer': reviewer,
                             'independentHumanReview': report.get('independentHumanReview', 'pending')},
            })
    return scores


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=pathlib.Path)
    parser.add_argument('--workflow-summary', type=pathlib.Path,
                        help='Matching evaluate-course-workflow.py result; unavailable scores are omitted')
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding='utf-8'))
    scores = build_scores(report, json.loads(args.workflow_summary.read_text(encoding='utf-8'))
                          if args.workflow_summary else None)
    config_file = pathlib.Path(os.environ.get('TJ_AI_LANGFUSE_CONFIG', pathlib.Path.home() / '.tianji/aigc-langfuse.properties'))
    settings = {}
    for line in config_file.read_text(encoding='utf-8').splitlines():
        if line.startswith('tj.ai.knowledge.observation.langfuse.') and '=' in line:
            key, value = line.split('=', 1)
            match = re.fullmatch(r'\$\{([^:]+):(.*)\}', value)
            settings[key.rsplit('.', 1)[-1]] = os.environ.get(match[1], match[2]) if match else value
    endpoint = urllib.parse.urlsplit(settings['endpoint'])
    if endpoint.hostname not in ('127.0.0.1', 'localhost', '::1'):
        raise ValueError('This publisher only writes to the authorized loopback Langfuse instance')
    base = urllib.parse.urlunsplit((endpoint.scheme, endpoint.netloc, '', '', ''))
    auth = 'Basic ' + base64.b64encode((settings['public-key'] + ':' + settings['secret-key']).encode()).decode()

    def call(method, path, payload=None):
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
        req = urllib.request.Request(base + path, data=data, method=method,
                                     headers={'Authorization': auth, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read()
            if response.status == 207:
                raise RuntimeError('Langfuse partially rejected the batch; inspect configuration before retrying')
            return json.loads(raw) if raw else None

    configs = {}
    page = 1
    while True:
        result = call('GET', '/api/public/score-configs?page=' + str(page))
        configs.update({item['name']: item for item in result['data'] if not item.get('isArchived')})
        if page >= result['meta']['totalPages']:
            break
        page += 1
    needed = {score['name'] for score in scores}
    for name in sorted(needed):
        kind, description = DEFINITIONS[name]
        if name not in configs:
            value = {'name': name, 'dataType': kind, 'description': description}
            if kind == 'CATEGORICAL':
                value['categories'] = [{'label': 'pass', 'value': 1}, {'label': 'fail', 'value': 0}, {'label': 'not_applicable', 'value': 2}]
            if kind == 'NUMERIC':
                value['minValue'] = 0
            configs[name] = call('POST', '/api/public/score-configs', value)
        if configs[name]['dataType'] != kind:
            raise ValueError('Existing score config has a different data type: ' + name)
    for score in scores:
        score['configId'] = configs[score['name']]['id']
    result = call('POST', '/api/public/scores', scores)
    receipt = args.report.with_name(args.report.stem + '-scores.json')
    receipt.write_text(json.dumps({'configs': {name: item['id'] for name, item in configs.items() if name in DEFINITIONS},
                                  'submittedScores': scores, 'response': result}, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'submitted': len(scores), 'configs': len(needed), 'receipt': str(receipt)}))


if __name__ == '__main__':
    main()
