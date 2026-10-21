PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS bridge_credentials (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL,
 token_hash TEXT NOT NULL UNIQUE, role TEXT NOT NULL CHECK(role='device'),
 scopes_json TEXT NOT NULL, device_name TEXT NOT NULL, expires_at INTEGER NOT NULL,
 revoked INTEGER NOT NULL DEFAULT 0 CHECK(revoked IN (0,1))
);
CREATE TABLE IF NOT EXISTS bridge_pairings (
 id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL,
 device_code_hash TEXT NOT NULL UNIQUE, user_code_hash TEXT NOT NULL UNIQUE,
 secret_hash TEXT NOT NULL, device_name TEXT NOT NULL, expires_at INTEGER NOT NULL,
 credential_id TEXT REFERENCES bridge_credentials(id), created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS bridge_profiles (
 owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL, profile_id TEXT NOT NULL,
 profile_json TEXT NOT NULL, PRIMARY KEY(owner_id,campaign_id,profile_id)
);
CREATE TABLE IF NOT EXISTS bridge_records (
 owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL, record_id TEXT NOT NULL,
 profile_id TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind='character_ability'),
 revision INTEGER NOT NULL CHECK(revision>=1), fields_json TEXT NOT NULL,
 PRIMARY KEY(owner_id,campaign_id,record_id)
);
CREATE TABLE IF NOT EXISTS bridge_proposals (
 owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL, command_id TEXT NOT NULL,
 record_id TEXT NOT NULL, expected_revision INTEGER NOT NULL,
 fingerprint TEXT NOT NULL, proposal_json TEXT NOT NULL,
 PRIMARY KEY(owner_id,campaign_id,command_id)
);
CREATE TABLE IF NOT EXISTS bridge_receipts (
 sequence INTEGER PRIMARY KEY AUTOINCREMENT,
 owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL, command_id TEXT NOT NULL,
 record_id TEXT NOT NULL, fingerprint TEXT NOT NULL, request_fingerprint TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PUBLISHED','APPLIED','REJECTED','CONFLICT')),
 prior_revision INTEGER NOT NULL, resulting_revision INTEGER NOT NULL,
 receipt_json TEXT NOT NULL, fields_json TEXT,
 UNIQUE(owner_id,campaign_id,command_id)
);
CREATE TRIGGER IF NOT EXISTS bridge_receipts_immutable_update BEFORE UPDATE ON bridge_receipts
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
CREATE TRIGGER IF NOT EXISTS bridge_receipts_immutable_delete BEFORE DELETE ON bridge_receipts
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
CREATE TRIGGER IF NOT EXISTS bridge_proposals_immutable_update BEFORE UPDATE ON bridge_proposals
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PROPOSAL'); END;
CREATE TRIGGER IF NOT EXISTS bridge_proposals_immutable_delete BEFORE DELETE ON bridge_proposals
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PROPOSAL'); END;
CREATE TRIGGER IF NOT EXISTS bridge_proposals_command_guard BEFORE INSERT ON bridge_proposals
 BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM bridge_receipts WHERE owner_id=NEW.owner_id
   AND campaign_id=NEW.campaign_id AND command_id=NEW.command_id)
   THEN RAISE(ABORT,'COMMAND_ID_REUSED') END;
  SELECT CASE WHEN (SELECT COUNT(*) FROM bridge_proposals p LEFT JOIN bridge_receipts r
   ON r.owner_id=p.owner_id AND r.campaign_id=p.campaign_id AND r.command_id=p.command_id
   WHERE p.owner_id=NEW.owner_id AND p.campaign_id=NEW.campaign_id AND r.command_id IS NULL)>=50
   THEN RAISE(ABORT,'PENDING_LIMIT') END;
 END;
CREATE TRIGGER IF NOT EXISTS bridge_receipts_command_guard BEFORE INSERT ON bridge_receipts
 BEGIN
  SELECT CASE WHEN NEW.status='PUBLISHED' AND EXISTS(SELECT 1 FROM bridge_proposals
   WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND command_id=NEW.command_id)
   THEN RAISE(ABORT,'COMMAND_ID_REUSED') END;
  SELECT CASE WHEN NEW.status IN ('APPLIED','REJECTED','CONFLICT') AND NOT EXISTS(
   SELECT 1 FROM bridge_proposals WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id
   AND command_id=NEW.command_id AND record_id=NEW.record_id AND fingerprint=NEW.fingerprint)
   THEN RAISE(ABORT,'PROPOSAL_MISMATCH') END;
  SELECT CASE WHEN NEW.status='APPLIED' AND NOT EXISTS(SELECT 1 FROM bridge_proposals
   WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND command_id=NEW.command_id
   AND expected_revision=NEW.prior_revision) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
  SELECT CASE WHEN NEW.status IN ('REJECTED','CONFLICT') AND (NEW.resulting_revision<>NEW.prior_revision
   OR NEW.fields_json IS NOT NULL) THEN RAISE(ABORT,'INVALID_REVISION') END;
 END;
CREATE TRIGGER IF NOT EXISTS bridge_pairings_quota BEFORE INSERT ON bridge_pairings
 WHEN (SELECT COUNT(*) FROM bridge_pairings WHERE owner_id=NEW.owner_id AND expires_at>NEW.created_at)>=20
 BEGIN SELECT RAISE(ABORT,'PAIRING_LIMIT'); END;
CREATE TRIGGER IF NOT EXISTS bridge_devices_quota BEFORE INSERT ON bridge_credentials
 WHEN (SELECT COUNT(*) FROM bridge_credentials WHERE owner_id=NEW.owner_id AND revoked=0)>=10
 BEGIN SELECT RAISE(ABORT,'DEVICE_LIMIT'); END;
-- The revision guard and mirror write execute inside the same SQLite statement.
-- D1 batch and the local adapter therefore have the same atomic CAS semantics.
CREATE TRIGGER IF NOT EXISTS bridge_receipt_revision_guard BEFORE INSERT ON bridge_receipts
 WHEN NEW.status IN ('APPLIED','PUBLISHED')
 BEGIN
  SELECT CASE WHEN COALESCE((SELECT revision FROM bridge_records WHERE owner_id=NEW.owner_id
   AND campaign_id=NEW.campaign_id AND record_id=NEW.record_id),0)<>NEW.prior_revision
   THEN RAISE(ABORT,'REVISION_CONFLICT') END;
  SELECT CASE WHEN NEW.resulting_revision<=NEW.prior_revision OR NEW.fields_json IS NULL
   THEN RAISE(ABORT,'INVALID_REVISION') END;
 END;
CREATE TRIGGER IF NOT EXISTS bridge_receipt_apply AFTER INSERT ON bridge_receipts
 WHEN NEW.status IN ('APPLIED','PUBLISHED')
 BEGIN
  INSERT INTO bridge_records(owner_id,campaign_id,record_id,profile_id,kind,revision,fields_json)
  VALUES(NEW.owner_id,NEW.campaign_id,NEW.record_id,json_extract(NEW.fields_json,'$.profileId'),
   'character_ability',NEW.resulting_revision,NEW.fields_json)
  ON CONFLICT(owner_id,campaign_id,record_id) DO UPDATE SET revision=excluded.revision,
   fields_json=excluded.fields_json;
 END;
CREATE INDEX IF NOT EXISTS bridge_pending_by_campaign ON bridge_proposals(owner_id,campaign_id);
CREATE INDEX IF NOT EXISTS bridge_receipts_by_campaign ON bridge_receipts(owner_id,campaign_id,sequence);
