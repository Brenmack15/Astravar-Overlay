import copy
from pathlib import Path
import runpy
import unittest

VERIFY = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'genesis-same-schema-readback.py'))['verify']


def proof():
    return {'phase': 'preserved', 'active_store': 'astravar-campaign-ledger.db',
            'databases': {'astravar.db': {'user_version': 3, 'tables': {'campaign': {'sha256': 'a' * 64}}},
                          'astravar-reforge.db': {'user_version': 1},
                          'astravar-campaign-ledger.db': {'user_version': 9, 'tables': {'reforge_characters': {'sha256': 'b' * 64}}}},
            'source_files': {'astravar-reforge.db': 'c' * 64}, 'source_digest': 'd' * 64,
            'source_counts': {'reforge_characters': 1}, 'receipt': {'sourceDigest': 'd' * 64},
            'actor': {'profileId': 'fictional-actor', 'revision': 4}, 'write_history': {},
            'fixture': {'resource_id': 'fictional-resource'}}


class GenesisSameSchemaReadbackTest(unittest.TestCase):
    def test_equal_independent_stopped_store_reports_pass(self):
        before = proof()
        self.assertTrue(VERIFY(before, copy.deepcopy(before))['all_three_stores_equal'])

    def test_each_saved_category_is_preserved(self):
        for key in ('databases', 'source_files', 'source_digest', 'source_counts', 'receipt', 'actor', 'write_history', 'fixture'):
            before, after = proof(), proof()
            if isinstance(after[key], dict):
                after[key]['changed'] = True
            else:
                after[key] += '-changed'
            with self.subTest(key=key), self.assertRaises(ValueError):
                VERIFY(before, after)

    def test_missing_source_receipt_phase_or_schema_cannot_pass(self):
        for alter in (lambda x: x.pop('actor'), lambda x: x.update(phase='baseline'),
                      lambda x: x.update(receipt=None), lambda x: x.update(source_files={}),
                      lambda x: x['databases']['astravar-campaign-ledger.db'].update(user_version=6)):
            before = proof()
            alter(before)
            with self.assertRaises(ValueError):
                VERIFY(before, copy.deepcopy(before))
