"""Fail if the UI reconstruction alters the verified engineering foundation."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'scripts/ui_foundation_sha256.json').read_text())
changed = [name for name, digest in manifest['files'].items()
           if not (root / name).is_file()
           or hashlib.sha256((root / name).read_bytes()).hexdigest() != digest]
gradle = (root / 'app/build.gradle.kts').read_text()
owner = gradle[gradle.index('    signingConfigs {'):gradle.index('    buildTypes {')]
if hashlib.sha256(owner.encode()).hexdigest() != manifest['owner_signing_block_sha256']:
    changed.append('owner signing configuration')
if changed:
    raise SystemExit('Verified foundation changed: ' + ', '.join(changed))
print(f"Preserved {len(manifest['files'])} foundation files and owner signing block from {manifest['base']}")
