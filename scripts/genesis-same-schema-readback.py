#!/usr/bin/env python3
"""Compare independent stopped-store proofs for an existing Genesis upgrade."""
import argparse
import json
from pathlib import Path


def verify(before, after):
    required = {'phase', 'active_store', 'databases', 'source_files', 'source_digest',
                'source_counts', 'receipt', 'actor', 'write_history', 'fixture'}
    if not isinstance(before, dict) or not isinstance(after, dict):
        raise ValueError('Expected independent store proof objects')
    if set(before) != required or set(after) != required:
        raise ValueError('Incomplete or unknown stopped-store proof fields')
    for proof in (before, after):
        if proof['phase'] != 'preserved' or proof['active_store'] != 'astravar-campaign-ledger.db':
            raise ValueError('Existing Genesis must already use canonical storage')
        databases = proof['databases']
        if set(databases) != {'astravar.db', 'astravar-reforge.db', 'astravar-campaign-ledger.db'}:
            raise ValueError('All three original stores are required')
        if databases['astravar-campaign-ledger.db']['user_version'] != 9:
            raise ValueError('Expected the installed Genesis schema9')
        if proof['receipt'] is None or not proof['source_files']:
            raise ValueError('Original source and cutover receipt are required')
    if before != after:
        raise ValueError('Genesis upgrade altered saved rows, sources, history or receipts')
    return {'same_schema': 9, 'all_three_stores_equal': True,
            'same_source_bytes': True, 'same_character_revision': True,
            'same_history_and_receipts': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = verify(json.loads(args.before.read_text()), json.loads(args.after.read_text()))
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
