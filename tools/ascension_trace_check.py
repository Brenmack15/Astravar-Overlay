#!/usr/bin/env python3
"""Validate the public Ascension matrices; optionally verify line coverage against an authorized local source."""
import argparse
import csv
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs/ascension'


def load(name):
    with (DOCS / name).open(encoding='utf-8', newline='') as source:
        return list(csv.DictReader(source))


def verify_protocol_coverage(capabilities, refresh, errors):
    report = json.loads((DOCS / 'protocol-parity.json').read_text(encoding='utf-8'))
    operations = report['operations']
    owner_only = set(report['summary'].get('ownerOnly', []))
    assigned = set()
    changed = False
    for row in capabilities:
        if not (ROOT / row['candidate_owner_implementation']).is_file() or not row['candidate_owner_entry']:
            errors.append(f"{row['id']} has no current native route")
        commands = row['related_registered_commands'].split()
        assigned.update(commands)
        unknown = set(commands).difference(operations)
        if unknown:
            errors.append(f"{row['id']} names unregistered commands {sorted(unknown)}")
        passed = []
        pending = []
        restricted = []
        for command in commands:
            result = operations.get(command, {})
            both = all(result.get(client, {}).get('commands', 0) > 0 for client in ('mcp', 'device'))
            (restricted if command in owner_only else passed if both else pending).append(command)
        for key, values in [('wire_verified_commands', passed), ('wire_unverified_commands', pending), ('wire_owner_only_commands', restricted)]:
            value = ' '.join(values)
            if row[key] != value:
                if refresh:
                    row[key] = value
                    changed = True
                else:
                    errors.append(f"{row['id']} has stale {key}. Run with --sync-wire after a protocol rerun.")
    unassigned = set(operations).difference(assigned)
    if unassigned:
        errors.append(f"Registered commands without a capability link: {sorted(unassigned)}")
    if changed:
        with (DOCS / 'capabilities.csv').open('w', encoding='utf-8', newline='') as target:
            writer = csv.DictWriter(target, fieldnames=capabilities[0], lineterminator='\n')
            writer.writeheader()
            writer.writerows(capabilities)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backlog', type=Path, help='Authorized private frozen 26 November source. Only counts and missing line numbers are printed.')
    parser.add_argument('--sync-wire', action='store_true', help='Refresh per-capability wire results from the saved protocol-parity report.')
    args = parser.parse_args()
    directive = load('requirements.csv')
    backlog = load('requirements-backlog.csv')
    fixtures = load('requirements-fixtures.csv')
    caps = load('capabilities.csv')
    errors = []
    verify_protocol_coverage(caps, args.sync_wire, errors)
    all_rows = directive + backlog + fixtures + caps
    ids = {row['id'] for row in all_rows}
    if len(ids) != len(all_rows):
        errors.append('Duplicate stable IDs')
    sections = {int(r['id'].split('-')[1]) for r in directive}
    if sections != set(range(1, 35)):
        errors.append('Directive sections 1 through 34 must all be indexed')
    directive_ids = {r['id'] for r in directive}
    for row in backlog + fixtures + caps:
        if row['directive_id'] not in directive_ids:
            errors.append(f"{row['id']} has no matching directive ID")
    for row in all_rows:
        if row['planned_acceptance_id'] != 'AT-' + row['id']:
            errors.append(f"Invalid test case ID for {row['id']}")
        if row['status'] in {'VERIFIED', 'INDEPENDENTLY_VERIFIED'} and not row['acceptance_evidence'].strip():
            errors.append(f"{row['id']} is verified without evidence")
        for field in ['implementation_or_baseline_area', 'existing_related_tests', 'related_baseline_tests']:
            for ref in filter(None, row.get(field, '').split('; ')):
                if not (ROOT / ref).exists():
                    errors.append(f"{row['id']} references missing path {ref}")
    evidence = load('requirements-candidate-evidence.csv')
    evidences = {entry['id']: entry for entry in evidence}
    if len(evidence) != len(evidences):
        errors.append('Duplicate candidate evidence IDs')
    for entry in evidence:
        if entry['verification'] not in {'PASS', 'SOURCE', 'HISTORICAL', 'FAIL'}:
            errors.append(f"Unexpected evidence status for {entry['id']}")
        for field in ('implementation_path', 'test_or_source_path'):
            if not (ROOT / entry[field]).exists():
                errors.append(f"{entry['id']} references missing {field} {entry[field]}")
    for row in all_rows:
        linked = row['candidate_evidence_ids'].split()
        if any(ref not in evidences for ref in linked):
            errors.append(f"{row['id']} has undefined candidate evidence")
        if row['status'] in {'VERIFIED', 'INDEPENDENTLY_VERIFIED'} and not any(
            evidences.get(ref, {}).get('verification') == 'PASS' for ref in linked
        ):
            errors.append(f"{row['id']} has no passing candidate check")
    if args.backlog:
        expected = {n for n, line in enumerate(args.backlog.read_text(encoding='utf-8').splitlines(), 1) if re.match(r'^- \[[ xX]\]', line)}
        mapped = {int(r['id'].split('-')[-1]) for r in backlog}
        missing = sorted(expected - mapped)
        if missing:
            errors.append(f'Missing frozen backlog checkbox paragraphs: {missing}')
        print(f'{len(expected)} frozen backlog checkbox paragraphs checked')
    for issue in errors:
        print(issue)
    print(f'{len(directive)} directive rows, {len(backlog)} backlog paragraphs, {len(fixtures)} fixture rows, {len(caps)} capability rows; {len(evidence)} distinct candidate checks; {len(errors)} integrity errors')
    return int(bool(errors))


if __name__ == '__main__':
    raise SystemExit(main())
