"""Evaluate the actual workflow policies against trusted/fork/event scenarios."""
import ast
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ('astravar-development.yml', 'astravar-3.0.yml', 'android.yml', 'owner-connection.yml')


def evaluate(expression, values):
    for name in sorted(values, key=len, reverse=True):
        expression = expression.replace(name, json.dumps(values[name]))
    expression = expression.replace('&&', ' and ').replace('||', ' or ')
    expression = re.sub(r'!(?!=)', ' not ', expression).strip()
    expression = expression.replace('true', 'True').replace('false', 'False')
    tree = ast.parse(expression, mode='eval')
    allowed = (ast.Expression, ast.BoolOp, ast.UnaryOp, ast.Compare, ast.Constant, ast.And, ast.Or, ast.Not, ast.Eq, ast.NotEq)
    if any(not isinstance(node, allowed) for node in ast.walk(tree)):
        raise ValueError('Unexpected workflow cache expression')
    return eval(compile(tree, '<validated-cache-policy>', 'eval'), {'__builtins__': {}}, {})


def context(event='pull_request', head='Brenmack15/Astravar-Overlay', ref='18/merge', successful=True,
            build_outcome='success'):
    return {'github.event_name': event, 'github.event.pull_request.head.repo.full_name': head,
            'github.repository': 'Brenmack15/Astravar-Overlay',
            'github.event.repository.default_branch': 'main', 'github.ref_name': ref,
            'success()': successful, 'env.GENESIS_GATE': 'true',
            'steps.native_build.outcome': build_outcome}


class CiCachePolicyTests(unittest.TestCase):
    def expressions(self):
        for name in WORKFLOWS:
            text = (ROOT / '.github/workflows' / name).read_text()
            match = re.search(r'cache-read-only: \$\{\{ (.*?) \}\}', text)
            self.assertIsNotNone(match, name)
            yield name, match.group(1)

    def test_fork_pull_requests_cannot_write(self):
        for name, expression in self.expressions():
            with self.subTest(workflow=name):
                self.assertTrue(evaluate(expression, context(head='fork/Astravar-Overlay')))

    def test_same_repository_pull_requests_can_warm_their_merge_ref(self):
        for name, expression in self.expressions():
            with self.subTest(workflow=name):
                self.assertFalse(evaluate(expression, context()))

    def test_untrusted_feature_push_is_read_only_and_explicit_runs_can_write(self):
        for name, expression in self.expressions():
            for event, ref, expected in [('push', 'feature', True), ('push', 'main', False), ('workflow_dispatch', 'feature', False)]:
                with self.subTest(workflow=name, event=event, ref=ref):
                    self.assertEqual(expected, evaluate(expression, context(event=event, ref=ref)))

    def test_historical_artifacts_require_success_and_allowed_scope(self):
        text = (ROOT / '.github/workflows/android.yml').read_text()
        expression = re.search(r'name: Save validated historical cache.*\n(?:\s+#.*\n)*\s+if: (.*)', text).group(1)
        self.assertTrue(evaluate(expression, context()))
        self.assertFalse(evaluate(expression, context(successful=False)))
        self.assertFalse(evaluate(expression, context(build_outcome='failure')))
        self.assertFalse(evaluate(expression, context(build_outcome='skipped')))
        self.assertFalse(evaluate(expression, context(head='fork/Astravar-Overlay')))
        self.assertFalse(evaluate(expression, context(event='push', ref='feature')))
        self.assertTrue(evaluate(expression, context(event='workflow_dispatch', ref='feature')))

    def test_historical_cache_saves_after_validation_before_native_acceptance(self):
        text = (ROOT / '.github/workflows/android.yml').read_text()
        build = text.index('      - name: Build native APKs;')
        cache = text.index('      - name: Save validated historical cache')
        native = text.index('      - name: Ordered release UI, import, recovery and in-place update')
        self.assertLess(build, cache)
        self.assertLess(cache, native)
        build_step = text[build:cache]
        self.assertIn('id: native_build', build_step)
        self.assertIn('scripts/inspect_release_apk.py', build_step)
        self.assertIn('scripts/ci-baseline.py --version-code 49', build_step)
        self.assertIn('scripts/ci-baseline.py --version-code 50', build_step)
        self.assertIn('run: timeout -k 10s 5100s bash scripts/ci-emulator.sh', text[native:])

    def test_no_privileged_pull_request_target_trigger_or_signing_cache(self):
        for name in WORKFLOWS:
            text = (ROOT / '.github/workflows' / name).read_text()
            self.assertNotIn('pull_request_target:', text)
            self.assertNotIn('cache-encryption-key:', text)
        native = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertNotIn('restore-keys:', native)
        self.assertIn("hashFiles('scripts/ci-baseline.py', 'scripts/inspect_release_apk.py')", native)
