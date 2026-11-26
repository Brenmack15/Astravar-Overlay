#!/usr/bin/env python3
"""Validate the public Ascension matrices; optionally verify line coverage against an authorized local source."""
import argparse
import csv
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs/ascension'


def load(name):
    with (DOCS / name).open(encoding='utf-8', newline='') as source:
        return list(csv.DictReader(source))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backlog', type=Path, help='Authorized private frozen 26 November source. Only counts and missing line numbers are printed.')
    args = parser.parse_args()
    directive = load('requirements.csv')
    backlog = load('requirements-backlog.csv')
    fixtures = load('requirements-fixtures.csv')
    caps = load('capabilities.csv')
    errors = []
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
    if args.backlog:
        expected = {n for n, line in enumerate(args.backlog.read_text(encoding='utf-8').splitlines(), 1) if re.match(r'^- \[[ xX]\]', line)}
        mapped = {int(r['id'].split('-')[-1]) for r in backlog}
        missing = sorted(expected - mapped)
        if missing:
            errors.append(f'Missing frozen backlog checkbox paragraphs: {missing}')
        print(f'{len(expected)} frozen backlog checkbox paragraphs checked')
    for issue in errors:
        print(issue)
    print(f'{len(directive)} directive rows, {len(backlog)} backlog paragraphs, {len(fixtures)} fixture rows, {len(caps)} capability rows; {len(errors)} integrity errors')
    return int(bool(errors))


if __name__ == '__main__':
    raise SystemExit(main())
