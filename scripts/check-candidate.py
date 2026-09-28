"""Verify the exact signed candidate and its recorded source inputs before device tests."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / 'validation/candidate.json').read_text())
for name, expected in manifest['sources'].items():
    path = root / name
    assert path.is_relative_to(root) and '..' not in Path(name).parts
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, f'Source changed: {name}; rebuild candidate'
apk = root / manifest['apk']
assert hashlib.sha256(apk.read_bytes()).hexdigest() == manifest['sha256'], 'APK checksum mismatch'
sdk = Path(os.environ['ANDROID_HOME'])
output = subprocess.check_output([str(sdk / 'build-tools/35.0.0/apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
certificate = '4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847'
assert f'Signer #1 certificate SHA-256 digest: {certificate}' in output, 'Wrong signing identity'
print(output)
print(f'Candidate checksum and {len(manifest["sources"])} source inputs verified.')
