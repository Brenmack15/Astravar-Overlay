"""Check immutable upgrade boundaries independently of reviewed source pins."""
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import subprocess

ROOT = Path(__file__).resolve().parents[1]
IMMUTABLE_SCHEMAS = {
    "com.yazsras.astravar.data.AstravarDb/2.json": "d82ab53b42c7f22d3e902f9874fb850e3f165c71639a98b46e65278f7e9f0a78",
    "com.yazsras.astravar.data.AstravarDb/3.json": "9e5e5d180cb4ffbc65623805795dde7e00e71dac707b46ed25db9cafd5de9585",
    "com.yazsras.astravar.data.CampaignLedgerDb/1.json": "8f92801eb01d6ed7d6ff4c5508e9fc4131146fbbcf9a9f8aad92e2596536ec30",
    "com.yazsras.astravar.data.CampaignLedgerDb/2.json": "50a395a8b87a7a119312af1b1edba8246f68aed77be8900baa29a5caff964361",
    "com.yazsras.astravar.data.CampaignLedgerDb/3.json": "d030a6759b35784528732ff331ed20b27cb3c41561db0b7031d839b962f49901",
    "com.yazsras.astravar.data.CampaignLedgerDb/4.json": "076ebbba19127c09137b7095d84cc89bc9fb64c25ab74951677b9d2537cd3c5f",
    "com.yazsras.astravar.data.CampaignLedgerDb/5.json": "055d82545e23397ef2a785aceb6856534b3d1b50ad918cd4e74e2ef87abf7349",
    "com.yazsras.astravar.data.CampaignLedgerDb/6.json": "e6a8c8388f27cf5188d3574206afe2a91645d9843832d0a8e0d6c57b5917faac",
    "com.yazsras.astravar.data.CampaignLedgerDb/7.json": "e72a80deed9cdea4465cc29c5cde44aab50787653585f982e316d53b58ca7470",
    "com.yazsras.astravar.data.CampaignLedgerDb/8.json": "c998677689925257d5c5c385d549688e5cf8d9d4df522720061c5309bdca977c",
    "com.yazsras.astravar.data.ReforgeDb/1.json": "8a289404aa8073ef7cfdf40147cd7bc033ab36f1b73bcd0fab12de70ad471719",
    "com.yazsras.astravar.data.SandboxSyncDb/1.json": "23de63787037f830e1295ba7cd7efbc7d62cf18e3ba836c4e511bec8fd6cef03",
}
for name, digest in IMMUTABLE_SCHEMAS.items():
    assert hashlib.sha256((ROOT / "app/schemas" / name).read_bytes()).hexdigest() == digest, f"Historical schema changed: {name}"

schema = json.loads((ROOT / "app/schemas/com.yazsras.astravar.data.CampaignLedgerDb/3.json").read_text())["database"]
assert schema["version"] == 3
assert {"ledger_items", "character_profiles", "companion_states", "campaign_audit", "command_receipts",
        "party_sheets", "item_placements", "party_wallets", "campaign_checkpoints", "external_record_references"} <= {e["tableName"] for e in schema["entities"]}

# The 2.2 wallet upgrade adds unknown EP/PP balances without rewriting existing records.
previous = json.loads((ROOT / "app/schemas/com.yazsras.astravar.data.CampaignLedgerDb/4.json").read_text())["database"]
latest = json.loads((ROOT / "app/schemas/com.yazsras.astravar.data.CampaignLedgerDb/5.json").read_text())["database"]
assert latest["version"] == 5
before = {e["tableName"]: e for e in previous["entities"]}
after = {e["tableName"]: e for e in latest["entities"]}
assert before.keys() == after.keys(), "Wallet migration must preserve every existing table"
for table, entity in before.items():
    if table != "party_wallets":
        assert entity == after[table], f"Unrelated schema changed: {table}"
        continue
    migrated = after[table]
    for key in ("primaryKey", "indices", "foreignKeys"):
        assert entity[key] == migrated[key], f"Wallet identity or constraints changed: {key}"
    old_fields = {field["columnName"]: field for field in entity["fields"]}
    new_fields = {field["columnName"]: field for field in migrated["fields"]}
    assert new_fields.keys() - old_fields.keys() == {"electrum", "platinum"}
    assert all(new_fields[name] == value for name, value in old_fields.items())
    for name in ("electrum", "platinum"):
        assert new_fields[name].get("affinity") == "INTEGER" and not new_fields[name].get("notNull", False)
        assert "defaultValue" not in new_fields[name], "Unknown balances must not be initialized to zero"

