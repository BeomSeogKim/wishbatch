#!/usr/bin/env python3
"""Score exact scenario snapshots offline, keeping model and maker layers separate."""
import argparse
import copy
import hashlib
from itertools import combinations
import json
from pathlib import Path
import uuid

from evaluate import KINDS, ROOT, VERDICTS, ratio, read, sha256, unique

MANIFEST = ROOT / 'evaluation/scenarios/manifest.json'
LABELS = VERDICTS + ('NOT_A_REQUEST',)


def seed_id(workspace, kind, key):
    digest = hashlib.md5(f'{workspace}:{kind}:{key}'.encode()).digest()
    return str(uuid.UUID(bytes=digest, version=3))


def scenario_inputs(key):
    manifest = read(MANIFEST)
    scenario = next(row for row in manifest['scenarios'] if row['key'] == key)
    source = ROOT / scenario['sourceDirectory']
    for name, digest in scenario['sourceSha256'].items():
        if sha256(source / name) != digest:
            raise ValueError(f'Scenario source drift: {key}/{name}')
    reference_path = ROOT / scenario['referenceFile']
    if sha256(reference_path) != scenario['referenceSha256']:
        raise ValueError('Reference changed since snapshot')
    reference = read(reference_path)
    texts = unique(read(source / 'wishes.json'), lambda row: row['key'])
    options = unique(read(source / 'options.json')['options'], lambda row: row['key'])
    for field, kind, keys in (('wishKeys', 'wish', texts), ('optionKeys', 'option', options)):
        expected = {seed_id(scenario['seedWorkspaceId'], kind, key): key for key in keys}
        if scenario[field] != expected:
            raise ValueError('Stable ID mapping mismatch')
    labels = unique(reference['wishes'], lambda row: row['wishKey'])
    if set(texts) != set(labels) or set(scenario['wishKeys'].values()) != set(labels):
        raise ValueError('Reference wish alignment mismatch')
    if set(reference['optionKeys']) != set(scenario['optionKeys'].values()):
        raise ValueError('Reference option alignment mismatch')
    for row in labels.values():
        expected = set(reference['optionKeys']) if row['kind'] == 'REQUEST' else set()
        if row['kind'] not in KINDS or set(row['verdicts']) != expected:
            raise ValueError('Invalid reference kind or coverage')
        if any(value not in VERDICTS for value in row['verdicts'].values()):
            raise ValueError('Invalid reference verdict')
    if reference['method']['sourceSha256'] != scenario['sourceSha256']:
        raise ValueError('Reference source alignment mismatch')
    counts = (len(labels), sum(row['kind'] == 'REQUEST' for row in labels.values()),
              sum(len(row['verdicts']) for row in labels.values()))
    if counts != tuple(scenario[field] for field in ('wishCount', 'requestCount', 'requestOptionPairCount')):
        raise ValueError('Reference counts mismatch')
    for subset in scenario['subsets'].values():
        keys = subset['wishKeys']
        if len(set(keys)) != len(keys) or not set(keys) <= set(labels):
            raise ValueError('Invalid subset alignment')
    return scenario, reference


def check_mapping(run, scenario):
    if run['inputHash'] != scenario['inputHash']:
        raise ValueError('Scenario inputHash mismatch')
    for field in ('wishKeys', 'optionKeys'):
        if field in run and run[field] != scenario[field]:
            raise ValueError(f'Scenario {field} mismatch')


def output_labels(output, scenario):
    wish_map, option_map = scenario['wishKeys'], scenario['optionKeys']
    if len(output['optionIds']) != len(option_map) or set(output['optionIds']) != set(option_map):
        raise ValueError('Invalid option roster')
    wishes = unique(output['wishes'], lambda row: row['wishId'])
    if not set(wishes) <= set(wish_map) or any(row['kind'] not in KINDS for row in wishes.values()):
        raise ValueError('Unknown wish or kind')
    verdicts = unique(output['verdicts'], lambda row: (row['wishId'], row['optionId']))
    expected = {(key, option) for key, row in wishes.items() if row['kind'] == 'REQUEST'
                for option in option_map}
    if set(verdicts) != expected or any(row['verdict'] not in VERDICTS for row in verdicts.values()):
        raise ValueError('Invalid verdict coverage')
    return {wish_map[key]: {'kind': row['kind'], 'verdicts': {
        option_map[option]: verdicts[(key, option)]['verdict'] for option in option_map
    } if row['kind'] == 'REQUEST' else {}} for key, row in wishes.items()}


