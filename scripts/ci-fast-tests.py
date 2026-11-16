#!/usr/bin/env python3
"""Run a checked test selection without shell interpolation or hiding the rules suite."""
import json
from pathlib import Path
import subprocess

plan = json.loads(Path('validation/ci-plan.json').read_text())
tests = plan['tests']
if not tests:
    raise SystemExit('No critical Android tests selected; fail closed.')
# Separate rules task: --tests belongs only to app:testDebugUnitTest.
subprocess.run(['bash', 'gradlew', '--console=plain', '--build-cache', ':rules:test'], check=True)
args = ['bash', 'gradlew', '--console=plain', '--build-cache', ':app:testDebugUnitTest']
for name in tests:
    args += ['--tests', name]
subprocess.run(args, check=True)
