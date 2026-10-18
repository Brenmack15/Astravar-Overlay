"""Check immutable upgrade boundaries independently of reviewed source pins."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
IMMUTABLE_SCHEMAS = {
    "com.yazsras.astravar.data.AstravarDb/2.json": "d82ab53b42c7f22d3e902f9874fb850e3f165c71639a98b46e65278f7e9f0a78",
    "com.yazsras.astravar.data.AstravarDb/3.json": "9e5e5d180cb4ffbc65623805795dde7e00e71dac707b46ed25db9cafd5de9585",
    "com.yazsras.astravar.data.CampaignLedgerDb/1.json": "8f92801eb01d6ed7d6ff4c5508e9fc4131146fbbcf9a9f8aad92e2596536ec30",
    "com.yazsras.astravar.data.CampaignLedgerDb/2.json": "50a395a8b87a7a119312af1b1edba8246f68aed77be8900baa29a5caff964361",
    "com.yazsras.astravar.data.SandboxSyncDb/1.json": "23de63787037f830e1295ba7cd7efbc7d62cf18e3ba836c4e511bec8fd6cef03",
}
for name, digest in IMMUTABLE_SCHEMAS.items():
    assert hashlib.sha256((ROOT / "app/schemas" / name).read_bytes()).hexdigest() == digest, f"Historical schema changed: {name}"

schema = json.loads((ROOT / "app/schemas/com.yazsras.astravar.data.CampaignLedgerDb/3.json").read_text())["database"]
assert schema["version"] == 3
assert {"ledger_items", "character_profiles", "companion_states", "campaign_audit", "command_receipts",
        "party_sheets", "item_placements", "party_wallets", "campaign_checkpoints", "external_record_references"} <= {e["tableName"] for e in schema["entities"]}

tracked = subprocess.check_output(["git", "-C", str(ROOT), "ls-files", "-z"], text=True).split("\0")
for name in filter(None, tracked):
    assert not re.search(r"\.(?:jks|keystore|p12|apk|aab|pdf|mht|zip)$", name, re.I), f"Private/distribution artifact in source: {name}"
    assert Path(name).name != "party_level6_review.json", "Private review sheet must not enter source control"

gradle = (ROOT / "app/build.gradle.kts").read_text()
assert 'applicationId = "com.yazsras.astravar"' in gradle
for suffix in (".v2uidebug", ".v2statdebug"):
    assert f'applicationIdSuffix = "{suffix}"' in gradle
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
assert 'android:allowBackup="false"' in manifest and 'android:usesCleartextTraffic="false"' in manifest
print("Verified five immutable historical schemas, additive Party schema, package boundaries and private-artifact exclusion")
