CREATE TABLE IF NOT EXISTS bridge_hp_bindings (
 pairing_id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL,
 profile_id TEXT NOT NULL, credential_id TEXT UNIQUE REFERENCES bridge_credentials(id)
);
--> statement-breakpoint
CREATE INDEX IF NOT EXISTS bridge_hp_bindings_scope ON bridge_hp_bindings(owner_id,campaign_id,profile_id);
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS bridge_hp_records (
 owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL, profile_id TEXT NOT NULL,
 revision INTEGER NOT NULL CHECK(revision>=1), fields_json TEXT NOT NULL,
 device_credential_id TEXT NOT NULL REFERENCES bridge_credentials(id),
 published_at INTEGER NOT NULL, last_device_sync_at INTEGER NOT NULL,
 last_heartbeat_id TEXT, last_heartbeat_fingerprint TEXT, last_heartbeat_sent_at INTEGER, last_heartbeat_at INTEGER,
 PRIMARY KEY(owner_id,campaign_id,profile_id)
);
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS bridge_hp_proposals (
 owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL, command_id TEXT NOT NULL,
 profile_id TEXT NOT NULL, expected_revision INTEGER NOT NULL, fingerprint TEXT NOT NULL,
 request_fingerprint TEXT NOT NULL, proposal_json TEXT NOT NULL,
 device_credential_id TEXT NOT NULL REFERENCES bridge_credentials(id), created_at INTEGER NOT NULL,
 PRIMARY KEY(owner_id,campaign_id,command_id)
);
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS bridge_hp_receipts (
 sequence INTEGER PRIMARY KEY AUTOINCREMENT, owner_id TEXT NOT NULL,
 campaign_id TEXT NOT NULL, command_id TEXT NOT NULL, profile_id TEXT NOT NULL,
 fingerprint TEXT NOT NULL, request_fingerprint TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PUBLISHED','APPLIED','REJECTED','CONFLICT')),
 prior_revision INTEGER NOT NULL, resulting_revision INTEGER NOT NULL,
 receipt_json TEXT NOT NULL, fields_json TEXT, device_credential_id TEXT NOT NULL,
 recorded_at INTEGER NOT NULL, UNIQUE(owner_id,campaign_id,command_id)
);
--> statement-breakpoint
CREATE INDEX IF NOT EXISTS bridge_hp_receipts_history ON bridge_hp_receipts(owner_id,campaign_id,profile_id,sequence);
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_hp_receipts_immutable_update BEFORE UPDATE ON bridge_hp_receipts
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_hp_receipts_immutable_delete BEFORE DELETE ON bridge_hp_receipts
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_hp_proposals_immutable_update BEFORE UPDATE ON bridge_hp_proposals
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PROPOSAL'); END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_hp_proposals_immutable_delete BEFORE DELETE ON bridge_hp_proposals
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PROPOSAL'); END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_hp_proposal_guard BEFORE INSERT ON bridge_hp_proposals
 BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM bridge_hp_receipts WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND command_id=NEW.command_id) THEN RAISE(ABORT,'COMMAND_ID_REUSED') END;
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_hp_records WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND profile_id=NEW.profile_id AND revision=NEW.expected_revision AND device_credential_id=NEW.device_credential_id) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_hp_records r JOIN bridge_credentials c ON c.id=r.device_credential_id WHERE r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.profile_id=NEW.profile_id AND c.revoked=0 AND c.expires_at>NEW.created_at AND r.last_device_sync_at>=NEW.created_at-300000) THEN RAISE(ABORT,'DEVICE_SYNC_STALE') END;
  SELECT CASE WHEN (SELECT COUNT(*) FROM bridge_hp_proposals WHERE owner_id=NEW.owner_id)>=10000 THEN RAISE(ABORT,'HISTORY_LIMIT') END;
  SELECT CASE WHEN (SELECT COUNT(*) FROM bridge_hp_proposals p JOIN bridge_credentials c ON c.id=p.device_credential_id LEFT JOIN bridge_hp_receipts r ON r.owner_id=p.owner_id AND r.campaign_id=p.campaign_id AND r.command_id=p.command_id WHERE p.owner_id=NEW.owner_id AND p.campaign_id=NEW.campaign_id AND c.revoked=0 AND c.expires_at>NEW.created_at AND r.command_id IS NULL)>=50 THEN RAISE(ABORT,'PENDING_LIMIT') END;
 END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_hp_receipt_guard BEFORE INSERT ON bridge_hp_receipts
 BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_hp_bindings b JOIN bridge_credentials c ON c.id=b.credential_id WHERE b.owner_id=NEW.owner_id AND b.campaign_id=NEW.campaign_id AND b.profile_id=NEW.profile_id AND b.credential_id=NEW.device_credential_id AND c.revoked=0 AND c.expires_at>NEW.recorded_at) THEN RAISE(ABORT,'HP_CONNECTION_NOT_AUTHORIZED') END;
  SELECT CASE WHEN NEW.status='PUBLISHED' AND EXISTS(SELECT 1 FROM bridge_hp_proposals WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND command_id=NEW.command_id) THEN RAISE(ABORT,'COMMAND_ID_REUSED') END;
  SELECT CASE WHEN NEW.status<>'PUBLISHED' AND NOT EXISTS(SELECT 1 FROM bridge_hp_proposals WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND command_id=NEW.command_id AND profile_id=NEW.profile_id AND fingerprint=NEW.fingerprint AND device_credential_id=NEW.device_credential_id) THEN RAISE(ABORT,'PROPOSAL_MISMATCH') END;
  SELECT CASE WHEN COALESCE((SELECT revision FROM bridge_hp_records WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND profile_id=NEW.profile_id),0)<>NEW.prior_revision THEN RAISE(ABORT,'REVISION_CONFLICT') END;
  SELECT CASE WHEN NEW.status='APPLIED' AND NOT EXISTS(SELECT 1 FROM bridge_hp_proposals WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND command_id=NEW.command_id AND expected_revision=NEW.prior_revision) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
  SELECT CASE WHEN NEW.status='APPLIED' AND (NEW.resulting_revision<>NEW.prior_revision+1 OR NEW.fields_json IS NULL) THEN RAISE(ABORT,'INVALID_REVISION') END;
  SELECT CASE WHEN NEW.status='PUBLISHED' AND (NEW.resulting_revision<NEW.prior_revision OR NEW.resulting_revision<1 OR NEW.fields_json IS NULL) THEN RAISE(ABORT,'INVALID_REVISION') END;
  SELECT CASE WHEN NEW.status='PUBLISHED' AND NEW.resulting_revision=NEW.prior_revision AND NOT EXISTS(SELECT 1 FROM bridge_hp_records WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND profile_id=NEW.profile_id AND fields_json=NEW.fields_json) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
  SELECT CASE WHEN NEW.status IN ('REJECTED','CONFLICT') AND (NEW.resulting_revision<>NEW.prior_revision OR NEW.fields_json IS NOT NULL) THEN RAISE(ABORT,'INVALID_REVISION') END;
 END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_hp_receipt_apply AFTER INSERT ON bridge_hp_receipts
 WHEN NEW.status IN ('PUBLISHED','APPLIED')
 BEGIN
  INSERT INTO bridge_hp_records(owner_id,campaign_id,profile_id,revision,fields_json,device_credential_id,published_at,last_device_sync_at)
  VALUES(NEW.owner_id,NEW.campaign_id,NEW.profile_id,NEW.resulting_revision,NEW.fields_json,NEW.device_credential_id,NEW.recorded_at,NEW.recorded_at)
  ON CONFLICT(owner_id,campaign_id,profile_id) DO UPDATE SET revision=excluded.revision,fields_json=excluded.fields_json,device_credential_id=excluded.device_credential_id,published_at=CASE WHEN bridge_hp_records.revision=excluded.revision THEN bridge_hp_records.published_at ELSE excluded.published_at END,last_device_sync_at=excluded.last_device_sync_at,last_heartbeat_id=NULL,last_heartbeat_fingerprint=NULL,last_heartbeat_sent_at=NULL,last_heartbeat_at=NULL;
 END;
