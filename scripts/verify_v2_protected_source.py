"""Verify pinned source evolution, historical migrations, fonts and original art."""
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
reviewed = len(manifest.get('authorized_player_first_source_changes', {}).get('files', {}))
print(f"Verified {len(manifest['files'])} protected files ({reviewed} reviewed 2.1 source changes); historical baselines remain in provenance")
