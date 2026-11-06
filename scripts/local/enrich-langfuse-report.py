"""Read local Langfuse metadata through WSL Docker; attach verified trace identity and server timings."""
import argparse
import json
import pathlib
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=pathlib.Path)
    parser.add_argument('--verify-scores', action='store_true')
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding='utf-8'))
    ids = [turn['traceId'] for turn in report['turns']]
    if not all(re.fullmatch('[0-9a-f]{32}', trace) for trace in ids):
        raise ValueError('Expected hexadecimal trace IDs')
    p = subprocess.run(['wsl', '-e', 'docker', 'ps', '--filter', 'label=com.docker.compose.project=langfuse',
                        '--filter', 'label=com.docker.compose.service=clickhouse', '--format', '{{.Names}}'],
                       capture_output=True, text=True, timeout=15)
    names = p.stdout.splitlines()
    if p.returncode or len(names) != 1:
        raise RuntimeError('Expected one local Langfuse ClickHouse container')

    def query(sql):
        p = subprocess.run(['wsl', '-e', 'docker', 'exec', names[0], 'sh', '-c',
                            'exec clickhouse-client --user "${CLICKHOUSE_USER:-clickhouse}" --password "${CLICKHOUSE_PASSWORD:-}" --query "$1"',
                            'sh', sql + ' FORMAT JSONEachRow'], capture_output=True, text=True, timeout=20)
        if p.returncode:
            raise RuntimeError('Read-only trace query failed')
        return [json.loads(line) for line in p.stdout.splitlines() if line.strip()]

    selected = ','.join("'" + trace + "'" for trace in ids)
    sql = """SELECT trace_id, span_id, parent_span_id, name, type, session_id, level,
        provided_model_name AS model, end_time IS NOT NULL AS ended,
        dateDiff('millisecond',start_time,end_time) AS duration_ms,
        mapFromArrays(arrayFilter(x -> startsWith(x,'attributes.chat.'), metadata_names),
          arrayMap(i -> metadata_values[i],arrayFilter(i -> startsWith(metadata_names[i],'attributes.chat.'),arrayEnumerate(metadata_names)))) AS chat_attributes
        FROM events_full FINAL WHERE is_deleted=0 AND trace_id IN (""" + selected + ')'
    rows = []
    for _ in range(10):
        rows = query(sql)
        if all(any(row['trace_id'] == trace and row['name'] == 'chat.request' and row['ended'] for row in rows) for trace in ids):
            break
        time.sleep(2)
    for turn in report['turns']:
        spans = [row for row in rows if row['trace_id'] == turn['traceId']]
        roots = [row for row in spans if row['name'] == 'chat.request']
        if len(roots) != 1 or roots[0]['session_id'] != turn['conversationId'] or not roots[0]['ended']:
            raise RuntimeError('Stored trace/session mapping or completion could not be verified')
        turn['traceVerified'] = True
        turn['serverTimings'] = {key.removeprefix('attributes.'): int(value) for key, value in roots[0]['chat_attributes'].items() if key.endswith('.ms')}
        turn['serverFinalStatus'] = roots[0]['chat_attributes'].get('attributes.chat.final.status')
        turn['modelCalls'] = sum(row['type'] == 'GENERATION' and row['model'] == 'deepseek-flash' for row in spans)
        turn['traceSpans'] = spans
    report['healthTraceInventory'] = query("SELECT count() AS count, toTimeZone(max(start_time),'Asia/Shanghai') AS latest FROM events_full FINAL WHERE is_deleted=0 AND name='http get /actuator/health'")[0]
    if args.verify_scores:
        receipt = json.loads(args.report.with_name(args.report.stem + '-scores.json').read_text(encoding='utf-8'))
        expected = {score['id']: score for score in receipt['submittedScores']}
        if not all(re.fullmatch('[0-9a-f-]{36}', key) for key in expected):
            raise ValueError('Unexpected score identity')
        score_ids = ','.join("'" + key + "'" for key in expected)
        stored = []
        for _ in range(10):
            stored = query('SELECT id,trace_id,name,data_type,value,string_value,source FROM scores FINAL WHERE is_deleted=0 AND id IN (' + score_ids + ')')
            if {score['id'] for score in stored} == set(expected):
                break
            time.sleep(2)
        if {score['id'] for score in stored} != set(expected):
            raise RuntimeError('Submitted scores have not all reached Langfuse storage')
        for score in stored:
            original = expected[score['id']]
            value = score['string_value'] if score['data_type'] == 'CATEGORICAL' else score['value']
            if score['trace_id'] != original['traceId'] or score['name'] != original['name'] or value != original['value'] or score['source'] != 'API':
                raise RuntimeError('Stored score does not match the submitted result')
        report['scoreVerification'] = {'expected': len(expected), 'stored': len(stored), 'passed': True}
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'label': report['label'], 'verified': len(report['turns']), 'scores': report.get('scoreVerification'), 'healthTraces': report['healthTraceInventory'], 'turns': [
        {k: turn[k] for k in ('caseId', 'modelCalls', 'serverTimings', 'serverFinalStatus')} for turn in report['turns']]}))


if __name__ == '__main__':
    main()
