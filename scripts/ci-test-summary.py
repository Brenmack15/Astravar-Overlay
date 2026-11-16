#!/usr/bin/env python3
"""Write compact actual JUnit failures; absence of reports is explicitly unknown."""
from pathlib import Path
import os
import xml.etree.ElementTree as ET

rows = []
for root in ('app/build/test-results', 'rules/build/test-results'):
    for path in sorted(Path(root).rglob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        for case in suite.iter('testcase'):
            for failure in list(case.findall('failure')) + list(case.findall('error')):
                rows.append((case.get('classname', ''), case.get('name', ''), failure.get('message', '').split('\n')[0][:300], str(path)))
reports = list(Path('app/build/test-results').rglob('TEST-*.xml')) + list(Path('rules/build/test-results').rglob('TEST-*.xml'))
text = f'JUnit reports found: {len(reports)}. Failing cases: {len(rows)}.\n'
if not reports:
    text += 'No executed JUnit evidence is available; inspect setup/compilation steps.\n'
for cls, name, message, path in rows[:40]:
    text += f'- `{cls}.{name}`: {message} (`{path}`)\n'
Path('validation').mkdir(exist_ok=True)
Path('validation/test-failures.md').write_text(text)
if os.getenv('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
        stream.write(text)
print(text)
