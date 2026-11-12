"""Offline checks for scoring errors that could inflate agreement."""
import copy
import unittest

from evaluate import compare


class EvaluationTests(unittest.TestCase):
    def setUp(self):
        self.reference = {'optionKeys': ['cards'], 'wishes': [
            {'wishKey': 'one', 'kind': 'REQUEST', 'verdicts': {'cards': 'COMPATIBLE'},
             'reasonableDisagreement': False, 'reason': 'Wants cards.'},
            {'wishKey': 'two', 'kind': 'REQUEST', 'verdicts': {'cards': 'CONDITIONAL'},
             'reasonableDisagreement': True, 'reason': 'Unknown budget.'},
            {'wishKey': 'three', 'kind': 'NOT_A_REQUEST', 'verdicts': {},
             'reasonableDisagreement': False, 'reason': 'Praise.'},
        ]}
        self.texts = {'one': 'Send  cards please.', 'two': 'Only if cheap.', 'three': 'Lovely.'}
        self.run = {
            'model': 'synthetic', 'completedAt': None, 'inputHash': 'synthetic',
            'wishKeys': {'1': 'one', '2': 'two', '3': 'three'}, 'optionKeys': {'c': 'cards'},
            'validated': {'flags': [], 'output': {
                'optionIds': ['c'],
                'wishes': [
                    {'wishId': '1', 'kind': 'REQUEST', 'needs': [{'quote': 'Send  cards'}]},
                    {'wishId': '2', 'kind': 'REQUEST', 'needs': []},
                    {'wishId': '3', 'kind': 'NOT_A_REQUEST', 'needs': []},
                ],
                'verdicts': [
                    {'wishId': '1', 'optionId': 'c', 'verdict': 'COMPATIBLE', 'reason': 'Cards.'},
                    {'wishId': '2', 'optionId': 'c', 'verdict': 'CONDITIONAL', 'reason': 'Budget.'},
                ],
                'proposal': {'optionId': 'c', 'rationale': [{'wishId': '1', 'quote': 'cards please.'}]},
            }},
        }

    def score(self):
        return compare(self.run, self.reference, self.texts)

    def test_false_positive_and_false_negative_are_not_hidden_by_request_agreement(self):
        verdicts = self.run['validated']['output']['verdicts']
        verdicts[0]['verdict'] = 'INCOMPATIBLE'
        verdicts[1]['verdict'] = 'COMPATIBLE'
        report = self.score()
        self.assertEqual(report['requestAgreement']['value'], 1)
        self.assertEqual(report['proposedOptionMetrics']['compatiblePrecision']['value'], 0)
        self.assertEqual(report['proposedOptionMetrics']['compatibleRecall']['value'], 0)
        self.assertEqual(report['differentWishCount'], 2)

    def test_kind_error_counts_against_recall_and_has_separate_confusion_coverage(self):
        output = self.run['validated']['output']
        output['wishes'][0]['kind'] = 'NOT_A_REQUEST'
        output['verdicts'].pop(0)
        report = self.score()
        self.assertEqual(report['requestAgreement']['numerator'], 2)
        self.assertEqual(report['proposedOptionMetrics']['compatibleRecall']['value'], 0)
        self.assertIsNone(report['proposedOptionMetrics']['compatiblePrecision']['value'])
        self.assertEqual(report['proposedOptionMetrics']['referenceRequestsMissingModelVerdict'], 1)

    def test_non_request_false_positive_reduces_precision(self):
        output = self.run['validated']['output']
        output['wishes'][2]['kind'] = 'REQUEST'
        output['verdicts'].append({'wishId': '3', 'optionId': 'c', 'verdict': 'COMPATIBLE', 'reason': 'Wrong.'})
        self.assertEqual(self.score()['proposedOptionMetrics']['compatiblePrecision']['value'], 0.5)

    def test_strict_quotes_cover_needs_and_rationale_without_normalizing(self):
        output = self.run['validated']['output']
        output['wishes'][0]['needs'][0]['quote'] = 'Send cards'
        output['proposal']['rationale'][0]['quote'] = 'invented'
        self.assertEqual(self.score()['exactQuotes']['value'], 0)
        output['wishes'][0]['needs'][0]['quote'] = ''
        self.assertEqual(self.score()['exactQuotes']['value'], 0)

    def test_incomplete_or_duplicate_rosters_are_rejected(self):
        original = copy.deepcopy(self.run)
        for mutation in ('missing', 'duplicate'):
            with self.subTest(mutation=mutation):
                self.run = copy.deepcopy(original)
                verdicts = self.run['validated']['output']['verdicts']
                if mutation == 'missing':
                    verdicts.pop()
                else:
                    verdicts.append(copy.deepcopy(verdicts[0]))
                with self.assertRaises(ValueError):
                    self.score()

    def test_null_proposal_and_no_quotes_are_unmeasured_not_perfect(self):
        self.run['validated']['output']['proposal'] = None
        self.run['validated']['output']['wishes'][0]['needs'] = []
        report = self.score()
        self.assertIsNone(report['proposedOptionMetrics'])
        self.assertIsNone(report['exactQuotes']['value'])


if __name__ == '__main__':
    unittest.main()
