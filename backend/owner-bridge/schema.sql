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
CREATE TABLE IF NOT EXISTS bridge_bootstrap_downloads (
 ticket_hash TEXT PRIMARY KEY, owner_id TEXT NOT NULL, envelope_json TEXT NOT NULL,
 expires_at INTEGER NOT NULL, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS bridge_bootstrap_downloads_owner_expiry
 ON bridge_bootstrap_downloads(owner_id,expires_at);
CREATE TRIGGER IF NOT EXISTS bridge_bootstrap_downloads_quota BEFORE INSERT ON bridge_bootstrap_downloads
 WHEN (SELECT COUNT(*) FROM bridge_bootstrap_downloads WHERE owner_id=NEW.owner_id
  AND expires_at>NEW.created_at)>=8
 BEGIN SELECT RAISE(ABORT,'BOOTSTRAP_DOWNLOAD_LIMIT'); END;
CREATE TABLE IF NOT EXISTS bridge_hp_bindings (
 pairing_id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL,
 profile_id TEXT NOT NULL, credential_id TEXT UNIQUE REFERENCES bridge_credentials(id)
);
CREATE INDEX IF NOT EXISTS bridge_hp_bindings_scope ON bridge_hp_bindings(owner_id,campaign_id,profile_id);
CREATE TABLE IF NOT EXISTS bridge_hp_records (
 owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL, profile_id TEXT NOT NULL,
 revision INTEGER NOT NULL CHECK(revision>=1), fields_json TEXT NOT NULL,
 device_credential_id TEXT NOT NULL REFERENCES bridge_credentials(id),
 published_at INTEGER NOT NULL, last_device_sync_at INTEGER NOT NULL,
 last_heartbeat_id TEXT, last_heartbeat_fingerprint TEXT, last_heartbeat_sent_at INTEGER, last_heartbeat_at INTEGER,
 PRIMARY KEY(owner_id,campaign_id,profile_id)
);
CREATE TABLE IF NOT EXISTS bridge_hp_proposals (
 owner_id TEXT NOT NULL, campaign_id TEXT NOT NULL, command_id TEXT NOT NULL,
 profile_id TEXT NOT NULL, expected_revision INTEGER NOT NULL, fingerprint TEXT NOT NULL,
 request_fingerprint TEXT NOT NULL, proposal_json TEXT NOT NULL,
 device_credential_id TEXT NOT NULL REFERENCES bridge_credentials(id), created_at INTEGER NOT NULL,
 PRIMARY KEY(owner_id,campaign_id,command_id)
);
CREATE TABLE IF NOT EXISTS bridge_hp_receipts (
 sequence INTEGER PRIMARY KEY AUTOINCREMENT, owner_id TEXT NOT NULL,
 campaign_id TEXT NOT NULL, command_id TEXT NOT NULL, profile_id TEXT NOT NULL,
 fingerprint TEXT NOT NULL, request_fingerprint TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('PUBLISHED','APPLIED','REJECTED','CONFLICT')),
 prior_revision INTEGER NOT NULL, resulting_revision INTEGER NOT NULL,
 receipt_json TEXT NOT NULL, fields_json TEXT, device_credential_id TEXT NOT NULL,
 recorded_at INTEGER NOT NULL, UNIQUE(owner_id,campaign_id,command_id)
);
CREATE INDEX IF NOT EXISTS bridge_hp_receipts_history ON bridge_hp_receipts(owner_id,campaign_id,profile_id,sequence);
CREATE TRIGGER IF NOT EXISTS bridge_hp_receipts_immutable_update BEFORE UPDATE ON bridge_hp_receipts
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
CREATE TRIGGER IF NOT EXISTS bridge_hp_receipts_immutable_delete BEFORE DELETE ON bridge_hp_receipts
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
CREATE TRIGGER IF NOT EXISTS bridge_hp_proposals_immutable_update BEFORE UPDATE ON bridge_hp_proposals
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PROPOSAL'); END;
CREATE TRIGGER IF NOT EXISTS bridge_hp_proposals_immutable_delete BEFORE DELETE ON bridge_hp_proposals
 BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PROPOSAL'); END;
CREATE TRIGGER IF NOT EXISTS bridge_hp_proposal_guard BEFORE INSERT ON bridge_hp_proposals
 BEGIN
  SELECT CASE WHEN EXISTS(SELECT 1 FROM bridge_hp_receipts WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND command_id=NEW.command_id) THEN RAISE(ABORT,'COMMAND_ID_REUSED') END;
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_hp_records WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND profile_id=NEW.profile_id AND revision=NEW.expected_revision AND device_credential_id=NEW.device_credential_id) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_hp_records r JOIN bridge_credentials c ON c.id=r.device_credential_id WHERE r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.profile_id=NEW.profile_id AND c.revoked=0 AND c.expires_at>NEW.created_at AND r.last_device_sync_at>=NEW.created_at-300000) THEN RAISE(ABORT,'DEVICE_SYNC_STALE') END;
  SELECT CASE WHEN (SELECT COUNT(*) FROM bridge_hp_proposals WHERE owner_id=NEW.owner_id)>=10000 THEN RAISE(ABORT,'HISTORY_LIMIT') END;
  SELECT CASE WHEN (SELECT COUNT(*) FROM bridge_hp_proposals p JOIN bridge_credentials c ON c.id=p.device_credential_id LEFT JOIN bridge_hp_receipts r ON r.owner_id=p.owner_id AND r.campaign_id=p.campaign_id AND r.command_id=p.command_id WHERE p.owner_id=NEW.owner_id AND p.campaign_id=NEW.campaign_id AND c.revoked=0 AND c.expires_at>NEW.created_at AND r.command_id IS NULL)>=50 THEN RAISE(ABORT,'PENDING_LIMIT') END;
 END;
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
CREATE TRIGGER IF NOT EXISTS bridge_hp_receipt_apply AFTER INSERT ON bridge_hp_receipts
 WHEN NEW.status IN ('PUBLISHED','APPLIED')
 BEGIN
  INSERT INTO bridge_hp_records(owner_id,campaign_id,profile_id,revision,fields_json,device_credential_id,published_at,last_device_sync_at)
  VALUES(NEW.owner_id,NEW.campaign_id,NEW.profile_id,NEW.resulting_revision,NEW.fields_json,NEW.device_credential_id,NEW.recorded_at,NEW.recorded_at)
  ON CONFLICT(owner_id,campaign_id,profile_id) DO UPDATE SET revision=excluded.revision,fields_json=excluded.fields_json,device_credential_id=excluded.device_credential_id,published_at=CASE WHEN bridge_hp_records.revision=excluded.revision THEN bridge_hp_records.published_at ELSE excluded.published_at END,last_device_sync_at=excluded.last_device_sync_at,last_heartbeat_id=NULL,last_heartbeat_fingerprint=NULL,last_heartbeat_sent_at=NULL,last_heartbeat_at=NULL;
 END;