# Prompt settings are additive app-wide rows; all campaign entity definitions stay byte-for-byte equal.
prompt_schema = json.loads((ROOT / "app/schemas/com.yazsras.astravar.data.CampaignLedgerDb/6.json").read_text())["database"]
assert prompt_schema["version"] == 6
prompt_tables = {entity["tableName"]: entity for entity in prompt_schema["entities"]}
assert prompt_tables.keys() - after.keys() == {"chatgpt_prompts", "prompt_catalog_state"}
assert after.keys() <= prompt_tables.keys()
for table, entity in after.items():
    assert entity == prompt_tables[table], f"Existing campaign schema changed during prompt upgrade: {table}"
fields = {field["columnName"] for field in prompt_tables["chatgpt_prompts"]["fields"]}
assert fields == {"id", "name", "description", "content", "category", "createdAt", "updatedAt", "revision"}

# Genesis co-locates playable rows without changing any prior table shape or recovery schema.
playable_schema = json.loads((ROOT / "app/schemas/com.yazsras.astravar.data.ReforgeDb/1.json").read_text())["database"]
playable_tables = {entity["tableName"]: entity for entity in playable_schema["entities"]}
genesis_schema = json.loads((ROOT / "app/schemas/com.yazsras.astravar.data.CampaignLedgerDb/7.json").read_text())["database"]
genesis_tables = {entity["tableName"]: entity for entity in genesis_schema["entities"]}
assert genesis_schema["version"] == 7
assert genesis_tables.keys() == prompt_tables.keys() | playable_tables.keys() | {"genesis_storage_receipt"}
for table, entity in (prompt_tables | playable_tables).items():
    assert genesis_tables[table] == entity, f"Existing table changed during Genesis storage cutover: {table}"
assert {field["columnName"] for field in genesis_tables["genesis_storage_receipt"]["fields"]} == {
    "id", "sourceVersion", "sourceDigest", "countsJson", "completedAt", "authority"}

# Connected authority is additive and starts empty; historical source/playable/installation rows retain exact schema.
authority_schema = json.loads((ROOT / "app/schemas/com.yazsras.astravar.data.CampaignLedgerDb/8.json").read_text())["database"]
assert authority_schema["version"] == 8
authority_tables = {entity["tableName"]: entity for entity in authority_schema["entities"]}
assert authority_tables.keys() - genesis_tables.keys() == {"genesis_campaigns", "genesis_actor_authority", "genesis_item_authority", "genesis_authority_receipts"}
assert genesis_tables.keys() <= authority_tables.keys()
for table, entity in genesis_tables.items():
    assert authority_tables[table] == entity, f"Historical table changed during explicit authority upgrade: {table}"

# Manual encounter tracking adds references only; existing campaign truth and playable tables stay unchanged.
encounter_schema = json.loads((ROOT / "app/schemas/com.yazsras.astravar.data.CampaignLedgerDb/9.json").read_text())["database"]
assert encounter_schema["version"] == 9
encounter_tables = {entity["tableName"]: entity for entity in encounter_schema["entities"]}
assert encounter_tables.keys() == authority_tables.keys() | {"genesis_encounters", "genesis_encounter_participants"}
for table, entity in authority_tables.items():
    assert encounter_tables[table] == entity, f"Historical table changed during encounter upgrade: {table}"

