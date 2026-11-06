import importlib.util
import pathlib
import unittest


def module(name):
    path = pathlib.Path(__file__).with_name(name + '.py')
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


flow = module('verify-chat-performance')
publisher = module('publish-langfuse-scores')


class EvaluationTest(unittest.TestCase):
    def test_card_price_uses_cents_field_and_free_flag(self):
        card = {'name': 'course', 'priceInCents': 14900, 'validDuration': 9999}
        facts = {'name': 'course', 'price': 14900, 'validDuration': 9999}
        self.assertTrue(flow.card_matches_facts(card, facts))
        facts['free'] = True
        self.assertFalse(flow.card_matches_facts(card, facts))
        card['priceInCents'] = 0
        self.assertTrue(flow.card_matches_facts(card, facts))

    def report(self):
        return {'label': 'baseline', 'datasetVersion': 'test-v1', 'startedUtc': '2026-10-26T00:00:00+00:00',
                'turns': [{'caseId': 'test', 'traceId': 'a' * 32, 'traceVerified': True, 'flowValid': True, 'checks': {}}]}

    def test_unreviewed_quality_is_never_automatically_passed(self):
        self.assertEqual([s['name'] for s in publisher.build_scores(self.report())], ['tj_flow_valid_v1'])

    def test_score_ids_are_stable_and_review_provenance_is_preserved(self):
        report = self.report()
        report['turns'][0]['reviews'] = {'fact_correctness': {'value': 'fail', 'reviewer': 'codex_source_review', 'comment': 'Contradicts the stated rule.'}}
        first = publisher.build_scores(report)
        self.assertEqual(first, publisher.build_scores(report))
        self.assertEqual(first[1]['value'], 'fail')
        self.assertEqual(first[1]['metadata']['independentHumanReview'], 'pending')
        self.assertEqual(first[1]['source'], 'API')

    def test_unknown_trace_mapping_blocks_publication(self):
        report = self.report()
        report['turns'][0]['traceVerified'] = False
        with self.assertRaises(ValueError):
            publisher.build_scores(report)

    def test_cancelled_run_does_not_invent_answer_completion_score(self):
        report = self.report()
        report['turns'][0]['serverTimings'] = {'chat.first_token.ms': 100, 'chat.total.ms': 300}
        self.assertNotIn('tj_answer_complete_ms_v1', [s['name'] for s in publisher.build_scores(report)])

    def test_tool_marker_leaks_and_error_events_fail_flow_check(self):
        turn = {'eventTypes': [1001, 1002], 'sse': True, 'answer': '回答', 'historyMatches': True}
        self.assertTrue(all(flow.flow_checks(turn).values()))
        turn['answer'] = '<｜DSML｜calls> raw tool'
        self.assertFalse(flow.flow_checks(turn)['no_tool_protocol_leak'])
        turn['eventTypes'] = [1005, 1002]
        self.assertFalse(flow.flow_checks(turn)['no_error_event'])

    def workflow_report(self):
        report = self.report()
        report['metadata'] = {'sourceKind': 'recorded-real', 'datasetHash': 'fixed'}
        report['turns'][0]['evidence'] = ['raw.json']
        summary = {'schemaVersion': 'course-workflow-v1', 'metadata': report['metadata'].copy(),
                   'rows': [{'caseId': 'test', 'scoreRoute': True, 'routeCorrect': False,
                             'toolContractPassed': True, 'failures': []}]}
        return report, summary

    def test_workflow_boolean_scores_include_failed_route(self):
        report, summary = self.workflow_report()
        scores = {score['name']: score['value'] for score in publisher.build_scores(report, summary)}
        self.assertEqual(scores['tj_route_correct_v1'], 0)
        self.assertEqual(scores['tj_tool_contract_v1'], 1)
        self.assertNotIn('tj_fact_correctness_v1', scores)

    def test_unavailable_tool_execution_is_not_published_as_pass(self):
        report, summary = self.workflow_report()
        summary['rows'][0]['toolContractPassed'] = None
        self.assertNotIn('tj_tool_contract_v1', [s['name'] for s in publisher.build_scores(report, summary)])

    def test_workflow_scores_require_matching_real_provenance(self):
        report, summary = self.workflow_report()
        report['metadata']['datasetHash'] = 'other'
        with self.assertRaises(ValueError):
            publisher.build_scores(report, summary)
        report['metadata'] = {'sourceKind': 'controlled'}
        summary['metadata'] = report['metadata'].copy()
        with self.assertRaises(ValueError):
            publisher.build_scores(report, summary)

    def test_workflow_scores_reject_missing_case_and_string_boolean(self):
        report, summary = self.workflow_report()
        summary['rows'][0]['routeCorrect'] = 'true'
        with self.assertRaises(ValueError):
            publisher.build_scores(report, summary)
        summary['rows'] = []
        with self.assertRaises(ValueError):
            publisher.build_scores(report, summary)


if __name__ == '__main__':
    unittest.main()
