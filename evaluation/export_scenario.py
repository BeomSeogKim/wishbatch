#!/usr/bin/env python3
"""Export exact scenario inputs for the existing seed generator; no model calls."""
import argparse
import json
from pathlib import Path

from evaluate import ROOT, read
from scenario_evaluate import MANIFEST, scenario_inputs


def export_snapshot(key, destination):
    scenario, _ = scenario_inputs(key)
    source = ROOT / scenario['sourceDirectory']
    option_ids = {value: key for key, value in scenario['optionKeys'].items()}
    wish_ids = {value: key for key, value in scenario['wishKeys'].items()}
    options = []
    for seed in read(source / 'options.json')['options']:
        option = {field: value for field, value in seed.items() if field != 'key'}
        option.update(id=option_ids[seed['key']], workspaceId=scenario['seedWorkspaceId'])
        options.append(option)
    wishes = [{'id': wish_ids[seed['key']], 'workspaceId': scenario['seedWorkspaceId'],
               'text': seed['text'], 'source': 'IMPORTED', 'displayName': seed['displayName'],
               'createdAt': '1970-01-01T00:00:00Z', 'hidden': False}
              for seed in read(source / 'wishes.json')]
    destination.mkdir(parents=True, exist_ok=False)
    for filename, payload in (('options.json', options), ('wishes.json', wishes)):
        (destination / filename).write_text(json.dumps(payload, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('scenario', choices=[row['key'] for row in read(MANIFEST)['scenarios']])
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    export_snapshot(args.scenario, args.destination)


if __name__ == '__main__':
    main()
