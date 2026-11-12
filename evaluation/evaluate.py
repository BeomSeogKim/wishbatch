#!/usr/bin/env python3
"""Compare historical analysis artifacts against their frozen source snapshot."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERDICTS = ('COMPATIBLE', 'CONDITIONAL', 'INCOMPATIBLE')
KINDS = ('REQUEST', 'NOT_A_REQUEST')


def read(path):
    return json.loads(path.read_text())


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def unique(rows, key):
    result = {}
    for row in rows:
        item = key(row)
        if item in result:
            raise ValueError(f'Duplicate row: {item}')
        result[item] = row
    return result


def ratio(numerator, denominator):
    return {'numerator': numerator, 'denominator': denominator,
            'value': numerator / denominator if denominator else None}


def compare(run, reference, texts):
    labels = unique(reference['wishes'], lambda row: row['wishKey'])
    option_keys = set(reference['optionKeys'])
    wish_map, option_map = run['wishKeys'], run['optionKeys']
    if set(wish_map.values()) != set(labels) or len(wish_map) != len(labels):
        raise ValueError('Seed wish mapping does not match reference')
    if set(option_map.values()) != option_keys or len(option_map) != len(option_keys):
        raise ValueError('Seed option mapping does not match reference')
    output = run['validated']['output']
    if len(output['optionIds']) != len(option_keys) or set(output['optionIds']) != set(option_map):
        raise ValueError('Analysis option roster does not match seed')
    wishes = unique(output['wishes'], lambda row: wish_map[row['wishId']])
    if set(wishes) != set(labels) or set(texts) != set(labels):
        raise ValueError('Analysis must contain every seed wish exactly once')
    if any(row['kind'] not in KINDS for row in wishes.values()):
        raise ValueError('Unknown request kind')
    verdicts = unique(output['verdicts'],
                      lambda row: (wish_map[row['wishId']], option_map[row['optionId']]))
    expected = {(key, option) for key, row in wishes.items() if row['kind'] == 'REQUEST'
                for option in option_keys}
    if set(verdicts) != expected or any(row['verdict'] not in VERDICTS for row in verdicts.values()):
        raise ValueError('Incomplete or invalid request/option verdict roster')
    for row in labels.values():
        if row['kind'] not in KINDS or set(row['verdicts']) != (option_keys if row['kind'] == 'REQUEST' else set()):
            raise ValueError('Invalid reference roster')
        if any(value not in VERDICTS for value in row['verdicts'].values()):
            raise ValueError('Invalid reference verdict')

    def model_verdict(key, option):
        return verdicts[(key, option)]['verdict'] if (key, option) in verdicts else None

    request_confusion = {ref: {pred: 0 for pred in KINDS} for ref in KINDS}
    for key, ref in labels.items():
        request_confusion[ref['kind']][wishes[key]['kind']] += 1
    proposal = output['proposal']
    selected = option_map[proposal['optionId']] if proposal else None
    option_reports = {}
    for option in sorted(option_keys):
        confusion = {ref: {pred: 0 for pred in VERDICTS} for ref in VERDICTS}
        comparable = 0
        for key, ref in labels.items():
            pred = model_verdict(key, option)
            if ref['kind'] == 'REQUEST' and pred is not None:
                confusion[ref['verdicts'][option]][pred] += 1
                comparable += 1
        actual = {key for key, row in labels.items() if row['verdicts'].get(option) == 'COMPATIBLE'}
        predicted = {key for key in labels if model_verdict(key, option) == 'COMPATIBLE'}
        true_positive = len(actual & predicted)
        option_reports[option] = {
            'compatiblePrecision': ratio(true_positive, len(predicted)),
            'compatibleRecall': ratio(true_positive, len(actual)),
            'truePositive': true_positive, 'falsePositive': len(predicted - actual),
            'falseNegative': len(actual - predicted),
            'verdictConfusionReferenceRowsModelColumns': confusion,
            'verdictComparableRequests': comparable,
            'referenceRequestsMissingModelVerdict': sum(
                row['kind'] == 'REQUEST' and model_verdict(key, option) is None
                for key, row in labels.items()),
        }
    quotes = [(wish_map[row['wishId']], need['quote'], 'need')
              for row in output['wishes'] for need in row['needs']]
    quotes += [(wish_map[row['wishId']], row['quote'], 'rationale')
               for row in proposal['rationale']] if proposal else []
    bad_quotes = [{'wishKey': key, 'quote': quote, 'source': source}
                  for key, quote, source in quotes if not quote or quote not in texts[key]]
    differences = []
    for key, ref in labels.items():
        predicted = {option: model_verdict(key, option) for option in sorted(option_keys)
                     if model_verdict(key, option) is not None}
        if ref['kind'] != wishes[key]['kind'] or ref['verdicts'] != predicted:
            differences.append({
                'wishKey': key, 'text': texts[key],
                'reasonableDisagreement': ref['reasonableDisagreement'],
                'reference': {'kind': ref['kind'], 'verdicts': ref['verdicts'], 'reason': ref['reason']},
                'model': {'kind': wishes[key]['kind'], 'verdicts': predicted,
                          'reasons': {option: verdicts[(key, option)]['reason'] for option in predicted}},
            })
    proposed_differences = [key for key, ref in labels.items() if selected and (
        ref['kind'] != wishes[key]['kind'] or ref['verdicts'].get(selected) != model_verdict(key, selected))]
    return {
        'model': run['model'], 'completedAt': run['completedAt'], 'inputHash': run['inputHash'],
        'wishCount': len(labels), 'referenceRequestCount': sum(row['kind'] == 'REQUEST' for row in labels.values()),
        'requestAgreement': ratio(sum(row['kind'] == wishes[key]['kind'] for key, row in labels.items()), len(labels)),
        'requestConfusionReferenceRowsModelColumns': request_confusion,
        'proposedOption': selected, 'proposedOptionMetrics': option_reports.get(selected),
        'allOptions': option_reports,
        'exactQuotes': ratio(len(quotes) - len(bad_quotes), len(quotes)),
        'nonExactQuotes': bad_quotes,
        'differentWishCount': len(differences), 'differences': differences,
        'proposedOptionDifferentWishCount': len(proposed_differences),
        'proposedOptionDifferentWishKeys': proposed_differences,
        'validationFlags': run['validated']['flags'],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs', nargs='+', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    reference_path = ROOT / 'evaluation/reference-labels.json'
    reference = read(reference_path)
    source = ROOT / 'evaluation/historical'
    baseline = read(source / 'baseline.json')
    if baseline['sourceSha256'] != reference['method']['sourceSha256'] or sha256(reference_path) != baseline['referenceSha256']:
        raise ValueError('Historical reference changed')
    for filename, digest in reference['method']['sourceSha256'].items():
        if sha256(source / filename) != digest:
            raise ValueError('Seed changed since reference labeling')
    texts = {row['key']: row['text'] for row in read(source / 'wishes.json')}
    reports = []
    for path in args.runs:
        path = path.resolve()
        report = compare(read(path), reference, texts)
        report['artifact'] = str(path.relative_to(ROOT))
        report['artifactSha256'] = sha256(path)
        telemetry_path = path.with_suffix('.telemetry.json')
        report['telemetry'] = read(telemetry_path) if telemetry_path.exists() else None
        if report['telemetry'] and report['telemetry']['analysisSha256'] != report['artifactSha256']:
            raise ValueError('Telemetry does not match analysis artifact')
        reports.append(report)
    if len({report['inputHash'] for report in reports}) != 1:
        raise ValueError('Runs have different input snapshots')
    result = {'status': 'historical', 'sourceBaseline': baseline,
              'referenceSha256': sha256(reference_path), 'runs': reports}
    rendered = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.write_text(rendered)
    else:
        print(rendered, end='')


if __name__ == '__main__':
    main()
