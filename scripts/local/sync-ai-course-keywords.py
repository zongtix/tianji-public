"""Inspect or create missing keyword documents for the existing AI demo courses.

ES credentials arrive on stdin. Existing documents are never overwritten.
"""
import argparse
import base64
import datetime
import hashlib
import json
import pathlib
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURE = ROOT / 'scripts/local/fixtures/ai-demo-courses.json'
FIELDS = ('id', 'name', 'categoryIdLv1', 'categoryIdLv2', 'categoryIdLv3', 'free',
          'sold', 'price', 'score', 'teacher', 'sections', 'coverUrl', 'publishTime')
NUMBERS = {'id', 'categoryIdLv1', 'categoryIdLv2', 'categoryIdLv3', 'teacher'}


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode('utf-8')).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--course-url', default='http://127.0.0.1:8086')
    args = parser.parse_args()
    import sys
    credentials = json.load(sys.stdin)
    es = 'http://127.0.0.1:19201'
    if urllib.parse.urlsplit(args.course_url).hostname not in ('127.0.0.1', 'localhost', '::1'):
        parser.error('Only a loopback course service is allowed')
    token = base64.b64encode((credentials['username'] + ':' + credentials['password']).encode()).decode()

    def request(method, path, payload=None, business=False):
        headers = {'Content-Type': 'application/json'}
        if not business:
            headers['Authorization'] = 'Basic ' + token
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
        req = urllib.request.Request((args.course_url.rstrip('/') if business else es) + path,
                                     data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            detail = json.loads(error.read()).get('error', {})
            safe = {'status': error.code, 'type': detail.get('type'), 'reason': str(detail.get('reason', ''))[:400]}
            failure = ValueError('HTTP request rejected: ' + canonical(safe))
            failure.safe_details = safe
            raise failure from None
        if business and isinstance(result, dict) and 'code' in result:
            if result['code'] != 200:
                raise ValueError('Course API did not return success')
            return result.get('data')
        return result

    def snapshot():
        result = request('POST', '/course/_search', {'size': 1000, 'query': {'match_all': {}}, 'track_total_hits': True})
        if result['hits']['total']['value'] > 1000:
            raise ValueError('Index is larger than the bounded demo sync supports')
        return {hit['_id']: hit['_source'] for hit in result['hits']['hits']}

    fixture = json.loads(FIXTURE.read_text(encoding='utf-8'))
    if fixture['dataset'] != 'aigc-course-demo-v1':
        raise ValueError('Unexpected demo dataset')
    ids = [item['id'] for item in fixture['courses']]
    if ids != [str(value) for value in range(910000000000010001, 910000000000010013)]:
        raise ValueError('Unexpected demo IDs')
    before = snapshot()
    existing_hashes = {key: digest(value) for key, value in before.items()}
    now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).replace(tzinfo=None)
    planned, skipped = {}, []
    for item in fixture['courses']:
        course_id = item['id']
        values = request('GET', '/courses/simpleInfo/list?' + urllib.parse.urlencode({'ids': course_id}), business=True)
        if len(values) != 1 or str(values[0]['id']) != course_id:
            raise ValueError('Demo course is missing or mismatched: ' + course_id)
        simple = values[0]
        start, end = simple.get('purchaseStartTime'), simple.get('purchaseEndTime')
        if (simple['status'] not in (2, 4) or start and datetime.datetime.fromisoformat(start) > now
                or end and datetime.datetime.fromisoformat(end) <= now):
            if course_id in before:
                raise ValueError('An unavailable demo is already indexed; review before changing it')
            skipped.append(course_id)
            continue
        source = request('GET', '/course/' + course_id + '/searchInfo', business=True)
        if (str(source.get('id')) != course_id or source.get('name') != '【AI演示】' + item['name']
                or source.get('price') != item['price'] or source.get('free') != (item['price'] == 0)):
            raise ValueError('Published demo data differs from the fixture: ' + course_id)
        document = {field: source.get(field) for field in FIELDS}
        if document['publishTime']:
            published = datetime.datetime.fromisoformat(document['publishTime'])
            if published.tzinfo is None:
                published = published.replace(tzinfo=datetime.timezone(datetime.timedelta(hours=8)))
            document['publishTime'] = int(published.timestamp() * 1000)
        for field in NUMBERS:
            if document[field] is not None:
                document[field] = int(document[field])
        document['type'] = source['courseType']
        if course_id in before and canonical(before[course_id]) != canonical(document):
            raise ValueError('Existing demo document differs; refusing to overwrite: ' + course_id)
        planned[course_id] = document

    report_dir = ROOT / 'target/course-guide-daily/keyword-sync'
    report_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    created, rollback_errors = [], []
    report = {'mode': 'apply' if args.apply else 'inspect', 'index': 'course',
              'before_count': len(before), 'eligible_ids': list(planned), 'skipped_ids': skipped,
              'missing_ids': [key for key in planned if key not in before], 'created_ids': [],
              'existing_sha256': digest(before), 'passed': False}
    try:
        if args.apply:
            (report_dir / ('before-' + stamp + '.json')).write_text(
                json.dumps(before, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            for course_id, document in planned.items():
                if course_id in before:
                    continue
                result = request('PUT', '/course/_create/' + course_id + '?refresh=wait_for', document)
                created.append((course_id, result['_seq_no'], result['_primary_term']))
                report['created_ids'].append(course_id)
            after = snapshot()
            if any(key not in after or digest(after[key]) != value for key, value in existing_hashes.items()):
                raise ValueError('A pre-existing document changed during sync')
            if any(canonical(after.get(key)) != canonical(value) for key, value in planned.items()):
                raise ValueError('Created demo verification failed')
            for course_id, document in planned.items():
                result = request('POST', '/course/_search', {'size': 20,
                    'query': {'match_phrase': {'name': document['name']}}, '_source': False})
                if course_id not in {hit['_id'] for hit in result['hits']['hits']}:
                    raise ValueError('Phrase lookup did not find the demo course: ' + course_id)
            report['after_count'] = len(after)
            report['existing_documents_unchanged'] = True
        report['passed'] = True
    except Exception as error:
        report['error'] = getattr(error, 'safe_details', {'type': type(error).__name__})
        for course_id, sequence, term in reversed(created):
            try:
                request('DELETE', '/course/_doc/' + course_id + '?' + urllib.parse.urlencode(
                    {'if_seq_no': sequence, 'if_primary_term': term, 'refresh': 'wait_for'}))
            except Exception:
                rollback_errors.append(course_id)
        report['rollback_errors'] = rollback_errors
        raise
    finally:
        (report_dir / (report['mode'] + '-' + stamp + '.json')).write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({key: report.get(key) for key in ('mode', 'passed', 'before_count', 'after_count',
              'created_ids', 'skipped_ids', 'existing_documents_unchanged', 'rollback_errors')}, ensure_ascii=True))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        import sys
        detail = str(error) if isinstance(error, ValueError) else type(error).__name__
        print('Keyword sync failed: ' + detail + '; credentials suppressed', file=sys.stderr)
        sys.exit(1)