expected_encounter_fields = {
    "genesis_encounters": {
        "id": ("TEXT", True), "campaignId": ("TEXT", True), "title": ("TEXT", True), "notes": ("TEXT", True),
        "status": ("TEXT", True), "round": ("INTEGER", True), "currentActorId": ("TEXT", False),
        "revision": ("INTEGER", True), "creationCommandId": ("TEXT", True), "lastCommandId": ("TEXT", True),
        "createdAt": ("INTEGER", True), "updatedAt": ("INTEGER", True),
    },
    "genesis_encounter_participants": {
        "encounterId": ("TEXT", True), "actorId": ("TEXT", True), "initiative": ("INTEGER", False), "position": ("INTEGER", True),
    },
}
for table, expected_fields in expected_encounter_fields.items():
    entity = encounter_tables[table]
    assert {field["columnName"]: (field["affinity"], field.get("notNull", False)) for field in entity["fields"]} == expected_fields, f"Encounter field shape changed: {table}"
    assert all("defaultValue" not in field for field in entity["fields"]), "Encounter migration must not infer initiative or tracker state"
    assert entity["primaryKey"] == {"autoGenerate": False, "columnNames": ["id"] if table == "genesis_encounters" else ["encounterId", "actorId"]}

def foreign_keys(entity):
    return {(key["table"], key["onDelete"], key["onUpdate"], tuple(key["columns"]), tuple(key["referencedColumns"])) for key in entity["foreignKeys"]}

assert foreign_keys(encounter_tables["genesis_encounters"]) == {
    ("genesis_campaigns", "RESTRICT", "NO ACTION", ("campaignId",), ("id",)),
}, "Encounter campaign identity must be enforced by a non-destructive foreign key"
assert foreign_keys(encounter_tables["genesis_encounter_participants"]) == {
    ("genesis_encounters", "RESTRICT", "NO ACTION", ("encounterId",), ("id",)),
    ("genesis_actor_authority", "RESTRICT", "NO ACTION", ("actorId",), ("actorId",)),
}, "Participants must reference existing encounter and authoritative actor identities"

def indices(entity):
    return {index["name"]: (index["unique"], tuple(index["columnNames"]), tuple(index.get("orders", []))) for index in entity["indices"]}

assert indices(encounter_tables["genesis_encounters"]) == {
    "index_genesis_encounters_campaignId": (False, ("campaignId",), ()),
}
assert indices(encounter_tables["genesis_encounter_participants"]) == {
    "index_genesis_encounter_participants_actorId": (False, ("actorId",), ()),
    "index_genesis_encounter_participants_encounterId_position": (True, ("encounterId", "position"), ()),
}, "Encounter participant order must have a unique indexed position"

# Independently execute the actual additive DDL and compare it with Room's generated schema.
migration = (ROOT / "app/src/main/java/com/yazsras/astravar/data/GenesisEncounterMigration.kt").read_text()
assert "Migration(8, 9)" in migration
statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', migration)
assert len(statements) == 5 and all(statement.startswith(("CREATE TABLE IF NOT EXISTS ", "CREATE INDEX IF NOT EXISTS ", "CREATE UNIQUE INDEX IF NOT EXISTS ")) for statement in statements), "Encounter migration must contain only its five additive table/index statements"

def create_entities(connection, entities):
    for entity in entities:
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            connection.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

def table_shape(connection, table):
    table_info = connection.execute(f'PRAGMA table_info("{table}")').fetchall()
    keys = sorted(connection.execute(f'PRAGMA foreign_key_list("{table}")').fetchall())
    index_list = connection.execute(f'PRAGMA index_list("{table}")').fetchall()
    indexes = sorted((row[1], row[2], row[3], row[4], tuple(connection.execute(f'PRAGMA index_info("{row[1]}")').fetchall())) for row in index_list)
    return table_info, keys, indexes

with sqlite3.connect(":memory:") as migrated, sqlite3.connect(":memory:") as generated:
    create_entities(migrated, authority_schema["entities"])
    original_definitions = migrated.execute("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").fetchall()
    for statement in statements:
        migrated.execute(statement)
    create_entities(generated, encounter_schema["entities"])
    retained = migrated.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name NOT IN ('genesis_encounters','genesis_encounter_participants') ORDER BY type,name").fetchall()
    assert retained == original_definitions, "Encounter migration rewrote a historical schema definition"
    for table in encounter_tables:
        assert table_shape(migrated, table) == table_shape(generated, table), f"Encounter migration differs from generated Room9: {table}"
    for table in expected_encounter_fields:
        assert migrated.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0] == 0, "Migration must not invent encounter records"

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
print("Verified twelve immutable historical schemas, additive wallet/prompt/Genesis/encounter migrations, exact Room9 references and order constraints, unchanged campaign and playable tables, package boundaries and private-artifact exclusion")