-- Additive V2 owner grant, bounded record mirror and durable command delivery.
CREATE TABLE bridge_campaign_pairings(pairing_id TEXT PRIMARY KEY REFERENCES bridge_pairings(id),read_scopes_json TEXT NOT NULL CHECK(json_valid(read_scopes_json)),write_scopes_json TEXT NOT NULL CHECK(json_valid(write_scopes_json)));
CREATE TABLE bridge_campaign_grants(id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,credential_id TEXT NOT NULL REFERENCES bridge_credentials(id),revision INTEGER NOT NULL DEFAULT 1,read_scopes_json TEXT NOT NULL,write_scopes_json TEXT NOT NULL,auto_scopes_json TEXT NOT NULL DEFAULT '[]',mirror_generation INTEGER NOT NULL DEFAULT 0,expires_at INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL);
CREATE INDEX bridge_campaign_grants_scope ON bridge_campaign_grants(owner_id,campaign_id,credential_id);
CREATE TABLE bridge_campaign_grant_events(owner_id TEXT NOT NULL,command_id TEXT NOT NULL,grant_id TEXT NOT NULL REFERENCES bridge_campaign_grants(id),expected_revision INTEGER NOT NULL,auto_scopes_json TEXT NOT NULL,read_scopes_json TEXT NOT NULL,write_scopes_json TEXT NOT NULL,fingerprint TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(owner_id,command_id));
CREATE TRIGGER bridge_campaign_grant_event_apply BEFORE INSERT ON bridge_campaign_grant_events BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_campaign_grants WHERE id=NEW.grant_id AND owner_id=NEW.owner_id AND revision=NEW.expected_revision AND revoked=0 AND expires_at>NEW.created_at) THEN RAISE(ABORT,'GRANT_REVISION_CONFLICT') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each(NEW.auto_scopes_json) s WHERE s.value NOT IN(SELECT value FROM json_each((SELECT write_scopes_json FROM bridge_campaign_grants WHERE id=NEW.grant_id)))) THEN RAISE(ABORT,'AUTO_SYNC_SCOPE_DENIED') END;
 UPDATE bridge_campaign_grants SET auto_scopes_json=NEW.auto_scopes_json,read_scopes_json=NEW.read_scopes_json,write_scopes_json=NEW.write_scopes_json,revision=revision+1 WHERE id=NEW.grant_id;
END;
CREATE TABLE bridge_campaign_records(owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,kind TEXT NOT NULL,record_id TEXT NOT NULL,revision INTEGER NOT NULL,fields_json TEXT NOT NULL,grant_id TEXT NOT NULL REFERENCES bridge_campaign_grants(id),visibility_revision INTEGER NOT NULL DEFAULT 1,published_at INTEGER NOT NULL,last_device_sync_at INTEGER NOT NULL,PRIMARY KEY(owner_id,campaign_id,kind,record_id));
CREATE INDEX bridge_campaign_records_grant ON bridge_campaign_records(grant_id,kind,record_id);
CREATE TABLE bridge_campaign_publications(owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,command_id TEXT NOT NULL,grant_id TEXT NOT NULL REFERENCES bridge_campaign_grants(id),fingerprint TEXT NOT NULL,records_json TEXT NOT NULL,removed_json TEXT NOT NULL DEFAULT '[]',expected_generation INTEGER,created_at INTEGER NOT NULL,PRIMARY KEY(owner_id,campaign_id,command_id));
CREATE TRIGGER bridge_campaign_publication_apply BEFORE INSERT ON bridge_campaign_publications BEGIN
 SELECT CASE WHEN NEW.expected_generation IS NOT NULL AND NOT EXISTS(SELECT 1 FROM bridge_campaign_grants WHERE id=NEW.grant_id AND mirror_generation=NEW.expected_generation) THEN RAISE(ABORT,'MIRROR_SNAPSHOT_CONFLICT') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each(NEW.removed_json) n LEFT JOIN bridge_campaign_records r ON r.grant_id=NEW.grant_id AND r.kind=json_extract(n.value,'$.kind') AND r.record_id=json_extract(n.value,'$.recordId') WHERE r.revision IS NULL OR r.revision<>json_extract(n.value,'$.expectedRevision')) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
 DELETE FROM bridge_campaign_records WHERE grant_id=NEW.grant_id AND EXISTS(SELECT 1 FROM json_each(NEW.removed_json) n WHERE json_extract(n.value,'$.kind')=bridge_campaign_records.kind AND json_extract(n.value,'$.recordId')=bridge_campaign_records.record_id);
 UPDATE bridge_campaign_grants SET mirror_generation=mirror_generation+1 WHERE id=NEW.grant_id AND (json_array_length(NEW.records_json)>0 OR json_array_length(NEW.removed_json)>0);
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_campaign_grants g JOIN bridge_credentials c ON c.id=g.credential_id WHERE g.id=NEW.grant_id AND g.owner_id=NEW.owner_id AND g.campaign_id=NEW.campaign_id AND g.revoked=0 AND g.expires_at>NEW.created_at AND c.revoked=0 AND c.expires_at>NEW.created_at) THEN RAISE(ABORT,'GRANT_EXPIRED') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each(NEW.records_json) n JOIN bridge_campaign_records r ON r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.kind=json_extract(n.value,'$.kind') AND r.record_id=json_extract(n.value,'$.recordId') WHERE r.revision>json_extract(n.value,'$.revision') OR (r.grant_id=NEW.grant_id AND r.visibility_revision=(SELECT revision FROM bridge_campaign_grants WHERE id=NEW.grant_id) AND r.revision=json_extract(n.value,'$.revision') AND r.fields_json<>json_extract(n.value,'$.fields'))) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
 UPDATE bridge_campaign_records SET last_device_sync_at=NEW.created_at WHERE grant_id=NEW.grant_id AND NEW.expected_generation IS NOT NULL;
 INSERT INTO bridge_campaign_records(owner_id,campaign_id,kind,record_id,revision,fields_json,grant_id,visibility_revision,published_at,last_device_sync_at) SELECT NEW.owner_id,NEW.campaign_id,json_extract(value,'$.kind'),json_extract(value,'$.recordId'),json_extract(value,'$.revision'),json_extract(value,'$.fields'),NEW.grant_id,(SELECT revision FROM bridge_campaign_grants WHERE id=NEW.grant_id),NEW.created_at,NEW.created_at FROM json_each(NEW.records_json) WHERE 1 ON CONFLICT(owner_id,campaign_id,kind,record_id) DO UPDATE SET revision=excluded.revision,fields_json=excluded.fields_json,grant_id=excluded.grant_id,visibility_revision=excluded.visibility_revision,published_at=excluded.published_at,last_device_sync_at=excluded.last_device_sync_at;