def reading_labels(second, scenario):
    if second is None:
        return None
    wish_map, option_map = scenario['wishKeys'], scenario['optionKeys']
    comparisons = unique(second['comparisons'], lambda row: (row['wishId'], row['optionId']))
    wish_ids = {key for key, _ in comparisons}
    if not wish_ids <= set(wish_map) or set(comparisons) != {
        (wish, option) for wish in wish_ids for option in option_map
    }:
        raise ValueError('Invalid second-reading coverage')
    readings = []
    for prefix in ('first', 'second'):
        labels = {}
        for wish in sorted(wish_ids):
            rows = [comparisons[(wish, option)] for option in option_map]
            kinds = {row[prefix + 'Kind'] for row in rows}
            if len(kinds) != 1 or not kinds <= set(KINDS):
                raise ValueError('Inconsistent reading kind')
            kind = kinds.pop()
            verdicts = {}
            for option, row in zip(option_map, rows):
                verdict = row[prefix + 'Verdict']
                if (kind == 'REQUEST' and verdict not in VERDICTS) or (kind == 'NOT_A_REQUEST' and verdict is not None):
                    raise ValueError('Invalid reading verdict')
                if verdict is not None:
                    verdicts[option_map[option]] = verdict
            labels[wish_map[wish]] = {'kind': kind, 'verdicts': verdicts}
        readings.append(labels)
    agreed = sum(readings[0][key] == readings[1][key] for key in readings[0])
    if second['comparedWishCount'] != len(wish_ids) or second['agreedWishCount'] != agreed:
        raise ValueError('Second-reading summary mismatch')
    return readings


def merged_labels(first, second):
    merged = {}
    for key, left in first.items():
        right = second[key]
        kind = 'REQUEST' if 'REQUEST' in (left['kind'], right['kind']) else 'NOT_A_REQUEST'
        options = set(left['verdicts']) | set(right['verdicts'])
        verdicts = {option: left['verdicts'].get(option)
                    if left['kind'] == right['kind'] and left['verdicts'].get(option) == right['verdicts'].get(option)
                    else 'CONDITIONAL' for option in sorted(options)} if kind == 'REQUEST' else {}
        merged[key] = {'kind': kind, 'verdicts': verdicts}
    return merged


def score(predicted, reference, keys=None):
    labels = {row['wishKey']: row for row in reference['wishes']}
    keys = sorted(labels if keys is None else keys)
    request_confusion = {label: {other: 0 for other in KINDS + ('MISSING',)} for label in KINDS}
    differences = []
    for key in keys:
        ref, actual = labels[key], predicted.get(key)
        request_confusion[ref['kind']][actual['kind'] if actual else 'MISSING'] += 1
        expected = {'kind': ref['kind'], 'verdicts': ref['verdicts']}
        if actual != expected:
            differences.append({'wishKey': key, 'reference': expected, 'predicted': actual,
                                'reasonableDisagreement': ref['reasonableDisagreement']})
    options = {}
    for option in reference['optionKeys']:
        confusion = {label: {other: 0 for other in LABELS + ('MISSING',)} for label in LABELS}
        for key in keys:
            ref, actual = labels[key], predicted.get(key)
            expected = ref['verdicts'].get(option, 'NOT_A_REQUEST')
            observed = actual['verdicts'].get(option, 'NOT_A_REQUEST') if actual else 'MISSING'
            confusion[expected][observed] += 1
        per_label = {}
        for label in LABELS:
            true_positive = confusion[label][label]
            actual_count = sum(confusion[label].values())
            predicted_count = sum(row[label] for row in confusion.values())
            per_label[label] = {'referenceCount': actual_count, 'predictedCount': predicted_count,
                                'precision': ratio(true_positive, predicted_count),
                                'recall': ratio(true_positive, actual_count)}
        options[option] = {'confusionReferenceRowsPredictionColumns': confusion, 'perLabel': per_label}
    return {'wishCount': len(keys), 'coveredWishCount': sum(key in predicted for key in keys),
            'requestAgreement': ratio(sum(key in predicted and predicted[key]['kind'] == labels[key]['kind']
                                          for key in keys), len(keys)),
            'requestConfusion': request_confusion, 'allOptions': options,
            'exactWishAgreement': ratio(len(keys) - len(differences), len(keys)),
            'disagreements': differences}


