"""Offline scenario alignment, scoring, provenance and missing-evidence checks."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from evaluate import ROOT, read, sha256
from export_scenario import export_snapshot
from scenario_evaluate import (MANIFEST, agreement, check_mapping, evaluate_artifact,
                               evaluate_scenario, output_labels, reading_labels,
                               reviewed_labels, scenario_inputs, score, seed_id)


class ScenarioTests(unittest.TestCase):
    def setUp(self):
        self.scenario, self.reference = scenario_inputs('illustrator')
        self.path = ROOT / self.scenario['sourceDirectory'] / 'analysis-run.json'
        self.run = read(self.path)
        self.output = self.run['validated']['output']
        self.labels = output_labels(self.output, self.scenario)
        self.wishes = {key: value for value, key in self.scenario['wishKeys'].items()}
        self.options = {key: value for value, key in self.scenario['optionKeys'].items()}

    def write_run(self, directory, run=None, name='run.json'):
        path = Path(directory) / name
        path.write_text(json.dumps(run or self.run))
        return path

    def second_reading(self):
        comparisons = []
        for wish_id, key in self.scenario['wishKeys'].items():
            wish = self.labels[key]
            for option_id, option in self.scenario['optionKeys'].items():
                verdict = wish['verdicts'].get(option)
                comparisons.append({'wishId': wish_id, 'optionId': option_id,
                                    'firstKind': wish['kind'], 'secondKind': wish['kind'],
                                    'firstVerdict': verdict, 'secondVerdict': verdict})
        return {'agreedWishCount': 40, 'comparedWishCount': 40, 'comparisons': comparisons,
                'batchCompositionChanged': True}

    def test_all_scenarios_have_exact_references_counts_and_separate_subsets(self):
        for key, requests in [('illustrator', 35), ('ceramicist', 37), ('textile', 37)]:
            with self.subTest(scenario=key):
                scenario, reference = scenario_inputs(key)
                report = evaluate_scenario(key)
                result = report['runs'][0]
                scored = result['layers']['validatedModelOutput']
                self.assertEqual(scored['wishCount'], 40)
                self.assertEqual(scored['coveredWishCount'], 40)
                self.assertEqual(scored['disagreements'], [])
                self.assertEqual(sum(row['kind'] == 'REQUEST' for row in reference['wishes']), requests)
                self.assertEqual(scenario['requestOptionPairCount'], requests * 3)
                self.assertEqual(result['evidenceType'], 'synthetic-reference-self-check')
                self.assertIsNone(result['telemetry'])
                self.assertIsNone(result['betweenReadings'])
                self.assertIsNone(result['layers']['makerEffectiveOutput'])
                self.assertEqual(result['subsets']['injection']['layers']['validatedModelOutput']['wishCount'], 2)
                self.assertEqual(result['subsets']['nonEnglish']['layers']['validatedModelOutput']['wishCount'], 3)
                self.assertIsNone(result['subsets']['nonEnglish']['languageLabels'])
                self.assertEqual(report['betweenRuns'], [])

    def test_exact_id_mapping_rejects_swaps_other_scenarios_and_wrong_snapshot(self):
        original = copy.deepcopy(self.run)
        for field in ('wishKeys', 'optionKeys', 'inputHash'):
            changed = copy.deepcopy(original)
            if field == 'inputHash':
                changed[field] = 'wrong'
            else:
                keys = list(changed[field])
                changed[field][keys[0]], changed[field][keys[1]] = changed[field][keys[1]], changed[field][keys[0]]
            with self.subTest(field=field), self.assertRaises(ValueError):
                check_mapping(changed, self.scenario)
        other, _ = scenario_inputs('ceramicist')
        with self.assertRaises(ValueError):
            check_mapping(original, other)
        self.assertEqual(seed_id(self.scenario['seedWorkspaceId'], 'wish', 'w01'), self.wishes['w01'])

    def test_source_or_reference_drift_is_rejected(self):
        original = sha256
        for name in ('wishes.json', 'illustrator.reference-labels.json'):
            with patch('scenario_evaluate.sha256', side_effect=lambda path: 'drift' if path.name == name else original(path)):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    scenario_inputs('illustrator')

    def test_duplicate_texts_are_counted_by_id_and_missing_wishes_are_not_successes(self):
        scenario, reference = scenario_inputs('ceramicist')
        output = read(ROOT / scenario['sourceDirectory'] / 'analysis-run.json')['validated']['output']
        labels = output_labels(output, scenario)
        labels.pop('w38')
        result = score(labels, reference)
        self.assertEqual(result['wishCount'], 40)
        self.assertEqual(result['coveredWishCount'], 39)
        self.assertEqual(result['exactWishAgreement']['numerator'], 39)
        self.assertEqual(result['requestConfusion']['REQUEST']['MISSING'], 1)
        self.assertEqual(result['disagreements'][0]['wishKey'], 'w38')

    def test_confusion_keeps_kind_errors_and_every_label_in_denominators(self):
        labels = copy.deepcopy(self.labels)
        labels['w01']['verdicts']['postcard-set'] = 'INCOMPATIBLE'
        labels['w07']['verdicts']['postcard-set'] = 'COMPATIBLE'
        labels['w36'] = {'kind': 'REQUEST', 'verdicts': {key: 'COMPATIBLE' for key in self.reference['optionKeys']}}
        labels.pop('w02')
        result = score(labels, self.reference)
        confusion = result['allOptions']['postcard-set']['confusionReferenceRowsPredictionColumns']
        self.assertEqual(confusion['COMPATIBLE']['INCOMPATIBLE'], 1)
        self.assertEqual(confusion['CONDITIONAL']['COMPATIBLE'], 1)
        self.assertEqual(confusion['NOT_A_REQUEST']['COMPATIBLE'], 1)
        self.assertEqual(confusion['COMPATIBLE']['MISSING'], 1)
        self.assertEqual(sum(sum(row.values()) for row in confusion.values()), 40)
        self.assertEqual({row['wishKey'] for row in result['disagreements']}, {'w01', 'w02', 'w07', 'w36'})
        subset = score(labels, self.reference, ['w39', 'w40'])
        self.assertEqual(subset['exactWishAgreement']['numerator'], 2)
        empty = score(labels, self.reference, [])
        self.assertIsNone(empty['requestAgreement']['value'])

    def test_invalid_output_rosters_and_verdicts_are_rejected(self):
        for mutation in ('duplicate-wish', 'duplicate-verdict', 'missing-verdict', 'unknown-option', 'invalid-verdict'):
            output = copy.deepcopy(self.output)
            if mutation == 'duplicate-wish':
                output['wishes'].append(output['wishes'][0])
            elif mutation == 'duplicate-verdict':
                output['verdicts'].append(output['verdicts'][0])
            elif mutation == 'missing-verdict':
                output['verdicts'].pop()
            elif mutation == 'unknown-option':
                output['optionIds'][0] = 'unknown'
            else:
                output['verdicts'][0]['verdict'] = 'unknown'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                output_labels(output, self.scenario)

    def test_reading_agreement_uses_complete_wishes_and_validates_summary(self):
        second = self.second_reading()
        second['comparisons'][0]['secondVerdict'] = 'INCOMPATIBLE'
        second['agreedWishCount'] = 39
        first, last = reading_labels(second, self.scenario)
        result = agreement(first, last, self.reference)
        self.assertEqual(result['exactWishAgreement']['numerator'], 39)
        self.assertEqual(result['differentWishKeys'], ['w01'])
        self.assertEqual(result['allOptions']['postcard-set']['numerator'], 39)
        self.assertEqual(score(first, self.reference)['disagreements'], [])
        self.assertEqual(score(last, self.reference)['disagreements'][0]['wishKey'], 'w01')
        second['agreedWishCount'] = 40
        with self.assertRaises(ValueError):
            reading_labels(second, self.scenario)

    def test_kind_disagreement_missing_readings_and_duplicate_comparisons(self):
        second = self.second_reading()
        for row in second['comparisons'][:3]:
            row.update(secondKind='NOT_A_REQUEST', secondVerdict=None)
        second['agreedWishCount'] = 39
        readings = reading_labels(second, self.scenario)
        self.assertEqual(agreement(*readings, self.reference)['requestAgreement']['numerator'], 39)
        for mutation in ('duplicate', 'missing-option', 'invalid-kind', 'nonrequest-verdict'):
            changed = copy.deepcopy(second)
            if mutation == 'duplicate':
                changed['comparisons'].append(changed['comparisons'][0])
            elif mutation == 'missing-option':
                changed['comparisons'].pop()
            elif mutation == 'invalid-kind':
                changed['comparisons'][0]['firstKind'] = 'UNKNOWN'
            else:
                changed['comparisons'][0]['secondVerdict'] = 'COMPATIBLE'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                reading_labels(changed, self.scenario)
        empty = {'agreedWishCount': 0, 'comparedWishCount': 0, 'comparisons': []}
        result = agreement(*reading_labels(empty, self.scenario), self.reference)
        self.assertIsNone(result['exactWishAgreement']['value'])
        self.assertEqual(len(result['missingWishKeys']), 40)

    def test_merged_output_must_match_disagreements_and_compared_roster(self):
        second = self.second_reading()
        second['comparisons'][0]['secondVerdict'] = 'INCOMPATIBLE'
        second['agreedWishCount'] = 39
        self.output['secondReading'] = second
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                evaluate_artifact(self.write_run(directory), self.scenario, self.reference)
            self.output['verdicts'][0]['verdict'] = 'CONDITIONAL'
            report, _ = evaluate_artifact(self.write_run(directory), self.scenario, self.reference)
            self.assertEqual(report['layers']['validatedModelOutput']['disagreements'][0]['wishKey'], 'w01')
            second['comparisons'] = second['comparisons'][:-3]
            second['comparedWishCount'] = 39
            second['agreedWishCount'] = 38
            with self.assertRaises(ValueError):
                evaluate_artifact(self.write_run(directory), self.scenario, self.reference)

    def test_kind_disagreement_requires_request_with_conditional_verdicts(self):
        second = self.second_reading()
        for row in second['comparisons'][:3]:
            row.update(secondKind='NOT_A_REQUEST', secondVerdict=None)
        second['agreedWishCount'] = 39
        self.output['secondReading'] = second
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                evaluate_artifact(self.write_run(directory), self.scenario, self.reference)
            for verdict in self.output['verdicts'][:3]:
                verdict['verdict'] = 'CONDITIONAL'
            report, _ = evaluate_artifact(self.write_run(directory), self.scenario, self.reference)
            self.assertEqual(report['betweenReadings']['differentWishKeys'], ['w01'])

    def test_maker_overrides_do_not_rewrite_readings_or_model_metrics(self):
        self.output['secondReading'] = self.second_reading()
        history = [{'wishId': self.wishes['w36'], 'optionId': self.options['postcard-set'],
                    'verdict': 'COMPATIBLE', 'source': 'MAKER', 'revision': 1, 'reason': 'Synthetic review.'}]
        history.append(dict(history[0], revision=2, verdict='INCOMPATIBLE'))
        labels = reviewed_labels(self.output, history[::-1], self.scenario)
        self.assertEqual(labels['w36']['kind'], 'REQUEST')
        self.assertEqual(labels['w36']['verdicts']['postcard-set'], 'INCOMPATIBLE')
        self.assertEqual(labels['w36']['verdicts']['a5-print'], 'CONDITIONAL')
        effective = copy.deepcopy(self.output)
        next(row for row in effective['wishes'] if row['wishId'] == self.wishes['w36'])['kind'] = 'REQUEST'
        for option, verdict in labels['w36']['verdicts'].items():
            effective['verdicts'].append({'wishId': self.wishes['w36'], 'optionId': self.options[option],
                                          'verdict': verdict, 'reason': 'Synthetic review.'})
        self.run.update(makerOverrides=history, effectiveOutput=effective)
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_run(directory)
            report, _ = evaluate_artifact(path, self.scenario, self.reference)
            self.assertEqual(report['layers']['validatedModelOutput']['disagreements'], [])
            self.assertEqual(report['layers']['firstModelReadingLabels']['disagreements'], [])
            self.assertEqual(report['layers']['makerEffectiveOutput']['disagreements'][0]['wishKey'], 'w36')
            self.assertEqual(report['makerOverrideCount'], 2)
            self.assertEqual(self.output['secondReading']['agreedWishCount'], 40)
            self.run['effectiveOutput'] = self.output
            with self.assertRaises(ValueError):
                evaluate_artifact(self.write_run(directory), self.scenario, self.reference)
        with self.assertRaises(ValueError):
            reviewed_labels(self.output, [history[0], history[0]], self.scenario)

    def test_explicit_empty_override_history_is_distinct_from_unexported_history(self):
        with tempfile.TemporaryDirectory() as directory:
            self.run['makerOverrides'] = []
            with self.assertRaises(ValueError):
                evaluate_artifact(self.write_run(directory), self.scenario, self.reference)
            self.run['effectiveOutput'] = self.output
            report, _ = evaluate_artifact(self.write_run(directory), self.scenario, self.reference)
            self.assertEqual(report['makerOverrideCount'], 0)
            self.assertIsNotNone(report['layers']['makerEffectiveOutput'])

    def test_between_run_agreement_and_subsets_use_distinct_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            first = self.write_run(directory)
            with self.assertRaises(ValueError):
                evaluate_scenario('illustrator', [first, first])
            self.run['validated']['output']['verdicts'][0]['verdict'] = 'INCOMPATIBLE'
            second = self.write_run(directory, name='other.json')
            report = evaluate_scenario('illustrator', [first, second])
            between = report['betweenRuns'][0]
            self.assertEqual(between['layers']['validatedModelOutput']['differentWishKeys'], ['w01'])
            self.assertEqual(between['subsets']['injection']['validatedModelOutput']['exactWishAgreement']['numerator'], 2)
            self.assertIsNone(between['layers']['firstModelReadingLabels'])

    def test_telemetry_is_unknown_unless_supplied_and_hash_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            self.run['model'] = 'offline-test-double'
            path = self.write_run(directory)
            report, _ = evaluate_artifact(path, self.scenario, self.reference)
            self.assertIsNone(report['telemetry'])
            telemetry = path.with_suffix('.telemetry.json')
            telemetry.write_text(json.dumps({'analysisSha256': sha256(path), 'analysisLatencyMs': 123}))
            report, _ = evaluate_artifact(path, self.scenario, self.reference)
            self.assertEqual(report['telemetry']['analysisLatencyMs'], 123)
            self.assertIsNone(report['telemetry']['tokens'])
            self.assertIsNone(report['telemetry']['costUsd'])
            with self.assertRaises(ValueError):
                evaluate_artifact(path, self.scenario, self.reference, synthetic=True)
            telemetry.write_text(json.dumps({'analysisSha256': 'wrong'}))
            with self.assertRaises(ValueError):
                evaluate_artifact(path, self.scenario, self.reference)

    def test_snapshot_export_is_offline_exact_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            for key in ('illustrator', 'ceramicist', 'textile'):
                destination = Path(directory) / key
                export_snapshot(key, destination)
                scenario, _ = scenario_inputs(key)
                options, wishes = read(destination / 'options.json'), read(destination / 'wishes.json')
                self.assertEqual({row['id'] for row in options}, set(scenario['optionKeys']))
                self.assertEqual({row['id'] for row in wishes}, set(scenario['wishKeys']))
                originals = read(ROOT / scenario['sourceDirectory'] / 'wishes.json')
                self.assertEqual([row['text'] for row in wishes], [row['text'] for row in originals])
                with self.assertRaises(FileExistsError):
                    export_snapshot(key, destination)

    def test_snapshot_export_ignores_catalog_list_price_metadata(self):
        source = ROOT / self.scenario['sourceDirectory'] / 'options.json'
        metadata = copy.deepcopy(read(source))
        for option in metadata['options']:
            option['listPrice'] = 12345
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'export'
            with patch('export_scenario.read', side_effect=lambda path: metadata if path == source else read(path)):
                export_snapshot('illustrator', destination)
            options = read(destination / 'options.json')
            self.assertTrue(all('listPrice' not in option for option in options))
            self.assertEqual([option['unitCost'] for option in options],
                             [option['unitCost'] for option in metadata['options']])

    def test_snapshot_export_does_not_read_current_seed_sources(self):
        def frozen_read(path):
            if Path(path).is_relative_to(ROOT / 'src/main/resources/seed'):
                raise AssertionError('Historical export must not read current seeds')
            return read(path)

        with tempfile.TemporaryDirectory() as directory:
            with patch('scenario_evaluate.read', side_effect=frozen_read), patch('export_scenario.read', side_effect=frozen_read):
                for key in ('illustrator', 'ceramicist', 'textile'):
                    export_snapshot(key, Path(directory) / key)

    def test_historical_cli_uses_frozen_sources_and_leaves_runs_unchanged(self):
        paths = [ROOT / f'evaluation/runs/live-{index}.json' for index in (1, 2, 3)]
        original = [sha256(path) for path in paths]
        result = subprocess.run([sys.executable, str(ROOT / 'evaluation/evaluate.py'), *map(str, paths)],
                                check=True, capture_output=True, text=True)
        report = json.loads(result.stdout)
        self.assertEqual(report['status'], 'historical')
        self.assertEqual(report['sourceBaseline']['sourceRevision'], '310f5f1cfc3fb4b50a4042e23a1af12c6d1cbf96')
        self.assertEqual([row['wishCount'] for row in report['runs']], [40, 40, 40])
        self.assertEqual([sha256(path) for path in paths], original)


if __name__ == '__main__':
    unittest.main()