END;
CREATE TABLE bridge_campaign_commands(owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,command_id TEXT NOT NULL,grant_id TEXT NOT NULL REFERENCES bridge_campaign_grants(id),grant_revision INTEGER NOT NULL,request_fingerprint TEXT NOT NULL,fingerprint TEXT NOT NULL,command_json TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(owner_id,campaign_id,command_id));
CREATE TRIGGER bridge_campaign_command_validate BEFORE INSERT ON bridge_campaign_commands BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_campaign_grants g JOIN bridge_credentials c ON c.id=g.credential_id WHERE g.id=NEW.grant_id AND g.owner_id=NEW.owner_id AND g.campaign_id=NEW.campaign_id AND g.revision=NEW.grant_revision AND g.revoked=0 AND g.expires_at>NEW.created_at AND c.revoked=0 AND c.expires_at>NEW.created_at) THEN RAISE(ABORT,'GRANT_REVISION_CONFLICT') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each(NEW.command_json,'$.operations') n LEFT JOIN bridge_campaign_records r ON r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.kind=json_extract(n.value,'$.kind') AND r.record_id=json_extract(n.value,'$.recordId') WHERE COALESCE(r.revision,0)<>json_extract(n.value,'$.expectedRevision')) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
 SELECT CASE WHEN (SELECT COUNT(*) FROM bridge_campaign_commands c LEFT JOIN bridge_campaign_receipts r ON r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id WHERE c.grant_id=NEW.grant_id AND c.grant_revision=NEW.grant_revision AND r.command_id IS NULL)>=100 THEN RAISE(ABORT,'PENDING_LIMIT') END;
END;
CREATE TABLE bridge_campaign_receipts(sequence INTEGER PRIMARY KEY AUTOINCREMENT,owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,command_id TEXT NOT NULL,grant_id TEXT NOT NULL REFERENCES bridge_campaign_grants(id),request_fingerprint TEXT NOT NULL,receipt_json TEXT NOT NULL,records_json TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(owner_id,campaign_id,command_id));
CREATE TRIGGER bridge_campaign_receipt_apply BEFORE INSERT ON bridge_campaign_receipts WHEN json_extract(NEW.receipt_json,'$.status')='APPLIED' BEGIN
 UPDATE bridge_campaign_grants SET mirror_generation=mirror_generation+1 WHERE id=NEW.grant_id;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each(NEW.records_json) n LEFT JOIN bridge_campaign_records r ON r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.kind=json_extract(n.value,'$.kind') AND r.record_id=json_extract(n.value,'$.recordId') WHERE COALESCE(r.revision,0)<>json_extract(n.value,'$.revision')-1) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM bridge_campaign_grants g JOIN bridge_credentials d ON d.id=g.credential_id JOIN bridge_campaign_commands c ON c.grant_id=g.id AND c.command_id=NEW.command_id AND c.owner_id=NEW.owner_id AND c.campaign_id=NEW.campaign_id WHERE g.id=NEW.grant_id AND g.revision=c.grant_revision AND g.revoked=0 AND g.expires_at>NEW.created_at AND d.revoked=0 AND d.expires_at>NEW.created_at) THEN RAISE(ABORT,'GRANT_REVISION_CONFLICT') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each((SELECT command_json FROM bridge_campaign_commands WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND command_id=NEW.command_id),'$.operations') n LEFT JOIN bridge_campaign_records r ON r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.kind=json_extract(n.value,'$.kind') AND r.record_id=json_extract(n.value,'$.recordId') WHERE COALESCE(r.revision,0)<>json_extract(n.value,'$.expectedRevision')) THEN RAISE(ABORT,'REVISION_CONFLICT') END;
 INSERT INTO bridge_campaign_records(owner_id,campaign_id,kind,record_id,revision,fields_json,grant_id,visibility_revision,published_at,last_device_sync_at) SELECT NEW.owner_id,NEW.campaign_id,json_extract(value,'$.kind'),json_extract(value,'$.recordId'),json_extract(value,'$.revision'),json_extract(value,'$.fields'),NEW.grant_id,(SELECT revision FROM bridge_campaign_grants WHERE id=NEW.grant_id),NEW.created_at,NEW.created_at FROM json_each(NEW.records_json) WHERE 1 ON CONFLICT(owner_id,campaign_id,kind,record_id) DO UPDATE SET revision=excluded.revision,fields_json=excluded.fields_json,grant_id=excluded.grant_id,visibility_revision=excluded.visibility_revision,published_at=excluded.published_at,last_device_sync_at=excluded.last_device_sync_at;
END;
CREATE TRIGGER bridge_campaign_receipt_immutable_update BEFORE UPDATE ON bridge_campaign_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
CREATE TRIGGER bridge_campaign_receipt_immutable_delete BEFORE DELETE ON bridge_campaign_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_RECEIPT'); END;
CREATE TRIGGER bridge_campaign_command_immutable_update BEFORE UPDATE ON bridge_campaign_commands BEGIN SELECT RAISE(ABORT,'IMMUTABLE_COMMAND'); END;
CREATE TRIGGER bridge_campaign_command_immutable_delete BEFORE DELETE ON bridge_campaign_commands BEGIN SELECT RAISE(ABORT,'IMMUTABLE_COMMAND'); END;
CREATE TRIGGER bridge_campaign_publication_immutable_update BEFORE UPDATE ON bridge_campaign_publications BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PUBLICATION'); END;
CREATE TRIGGER bridge_campaign_publication_immutable_delete BEFORE DELETE ON bridge_campaign_publications BEGIN SELECT RAISE(ABORT,'IMMUTABLE_PUBLICATION'); END;
CREATE TRIGGER bridge_campaign_grant_event_immutable_update BEFORE UPDATE ON bridge_campaign_grant_events BEGIN SELECT RAISE(ABORT,'IMMUTABLE_GRANT_EVENT'); END;
CREATE TRIGGER bridge_campaign_grant_event_immutable_delete BEFORE DELETE ON bridge_campaign_grant_events BEGIN SELECT RAISE(ABORT,'IMMUTABLE_GRANT_EVENT'); END;
CREATE TABLE IF NOT EXISTS bridge_campaign_push_tokens(owner_id TEXT NOT NULL,campaign_id TEXT NOT NULL,grant_id TEXT NOT NULL,credential_id TEXT NOT NULL,token_hash TEXT NOT NULL,token_iv TEXT NOT NULL,token_ciphertext TEXT NOT NULL,notifications_enabled INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,last_push_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(owner_id,campaign_id,credential_id));
CREATE INDEX IF NOT EXISTS bridge_campaign_push_grant ON bridge_campaign_push_tokens(grant_id,campaign_id);

--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_campaign_push_credential_revoke AFTER UPDATE OF revoked ON bridge_credentials WHEN NEW.revoked=1 BEGIN DELETE FROM bridge_campaign_push_tokens WHERE credential_id=NEW.id AND owner_id=NEW.owner_id; END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_campaign_push_grant_revoke AFTER UPDATE OF revoked ON bridge_campaign_grants WHEN NEW.revoked=1 BEGIN DELETE FROM bridge_campaign_push_tokens WHERE grant_id=NEW.id AND owner_id=NEW.owner_id; END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_campaign_push_credential_delete AFTER DELETE ON bridge_credentials BEGIN DELETE FROM bridge_campaign_push_tokens WHERE credential_id=OLD.id AND owner_id=OLD.owner_id; END;
--> statement-breakpoint
CREATE TRIGGER IF NOT EXISTS bridge_campaign_push_grant_delete AFTER DELETE ON bridge_campaign_grants BEGIN DELETE FROM bridge_campaign_push_tokens WHERE grant_id=OLD.id AND owner_id=OLD.owner_id; END;

