"""Verify the recovered engine, migrations, native overlay, fonts and original weapon art."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'scripts/protected_v170_sha256.json').read_text())
failures = []
for relative, expected in manifest['files'].items():
    path = root / relative
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        failures.append(relative)
if failures:
    raise SystemExit('Protected v1.7.0 sources changed: ' + ', '.join(failures))
print(f"Preserved {len(manifest['files'])} protected files from {manifest['base']}")
