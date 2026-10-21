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
# The 2.2 overlay extension is an exact additive delivery loop. Independently
# reconstruct the complete pinned pre-upgrade source to protect every combat line.
sync_review = manifest.get('authorized_campaign_sync_source_changes', {})
assert sync_review.get('verified_source_baseline') == 'abae4f49beec75d14a364be91f7d0fb4303524ad'
overlay = 'app/src/main/java/com/yazsras/astravar/overlay/OverlayService.kt'
entry = sync_review['files'][overlay]
original_overlay = '355b4b5bf6e8fa934eba59c453ddb83d0d11ca1001d34bbf72fa38f032141153'
assert entry['previous_pinned_sha256'] == original_overlay
assert entry['after_removing_exact_insertion_sha256'] == original_overlay
block = entry['exact_authorized_insertion']
assert block.count('\n') == 13
current = (root / overlay).read_text()
fast = manifest.get('authorized_fast_sync_source_changes', {})
if fast:
    assert fast.get('verified_source_baseline') == '045bfaed33393675c8d8d621bf260636deb04dd8'
    # Reverse only the reviewed pause/screen-lock loop changes. The complete
    # previous file must match before applying the original combat-source proof.
    for replacement in fast.get('exact_overlay_replacements', []):
        assert current.count(replacement['after']) == 1, 'Exact authorized sync guard changed'
        current = current.replace(replacement['after'], replacement['before'], 1)
    assert hashlib.sha256(current.encode()).hexdigest() == fast['files'][overlay]['previous_pinned_sha256']
assert current.count(block) == 1, 'Exact authorized foreground sync insertion changed'
assert hashlib.sha256(current.replace(block, '', 1).encode()).hexdigest() == original_overlay, 'Overlay combat/source changed outside the authorized delivery loop'

reviewed = len(manifest.get('authorized_player_first_source_changes', {}).get('files', {}))
print(f"Verified {len(manifest['files'])} protected files ({reviewed} reviewed 2.1 source changes); historical baselines remain in provenance; exact additive overlay proof passes")