def agreement(first, second, reference, keys=None):
    keys = sorted([row['wishKey'] for row in reference['wishes']] if keys is None else keys)
    covered = [key for key in keys if key in first and key in second]
    different = [key for key in covered if first[key] != second[key]]
    return {'expectedWishCount': len(keys), 'comparedWishCount': len(covered),
            'exactWishAgreement': ratio(len(covered) - len(different), len(covered)),
            'missingWishKeys': [key for key in keys if key not in covered],
            'differentWishKeys': different,
            'requestAgreement': ratio(sum(first[key]['kind'] == second[key]['kind'] for key in covered), len(covered)),
            'allOptions': {option: ratio(sum(
                first[key]['verdicts'].get(option, 'NOT_A_REQUEST') ==
                second[key]['verdicts'].get(option, 'NOT_A_REQUEST') for key in covered), len(covered))
                for option in reference['optionKeys']}}


def reviewed_labels(output, history, scenario):
    labels = copy.deepcopy(output_labels(output, scenario))
    revisions = set()
    latest = {}
    for row in history:
        key = (row['wishId'], row['optionId'])
        revision = row['revision']
        if (key[0] not in scenario['wishKeys'] or key[1] not in scenario['optionKeys'] or
                scenario['wishKeys'][key[0]] not in labels or
                row['source'] != 'MAKER' or row['verdict'] not in ('COMPATIBLE', 'INCOMPATIBLE') or
                not isinstance(revision, int) or isinstance(revision, bool) or revision < 1 or
                not row['reason'].strip() or len(row['reason']) > 500 or (key, revision) in revisions):
            raise ValueError('Invalid maker override')
        revisions.add((key, revision))
        if key not in latest or revision > latest[key]['revision']:
            latest[key] = row
    for (wish_id, option_id), row in latest.items():
        wish = labels[scenario['wishKeys'][wish_id]]
        if wish['kind'] == 'NOT_A_REQUEST':
            wish.update(kind='REQUEST', verdicts={option: 'CONDITIONAL' for option in scenario['optionKeys'].values()})
        wish['verdicts'][scenario['optionKeys'][option_id]] = row['verdict']
    return labels


def telemetry_for(path, synthetic):
    telemetry_path = path.with_suffix('.telemetry.json')
    if not telemetry_path.exists():
        return None
    if synthetic:
        raise ValueError('Synthetic references cannot carry live telemetry')
    telemetry = read(telemetry_path)
    if telemetry['analysisSha256'] != sha256(path):
        raise ValueError('Telemetry artifact mismatch')
    return {'analysisLatencyMs': telemetry.get('analysisLatencyMs'), 'tokens': telemetry.get('tokens'),
            'costUsd': telemetry.get('costUsd'), 'usageResponses': telemetry.get('usageResponses'),
            'usageLimit': telemetry.get('usageLimit'), 'status': telemetry.get('status')}


