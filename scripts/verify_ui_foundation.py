"""Verify immutable assets/signing and explicitly reviewed 2.1 source digests."""
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
sync_review = manifest.get('authorized_campaign_sync_source_changes', {})
assert sync_review.get('verified_source_baseline') == 'abae4f49beec75d14a364be91f7d0fb4303524ad'
for name, review in sync_review['files'].items():
    assert review['reviewed_sha256'] == manifest['files'][name] and review['previous_pinned_sha256'] != review['reviewed_sha256']

reviewed = len(manifest.get('authorized_player_first_source_changes', {}).get('files', {}))
print(f"Verified {len(manifest['files'])} pinned foundation files ({reviewed} reviewed 2.1 source changes) and unchanged owner signing block")