--> statement-breakpoint
CREATE INDEX IF NOT EXISTS bridge_campaign_session_checkpoint ON bridge_campaign_records(owner_id,campaign_id,grant_id,kind,COALESCE(json_extract(fields_json,'$.updatedAt'),0) DESC,record_id);

CREATE TRIGGER bridge_campaign_acquisition_holder_queue BEFORE INSERT ON bridge_campaign_commands BEGIN
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each(NEW.command_json,'$.operations') n LEFT JOIN bridge_campaign_records r ON r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.grant_id=NEW.grant_id AND r.kind='character-profile' AND r.record_id=json_extract(n.value,'$.fields.placement.holderProfileId') WHERE json_extract(n.value,'$.type')='acquire_item' AND json_type(n.value,'$.fields.placement') IS NOT NULL AND (r.record_id IS NULL OR r.revision<>json_extract(n.value,'$.fields.placement.expectedHolderRevision'))) THEN RAISE(ABORT,'HOLDER_REVISION_CONFLICT') END;
END;
CREATE TRIGGER bridge_campaign_acquisition_holder_receipt BEFORE INSERT ON bridge_campaign_receipts WHEN json_extract(NEW.receipt_json,'$.status')='APPLIED' BEGIN
 SELECT CASE WHEN EXISTS(SELECT 1 FROM json_each((SELECT command_json FROM bridge_campaign_commands WHERE owner_id=NEW.owner_id AND campaign_id=NEW.campaign_id AND command_id=NEW.command_id),'$.operations') n LEFT JOIN bridge_campaign_records r ON r.owner_id=NEW.owner_id AND r.campaign_id=NEW.campaign_id AND r.grant_id=NEW.grant_id AND r.kind='character-profile' AND r.record_id=json_extract(n.value,'$.fields.placement.holderProfileId') WHERE json_extract(n.value,'$.type')='acquire_item' AND json_type(n.value,'$.fields.placement') IS NOT NULL AND (r.record_id IS NULL OR r.revision<>json_extract(n.value,'$.fields.placement.expectedHolderRevision'))) THEN RAISE(ABORT,'HOLDER_REVISION_CONFLICT') END;
END;

CREATE INDEX bridge_campaign_placement_holder ON bridge_campaign_records(owner_id,campaign_id,grant_id,kind,json_extract(fields_json,'$.holderProfileId'),record_id);
--> statement-breakpoint
CREATE INDEX bridge_campaign_property_item ON bridge_campaign_records(owner_id,campaign_id,grant_id,kind,json_extract(fields_json,'$.itemId'),record_id);

--> statement-breakpoint
CREATE INDEX bridge_campaign_companion_resource ON bridge_campaign_records(owner_id,campaign_id,grant_id,kind,json_extract(fields_json,'$.companionId'),record_id);

ALTER TABLE bridge_campaign_publications ADD COLUMN device_build_json TEXT;
--> statement-breakpoint
CREATE INDEX bridge_campaign_verified_publication ON bridge_campaign_publications(grant_id,created_at DESC) WHERE expected_generation IS NOT NULL;

