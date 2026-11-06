"""Boundary checks for the acceptance harness; no network or model requests."""
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('live_support', pathlib.Path(__file__).with_name('live-support.py'))
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)


class LiveScopeTest(unittest.TestCase):
    def test_keyword_ids_preserve_large_ids_and_result_order(self):
        approved = [int(value) for value in sorted(support.APPROVED_COURSE_IDS)]
        value, removed = support.restrict_search_results([approved[1], 910000000000010007, approved[0]])
        self.assertEqual([approved[1], approved[0]], value)
        self.assertEqual(1, removed)

    def test_browse_envelope_keeps_facts_and_removes_unapproved_candidates(self):
        approved = {'id': '1589888774267072513', 'price': None, 'sold': 0}
        value, removed = support.restrict_search_results({'code': 200, 'data': [approved, {'id': 910000000000010007}], 'msg': 'ok'})
        self.assertEqual({'code': 200, 'data': [approved], 'msg': 'ok'}, value)
        self.assertEqual(1, removed)

    def test_export_guard_checks_nested_tool_results(self):
        payload = {'messages': [{'role': 'tool', 'content': '[{"course":{"courseId":"910000000000010007"},"info":{"id":1589888774267072513}}]'}]}
        self.assertEqual({'910000000000010007'}, support.unapproved_course_ids(payload))

    def test_approved_and_empty_results_are_exportable(self):
        for content in ('null', '[]', '{"id":1589888774267072513,"price":null}'):
            self.assertEqual(set(), support.unapproved_course_ids({'messages': [{'role': 'tool', 'content': content}]}))

    def test_unexpected_search_and_tool_formats_fail_closed(self):
        with self.assertRaises(ValueError):
            support.restrict_search_results({'code': 500, 'message': 'failed'})
        with self.assertRaises(ValueError):
            support.unapproved_course_ids({'messages': [{'role': 'tool', 'content': 'unexpected plain text'}]})


if __name__ == '__main__':
    unittest.main()
