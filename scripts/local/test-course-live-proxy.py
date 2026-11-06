"""Checks for the approved batch boundary; no real network/model requests."""
import concurrent.futures
import importlib.util
import json
import pathlib
import unittest
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

spec = importlib.util.spec_from_file_location('proxy', pathlib.Path(__file__).with_name('course-live-proxy.py'))
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


class CourseLiveProxyTest(unittest.TestCase):
    def test_smaller_cli_budget_is_reported_by_the_running_proxy_before_any_paid_request(self):
        with tempfile.TemporaryDirectory() as directory:
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0))
                port = probe.getsockname()[1]
            process = subprocess.Popen([sys.executable, '-X', 'utf8', str(pathlib.Path(__file__).with_name('course-live-proxy.py')),
                    '--port', str(port), '--course-port', '1', '--search-port', '1', '--output', directory, '--limit', '24'],
                env={**os.environ, 'COURSE_REPLAY_PROVIDER_KEY': 'test-only-provider', 'COURSE_REPLAY_MODEL_KEY': 'test-only-model'},
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            try:
                deadline = time.monotonic()+5
                while True:
                    try:
                        with urllib.request.urlopen(f'http://127.0.0.1:{port}/status', timeout=1) as response:
                            status = json.load(response)
                        break
                    except OSError:
                        if time.monotonic() >= deadline or process.poll() is not None:
                            self.fail('Local proxy did not start')
                        time.sleep(.05)
                self.assertEqual(status['limit'], 24)
                self.assertEqual(status['models'], 0)
                self.assertFalse((pathlib.Path(directory)/'model-starts.jsonl').exists())
            finally:
                process.terminate(); process.communicate(timeout=5)

    def test_concurrent_budget_never_exceeds_eighty(self):
        budget = proxy.Budget(80)
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(lambda _: budget.acquire(), range(140)))
        self.assertEqual(sorted(v for v in results if v is not None), list(range(1, 81)))
        self.assertIsNone(budget.acquire())

    def test_model_and_nested_course_scope_are_checked(self):
        proxy.check_model({'model': 'deepseek-flash', 'messages': [
            {'role': 'tool', 'content': '{"id":1589888774267072513}'}]})
        for payload in ({'model': 'other'}, {'model': 'deepseek-flash', 'messages': [
            {'role': 'tool', 'content': '{"nested":[{"courseId":"910000000000010007"}]}'}]}):
            with self.assertRaises(ValueError):
                proxy.check_model(payload)

    def test_malformed_tool_result_is_blocked(self):
        with self.assertRaises(ValueError):
            proxy.check_model({'model': 'deepseek-flash', 'messages': [{'role': 'tool', 'content': 'not JSON'}]})

    def test_search_filters_before_returning_candidates(self):
        value, count = proxy.restrict_candidates({'code': 200, 'data': [
            {'id': '910000000000010007', 'price': 1}, {'id': 1589888774267072513, 'price': None}]})
        self.assertEqual(count, 1)
        self.assertEqual(value['data'], [{'id': 1589888774267072513, 'price': None}])

    def test_course_reads_only_allow_approved_ids_and_endpoints(self):
        proxy.approved_course_request('GET', '/course/1589888774267072513/baseInfo', b'')
        proxy.approved_course_request('GET', '/courses/simpleInfo/list?ids=1589888774267072513,1589905661084430337', b'')
        proxy.approved_course_request('POST', '/courses/base-info/batch', json.dumps([1589888774267072513]).encode())
        for method, route, body in [('GET', '/course/123/baseInfo', b''),
                                    ('GET', '/courses/simpleInfo/list?ids=1589888774267072513&ids=123', b''),
                                    ('POST', '/courses/base-info/batch', b'[123]'),
                                    ('POST', '/orders', b'{}')]:
            with self.subTest(route=route), self.assertRaises(ValueError):
                proxy.approved_course_request(method, route, body)

    def test_redirects_cannot_change_the_approved_provider_destination(self):
        self.assertIsNone(proxy.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example'))


if __name__ == '__main__':
    unittest.main()