-- Private campaign protocol v1. Existing campaigns acquire no authority or grant
-- from installation of this migration. Their data remains in its original tables.
CREATE TABLE cloud_continuities (
  owner_id TEXT NOT NULL CHECK(length(owner_id) BETWEEN 1 AND 200),
  continuity_id TEXT NOT NULL CHECK(length(continuity_id) BETWEEN 1 AND 200),
  authority TEXT NOT NULL CHECK(authority IN ('legacy','staged','cloud')),
  epoch INTEGER NOT NULL DEFAULT 1 CHECK(typeof(epoch)='integer' AND epoch BETWEEN 1 AND 9007199254740991),
  revision INTEGER NOT NULL DEFAULT 0 CHECK(typeof(revision)='integer' AND revision BETWEEN 0 AND 9007199254740991),
  schema_version INTEGER NOT NULL DEFAULT 1 CHECK(schema_version=1),
  PRIMARY KEY(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TRIGGER cloud_continuity_identity BEFORE UPDATE ON cloud_continuities
WHEN NEW.owner_id IS NOT OLD.owner_id OR NEW.continuity_id IS NOT OLD.continuity_id
  OR NEW.revision<OLD.revision OR NEW.epoch<OLD.epoch OR NEW.epoch>OLD.epoch+1
  OR (NEW.authority<>OLD.authority AND NEW.epoch<>OLD.epoch+1)
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_EPOCH'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_continuity_no_replacement BEFORE INSERT ON cloud_continuities
WHEN EXISTS(SELECT 1 FROM cloud_continuities WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id)
BEGIN SELECT RAISE(ABORT,'CLOUD_CONTINUITY_EXISTS'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_continuity_no_delete BEFORE DELETE ON cloud_continuities
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
-- The discriminator in the compound parent key enforces an actor-typed FK
-- even for out-of-order imports. An actor references its own stable identity.
CREATE TABLE cloud_entities (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  id TEXT NOT NULL CHECK(length(id) BETWEEN 1 AND 200),
  kind TEXT NOT NULL CHECK(length(kind) BETWEEN 1 AND 80 AND kind NOT GLOB '*[^a-z0-9_]*'),
  actor_id TEXT,
  title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 1000),
  data_json TEXT NOT NULL CHECK(json_valid(data_json) AND json_type(data_json)='object'),
  life TEXT NOT NULL DEFAULT 'active' CHECK(life IN ('active','archived')),
  revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision BETWEEN 1 AND 9007199254740991),
  actor_kind TEXT GENERATED ALWAYS AS (CASE WHEN actor_id IS NOT NULL THEN 'actor' END) VIRTUAL,
  PRIMARY KEY(owner_id,continuity_id,id),
  UNIQUE(owner_id,continuity_id,id,kind),
  CHECK(kind<>'actor' OR (actor_id IS NOT NULL AND actor_id=id)),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id),
  FOREIGN KEY(owner_id,continuity_id,actor_id,actor_kind)
    REFERENCES cloud_entities(owner_id,continuity_id,id,kind) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_entities_by_kind ON cloud_entities(owner_id,continuity_id,kind,life,id);
--> statement-breakpoint
CREATE INDEX cloud_entities_by_actor ON cloud_entities(owner_id,continuity_id,actor_id,kind,life,id);
--> statement-breakpoint
CREATE TRIGGER cloud_entity_identity BEFORE UPDATE ON cloud_entities
WHEN NEW.owner_id IS NOT OLD.owner_id OR NEW.continuity_id IS NOT OLD.continuity_id
  OR NEW.id IS NOT OLD.id OR NEW.kind IS NOT OLD.kind OR NEW.revision<=OLD.revision
BEGIN SELECT RAISE(ABORT,'CLOUD_ENTITY_REVISION'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_entity_insert_revision BEFORE INSERT ON cloud_entities
WHEN EXISTS(SELECT 1 FROM cloud_entities WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id
  AND id=NEW.id AND (kind<>NEW.kind OR revision>=NEW.revision))
BEGIN SELECT RAISE(ABORT,'CLOUD_ENTITY_REVISION'); END;
--> statement-breakpoint
CREATE TABLE cloud_links (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  from_id TEXT NOT NULL,
  relation TEXT NOT NULL CHECK(length(relation) BETWEEN 1 AND 80 AND relation NOT GLOB '*[^a-z0-9_]*'),
  to_id TEXT NOT NULL,
  PRIMARY KEY(owner_id,continuity_id,from_id,relation,to_id),
  FOREIGN KEY(owner_id,continuity_id,from_id) REFERENCES cloud_entities(owner_id,continuity_id,id) DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY(owner_id,continuity_id,to_id) REFERENCES cloud_entities(owner_id,continuity_id,id) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_links_to ON cloud_links(owner_id,continuity_id,to_id,relation,from_id);
--> statement-breakpoint
CREATE TABLE cloud_grants (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  subject TEXT NOT NULL CHECK(length(subject) BETWEEN 1 AND 240),
  scopes_json TEXT NOT NULL CHECK(json_valid(scopes_json) AND json_type(scopes_json)='array'),
  actors_json TEXT NOT NULL CHECK(json_valid(actors_json) AND json_type(actors_json)='array'),
  auto_json TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(auto_json) AND json_type(auto_json)='array'),
  expires_at INTEGER NOT NULL CHECK(typeof(expires_at)='integer' AND expires_at BETWEEN 0 AND 9007199254740991),
  revoked INTEGER NOT NULL DEFAULT 0 CHECK(revoked IN (0,1)),
  revision INTEGER NOT NULL DEFAULT 1 CHECK(typeof(revision)='integer' AND revision BETWEEN 1 AND 9007199254740991),
  PRIMARY KEY(owner_id,continuity_id,subject),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_identity BEFORE UPDATE ON cloud_grants
WHEN NEW.owner_id IS NOT OLD.owner_id OR NEW.continuity_id IS NOT OLD.continuity_id OR NEW.subject IS NOT OLD.subject
  OR NEW.revision<>OLD.revision+1
BEGIN SELECT RAISE(ABORT,'CLOUD_GRANT_REVISION'); END;
--> statement-breakpoint
-- Grant scope sets must remain structurally valid even after a reviewed import.
-- Individual actor IDs can be granted before an authorized actor creation.
CREATE VIEW cloud_invalid_grants AS
SELECT g.owner_id,g.continuity_id,g.subject FROM cloud_grants g
WHERE EXISTS(SELECT 1 FROM json_each(g.scopes_json) s WHERE s.type<>'text'
  OR (s.value NOT GLOB '?*.read' AND s.value NOT GLOB '?*.write')
  OR (s.value GLOB '*.write' AND substr(s.value,1,length(s.value)-6)||'.read' NOT IN (SELECT value FROM json_each(g.scopes_json))))
  OR EXISTS(SELECT 1 FROM json_each(g.auto_json) a WHERE a.type<>'text' OR a.value NOT GLOB '?*.write'
    OR a.value NOT IN (SELECT value FROM json_each(g.scopes_json)))
  OR EXISTS(SELECT 1 FROM json_each(g.actors_json) a WHERE a.type<>'text' OR length(a.value) NOT BETWEEN 1 AND 200)
  OR EXISTS(SELECT 1 FROM json_each(g.actors_json) a WHERE a.value='*' AND json_array_length(g.actors_json)<>1)
  OR (SELECT count(DISTINCT value) FROM json_each(g.scopes_json))<>json_array_length(g.scopes_json)
  OR (SELECT count(DISTINCT value) FROM json_each(g.auto_json))<>json_array_length(g.auto_json)
  OR (SELECT count(DISTINCT value) FROM json_each(g.actors_json))<>json_array_length(g.actors_json);
--> statement-breakpoint
CREATE TRIGGER cloud_grant_validate_insert AFTER INSERT ON cloud_grants
WHEN EXISTS(SELECT 1 FROM cloud_invalid_grants WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND subject=NEW.subject)
BEGIN SELECT RAISE(ABORT,'INVALID_CLOUD_GRANT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_validate_update AFTER UPDATE ON cloud_grants
WHEN EXISTS(SELECT 1 FROM cloud_invalid_grants WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND subject=NEW.subject)
BEGIN SELECT RAISE(ABORT,'INVALID_CLOUD_GRANT'); END;
--> statement-breakpoint
CREATE TABLE cloud_requests (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  command_id TEXT NOT NULL CHECK(length(command_id) BETWEEN 1 AND 200),
  subject TEXT NOT NULL CHECK(length(subject) BETWEEN 1 AND 240),
  digest TEXT NOT NULL CHECK(length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*'),
  request_json TEXT NOT NULL CHECK(json_valid(request_json) AND json_type(request_json)='object'),
  access_json TEXT NOT NULL CHECK(json_valid(access_json) AND json_type(access_json)='array'),
  status TEXT NOT NULL CHECK(status IN ('PENDING_APPROVAL','COMMITTED','REFUSED','CONFLICT','SUPERSEDED','FAILED')),
  submitted_at INTEGER NOT NULL CHECK(typeof(submitted_at)='integer' AND submitted_at>=0),
  terminal_command_id TEXT GENERATED ALWAYS AS (CASE WHEN status<>'PENDING_APPROVAL' THEN command_id END) VIRTUAL,
  PRIMARY KEY(owner_id,continuity_id,command_id),
  UNIQUE(owner_id,continuity_id,command_id,status),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id),
  FOREIGN KEY(owner_id,continuity_id,terminal_command_id)
    REFERENCES cloud_receipts(owner_id,continuity_id,command_id) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_pending_requests ON cloud_requests(owner_id,continuity_id,submitted_at,command_id)
  WHERE status='PENDING_APPROVAL';
--> statement-breakpoint
CREATE TRIGGER cloud_request_no_replacement BEFORE INSERT ON cloud_requests
WHEN EXISTS(SELECT 1 FROM cloud_requests WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND command_id=NEW.command_id)
BEGIN SELECT RAISE(ABORT,'CLOUD_COMMAND_EXISTS'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_request_immutable BEFORE UPDATE ON cloud_requests
WHEN NEW.owner_id IS NOT OLD.owner_id OR NEW.continuity_id IS NOT OLD.continuity_id OR NEW.command_id IS NOT OLD.command_id
  OR NEW.subject IS NOT OLD.subject OR NEW.digest IS NOT OLD.digest OR NEW.request_json IS NOT OLD.request_json
  OR NEW.access_json IS NOT OLD.access_json OR NEW.submitted_at IS NOT OLD.submitted_at
  OR OLD.status<>'PENDING_APPROVAL' OR NEW.status='PENDING_APPROVAL'
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_REQUEST'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_request_no_delete BEFORE DELETE ON cloud_requests
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_REQUEST'); END;
--> statement-breakpoint
CREATE TABLE cloud_receipts (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  command_id TEXT NOT NULL,
  transaction_id TEXT NOT NULL CHECK(length(transaction_id) BETWEEN 1 AND 200),
  status TEXT NOT NULL CHECK(status IN ('COMMITTED','REFUSED','CONFLICT','SUPERSEDED','FAILED')),
  epoch INTEGER NOT NULL CHECK(typeof(epoch)='integer' AND epoch BETWEEN 1 AND 9007199254740991),
  revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision BETWEEN 0 AND 9007199254740991),
  result_json TEXT NOT NULL CHECK(json_valid(result_json) AND json_type(result_json)='object'),
  access_json TEXT NOT NULL CHECK(json_valid(access_json) AND json_type(access_json)='array'),
  created_at INTEGER NOT NULL CHECK(typeof(created_at)='integer' AND created_at>=0),
  PRIMARY KEY(owner_id,continuity_id,command_id),
  UNIQUE(owner_id,continuity_id,transaction_id),
  FOREIGN KEY(owner_id,continuity_id,command_id,status)
    REFERENCES cloud_requests(owner_id,continuity_id,command_id,status) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_receipts_by_revision ON cloud_receipts(owner_id,continuity_id,revision,command_id);
--> statement-breakpoint
CREATE TRIGGER cloud_receipt_no_replacement BEFORE INSERT ON cloud_receipts
WHEN EXISTS(SELECT 1 FROM cloud_receipts WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id
  AND (command_id=NEW.command_id OR transaction_id=NEW.transaction_id))
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_RECEIPT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_receipt_no_update BEFORE UPDATE ON cloud_receipts
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_RECEIPT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_receipt_no_delete BEFORE DELETE ON cloud_receipts
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_RECEIPT'); END;
--> statement-breakpoint
CREATE TABLE cloud_changes (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision BETWEEN 1 AND 9007199254740991),
  ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal>=0),
  entity_id TEXT NOT NULL CHECK(length(entity_id) BETWEEN 1 AND 200),
  kind TEXT NOT NULL CHECK(length(kind) BETWEEN 1 AND 80),
  actor_id TEXT,
  change_type TEXT NOT NULL CHECK(change_type IN ('upsert','delete')),
  PRIMARY KEY(owner_id,continuity_id,revision,ordinal),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_changes_by_actor ON cloud_changes(owner_id,continuity_id,actor_id,revision,ordinal);
--> statement-breakpoint
CREATE TRIGGER cloud_change_no_replacement BEFORE INSERT ON cloud_changes
WHEN EXISTS(SELECT 1 FROM cloud_changes WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id
  AND revision=NEW.revision AND ordinal=NEW.ordinal)
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_CHANGE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_change_no_update BEFORE UPDATE ON cloud_changes
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_CHANGE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_change_no_delete BEFORE DELETE ON cloud_changes
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_CHANGE'); END;
--> statement-breakpoint
CREATE TABLE cloud_tombstones (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  id TEXT NOT NULL CHECK(length(id) BETWEEN 1 AND 200),
  kind TEXT NOT NULL CHECK(length(kind) BETWEEN 1 AND 80),
  deleted_revision INTEGER NOT NULL CHECK(typeof(deleted_revision)='integer' AND deleted_revision BETWEEN 1 AND 9007199254740991),
  PRIMARY KEY(owner_id,continuity_id,id),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TRIGGER cloud_tombstone_insert BEFORE INSERT ON cloud_tombstones
WHEN EXISTS(SELECT 1 FROM cloud_tombstones WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND id=NEW.id)
  OR EXISTS(SELECT 1 FROM cloud_entities WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND id=NEW.id
    AND (kind<>NEW.kind OR revision>=NEW.deleted_revision))
BEGIN SELECT RAISE(ABORT,'CLOUD_TOMBSTONE_CONFLICT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_tombstone_no_update BEFORE UPDATE ON cloud_tombstones
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_TOMBSTONE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_tombstone_no_delete BEFORE DELETE ON cloud_tombstones
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_TOMBSTONE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_entity_block_resurrection BEFORE INSERT ON cloud_entities
WHEN EXISTS(SELECT 1 FROM cloud_tombstones WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND id=NEW.id)
BEGIN SELECT RAISE(ABORT,'DELETED_CLOUD_IDENTITY'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_entity_require_tombstone BEFORE DELETE ON cloud_entities
WHEN NOT EXISTS(SELECT 1 FROM cloud_tombstones WHERE owner_id=OLD.owner_id AND continuity_id=OLD.continuity_id AND id=OLD.id AND kind=OLD.kind)
BEGIN SELECT RAISE(ABORT,'CLOUD_TOMBSTONE_REQUIRED'); END;
--> statement-breakpoint
CREATE TABLE cloud_guards (ok INTEGER NOT NULL CHECK(ok=1));
--> statement-breakpoint
CREATE TRIGGER cloud_fence_v2_submit BEFORE INSERT ON bridge_campaign_commands
WHEN EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_v2_publish BEFORE INSERT ON bridge_campaign_publications
WHEN (json_array_length(NEW.records_json)>0 OR json_array_length(NEW.removed_json)>0) AND EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_v2_decide BEFORE INSERT ON bridge_campaign_receipts
WHEN json_extract(NEW.receipt_json,'$.status')='APPLIED' AND EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_hp_submit BEFORE INSERT ON bridge_hp_proposals
WHEN EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_hp_decide BEFORE INSERT ON bridge_hp_receipts
WHEN NEW.status IN ('APPLIED','PUBLISHED') AND EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_v1_submit BEFORE INSERT ON bridge_proposals
WHEN EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_fence_v1_decide BEFORE INSERT ON bridge_receipts
WHEN NEW.status IN ('APPLIED','PUBLISHED') AND EXISTS(SELECT 1 FROM cloud_continuities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.campaign_id AND authority='cloud')
BEGIN SELECT RAISE(ABORT,'CLOUD_AUTHORITY_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_enroll_existing BEFORE INSERT ON cloud_continuities
WHEN NEW.authority='cloud' AND (
  EXISTS(SELECT 1 FROM bridge_campaign_records WHERE owner_id=NEW.owner_id AND campaign_id=NEW.continuity_id)
  OR EXISTS(SELECT 1 FROM bridge_hp_records WHERE owner_id=NEW.owner_id AND campaign_id=NEW.continuity_id)
  OR EXISTS(SELECT 1 FROM bridge_records WHERE owner_id=NEW.owner_id AND campaign_id=NEW.continuity_id))
BEGIN SELECT RAISE(ABORT,'STAGED_MIGRATION_REQUIRED'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_enroll_pending BEFORE INSERT ON cloud_continuities
WHEN NEW.authority='cloud' AND (
  EXISTS(SELECT 1 FROM bridge_campaign_commands c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_campaign_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)) OR EXISTS(SELECT 1 FROM bridge_hp_proposals c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_hp_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)) OR EXISTS(SELECT 1 FROM bridge_proposals c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)))
BEGIN SELECT RAISE(ABORT,'LEGACY_COMMAND_PENDING'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_cutover_pending BEFORE UPDATE OF authority ON cloud_continuities
WHEN OLD.authority<>'cloud' AND NEW.authority='cloud' AND (
  EXISTS(SELECT 1 FROM bridge_campaign_commands c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_campaign_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)) OR EXISTS(SELECT 1 FROM bridge_hp_proposals c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_hp_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)) OR EXISTS(SELECT 1 FROM bridge_proposals c WHERE c.owner_id=NEW.owner_id AND c.campaign_id=NEW.continuity_id
    AND NOT EXISTS(SELECT 1 FROM bridge_receipts r WHERE r.owner_id=c.owner_id AND r.campaign_id=c.campaign_id AND r.command_id=c.command_id)))
BEGIN SELECT RAISE(ABORT,'LEGACY_COMMAND_PENDING'); END;

-- An upload is a scoped, temporary candidate. Its reservation, a binding to
-- exact bytes, is separate from the owner-reviewed primary image transaction.
CREATE TABLE cloud_media_uploads (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL,
  upload_id TEXT NOT NULL CHECK(length(upload_id) BETWEEN 1 AND 128),
  subject TEXT NOT NULL, epoch INTEGER NOT NULL CHECK(epoch>=1),
  target_id TEXT NOT NULL, target_kind TEXT NOT NULL, target_revision INTEGER NOT NULL CHECK(target_revision>=1),
  actor_id TEXT, digest TEXT NOT NULL CHECK(length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*'),
  metadata_json TEXT NOT NULL CHECK(json_valid(metadata_json) AND json_type(metadata_json)='object'),
  state TEXT NOT NULL CHECK(state IN ('uploading','ready','cleaning')),
  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL CHECK(expires_at>created_at),
  PRIMARY KEY(owner_id,continuity_id,upload_id),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_media_subject_time ON cloud_media_uploads(owner_id,continuity_id,subject,created_at);
--> statement-breakpoint
CREATE INDEX cloud_media_expiry ON cloud_media_uploads(owner_id,continuity_id,expires_at,upload_id);
--> statement-breakpoint
CREATE INDEX cloud_media_assets_by_upload ON cloud_entities(owner_id,continuity_id,json_extract(data_json,'$.uploadId'))
  WHERE kind='media_asset';
--> statement-breakpoint
CREATE UNIQUE INDEX cloud_one_primary_image ON cloud_links(owner_id,continuity_id,from_id) WHERE relation='primary_image';
--> statement-breakpoint
CREATE TRIGGER cloud_media_quota BEFORE INSERT ON cloud_media_uploads
WHEN (SELECT count(*) FROM cloud_media_uploads WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id
  AND subject=NEW.subject AND created_at>NEW.created_at-3600000)>=32
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_UPLOAD_LIMIT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_media_reservation_immutable BEFORE UPDATE ON cloud_media_uploads
WHEN NEW.owner_id IS NOT OLD.owner_id OR NEW.continuity_id IS NOT OLD.continuity_id OR NEW.upload_id IS NOT OLD.upload_id
  OR NEW.subject IS NOT OLD.subject OR NEW.epoch IS NOT OLD.epoch OR NEW.target_id IS NOT OLD.target_id
  OR NEW.target_kind IS NOT OLD.target_kind OR NEW.target_revision IS NOT OLD.target_revision OR NEW.actor_id IS NOT OLD.actor_id
  OR NEW.digest IS NOT OLD.digest OR NEW.metadata_json IS NOT OLD.metadata_json OR NEW.created_at IS NOT OLD.created_at
  OR NEW.expires_at IS NOT OLD.expires_at OR (OLD.state='cleaning' AND NEW.state<>'cleaning')
  OR (OLD.state='ready' AND NEW.state='uploading')
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_RESERVATION_CONFLICT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_media_asset_validate BEFORE INSERT ON cloud_entities
WHEN NEW.kind='media_asset' AND EXISTS(SELECT 1 FROM cloud_continuities WHERE owner_id=NEW.owner_id
    AND continuity_id=NEW.continuity_id AND authority='cloud')
  AND NOT EXISTS(SELECT 1 FROM cloud_media_uploads u JOIN cloud_continuities c
    ON c.owner_id=u.owner_id AND c.continuity_id=u.continuity_id AND c.epoch=u.epoch
    WHERE u.owner_id=NEW.owner_id AND u.continuity_id=NEW.continuity_id AND u.state='ready'
    AND u.upload_id=json_extract(NEW.data_json,'$.uploadId') AND u.digest=json_extract(NEW.data_json,'$.sha256')
    AND u.actor_id IS NEW.actor_id
    AND json_extract(u.metadata_json,'$.thumbnailSha256')=json_extract(NEW.data_json,'$.thumbnailSha256'))
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_BYTES_UNAVAILABLE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_media_version_immutable BEFORE UPDATE ON cloud_entities
WHEN OLD.kind='media_asset' AND (OLD.data_json<>NEW.data_json OR OLD.actor_id IS NOT NEW.actor_id)
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_MEDIA_VERSION'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_media_link_kind BEFORE INSERT ON cloud_links
WHEN NEW.relation='primary_image' AND NOT EXISTS(SELECT 1 FROM cloud_entities
  WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND id=NEW.to_id AND kind='media_asset' AND life='active')
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_ASSET_REQUIRED'); END;

CREATE TABLE cloud_media_previews (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL, upload_id TEXT NOT NULL,
  digest TEXT NOT NULL, target_id TEXT NOT NULL, target_kind TEXT NOT NULL, target_revision INTEGER NOT NULL,
  requested_at INTEGER NOT NULL,
  PRIMARY KEY(owner_id,continuity_id,upload_id),
  FOREIGN KEY(owner_id,continuity_id,upload_id) REFERENCES cloud_media_uploads(owner_id,continuity_id,upload_id) ON DELETE CASCADE
) WITHOUT ROWID;
--> statement-breakpoint
-- A cleanup may begin while a connector prepares its proposal. Validate the
-- exact candidate again atomically when saving a pending approval request.
CREATE TRIGGER cloud_media_request_stage BEFORE INSERT ON cloud_requests
WHEN NEW.status='PENDING_APPROVAL' AND json_extract(NEW.request_json,'$.command.operation')='assign_primary_image'
  AND NOT EXISTS(SELECT 1 FROM cloud_media_uploads u
    WHERE u.owner_id=NEW.owner_id AND u.continuity_id=NEW.continuity_id AND u.state='ready'
    AND u.upload_id=json_extract(NEW.request_json,'$.command.args.uploadId')
    AND u.digest=json_extract(NEW.request_json,'$.command.args.sha256')
    AND u.target_id=json_extract(NEW.request_json,'$.command.args.targetId')
    AND u.target_kind=json_extract(NEW.request_json,'$.command.args.targetKind')
    AND u.epoch=json_extract(NEW.request_json,'$.command.epoch'))
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_CANDIDATE_CHANGED'); END;

-- Command and receipt headers keep their immutable identity/digests. Detailed
-- evidence is split by record so deletion can retain unrelated transaction legs.
CREATE TABLE cloud_evidence_segments (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL, command_id TEXT NOT NULL, part TEXT NOT NULL,
  digest TEXT NOT NULL CHECK(length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*'),
  payload_json TEXT NOT NULL CHECK(json_valid(payload_json)),
  PRIMARY KEY(owner_id,continuity_id,command_id,part),
  FOREIGN KEY(owner_id,continuity_id,command_id) REFERENCES cloud_requests(owner_id,continuity_id,command_id) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TABLE cloud_evidence_subjects (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL, command_id TEXT NOT NULL, part TEXT NOT NULL, entity_id TEXT NOT NULL,
  PRIMARY KEY(owner_id,continuity_id,command_id,part,entity_id),
  FOREIGN KEY(owner_id,continuity_id,command_id,part) REFERENCES cloud_evidence_segments(owner_id,continuity_id,command_id,part)
    ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_evidence_by_entity ON cloud_evidence_subjects(owner_id,continuity_id,entity_id,command_id,part);
--> statement-breakpoint
CREATE TABLE cloud_evidence_erased (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL, command_id TEXT NOT NULL, part TEXT NOT NULL,
  digest TEXT NOT NULL, entity_id TEXT NOT NULL, deleted_revision INTEGER NOT NULL,
  PRIMARY KEY(owner_id,continuity_id,command_id,part),
  FOREIGN KEY(owner_id,continuity_id,command_id) REFERENCES cloud_requests(owner_id,continuity_id,command_id),
  FOREIGN KEY(owner_id,continuity_id,entity_id) REFERENCES cloud_tombstones(owner_id,continuity_id,id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TRIGGER cloud_evidence_no_update BEFORE UPDATE ON cloud_evidence_segments
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_EVIDENCE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_evidence_no_replacement BEFORE INSERT ON cloud_evidence_segments
WHEN EXISTS(SELECT 1 FROM cloud_evidence_segments WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND command_id=NEW.command_id AND part=NEW.part)
  OR EXISTS(SELECT 1 FROM cloud_evidence_erased WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND command_id=NEW.command_id AND part=NEW.part)
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_EVIDENCE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_evidence_reviewed_purge BEFORE DELETE ON cloud_evidence_segments
BEGIN
  SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM cloud_evidence_subjects s JOIN cloud_tombstones t
    ON t.owner_id=s.owner_id AND t.continuity_id=s.continuity_id AND t.id=s.entity_id
    WHERE s.owner_id=OLD.owner_id AND s.continuity_id=OLD.continuity_id AND s.command_id=OLD.command_id AND s.part=OLD.part)
  THEN RAISE(ABORT,'CLOUD_EVIDENCE_TOMBSTONE_REQUIRED') END;
  INSERT INTO cloud_evidence_erased(owner_id,continuity_id,command_id,part,digest,entity_id,deleted_revision)
    SELECT OLD.owner_id,OLD.continuity_id,OLD.command_id,OLD.part,OLD.digest,t.id,t.deleted_revision
    FROM cloud_evidence_subjects s JOIN cloud_tombstones t ON t.owner_id=s.owner_id AND t.continuity_id=s.continuity_id AND t.id=s.entity_id
    WHERE s.owner_id=OLD.owner_id AND s.continuity_id=OLD.continuity_id AND s.command_id=OLD.command_id AND s.part=OLD.part ORDER BY t.id LIMIT 1;
END;
--> statement-breakpoint
CREATE TRIGGER cloud_evidence_marker_no_update BEFORE UPDATE ON cloud_evidence_erased
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_EVIDENCE'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_evidence_marker_no_delete BEFORE DELETE ON cloud_evidence_erased
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_CLOUD_EVIDENCE'); END;
--> statement-breakpoint
CREATE TABLE cloud_media_gc (
  owner_id TEXT NOT NULL, continuity_id TEXT NOT NULL, command_id TEXT NOT NULL, upload_id TEXT NOT NULL, digest TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('pending','removed')), PRIMARY KEY(owner_id,continuity_id,command_id,upload_id),
  FOREIGN KEY(owner_id,continuity_id,command_id) REFERENCES cloud_requests(owner_id,continuity_id,command_id) DEFERRABLE INITIALLY DEFERRED
) WITHOUT ROWID;
--> statement-breakpoint
CREATE TRIGGER cloud_media_retired_reservation BEFORE INSERT ON cloud_media_uploads
WHEN EXISTS(SELECT 1 FROM cloud_media_gc g WHERE g.owner_id=NEW.owner_id AND g.continuity_id=NEW.continuity_id AND g.upload_id=NEW.upload_id)
BEGIN SELECT RAISE(ABORT,'CLOUD_MEDIA_RETIRED_ID'); END;
-- Security history remains separate from gameplay revision and from effective grants.
CREATE TABLE cloud_grant_audit (
  owner_id TEXT NOT NULL,
  continuity_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  epoch INTEGER NOT NULL,
  subject TEXT NOT NULL,
  grant_revision INTEGER NOT NULL,
  scopes_json TEXT NOT NULL CHECK(json_valid(scopes_json)),
  actors_json TEXT NOT NULL CHECK(json_valid(actors_json)),
  auto_json TEXT NOT NULL CHECK(json_valid(auto_json)),
  expires_at INTEGER NOT NULL, revoked INTEGER NOT NULL CHECK(revoked IN (0,1)),
  recorded_at INTEGER NOT NULL,
  PRIMARY KEY(owner_id,continuity_id,event_id),
  FOREIGN KEY(owner_id,continuity_id) REFERENCES cloud_continuities(owner_id,continuity_id)
) WITHOUT ROWID;
--> statement-breakpoint
CREATE INDEX cloud_grant_audit_by_time ON cloud_grant_audit(owner_id,continuity_id,recorded_at,event_id);
--> statement-breakpoint
CREATE TRIGGER cloud_grant_log_insert AFTER INSERT ON cloud_grants
BEGIN
 INSERT INTO cloud_grant_audit(owner_id,continuity_id,event_id,epoch,subject,grant_revision,scopes_json,actors_json,auto_json,expires_at,revoked,recorded_at)
 SELECT NEW.owner_id,NEW.continuity_id,lower(hex(randomblob(16))),c.epoch,NEW.subject,NEW.revision,NEW.scopes_json,NEW.actors_json,NEW.auto_json,NEW.expires_at,NEW.revoked,CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)
 FROM cloud_continuities c WHERE c.owner_id=NEW.owner_id AND c.continuity_id=NEW.continuity_id;
END;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_log_update AFTER UPDATE ON cloud_grants
BEGIN
 INSERT INTO cloud_grant_audit(owner_id,continuity_id,event_id,epoch,subject,grant_revision,scopes_json,actors_json,auto_json,expires_at,revoked,recorded_at)
 SELECT NEW.owner_id,NEW.continuity_id,lower(hex(randomblob(16))),c.epoch,NEW.subject,NEW.revision,NEW.scopes_json,NEW.actors_json,NEW.auto_json,NEW.expires_at,NEW.revoked,CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)
 FROM cloud_continuities c WHERE c.owner_id=NEW.owner_id AND c.continuity_id=NEW.continuity_id;
END;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_log_no_update BEFORE UPDATE ON cloud_grant_audit
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_GRANT_AUDIT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_log_no_delete BEFORE DELETE ON cloud_grant_audit
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_GRANT_AUDIT'); END;
--> statement-breakpoint
CREATE TRIGGER cloud_grant_log_no_replace BEFORE INSERT ON cloud_grant_audit
WHEN EXISTS(SELECT 1 FROM cloud_grant_audit WHERE owner_id=NEW.owner_id AND continuity_id=NEW.continuity_id AND event_id=NEW.event_id)
BEGIN SELECT RAISE(ABORT,'IMMUTABLE_GRANT_AUDIT'); END;
