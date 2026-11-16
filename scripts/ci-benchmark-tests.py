#!/usr/bin/env python3
"""Opt-in bounded test-worker benchmark; never changes the release defaults."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

CLASSES = [
    'data.CampaignConnectionTest', 'data.GenesisAuthorityBackupTest',
    'data.CampaignSyncDomainTest', 'data.ReforgeLargeCampaignSelectionTest',
    'ui.GenesisPagerTest', 'ui.GenesisCompactChromeTest',
    'ui.CampaignConnectionUiTest', 'ui.GenesisCampaignUiTest',
]
root = Path('validation/test-worker-benchmark')
root.mkdir(parents=True, exist_ok=True)
# Compile once outside measured runs. The per-task --rerun forces actual test
# execution (not UP-TO-DATE/FROM-CACHE), while reusable compilation stays warm.
subprocess.run(['bash', 'gradlew', '--console=plain', '--build-cache', ':app:compileDebugUnitTestKotlin'], check=True)
results = []
expected_cases = None
for repeat in (1, 2):
    for forks, every in ((1, 1), (1, 4), (2, 1), (2, 4)):
        name = f'repeat{repeat}-forks{forks}-every{every}'
        output = root / name
        output.mkdir(exist_ok=True)
        args = ['bash', 'gradlew', '--console=plain', '--build-cache', ':app:testDebugUnitTest', '--rerun',
                f'-PastravarTestForks={forks}', f'-PastravarTestForkEvery={every}']
        for cls in CLASSES:
            args += ['--tests', 'com.yazsras.astravar.' + cls]
        started = time.monotonic()
        with (output / 'gradle.log').open('w') as log:
            proc = subprocess.run(['/usr/bin/time', '-v', '-o', str(output / 'resources.txt'), *args],
                                  stdout=log, stderr=subprocess.STDOUT)
        cases = []
        junit_seconds = 0.0
        for report in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
            shutil.copy2(report, output / report.name)
            suite = ET.parse(report).getroot()
            junit_seconds += float(suite.get('time', '0'))
            for case in suite.iter('testcase'):
                cases.append((case.get('classname'), case.get('name'),
                              bool(case.find('failure') is not None or case.find('error') is not None),
                              case.find('skipped') is not None))
        result = dict(configuration=name, exit_code=proc.returncode,
                      elapsed_seconds=round(time.monotonic() - started, 3),
                      junit_seconds=round(junit_seconds, 3), cases=len(cases))
        results.append(result)
        (root / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
        if proc.returncode or not cases or any(case[2] for case in cases):
            raise SystemExit(f'{name} failed; inspect {output}; no faster setting was accepted.')
        if expected_cases is None:
            expected_cases = sorted(cases)
        elif expected_cases != sorted(cases):
            raise SystemExit(f'{name} did not execute identical successful cases; no promotion.')
print(json.dumps(results, indent=2))
if os.getenv('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        summary.write('Worker benchmark passed the same bounded cases twice per setting. Full repeated debug/release suites and memory review are still required before changing defaults.\n')
