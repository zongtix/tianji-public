"""Offline failure-oriented tests for the workflow scorer and CLI exit codes."""
import copy
import hashlib
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name('evaluate-course-workflow.py')
spec = importlib.util.spec_from_file_location('workflow', SCRIPT)
workflow = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workflow)
import_spec = importlib.util.spec_from_file_location('recording', SCRIPT.with_name('import-course-workflow-recording.py'))
recording = importlib.util.module_from_spec(import_spec)
import_spec.loader.exec_module(recording)


class WorkflowEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.case = {'id': 'consult', 'split': 'holdout', 'question': '第二门多少钱？',
                     'expectedAgents': ['CONSULT'], 'tools': {'required': [
                         {'anyOf': ['queryCourseById'], 'arguments': {'courseId': '202'}}],
                         'forbidden': ['recommendCourses']}, 'cards': 'required',
                     'requiredChecks': ['history_matches', 'card_facts_match'],
                     'reviewCriteria': '核对第二门课程的最新价格。'}
        self.dataset = {'datasetVersion': 'test-v1', 'gatePolicy': {
            'route_accuracy': 1.0, 'tool_contract_pass_rate': 1.0}, 'cases': [self.case]}
        self.turn = {'caseId': 'consult', 'status': 'completed', 'evidence': ['raw.json'],
                     'route': 'CONSULT', 'toolsComplete': True, 'tools': [
                         {'name': 'queryCourseById', 'arguments': {'courseId': 202}, 'result': {'id': 202}}],
                     'checks': {'history_matches': True, 'card_facts_match': True},
                     'eventTypes': [1001, 1004, 1002], 'errorCodes': [], 'answer': '按当前资料回答。',
                     'cards': [{'courseId': '202'}]}
        self.report = {'metadata': {'datasetVersion': 'test-v1', 'datasetHash': workflow.digest(self.dataset),
                                   'sourceKind': 'controlled', 'dataHash': 'frozen-data', 'promptHash': 'frozen-prompts',
                                   'model': 'controlled-model', 'modelOptions': {'temperature': 0},
                                   'config': {'detailSource': 'local'}, 'configComplete': True, 'codeVersion': 'before'},
                       'turns': [self.turn]}

    def score(self):
        self.report['metadata']['datasetHash'] = workflow.digest(self.dataset)
        return workflow.evaluate(self.dataset, self.report)

    def test_valid_protocol_is_not_quality_acceptance(self):
        result = self.score()
        self.assertTrue(result['programPassed'])
        self.assertEqual(result['contentReview'], 'pending')
        self.assertFalse(result['acceptancePassed'])

    def test_wrong_course_id_fails_tool_contract(self):
        self.turn['tools'][0]['arguments']['courseId'] = 101
        self.assertFalse(self.score()['programPassed'])

    def test_detail_response_must_match_requested_course_even_without_recommendation(self):
        self.turn['tools'][0]['result'] = {'id': 101}
        self.assertFalse(self.score()['programPassed'])

    def test_unknown_tool_cannot_bypass_case_forbidden_list(self):
        self.turn['tools'].append({'name': 'payOrder', 'arguments': {}, 'result': {}})
        self.assertFalse(self.score()['programPassed'])

    def test_consult_cannot_submit_recommendation(self):
        self.turn['tools'].append({'name': 'recommendCourses', 'arguments': {}, 'result': []})
        self.assertFalse(self.score()['programPassed'])

    def test_mismatched_detail_result_does_not_verify_recommendation(self):
        self.case['tools'] = {}
        self.turn['tools'][0]['result'] = {'id': 101}
        self.turn['tools'].append({'name': 'recommendCourses', 'arguments': {}, 'result': [{'courseId': '202'}]})
        self.assertIn('recommendation_without_prior_verified_detail', self.score()['rows'][0]['failures'])

    def test_browse_and_semantic_results_are_equivalent_detail_paths(self):
        self.case['tools'] = {'required': [{'anyOf': ['browseCourses', 'searchCoursesByGoal']},
                                         {'anyOf': ['recommendCourses']}]}
        candidate = {'course': {'courseId': '202'}, 'info': {'id': 202}}
        for name, result in [('browseCourses', {'courses': [candidate]}), ('searchCoursesByGoal', [candidate])]:
            with self.subTest(name=name):
                self.turn['tools'] = [{'name': name, 'arguments': {}, 'result': result},
                                      {'name': 'recommendCourses', 'arguments': {}, 'result': [{'courseId': '202'}]}]
                self.assertTrue(self.score()['programPassed'])

    def test_detail_after_recommendation_is_too_late(self):
        self.case['tools'] = {}
        self.turn['tools'].insert(0, {'name': 'recommendCourses', 'arguments': {}, 'result': [{'courseId': '202'}]})
        self.assertFalse(self.score()['programPassed'])

    def test_missing_tool_logs_are_unavailable_not_perfect(self):
        del self.turn['toolsComplete']
        result = self.score()
        self.assertIsNone(result['metrics']['tool_contract_pass_rate']['value'])
        self.assertFalse(result['programPassed'])

    def test_empty_recommendation_must_be_executed_not_just_no_cards(self):
        self.case['tools'] = {'required': [{'anyOf': ['recommendCourses']}], 'emptySelection': True}
        self.case['cards'] = 'none'
        self.turn['cards'] = []
        self.turn['tools'] = []
        self.assertFalse(self.score()['programPassed'])
        self.turn['tools'] = [{'name': 'recommendCourses', 'arguments': {'selections': []}, 'result': []}]
        self.assertTrue(self.score()['programPassed'])

    def test_missing_checks_and_string_true_do_not_pass(self):
        self.turn['checks']['history_matches'] = 'true'
        self.assertFalse(self.score()['programPassed'])
        self.turn['checks'].pop('history_matches')
        self.assertFalse(self.score()['programPassed'])

    def test_tool_leak_checked_against_captured_answer(self):
        self.turn['checks']['no_tool_protocol_leak'] = True
        self.turn['answer'] = '<｜DSML｜calls><｜DSML｜invoke name="recommendCourses">'
        self.assertFalse(self.score()['programPassed'])

    def test_stop_must_be_last_and_unique(self):
        for events in ([1002, 1001], [1001, 1002, 1002], []):
            self.turn['eventTypes'] = events
            self.assertFalse(self.score()['programPassed'])

    def test_expected_error_is_not_counted_as_unexpected_failure(self):
        self.case.update(expectedError='COURSE_RETRIEVAL_FAILED', allowEmptyAnswer=True)
        self.turn.update(answer='', eventTypes=[1005, 1002], errorCodes=['COURSE_RETRIEVAL_FAILED'])
        self.assertTrue(self.score()['programPassed'])

    def test_missing_duplicate_or_unknown_case_is_rejected(self):
        for turns in ([], [self.turn, self.turn], [dict(self.turn, caseId='other')]):
            self.report['turns'] = turns
            with self.assertRaises(ValueError):
                self.score()

    def test_missing_route_and_empty_clarification_are_rejected(self):
        self.turn.pop('route')
        self.assertFalse(self.score()['programPassed'])
        self.turn['route'] = 'CLARIFY'
        self.assertFalse(self.score()['programPassed'])

    def test_incompatible_metadata_is_rejected(self):
        baseline = self.score()
        for field, value in [('model', 'other'), ('promptHash', 'changed'), ('dataHash', 'changed'),
                             ('sourceKind', 'live-real'), ('modelOptions', {'temperature': 0.7})]:
            current = copy.deepcopy(baseline)
            current['metadata'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                workflow.compare(baseline, current)

    def test_only_explicit_configuration_changes_are_allowed(self):
        baseline = self.score()
        current = copy.deepcopy(baseline)
        current['metadata']['config']['detailSource'] = 'mcp'
        with self.assertRaises(ValueError):
            workflow.compare(baseline, current)
        self.assertTrue(workflow.compare(baseline, current, ['detailSource'])['passed'])

    def test_partial_historical_config_cannot_be_an_application_baseline(self):
        baseline = self.score()
        current = copy.deepcopy(baseline)
        current['metadata']['configComplete'] = False
        with self.assertRaises(ValueError):
            workflow.compare(baseline, current)

    def test_not_applicable_and_controlled_results_are_not_acceptance(self):
        self.report['independentHumanReview'] = 'completed'
        self.turn['reviews'] = {dimension: {'value': 'not_applicable', 'reviewer': 'reviewer', 'comment': 'No applicable facts.'}
                                for dimension in workflow.REVIEW_DIMENSIONS}
        self.assertFalse(self.score()['acceptancePassed'])
        for review in self.turn['reviews'].values():
            review['value'] = 'pass'
        self.assertFalse(self.score()['acceptancePassed'])

    def test_route_regression_is_detected_even_when_wire_protocol_passes(self):
        baseline = self.score()
        self.turn['route'] = 'RECOMMEND'
        result = workflow.compare(baseline, self.score())
        self.assertFalse(result['passed'])
        self.assertIn('route_accuracy:regression', result['failures'])

    def test_cli_exit_codes_and_immutable_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            p = pathlib.Path(directory)
            (p/'raw.json').write_text('{"captured":"test fixture, not a real service run"}', encoding='utf-8')
            self.report['evidenceFiles'] = {'raw.json': hashlib.sha256((p/'raw.json').read_bytes()).hexdigest()}
            def run(label, baseline=None):
                (p/'cases.json').write_text(json.dumps(self.dataset), encoding='utf-8')
                (p/'input.json').write_text(json.dumps(self.report), encoding='utf-8')
                command = [sys.executable, str(SCRIPT), '--dataset', str(p/'cases.json'),
                           '--input', str(p/'input.json'), '--output', str(p/(label+'.json'))]
                if baseline:
                    command += ['--baseline', str(p/(baseline+'.json'))]
                return subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(run('baseline').returncode, 0)
            self.assertNotEqual(run('baseline').returncode, 0)
            self.assertEqual(run('same', 'baseline').returncode, 0)
            self.turn['route'] = 'RECOMMEND'
            self.assertEqual(run('regression', 'baseline').returncode, 1)
            self.turn['route'] = 'CONSULT'
            self.turn['checks']['history_matches'] = False
            self.assertEqual(run('critical', 'baseline').returncode, 1)
            self.turn['checks']['history_matches'] = True
            self.report['turns'] = []
            self.assertEqual(run('missing', 'baseline').returncode, 2)
            self.report['turns'] = [self.turn]
            self.report['metadata']['dataHash'] = 'different'
            self.assertEqual(run('incompatible', 'baseline').returncode, 2)
            self.report['metadata']['dataHash'] = 'frozen-data'
            (p/'raw.json').write_text('changed', encoding='utf-8')
            self.assertEqual(run('stale', 'baseline').returncode, 2)

    def test_repository_dataset_has_twenty_four_documented_cases(self):
        dataset = workflow.read(SCRIPT.parents[2]/'docs/course-workflow-evaluation/cases.json')
        cases = workflow.validate_dataset(dataset)
        self.assertEqual(len(cases), 24)
        self.assertEqual({case['split'] for case in cases}, {'dev', 'holdout'})

    def test_unmet_scenario_precondition_is_unavailable_and_never_passes_the_gate(self):
        self.turn['scenarioPreconditionMet'] = False
        result = self.score()
        self.assertIsNone(result['metrics']['route_accuracy']['value'])
        self.assertIsNone(result['metrics']['tool_contract_pass_rate']['value'])
        self.assertIn('scenario_precondition_not_met', result['rows'][0]['failures'])
        self.assertFalse(result['gatePassed'])
        self.assertFalse(result['acceptancePassed'])

    def test_recorded_tool_proposal_without_result_is_not_execution(self):
        proposed = {'id': 'call-1', 'function': {'name': 'queryCourseById', 'arguments': '{"courseId":202}'}}
        models = [{'request': {'messages': []}, 'responses': [
            {'choices': [{'message': {'tool_calls': [proposed]}}]}]}]
        tools, complete = recording.executions(models)
        self.assertEqual(tools, [])
        self.assertFalse(complete)
        models.append({'request': {'messages': [{'role': 'tool', 'tool_call_id': 'call-1', 'content': '{"id":202}'}]},
                       'responses': []})
        tools, complete = recording.executions(models)
        self.assertTrue(complete)
        self.assertEqual(tools[0]['result']['id'], 202)

    def test_conflicting_recorded_tool_response_is_rejected(self):
        models = [{'request': {'messages': [{'role': 'tool', 'tool_call_id': 'same', 'content': value}]}, 'responses': []}
                  for value in ('[]', 'null')]
        with self.assertRaises(ValueError):
            recording.executions(models)


if __name__ == '__main__':
    unittest.main()