def evaluate_artifact(path, scenario, reference, synthetic=False):
    run = read(path)
    check_mapping(run, scenario)
    if synthetic and run['model'] != 'synthetic-reference':
        raise ValueError('Expected a synthetic stored reference')
    synthetic_model = run['model'] == 'synthetic-reference'
    output = run['validated']['output']
    validated = output_labels(output, scenario)
    raw = reading_labels(output.get('secondReading'), scenario)
    if raw is not None and validated != merged_labels(*raw):
        raise ValueError('Validated model output does not match retained reading merge')
    history = run.get('makerOverrides')
    effective = None
    if history is not None:
        if 'effectiveOutput' not in run:
            raise ValueError('Maker history requires a separately exported effective output')
        effective = output_labels(run['effectiveOutput'], scenario)
        if effective != reviewed_labels(output, history, scenario):
            raise ValueError('Effective output does not match maker override history')
    elif 'effectiveOutput' in run:
        raise ValueError('Effective output requires explicit maker history')
    layers = {'validatedModelOutput': validated,
              'firstModelReadingLabels': raw[0] if raw else None,
              'secondModelReadingLabels': raw[1] if raw else None,
              'makerEffectiveOutput': effective}
    def reports(keys=None):
        return {name: score(labels, reference, keys) if labels is not None else None
                for name, labels in layers.items()}
    return {'artifactSha256': sha256(path), 'model': run['model'], 'inputHash': run['inputHash'],
            'evidenceType': ('synthetic-reference-self-check' if synthetic else
                             'supplied-synthetic-artifact' if synthetic_model else 'supplied-analysis-artifact'),
            'referenceIndependence': 'Derived from stored synthetic reference; not independent or human adjudicated.',
            'rawProviderPayloads': None, 'makerOverrideCount': len(history) if history is not None else None,
            'layers': reports(),
            'betweenReadings': agreement(*raw, reference) if raw else None,
            'subsets': {name: {'annotationBasis': subset['annotationBasis'],
                              'languageLabels': subset.get('languageLabels'),
                              'layers': reports(subset['wishKeys']),
                              'betweenReadings': agreement(*raw, reference, subset['wishKeys']) if raw else None}
                        for name, subset in scenario['subsets'].items()},
            'telemetry': telemetry_for(path, synthetic or synthetic_model),
            'validationFlags': run['validated']['flags']}, layers


def evaluate_scenario(key, paths=None):
    scenario, reference = scenario_inputs(key)
    synthetic = paths is None
    paths = [ROOT / scenario['sourceDirectory'] / 'analysis-run.json'] if synthetic else paths
    evaluated = [evaluate_artifact(path, scenario, reference, synthetic) for path in paths]
    if len({report['artifactSha256'] for report, _ in evaluated}) != len(evaluated):
        raise ValueError('Repeated artifact cannot count as another run')
    between_runs = []
    for (left, (_, first)), (right, (_, second)) in combinations(enumerate(evaluated), 2):
        between_runs.append({'firstRun': left, 'secondRun': right,
                             'layers': {name: agreement(first[name], second[name], reference)
                                        if first[name] is not None and second[name] is not None else None
                                        for name in first},
                             'subsets': {subset: {
                                 name: agreement(first[name], second[name], reference, definition['wishKeys'])
                                 if first[name] is not None and second[name] is not None else None for name in first}
                                 for subset, definition in scenario['subsets'].items()}})
    return {'scenario': key, 'sourceSha256': scenario['sourceSha256'],
            'referenceSha256': sha256(ROOT / scenario['referenceFile']),
            'runs': [report for report, _ in evaluated], 'betweenRuns': between_runs}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=[row['key'] for row in read(MANIFEST)['scenarios']])
    parser.add_argument('--runs', nargs='+', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.runs and not args.scenario:
        parser.error('--runs requires --scenario')
    keys = [args.scenario] if args.scenario else [row['key'] for row in read(MANIFEST)['scenarios']]
    result = {'schemaVersion': 1, 'scenarios': [evaluate_scenario(key, args.runs) for key in keys]}
    rendered = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.write_text(rendered)
    else:
        print(rendered, end='')


if __name__ == '__main__':
    main()
