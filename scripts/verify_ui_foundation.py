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
prompts = manifest.get('authorized_dynamic_prompt_changes', {})
assert sync_review.get('verified_source_baseline') == 'abae4f49beec75d14a364be91f7d0fb4303524ad'
for name, review in sync_review['files'].items():
    fast = manifest.get('authorized_fast_sync_source_changes', {}).get('files', {}).get(name)
    prompt_review = prompts.get('files', {}).get(name)
    expected = fast['previous_pinned_sha256'] if fast else prompt_review['previous_pinned_sha256'] if prompt_review else manifest['files'][name]
    assert review['reviewed_sha256'] == expected and review['previous_pinned_sha256'] != review['reviewed_sha256']
fast = manifest.get('authorized_fast_sync_source_changes', {})
pushy = manifest.get('authorized_pushy_source_changes', {})
if fast:
    assert fast.get('verified_source_baseline') == '045bfaed33393675c8d8d621bf260636deb04dd8'
    for name, review in fast['files'].items():
        successor = pushy.get('files', {}).get(name)
        expected = successor['previous_pinned_sha256'] if successor else manifest['files'][name]
        assert review['reviewed_sha256'] == expected
        assert review['previous_pinned_sha256'] != review['reviewed_sha256']
if pushy:
    assert pushy.get('verified_source_baseline') == 'e00359f6c9f6ad6ecb116937fbe8471536237615'
    assert set(pushy['files']) == {'app/src/main/AndroidManifest.xml'}
    for name, review in pushy['files'].items():
        assert review['reviewed_sha256'] == manifest['files'][name]
        assert review['previous_pinned_sha256'] == fast['files'][name]['reviewed_sha256']
        assert review['previous_pinned_sha256'] != review['reviewed_sha256']

if prompts:
    assert prompts['verified_source_baseline'] == 'bc7fe027cf9670b2df9663cd8d8c02ab2a9afb0c'
    assert set(prompts['files']) == {'app/src/main/java/com/yazsras/astravar/data/CampaignLedger.kt'}
    for name, review in prompts['files'].items():
        assert review['reviewed_sha256'] == manifest['files'][name]
        restored = (root / name).read_text()
        for replacement in review['exact_replacements']:
            assert restored.count(replacement['after']) == 1, 'Exact prompt integration changed'
            restored = restored.replace(replacement['after'], replacement['before'], 1)
        assert hashlib.sha256(restored.encode()).hexdigest() == review['previous_pinned_sha256'], 'Inventory changed beyond prompt integration'
    assert hashlib.sha256((root / 'app/src/main/assets/astravar_chatgpt_prompts.json').read_bytes()).hexdigest() == prompts['source_catalog_sha256'], 'Original prompt seed wording changed'

reviewed = len(manifest.get('authorized_player_first_source_changes', {}).get('files', {}))
print(f"Verified {len(manifest['files'])} pinned foundation files ({reviewed} reviewed 2.1 source changes) and unchanged owner signing block")
